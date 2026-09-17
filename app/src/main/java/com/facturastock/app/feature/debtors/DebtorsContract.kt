package com.facturastock.app.feature.debtors

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.time.Instant

object DebtorsContract {
    enum class StatusFilter { OPEN, PAID, ALL }

    @Immutable
    data class PaymentEditor(
        val debtId: DebtId,
        val expectedVersion: Long,
        val balance: Money,
        val amountInput: String = "",
        val method: DebtPaymentMethod = DebtPaymentMethod.CASH,
        val noteInput: String = "",
        val referenceInput: String = "",
        val submitAttempted: Boolean = false,
        /** Conserva idempotencia al reintentar exactamente el mismo gesto. */
        val occurredAt: Instant? = null,
    ) {
        val amount: Money?
            get() {
                val canonical = amountInput.trim().replace(',', '.')
                if (canonical.isEmpty()) return null
                return try {
                    Money.fromMajor(canonical, balance.currency).takeIf {
                        it.minorUnits in 1L..balance.minorUnits
                    }
                } catch (_: DomainRuleViolation) {
                    null
                }
            }

        val isValid: Boolean
            get() = amount != null && noteInput.length <= MAX_NOTE_LENGTH &&
                referenceInput.length <= MAX_REFERENCE_LENGTH
    }

    @Immutable
    data class State(
        val debtId: DebtId? = null,
        val isLoading: Boolean = true,
        val isSavingPayment: Boolean = false,
        val allDebts: List<DebtSummary> = emptyList(),
        val debts: List<DebtSummary> = emptyList(),
        val detail: DebtDetail? = null,
        val query: String = "",
        val statusFilter: StatusFilter = StatusFilter.OPEN,
        val totalOpenBalance: Money? = null,
        val paymentEditor: PaymentEditor? = null,
        val failure: Failure? = null,
        val deleteTarget: DebtSummary? = null,
        val deletePreview: SaleVoidPreview? = null,
        val isLoadingDeletePreview: Boolean = false,
        val isDeletingDebt: Boolean = false,
        val deleteFailure: DeleteFailure? = null,
    ) : UiState

    enum class DeleteFailure {
        LOAD_FAILED, OPERATION_FAILED, STALE, CONTEXT_CHANGED, NO_ACTIVE_BUSINESS,
        NOT_FOUND, UNAUTHORIZED, SHARED_BUSINESS_UNSUPPORTED, INVALID_HISTORY,
    }

    enum class Failure {
        INVALID_DEBT_ID,
        LOAD_FAILED,
        DEBT_NOT_FOUND,
        INVALID_PAYMENT,
        STALE_DEBT,
        PAYMENT_EXCEEDS_BALANCE,
        ONLINE_REQUIRED,
        REMOTE_REJECTED,
        SAVE_PAYMENT_FAILED,
    }

    sealed interface Action : UiAction {
        data object Retry : Action
        data class SearchChanged(val query: String) : Action
        data class StatusFilterChanged(val filter: StatusFilter) : Action
        data object NewDebtSelected : Action
        data class DebtSelected(val debtId: DebtId) : Action
        data object PaymentRequested : Action
        data object PartialPaymentRequested : Action
        data class PaymentAmountChanged(val value: String) : Action
        data class PaymentMethodChanged(val method: DebtPaymentMethod) : Action
        data class PaymentNoteChanged(val value: String) : Action
        data class PaymentReferenceChanged(val value: String) : Action
        data object PaymentConfirmed : Action
        data object PaymentDismissed : Action
        data object BackSelected : Action
        data object DeleteRequested : Action
        data object DeleteConfirmed : Action
        data object DeleteDismissed : Action
        data object DeletePreviewRetry : Action
    }

    sealed interface Effect : UiEffect {
        data object OpenNewDebt : Effect
        data class OpenDebt(val debtId: DebtId) : Effect
        data object PaymentSaved : Effect
        data object FullPaymentSaved : Effect
        data object DebtDeleted : Effect
        data object Back : Effect
        data object CloseInvalidRoute : Effect
    }

    const val MAX_QUERY_LENGTH = 120
    const val MAX_NOTE_LENGTH = 500
    const val MAX_REFERENCE_LENGTH = 120
}
