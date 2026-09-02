package com.facturastock.app.domain.usecase

import app.cash.turbine.test
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseHistoryRequest
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakePurchaseReadRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservePurchaseReadsUseCasesTest {
    private val configuration = FakeAppConfigurationRepository()
    private val purchases = FakePurchaseReadRepository()
    private val observePurchases = ObservePurchasesUseCase(configuration, purchases)
    private val observeHistory = ObservePurchaseHistoryUseCase(configuration, purchases)
    private val observeDetail = ObservePurchaseDetailUseCase(configuration, purchases)

    @Test
    fun `a newly posted snapshot is forwarded immediately without accumulating the prior one`() =
        runTest {
            activate(BUSINESS_ID)
            val posted = summary(seed = 1, businessId = BUSINESS_ID)

            observePurchases().test {
                assertEquals(emptyList<PurchaseReadSummary>(), awaitItem())

                purchases.replacePurchases(BUSINESS_ID, listOf(posted))
                assertEquals(listOf(posted), awaitItem())

                val synced = posted.copy(syncState = PurchaseSyncState.SYNCED)
                purchases.replacePurchases(BUSINESS_ID, listOf(synced))
                val refreshed = awaitItem()
                assertEquals(1, refreshed.size)
                assertEquals(synced, refreshed.single())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `switching active business cancels the previous tenant and observes only the new one`() =
        runTest {
            val realPurchase = summary(seed = 1, businessId = BUSINESS_ID)
            val demoPurchase = summary(seed = 2, businessId = DEMO_BUSINESS_ID)
            purchases.replacePurchases(BUSINESS_ID, listOf(realPurchase))
            purchases.replacePurchases(DEMO_BUSINESS_ID, listOf(demoPurchase))
            activate(BUSINESS_ID)

            observePurchases().test {
                assertEquals(listOf(realPurchase), awaitItem())

                configuration.enterDemoMode(DEMO_BUSINESS_ID)
                assertEquals(listOf(demoPurchase), awaitItem())

                val secondRealPurchase = summary(seed = 3, businessId = BUSINESS_ID)
                purchases.replacePurchases(
                    BUSINESS_ID,
                    listOf(secondRealPurchase, realPurchase),
                )
                expectNoEvents()

                val secondDemoPurchase = summary(seed = 4, businessId = DEMO_BUSINESS_ID)
                purchases.replacePurchases(
                    DEMO_BUSINESS_ID,
                    listOf(secondDemoPurchase, demoPurchase),
                )
                assertEquals(listOf(secondDemoPurchase, demoPurchase), awaitItem())

                configuration.exitDemoMode()
                assertEquals(listOf(secondRealPurchase, realPurchase), awaitItem())
                assertEquals(
                    listOf(BUSINESS_ID, DEMO_BUSINESS_ID, BUSINESS_ID),
                    purchases.observedPurchaseBusinesses,
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `list is empty and repository is not queried without an active business`() = runTest {
        observePurchases().test {
            assertEquals(emptyList<PurchaseReadSummary>(), awaitItem())
            assertEquals(emptyList<BusinessId>(), purchases.observedPurchaseBusinesses)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paged UI never truncates the exhaustive purchase flow`() = runTest {
        activate(BUSINESS_ID)
        val completeHistory = (1..35).map { seed ->
            summary(seed = seed, businessId = BUSINESS_ID)
        }
        purchases.replacePurchases(BUSINESS_ID, completeHistory)

        observePurchases().test {
            assertEquals(completeHistory, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        observeHistory(
            PurchaseHistoryRequest(pageSize = 30, visiblePages = 1),
        ).test {
            val page = awaitItem()
            assertEquals(completeHistory.take(30), page.items)
            assertEquals(true, page.hasMore)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `detail is null without an active business and a foreign tenant is never queried`() =
        runTest {
            val purchaseId = purchaseId(8)
            val foreignDetail = detail(summary(8, DEMO_BUSINESS_ID))
            purchases.setPurchaseDetail(DEMO_BUSINESS_ID, purchaseId, foreignDetail)

            observeDetail(purchaseId).test {
                assertNull(awaitItem())
                assertEquals(emptyList<Pair<BusinessId, PurchaseId>>(), purchases.observedDetails)

                activate(BUSINESS_ID)
                assertNull(awaitItem())
                assertEquals(
                    listOf(BUSINESS_ID to purchaseId),
                    purchases.observedDetails,
                )

                configuration.enterDemoMode(DEMO_BUSINESS_ID)
                assertEquals(foreignDetail, awaitItem())
                assertEquals(
                    listOf(BUSINESS_ID to purchaseId, DEMO_BUSINESS_ID to purchaseId),
                    purchases.observedDetails,
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun activate(businessId: BusinessId) {
        configuration.completeOnboarding(
            businessId,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun summary(
        seed: Int,
        businessId: BusinessId,
    ): PurchaseReadSummary = PurchaseReadSummary(
        purchaseId = purchaseId(seed),
        businessId = businessId,
        sourceDraftId = DraftId.from(uuid(100 + seed)),
        supplierRuc = "20123456786",
        supplierLegalName = "Proveedor $seed SAC",
        documentType = PurchaseDocumentType.INVOICE,
        documentSeries = "F001",
        documentNumber = "%08d".format(seed),
        issueDate = LocalDate.of(2026, 8, 1).plusDays(seed.toLong()),
        currency = PEN,
        total = Money.ofMinor(seed * 1_000L, PEN),
        status = PurchaseStatus.POSTED,
        syncState = PurchaseSyncState.PENDING_SYNC,
        lineCount = 0,
        productCount = 0,
        postedAt = Instant.parse("2026-08-14T12:00:00Z").plusSeconds(seed.toLong()),
    )

    private fun detail(summary: PurchaseReadSummary): PurchaseReadDetail = PurchaseReadDetail(
        summary = summary,
        supplierId = SupplierId.from(uuid(500)),
        subtotal = summary.total,
        tax = Money.zero(summary.currency),
        otherCharges = Money.zero(summary.currency),
        adjustment = null,
        lines = emptyList(),
        movements = emptyList(),
        auditEvents = emptyList(),
        images = emptyList(),
        preparedLogicalHash = "a".repeat(64),
        acceptedWarnings = emptyList(),
    )

    private companion object {
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(901))
        val DEMO_BUSINESS_ID: BusinessId = BusinessId.from(uuid(902))

        fun purchaseId(seed: Int): PurchaseId = PurchaseId.from(uuid(seed))

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
