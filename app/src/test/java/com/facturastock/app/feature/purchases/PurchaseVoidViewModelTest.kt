package com.facturastock.app.feature.purchases

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseVoidImpact
import com.facturastock.app.domain.model.PurchaseVoidPreview
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import com.facturastock.app.domain.repository.PurchaseVoidCommand
import com.facturastock.app.domain.repository.PurchaseVoidRepository
import com.facturastock.app.domain.repository.PurchaseVoidResult
import com.facturastock.app.domain.usecase.PreviewPurchaseVoidUseCase
import com.facturastock.app.domain.usecase.VoidPurchaseUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseVoidViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val configuration = FakeAppConfigurationRepository()
    private val authorization = object : PurchaseOverrideAuthorizationRepository {
        override suspend fun currentActor(businessId: BusinessId): PurchaseOverrideActor =
            PurchaseOverrideActor("owner-${businessId.value}", PurchaseOverrideRole.OWNER)
    }
    private val repository = FakePurchaseVoidRepository()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `double submit executes one command and completes once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val preview = preview(hashCharacter = 'a')
            val pendingResult = CompletableDeferred<PurchaseVoidResult>()
            repository.previewResult = PreviewPurchaseVoidResult.Ready(preview)
            repository.voidHandler = { pendingResult.await() }
            val viewModel = createViewModel(preview.purchaseId)
            runCurrent()

            viewModel.onAction(
                PurchaseVoidContract.Action.ReasonChanged("  Documento duplicado  "),
            )
            viewModel.onAction(PurchaseVoidContract.Action.ConfirmationChanged(true))
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(PurchaseVoidContract.Action.Submit)
                viewModel.onAction(PurchaseVoidContract.Action.Submit)
                runCurrent()

                assertEquals(1, repository.voidCalls.size)
                assertTrue(viewModel.uiState.value.isSubmitting)
                assertEquals("Documento duplicado", repository.voidCalls.single().reason)
                assertEquals(preview.expectedImpactHash, repository.voidCalls.single().expectedImpactHash)

                pendingResult.complete(
                    PurchaseVoidResult.Voided(preview.purchaseId, negativeImpacts = emptyList()),
                )
                runCurrent()

                assertEquals(
                    PurchaseVoidContract.Effect.Completed(preview.purchaseId),
                    awaitItem(),
                )
                assertFalse(viewModel.uiState.value.isSubmitting)
                assertEquals(1, repository.voidCalls.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `impact change replaces preview and requires confirmation again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = preview(hashCharacter = 'a')
            val changed = preview(
                hashCharacter = 'b',
                currentQuantity = BigDecimal("4"),
                resultingQuantity = BigDecimal("-6"),
            )
            repository.previewResult = PreviewPurchaseVoidResult.Ready(original)
            repository.voidHandler = { PurchaseVoidResult.ImpactChanged(changed) }
            val viewModel = createViewModel(original.purchaseId)
            runCurrent()

            viewModel.onAction(
                PurchaseVoidContract.Action.ReasonChanged("Documento duplicado"),
            )
            viewModel.onAction(PurchaseVoidContract.Action.ConfirmationChanged(true))
            runCurrent()
            viewModel.onAction(PurchaseVoidContract.Action.Submit)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(changed, state.preview)
            assertEquals(PurchaseVoidContract.Failure.IMPACT_CHANGED, state.failure)
            assertFalse(state.confirmed)
            assertFalse(state.canSubmit)
            assertEquals(1, repository.voidCalls.size)
        }

    @Test
    fun `retryable conflict clears stale preview before allowing another submit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val preview = preview(hashCharacter = 'a')
            repository.previewResult = PreviewPurchaseVoidResult.Ready(preview)
            repository.voidHandler = { PurchaseVoidResult.RetryableConflict }
            val viewModel = createViewModel(preview.purchaseId)
            runCurrent()

            viewModel.onAction(
                PurchaseVoidContract.Action.ReasonChanged("Documento duplicado"),
            )
            viewModel.onAction(PurchaseVoidContract.Action.ConfirmationChanged(true))
            runCurrent()
            viewModel.onAction(PurchaseVoidContract.Action.Submit)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(PurchaseVoidContract.Failure.RETRYABLE_CONFLICT, state.failure)
            assertEquals(null, state.preview)
            assertFalse(state.confirmed)
            assertFalse(state.canSubmit)
        }

    @Test
    fun `poco espacio conserva impacto motivo y confirmacion para reintentar`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val preview = preview(hashCharacter = 'a')
            repository.previewResult = PreviewPurchaseVoidResult.Ready(preview)
            repository.voidHandler = { throw StorageException(StorageError.InsufficientSpace) }
            val viewModel = createViewModel(preview.purchaseId)
            runCurrent()

            viewModel.onAction(
                PurchaseVoidContract.Action.ReasonChanged("Documento duplicado"),
            )
            viewModel.onAction(PurchaseVoidContract.Action.ConfirmationChanged(true))
            runCurrent()
            viewModel.onAction(PurchaseVoidContract.Action.Submit)
            runCurrent()

            val failed = viewModel.uiState.value
            assertEquals(PurchaseVoidContract.Failure.STORAGE_FULL, failed.failure)
            assertEquals(preview, failed.preview)
            assertEquals("Documento duplicado", failed.reason)
            assertTrue(failed.confirmed)
            assertTrue(failed.canSubmit)
            assertEquals(1, repository.voidCalls.size)

            repository.voidHandler = {
                PurchaseVoidResult.Voided(preview.purchaseId, negativeImpacts = emptyList())
            }
            viewModel.effects.test {
                viewModel.onAction(PurchaseVoidContract.Action.Submit)
                runCurrent()
                assertEquals(
                    PurchaseVoidContract.Effect.Completed(preview.purchaseId),
                    awaitItem(),
                )
                assertEquals(2, repository.voidCalls.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel(purchaseId: PurchaseId): PurchaseVoidViewModel =
        PurchaseVoidViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(RouteArgumentKeys.PURCHASE_ID to purchaseId.value),
            ),
            previewPurchaseVoid = PreviewPurchaseVoidUseCase(
                configuration,
                authorization,
                repository,
            ),
            voidPurchase = VoidPurchaseUseCase(
                configuration,
                authorization,
                repository,
                FakePurchaseBackupScheduler(),
            ),
            dispatcherProvider = dispatchers,
        )

    private suspend fun activate() {
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun preview(
        hashCharacter: Char,
        currentQuantity: BigDecimal = BigDecimal("8"),
        resultingQuantity: BigDecimal = BigDecimal("-2"),
    ): PurchaseVoidPreview = PurchaseVoidPreview(
        purchaseId = PURCHASE_ID,
        documentNumber = "F001-42",
        actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER),
        impacts = listOf(
            PurchaseVoidImpact(
                productId = PRODUCT_ID,
                productName = "Café molido",
                locationId = LOCATION_ID,
                locationName = "Almacén principal",
                unitCode = "NIU",
                currentQuantity = currentQuantity,
                reversalQuantity = BigDecimal("-10"),
                resultingQuantity = resultingQuantity,
                currency = CurrencyCode.of("PEN"),
            ),
        ),
        expectedImpactHash = hashCharacter.toString().repeat(64),
    )

    private class FakePurchaseVoidRepository : PurchaseVoidRepository {
        var previewResult: PreviewPurchaseVoidResult = PreviewPurchaseVoidResult.NotFound
        var voidHandler: suspend (PurchaseVoidCommand) -> PurchaseVoidResult = {
            PurchaseVoidResult.RetryableConflict
        }
        val voidCalls = mutableListOf<PurchaseVoidCommand>()

        override suspend fun preview(
            businessId: BusinessId,
            purchaseId: PurchaseId,
            actor: PurchaseOverrideActor,
        ): PreviewPurchaseVoidResult = previewResult

        override suspend fun void(command: PurchaseVoidCommand): PurchaseVoidResult {
            voidCalls += command
            return voidHandler(command)
        }
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(2))
        val PRODUCT_ID: ProductId = ProductId.from(uuid(3))
        val LOCATION_ID: LocationId = LocationId.from(uuid(4))

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
