package com.facturastock.app.feature.preview

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.ImageQualityReport
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object PreviewContract {
    /** Página del borrador tal como la necesita la vista previa (metadatos, no contenido). */
    @Immutable
    data class Page(
        val imageId: ImageId,
        val filePath: String,
        val widthPx: Int,
        val heightPx: Int,
        val rotationDegrees: Int,
        val crop: ImageCrop?,
        val pageIndex: Int,
    )

    /**
     * Modo de la pantalla: revisando páginas o editando el recorte. El borrador del
     * rectángulo vive solo en memoria mientras se edita; al confirmar se persiste.
     */
    @Immutable
    enum class Mode {
        VIEWING,
        CROPPING,
    }

    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val pages: List<Page> = emptyList(),
        val currentIndex: Int = 0,
        val mode: Mode = Mode.VIEWING,
        /** Rectángulo en edición (fracciones 0..10000 sobre la imagen ya rotada). */
        val cropDraft: ImageCrop? = null,
        /** Una mutación durable de páginas está en vuelo; bloquea acciones competidoras. */
        val isMutating: Boolean = false,
        val isWorking: Boolean = false,
        val showDeleteConfirm: Boolean = false,
        /** Informes con señales heurísticas pendientes de decisión del usuario. */
        val qualityWarnings: List<ImageQualityReport> = emptyList(),
        val processFailed: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val currentPage: Page?
            get() = pages.getOrNull(currentIndex)

        val isBusy: Boolean
            get() = isMutating || isWorking
    }

    enum class Failure {
        INVALID_ROUTE,
        LOAD_FAILED,
        /** La página permanece intacta y la misma edición puede volver a intentarse. */
        MUTATION_FAILED,
    }

    /** Esquina del rectángulo de recorte que se arrastra. */
    enum class CropCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
    }

    sealed interface Action : UiAction {
        /** Validación de entrada: la ruta sin draftId/captureId canónicos se cierra. */
        data object Start : Action

        data object RetryLoad : Action

        data class PageSelected(val index: Int) : Action

        data object PreviousPageClicked : Action

        data object NextPageClicked : Action

        data object RotateClicked : Action

        data object CropClicked : Action

        /** Arrastre de una esquina, ya en fracciones 0..10000 del marco rotado. */
        data class CropCornerDragged(
            val corner: CropCorner,
            val xFraction: Int,
            val yFraction: Int,
        ) : Action

        data object CropConfirmed : Action

        data object CropCancelled : Action

        /** Repetir la página actual (re-capturar o reelegir conservando su posición). */
        data object RetakeClicked : Action

        data object AddPageClicked : Action

        data object MoveUpClicked : Action

        data object MoveDownClicked : Action

        data object DeleteClicked : Action

        data object DeleteConfirmed : Action

        data object DeleteDismissed : Action

        /** Finaliza la edición: analiza calidad y prepara copias antes del OCR. */
        data object ProcessClicked : Action

        /** Acepta explícitamente las advertencias y crea las versiones OCR. */
        data object ContinueWithWarningsClicked : Action

        /** Vuelve a capturar la primera página advertida, conservando su posición. */
        data object RetakeWarnedPageClicked : Action

        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        /** Origen con el objetivo de reemplazo actual (o sin él para "Añadir página"). */
        data class OpenSource(val draftId: DraftId, val replaceImageId: ImageId?) : Effect

        data class OpenProcessing(val draftId: DraftId) : Effect

        data object Back : Effect

        data object CloseInvalidRoute : Effect
    }
}
