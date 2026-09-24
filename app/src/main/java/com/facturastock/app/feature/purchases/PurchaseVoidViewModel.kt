package com.facturastock.app.feature.purchases

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseVoidRequest
import com.facturastock.app.domain.repository.PurchaseVoidResult
import com.facturastock.app.domain.usecase.PreviewPurchaseVoidUseCase
import com.facturastock.app.domain.usecase.VoidPurchaseUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
class PurchaseVoidViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val previewPurchaseVoid: PreviewPurchaseVoidUseCase,
    private val voidPurchase: VoidPurchaseUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<
    PurchaseVoidContract.State,
    PurchaseVoidContract.Action,
    PurchaseVoidContract.Effect,
    >(
    initialState = restoredPurchaseVoidState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private val submitMutex = Mutex()
    private var previewJob: Job? = null

    init {
        loadPreview()
    }

    override fun onAction(action: PurchaseVoidContract.Action) {
        when (action) {
            PurchaseVoidContract.Action.Load,
            PurchaseVoidContract.Action.Retry,
            -> loadPreview()

            is PurchaseVoidContract.Action.ReasonChanged -> updateReason(action.reason)
            is PurchaseVoidContract.Action.ConfirmationChanged -> updateConfirmation(
                action.confirmed,
            )
            PurchaseVoidContract.Action.Submit -> submit()
            PurchaseVoidContract.Action.BackSelected -> executeMain {
                emitEffect(PurchaseVoidContract.Effect.Back)
            }
        }
    }

    private fun loadPreview() {
        val purchaseId = uiState.value.purchaseId
        if (purchaseId == null) {
            executeMain { emitEffect(PurchaseVoidContract.Effect.CloseInvalidRoute) }
            return
        }
        previewJob?.cancel()
        previewJob = executeIo(
            before = {
                updateState {
                    copy(
                        isLoading = true,
                        preview = null,
                        confirmed = false,
                        failure = null,
                    )
                }
            },
            operation = { previewPurchaseVoid(purchaseId) },
            onSuccess = ::handlePreview,
            onFailure = {
                updateState {
                    copy(
                        isLoading = false,
                        preview = null,
                        failure = PurchaseVoidContract.Failure.LOAD_FAILED,
                    )
                }
            },
        )
    }

    private suspend fun handlePreview(result: PreviewPurchaseVoidResult) {
        when (result) {
            is PreviewPurchaseVoidResult.Ready -> updateState {
                copy(
                    isLoading = false,
                    preview = result.preview,
                    confirmed = false,
                    failure = null,
                )
            }
            is PreviewPurchaseVoidResult.AlreadyVoided -> {
                updateState { copy(isLoading = false) }
                emitEffect(PurchaseVoidContract.Effect.Completed(result.purchaseId))
            }
            PreviewPurchaseVoidResult.NoActiveBusiness -> previewFailure(
                PurchaseVoidContract.Failure.NO_ACTIVE_BUSINESS,
            )
            PreviewPurchaseVoidResult.NotFound -> previewFailure(
                PurchaseVoidContract.Failure.NOT_FOUND,
            )
            is PreviewPurchaseVoidResult.NotPosted -> previewFailure(
                PurchaseVoidContract.Failure.NOT_POSTED,
            )
            PreviewPurchaseVoidResult.Unauthorized -> previewFailure(
                PurchaseVoidContract.Failure.UNAUTHORIZED,
            )
            PreviewPurchaseVoidResult.RetryableConflict -> previewFailure(
                PurchaseVoidContract.Failure.RETRYABLE_CONFLICT,
            )
        }
    }

    private fun previewFailure(failure: PurchaseVoidContract.Failure) {
        updateState {
            copy(
                isLoading = false,
                preview = null,
                confirmed = false,
                failure = failure,
            )
        }
    }

    private fun updateReason(reason: String) {
        executeMain {
            val safeReason = reason.take(PurchaseVoidRequest.MAX_REASON_LENGTH)
            savedStateHandle[REASON_KEY] = safeReason
            updateState {
                copy(
                    reason = safeReason,
                    failure = failure.takeUnless {
                        it == PurchaseVoidContract.Failure.INVALID_REASON
                    },
                )
            }
        }
    }

    private fun updateConfirmation(confirmed: Boolean) {
        executeMain {
            updateState {
                copy(
                    confirmed = confirmed,
                    failure = failure.takeUnless {
                        it == PurchaseVoidContract.Failure.CONFIRMATION_REQUIRED ||
                            (confirmed && it == PurchaseVoidContract.Failure.IMPACT_CHANGED)
                    },
                )
            }
        }
    }

    private fun submit() {
        executeMain {
            if (!submitMutex.tryLock()) return@executeMain
            try {
                val state = uiState.value
                val purchaseId = state.purchaseId
                val preview = state.preview
                when {
                    purchaseId == null -> {
                        emitEffect(PurchaseVoidContract.Effect.CloseInvalidRoute)
                        return@executeMain
                    }
                    preview == null -> {
                        updateState {
                            copy(failure = PurchaseVoidContract.Failure.LOAD_FAILED)
                        }
                        return@executeMain
                    }
                    !state.reasonIsValid -> {
                        updateState {
                            copy(failure = PurchaseVoidContract.Failure.INVALID_REASON)
                        }
                        return@executeMain
                    }
                    !state.confirmed -> {
                        updateState {
                            copy(failure = PurchaseVoidContract.Failure.CONFIRMATION_REQUIRED)
                        }
                        return@executeMain
                    }
                }

                updateState { copy(isSubmitting = true, failure = null) }
                val result = withContext(dispatcherProvider.io) {
                    voidPurchase(
                        PurchaseVoidRequest(
                            purchaseId = purchaseId,
                            reason = state.reason,
                            expectedImpactHash = preview.expectedImpactHash,
                            confirmed = true,
                        ),
                    )
                }
                handleVoidResult(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (failure.isInsufficientStorage()) {
                    // Room ya revirtió la transacción. Conservamos el impacto, el motivo y
                    // la confirmación para que liberar espacio + tocar de nuevo sea suficiente.
                    updateState {
                        copy(
                            isSubmitting = false,
                            failure = PurchaseVoidContract.Failure.STORAGE_FULL,
                        )
                    }
                } else {
                    updateState {
                        copy(
                            isSubmitting = false,
                            preview = null,
                            confirmed = false,
                            failure = PurchaseVoidContract.Failure.SUBMIT_FAILED,
                        )
                    }
                }
            } finally {
                submitMutex.unlock()
            }
        }
    }

    private suspend fun handleVoidResult(result: PurchaseVoidResult) {
        when (result) {
            is PurchaseVoidResult.Voided -> complete(result.purchaseId)
            is PurchaseVoidResult.AlreadyVoided -> complete(result.purchaseId)
            PurchaseVoidResult.ConfirmationRequired -> submitFailure(
                PurchaseVoidContract.Failure.CONFIRMATION_REQUIRED,
                clearConfirmation = true,
            )
            PurchaseVoidResult.InvalidReason -> submitFailure(
                PurchaseVoidContract.Failure.INVALID_REASON,
            )
            PurchaseVoidResult.NoActiveBusiness -> submitFailure(
                PurchaseVoidContract.Failure.NO_ACTIVE_BUSINESS,
                clearPreview = true,
            )
            PurchaseVoidResult.NotFound -> submitFailure(
                PurchaseVoidContract.Failure.NOT_FOUND,
                clearPreview = true,
            )
            is PurchaseVoidResult.NotPosted -> submitFailure(
                PurchaseVoidContract.Failure.NOT_POSTED,
                clearPreview = true,
            )
            PurchaseVoidResult.Unauthorized -> submitFailure(
                PurchaseVoidContract.Failure.UNAUTHORIZED,
                clearPreview = true,
            )
            is PurchaseVoidResult.ImpactChanged -> updateState {
                copy(
                    isSubmitting = false,
                    preview = result.newPreview,
                    confirmed = false,
                    failure = PurchaseVoidContract.Failure.IMPACT_CHANGED,
                )
            }
            PurchaseVoidResult.RetryableConflict -> submitFailure(
                PurchaseVoidContract.Failure.RETRYABLE_CONFLICT,
                clearConfirmation = true,
                clearPreview = true,
            )
        }
    }

    private suspend fun complete(purchaseId: PurchaseId) {
        savedStateHandle[REASON_KEY] = ""
        updateState { copy(isSubmitting = false, failure = null) }
        emitEffect(PurchaseVoidContract.Effect.Completed(purchaseId))
    }

    private fun submitFailure(
        failure: PurchaseVoidContract.Failure,
        clearConfirmation: Boolean = false,
        clearPreview: Boolean = false,
    ) {
        updateState {
            copy(
                isSubmitting = false,
                preview = if (clearPreview) null else preview,
                confirmed = if (clearConfirmation || clearPreview) false else confirmed,
                failure = failure,
            )
        }
    }

    private companion object {
        const val REASON_KEY = "purchaseVoid.reason"
    }
}

private fun Throwable.isInsufficientStorage(): Boolean =
    this is StorageException && error == StorageError.InsufficientSpace

private fun restoredPurchaseVoidState(
    savedStateHandle: SavedStateHandle,
): PurchaseVoidContract.State {
    val rawPurchaseId = savedStateHandle.get<String>(RouteArgumentKeys.PURCHASE_ID)
    val purchaseId = PurchaseId.parse(rawPurchaseId)
    return PurchaseVoidContract.State(
        purchaseId = purchaseId,
        reason = savedStateHandle.get<String>("purchaseVoid.reason")
            .orEmpty()
            .take(PurchaseVoidRequest.MAX_REASON_LENGTH),
        failure = if (rawPurchaseId != null && purchaseId == null) {
            PurchaseVoidContract.Failure.INVALID_PURCHASE_ID
        } else {
            null
        },
    )
}
