package com.facturastock.app.feature.source

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
class SourceViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importDraftImageUseCase: ImportDraftImageUseCase,
    private val observeInvoiceDraftUseCase: ObserveInvoiceDraftUseCase,
    private val observeDraftImagesUseCase: ObserveDraftImagesUseCase,
    private val uuidGenerator: UuidGenerator,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<SourceContract.State, SourceContract.Action, SourceContract.Effect>(
    initialState = sourceInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var importJob: Job? = null
    private var routeObservationJob: Job? = null
    private var localPublicationInFlight = false
    private var terminalEffectEmitted = false
    private var routeActive = true

    override fun onAction(action: SourceContract.Action) {
        when (action) {
            SourceContract.Action.Start -> {
                routeActive = true
                startDurableRouteObservation()
            }

            SourceContract.Action.CameraSelected -> ifSourceMutationAllowed {
                if (uiState.value.draftId != null) {
                    clearPendingImageIntent()
                    emitEffect(SourceContract.Effect.RequestCameraPermission)
                }
            }

            is SourceContract.Action.CameraPermissionResult ->
                onCameraPermissionResult(action.granted, action.permanentlyDenied)

            SourceContract.Action.OpenCameraSettingsSelected -> ifSourceMutationAllowed {
                emitEffect(SourceContract.Effect.OpenAppSettings)
            }

            SourceContract.Action.PickImageSelected -> ifSourceMutationAllowed {
                if (uiState.value.draftId != null) {
                    ensurePendingImageId()
                    emitEffect(SourceContract.Effect.LaunchImagePicker)
                }
            }

            is SourceContract.Action.ImagePicked -> {
                if (routeActive) importImage(action.uriString)
            }

            // El usuario cerró el selector sin elegir: no se toca nada.
            SourceContract.Action.PickCancelled -> ifSourceMutationAllowed {
                clearPendingImageIntent()
            }

            SourceContract.Action.PreviewNavigationHandled -> {
                clearPendingImageIntent()
                // Source permanece en el back stack: al volver desde Preview debe admitir
                // otra importación. Sin pending ID el observador Room no puede rebotar.
                terminalEffectEmitted = false
            }

            SourceContract.Action.DismissInvalidImage -> executeMain {
                savedStateHandle.remove<String>(INVALID_IMAGE_KEY)
                updateState { copy(invalidImage = null) }
            }

            SourceContract.Action.BackSelected -> executeMain {
                // La publicación puede alcanzar Room aunque se cancele tarde. Mientras está en
                // curso se consume Back para no dejar una página invisible tras retirar la ruta.
                if (sourceMutationBlocked()) return@executeMain
                routeActive = false
                routeObservationJob?.cancel()
                clearPendingImageIntent()
                emitEffect(SourceContract.Effect.Back)
            }
        }
    }

    private fun onCameraPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (sourceMutationBlocked()) return
        val current = uiState.value
        val draftId = current.draftId ?: return
        executeMain {
            if (sourceMutationBlocked()) return@executeMain
            if (granted) {
                savedStateHandle[CAMERA_DENIED_KEY] = false
                savedStateHandle[CAMERA_PERMANENTLY_DENIED_KEY] = false
                updateState {
                    copy(
                        cameraAccessDenied = false,
                        cameraPermissionPermanentlyDenied = false,
                    )
                }
                emitEffect(
                    SourceContract.Effect.OpenCamera(
                        draftId = draftId,
                        replaceImageId = current.replaceImageId,
                    ),
                )
            } else {
                savedStateHandle[CAMERA_DENIED_KEY] = true
                savedStateHandle[CAMERA_PERMANENTLY_DENIED_KEY] = permanentlyDenied
                updateState {
                    copy(
                        cameraAccessDenied = true,
                        cameraPermissionPermanentlyDenied = permanentlyDenied,
                    )
                }
            }
        }
    }

    private fun importImage(uriString: String) {
        if (sourceMutationBlocked()) return
        val current = uiState.value
        val draftId = current.draftId ?: return
        val pendingImageId = ensurePendingImageId()
        importJob = executeIo(
            before = {
                localPublicationInFlight = true
                updateState { copy(isImporting = true, invalidImage = null) }
            },
            operation = {
                importDraftImageUseCase(
                    draftId = draftId,
                    sourceUri = uriString,
                    replaceImageId = current.replaceImageId,
                    preferredImageId = pendingImageId,
                )
            },
            onSuccess = { image ->
                importJob = null
                terminalEffectEmitted = true
                localPublicationInFlight = false
                savedStateHandle.remove<String>(INVALID_IMAGE_KEY)
                updateState { copy(isImporting = false, invalidImage = null) }
                // Galería: el captureId de la vista previa ES el imageId persistido.
                emitEffect(
                    SourceContract.Effect.OpenPreview(
                        draftId = image.draftId,
                        captureId = CaptureId.from(UUID.fromString(image.imageId.value)),
                    ),
                )
            },
            onFailure = { error ->
                importJob = null
                localPublicationInFlight = false
                clearPendingImageIntent()
                val reason = error.toInvalidImageReason()
                savedStateHandle[INVALID_IMAGE_KEY] = reason.name
                updateState { copy(isImporting = false, invalidImage = reason) }
            },
        )
    }

    /**
     * Room decide si una ruta restaurada sigue siendo válida. La presencia de cualquier
     * página CAPTURED no basta (una ruta fresca de "Añadir página" ya las tiene): solo la
     * imagen estable reservada antes del picker prueba que ESTA publicación llegó al commit.
     */
    private fun startDurableRouteObservation() {
        val draftId = uiState.value.draftId
        if (draftId == null) {
            if (!terminalEffectEmitted) {
                terminalEffectEmitted = true
                executeMain { emitEffect(SourceContract.Effect.CloseInvalidRoute) }
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
                        emitEffect(SourceContract.Effect.CloseInvalidRoute)
                        return@collect
                    }
                    if (draft.status != DraftStatus.CAPTURED || localPublicationInFlight) {
                        return@collect
                    }
                    val pendingImageId = pendingImageId() ?: return@collect
                    val published = images.firstOrNull { it.imageId == pendingImageId }
                        ?: return@collect
                    terminalEffectEmitted = true
                    updateState { copy(isImporting = false, invalidImage = null) }
                    emitEffect(
                        SourceContract.Effect.OpenPreview(
                            draftId = draftId,
                            captureId = CaptureId.from(UUID.fromString(published.imageId.value)),
                        ),
                    )
                }
        }
    }

    /** Reserva antes del picker el ID que permite reconocer el commit tras muerte de proceso. */
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

    /** Captura y revalida la guarda: cubre acciones encoladas antes del callback `before`. */
    private fun ifSourceMutationAllowed(block: suspend () -> Unit) {
        if (sourceMutationBlocked()) return
        executeMain {
            if (sourceMutationBlocked()) return@executeMain
            block()
        }
    }

    private fun sourceMutationBlocked(): Boolean =
        !routeActive || importJob?.isActive == true || localPublicationInFlight ||
            terminalEffectEmitted

    private fun Throwable.toInvalidImageReason(): SourceContract.InvalidImageReason =
        when (this) {
            is FileException -> when (error) {
                FileError.UnsupportedFormat -> SourceContract.InvalidImageReason.UNSUPPORTED_FORMAT
                FileError.TooLarge -> SourceContract.InvalidImageReason.TOO_LARGE
                FileError.Corrupt -> SourceContract.InvalidImageReason.CORRUPT
                FileError.NotFound -> SourceContract.InvalidImageReason.NOT_FOUND
                FileError.InsufficientSpace -> SourceContract.InvalidImageReason.STORAGE_FULL
            }

            is StorageException -> if (error == StorageError.InsufficientSpace) {
                SourceContract.InvalidImageReason.STORAGE_FULL
            } else {
                SourceContract.InvalidImageReason.UNAVAILABLE
            }

            else -> SourceContract.InvalidImageReason.UNAVAILABLE
        }

    private companion object {
        const val CAMERA_DENIED_KEY = "source.cameraAccessDenied"
        const val CAMERA_PERMANENTLY_DENIED_KEY = "source.cameraPermissionPermanentlyDenied"
        const val INVALID_IMAGE_KEY = "source.invalidImage"
        const val PENDING_IMAGE_ID_KEY = "source.pendingImageId"
    }
}

private fun sourceInitialState(savedStateHandle: SavedStateHandle): SourceContract.State {
    val draftId = DraftId.parse(savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID))
    val replaceImageId = ImageId.parse(savedStateHandle.get<String>(RouteArgumentKeys.REPLACE_ID))
    return SourceContract.State(
        draftId = draftId,
        replaceImageId = replaceImageId,
        cameraAccessDenied = savedStateHandle.get<Boolean>("source.cameraAccessDenied") ?: false,
        cameraPermissionPermanentlyDenied = savedStateHandle.get<Boolean>(
            "source.cameraPermissionPermanentlyDenied",
        ) ?: false,
        invalidImage = savedStateHandle.get<String>("source.invalidImage")
            ?.let { saved ->
                SourceContract.InvalidImageReason.entries.firstOrNull { it.name == saved }
            },
        failure = if (draftId == null) {
            SourceContract.Failure.INVALID_DRAFT_ID
        } else {
            null
        },
    )
}
