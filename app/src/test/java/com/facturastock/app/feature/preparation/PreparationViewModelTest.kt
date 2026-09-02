package com.facturastock.app.feature.preparation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RecordedPurchase
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.PublishPreparedPurchaseResult
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import com.facturastock.app.domain.usecase.AuthorizePurchaseDuplicateOverrideUseCase
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterConfirmUseCase
import com.facturastock.app.domain.usecase.CheckPurchaseDuplicateUseCase
import com.facturastock.app.domain.usecase.ConfirmPurchaseUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.ControlledPurchasePostingRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakePreparedPurchaseRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakePurchaseOverrideAuthorizationRepository
import com.facturastock.app.testing.FakePurchaseRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `exacto bloquea confirmacion y permite abrir compra existente`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()
                assertEquals(
                    PurchaseDuplicateKind.EXACT,
                    viewModel.uiState.value.duplicateAssessment?.kind,
                )

                viewModel.onAction(PreparationContract.Action.Confirm)
                runCurrent()
                assertTrue(viewModel.uiState.value.showOverrideDialog)
                assertTrue(fixture.posting.calls.isEmpty())

                viewModel.onAction(PreparationContract.Action.OpenExistingSelected)
                assertEquals(
                    PreparationContract.Effect.OpenExistingPurchase(EXISTING_PURCHASE_ID),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `override autorizado viaja al commit y se conserva para retry sin auditoria separada`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()
                viewModel.onAction(PreparationContract.Action.RequestOverride)
                viewModel.onAction(
                    PreparationContract.Action.OverrideReasonChanged(
                        "Factura emitida nuevamente por corrección autorizada",
                    ),
                )
                runCurrent()
                viewModel.onAction(PreparationContract.Action.ConfirmOverride)
                runCurrent()

                val postingCall = fixture.posting.takeCall()
                val duplicateOverride = postingCall.input.command.duplicateOverride
                assertEquals(
                    "Factura emitida nuevamente por corrección autorizada",
                    duplicateOverride?.reason,
                )
                assertEquals(DRAFT_ID, duplicateOverride?.draftId)
                assertEquals(BUSINESS_ID, duplicateOverride?.businessId)
                assertEquals(EXISTING_PURCHASE_ID, duplicateOverride?.existingPurchaseId)
                assertEquals("test-owner", duplicateOverride?.actor?.actorId)
                assertEquals(PurchaseOverrideRole.OWNER, duplicateOverride?.actor?.role)
                assertTrue(fixture.purchases.overrides.isEmpty())
                assertFalse(viewModel.uiState.value.showOverrideDialog)

                postingCall.succeed(ConfirmPurchaseResult.RetryableConflict)
                advanceUntilIdle()
                assertEquals(
                    PreparationContract.Failure.RETRYABLE_CONFLICT,
                    viewModel.uiState.value.failure,
                )
                expectNoEvents()

                viewModel.onAction(PreparationContract.Action.Retry)
                runCurrent()
                val retryCall = fixture.posting.takeCall()
                assertEquals(duplicateOverride, retryCall.input.command.duplicateOverride)
                assertTrue(fixture.purchases.overrides.isEmpty())

                retryCall.succeed(ConfirmPurchaseResult.ExactDuplicate(EXISTING_PURCHASE_ID))
                advanceUntilIdle()
                assertEquals(
                    PreparationContract.Effect.OpenExistingPurchase(EXISTING_PURCHASE_ID),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `coincidencia probable se muestra y permite continuar sin excepcion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                existing = existingPurchase().copy(
                    documentSeries = "F001",
                    documentNumber = "999",
                ),
            )
            val viewModel = fixture.viewModel()

            viewModel.onAction(PreparationContract.Action.Start)
            advanceUntilIdle()
            assertEquals(
                PurchaseDuplicateKind.PROBABLE,
                viewModel.uiState.value.duplicateAssessment?.kind,
            )

            viewModel.onAction(PreparationContract.Action.Confirm)
            runCurrent()

            fixture.posting.takeCall().succeed(ConfirmPurchaseResult.Posted(NEW_PURCHASE_ID))
            advanceUntilIdle()
            assertTrue(fixture.purchases.overrides.isEmpty())
        }

    @Test
    fun `doble confirm pendiente ejecuta un posting y navega una sola vez`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(existing = null)
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()

                viewModel.onAction(PreparationContract.Action.Confirm)
                viewModel.onAction(PreparationContract.Action.Confirm)
                runCurrent()

                assertEquals(1, fixture.posting.calls.size)
                assertTrue(viewModel.uiState.value.isConfirming)

                fixture.posting.takeCall().succeed(
                    ConfirmPurchaseResult.Posted(NEW_PURCHASE_ID),
                )
                advanceUntilIdle()
                assertEquals(
                    PreparationContract.Effect.OpenPurchase(NEW_PURCHASE_ID),
                    awaitItem(),
                )
                assertEquals(NEW_PURCHASE_ID, viewModel.uiState.value.confirmedPurchaseId)

                viewModel.onAction(PreparationContract.Action.Confirm)
                runCurrent()
                assertEquals(1, fixture.posting.calls.size)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `reinicio despues del commit abre compra una vez sin preflight ni nuevo posting`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(existing = null)
            val readyDraft = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
            assertTrue(
                fixture.drafts.updateDraft(
                    readyDraft.copy(
                        status = DraftStatus.COMMITTED,
                        confirmedPurchaseId = NEW_PURCHASE_ID,
                    ),
                ),
            )
            val restoredViewModel = fixture.viewModel()

            restoredViewModel.effects.test {
                restoredViewModel.onAction(PreparationContract.Action.Start)
                restoredViewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()

                assertEquals(
                    PreparationContract.Effect.OpenPurchase(NEW_PURCHASE_ID),
                    awaitItem(),
                )
                assertEquals(
                    NEW_PURCHASE_ID,
                    restoredViewModel.uiState.value.confirmedPurchaseId,
                )
                assertEquals(0, fixture.duplicatePrepared.findCalls)
                assertTrue(fixture.posting.calls.isEmpty())

                // Una nueva invalidación Room y acciones tardías no repiten la navegación.
                val committed = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
                fixture.drafts.updateDraft(
                    committed.copy(documentNumberRaw = "F001-000123"),
                )
                restoredViewModel.onAction(PreparationContract.Action.Start)
                restoredViewModel.onAction(PreparationContract.Action.Confirm)
                advanceUntilIdle()

                assertEquals(0, fixture.duplicatePrepared.findCalls)
                assertTrue(fixture.posting.calls.isEmpty())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `borrador ausente cierra ruta una vez sin preflight ni posting`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(existing = null)
            assertTrue(fixture.drafts.deleteDraft(DRAFT_ID))
            val restoredViewModel = fixture.viewModel()

            restoredViewModel.effects.test {
                restoredViewModel.onAction(PreparationContract.Action.Start)
                restoredViewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()

                assertEquals(PreparationContract.Effect.CloseInvalidRoute, awaitItem())
                assertEquals(
                    PreparationContract.Failure.INVALID_DRAFT_ID,
                    restoredViewModel.uiState.value.failure,
                )
                assertEquals(0, fixture.duplicatePrepared.findCalls)
                assertTrue(fixture.posting.calls.isEmpty())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `borrador committed sin compra durable se trata como ruta incompatible`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(existing = null)
            val readyDraft = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
            assertTrue(
                fixture.drafts.updateDraft(
                    readyDraft.copy(
                        status = DraftStatus.COMMITTED,
                        confirmedPurchaseId = null,
                    ),
                ),
            )
            val restoredViewModel = fixture.viewModel()

            restoredViewModel.effects.test {
                restoredViewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()

                assertEquals(PreparationContract.Effect.CloseInvalidRoute, awaitItem())
                assertEquals(
                    PreparationContract.Failure.INVALID_DRAFT_ID,
                    restoredViewModel.uiState.value.failure,
                )
                assertEquals(0, fixture.duplicatePrepared.findCalls)
                assertTrue(fixture.posting.calls.isEmpty())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `preflight de otra instantanea bloquea confirmacion sin llamar al posting`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(existing = null)
            val viewModel = fixture.viewModel(expectedPreparedHash = "b".repeat(64))

            viewModel.effects.test {
                viewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()

                assertEquals(
                    PreparationContract.Failure.PREPARED_PURCHASE_CHANGED,
                    viewModel.uiState.value.failure,
                )
                assertEquals(null, viewModel.uiState.value.duplicateAssessment)

                viewModel.onAction(PreparationContract.Action.Confirm)
                advanceUntilIdle()

                assertTrue(fixture.posting.calls.isEmpty())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `acciones tardias tras publicar no reabren override ni repiten posting`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()
                viewModel.onAction(PreparationContract.Action.RequestOverride)
                viewModel.onAction(
                    PreparationContract.Action.OverrideReasonChanged(
                        "Factura emitida nuevamente por corrección autorizada",
                    ),
                )
                runCurrent()
                viewModel.onAction(PreparationContract.Action.ConfirmOverride)
                runCurrent()

                fixture.posting.takeCall().succeed(
                    ConfirmPurchaseResult.Posted(NEW_PURCHASE_ID),
                )
                advanceUntilIdle()
                assertEquals(
                    PreparationContract.Effect.OpenPurchase(NEW_PURCHASE_ID),
                    awaitItem(),
                )

                viewModel.onAction(PreparationContract.Action.Confirm)
                viewModel.onAction(PreparationContract.Action.RequestOverride)
                viewModel.onAction(PreparationContract.Action.ConfirmOverride)
                viewModel.onAction(PreparationContract.Action.Retry)
                advanceUntilIdle()

                assertEquals(1, fixture.posting.calls.size)
                assertFalse(viewModel.uiState.value.showOverrideDialog)
                assertEquals(NEW_PURCHASE_ID, viewModel.uiState.value.confirmedPurchaseId)
                assertEquals(null, viewModel.uiState.value.failure)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `conflicto reintentable repite comando y AlreadyPosted navega al mismo id`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(existing = null)
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()
                viewModel.onAction(PreparationContract.Action.Confirm)
                runCurrent()

                val firstCall = fixture.posting.takeCall()
                firstCall.succeed(ConfirmPurchaseResult.RetryableConflict)
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isConfirming)
                assertEquals(
                    PreparationContract.Failure.RETRYABLE_CONFLICT,
                    viewModel.uiState.value.failure,
                )
                expectNoEvents()

                viewModel.onAction(PreparationContract.Action.Retry)
                runCurrent()
                val retryCall = fixture.posting.takeCall()
                assertEquals(firstCall.input, retryCall.input)
                assertEquals(2, fixture.posting.calls.size)

                retryCall.succeed(ConfirmPurchaseResult.AlreadyPosted(NEW_PURCHASE_ID))
                advanceUntilIdle()
                assertEquals(
                    PreparationContract.Effect.OpenPurchase(NEW_PURCHASE_ID),
                    awaitItem(),
                )
                assertEquals(NEW_PURCHASE_ID, viewModel.uiState.value.confirmedPurchaseId)
                assertEquals(null, viewModel.uiState.value.failure)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `poco espacio al publicar conserva draft y preparacion y reintenta el mismo comando`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(existing = null)
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(PreparationContract.Action.Start)
                advanceUntilIdle()
                assertEquals(DraftStatus.READY_TO_POST, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(PREPARED_HASH, fixture.prepared.find(DRAFT_ID)?.logicalHash)

                viewModel.onAction(PreparationContract.Action.Confirm)
                runCurrent()
                val failedCall = fixture.posting.takeCall()
                failedCall.fail(StorageException(StorageError.InsufficientSpace))
                advanceUntilIdle()

                assertEquals(
                    PreparationContract.Failure.STORAGE_FULL,
                    viewModel.uiState.value.failure,
                )
                assertFalse(viewModel.uiState.value.isConfirming)
                assertEquals(PREPARED_HASH, viewModel.uiState.value.expectedPreparedLogicalHash)
                assertEquals(DraftStatus.READY_TO_POST, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(PREPARED_HASH, fixture.prepared.find(DRAFT_ID)?.logicalHash)
                expectNoEvents()

                viewModel.onAction(PreparationContract.Action.Retry)
                runCurrent()
                val retryCall = fixture.posting.takeCall()
                assertEquals(failedCall.input.command, retryCall.input.command)
                assertEquals(failedCall.input.context, retryCall.input.context)
                assertEquals(2, fixture.posting.calls.size)

                retryCall.succeed(ConfirmPurchaseResult.Posted(NEW_PURCHASE_ID))
                advanceUntilIdle()
                assertEquals(
                    PreparationContract.Effect.OpenPurchase(NEW_PURCHASE_ID),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun fixture(
        existing: RecordedPurchase? = existingPurchase(),
    ): Fixture {
        val clock = AppClock { NOW }
        val drafts = FakeInvoiceDraftRepository(clock)
        val created = drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val prepared = FakePreparedPurchaseRepository(drafts, clock)
        assertEquals(
            PublishPreparedPurchaseResult.PREPARED,
            prepared.publish(
                preparedPurchase(),
                created.updatedAt,
                expectedHeaderRevision = 0,
                expectedLinesRevision = 0,
            ),
        )
        val purchases = FakePurchaseRepository(listOfNotNull(existing))
        val configuration = FakeAppConfigurationRepository().also { repository ->
            repository.completeOnboarding(
                businessId = BUSINESS_ID,
                taxRate = AppConfiguration.DEFAULT_TAX_RATE,
                costPolicy = AppConfiguration.DEFAULT_COST_POLICY,
            )
        }
        val posting = ControlledPurchasePostingRepository()
        val duplicatePrepared = CountingPreparedPurchaseRepository(prepared)
        val check = CheckPurchaseDuplicateUseCase(duplicatePrepared, drafts, purchases)
        return Fixture(
            posting = posting,
            drafts = drafts,
            prepared = prepared,
            configuration = configuration,
            purchases = purchases,
            duplicatePrepared = duplicatePrepared,
            check = check,
            authorization = FakePurchaseOverrideAuthorizationRepository(),
            dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        )
    }

    private data class Fixture(
        val posting: ControlledPurchasePostingRepository,
        val drafts: FakeInvoiceDraftRepository,
        val prepared: FakePreparedPurchaseRepository,
        val configuration: FakeAppConfigurationRepository,
        val purchases: FakePurchaseRepository,
        val duplicatePrepared: CountingPreparedPurchaseRepository,
        val check: CheckPurchaseDuplicateUseCase,
        val authorization: FakePurchaseOverrideAuthorizationRepository,
        val dispatchers: DispatcherProvider,
    ) {
        fun viewModel(expectedPreparedHash: String = PREPARED_HASH) = PreparationViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value,
                    RouteArgumentKeys.EXPECTED_PREPARED_HASH to expectedPreparedHash,
                ),
            ),
            confirmPurchaseUseCase = ConfirmPurchaseUseCase(
                configuration,
                posting,
                FakePurchaseBackupScheduler(),
                ApplyImageRetentionAfterConfirmUseCase(configuration, FakeDraftFileStore()),
            ),
            checkPurchaseDuplicateUseCase = check,
            authorizeDuplicateOverrideUseCase = AuthorizePurchaseDuplicateOverrideUseCase(
                checkDuplicate = check,
                authorizationRepository = authorization,
            ),
            observeInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(drafts),
            dispatcherProvider = dispatchers,
        )
    }

    private class CountingPreparedPurchaseRepository(
        private val delegate: PreparedPurchaseRepository,
    ) : PreparedPurchaseRepository by delegate {
        var findCalls: Int = 0
            private set

        override suspend fun find(draftId: DraftId): PreparedPurchase? {
            findCalls += 1
            return delegate.find(draftId)
        }
    }

    private fun preparedPurchase() = PreparedPurchase(
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        supplierId = SUPPLIER_ID,
        supplierRuc = "20123456789",
        supplierLegalName = "Proveedor SAC",
        documentType = PurchaseDocumentType.INVOICE,
        documentNumber = "F001-000123",
        issueDate = DATE,
        currency = PEN,
        lines = listOf(
            PreparedPurchaseLine(
                lineId = LineId.from(uuid(8)),
                position = 0,
                productId = ProductId.from(uuid(9)),
                unitId = UnitId.from(uuid(10)),
                description = "Producto",
                quantity = Quantity.of("1"),
                lineTotal = Money.ofMinor(1_000, PEN),
            ),
        ),
        subtotal = Money.ofMinor(1_000, PEN),
        tax = Money.zero(PEN),
        otherCharges = null,
        total = Money.ofMinor(1_000, PEN),
        acceptedWarnings = emptyList(),
        logicalHash = "a".repeat(64),
        preparedAt = NOW,
    )

    private fun existingPurchase() = RecordedPurchase(
        purchaseId = EXISTING_PURCHASE_ID,
        businessId = BUSINESS_ID,
        sourceDraftId = OLD_DRAFT_ID,
        supplierId = SUPPLIER_ID,
        supplierRuc = "20123456789",
        supplierLegalName = "Proveedor SAC",
        documentType = PurchaseDocumentType.INVOICE,
        documentSeries = "F001",
        documentNumber = "000123",
        issueDate = DATE,
        currency = PEN,
        total = Money.ofMinor(1_000, PEN),
        status = PurchaseStatus.POSTED,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-13T12:00:00Z")
        val DATE: LocalDate = LocalDate.of(2026, 8, 12)
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DRAFT_ID: DraftId = DraftId.from(uuid(2))
        val OLD_DRAFT_ID: DraftId = DraftId.from(uuid(3))
        val SUPPLIER_ID: SupplierId = SupplierId.from(uuid(4))
        val EXISTING_PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(5))
        val NEW_PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(6))
        const val PREPARED_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
