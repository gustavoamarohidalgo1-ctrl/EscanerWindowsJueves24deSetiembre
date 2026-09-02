package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** Confianza de una comparación local contra compras ya registradas. */
enum class PurchaseDuplicateKind {
    EXACT,
    PROBABLE,
    DISTINCT,
}

/** Señales explicables que llevaron a clasificar una coincidencia. */
enum class PurchaseDuplicateReason {
    SAME_BUSINESS,
    SAME_SUPPLIER,
    SAME_DOCUMENT_TYPE,
    SAME_DOCUMENT_NUMBER,
    CORRELATIVE_PADDING_VARIANT,
    SAME_SERIES,
    SAME_ISSUE_DATE,
    SAME_TOTAL,
    SAME_IMAGE_HASH,
}

/** Compra durable mínima que puede mostrarse antes de publicar otra. */
data class RecordedPurchase(
    val purchaseId: PurchaseId,
    val businessId: BusinessId,
    val sourceDraftId: DraftId,
    val supplierId: SupplierId,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val documentType: PurchaseDocumentType,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: LocalDate,
    val currency: CurrencyCode,
    val total: Money,
    val status: PurchaseStatus,
    val imageHashes: Set<String> = emptySet(),
) {
    val canonicalDocumentNumber: String
        get() = "$documentSeries-$documentNumber"
}

/** Datos de la instantánea preparada usados para buscar duplicados sin conectividad. */
data class PurchaseDuplicateProbe(
    val draftId: DraftId,
    /** Identidad de la instantánea sobre la que se ejecutó este preflight. */
    val preparedLogicalHash: String,
    val businessId: BusinessId,
    val supplierId: SupplierId?,
    val supplierRuc: String,
    val documentType: PurchaseDocumentType?,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: LocalDate,
    val currency: CurrencyCode,
    val total: Money,
    val imageHashes: Set<String>,
    val reconciliationAdjustment: PurchaseReconciliationAdjustment? = null,
) {
    init {
        require(PREPARED_HASH.matches(preparedLogicalHash)) {
            "preparedLogicalHash debe ser SHA-256 hex en minúsculas"
        }
        require(
            reconciliationAdjustment == null ||
                reconciliationAdjustment.amount.currency == currency,
        ) { "El ajuste preparado debe compartir la moneda de la compra" }
    }

    private companion object {
        val PREPARED_HASH = Regex("[0-9a-f]{64}")
    }
}

data class PurchaseDuplicateMatch(
    val kind: PurchaseDuplicateKind,
    val purchase: RecordedPurchase,
    val reasons: Set<PurchaseDuplicateReason>,
) {
    init {
        require(kind != PurchaseDuplicateKind.DISTINCT) {
            "Una coincidencia no puede clasificarse como DISTINCT"
        }
        require(reasons.isNotEmpty()) { "Una coincidencia necesita señales explicables" }
    }
}

data class PurchaseDuplicateAssessment(
    val probe: PurchaseDuplicateProbe,
    val match: PurchaseDuplicateMatch?,
) {
    val kind: PurchaseDuplicateKind
        get() = match?.kind ?: PurchaseDuplicateKind.DISTINCT
}

/** Rol local capaz de autorizar una excepción de duplicado. */
enum class PurchaseOverrideRole {
    OWNER,
    MANAGER,
    OPERATOR,
}

data class PurchaseOverrideActor(
    val actorId: String,
    val role: PurchaseOverrideRole,
) {
    init {
        require(actorId.isNotBlank() && actorId.length <= 128) { "actorId inválido" }
    }

    val canOverrideDuplicate: Boolean
        get() = role == PurchaseOverrideRole.OWNER || role == PurchaseOverrideRole.MANAGER

    /** Las anulaciones alteran stock y requieren el mismo nivel responsable que una excepción. */
    val canVoidPurchase: Boolean
        get() = role == PurchaseOverrideRole.OWNER || role == PurchaseOverrideRole.MANAGER
}

data class PurchaseDuplicateOverride(
    val draftId: DraftId,
    /** Hash exacto sobre el que el responsable autorizó la excepción. */
    val preparedLogicalHash: String,
    val businessId: BusinessId,
    val existingPurchaseId: PurchaseId,
    val duplicateKind: PurchaseDuplicateKind,
    val reasons: Set<PurchaseDuplicateReason>,
    val reason: String,
    val actor: PurchaseOverrideActor,
) {
    init {
        require(duplicateKind == PurchaseDuplicateKind.EXACT) {
            "Solo una coincidencia exacta necesita una excepción autorizada"
        }
        require(reasons.isNotEmpty())
        require(PREPARED_HASH.matches(preparedLogicalHash)) {
            "preparedLogicalHash debe ser SHA-256 hex en minúsculas"
        }
        require(reason == reason.trim() && reason.length in MIN_REASON_LENGTH..MAX_REASON_LENGTH) {
            "El motivo debe tener entre $MIN_REASON_LENGTH y $MAX_REASON_LENGTH caracteres"
        }
        require(actor.canOverrideDuplicate) { "El rol no autoriza excepciones de duplicado" }
    }

    companion object {
        const val MIN_REASON_LENGTH = 10
        const val MAX_REASON_LENGTH = 500
        private val PREPARED_HASH = Regex("[0-9a-f]{64}")
    }
}

/**
 * Normalización exclusiva de identidad documental. NFKC, mayúsculas y eliminación de espacios,
 * guiones y otros separadores hacen que diferencias de formato no eludan la detección. Los ceros
 * del correlativo se conservan para la coincidencia exacta.
 */
object PurchaseDuplicateCanonicalizer {
    fun documentType(value: String): String = alphaNumeric(value)

    fun series(value: String): String = alphaNumeric(value)

    fun correlative(value: String): String = alphaNumeric(value)

    fun ruc(value: String?): String? = value
        ?.let(::alphaNumeric)
        ?.takeIf(String::isNotEmpty)

    fun withoutLeftPadding(value: String): String =
        value.trimStart('0').ifEmpty { "0" }

    private fun alphaNumeric(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .uppercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}

/** Clasificador puro: el hash de imagen nunca puede producir una coincidencia por sí solo. */
class PurchaseDuplicateDetector {
    fun assess(
        probe: PurchaseDuplicateProbe,
        candidates: List<RecordedPurchase>,
    ): PurchaseDuplicateAssessment {
        val matches = candidates.mapNotNull { candidate -> classify(probe, candidate) }
        val best = matches.maxWithOrNull(
            compareBy<PurchaseDuplicateMatch> { it.kind.rank }
                .thenBy { it.reasons.size }
                .thenBy { it.purchase.issueDate },
        )
        return PurchaseDuplicateAssessment(probe = probe, match = best)
    }

    private fun classify(
        probe: PurchaseDuplicateProbe,
        candidate: RecordedPurchase,
    ): PurchaseDuplicateMatch? {
        if (probe.businessId != candidate.businessId) return null
        if (!sameSupplier(probe, candidate)) return null
        if (probe.documentType == null || probe.documentType != candidate.documentType) return null

        val common = mutableSetOf(
            PurchaseDuplicateReason.SAME_BUSINESS,
            PurchaseDuplicateReason.SAME_SUPPLIER,
            PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
        )
        val probeSeries = PurchaseDuplicateCanonicalizer.series(probe.documentSeries)
        val probeCorrelative = PurchaseDuplicateCanonicalizer.correlative(probe.documentNumber)
        val candidateSeries = PurchaseDuplicateCanonicalizer.series(candidate.documentSeries)
        val candidateCorrelative = PurchaseDuplicateCanonicalizer.correlative(candidate.documentNumber)
        if (
            probeSeries.isNotEmpty() &&
            probeCorrelative.isNotEmpty() &&
            probeSeries == candidateSeries &&
            probeCorrelative == candidateCorrelative
        ) {
            common += PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER
            return PurchaseDuplicateMatch(
                kind = PurchaseDuplicateKind.EXACT,
                purchase = candidate,
                reasons = common,
            )
        }

        val sameSeries = probeSeries.isNotEmpty() && probeSeries == candidateSeries
        val paddingVariant = sameSeries && probeCorrelative.isNotEmpty() &&
            PurchaseDuplicateCanonicalizer.withoutLeftPadding(probeCorrelative) ==
            PurchaseDuplicateCanonicalizer.withoutLeftPadding(candidateCorrelative)
        val sameDate = probe.issueDate == candidate.issueDate
        val sameTotal = probe.currency == candidate.currency && probe.total == candidate.total
        val sameImage = probe.imageHashes.isNotEmpty() &&
            candidate.imageHashes.any(probe.imageHashes::contains)

        if (sameSeries) common += PurchaseDuplicateReason.SAME_SERIES
        if (paddingVariant) common += PurchaseDuplicateReason.CORRELATIVE_PADDING_VARIANT
        if (sameDate) common += PurchaseDuplicateReason.SAME_ISSUE_DATE
        if (sameTotal) common += PurchaseDuplicateReason.SAME_TOTAL
        if (sameImage) common += PurchaseDuplicateReason.SAME_IMAGE_HASH

        val probable = paddingVariant ||
            (sameSeries && sameDate && sameTotal) ||
            (sameImage && (sameDate || sameTotal))
        return if (probable) {
            PurchaseDuplicateMatch(
                kind = PurchaseDuplicateKind.PROBABLE,
                purchase = candidate,
                reasons = common,
            )
        } else {
            null
        }
    }

    private fun sameSupplier(
        probe: PurchaseDuplicateProbe,
        candidate: RecordedPurchase,
    ): Boolean {
        if (probe.supplierId != null && probe.supplierId == candidate.supplierId) return true
        val probeRuc = PurchaseDuplicateCanonicalizer.ruc(probe.supplierRuc)
        val candidateRuc = PurchaseDuplicateCanonicalizer.ruc(candidate.supplierRuc)
        return probeRuc != null && probeRuc == candidateRuc
    }

    private val PurchaseDuplicateKind.rank: Int
        get() = when (this) {
            PurchaseDuplicateKind.EXACT -> 2
            PurchaseDuplicateKind.PROBABLE -> 1
            PurchaseDuplicateKind.DISTINCT -> 0
        }
}
