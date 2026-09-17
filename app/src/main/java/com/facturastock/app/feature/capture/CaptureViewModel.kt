package com.facturastock.app.feature.capture

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/**
 * ViewModel del paso de captura con cámara. No conoce CameraX: la vista traduce el efecto
 * [CaptureContract.Effect.CaptureNow] a un `takePicture` y devuelve el resultado como
 * acciones ([CaptureContract.Action.CaptureSucceeded] / [CaptureContract.Action.CaptureFailed]).
 *
 * Guardia anti doble captura: [CaptureContract.Action.CaptureClicked] con `isCapturing` activo
 * se ignora, de modo que un toque genera como máximo una imagen; `isCapturing` solo se libera
 * con el éxito (que navega al procesamiento) o con un fallo (captura o importación).
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importDraftImageUseCase: ImportDraftImageUseCase,
    private val observeInvoiceDraftUseCase: ObserveInvoiceDraftUseCase,
    private val observeDraftImagesUseCase: ObserveDraftImagesUseCase,
    private val uuidGenerator: UuidGenerator,
    dispatcherProvider: DispatcherProvider,
    private val observability: ProductionObservability = DisabledProductionObservability,
) : UdfViewModel<CaptureContract.State, CaptureContract.Action, CaptureContract.Effect>(
    initialState = captureInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var importJob: Job? = null
    private var routeObservationJob: Job? = null
    private var localCaptureInFlight = false
    private var terminalEffectEmitted = false
    private var routeActive = true

    override fun onAction(action: CaptureContract.Action) {
        when (action) {
            CaptureContract.Action.Start -> {
                routeActive = true
                startDurableRouteObservation()
            }

            CaptureContract.Action.CaptureClicked -> onCaptureClicked()

            is CaptureContract.Action.CaptureSucceeded -> {
                if (routeActive) importCaptured(action.jpegBytes, action.rotationDegrees)
            }

            CaptureContract.Action.ProcessingNavigationHandled -> {
                clearPendingImageIntent()
                // La misma instancia puede reaparecer si se cancela el procesamiento.
                terminalEffectEmitted = false
            }

            CaptureContract.Action.CaptureFailed -> executeMain {
                if (!routeActive) return@executeMain
                recordCameraFailure(OperationalErrorCode.CAMERA_CAPTURE_FAILED)
                localCaptureInFlight = false
                clearPendingImageIntent()
                updateState {
                    copy(
                        isCapturing = false,
                        cameraError = CaptureContract.CameraErrorKind.CAPTURE_FAILED,
                    )
                }
            }

            CaptureContract.Action.CameraReady -> executeMain {
                updateState {
                    copy(isCameraReady = true, cameraError = null)
                }
            }

            CaptureContract.Action.CameraBindFailed -> executeMain {
                recordCameraFailure(OperationalErrorCode.CAMERA_UNAVAILABLE)
                updateState {
                    copy(
                        isCameraReady = false,
                        cameraError = CaptureContract.CameraErrorKind.BIND_FAILED,
                    )
                }
            }

            CaptureContract.Action.FlashToggled -> executeMain {
                val next = when (uiState.value.flashMode) {
                    CaptureFlashMode.AUTO -> CaptureFlashMode.ON
                    CaptureFlashMode.ON -> CaptureFlashMode.OFF
                    CaptureFlashMode.OFF -> CaptureFlashMode.AUTO
                }
                savedStateHandle[FLASH_MODE_KEY] = next.name
                updateState { copy(flashMode = next) }
            }

            is CaptureContract.Action.CameraPermissionResult -> executeMain {
                if (!routeActive) return@executeMain
                if (!action.granted) {
                    recordCameraFailure(OperationalErrorCode.CAMERA_PERMISSION_DENIED)
                }
                savedStateHandle[PERMISSION_DENIED_KEY] = !action.granted
                savedStateHandle[PERMISSION_PERMANENTLY_DENIED_KEY] =
                    !action.granted && action.permanentlyDenied
                updateState {
                    copy(
                        isCameraReady = false,
                        cameraPermissionDenied = !action.granted,
                        cameraPermissionPermanentlyDenied =
                            !action.granted && action.permanentlyDenied,
                    )
                }
            }

            is CaptureContract.Action.CameraPermissionSnapshot -> executeMain {
                if (!routeActive) return@executeMain
                if (action.granted) {
                    savedStateHandle[PERMISSION_DENIED_KEY] = false
                    savedStateHandle[PERMISSION_PERMANENTLY_DENIED_KEY] = false
                    updateState {
                        copy(
                            cameraPermissionDenied = false,
                            cameraPermissionPermanentlyDenied = false,
                        )
                    }
                } else {
                    // checkSelfPermission() no distingue "aún no solicitado" de "no volver a
                    // preguntar"; no debe degradar evidencia restaurada de denegación permanente.
                    savedStateHandle[PERMISSION_DENIED_KEY] = true
                    updateState { copy(cameraPermissionDenied = true) }
                }
            }

            CaptureContract.Action.RequestPermissionSelected -> executeMain {
                emitEffect(
                    if (uiState.value.cameraPermissionPermanentlyDenied) {
                        CaptureContract.Effect.OpenAppSettings
                    } else {
                        CaptureContract.Effect.RequestCameraPermission
                    },
                )
            }

            CaptureContract.Action.RetryCameraSelected -> executeMain {
                // Al limpiar el error la vista recompone el slot y vuelve a ligar la cámara.
                updateState { copy(isCameraReady = false, cameraError = null) }
            }

            CaptureContract.Action.ImportSelected -> executeMain {
                if (importJob?.isActive == true) return@executeMain
                // La galería vive en el paso de origen: basta con volver.
                routeActive = false
                localCaptureInFlight = false
                routeObservationJob?.cancel()
                clearPendingImageIntent()
                updateState { copy(isCapturing = false) }
                emitEffect(CaptureContract.Effect.Back)
            }

            CaptureContract.Action.BackSelected -> executeMain {
                // Un commit ya iniciado conserva la ruta hasta terminar. Si solo CameraX posee
                // la captura, se permite salir y routeActive invalida cualquier callback tardío
                // antes de que pueda alcanzar persistencia.
                if (importJob?.isActive == true) return@executeMain
                routeActive = false
                localCaptureInFlight = false
                routeObservationJob?.cancel()
                clearPendingImageIntent()
                updateState { copy(isCapturing = false) }
                emitEffect(CaptureContract.Effect.Back)
            }
        }
    }

    private fun onCaptureClicked() {
        val current = uiState.value
        // Guardia anti doble captura: un toque genera como máximo una imagen.
        if (
            current.draftId == null ||
            !current.isCameraReady ||
            current.isCapturing ||
            localCaptureInFlight ||
            terminalEffectEmitted ||
            !routeActive
        ) {
            return
        }
        ensurePendingImageId()
        localCaptureInFlight = true
        executeMain {
            updateState { copy(isCapturing = true, cameraError = null) }
            emitEffect(CaptureContract.Effect.CaptureNow)
        }
    }

    private fun importCaptured(jpegBytes: ByteArray, rotationDegrees: Int) {
        val current = uiState.value
        val draftId = current.draftId ?: return
        if (!routeActive || importJob?.isActive == true || terminalEffectEmitted) return
        val pendingImageId = ensurePendingImageId()
        localCaptureInFlight = true
        importJob = executeIo(
            operation = {
                importDraftImageUseCase(
                    draftId = draftId,
                    jpegBytes = jpegBytes,
                    rotationDegrees = rotationDegrees,
                    replaceImageId = current.replaceImageId,
                    preferredImageId = pendingImageId,
                    replaceSoleInvoiceScan = current.replaceSoleInvoiceScan,
                )
            },
            onSuccess = { image ->
                importJob = null
                terminalEffectEmitted = true
                localCaptureInFlight = false
                updateState { copy(isCapturing = false, cameraError = null) }
                emitEffect(
                    CaptureContract.Effect.OpenProcessing(image.draftId),
                )
            },
            onFailure = { error ->
                // La foto se tomó pero la validación o la persistencia la rechazó.
                importJob = null
                localCaptureInFlight = false
                clearPendingImageIntent()
                updateState {
                    copy(
                        isCapturing = false,
                        cameraError = if (error.isInsufficientSpace()) {
                            CaptureContract.CameraErrorKind.STORAGE_FULL
                        } else {
                            CaptureContract.CameraErrorKind.IMPORT_FAILED
                        },
                    )
                }
            },
        )
    }

    /**
     * Reconcilia una ruta recreada con Room. Un draft CAPTURED con páginas puede ser una
     * entrada fresca para "Añadir página"; solo la aparición del ID reservado antes del
     * obturador demuestra que esta captura alcanzó el commit y habilita la navegación.
     */
    private fun startDurableRouteObservation() {
        val draftId = uiState.value.draftId
        if (draftId == null) {
            if (!terminalEffectEmitted) {
                terminalEffectEmitted = true
                executeMain { emitEffect(CaptureContract.Effect.CloseInvalidRoute) }
            }
            return
        }
        if (routeObservationJob?.isActive == true || terminalEffectEmitted) return
        routeObservationJob = executeMain {
            combine(
                observeInvoiceDraftUseCase(draftId),
                observeDraftImagesUseCase(draftId),
            ) { draft, images -> draft to images }
                .collect { (draft, images) ->
                    if (terminalEffectEmitted) return@collect
                    if (draft == null) {
                        terminalEffectEmitted = true
                        emitEffect(CaptureContract.Effect.CloseInvalidRoute)
                        return@collect
                    }
                    if (draft.status != DraftStatus.CAPTURED || localCaptureInFlight) {
                        return@collect
                    }
                    val pendingImageId = pendingImageId() ?: return@collect
                    val published = images.firstOrNull { it.imageId == pendingImageId }
                        ?: return@collect
                    terminalEffectEmitted = true
                    updateState { copy(isCapturing = false, cameraError = null) }
                    emitEffect(
                        CaptureContract.Effect.OpenProcessing(draftId),
                    )
                }
        }
    }

    /** El ID se guarda antes de `takePicture`, por lo que sobrevive a la recreación. */
    private fun ensurePendingImageId(): ImageId {
        pendingImageId()?.let { return it }
        return ImageId.from(uuidGenerator.newUuid()).also { imageId ->
            savedStateHandle[PENDING_IMAGE_ID_KEY] = imageId.value
        }
    }

    private fun pendingImageId(): ImageId? =
        ImageId.parse(savedStateHandle.get<String>(PENDING_IMAGE_ID_KEY))

    private fun clearPendingImageIntent() {
        savedStateHandle.remove<String>(PENDING_IMAGE_ID_KEY)
    }

    private suspend fun recordCameraFailure(errorCode: OperationalErrorCode) {
        val draftId = uiState.value.draftId ?: return
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.INVOICE_CAPTURE,
                outcome = OperationalOutcome.FAILED,
                identifiers = InternalIdentifiers(
                    draftId = draftId,
                    imageId = pendingImageId(),
                ),
                errorCode = errorCode,
            ),
        )
    }

    private companion object {
        const val FLASH_MODE_KEY = "capture.flashMode"
        const val PERMISSION_DENIED_KEY = "capture.cameraPermissionDenied"
        const val PERMISSION_PERMANENTLY_DENIED_KEY =
            "capture.cameraPermissionPermanentlyDenied"
        const val PENDING_IMAGE_ID_KEY = "capture.pendingImageId"
    }
}

private fun Throwable.isInsufficientSpace(): Boolean =
    (this is FileException && error == FileError.InsufficientSpace) ||
        (this is StorageException && error == StorageError.InsufficientSpace)

private fun captureInitialState(savedStateHandle: SavedStateHandle): CaptureContract.State {
    val draftId = DraftId.parse(savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID))
    val replaceImageId = ImageId.parse(savedStateHandle.get<String>(RouteArgumentKeys.REPLACE_ID))
    val flashMode = savedStateHandle.get<String>("capture.flashMode")
        ?.let { saved ->
            CaptureFlashMode.entries.firstOrNull { it.name == saved }
        }
        ?: CaptureFlashMode.AUTO
    return CaptureContract.State(
        draftId = draftId,
        replaceImageId = replaceImageId,
        replaceSoleInvoiceScan = savedStateHandle.get<String>(RouteArgumentKeys.SCAN_RETAKE)
            ?.toBooleanStrictOrNull() == true,
        flashMode = flashMode,
        cameraPermissionDenied = savedStateHandle.get<Boolean>("capture.cameraPermissionDenied")
            ?: false,
        cameraPermissionPermanentlyDenied = savedStateHandle.get<Boolean>(
            "capture.cameraPermissionPermanentlyDenied",
        ) ?: false,
        failure = if (draftId == null) {
            CaptureContract.Failure.INVALID_DRAFT_ID
        } else {
            null
        },
    )
}
