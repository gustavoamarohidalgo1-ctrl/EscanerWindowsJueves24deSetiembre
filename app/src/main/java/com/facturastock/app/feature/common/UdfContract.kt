package com.facturastock.app.feature.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facturastock.app.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Marker for an immutable snapshot rendered by a feature. */
interface UiState

/** Marker for an input coming from the UI. */
interface UiAction

/** Marker for a one-off event, such as navigation or a transient notification. */
interface UiEffect

/** Keys shared with the navigation route arguments, kept free of navigation dependencies. */
object RouteArgumentKeys {
    const val DRAFT_ID = "draftId"
    const val CAPTURE_ID = "captureId"
    const val LINE_ID = "lineId"
    const val PURCHASE_ID = "purchaseId"
    const val PRODUCT_ID = "productId"
    const val DEBT_ID = "debtId"
    const val REPLACE_ID = "replaceId"
    const val SCAN_RETAKE = "scanRetake"
    const val PREFILL_BARCODE = "barcode"
    const val EDIT_PRODUCT_ID = "editProductId"
    const val REGISTRATION_REQUEST_ID = "requestId"
    const val REGISTRATION_BUSINESS_ID = "businessId"
    const val EXPECTED_PREPARED_HASH = "expectedPreparedHash"
}

interface UdfContract<S : UiState, A : UiAction, E : UiEffect> {
    val uiState: StateFlow<S>
    val effects: Flow<E>

    fun onAction(action: A)
}

/**
 * Common UDF implementation.
 *
 * State is never exposed as mutable. Effects are delivered through a buffered channel, so they
 * are consumed once and are not replayed to later collectors. Every callback passed to
 * [executeIo] runs on [DispatcherProvider.main], while only [operation] runs on IO.
 */
abstract class UdfViewModel<S : UiState, A : UiAction, E : UiEffect>(
    initialState: S,
    protected val dispatcherProvider: DispatcherProvider,
) : ViewModel(), UdfContract<S, A, E> {
    private val mutableUiState = MutableStateFlow(initialState)
    final override val uiState: StateFlow<S> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<E>(capacity = Channel.BUFFERED)
    final override val effects: Flow<E> = effectChannel.receiveAsFlow()

    /** Launches a state/effect operation on the injected main dispatcher. */
    protected fun executeMain(block: suspend () -> Unit): Job =
        viewModelScope.launch(dispatcherProvider.main) {
            block()
        }

    /** Must be called from an [executeMain] or [executeIo] main-dispatcher callback. */
    protected fun updateState(transform: S.() -> S) {
        mutableUiState.update { currentState -> currentState.transform() }
    }

    /** Must be called from an [executeMain] or [executeIo] main-dispatcher callback. */
    protected suspend fun emitEffect(effect: E) {
        effectChannel.send(effect)
    }

    /**
     * Executes blocking or repository work on IO and returns to main for every UI mutation.
     * Cancellation is structural: [onCancellation] runs synchronously on Main for local claim/
     * state cleanup, then the cancellation is always rethrown and can never become a UI error.
     */
    protected fun <T> executeIo(
        before: () -> Unit = {},
        operation: suspend () -> T,
        onSuccess: suspend (T) -> Unit,
        onFailure: suspend (Throwable) -> Unit,
        onCancellation: () -> Unit = {},
    ): Job = executeMain {
        try {
            before()
            val result = withContext(dispatcherProvider.io) {
                operation()
            }
            onSuccess(result)
        } catch (cancellation: CancellationException) {
            onCancellation()
            throw cancellation
        } catch (error: Throwable) {
            onFailure(error)
        }
    }
}
