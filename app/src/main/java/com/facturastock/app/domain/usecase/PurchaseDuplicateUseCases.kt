package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.PurchaseDuplicateAssessment
import com.facturastock.app.domain.model.PurchaseDuplicateDetector
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.PurchaseDuplicateProbe
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import com.facturastock.app.domain.repository.PurchaseRepository
import kotlinx.coroutines.flow.first

class CheckPurchaseDuplicateUseCase(
    private val preparedPurchaseRepository: PreparedPurchaseRepository,
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val purchaseRepository: PurchaseRepository,
    private val detector: PurchaseDuplicateDetector = PurchaseDuplicateDetector(),
) {
    suspend operator fun invoke(draftId: DraftId): PurchaseDuplicateAssessment {
        val prepared = requireNotNull(preparedPurchaseRepository.find(draftId)) {
            "No existe una compra preparada para el borrador"
        }
        val imageHashes = invoiceDraftRepository.observeImages(draftId).first()
            .mapTo(linkedSetOf()) { image -> image.sha256 }
        val document = requireNotNull(
            InvoiceDocumentNumber.parseCanonical(prepared.documentNumber),
        ) { "El comprobante preparado no tiene una serie y correlativo canónicos" }
        val probe = PurchaseDuplicateProbe(
            draftId = draftId,
            preparedLogicalHash = prepared.logicalHash,
            businessId = prepared.businessId,
            supplierId = prepared.supplierId,
            supplierRuc = prepared.supplierRuc,
            documentType = prepared.documentType,
            documentSeries = document.series,
            documentNumber = document.correlative,
            issueDate = prepared.issueDate,
            currency = prepared.currency,
            total = prepared.total,
            imageHashes = imageHashes,
            reconciliationAdjustment = prepared.reconciliationAdjustment,
        )
        val candidates = prepared.documentType?.let { documentType ->
            purchaseRepository.findDuplicateCandidates(
                businessId = prepared.businessId,
                supplierId = prepared.supplierId,
                supplierRuc = prepared.supplierRuc,
                documentType = documentType,
            )
        }.orEmpty()
        return detector.assess(probe, candidates)
    }
}

sealed interface AuthorizeDuplicateOverrideResult {
    /** Excepción revalidada que todavía no fue auditada ni comprometida. */
    data class Authorized(
        val override: PurchaseDuplicateOverride,
    ) : AuthorizeDuplicateOverrideResult

    data object InvalidReason : AuthorizeDuplicateOverrideResult
    data object Unauthorized : AuthorizeDuplicateOverrideResult
    data object StaleMatch : AuthorizeDuplicateOverrideResult
}

class AuthorizePurchaseDuplicateOverrideUseCase(
    private val checkDuplicate: CheckPurchaseDuplicateUseCase,
    private val authorizationRepository: PurchaseOverrideAuthorizationRepository,
) {
    suspend operator fun invoke(
        draftId: DraftId,
        expectedPreparedLogicalHash: String,
        expectedPurchaseId: PurchaseId,
        reason: String,
    ): AuthorizeDuplicateOverrideResult {
        val trimmedReason = reason.trim()
        if (trimmedReason.length !in PurchaseDuplicateOverride.MIN_REASON_LENGTH..
            PurchaseDuplicateOverride.MAX_REASON_LENGTH
        ) {
            return AuthorizeDuplicateOverrideResult.InvalidReason
        }
        val assessment = checkDuplicate(draftId)
        if (assessment.probe.preparedLogicalHash != expectedPreparedLogicalHash) {
            return AuthorizeDuplicateOverrideResult.StaleMatch
        }
        val match = assessment.match
            ?.takeIf { it.purchase.purchaseId == expectedPurchaseId }
            ?: return AuthorizeDuplicateOverrideResult.StaleMatch
        if (match.kind != PurchaseDuplicateKind.EXACT) {
            return AuthorizeDuplicateOverrideResult.StaleMatch
        }
        val actor = authorizationRepository.currentActor(assessment.probe.businessId)
            ?.takeIf { it.canOverrideDuplicate }
            ?: return AuthorizeDuplicateOverrideResult.Unauthorized
        return AuthorizeDuplicateOverrideResult.Authorized(
            override = PurchaseDuplicateOverride(
                draftId = draftId,
                preparedLogicalHash = assessment.probe.preparedLogicalHash,
                businessId = assessment.probe.businessId,
                existingPurchaseId = expectedPurchaseId,
                duplicateKind = match.kind,
                reasons = match.reasons,
                reason = trimmedReason,
                actor = actor,
            ),
        )
    }
}

class FindPurchaseUseCase(private val purchaseRepository: PurchaseRepository) {
    suspend operator fun invoke(purchaseId: PurchaseId) = purchaseRepository.findById(purchaseId)
}
