package com.facturastock.app.domain.usecase

import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.HomeDashboardRead
import com.facturastock.app.domain.model.HomeDraftOverview
import com.facturastock.app.domain.model.HomeInventoryOverview
import com.facturastock.app.domain.model.HomePurchaseOverview
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeHomeDashboardReadRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeRecentDraftReadRepository
import com.facturastock.app.testing.FakeSupplierRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveHomeDashboardUseCaseTest {
    private var now: Instant = NOW
    private val clock = AppClock { now }
    private val configuration = FakeAppConfigurationRepository()
    private val businesses = FakeBusinessRepository(clock)
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val suppliers = FakeSupplierRepository(clock)
    private val recentDrafts = FakeRecentDraftReadRepository(drafts, suppliers)
    private val dashboard = FakeHomeDashboardReadRepository(recentDrafts)
    private val useCase = ObserveHomeDashboardUseCase(
        configurationRepository = configuration,
        businessRepository = businesses,
        dashboardReadRepository = dashboard,
        clock = clock,
    )

    @Test
    fun `emits a coherent real business snapshot with every dashboard source`() = runTest {
        val business = businesses.create(business(BUSINESS_ID, "Bodega Central"))
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        suppliers.create(supplier(SUPPLIER_ID, BUSINESS_ID, "Distribuidora Andina S.A.C."))
        drafts.createDraft(draft(DRAFT_ID, BUSINESS_ID, supplierId = SUPPLIER_ID))

        dashboard.replaceDashboard(
            BUSINESS_ID,
            dashboardRead(
                inventoryProductCount = 8,
                postedPurchaseCount = 3,
            ),
        )
        useCase().test {
            val snapshot = awaitItem()

            assertEquals(business, snapshot.business)
            assertFalse(snapshot.isDemoMode)
            assertEquals(AppConfiguration.DEFAULT_ZONE_ID, snapshot.zoneId)
            assertEquals(NOW, snapshot.observedAt)
            assertEquals(1, snapshot.overview.drafts.openCount)
            assertEquals(listOf(DRAFT_ID), snapshot.overview.drafts.recent.map {
                it.draft.draftId
            })
            assertEquals(
                "Distribuidora Andina S.A.C.",
                snapshot.overview.drafts.recent.single().supplierName,
            )
            assertEquals(8, snapshot.overview.inventory.productCount)
            assertEquals(3, snapshot.overview.purchases.postedCount)
            assertEquals(listOf(BUSINESS_ID), dashboard.observedBusinessIds)
            assertEquals(listOf(BUSINESS_ID to 50), recentDrafts.observedRequests)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching tenant cancels the old sources and never mixes their data`() = runTest {
        businesses.create(business(BUSINESS_ID, "Negocio real"))
        businesses.create(business(DEMO_BUSINESS_ID, "Negocio demo"))
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        drafts.createDraft(draft(DRAFT_ID, BUSINESS_ID))
        drafts.createDraft(draft(DEMO_DRAFT_ID, DEMO_BUSINESS_ID))
        dashboard.replaceDashboard(
            BUSINESS_ID,
            dashboardRead(inventoryProductCount = 4, postedPurchaseCount = 1),
        )
        dashboard.replaceDashboard(
            DEMO_BUSINESS_ID,
            dashboardRead(inventoryProductCount = 9, postedPurchaseCount = 7),
        )
        useCase().test {
            val real = awaitItem()
            assertEquals(BUSINESS_ID, real.business?.businessId)
            assertFalse(real.isDemoMode)
            assertEquals(listOf(DRAFT_ID), real.overview.drafts.recent.map {
                it.draft.draftId
            })

            configuration.enterDemoMode(DEMO_BUSINESS_ID)
            val demo = awaitItem()
            assertEquals(DEMO_BUSINESS_ID, demo.business?.businessId)
            assertTrue(demo.isDemoMode)
            assertEquals(9, demo.overview.inventory.productCount)
            assertEquals(listOf(DEMO_DRAFT_ID), demo.overview.drafts.recent.map {
                it.draft.draftId
            })
            assertTrue(demo.overview.drafts.recent.all {
                it.draft.businessId == DEMO_BUSINESS_ID
            })

            dashboard.replaceDashboard(
                BUSINESS_ID,
                dashboardRead(inventoryProductCount = 99, postedPurchaseCount = 99),
            )
            drafts.createDraft(draft(draftId(12), BUSINESS_ID))
            runCurrent()
            expectNoEvents()

            dashboard.replaceDashboard(
                DEMO_BUSINESS_ID,
                dashboardRead(inventoryProductCount = 10, postedPurchaseCount = 8),
            )
            val updatedDemo = awaitItem()
            assertEquals(DEMO_BUSINESS_ID, updatedDemo.business?.businessId)
            assertEquals(10, updatedDemo.overview.inventory.productCount)
            assertEquals(listOf(BUSINESS_ID, DEMO_BUSINESS_ID), dashboard.observedBusinessIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `null active business emits an empty snapshot without opening tenant repositories`() =
        runTest {
            useCase().test {
                val snapshot = awaitItem()

                assertNull(snapshot.business)
                assertFalse(snapshot.isDemoMode)
                assertEquals(AppConfiguration.DEFAULT_ZONE_ID, snapshot.zoneId)
                assertEquals(emptyDashboardRead(), snapshot.overview)
                assertEquals(NOW, snapshot.observedAt)
                assertTrue(dashboard.observedBusinessIds.isEmpty())
                assertTrue(recentDrafts.observedRequests.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `reacts independently to business dashboard and draft updates`() = runTest {
        val originalBusiness = businesses.create(business(BUSINESS_ID, "Nombre inicial"))
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )

        useCase().test {
            val initial = awaitItem()
            assertEquals(originalBusiness, initial.business)
            assertEquals(emptyDashboardRead(), initial.overview)

            advanceClock()
            dashboard.replaceDashboard(
                BUSINESS_ID,
                dashboardRead(inventoryProductCount = 6, postedPurchaseCount = 2),
            )
            val dashboardUpdate = awaitItem()
            assertEquals(6, dashboardUpdate.overview.inventory.productCount)
            assertEquals(2, dashboardUpdate.overview.purchases.postedCount)
            assertEquals(now, dashboardUpdate.observedAt)

            advanceClock()
            drafts.createDraft(draft(DRAFT_ID, BUSINESS_ID))
            val draftUpdate = awaitItem()
            assertEquals(1, draftUpdate.overview.drafts.openCount)
            assertEquals(DRAFT_ID, draftUpdate.overview.drafts.recent.single().draft.draftId)
            assertEquals(now, draftUpdate.observedAt)

            advanceClock()
            assertTrue(businesses.update(originalBusiness.copy(legalName = "Nombre actualizado")))
            val businessUpdate = awaitItem()
            assertEquals("Nombre actualizado", businessUpdate.business?.legalName)
            assertEquals(now, businessUpdate.observedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun advanceClock() {
        now = now.plusSeconds(60)
    }

    private fun dashboardRead(
        inventoryProductCount: Int = 0,
        postedPurchaseCount: Int = 0,
    ): HomeDashboardRead = HomeDashboardRead(
        drafts = HomeDraftOverview(openCount = 0, recent = emptyList()),
        inventory = HomeInventoryOverview(
            productCount = inventoryProductCount,
            availableProductCount = inventoryProductCount,
            attentionProductCount = 0,
            withoutStockCount = 0,
            withoutSalePriceCount = 0,
            negativeStockCount = 0,
        ),
        purchases = HomePurchaseOverview(
            postedCount = postedPurchaseCount,
            syncProblemCount = 0,
        ),
    )

    private fun emptyDashboardRead(): HomeDashboardRead = dashboardRead()

    private fun business(businessId: BusinessId, name: String): Business = Business(
        businessId = businessId,
        legalName = name,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun supplier(
        supplierId: SupplierId,
        businessId: BusinessId,
        name: String,
    ): Supplier = Supplier(
        supplierId = supplierId,
        businessId = businessId,
        legalName = name,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun draft(
        draftId: DraftId,
        businessId: BusinessId,
        supplierId: SupplierId? = null,
    ): InvoiceDraft = InvoiceDraft(
        draftId = draftId,
        businessId = businessId,
        status = DraftStatus.CREATED,
        supplierId = supplierId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-28T15:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DEMO_BUSINESS_ID: BusinessId = BusinessId.from(uuid(2))
        val SUPPLIER_ID: SupplierId = SupplierId.from(uuid(3))
        val DRAFT_ID: DraftId = DraftId.from(uuid(4))
        val DEMO_DRAFT_ID: DraftId = DraftId.from(uuid(5))

        fun uuid(seed: Long): UUID = UUID(0L, seed)

        fun draftId(seed: Long): DraftId = DraftId.from(uuid(100 + seed))

    }
}
