package com.facturastock.app.navigation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.StartInvoiceDraftUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftFlowViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = AppClock { NOW }
    private val configuration = FakeAppConfigurationRepository()
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val fileStore = FakeDraftFileStore()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `double start persists one draft before emitting camera navigation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val uuids = CountingUuidGenerator(DRAFT_UUID)
            val savedStateHandle = SavedStateHandle()
            val viewModel = createViewModel(savedStateHandle, uuids)

            viewModel.effects.test {
                viewModel.onAction(DraftFlowContract.Action.StartDraft)
                viewModel.onAction(DraftFlowContract.Action.StartDraft)
                runCurrent()

                assertEquals(
                    DraftFlowContract.Effect.OpenDraftCamera(DRAFT_ID),
                    awaitItem(),
                )
                assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(1, uuids.calls)
                assertTrue(viewModel.uiState.value.isCreatingDraft)

                viewModel.onAction(DraftFlowContract.Action.StartNavigationHandled)
                runCurrent()
                assertFalse(viewModel.uiState.value.isCreatingDraft)
                assertNull(savedStateHandle.get<String>("draftFlow.pendingCreationId"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `process recreation resumes pending creation without inserting a duplicate`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft())
            val uuids = CountingUuidGenerator(UNUSED_UUID)
            val savedStateHandle = SavedStateHandle(
                mapOf("draftFlow.pendingCreationId" to DRAFT_ID.value),
            )
            val recreated = createViewModel(savedStateHandle, uuids)

            recreated.effects.test {
                runCurrent()
                assertEquals(
                    DraftFlowContract.Effect.OpenDraftCamera(DRAFT_ID),
                    awaitItem(),
                )
                assertEquals(DRAFT_ID, drafts.findDraft(DRAFT_ID)?.draftId)
                assertEquals(0, uuids.calls)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `double discard deletes once and navigation waits for durable absence`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft())
            val savedStateHandle = SavedStateHandle()
            val viewModel = createViewModel(
                savedStateHandle,
                CountingUuidGenerator(UNUSED_UUID),
            )

            viewModel.effects.test {
                viewModel.onAction(DraftFlowContract.Action.DiscardDraft(DRAFT_ID))
                viewModel.onAction(DraftFlowContract.Action.DiscardDraft(DRAFT_ID))
                runCurrent()

                assertEquals(
                    DraftFlowContract.Effect.DraftDiscarded(DRAFT_ID),
                    awaitItem(),
                )
                assertNull(drafts.findDraft(DRAFT_ID))
                assertEquals(DRAFT_ID, viewModel.uiState.value.discardingDraftId)

                viewModel.onAction(DraftFlowContract.Action.DiscardNavigationHandled)
                runCurrent()
                assertNull(viewModel.uiState.value.discardingDraftId)
                assertNull(savedStateHandle.get<String>("draftFlow.pendingDiscardId"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle,
        uuidGenerator: UuidGenerator,
    ): DraftFlowViewModel = DraftFlowViewModel(
        savedStateHandle = savedStateHandle,
        startInvoiceDraftUseCase = StartInvoiceDraftUseCase(
            appConfigurationRepository = configuration,
            invoiceDraftRepository = drafts,
            appClock = clock,
        ),
        deleteDraftUseCase = DeleteDraftUseCase(drafts, fileStore),
        observeInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(drafts),
        uuidGenerator = uuidGenerator,
        dispatcherProvider = dispatchers,
    )

    private suspend fun activateBusiness() {
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun draft() = InvoiceDraft(
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        status = DraftStatus.CREATED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private class CountingUuidGenerator(private val uuid: UUID) : UuidGenerator {
        var calls: Int = 0
            private set

        override fun newUuid(): UUID {
            calls += 1
            return uuid
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-20T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
        )
        val DRAFT_UUID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val DRAFT_ID: DraftId = DraftId.from(DRAFT_UUID)
        val UNUSED_UUID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    }
}
