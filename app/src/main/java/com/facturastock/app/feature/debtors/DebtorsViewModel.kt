package com.facturastock.app.feature.debtors

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
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
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect

@HiltViewModel
class DebtorsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeDebts: ObserveDebtsUseCase,
    private val observeDebtDetail: ObserveDebtDetailUseCase,
    private val recordPayment: RecordDebtPaymentUseCase,
    private val clock: AppClock,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<DebtorsContract.State, DebtorsContract.Action, DebtorsContract.Effect>(
    initialState = restoredDebtorsState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var observation: Job? = null

    init {
        load()
    }

    override fun onAction(action: DebtorsContract.Action) {
        when (action) {
            DebtorsContract.Action.Retry -> load()
            is DebtorsContract.Action.SearchChanged -> updateSearch(action.query)
            is DebtorsContract.Action.StatusFilterChanged -> updateFilter(action.filter)
            DebtorsContract.Action.NewDebtSelected -> executeMain {
                emitEffect(DebtorsContract.Effect.OpenNewDebt)
            }
            is DebtorsContract.Action.DebtSelected -> executeMain {
                emitEffect(DebtorsContract.Effect.OpenDebt(action.debtId))
            }
            DebtorsContract.Action.PaymentRequested -> openPaymentEditor()
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
                if (!uiState.value.isSavingPayment) {
                    updateState { copy(paymentEditor = null, failure = null) }
                }
            }
            DebtorsContract.Action.BackSelected -> executeMain {
                emitEffect(DebtorsContract.Effect.Back)
            }
        }
    }

    private fun load() {
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
            updateState {
                copy(
                    isLoading = false,
                    detail = observed,
                    paymentEditor = paymentEditor?.takeIf {
                        observed?.debt?.status == DebtStatus.OPEN
                    },
                    failure = if (observed == null) {
                        DebtorsContract.Failure.DEBT_NOT_FOUND
                    } else {
                        failure?.takeIf { isPaymentFailure(it) }
                    },
                )
            }
        }
    }

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

    private fun openPaymentEditor() {
        val debt = uiState.value.detail?.debt
            ?.takeIf { it.status == DebtStatus.OPEN && it.balance.minorUnits > 0L }
            ?: return
        executeMain {
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
        if (uiState.value.isSavingPayment) return
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
        val editor = uiState.value.paymentEditor ?: return
        if (uiState.value.isSavingPayment) return
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
                updateState {
                    copy(
                        isSavingPayment = false,
                        failure = DebtorsContract.Failure.SAVE_PAYMENT_FAILED,
                    )
                }
            },
            onCancellation = {
                updateState { copy(isSavingPayment = false) }
            },
        )
    }

    private suspend fun handlePaymentResult(result: RecordDebtPaymentResult) {
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
