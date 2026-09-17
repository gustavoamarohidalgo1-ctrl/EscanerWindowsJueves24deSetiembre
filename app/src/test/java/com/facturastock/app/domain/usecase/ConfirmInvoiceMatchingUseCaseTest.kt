package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceMatchUnitChoice
import com.facturastock.app.domain.model.InvoiceMatchingIssue
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InvoiceMatchingCommitRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ConfirmInvoiceMatchingUseCaseTest {
    @Test fun `no active business never calls commit`() =
        runTest {
            val useCase =
                ConfirmInvoiceMatchingUseCase(
                    FakeAppConfigurationRepository(),
                    object : InvoiceMatchingCommitRepository {
                        override suspend fun confirm(
                            businessId: BusinessId,
                            currency: CurrencyCode,
                            draftId: DraftId,
                            items: List<ScannedItemMatch>,
                        ): ConfirmInvoiceMatchingResult = error("must not call")
                    },
                )
            assertEquals(ConfirmInvoiceMatchingResult.NoActiveBusiness, useCase(DRAFT, emptyList()))
        }

    @Test fun `commit receives complete reviewed batch without filtering unknown quantities`() =
        runTest {
            val config = FakeAppConfigurationRepository()
            config.completeOnboarding(BUSINESS, AppConfiguration.DEFAULT_TAX_RATE, CostPolicy.NET)
            val rows = listOf(item(), item().copy(lineIndex = 1, quantity = null))
            val expected = ConfirmInvoiceMatchingResult.ReviewRequired(mapOf(1 to setOf(InvoiceMatchingIssue.QUANTITY_REQUIRED)))
            val useCase =
                ConfirmInvoiceMatchingUseCase(
                    config,
                    object : InvoiceMatchingCommitRepository {
                        override suspend fun confirm(
                            businessId: BusinessId,
                            currency: CurrencyCode,
                            draftId: DraftId,
                            items: List<ScannedItemMatch>,
                        ): ConfirmInvoiceMatchingResult {
                            assertEquals(BUSINESS, businessId)
                            assertEquals(DRAFT, draftId)
                            assertEquals(rows, items)
                            return expected
                        }
                    },
                )
            assertEquals(expected, useCase(DRAFT, rows))
        }

    @Test fun `box quantities convert but quantities already in inventory units do not`() {
        val box = item().copy(sourceUnitCode = "CJA")
        val boxes = requireNotNull(InvoiceMatchingStockResolver.resolve(box, "NIU", "CJA"))
        assertDecimal("24", boxes.quantity)
        assertDecimal("10", boxes.unitCost)
        assertDecimal("240", boxes.appliedCostTotal)
        val units = requireNotNull(InvoiceMatchingStockResolver.resolve(box.copy(sourceUnitCode = "NIU"), "NIU", "CJA"))
        assertDecimal("2", units.quantity)
        assertDecimal("120", units.unitCost)
    }

    @Test fun `unknown units require an explicit unit choice`() {
        val row = item().copy(sourceUnitCode = null)
        assertTrue(InvoiceMatchingIssue.UNIT_REQUIRED in InvoiceMatchingStockResolver.issues(row, PEN, "NIU", "CJA"))
        assertNull(InvoiceMatchingStockResolver.resolve(row, "NIU", "CJA"))
        assertDecimal("2", requireNotNull(InvoiceMatchingStockResolver.resolve(row.copy(unitChoice = InvoiceMatchUnitChoice.INVENTORY), "NIU", "CJA")).quantity)
        assertDecimal("24", requireNotNull(InvoiceMatchingStockResolver.resolve(row.copy(unitChoice = InvoiceMatchUnitChoice.PURCHASE), "NIU", "CJA")).quantity)
    }

    @Test fun `unknown quantity cost and currency cannot silently become an entry`() {
        val issues = InvoiceMatchingStockResolver.issues(item().copy(quantity = null, unitCost = null, sourceCurrency = null), PEN, "NIU", "CJA")
        assertTrue(issues.containsAll(setOf(InvoiceMatchingIssue.QUANTITY_REQUIRED, InvoiceMatchingIssue.COST_REQUIRED, InvoiceMatchingIssue.CURRENCY_REQUIRED)))
        assertTrue(InvoiceMatchingIssue.CURRENCY_MISMATCH in InvoiceMatchingStockResolver.issues(item().copy(sourceCurrency = CurrencyCode.of("USD")), PEN, "NIU", "CJA"))
        assertFalse(InvoiceMatchingIssue.COST_REQUIRED in InvoiceMatchingStockResolver.issues(item().copy(unitCost = BigDecimal.ZERO), PEN, "NIU", "CJA"))
    }

    @Test fun `nonterminating unit division preserves exact incoming total`() {
        val row = item().copy(quantity = BigDecimal.ONE, unitCost = BigDecimal("100"), sourceUnitCode = "CJA", matchedProduct = product().copy(purchaseFactor = BigDecimal("3")))
        val resolved = requireNotNull(InvoiceMatchingStockResolver.resolve(row, "NIU", "CJA"))
        assertDecimal("3", resolved.quantity)
        assertDecimal("100", resolved.appliedCostTotal)
        assertEquals(18, resolved.unitCost.scale())
    }

    private fun item() = ScannedItemMatch(lineIndex = 0, rawDescription = "Arroz", quantity = BigDecimal("2"), unitCost = BigDecimal("120"), matchedProduct = product(), sourceCurrency = PEN, sourceUnitCode = "NIU")

    private fun product() = Product(ProductId.from(UUID(0, 3)), BUSINESS, UnitId.from(UUID(0, 4)), "Arroz", purchaseUnitId = UnitId.from(UUID(0, 5)), purchaseFactor = BigDecimal("12"), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    private fun assertDecimal(
        expected: String,
        actual: BigDecimal,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    companion object {
        val BUSINESS = BusinessId.from(UUID(0, 1))
        val DRAFT = DraftId.from(UUID(0, 2))
        val PEN = CurrencyCode.of("PEN")
    }
}
