package com.facturastock.app.feature.preview

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.usecase.AnalyzeDraftImagesUseCase
import com.facturastock.app.domain.usecase.CropDraftImageUseCase
import com.facturastock.app.domain.usecase.DeleteDraftImageUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ReorderDraftImagesUseCase
import com.facturastock.app.domain.usecase.RotateDraftImageUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * ViewModel de la vista previa de páginas: zoom y revisión del documento ANTES del OCR.
 * Todas las transformaciones (giro, recorte, reorden, eliminación) se aplican por casos de
 * uso y llegan de vuelta por la colección de páginas — así sobreviven a la muerte del
 * proceso. El borrador del rectángulo en edición es efímero por diseño (se confirma o se
 * descarta). El procesamiento nunca arranca solo: [PreviewContract.Action.ProcessClicked]
 * analiza calidad y, con aceptación explícita de cualquier advertencia, abre el orquestador
 * OCR. Ese destino es el único dueño del preprocesado y del reconocimiento recuperable.
 */
class PreviewViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeDraftImagesUseCase: ObserveDraftImagesUseCase,
    private val rotateDraftImageUseCase: RotateDraftImageUseCase,
    private val cropDraftImageUseCase: CropDraftImageUseCase,
    private val reorderDraftImagesUseCase: ReorderDraftImagesUseCase,
    private val deleteDraftImageUseCase: DeleteDraftImageUseCase,
    private val analyzeDraftImagesUseCase: AnalyzeDraftImagesUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<PreviewContract.State, PreviewContract.Action, PreviewContract.Effect>(
    initialState = previewInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var pagesJob: Job? = null
    private var processingJob: Job? = null
    private var mutationJob: Job? = null
    /** Claim sincrónico: cierra la ventana entre el tap y el arranque del coroutine main. */
    private var mutationClaimed: Boolean = false
    private var pendingInitialCaptureId: ImageId? = if (
        savedStateHandle.contains(CURRENT_INDEX_KEY)
    ) {
        // Tras muerte de proceso manda la selección explícita del usuario, no el argumento
        // inmutable de la ruta que solo debía aplicarse en la primera entrada.
        null
    } else {
        ImageId.parse(savedStateHandle.get<String>(RouteArgumentKeys.CAPTURE_ID))
    }

    init {
        uiState.value.draftId?.let(::observePages)
    }

    override fun onAction(action: PreviewContract.Action) {
        when (action) {
            PreviewContract.Action.Start -> executeMain {
                if (uiState.value.draftId == null) {
                    emitEffect(PreviewContract.Effect.CloseInvalidRoute)
                }
            }

            PreviewContract.Action.RetryLoad -> {
                uiState.value.draftId?.let(::observePages)
            }

            is PreviewContract.Action.PageSelected -> executeMain {
                if (mutationClaimed) return@executeMain
                selectPage(action.index)
            }

            PreviewContract.Action.PreviousPageClicked -> executeMain {
                if (mutationClaimed) return@executeMain
                selectPage(uiState.value.currentIndex - 1)
            }

            PreviewContract.Action.NextPageClicked -> executeMain {
                if (mutationClaimed) return@executeMain
                selectPage(uiState.value.currentIndex + 1)
            }

            PreviewContract.Action.RotateClicked -> mutateCurrentPage(
                operation = { page -> rotateDraftImageUseCase(page.imageId) },
            )

            PreviewContract.Action.CropClicked -> executeMain {
                if (mutationClaimed || processingJob?.isActive == true) return@executeMain
                val page = uiState.value.currentPage ?: return@executeMain
                updateState {
                    copy(
                        mode = PreviewContract.Mode.CROPPING,
                        cropDraft = page.crop ?: FULL_CROP,
                    )
                }
            }

            is PreviewContract.Action.CropCornerDragged -> onCropCornerDragged(action)

            PreviewContract.Action.CropConfirmed -> {
                val crop = uiState.value.cropDraft
                mutateCurrentPage(
                    operation = { page -> cropDraftImageUseCase(page.imageId, crop) },
                    onSuccess = {
                        updateState {
                            copy(mode = PreviewContract.Mode.VIEWING, cropDraft = null)
                        }
                    },
                )
            }

            PreviewContract.Action.CropCancelled -> executeMain {
                if (mutationClaimed) return@executeMain
                updateState {
                    copy(mode = PreviewContract.Mode.VIEWING, cropDraft = null)
                }
            }

            PreviewContract.Action.RetakeClicked -> executeMain {
                if (mutationClaimed || processingJob?.isActive == true) return@executeMain
                val draftId = uiState.value.draftId ?: return@executeMain
                val page = uiState.value.currentPage ?: return@executeMain
                emitEffect(
                    PreviewContract.Effect.OpenSource(
                        draftId = draftId,
                        replaceImageId = page.imageId,
                    ),
                )
            }

            PreviewContract.Action.AddPageClicked -> executeMain {
                if (mutationClaimed || processingJob?.isActive == true) return@executeMain
                val draftId = uiState.value.draftId ?: return@executeMain
                emitEffect(PreviewContract.Effect.OpenSource(draftId, replaceImageId = null))
            }

            PreviewContract.Action.MoveUpClicked -> moveCurrentPage(moveUp = true)

            PreviewContract.Action.MoveDownClicked -> moveCurrentPage(moveUp = false)

            PreviewContract.Action.DeleteClicked -> executeMain {
                if (mutationClaimed || processingJob?.isActive == true) return@executeMain
                if (uiState.value.currentPage != null) {
                    updateState { copy(showDeleteConfirm = true) }
                }
            }

            PreviewContract.Action.DeleteConfirmed -> deleteCurrentPage()

            PreviewContract.Action.DeleteDismissed -> executeMain {
                if (mutationClaimed) return@executeMain
                updateState { copy(showDeleteConfirm = false) }
            }

            PreviewContract.Action.ProcessClicked -> processDraft()

            PreviewContract.Action.ContinueWithWarningsClicked -> {
                // La acción del diálogo solo es válida para el último análisis visible; así una
                // acción sintética no puede saltarse el análisis ni un doble toque navegar dos veces.
                if (
                    uiState.value.qualityWarnings.isNotEmpty() &&
                    processingJob?.isActive != true &&
                    !mutationClaimed
                ) {
                    processingJob = executeMain {
                        openProcessing()
                        processingJob = null
                    }
                }
            }

            PreviewContract.Action.RetakeWarnedPageClicked -> retakeFirstWarnedPage()

            PreviewContract.Action.BackSelected -> executeMain {
                processingJob?.cancel()
                processingJob = null
                mutationJob?.cancel()
                mutationJob = null
                mutationClaimed = false
                updateState { copy(isWorking = false, isMutating = false) }
                emitEffect(PreviewContract.Effect.Back)
            }
        }
    }

    /** Colección continua de páginas; la primera emisión coloca la página de la ruta. */
    private fun observePages(draftId: DraftId) {
        pagesJob?.cancel()
        pagesJob = executeMain {
            updateState { copy(failure = null) }
            try {
                observeDraftImagesUseCase(draftId).collect { images ->
                    val pages = images.map { image ->
                        PreviewContract.Page(
                            imageId = image.imageId,
                            filePath = image.filePath,
                            widthPx = image.widthPx,
                            heightPx = image.heightPx,
                            rotationDegrees = image.rotationDegrees,
                            crop = image.crop,
                            pageIndex = image.pageIndex,
                        )
                    }
                    val initialIndex = pendingInitialCaptureId
                        ?.let { target -> pages.indexOfFirst { it.imageId == target } }
                        ?.takeIf { it >= 0 }
                    pendingInitialCaptureId = null
                    updateState {
                        val nextIndex = when {
                            initialIndex != null -> initialIndex
                            pages.isEmpty() -> 0
                            else -> currentIndex.coerceIn(0, pages.size - 1)
                        }
                        copy(pages = pages, currentIndex = nextIndex, failure = null)
                    }
                    if (initialIndex != null) {
                        savedStateHandle[CURRENT_INDEX_KEY] = initialIndex
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(failure = PreviewContract.Failure.LOAD_FAILED) }
            }
        }
    }

    private fun selectPage(index: Int) {
        val pages = uiState.value.pages
        if (index !in pages.indices) return
        savedStateHandle[CURRENT_INDEX_KEY] = index
        updateState { copy(currentIndex = index) }
    }

    private fun onCropCornerDragged(action: PreviewContract.Action.CropCornerDragged) {
        executeMain {
            if (mutationClaimed) return@executeMain
            val draft = uiState.value.cropDraft ?: return@executeMain
            val x = action.xFraction.coerceIn(0, ImageCrop.FRACTION_MAX)
            val y = action.yFraction.coerceIn(0, ImageCrop.FRACTION_MAX)
            val next = when (action.corner) {
                PreviewContract.CropCorner.TOP_LEFT -> draft.copy(
                    left = x.coerceAtMost(draft.right - MIN_CROP_SPAN),
                    top = y.coerceAtMost(draft.bottom - MIN_CROP_SPAN),
                )
                PreviewContract.CropCorner.TOP_RIGHT -> draft.copy(
                    right = x.coerceAtLeast(draft.left + MIN_CROP_SPAN),
                    top = y.coerceAtMost(draft.bottom - MIN_CROP_SPAN),
                )
                PreviewContract.CropCorner.BOTTOM_LEFT -> draft.copy(
                    left = x.coerceAtMost(draft.right - MIN_CROP_SPAN),
                    bottom = y.coerceAtLeast(draft.top + MIN_CROP_SPAN),
                )
                PreviewContract.CropCorner.BOTTOM_RIGHT -> draft.copy(
                    right = x.coerceAtLeast(draft.left + MIN_CROP_SPAN),
                    bottom = y.coerceAtLeast(draft.top + MIN_CROP_SPAN),
                )
            }
            updateState { copy(cropDraft = next) }
        }
    }

    private fun moveCurrentPage(moveUp: Boolean) {
        val draftId = uiState.value.draftId ?: return
        val page = uiState.value.currentPage ?: return
        launchMutation(
            operation = { reorderDraftImagesUseCase(draftId, page.imageId, moveUp) },
            onSuccess = { reordered ->
                reordered.indexOfFirst { it.imageId == page.imageId }
                    .takeIf { it >= 0 }
                    ?.let { newIndex ->
                        savedStateHandle[CURRENT_INDEX_KEY] = newIndex
                        updateState { copy(currentIndex = newIndex) }
                    }
            },
            onFailure = {
                // La colección de páginas mantiene la vista coherente; no hay estado de error.
            },
        )
    }

    private fun deleteCurrentPage() {
        val draftId = uiState.value.draftId ?: return
        val page = uiState.value.currentPage ?: return
        launchMutation(
            before = { updateState { copy(showDeleteConfirm = false) } },
            operation = { deleteDraftImageUseCase(page.imageId) },
            onSuccess = { result ->
                if (result.removed && result.draftIsEmpty) {
                    // El borrador quedó sin páginas (vuelve a CREATED): se vuelve al origen.
                    emitEffect(
                        PreviewContract.Effect.OpenSource(
                            draftId = draftId,
                            replaceImageId = null,
                        ),
                    )
                }
            },
            onFailure = {
                // Sin estado de error transitorio: la página sigue ahí y se puede reintentar.
            },
        )
    }

    /** "Procesar": analiza primero; las heurísticas nunca bloquean sin decisión del usuario. */
    private fun processDraft() {
        val draftId = uiState.value.draftId ?: return
        if (
            uiState.value.mode != PreviewContract.Mode.VIEWING ||
            uiState.value.pages.isEmpty() || processingJob?.isActive == true || mutationClaimed
        ) {
            return
        }
        processingJob = executeIo(
            before = {
                updateState {
                    copy(
                        isWorking = true,
                        processFailed = false,
                        qualityWarnings = emptyList(),
                    )
                }
            },
            operation = { analyzeDraftImagesUseCase(draftId) },
            onSuccess = { reports ->
                processingJob = null
                val warnedReports = reports.filter { it.hasWarnings }
                if (warnedReports.isEmpty()) {
                    openProcessing()
                } else {
                    val firstWarnedIndex = uiState.value.pages.indexOfFirst { page ->
                        page.imageId == warnedReports.first().imageId
                    }.takeIf { it >= 0 }
                    firstWarnedIndex?.let { index ->
                        savedStateHandle[CURRENT_INDEX_KEY] = index
                    }
                    updateState {
                        copy(
                            isWorking = false,
                            currentIndex = firstWarnedIndex ?: currentIndex,
                            qualityWarnings = warnedReports,
                        )
                    }
                }
            },
            onFailure = {
                processingJob = null
                updateState { copy(isWorking = false, processFailed = true) }
            },
        )
    }

    /** Cede al destino OCR el lote original; allí se reclama, prepara y publica como una unidad. */
    private suspend fun openProcessing() {
        val draftId = uiState.value.draftId ?: return
        if (uiState.value.pages.isEmpty()) return
        updateState {
            copy(
                isWorking = false,
                processFailed = false,
                qualityWarnings = emptyList(),
            )
        }
        emitEffect(PreviewContract.Effect.OpenProcessing(draftId))
    }

    private fun retakeFirstWarnedPage() {
        if (mutationClaimed || processingJob?.isActive == true) return
        val draftId = uiState.value.draftId ?: return
        val warnedImageId = uiState.value.qualityWarnings.firstOrNull()?.imageId ?: return
        executeMain {
            val index = uiState.value.pages.indexOfFirst { it.imageId == warnedImageId }
            val page = uiState.value.pages.getOrNull(index) ?: return@executeMain
            savedStateHandle[CURRENT_INDEX_KEY] = index
            updateState { copy(currentIndex = index, qualityWarnings = emptyList()) }
            emitEffect(PreviewContract.Effect.OpenSource(draftId, page.imageId))
        }
    }

    private fun mutateCurrentPage(
        operation: suspend (PreviewContract.Page) -> Unit,
        onSuccess: suspend () -> Unit = {},
    ) {
        val page = uiState.value.currentPage ?: return
        launchMutation(
            operation = { operation(page) },
            onSuccess = { onSuccess() },
            onFailure = {
                // La colección de páginas mantiene la vista coherente; no hay estado de error.
            },
        )
    }

    /** Serializa todas las escrituras de página y publica el claim antes de lanzar el coroutine. */
    private fun <T> launchMutation(
        before: () -> Unit = {},
        operation: suspend () -> T,
        onSuccess: suspend (T) -> Unit,
        onFailure: suspend (Throwable) -> Unit,
    ) {
        if (mutationClaimed || processingJob?.isActive == true) return
        mutationClaimed = true
        mutationJob = executeIo(
            before = {
                updateState {
                    copy(
                        isMutating = true,
                        failure = failure.takeUnless {
                            it == PreviewContract.Failure.MUTATION_FAILED
                        },
                    )
                }
                before()
            },
            operation = operation,
            onSuccess = { result ->
                mutationClaimed = false
                mutationJob = null
                updateState { copy(isMutating = false) }
                onSuccess(result)
            },
            onFailure = { error ->
                mutationClaimed = false
                mutationJob = null
                updateState {
                    copy(
                        isMutating = false,
                        failure = if (failure == PreviewContract.Failure.LOAD_FAILED) {
                            failure
                        } else {
                            PreviewContract.Failure.MUTATION_FAILED
                        },
                    )
                }
                onFailure(error)
            },
            onCancellation = {
                mutationClaimed = false
                mutationJob = null
                updateState { copy(isMutating = false) }
            },
        )
    }

    private companion object {
        const val MIN_CROP_SPAN = 500
        val FULL_CROP = ImageCrop(0, 0, ImageCrop.FRACTION_MAX, ImageCrop.FRACTION_MAX)
    }
}

private const val CURRENT_INDEX_KEY = "preview.currentIndex"

private fun previewInitialState(savedStateHandle: SavedStateHandle): PreviewContract.State {
    val draftId = DraftId.parse(savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID))
    return PreviewContract.State(
        draftId = draftId,
        currentIndex = savedStateHandle.get<Int>(CURRENT_INDEX_KEY) ?: 0,
        failure = if (draftId == null) {
            PreviewContract.Failure.INVALID_ROUTE
        } else {
            null
        },
    )
}
