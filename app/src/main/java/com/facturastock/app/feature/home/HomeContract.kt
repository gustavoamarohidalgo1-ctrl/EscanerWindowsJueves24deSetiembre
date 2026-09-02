package com.facturastock.app.feature.home

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.HomeDashboardSnapshot
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object HomeContract {
    @Immutable
    data class State(
        val isLoading: Boolean = false,
        val isCreatingDraft: Boolean = false,
        val dashboard: HomeDashboardSnapshot? = null,
        val drafts: List<HomeDraftItem>? = null,
        val failure: Failure? = null,
        val draftIdPendingDeletion: DraftId? = null,
        val draftIdPendingOcrChoice: DraftId? = null,
        val ocrRunIdPendingChoice: OcrRunId? = null,
        val isRecoveringOcr: Boolean = false,
    ) : UiState

    /**
     * Borrador reciente con datos crudos de dominio; las etiquetas visibles (proveedor,
     * documento, importes y fechas formateados) se resuelven en el composable.
     */
    @Immutable
    data class HomeDraftItem(
        val draft: InvoiceDraft,
        val supplierName: String?,
    )

    enum class Failure {
        LOAD_FAILED,
    }

    sealed interface Action : UiAction {
        data object Retry : Action

        data object ScanInvoiceSelected : Action

        data object ProductsSelected : Action

        data object PurchasesSelected : Action

        data object InventorySelected : Action

        data class DraftSelected(val draftId: DraftId) : Action

        data class DeleteRequested(val draftId: DraftId) : Action

        data object DeleteConfirmed : Action

        data object DeleteDismissed : Action

        data object OcrResumeSelected : Action

        data object OcrRetrySelected : Action

        data object OcrChoiceDismissed : Action
    }

    sealed interface Effect : UiEffect {
        /** Un borrador nuevo o todavía vacío abre la cámara sin pantalla intermedia. */
        data class OpenDraftCamera(val draftId: DraftId) : Effect

        /** Compatibilidad con herramientas que todavía abren el selector de origen. */
        data class OpenDraftSource(val draftId: DraftId) : Effect

        /** Vista previa de las páginas capturadas, empezando por [imageId]. */
        data class OpenPreview(val draftId: DraftId, val imageId: ImageId) : Effect

        data class OpenProcessing(val draftId: DraftId) : Effect

        data class OpenHeader(val draftId: DraftId) : Effect

        data class OpenLines(val draftId: DraftId) : Effect

        data class OpenSummary(val draftId: DraftId) : Effect

        data class OpenPurchaseDetail(val purchaseId: PurchaseId) : Effect

        data object OpenProducts : Effect

        data object OpenPurchases : Effect

        data object OpenInventory : Effect

        data object ShowDeleteSuccess : Effect

        /** Room no pudo crear el borrador; la pantalla actual permanece intacta y permite retry. */
        data object ShowDraftCreationFailure : Effect

        /** La recuperación perdió su CAS o falló; el diálogo queda abierto para reintentar. */
        data object ShowOcrRecoveryFailure : Effect

        /** El borrado falló; la UI ofrece reintentar el borrado del mismo borrador. */
        data class ShowDeleteFailure(val draftId: DraftId) : Effect
    }
}
