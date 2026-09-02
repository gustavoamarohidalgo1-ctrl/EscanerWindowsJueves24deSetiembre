package com.facturastock.app.feature.summary

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PurchaseReconciliationAdjustment
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

/**
 * Contrato de presentación del resumen de compra. Dos modos excluyentes: edición (borrador
 * revisable, enumera bloqueos y permite preparar) y preparado (instantánea congelada lista para
 * registrar). La pantalla nunca valida: los bloqueos y la instantánea los produce el dominio.
 */
object PurchaseSummaryContract {
    enum class Mode {
        EDITING,
        PREPARED,
    }

    enum class Failure {
        INVALID_ROUTE,
        LOAD_FAILED,
        ACTION_FAILED,
        STORAGE_FULL,
    }

    /**
     * Bloqueo listo para mostrar. [linePosition] es el ordinal 1-based de la línea entre las
     * líneas con bloqueos: el dominio emite los bloqueos de línea en orden de posición, pero la
     * inspección no expone las posiciones absolutas.
     */
    @Immutable
    data class BlockerItem(
        val code: PrepareBlockerCode,
        val linePosition: Int? = null,
        val detail: String? = null,
    )

    /** Totales ya formateados para mostrar; cadena vacía significa dato no disponible. */
    @Immutable
    data class FinanceSummary(
        val lineSum: String = "",
        val invoiceTotal: String = "",
        val difference: String = "",
        val hasDifference: Boolean = false,
    )

    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val isLoading: Boolean = true,
        val mode: Mode = Mode.EDITING,
        /** Bloqueos evaluados sin la aceptación de redondeo; la UI filtra según el checkbox. */
        val blockers: List<BlockerItem> = emptyList(),
        /** Diferencia de redondeo exacta ya formateada; null si la compra cuadra. */
        val roundingDifference: String? = null,
        val roundingAccepted: Boolean = false,
        /** Motivo durable del ajuste; se conserva mientras la pantalla rota o se recrea. */
        val adjustmentReason: String = "",
        val finance: FinanceSummary? = null,
        val prepared: PreparedPurchase? = null,
        val isBusy: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val visibleBlockers: List<BlockerItem>
            get() = blockers.filterNot { blocker ->
                (roundingAccepted && blocker.code == PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED) ||
                    (
                        PurchaseReconciliationAdjustment.isValidReason(adjustmentReason) &&
                            blocker.code == PrepareBlockerCode.ADJUSTMENT_REASON_REQUIRED
                        )
            }

        /** Solo se prepara sin bloqueos, o cuando el único pendiente es la aceptación marcada. */
        val canPrepare: Boolean
            get() = mode == Mode.EDITING &&
                !isLoading &&
                !isBusy &&
                failure == null &&
                blockers.all { blocker ->
                    blocker.code in setOf(
                        PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED,
                        PrepareBlockerCode.ADJUSTMENT_REASON_REQUIRED,
                    )
                } &&
                (
                    roundingDifference == null ||
                        (
                            roundingAccepted &&
                                PurchaseReconciliationAdjustment.isValidReason(adjustmentReason)
                            )
                    )
    }

    sealed interface Action : UiAction {
        data object Start : Action

        data object Retry : Action

        data class RoundingAcceptanceChanged(val accepted: Boolean) : Action

        data class AdjustmentReasonChanged(val reason: String) : Action

        data object PrepareSelected : Action

        data object ReopenSelected : Action

        data object RegisterSelected : Action

        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        /**
         * La instantánea ya está publicada y el borrador está en READY_TO_POST. Navegación usa
         * este evento para retirar del back stack las pantallas que todavía podrían editarlo.
         */
        data class PreparedStateEntered(val draftId: DraftId) : Effect

        data class OpenLineReview(val draftId: DraftId) : Effect

        data class OpenConfirmation(
            val draftId: DraftId,
            val expectedPreparedLogicalHash: String,
        ) : Effect

        data object Back : Effect

        data object CloseInvalidRoute : Effect
    }
}
