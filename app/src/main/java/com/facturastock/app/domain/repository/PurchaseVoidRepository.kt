package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseVoidImpact
import com.facturastock.app.domain.model.PurchaseVoidPreview
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId

/** Orden autorizada que la implementación debe revalidar dentro de la transacción Room. */
data class PurchaseVoidCommand(
    val businessId: BusinessId,
    val purchaseId: PurchaseId,
    val reason: String,
    val actor: PurchaseOverrideActor,
    val expectedImpactHash: String,
) {
    init {
        require(reason == reason.trim()) { "reason debe estar recortado" }
        require(reason.length in PurchaseVoidRequest.MIN_REASON_LENGTH..
            PurchaseVoidRequest.MAX_REASON_LENGTH
        ) { "reason no cumple la longitud permitida" }
        require(actor.canVoidPurchase) { "El actor no puede anular compras" }
        require(IMPACT_HASH.matches(expectedImpactHash)) {
            "expectedImpactHash debe ser SHA-256 hex en minúsculas"
        }
    }

    private companion object {
        val IMPACT_HASH = Regex("[0-9a-f]{64}")
    }
}

/** Entrada de UI: deliberadamente no incluye actor ni rol. */
data class PurchaseVoidRequest(
    val purchaseId: PurchaseId,
    val reason: String,
    val expectedImpactHash: String,
    val confirmed: Boolean,
) {
    companion object {
        const val MIN_REASON_LENGTH: Int = 10
        const val MAX_REASON_LENGTH: Int = 500
    }
}

sealed interface PreviewPurchaseVoidResult {
    data class Ready(val preview: PurchaseVoidPreview) : PreviewPurchaseVoidResult
    data class AlreadyVoided(val purchaseId: PurchaseId) : PreviewPurchaseVoidResult
    data class NotPosted(
        val purchaseId: PurchaseId,
        val status: PurchaseStatus,
    ) : PreviewPurchaseVoidResult
    data object NoActiveBusiness : PreviewPurchaseVoidResult
    data object NotFound : PreviewPurchaseVoidResult
    data object Unauthorized : PreviewPurchaseVoidResult
    /** El agregado publicado no tiene un libro/proyección consistente para revertir. */
    data object RetryableConflict : PreviewPurchaseVoidResult
}

sealed interface PurchaseVoidResult {
    data class Voided(
        val purchaseId: PurchaseId,
        val negativeImpacts: List<PurchaseVoidImpact>,
    ) : PurchaseVoidResult {
        init {
            require(negativeImpacts.all(PurchaseVoidImpact::becomesNegative))
        }
    }

    /** Éxito idempotente: una llamada anterior ya escribió toda la reversa. */
    data class AlreadyVoided(val purchaseId: PurchaseId) : PurchaseVoidResult
    data object ConfirmationRequired : PurchaseVoidResult
    data object InvalidReason : PurchaseVoidResult
    data object NoActiveBusiness : PurchaseVoidResult
    data object NotFound : PurchaseVoidResult
    data class NotPosted(
        val purchaseId: PurchaseId,
        val status: PurchaseStatus,
    ) : PurchaseVoidResult
    data object Unauthorized : PurchaseVoidResult
    /** El saldo/libro cambió desde el preview; exige mostrar y confirmar el impacto nuevo. */
    data class ImpactChanged(val newPreview: PurchaseVoidPreview) : PurchaseVoidResult
    data object RetryableConflict : PurchaseVoidResult
}

interface PurchaseVoidRepository {
    suspend fun preview(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        actor: PurchaseOverrideActor,
    ): PreviewPurchaseVoidResult

    /**
     * Marca VOIDED, compensa stock, actualiza proyecciones, audita y genera outbox en un único
     * commit. Repetir el comando debe devolver [PurchaseVoidResult.AlreadyVoided].
     */
    suspend fun void(command: PurchaseVoidCommand): PurchaseVoidResult
}
