package com.facturastock.app.feature.debtors

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.domain.usecase.VoidSaleUseCase
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.usecase.ObserveDebtDetailUseCase
import com.facturastock.app.domain.usecase.ObserveDebtsUseCase
import com.facturastock.app.domain.usecase.RecordDebtPaymentUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
class DebtorsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeDebts: ObserveDebtsUseCase,
    private val observeDebtDetail: ObserveDebtDetailUseCase,
    private val recordPayment: RecordDebtPaymentUseCase,
    private val clock: AppClock,
    dispatcherProvider: DispatcherProvider,
    private val voidSale: VoidSaleUseCase,
    private val configuration: AppConfigurationRepository,
) : UdfViewModel<DebtorsContract.State, DebtorsContract.Action, DebtorsContract.Effect>(
    initialState = restoredDebtorsState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var observation: Job? = null
    private var businessObservation: Job? = null
    private var activeBusinessId: BusinessId? = null
    private var latestDetail: DebtDetail? = null
    private var deleteContext: DeleteContext? = null
    private var deleteSequence = 0L
    private var deletePreviewJob: Job? = null
    private var deletePreviewPending = false
    private var deleteCommitPending = false
    private var deleteNavigationSent = false
    private var paymentEditorPending = false
    private var paymentCommitPending = false
    private var paymentBusinessGeneration = 0L
    private var fullPaymentAttempt: FullPaymentAttempt? = null
    private var fullPaymentNavigationSent = false
    private var fullPaymentNavigationClaimed = false
    private var completedPaymentBusinessId: BusinessId? = null

    init {
        load()
    }

    private fun observeBusinessContext() {
        if (businessObservation?.isActive == true) return
        businessObservation = executeMain {
            configuration.observe().map { it.activeBusinessId }.distinctUntilChanged()
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    invalidateFullPaymentContext()
                    activeBusinessId = null
                    invalidateDeleteContext()
                    updateState {
                        copy(
                            deleteTarget = deleteTarget.takeIf { deleteCommitPending },
                            deletePreview = null, isLoadingDeletePreview = false,
                            deleteFailure = DebtorsContract.DeleteFailure.LOAD_FAILED,
                            failure = DebtorsContract.Failure.LOAD_FAILED,
                        )
                    }
                }.collect { businessId ->
                    if (activeBusinessId != businessId) invalidateFullPaymentContext()
                    activeBusinessId = businessId
                    if (deleteContext?.target?.businessId?.let { it != businessId } == true) {
                        invalidateDeleteContext()
                        updateState {
                            copy(
                                deleteTarget = deleteTarget.takeIf { deleteCommitPending },
                                deletePreview = null,
                                isLoadingDeletePreview = false,
                                deleteFailure = DebtorsContract.DeleteFailure.CONTEXT_CHANGED,
                            )
                        }
                    }
                }
        }
    }

    override fun onAction(action: DebtorsContract.Action) {
        when (action) {
            DebtorsContract.Action.Retry -> load()
            is DebtorsContract.Action.SearchChanged -> updateSearch(action.query)
            is DebtorsContract.Action.StatusFilterChanged -> updateFilter(action.filter)
            DebtorsContract.Action.NewDebtSelected -> executeMain {
                if (!deleteCommitPending && !paymentCommitPending && !deleteNavigationSent && !fullPaymentNavigationSent) emitEffect(DebtorsContract.Effect.OpenNewDebt)
            }
            is DebtorsContract.Action.DebtSelected -> executeMain {
                if (!deleteCommitPending && !paymentCommitPending && !deleteNavigationSent && !fullPaymentNavigationSent) emitEffect(DebtorsContract.Effect.OpenDebt(action.debtId))
            }
            DebtorsContract.Action.PaymentRequested -> recordFullPayment()
            DebtorsContract.Action.PartialPaymentRequested -> openPaymentEditor()
            is DebtorsContract.Action.PaymentAmountChanged -> updatePaymentEditor {
                copy(amountInput = action.value.take(MAX_AMOUNT_INPUT_LENGTH), occurredAt = null)
            }
            is DebtorsContract.Action.PaymentMethodChanged -> updatePaymentEditor {
                copy(method = action.method, occurredAt = null)
            }
            is DebtorsContract.Action.PaymentNoteChanged -> updatePaymentEditor {
                copy(
                    noteInput = action.value.take(DebtorsContract.MAX_NOTE_LENGTH + 1),
                    occurredAt = null,
                )
            }
            is DebtorsContract.Action.PaymentReferenceChanged -> updatePaymentEditor {
                copy(
                    referenceInput = action.value.take(DebtorsContract.MAX_REFERENCE_LENGTH + 1),
                    occurredAt = null,
                )
            }
            DebtorsContract.Action.PaymentConfirmed -> confirmPayment()
            DebtorsContract.Action.PaymentDismissed -> executeMain {
                if (!paymentCommitPending && !uiState.value.isSavingPayment) {
                    updateState { copy(paymentEditor = null, failure = null) }
                }
            }
            DebtorsContract.Action.BackSelected -> {
                if (!deleteCommitPending && !paymentCommitPending && !deleteNavigationSent && !fullPaymentNavigationSent) {
                    when {
                        uiState.value.deleteTarget != null || deleteContext != null -> dismissDelete()
                        uiState.value.paymentEditor != null || paymentEditorPending -> onAction(DebtorsContract.Action.PaymentDismissed)
                        else -> executeMain { emitEffect(DebtorsContract.Effect.Back) }
                    }
                }
            }
            DebtorsContract.Action.DeleteRequested -> requestDelete()
            DebtorsContract.Action.DeleteConfirmed -> confirmDelete()
            DebtorsContract.Action.DeleteDismissed -> dismissDelete()
            DebtorsContract.Action.DeletePreviewRetry -> retryDeletePreview()
        }
    }

    private fun load() {
        if (deleteCommitPending || paymentCommitPending || deleteNavigationSent || fullPaymentNavigationSent) return
        configuration.retryObservation()
        observeBusinessContext()
        if (uiState.value.failure == DebtorsContract.Failure.INVALID_DEBT_ID) {
            executeMain { emitEffect(DebtorsContract.Effect.CloseInvalidRoute) }
            return
        }
        observation?.cancel()
        observation = executeMain {
            updateState {
                copy(
                    isLoading = if (debtId == null) allDebts.isEmpty() else detail == null,
                    failure = null,
                )
            }
            try {
                val debtId = uiState.value.debtId
                if (debtId == null) observeList() else observeDetail(debtId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateState { copy(isLoading = false, failure = DebtorsContract.Failure.LOAD_FAILED) }
            }
        }
    }

    private suspend fun observeList() {
        observeDebts(includePaid = true).collect { debts ->
            updateState {
                copy(
                    isLoading = false,
                    allDebts = debts,
                    debts = filterDebts(debts, query, statusFilter),
                    totalOpenBalance = calculateOpenBalance(debts),
                    failure = null,
                )
            }
        }
    }

    private suspend fun observeDetail(debtId: DebtId) {
        observeDebtDetail(debtId).collect { observed ->
            if (fullPaymentNavigationSent) return@collect
            latestDetail = observed
            if (deleteNavigationSent) return@collect
            val context = deleteContext
            if (context != null && observed != null && observed.debt != context.target && !deleteCommitPending) {
                invalidateDeleteContext()
                updateState { copy(deletePreview = null, isLoadingDeletePreview = false, deleteFailure = DebtorsContract.DeleteFailure.STALE) }
            }
            if (context != null && observed == null && !deleteCommitPending && !deletePreviewPending) {
                invalidateDeleteContext()
                updateState { copy(deletePreview = null, deleteFailure = DebtorsContract.DeleteFailure.NOT_FOUND) }
            }
            // Room can hide the debt before confirm/preview returns AlreadyVoided. Keep the
            // reviewed content until that result; a business switch invalidates the request separately.
            val keepReviewedDetail = observed == null && (uiState.value.deleteTarget != null || deleteCommitPending)
            updateState {
                copy(
                    isLoading = false,
                    detail = if (keepReviewedDetail) detail else observed,
                    paymentEditor = paymentEditor?.takeIf { observed?.debt?.status == DebtStatus.OPEN },
                    failure = if (observed == null && !keepReviewedDetail) DebtorsContract.Failure.DEBT_NOT_FOUND
                    else failure?.takeIf { isPaymentFailure(it) },
                )
            }
        }
    }

    private fun requestDelete() {
        val state = uiState.value
        val target = latestDetail?.debt ?: return
        if (deleteContext != null || state.deleteTarget != null || deleteCommitPending ||
            paymentCommitPending || paymentEditorPending || state.paymentEditor != null ||
            state.isSavingPayment || state.isLoading || state.failure != null || deleteNavigationSent || fullPaymentNavigationSent ||
            target.businessId != activeBusinessId || target.debtId != state.debtId || state.detail?.debt != target
        ) return
        loadDeletePreview(DeleteContext(++deleteSequence, target))
    }

    private fun retryDeletePreview() {
        val state = uiState.value
        if (state.deleteTarget == null || deleteCommitPending || deletePreviewPending ||
            paymentCommitPending || state.paymentEditor != null || deleteNavigationSent || fullPaymentNavigationSent
        ) return
        val target = latestDetail?.debt ?: state.deleteTarget
        if (target.businessId != activeBusinessId || target.debtId != state.debtId) return
        loadDeletePreview(DeleteContext(++deleteSequence, target))
    }

    private fun loadDeletePreview(context: DeleteContext) {
        deletePreviewJob?.cancel()
        deleteContext = context
        deletePreviewPending = true
        deletePreviewJob = executeMain {
            updateState {
                copy(deleteTarget = context.target, deletePreview = null, isLoadingDeletePreview = true, deleteFailure = null)
            }
            try {
                val result = withContext(dispatcherProvider.io) {
                    voidSale.preview(context.target.businessId, context.target.saleId)
                }
                if (!isDeleteRequestCurrent(context)) return@executeMain
                when (result) {
                    is SaleVoidPreviewResult.Ready -> {
                        val failure = when {
                            latestDetail?.debt != context.target -> DebtorsContract.DeleteFailure.STALE
                            !result.preview.matches(context.target) -> DebtorsContract.DeleteFailure.INVALID_HISTORY
                            else -> null
                        }
                        updateState { copy(deletePreview = result.preview.takeIf { failure == null }, deleteFailure = failure) }
                    }
                    SaleVoidPreviewResult.AlreadyVoided -> finishDeletion(context)
                    else -> updateState { copy(deletePreview = null, deleteFailure = result.toDeleteFailure()) }
                }
            } catch (cancelled: CancellationException) {
                if (isDeleteRequestCurrent(context)) updateState { copy(deletePreview = null) }
                throw cancelled
            } catch (_: Throwable) {
                if (isDeleteRequestCurrent(context)) updateState {
                    copy(deletePreview = null, deleteFailure = DebtorsContract.DeleteFailure.LOAD_FAILED)
                }
            } finally {
                if (deleteContext == context) {
                    deletePreviewPending = false
                    updateState { copy(isLoadingDeletePreview = false) }
                }
            }
        }
    }

    private fun confirmDelete() {
        val context = deleteContext ?: return
        val state = uiState.value
        val preview = state.deletePreview ?: return
        if (deleteCommitPending || deletePreviewPending || paymentCommitPending || paymentEditorPending ||
            state.paymentEditor != null || state.isSavingPayment || !isDeleteRequestCurrent(context) ||
            latestDetail?.debt != context.target || !preview.matches(context.target)
        ) return
        // Reserve before launching: two confirmations in the same frame cannot mutate twice.
        deleteCommitPending = true
        executeMain {
            updateState { copy(isDeletingDebt = true, deleteFailure = null) }
            try {
                val result = withContext(dispatcherProvider.io) { voidSale.confirm(preview) }
                if (!isDeleteRequestCurrent(context)) return@executeMain
                if (latestDetail?.debt?.let { it != context.target } == true) {
                    updateState { copy(deletePreview = null, deleteFailure = DebtorsContract.DeleteFailure.STALE) }
                    return@executeMain
                }
                when (result) {
                    SaleVoidResult.Voided, SaleVoidResult.AlreadyVoided -> finishDeletion(context)
                    SaleVoidResult.Stale -> updateState {
                        // Reviewing again is a separate user action, never an automatic commit retry.
                        copy(deletePreview = null, deleteFailure = DebtorsContract.DeleteFailure.STALE)
                    }
                    else -> updateState { copy(deletePreview = null, deleteFailure = result.toDeleteFailure()) }
                }
            } catch (cancelled: CancellationException) {
                if (isDeleteRequestCurrent(context)) updateState { copy(deletePreview = null) }
                throw cancelled
            } catch (_: Throwable) {
                if (isDeleteRequestCurrent(context)) updateState {
                    copy(deletePreview = null, deleteFailure = DebtorsContract.DeleteFailure.OPERATION_FAILED)
                }
            } finally {
                deleteCommitPending = false
                updateState { copy(isDeletingDebt = false) }
                if (deleteContext == null && !deleteNavigationSent && !fullPaymentNavigationSent) {
                    updateState {
                        copy(
                            deleteTarget = null,
                            detail = latestDetail,
                            failure = if (latestDetail == null) DebtorsContract.Failure.DEBT_NOT_FOUND else failure,
                        )
                    }
                }
            }
        }
    }

    private fun dismissDelete() {
        if (deleteCommitPending || deleteNavigationSent || fullPaymentNavigationSent) return
        invalidateDeleteContext()
        executeMain {
            updateState {
                copy(
                    deleteTarget = null, deletePreview = null, isLoadingDeletePreview = false, deleteFailure = null,
                    detail = latestDetail,
                    failure = if (latestDetail == null && debtId != null) DebtorsContract.Failure.DEBT_NOT_FOUND else failure,
                )
            }
        }
    }

    private suspend fun finishDeletion(context: DeleteContext) {
        if (!isDeleteRequestCurrent(context) || deleteNavigationSent || fullPaymentNavigationSent) return
        deleteNavigationSent = true
        deleteContext = null
        deletePreviewJob = null
        deletePreviewPending = false
        updateState {
            copy(deleteTarget = null, deletePreview = null, isLoadingDeletePreview = false, deleteFailure = null, failure = null)
        }
        emitEffect(DebtorsContract.Effect.DebtDeleted)
    }

    private fun invalidateDeleteContext() {
        deleteContext = null
        deletePreviewPending = false
        deletePreviewJob?.cancel()
        deletePreviewJob = null
    }

    private fun isDeleteRequestCurrent(context: DeleteContext): Boolean =
        deleteContext == context && activeBusinessId == context.target.businessId &&
            uiState.value.debtId == context.target.debtId && !deleteNavigationSent && !fullPaymentNavigationSent

    private data class DeleteContext(val requestId: Long, val target: DebtSummary)

    private fun SaleVoidPreview.matches(debt: DebtSummary): Boolean =
        businessId == debt.businessId && saleId == debt.saleId && total == debt.originalAmount &&
            debtBalanceToCancel != null && debtBalanceToCancel == debt.balance

    private fun updateSearch(rawQuery: String) {
        val query = rawQuery.take(DebtorsContract.MAX_QUERY_LENGTH)
        savedStateHandle[QUERY_KEY] = query
        executeMain {
            updateState {
                copy(
                    query = query,
                    debts = filterDebts(allDebts, query, statusFilter),
                )
            }
        }
    }

    private fun updateFilter(filter: DebtorsContract.StatusFilter) {
        savedStateHandle[FILTER_KEY] = filter.name
        executeMain {
            updateState {
                copy(
                    statusFilter = filter,
                    debts = filterDebts(allDebts, query, filter),
                )
            }
        }
    }

    private fun recordFullPayment() {
        val state = uiState.value
        val target = latestDetail?.debt ?: return
        if (paymentCommitPending || paymentEditorPending || state.paymentEditor != null || state.isSavingPayment ||
            deleteContext != null || state.deleteTarget != null || deleteCommitPending || deleteNavigationSent ||
            fullPaymentNavigationSent || state.isLoading || state.failure == DebtorsContract.Failure.LOAD_FAILED ||
            target.businessId != activeBusinessId || target.debtId != state.debtId || state.detail?.debt != target ||
            target.status != DebtStatus.OPEN || target.balance.minorUnits <= 0L
        ) return
        val attempt = fullPaymentAttempt?.takeIf {
            it.target == target && it.businessGeneration == paymentBusinessGeneration
        } ?: FullPaymentAttempt(
            target,
            RecordDebtPaymentCommand(
                debtId = target.debtId,
                expectedVersion = target.version,
                amount = target.balance,
                method = DebtPaymentMethod.OTHER,
                occurredAt = clock.now(),
            ),
            paymentBusinessGeneration,
        )
        fullPaymentAttempt = attempt
        paymentCommitPending = true
        executeMain {
            updateState { copy(isSavingPayment = true, failure = null) }
            try {
                val result = withContext(dispatcherProvider.io) {
                    if (configuration.current().activeBusinessId != target.businessId) RecordDebtPaymentResult.NoActiveBusiness
                    else recordPayment.forBusiness(target.businessId, attempt.command)
                }
                if (!isFullPaymentCurrent(attempt)) return@executeMain
                when (result) {
                    is RecordDebtPaymentResult.Recorded -> finishFullPayment(attempt, result.debt, result.payment)
                    is RecordDebtPaymentResult.AlreadyRecorded -> finishFullPayment(attempt, result.debt, result.payment)
                    else -> {
                        if (result == RecordDebtPaymentResult.Stale || result == RecordDebtPaymentResult.AlreadyPaid ||
                            result == RecordDebtPaymentResult.AmountExceedsBalance || result == RecordDebtPaymentResult.CurrencyMismatch ||
                            result == RecordDebtPaymentResult.InvalidPayment || result == RecordDebtPaymentResult.NotFound ||
                            result == RecordDebtPaymentResult.NoActiveBusiness
                        ) fullPaymentAttempt = null
                        updatePaymentFailure(result.paymentFailure())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The same version/amount/time is retained: a lost response cannot create a
                // different payment on a second tap before Room publishes the original result.
                if (isFullPaymentCurrent(attempt)) updatePaymentFailure(DebtorsContract.Failure.SAVE_PAYMENT_FAILED)
            } finally {
                paymentCommitPending = false
                updateState { copy(isSavingPayment = false) }
            }
        }
    }

    private suspend fun finishFullPayment(
        attempt: FullPaymentAttempt,
        debt: DebtSummary,
        payment: com.facturastock.app.domain.model.DebtPayment,
    ) {
        val target = attempt.target
        if (debt.businessId != target.businessId || debt.debtId != target.debtId || debt.saleId != target.saleId ||
            debt.status != DebtStatus.PAID || debt.balance.minorUnits != 0L || debt.originalAmount != target.originalAmount ||
            payment.debtId != target.debtId || payment.amount != attempt.command.amount ||
            payment.expectedDebtVersion != attempt.command.expectedVersion
        ) {
            updatePaymentFailure(DebtorsContract.Failure.SAVE_PAYMENT_FAILED)
            return
        }
        val current = latestDetail ?: return
        if (current.debt.version > debt.version) {
            fullPaymentAttempt = null
            updatePaymentFailure(DebtorsContract.Failure.STALE_DEBT)
            return
        }
        val paidDetail = current.copy(
            debt = debt,
            payments = current.payments.filterNot { it.paymentId == payment.paymentId } + payment,
        )
        latestDetail = paidDetail
        fullPaymentAttempt = null
        fullPaymentNavigationSent = true
        fullPaymentNavigationClaimed = false
        completedPaymentBusinessId = target.businessId
        updateState { copy(detail = paidDetail, isSavingPayment = false, paymentEditor = null, failure = null) }
        emitEffect(DebtorsContract.Effect.FullPaymentSaved)
    }

    /** Avoid consuming a buffered success for a business that is no longer active. */
    fun claimFullPaymentNavigation(): Boolean {
        if (!fullPaymentNavigationSent || fullPaymentNavigationClaimed || completedPaymentBusinessId != activeBusinessId) return false
        fullPaymentNavigationClaimed = true
        return true
    }

    private fun isFullPaymentCurrent(attempt: FullPaymentAttempt): Boolean {
        val current = latestDetail?.debt ?: return false
        return attempt.businessGeneration == paymentBusinessGeneration && activeBusinessId == attempt.target.businessId &&
            uiState.value.debtId == attempt.target.debtId && current.businessId == attempt.target.businessId &&
            current.debtId == attempt.target.debtId && current.saleId == attempt.target.saleId && !fullPaymentNavigationSent
    }

    private fun invalidateFullPaymentContext() {
        paymentBusinessGeneration++
        fullPaymentAttempt = null
        fullPaymentNavigationSent = false
        fullPaymentNavigationClaimed = false
        completedPaymentBusinessId = null
    }

    private data class FullPaymentAttempt(
        val target: DebtSummary,
        val command: RecordDebtPaymentCommand,
        val businessGeneration: Long,
    )

    private fun openPaymentEditor() {
        if (deleteContext != null || uiState.value.deleteTarget != null || deleteCommitPending ||
            paymentCommitPending || paymentEditorPending || deleteNavigationSent || fullPaymentNavigationSent
        ) return
        val debt = uiState.value.detail?.debt
            ?.takeIf { it.status == DebtStatus.OPEN && it.balance.minorUnits > 0L }
            ?: return
        if (debt.businessId != activeBusinessId) return
        paymentEditorPending = true
        executeMain {
            paymentEditorPending = false
            updateState {
                copy(
                    paymentEditor = DebtorsContract.PaymentEditor(
                        debtId = debt.debtId,
                        expectedVersion = debt.version,
                        balance = debt.balance,
                    ),
                    failure = null,
                )
            }
        }
    }

    private fun updatePaymentEditor(
        transform: DebtorsContract.PaymentEditor.() -> DebtorsContract.PaymentEditor,
    ) {
        if (paymentCommitPending || deleteCommitPending || uiState.value.isSavingPayment) return
        executeMain {
            updateState {
                copy(
                    paymentEditor = paymentEditor?.transform(),
                    failure = null,
                )
            }
        }
    }

    private fun confirmPayment() {
        if (deleteContext != null || uiState.value.deleteTarget != null || deleteCommitPending || deleteNavigationSent || fullPaymentNavigationSent) return
        val editor = uiState.value.paymentEditor ?: return
        if (paymentCommitPending || deleteCommitPending || uiState.value.isSavingPayment) return
        val amount = editor.amount
        if (amount == null || !editor.isValid) {
            executeMain {
                updateState {
                    copy(
                        paymentEditor = paymentEditor?.copy(submitAttempted = true),
                        failure = DebtorsContract.Failure.INVALID_PAYMENT,
                    )
                }
            }
            return
        }
        val occurredAt = editor.occurredAt ?: clock.now()
        val command = RecordDebtPaymentCommand(
            debtId = editor.debtId,
            expectedVersion = editor.expectedVersion,
            amount = amount,
            method = editor.method,
            note = editor.noteInput,
            reference = editor.referenceInput,
            occurredAt = occurredAt,
        )
        paymentCommitPending = true
        executeIo(
            before = {
                updateState {
                    copy(
                        isSavingPayment = true,
                        paymentEditor = paymentEditor?.copy(
                            submitAttempted = true,
                            occurredAt = occurredAt,
                        ),
                        failure = null,
                    )
                }
            },
            operation = { recordPayment(command) },
            onSuccess = ::handlePaymentResult,
            onFailure = {
                paymentCommitPending = false
                updateState {
                    copy(
                        isSavingPayment = false,
                        failure = DebtorsContract.Failure.SAVE_PAYMENT_FAILED,
                    )
                }
            },
            onCancellation = {
                paymentCommitPending = false
                updateState { copy(isSavingPayment = false) }
            },
        )
    }

    private suspend fun handlePaymentResult(result: RecordDebtPaymentResult) {
        paymentCommitPending = false
        when (result) {
            is RecordDebtPaymentResult.Recorded,
            is RecordDebtPaymentResult.AlreadyRecorded,
            -> {
                updateState {
                    copy(
                        isSavingPayment = false,
                        paymentEditor = null,
                        failure = null,
                    )
                }
                emitEffect(DebtorsContract.Effect.PaymentSaved)
            }
            RecordDebtPaymentResult.Stale,
            RecordDebtPaymentResult.RetryableConflict,
            RecordDebtPaymentResult.AlreadyPaid,
            -> updatePaymentFailure(DebtorsContract.Failure.STALE_DEBT)
            RecordDebtPaymentResult.AmountExceedsBalance ->
                updatePaymentFailure(DebtorsContract.Failure.PAYMENT_EXCEEDS_BALANCE)
            RecordDebtPaymentResult.CurrencyMismatch,
            RecordDebtPaymentResult.InvalidPayment,
            -> updatePaymentFailure(DebtorsContract.Failure.INVALID_PAYMENT)
            RecordDebtPaymentResult.NotFound,
            RecordDebtPaymentResult.NoActiveBusiness,
            -> updatePaymentFailure(DebtorsContract.Failure.DEBT_NOT_FOUND)
            RecordDebtPaymentResult.OnlineRequired ->
                updatePaymentFailure(DebtorsContract.Failure.ONLINE_REQUIRED)
            RecordDebtPaymentResult.RemoteRejected ->
                updatePaymentFailure(DebtorsContract.Failure.REMOTE_REJECTED)
        }
    }

    private fun updatePaymentFailure(failure: DebtorsContract.Failure) {
        updateState { copy(isSavingPayment = false, failure = failure) }
    }

    override fun onCleared() {
        observation?.cancel()
        businessObservation?.cancel()
        deletePreviewJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val QUERY_KEY = "debtors.query"
        const val FILTER_KEY = "debtors.filter"
        const val MAX_AMOUNT_INPUT_LENGTH = 64
    }
}

private fun restoredDebtorsState(savedStateHandle: SavedStateHandle): DebtorsContract.State {
    val rawDebtId = savedStateHandle.get<String>(RouteArgumentKeys.DEBT_ID)
    val debtId = DebtId.parse(rawDebtId)
    val filter = savedStateHandle.get<String>("debtors.filter")
        ?.let { raw ->
            DebtorsContract.StatusFilter.entries.firstOrNull { it.name == raw }
        }
        ?: DebtorsContract.StatusFilter.OPEN
    return DebtorsContract.State(
        debtId = debtId,
        query = savedStateHandle.get<String>("debtors.query")
            .orEmpty()
            .take(DebtorsContract.MAX_QUERY_LENGTH),
        statusFilter = filter,
        failure = if (rawDebtId != null && debtId == null) {
            DebtorsContract.Failure.INVALID_DEBT_ID
        } else {
            null
        },
    )
}

private fun filterDebts(
    debts: List<DebtSummary>,
    query: String,
    filter: DebtorsContract.StatusFilter,
): List<DebtSummary> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return debts.filter { debt ->
        val matchesStatus = when (filter) {
            DebtorsContract.StatusFilter.OPEN -> debt.status == DebtStatus.OPEN
            DebtorsContract.StatusFilter.PAID -> debt.status == DebtStatus.PAID
            DebtorsContract.StatusFilter.ALL -> true
        }
        matchesStatus && (
            normalizedQuery.isEmpty() ||
                debt.debtorName.lowercase(Locale.ROOT).contains(normalizedQuery)
            )
    }
}

private fun calculateOpenBalance(debts: List<DebtSummary>): Money? {
    val balances = debts.filter { it.status == DebtStatus.OPEN }.map(DebtSummary::balance)
    val first = balances.firstOrNull() ?: return debts.firstOrNull()?.originalAmount?.let {
        Money.zero(it.currency)
    }
    if (balances.any { it.currency != first.currency }) return null
    return balances.drop(1).fold(first, Money::plus)
}

private fun isPaymentFailure(failure: DebtorsContract.Failure): Boolean = when (failure) {
    DebtorsContract.Failure.INVALID_PAYMENT,
    DebtorsContract.Failure.STALE_DEBT,
    DebtorsContract.Failure.PAYMENT_EXCEEDS_BALANCE,
    DebtorsContract.Failure.ONLINE_REQUIRED,
    DebtorsContract.Failure.REMOTE_REJECTED,
    DebtorsContract.Failure.SAVE_PAYMENT_FAILED,
    -> true
    else -> false
}

private fun SaleVoidPreviewResult.toDeleteFailure(): DebtorsContract.DeleteFailure = when (this) {
    SaleVoidPreviewResult.NoActiveBusiness -> DebtorsContract.DeleteFailure.NO_ACTIVE_BUSINESS
    SaleVoidPreviewResult.NotFound -> DebtorsContract.DeleteFailure.NOT_FOUND
    SaleVoidPreviewResult.Unauthorized -> DebtorsContract.DeleteFailure.UNAUTHORIZED
    SaleVoidPreviewResult.SharedBusinessUnsupported -> DebtorsContract.DeleteFailure.SHARED_BUSINESS_UNSUPPORTED
    SaleVoidPreviewResult.InvalidHistory -> DebtorsContract.DeleteFailure.INVALID_HISTORY
    is SaleVoidPreviewResult.Ready, SaleVoidPreviewResult.AlreadyVoided -> error("Successful preview has no failure")
}

private fun SaleVoidResult.toDeleteFailure(): DebtorsContract.DeleteFailure = when (this) {
    SaleVoidResult.NoActiveBusiness -> DebtorsContract.DeleteFailure.NO_ACTIVE_BUSINESS
    SaleVoidResult.NotFound -> DebtorsContract.DeleteFailure.NOT_FOUND
    SaleVoidResult.Unauthorized -> DebtorsContract.DeleteFailure.UNAUTHORIZED
    SaleVoidResult.SharedBusinessUnsupported -> DebtorsContract.DeleteFailure.SHARED_BUSINESS_UNSUPPORTED
    SaleVoidResult.InvalidHistory -> DebtorsContract.DeleteFailure.INVALID_HISTORY
    SaleVoidResult.Stale -> DebtorsContract.DeleteFailure.STALE
    SaleVoidResult.Voided, SaleVoidResult.AlreadyVoided -> error("Successful deletion has no failure")
}

private fun RecordDebtPaymentResult.paymentFailure(): DebtorsContract.Failure = when (this) {
    RecordDebtPaymentResult.Stale, RecordDebtPaymentResult.RetryableConflict, RecordDebtPaymentResult.AlreadyPaid -> DebtorsContract.Failure.STALE_DEBT
    RecordDebtPaymentResult.AmountExceedsBalance -> DebtorsContract.Failure.PAYMENT_EXCEEDS_BALANCE
    RecordDebtPaymentResult.CurrencyMismatch, RecordDebtPaymentResult.InvalidPayment -> DebtorsContract.Failure.INVALID_PAYMENT
    RecordDebtPaymentResult.NotFound, RecordDebtPaymentResult.NoActiveBusiness -> DebtorsContract.Failure.DEBT_NOT_FOUND
    RecordDebtPaymentResult.OnlineRequired -> DebtorsContract.Failure.ONLINE_REQUIRED
    RecordDebtPaymentResult.RemoteRejected -> DebtorsContract.Failure.REMOTE_REJECTED
    is RecordDebtPaymentResult.Recorded, is RecordDebtPaymentResult.AlreadyRecorded -> error("Successful payment has no failure")
}
