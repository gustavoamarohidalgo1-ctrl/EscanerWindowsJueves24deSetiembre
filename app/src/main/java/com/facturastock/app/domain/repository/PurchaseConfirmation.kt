package com.facturastock.app.domain.repository

import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.InventoryCostingDecisionReason
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.CurrencyCode

/** Orden estable de confirmación, ligada a la instantánea que el usuario revisó. */
data class ConfirmPurchaseCommand(
    val draftId: DraftId,
    /** SHA-256 lógico mostrado en el resumen; evita publicar una preparación más reciente. */
    val expectedPreparedLogicalHash: String,
    /** Excepción EXACT revalidada; solo el commit atómico puede hacerla durable. */
    val duplicateOverride: PurchaseDuplicateOverride? = null,
) {
    init {
        require(PREPARED_HASH.matches(expectedPreparedLogicalHash)) {
            "expectedPreparedLogicalHash debe ser SHA-256 hex en minúsculas"
        }
    }

    private companion object {
        val PREPARED_HASH = Regex("[0-9a-f]{64}")
    }
}

/**
 * Configuración capturada por el caso de uso para esta confirmación.
 *
 * El repositorio resuelve desde la instantánea preparada y el catálogo las decisiones por
 * línea, pero nunca debe leer de nuevo el negocio activo ni la política de costos durante el
 * commit. De este modo puede validar pertenencia y congelar la política realmente aplicada.
 */
data class PurchaseConfirmationContext(
    val activeBusinessId: BusinessId,
    val costPolicy: CostPolicy,
    /** Consentimiento y política capturados antes de entrar a la transacción de publicación. */
    val backupEnabled: Boolean = false,
    val documentBackupEnabled: Boolean = false,
    val imageRetentionPolicy: ImageRetentionPolicy = ImageRetentionPolicy.KEEP,
)

/** Motivo de dominio que impide confirmar sin haber escrito un grafo parcial. */
sealed interface PurchaseConfirmationBlocker {
    /** La aplicación no tiene un negocio real o demo activo. */
    data object NoActiveBusiness : PurchaseConfirmationBlocker

    /** El borrador solicitado ya no existe. */
    data object DraftNotFound : PurchaseConfirmationBlocker

    /** El borrador existe, pero no está congelado en READY_TO_POST. */
    data object DraftNotReady : PurchaseConfirmationBlocker

    /** El borrador dice estar preparado, pero no existe su instantánea inmutable. */
    data object PreparedPurchaseMissing : PurchaseConfirmationBlocker

    /** La instantánea no conserva un proveedor de catálogo publicable. */
    data object SupplierMissing : PurchaseConfirmationBlocker

    /** El proveedor congelado fue eliminado, archivado o pertenece a otro negocio. */
    data class SupplierUnavailable(
        val supplierId: SupplierId,
    ) : PurchaseConfirmationBlocker

    /** El producto congelado fue eliminado, archivado o pertenece a otro negocio. */
    data class ProductUnavailable(
        val lineId: LineId,
        val productId: ProductId,
    ) : PurchaseConfirmationBlocker

    /** Un producto creado en el borrador no puede publicarse sin precio por unidad confirmado. */
    data class SalePriceRequired(
        val lineId: LineId,
        val productId: ProductId,
    ) : PurchaseConfirmationBlocker

    /** El producto de la línea no tiene un destino de inventario publicable. */
    data class LocationMissing(
        val lineId: LineId,
        val productId: ProductId,
    ) : PurchaseConfirmationBlocker

    /** Un saldo existente no puede cambiar de moneda durante una entrada de compra. */
    data class BalanceCurrencyMismatch(
        val lineId: LineId,
        val expected: CurrencyCode,
        val actual: CurrencyCode,
    ) : PurchaseConfirmationBlocker

    /**
     * La evidencia revisada no basta para congelar el tratamiento tributario y el costo de la
     * línea. [reasons] conserva las decisiones concretas producidas por el motor de costos.
     */
    data class TaxDecisionRequired(
        val lineId: LineId,
        val reasons: Set<InventoryCostingDecisionReason>,
    ) : PurchaseConfirmationBlocker {
        init {
            require(reasons.isNotEmpty()) {
                "TaxDecisionRequired necesita al menos un motivo"
            }
        }
    }

    /** La reversión contable de notas de crédito todavía no está disponible. */
    data object CreditNoteUnsupported : PurchaseConfirmationBlocker

    /** El borrador preparado no pertenece al negocio activo capturado por el caso de uso. */
    data class BusinessMismatch(
        val activeBusinessId: BusinessId,
        val preparedBusinessId: BusinessId,
    ) : PurchaseConfirmationBlocker
}

/** Resultado cerrado de confirmar; los conflictos esperables nunca se filtran como excepciones. */
sealed interface ConfirmPurchaseResult {
    /** La llamada creó y publicó el grafo durable completo. */
    data class Posted(val purchaseId: PurchaseId) : ConfirmPurchaseResult

    /**
     * El mismo comando ya se había comprometido. Es un éxito idempotente y debe abrir la misma
     * compra, sin repetir movimientos, auditoría ni outbox.
     */
    data class AlreadyPosted(val purchaseId: PurchaseId) : ConfirmPurchaseResult

    /** Otro borrador ya publicó exactamente el mismo comprobante. */
    data class ExactDuplicate(val existingPurchaseId: PurchaseId) : ConfirmPurchaseResult

    /** Hay uno o más bloqueos accionables y no se escribió ningún artefacto del posting. */
    data class Blocked(
        val reasons: Set<PurchaseConfirmationBlocker>,
    ) : ConfirmPurchaseResult {
        init {
            require(reasons.isNotEmpty()) { "Blocked necesita al menos un motivo" }
        }
    }

    /** La instantánea preparada cambió; debe volver a inspeccionarse antes de confirmar. */
    data object PreparedChanged : ConfirmPurchaseResult

    /** Una carrera transitoria revirtió el commit completo y permite reintentar el comando. */
    data object RetryableConflict : ConfirmPurchaseResult
}

/**
 * Puerto del posting durable. La implementación debe comprometer compra, líneas, saldos,
 * movimientos, auditoría, outbox y enlace del borrador en una sola transacción; además debe
 * convertir la repetición del mismo comando en [ConfirmPurchaseResult.AlreadyPosted].
 */
interface PurchasePostingRepository {
    suspend fun confirm(
        command: ConfirmPurchaseCommand,
        context: PurchaseConfirmationContext,
    ): ConfirmPurchaseResult
}
