package com.facturastock.app.domain.usecase

import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeRecentDraftReadRepository
import com.facturastock.app.testing.FakeSupplierRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObserveRecentDraftsUseCaseTest {
    private var now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock = AppClock { now }
    private val appConfig = FakeAppConfigurationRepository()
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val suppliers = FakeSupplierRepository(clock)
    private val recentDrafts = FakeRecentDraftReadRepository(drafts, suppliers)
    private val useCase = ObserveRecentDraftsUseCase(
        appConfigurationRepository = appConfig,
        recentDraftReadRepository = recentDrafts,
    )

    @Test
    fun `emits an empty list while there is no active business`() = runTest {
        useCase().test {
            assertEquals(emptyList<RecentDraft>(), awaitItem())
            assertEquals(emptyList<Pair<BusinessId, Int>>(), recentDrafts.observedRequests)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits drafts ordered by recency with the supplier name resolved`() = runTest {
        appConfig.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        suppliers.create(supplier(SUPPLIER_ID, "Distribuidora Andina S.A.C."))
        drafts.createDraft(draft(draftId(1), supplierId = SUPPLIER_ID))
        advanceClock()
        drafts.createDraft(draft(draftId(2)))

        useCase().test {
            val emission = awaitItem()
            assertEquals(listOf(draftId(2), draftId(1)), emission.map { it.draft.draftId })
            assertEquals("Distribuidora Andina S.A.C.", emission[1].supplierName)
            assertNull(emission[0].supplierName)
            assertEquals(listOf(BUSINESS_ID to 10), recentDrafts.observedRequests)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `keeps only the ten most recent drafts`() = runTest {
        appConfig.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        repeat(12) { index ->
            drafts.createDraft(draft(draftId(index + 1)))
            advanceClock()
        }

        useCase().test {
            val emission = awaitItem()
            assertEquals(10, emission.size)
            assertEquals(draftId(12), emission.first().draft.draftId)
            assertEquals(draftId(3), emission.last().draft.draftId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `excludes a draft that already became a confirmed purchase`() = runTest {
        appConfig.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        drafts.createDraft(draft(draftId(1)))
        drafts.createDraft(
            draft(draftId(2)).copy(
                status = DraftStatus.COMMITTED,
                confirmedPurchaseId = PURCHASE_ID,
            ),
        )

        useCase().test {
            assertEquals(listOf(draftId(1)), awaitItem().map { it.draft.draftId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switches the observed drafts when the active business changes`() = runTest {
        appConfig.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        drafts.createDraft(draft(draftId(1), businessId = BUSINESS_ID))

        useCase().test {
            assertEquals(listOf(draftId(1)), awaitItem().map { it.draft.draftId })

            appConfig.enterDemoMode(DEMO_BUSINESS_ID)
            assertEquals(emptyList<RecentDraft>(), awaitItem())

            drafts.createDraft(draft(draftId(2), businessId = DEMO_BUSINESS_ID))
            assertEquals(listOf(draftId(2)), awaitItem().map { it.draft.draftId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun advanceClock() {
        now = now.plusSeconds(60)
    }

    private fun draft(
        id: DraftId,
        businessId: BusinessId = BUSINESS_ID,
        supplierId: SupplierId? = null,
    ) = InvoiceDraft(
        draftId = id,
        businessId = businessId,
        status = DraftStatus.CREATED,
        supplierId = supplierId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun supplier(id: SupplierId, legalName: String) = Supplier(
        supplierId = id,
        businessId = BUSINESS_ID,
        legalName = legalName,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val DEMO_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
        )
        val SUPPLIER_ID: SupplierId = SupplierId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
        )
        val PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
        )

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

        fun draftId(seed: Int): DraftId = DraftId.from(uuid(seed))
    }
}
