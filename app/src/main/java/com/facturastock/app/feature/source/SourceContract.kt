package com.facturastock.app.feature.source

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object SourceContract {
    @Immutable
    data class State(
        val draftId: DraftId? = null,
        /** Página a reemplazar ("Repetir" desde la vista previa); null = añadir página nueva. */
        val replaceImageId: ImageId? = null,
        val isImporting: Boolean = false,
        val cameraAccessDenied: Boolean = false,
        val cameraPermissionPermanentlyDenied: Boolean = false,
        val invalidImage: InvalidImageReason? = null,
        val failure: Failure? = null,
    ) : UiState

    /** Motivo por el que una imagen elegida desde galería no se pudo importar. */
    enum class InvalidImageReason {
        UNSUPPORTED_FORMAT,
        TOO_LARGE,
        CORRUPT,
        NOT_FOUND,
        STORAGE_FULL,
        UNAVAILABLE,
    }

    enum class Failure {
        INVALID_DRAFT_ID,
    }

    sealed interface Action : UiAction {
        /** Validación de entrada: la ruta sin draftId canónico se cierra de inmediato. */
        data object Start : Action

        data object CameraSelected : Action

        /** Resultado del permiso de cámara, ya concedido o resuelto por el diálogo del sistema. */
        data class CameraPermissionResult(
            val granted: Boolean,
            val permanentlyDenied: Boolean = false,
        ) : Action

        /** Abre la ficha de la app cuando Android ya no volverá a mostrar el diálogo. */
        data object OpenCameraSettingsSelected : Action

        data object PickImageSelected : Action

        data class ImagePicked(val uriString: String) : Action

        /** El selector se cerró sin elegir: el borrador se conserva y la UI vuelve a reposo. */
        data object PickCancelled : Action

        /** La ruta ya aplicó el efecto de vista previa; permite limpiar la intención durable. */
        data object PreviewNavigationHandled : Action

        data object DismissInvalidImage : Action

        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        /** La ruta decide si puede saltarse el diálogo del sistema (permiso ya concedido). */
        data object RequestCameraPermission : Effect

        data object OpenAppSettings : Effect

        data object LaunchImagePicker : Effect

        /** Abrir la cámara conservando el objetivo de reemplazo, si lo hay. */
        data class OpenCamera(val draftId: DraftId, val replaceImageId: ImageId? = null) : Effect

        /**
         * Para imágenes de galería el [captureId] de la vista previa ES el `ImageId` de la
         * imagen importada: la página 0 ya quedó persistida y ese id la identifica.
         */
        data class OpenPreview(val draftId: DraftId, val captureId: CaptureId) : Effect

        data object Back : Effect

        data object CloseInvalidRoute : Effect
    }
}
