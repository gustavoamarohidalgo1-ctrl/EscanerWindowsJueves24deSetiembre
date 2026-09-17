package com.facturastock.app.feature.debtors

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidResult
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
class DebtDeleteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val configuration = FakeAppConfigurationRepository()
    private val debts = FakeDebtDeletionRepository()
    private val voids = FakeDebtVoidRepository()

    @Test
    fun `open and paid debts load the exact preview without committing until confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            for (paid in listOf(false, true)) {
                val target = DebtDeletionFixtures.summary(paid)
                debts.detail.value = DebtDeletionFixtures.detail(target)
                voids.previewResult = SaleVoidPreviewResult.Ready(DebtDeletionFixtures.preview(target))
                withViewModel { vm, effects ->
                    vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                    runCurrent()
                    val commits = voids.confirmations.size
                    repeat(2) { vm.onAction(DebtorsContract.Action.DeleteRequested) }
                    runCurrent()
                    assertEquals(target, vm.uiState.value.deleteTarget)
                    assertEquals(
                        target.balance,
                        vm.uiState.value.deletePreview
                            ?.debtBalanceToCancel,
                    )
                    assertEquals(commits, voids.confirmations.size)
                    assertTrue(effects.isEmpty())
                    vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                    runCurrent()
                    assertEquals(commits + 1, voids.confirmations.size)
                    assertEquals(listOf(DebtorsContract.Effect.DebtDeleted), effects)
                    assertNull(vm.uiState.value.deleteTarget)
                }
            }
            assertEquals(2, voids.previews.size)
        }

    @Test
    fun `cancel and back discard a pending preview and its late result without mutating`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                for (cancel in listOf(DebtorsContract.Action.DeleteDismissed, DebtorsContract.Action.BackSelected)) {
                    voids.previewGate = CompletableDeferred()
                    vm.onAction(DebtorsContract.Action.DeleteRequested)
                    runCurrent()
                    assertTrue(vm.uiState.value.isLoadingDeletePreview)
                    vm.onAction(cancel)
                    runCurrent()
                    voids.previewGate!!.complete(Unit)
                    runCurrent()
                    assertNull(vm.uiState.value.deleteTarget)
                    assertNull(vm.uiState.value.deletePreview)
                    assertFalse(vm.uiState.value.isLoadingDeletePreview)
                    assertEquals(debts.detail.value, vm.uiState.value.detail)
                    assertTrue(voids.confirmations.isEmpty())
                    assertTrue(effects.isEmpty())
                }
            }
        }

    @Test
    fun `payment editor and payment commit exclude deletion and keep entered values`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, _ ->
                vm.onAction(DebtorsContract.Action.PartialPaymentRequested)
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                runCurrent()
                assertTrue(voids.previews.isEmpty())
                vm.onAction(DebtorsContract.Action.PaymentAmountChanged("5"))
                vm.onAction(DebtorsContract.Action.PaymentNoteChanged("Abono guardado en edición"))
                runCurrent()
                val editor = vm.uiState.value.paymentEditor
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                vm.onAction(DebtorsContract.Action.DeleteDismissed)
                runCurrent()
                assertEquals(editor, vm.uiState.value.paymentEditor)
                debts.paymentGate = CompletableDeferred()
                vm.onAction(DebtorsContract.Action.PaymentConfirmed)
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                runCurrent()
                assertEquals(1, debts.paymentCommands.size)
                assertTrue(voids.previews.isEmpty())
                debts.paymentGate!!.complete(Unit)
                runCurrent()
                assertEquals(
                    "5",
                    vm.uiState.value.paymentEditor
                        ?.amountInput,
                )
            }
        }

    @Test
    fun `one deletion excludes payments navigation and duplicate commits while null detail never flashes not found`() =
        runTest(mainDispatcherRule.dispatcher) {
            voids.confirmGate = CompletableDeferred()
            withViewModel { vm, effects ->
                val states = mutableListOf<DebtorsContract.State>()
                val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { states += it } }
                requestPreview(vm)
                vm.onAction(DebtorsContract.Action.PartialPaymentRequested)
                vm.onAction(DebtorsContract.Action.PaymentConfirmed)
                repeat(2) { vm.onAction(DebtorsContract.Action.DeleteConfirmed) }
                vm.onAction(DebtorsContract.Action.BackSelected)
                vm.onAction(DebtorsContract.Action.DeleteDismissed)
                runCurrent()
                assertEquals(1, voids.confirmations.size)
                assertTrue(debts.paymentCommands.isEmpty())
                assertTrue(vm.uiState.value.isDeletingDebt)
                assertTrue(effects.isEmpty())
                debts.detail.value = null
                runCurrent()
                assertNotNull(vm.uiState.value.detail)
                assertNotNull(vm.uiState.value.deleteTarget)
                assertTrue(states.none { it.failure == DebtorsContract.Failure.DEBT_NOT_FOUND })
                voids.confirmGate!!.complete(Unit)
                runCurrent()
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                vm.onAction(DebtorsContract.Action.BackSelected)
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                runCurrent()
                assertEquals(listOf(DebtorsContract.Effect.DebtDeleted), effects)
                assertEquals(1, voids.confirmations.size)
                assertTrue(states.none { it.failure == DebtorsContract.Failure.DEBT_NOT_FOUND })
                collector.cancel()
            }
        }

    @Test
    fun `payment and version changes invalidate a delayed preview and require a fresh explicit review`() =
        runTest(mainDispatcherRule.dispatcher) {
            voids.previewGate = CompletableDeferred()
            withViewModel { vm, effects ->
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                runCurrent()
                val changed = requireNotNull(debts.detail.value).debt.copy(balance = DebtDeletionFixtures.money("10"), version = 3L)
                debts.detail.value = DebtDeletionFixtures.detail(changed)
                runCurrent()
                voids.previewGate!!.complete(Unit)
                runCurrent()
                assertEquals(DebtorsContract.DeleteFailure.STALE, vm.uiState.value.deleteFailure)
                assertNull(vm.uiState.value.deletePreview)
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertTrue(voids.confirmations.isEmpty())
                voids.previewResult = SaleVoidPreviewResult.Ready(DebtDeletionFixtures.preview(changed, "b".repeat(64)))
                vm.onAction(DebtorsContract.Action.DeletePreviewRetry)
                runCurrent()
                assertEquals(changed, vm.uiState.value.deleteTarget)
                assertEquals(
                    changed.balance,
                    vm.uiState.value.deletePreview
                        ?.debtBalanceToCancel,
                )
                assertEquals(2, voids.previews.size)
                assertTrue(voids.confirmations.isEmpty())
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `changed version or sale identity after review prevents confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, _ ->
                requestPreview(vm)
                val original = requireNotNull(debts.detail.value).debt
                debts.detail.value = DebtDeletionFixtures.detail(original.copy(version = original.version + 1))
                runCurrent()
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertNull(vm.uiState.value.deletePreview)
                assertTrue(voids.confirmations.isEmpty())
                vm.onAction(DebtorsContract.Action.DeletePreviewRetry)
                runCurrent()
                debts.detail.value = DebtDeletionFixtures.detail(original.copy(saleId = SaleId.from(UUID(3L, 2L))))
                runCurrent()
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertTrue(voids.confirmations.isEmpty())
            }
        }

    @Test
    fun `business change ignores pending preview and commit results`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                voids.previewGate = CompletableDeferred()
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                runCurrent()
                configuration.enterDemoMode(BusinessId.from(UUID(1L, 9L)))
                runCurrent()
                voids.previewGate!!.complete(Unit)
                runCurrent()
                assertNull(vm.uiState.value.deletePreview)
                assertNull(vm.uiState.value.deleteTarget)
                assertTrue(effects.isEmpty())
                configuration.exitDemoMode()
                runCurrent()
                requestPreview(vm)
                voids.confirmGate = CompletableDeferred()
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                configuration.enterDemoMode(BusinessId.from(UUID(1L, 9L)))
                runCurrent()
                assertTrue(vm.uiState.value.isDeletingDebt)
                voids.confirmGate!!.complete(Unit)
                runCurrent()
                assertFalse(vm.uiState.value.isDeletingDebt)
                assertNull(vm.uiState.value.deleteTarget)
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `stale confirmation requires review followed by a new confirmation and never retries itself`() =
        runTest(mainDispatcherRule.dispatcher) {
            voids.confirmResult = SaleVoidResult.Stale
            withViewModel { vm, effects ->
                requestPreview(vm)
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(DebtorsContract.DeleteFailure.STALE, vm.uiState.value.deleteFailure)
                assertNull(vm.uiState.value.deletePreview)
                assertEquals(1, voids.previews.size)
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(1, voids.confirmations.size)
                val fresh = DebtDeletionFixtures.preview(hash = "b".repeat(64))
                voids.previewResult = SaleVoidPreviewResult.Ready(fresh)
                repeat(2) { vm.onAction(DebtorsContract.Action.DeletePreviewRetry) }
                runCurrent()
                assertEquals(fresh, vm.uiState.value.deletePreview)
                assertEquals(2, voids.previews.size)
                assertEquals(1, voids.confirmations.size)
                assertTrue(effects.isEmpty())
                voids.confirmResult = SaleVoidResult.Voided
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(fresh, voids.confirmations.last())
                assertEquals(listOf(DebtorsContract.Effect.DebtDeleted), effects)
            }
        }

    @Test
    fun `cash sale or mismatched identity total and balance previews can never be confirmed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val valid = DebtDeletionFixtures.preview()
            val invalid =
                listOf(
                    valid.copy(debtBalanceToCancel = null, refundAmount = valid.total),
                    valid.copy(businessId = BusinessId.from(UUID(1L, 9L))),
                    valid.copy(saleId = SaleId.from(UUID(3L, 9L))),
                    valid.copy(total = DebtDeletionFixtures.money("30"), refundAmount = DebtDeletionFixtures.money("15")),
                    valid.copy(debtBalanceToCancel = DebtDeletionFixtures.money("10"), refundAmount = DebtDeletionFixtures.money("10")),
                )
            withViewModel { vm, effects ->
                for (preview in invalid) {
                    voids.previewResult = SaleVoidPreviewResult.Ready(preview)
                    requestPreview(vm)
                    assertEquals(DebtorsContract.DeleteFailure.INVALID_HISTORY, vm.uiState.value.deleteFailure)
                    assertNull(vm.uiState.value.deletePreview)
                    vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                    runCurrent()
                    assertTrue(voids.confirmations.isEmpty())
                    vm.onAction(DebtorsContract.Action.DeleteDismissed)
                    runCurrent()
                }
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `preview rejections stay distinct and allow only review or cancellation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val cases =
                listOf(
                    SaleVoidPreviewResult.NoActiveBusiness to DebtorsContract.DeleteFailure.NO_ACTIVE_BUSINESS,
                    SaleVoidPreviewResult.NotFound to DebtorsContract.DeleteFailure.NOT_FOUND,
                    SaleVoidPreviewResult.Unauthorized to DebtorsContract.DeleteFailure.UNAUTHORIZED,
                    SaleVoidPreviewResult.SharedBusinessUnsupported to DebtorsContract.DeleteFailure.SHARED_BUSINESS_UNSUPPORTED,
                    SaleVoidPreviewResult.InvalidHistory to DebtorsContract.DeleteFailure.INVALID_HISTORY,
                )
            withViewModel { vm, _ ->
                for ((result, failure) in cases) {
                    voids.previewResult = result
                    requestPreview(vm)
                    assertEquals(failure, vm.uiState.value.deleteFailure)
                    assertFalse(vm.uiState.value.isLoadingDeletePreview)
                    vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                    runCurrent()
                    assertTrue(voids.confirmations.isEmpty())
                    vm.onAction(DebtorsContract.Action.DeleteDismissed)
                    runCurrent()
                }
            }
        }

    @Test
    fun `commit rejections clear the preview and preserve the appropriate failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            val cases =
                listOf(
                    SaleVoidResult.NoActiveBusiness to DebtorsContract.DeleteFailure.NO_ACTIVE_BUSINESS,
                    SaleVoidResult.NotFound to DebtorsContract.DeleteFailure.NOT_FOUND,
                    SaleVoidResult.Unauthorized to DebtorsContract.DeleteFailure.UNAUTHORIZED,
                    SaleVoidResult.SharedBusinessUnsupported to DebtorsContract.DeleteFailure.SHARED_BUSINESS_UNSUPPORTED,
                    SaleVoidResult.InvalidHistory to DebtorsContract.DeleteFailure.INVALID_HISTORY,
                )
            withViewModel { vm, effects ->
                for ((result, failure) in cases) {
                    voids.confirmResult = result
                    requestPreview(vm)
                    vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                    runCurrent()
                    assertEquals(failure, vm.uiState.value.deleteFailure)
                    assertFalse(vm.uiState.value.isDeletingDebt)
                    assertNull(vm.uiState.value.deletePreview)
                    vm.onAction(DebtorsContract.Action.DeleteDismissed)
                    runCurrent()
                }
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `ambiguous IO failure requires review and already voided emits one completion without another commit`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                voids.previewFailure = IOException("read failed")
                requestPreview(vm)
                assertEquals(DebtorsContract.DeleteFailure.LOAD_FAILED, vm.uiState.value.deleteFailure)
                voids.previewFailure = null
                vm.onAction(DebtorsContract.Action.DeletePreviewRetry)
                runCurrent()
                voids.confirmFailure = IOException("response lost")
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(DebtorsContract.DeleteFailure.OPERATION_FAILED, vm.uiState.value.deleteFailure)
                assertTrue(effects.isEmpty())
                debts.detail.value = null
                runCurrent()
                voids.previewResult = SaleVoidPreviewResult.AlreadyVoided
                vm.onAction(DebtorsContract.Action.DeletePreviewRetry)
                runCurrent()
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                vm.onAction(DebtorsContract.Action.DeletePreviewRetry)
                runCurrent()
                assertEquals(listOf(DebtorsContract.Effect.DebtDeleted), effects)
                assertEquals(1, voids.confirmations.size)
                assertNull(vm.uiState.value.failure)
            }
        }

    @Test
    fun `already voided confirmation is a single successful completion`() =
        runTest(mainDispatcherRule.dispatcher) {
            voids.confirmResult = SaleVoidResult.AlreadyVoided
            withViewModel { vm, effects ->
                requestPreview(vm)
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(listOf(DebtorsContract.Effect.DebtDeleted), effects)
                assertFalse(vm.uiState.value.isDeletingDebt)
            }
        }

    @Test
    fun `cancellation releases progress without converting it into a failure or committing`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                voids.previewFailure = CancellationException("cancel preview")
                requestPreview(vm)
                assertNull(vm.uiState.value.deleteFailure)
                assertFalse(vm.uiState.value.isLoadingDeletePreview)
                voids.previewFailure = null
                vm.onAction(DebtorsContract.Action.DeletePreviewRetry)
                runCurrent()
                voids.confirmFailure = CancellationException("cancel commit")
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertFalse(vm.uiState.value.isDeletingDebt)
                assertNull(vm.uiState.value.deletePreview)
                assertNull(vm.uiState.value.deleteFailure)
                assertTrue(effects.isEmpty())
                vm.onAction(DebtorsContract.Action.DeleteDismissed)
                runCurrent()
                assertNull(vm.uiState.value.deleteTarget)
            }
        }

    @Test
    fun `configuration observation failure closes deletion safely and Retry restores actions`() =
        runTest(mainDispatcherRule.dispatcher) {
            var unavailable = false
            val configurationStream = kotlinx.coroutines.flow.MutableStateFlow(false)
            val flaky =
                object : AppConfigurationRepository by configuration {
                    override fun observe() =
                        flow {
                            emitAll(
                                configurationStream.flatMapLatest { failed ->
                                    if (failed || unavailable) {
                                        flow { throw IOException("configuration unavailable") }
                                    } else {
                                        configuration.observe()
                                    }
                                },
                            )
                        }
                }
            withViewModel(configurationRepository = flaky) { vm, effects ->
                requestPreview(vm)
                vm.onAction(DebtorsContract.Action.DeleteDismissed)
                runCurrent()
                // Both active subscribers receive a storage error and complete; only Retry reopens them.
                unavailable = true
                configurationStream.value = true
                runCurrent()
                assertEquals(DebtorsContract.Failure.LOAD_FAILED, vm.uiState.value.failure)
                vm.onAction(DebtorsContract.Action.DeleteRequested)
                runCurrent()
                assertNull(vm.uiState.value.deleteTarget)
                unavailable = false
                configurationStream.value = false
                vm.onAction(DebtorsContract.Action.Retry)
                runCurrent()
                assertNull(vm.uiState.value.failure)
                requestPreview(vm)
                assertNotNull(vm.uiState.value.deletePreview)
                assertTrue(voids.confirmations.isEmpty())
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `missing detail after a ready preview offers review and recognizes an already voided sale`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                requestPreview(vm)
                assertNotNull(vm.uiState.value.deletePreview)
                debts.detail.value = null
                runCurrent()
                assertNull(vm.uiState.value.deletePreview)
                assertNotNull(vm.uiState.value.deleteTarget)
                assertEquals(DebtorsContract.DeleteFailure.NOT_FOUND, vm.uiState.value.deleteFailure)
                vm.onAction(DebtorsContract.Action.DeleteConfirmed)
                runCurrent()
                assertTrue(voids.confirmations.isEmpty())
                voids.previewResult = SaleVoidPreviewResult.AlreadyVoided
                vm.onAction(DebtorsContract.Action.DeletePreviewRetry)
                runCurrent()
                assertEquals(listOf(DebtorsContract.Effect.DebtDeleted), effects)
                assertNull(vm.uiState.value.failure)
            }
        }

    private fun TestScope.requestPreview(vm: DebtorsViewModel) {
        vm.onAction(DebtorsContract.Action.DeleteRequested)
        runCurrent()
    }

    private suspend fun TestScope.withViewModel(
        configurationRepository: AppConfigurationRepository = configuration,
        block: suspend TestScope.(DebtorsViewModel, MutableList<DebtorsContract.Effect>) -> Unit,
    ) {
        configuration.completeOnboarding(DebtDeletionFixtures.businessId, AppConfiguration.DEFAULT_TAX_RATE, AppConfiguration.DEFAULT_COST_POLICY)
        val vm =
            DebtorsViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.DEBT_ID to DebtDeletionFixtures.debtId.value)),
                ObserveDebtsUseCase(configurationRepository, debts),
                ObserveDebtDetailUseCase(configurationRepository, debts),
                RecordDebtPaymentUseCase(configurationRepository, debts),
                AppClock { DebtDeletionFixtures.now },
                TestDispatcherProvider(mainDispatcherRule.dispatcher),
                VoidSaleUseCase(configurationRepository, voids),
                configurationRepository,
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
