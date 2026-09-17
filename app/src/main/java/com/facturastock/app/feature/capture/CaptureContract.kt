package com.facturastock.app.feature.capture

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

/** Modo de flash de la cámara elegido por el usuario; persiste en el `SavedStateHandle`. */
enum class CaptureFlashMode {
    AUTO,
    ON,
    OFF,
}

object CaptureContract {
    @Immutable
    data class State(
        val draftId: DraftId? = null,
        /** Página a reemplazar ("Repetir" desde la vista previa); null = añadir página nueva. */
        val replaceImageId: ImageId? = null,
        /** Reemplaza atómicamente la única foto del escaneo rápido, incluso tras publicar OCR. */
        val replaceSoleInvoiceScan: Boolean = false,
        /** `true` solo después de que CameraX haya ligado un `ImageCapture` utilizable. */
        val isCameraReady: Boolean = false,
        val isCapturing: Boolean = false,
        val flashMode: CaptureFlashMode = CaptureFlashMode.AUTO,
        val cameraPermissionDenied: Boolean = false,
        val cameraPermissionPermanentlyDenied: Boolean = false,
        val cameraError: CameraErrorKind? = null,
        val failure: Failure? = null,
    ) : UiState

    /** Fallo de cámara recuperable: siempre permite reintentar; la galería es solo legacy. */
    enum class CameraErrorKind {
        /** El proveedor de cámara o el enlace al ciclo de vida falló (o no hay cámara trasera). */
        BIND_FAILED,

        /** `takePicture` terminó en error antes de producir la imagen. */
        CAPTURE_FAILED,

        /** La foto se tomó pero la validación/persistencia la rechazó (`FileException`). */
        IMPORT_FAILED,

        /** La captura no pudo publicarse por falta de espacio; el borrador previo se conserva. */
        STORAGE_FULL,
    }

    enum class Failure {
        INVALID_DRAFT_ID,
    }

    sealed interface Action : UiAction {
        /** Validación de entrada: la ruta sin draftId canónico se cierra de inmediato. */
        data object Start : Action

        /**
         * Toque del obturador. Con una captura en curso se ignora: un toque genera como
         * máximo una imagen.
         */
        data object CaptureClicked : Action

        /** La cámara produjo el JPEG; se importa como página 0 del borrador. */
        data class CaptureSucceeded(
            val jpegBytes: ByteArray,
            val rotationDegrees: Int,
        ) : Action

        /** La ruta ya abrió el procesamiento; permite limpiar la intención durable. */
        data object ProcessingNavigationHandled : Action

        /** `takePicture` falló; la captura en curso se libera para poder reintentar. */
        data object CaptureFailed : Action

        /** CameraX terminó el enlace y ya existe un `ImageCapture` utilizable. */
        data object CameraReady : Action

        /** El proveedor de cámara o el enlace al ciclo de vida falló. */
        data object CameraBindFailed : Action

        /** Cicla el modo de flash: automático → activado → desactivado → automático. */
        data object FlashToggled : Action

        /** Estado actual del permiso de cámara, comprobado al entrar o tras el diálogo. */
        data class CameraPermissionResult(
            val granted: Boolean,
            val permanentlyDenied: Boolean = false,
        ) : Action

        /**
         * Lectura inicial del permiso al componer la ruta. Una lectura denegada no demuestra
         * que Android volverá a mostrar el diálogo y por eso conserva la denegación permanente
         * restaurada; un permiso concedido sí limpia ambos indicadores.
         */
        data class CameraPermissionSnapshot(val granted: Boolean) : Action

        /** El usuario pide el permiso desde el estado de denegado. */
        data object RequestPermissionSelected : Action

        /** Reintenta el enlace de la cámara tras un error (la vista se recompone y re-liga). */
        data object RetryCameraSelected : Action

        /** Vuelve al paso de origen legacy, donde vive el selector de galería. */
        data object ImportSelected : Action

        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        /** One-shot hacia la vista: ejecutar `takePicture` sobre el `ImageCapture` activo. */
        data object CaptureNow : Effect

        /** La ruta lanza el diálogo del sistema para el permiso de cámara. */
        data object RequestCameraPermission : Effect

        data object OpenAppSettings : Effect

        /** La foto ya es durable y puede entrar directamente al OCR. */
        data class OpenProcessing(val draftId: DraftId) : Effect

        data object Back : Effect

        data object CloseInvalidRoute : Effect
    }
}
