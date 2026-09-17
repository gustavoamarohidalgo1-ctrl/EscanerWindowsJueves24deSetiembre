package com.facturastock.app.feature.debtors

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.DebtPayment
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.usecase.ObserveDebtDetailUseCase
import com.facturastock.app.domain.usecase.ObserveDebtsUseCase
import com.facturastock.app.domain.usecase.RecordDebtPaymentUseCase
import com.facturastock.app.domain.usecase.VoidSaleUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DirectDebtPaymentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val configuration = FakeAppConfigurationRepository()
    private val debts = FakeDebtDeletionRepository()
    private val voids = FakeDebtVoidRepository()
    private var now = DebtDeletionFixtures.now

    @Test
    fun `one tap records the entire current balance as OTHER and updates paid detail before Room emits`() =
        runTest(mainDispatcherRule.dispatcher) {
            val original = requireNotNull(debts.detail.value).debt
            val result = recorded(original)
            debts.paymentResult = result
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                val command = debts.paymentCommands.single()
                assertEquals(original.balance, command.amount)
                assertEquals(original.version, command.expectedVersion)
                assertEquals(original.debtId, command.debtId)
                assertEquals(DebtPaymentMethod.OTHER, command.method)
                assertEquals(now, command.occurredAt)
                assertNull(command.note)
                assertNull(command.reference)
                assertEquals(listOf(original.businessId), debts.paymentBusinesses)
                assertNull(vm.uiState.value.paymentEditor)
                assertEquals(
                    result.debt,
                    vm.uiState.value.detail
                        ?.debt,
                )
                assertEquals(
                    listOf(result.payment),
                    vm.uiState.value.detail
                        ?.payments,
                )
                assertEquals(listOf(DebtorsContract.Effect.FullPaymentSaved), effects)
                assertEquals(original, debts.detail.value?.debt)
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                vm.onAction(DebtorsContract.Action.BackSelected)
                runCurrent()
                assertEquals(1, debts.paymentCommands.size)
                assertEquals(1, effects.size)
                assertTrue(vm.claimFullPaymentNavigation())
                assertFalse(vm.claimFullPaymentNavigation())
                assertTrue(voids.confirmations.isEmpty())
                assertTrue(voids.previews.isEmpty())
            }
        }

    @Test
    fun `same frame double taps reserve one payment and exclude deletion partial editor and back`() =
        runTest(mainDispatcherRule.dispatcher) {
            debts.paymentGate = CompletableDeferred()
            debts.paymentResult = recorded()
            withViewModel { vm, effects ->
                repeat(2) { vm.onAction(DebtorsContract.Action.PaymentRequested) }
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                vm.onAction(DebtorsContract.Action.PartialPaymentRequested)
                vm.onAction(DebtorsContract.Action.BackSelected)
                runCurrent()
                assertEquals(1, debts.paymentCommands.size)
                assertTrue(vm.uiState.value.isSavingPayment)
                assertNull(vm.uiState.value.paymentEditor)
                assertNull(vm.uiState.value.deleteTarget)
                assertTrue(voids.previews.isEmpty())
                assertTrue(effects.isEmpty())
                debts.paymentGate!!.complete(Unit)
                runCurrent()
                assertFalse(vm.uiState.value.isSavingPayment)
                assertEquals(listOf(DebtorsContract.Effect.FullPaymentSaved), effects)
            }
        }

    @Test
    fun `an ambiguous failure retries the identical command and time even after the clock changes`() =
        runTest(mainDispatcherRule.dispatcher) {
            debts.paymentFailure = IOException("response lost")
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                val command = debts.paymentCommands.single()
                assertEquals(DebtorsContract.Failure.SAVE_PAYMENT_FAILED, vm.uiState.value.failure)
                assertNull(vm.uiState.value.paymentEditor)
                assertFalse(vm.uiState.value.isSavingPayment)
                assertTrue(effects.isEmpty())
                now = now.plusSeconds(30)
                debts.paymentFailure = null
                val recorded = recorded(occurredAt = command.occurredAt)
                debts.paymentResult = RecordDebtPaymentResult.AlreadyRecorded(recorded.debt, recorded.payment)
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertEquals(listOf(command, command), debts.paymentCommands)
                assertEquals(listOf(DebtorsContract.Effect.FullPaymentSaved), effects)
                assertEquals(
                    DebtStatus.PAID,
                    vm.uiState.value.detail
                        ?.debt
                        ?.status,
                )
            }
        }

    @Test
    fun `stale payment waits for another tap and then uses the newly observed balance and version`() =
        runTest(mainDispatcherRule.dispatcher) {
            debts.paymentResult = RecordDebtPaymentResult.Stale
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertEquals(DebtorsContract.Failure.STALE_DEBT, vm.uiState.value.failure)
                val changed = requireNotNull(debts.detail.value).debt.copy(balance = DebtDeletionFixtures.money("9"), version = 3L)
                debts.detail.value = DebtDeletionFixtures.detail(changed)
                now = now.plusSeconds(10)
                runCurrent()
                assertEquals(1, debts.paymentCommands.size)
                assertTrue(effects.isEmpty())
                debts.paymentResult = recorded(changed)
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertEquals(changed.balance, debts.paymentCommands.last().amount)
                assertEquals(changed.version, debts.paymentCommands.last().expectedVersion)
                assertEquals(now, debts.paymentCommands.last().occurredAt)
                assertEquals(listOf(DebtorsContract.Effect.FullPaymentSaved), effects)
            }
        }

    @Test
    fun `paid debts and debts from an inactive business never send a payment`() =
        runTest(mainDispatcherRule.dispatcher) {
            debts.detail.value = DebtDeletionFixtures.detail(DebtDeletionFixtures.summary(paid = true))
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertTrue(debts.paymentCommands.isEmpty())
                debts.detail.value = DebtDeletionFixtures.detail()
                configuration.enterDemoMode(BusinessId.from(UUID(1L, 9L)))
                runCurrent()
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertTrue(debts.paymentCommands.isEmpty())
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `an old business result cannot navigate even after switching away and back`() =
        runTest(mainDispatcherRule.dispatcher) {
            debts.paymentGate = CompletableDeferred()
            debts.paymentResult = recorded()
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                configuration.enterDemoMode(BusinessId.from(UUID(1L, 9L)))
                runCurrent()
                configuration.exitDemoMode()
                runCurrent()
                debts.paymentGate!!.complete(Unit)
                runCurrent()
                assertFalse(vm.uiState.value.isSavingPayment)
                assertEquals(
                    DebtStatus.OPEN,
                    vm.uiState.value.detail
                        ?.debt
                        ?.status,
                )
                assertTrue(effects.isEmpty())
                assertFalse(vm.claimFullPaymentNavigation())
            }
        }

    @Test
    fun `a buffered payment success cannot navigate after the active business changes`() =
        runTest(mainDispatcherRule.dispatcher) {
            debts.paymentResult = recorded()
            withViewModel { vm, _ ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                configuration.enterDemoMode(BusinessId.from(UUID(1L, 9L)))
                runCurrent()
                assertFalse(vm.claimFullPaymentNavigation())
            }
        }

    @Test
    fun `partial payment remains a separate editor and excludes a simultaneous full payment`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PartialPaymentRequested)
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertNotNull(vm.uiState.value.paymentEditor)
                assertTrue(debts.paymentCommands.isEmpty())
                vm.onAction(DebtorsContract.Action.PaymentAmountChanged("3"))
                runCurrent()
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertEquals(
                    "3",
                    vm.uiState.value.paymentEditor
                        ?.amountInput,
                )
                assertTrue(debts.paymentCommands.isEmpty())
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `delete preview blocks the direct payment even before its state update`() =
        runTest(mainDispatcherRule.dispatcher) {
            voids.previewGate = CompletableDeferred()
            withViewModel { vm, _ ->
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertTrue(debts.paymentCommands.isEmpty())
                assertTrue(vm.uiState.value.isLoadingDeletePreview)
                voids.previewGate!!.complete(Unit)
                runCurrent()
            }
        }

    @Test
    fun `online rejection remains visible without an editor and retry preserves the original gesture`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                val first = debts.paymentCommands.single()
                assertEquals(DebtorsContract.Failure.ONLINE_REQUIRED, vm.uiState.value.failure)
                assertNull(vm.uiState.value.paymentEditor)
                now = now.plusSeconds(20)
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertEquals(listOf(first, first), debts.paymentCommands)
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `cancellation releases the busy state and preserves the command for an explicit retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            debts.paymentFailure = CancellationException("cancelled")
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertFalse(vm.uiState.value.isSavingPayment)
                assertNull(vm.uiState.value.failure)
                assertTrue(effects.isEmpty())
                val first = debts.paymentCommands.single()
                debts.paymentFailure = null
                now = now.plusSeconds(10)
                vm.onAction(DebtorsContract.Action.PaymentRequested)
                runCurrent()
                assertEquals(listOf(first, first), debts.paymentCommands)
            }
        }

    private fun recorded(
        target: DebtSummary = requireNotNull(debts.detail.value).debt,
        occurredAt: java.time.Instant = now,
    ): RecordDebtPaymentResult.Recorded {
        val paid =
            target.copy(
                balance = DebtDeletionFixtures.money("0"),
                status = DebtStatus.PAID,
                version = target.version + 1,
                paidAt = occurredAt,
                updatedAt = occurredAt,
            )
        val payment =
            DebtPayment(
                DebtPaymentId.from(UUID(8L, target.version)),
                target.debtId,
                target.balance,
                DebtPaymentMethod.OTHER,
                null,
                null,
                target.version,
                paid.balance,
                occurredAt,
                occurredAt,
            )
        return RecordDebtPaymentResult.Recorded(paid, payment)
    }

    private suspend fun TestScope.withViewModel(block: suspend TestScope.(DebtorsViewModel, MutableList<DebtorsContract.Effect>) -> Unit) {
        configuration.completeOnboarding(DebtDeletionFixtures.businessId, AppConfiguration.DEFAULT_TAX_RATE, AppConfiguration.DEFAULT_COST_POLICY)
        val vm =
            DebtorsViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.DEBT_ID to DebtDeletionFixtures.debtId.value)),
                ObserveDebtsUseCase(configuration, debts),
                ObserveDebtDetailUseCase(configuration, debts),
                RecordDebtPaymentUseCase(configuration, debts),
                AppClock { now },
                TestDispatcherProvider(mainDispatcherRule.dispatcher),
                VoidSaleUseCase(configuration, voids),
                configuration,
            )
        val effects = mutableListOf<DebtorsContract.Effect>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        try {
            runCurrent()
            block(vm, effects)
        } finally {
            collector.cancel()
            vm.viewModelScope.cancel()
        }
    }
}
