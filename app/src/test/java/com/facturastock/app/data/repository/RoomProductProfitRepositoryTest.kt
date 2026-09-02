package com.facturastock.app.data.repository

import com.facturastock.app.data.local.dao.ProductProfitReadRow
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ProductProfitIssue
import com.facturastock.app.domain.model.ProductProfitStatus
import com.facturastock.app.domain.model.id.BusinessId
import java.math.BigDecimal
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomProductProfitRepositoryTest {
    @Test
    fun `pondera almacenes en BigDecimal y conserva el potencial exacto`() {
        val profit = singleProfit(
            row(locationSeed = 10, quantity = "2.000", cost = "3.005", currency = "PEN"),
            row(locationSeed = 11, quantity = "1", cost = "5.005", currency = "PEN"),
        )

        assertEquals(ProductProfitStatus.AVAILABLE, profit.status)
        assertEquals("3.000", profit.totalStockQuantity?.toPlainString())
        assertEquals("3.671666666666666667", profit.averageUnitCost?.amount?.toPlainString())
        assertEquals("2.328333333333333333", profit.unitProfit?.amount?.toPlainString())
        assertEquals(BigDecimal("38.8056"), profit.marginPercent)
        assertEquals("6.985000", profit.potentialProfit?.amount?.toPlainString())
        assertTrue(profit.issues.isEmpty())
    }

    @Test
    fun `calcula cantidades fraccionarias y costos subcentavo sin Float`() {
        val profit = singleProfit(
            row(
                locationSeed = 10,
                quantity = "0.333",
                cost = "0.005",
                currency = "PEN",
                salePriceMinorUnits = 1L,
            ),
        )

        assertEquals(ProductProfitStatus.AVAILABLE, profit.status)
        assertEquals("0.005000000000000000", profit.averageUnitCost?.amount?.toPlainString())
        assertEquals("0.005000000000000000", profit.unitProfit?.amount?.toPlainString())
        assertEquals(BigDecimal("50.0000"), profit.marginPercent)
        assertEquals("0.001665", profit.potentialProfit?.amount?.toPlainString())
    }

    @Test
    fun `ignora moneda de un saldo cero pero no mezcla monedas con stock positivo`() {
        val withHistoricalZero = singleProfit(
            row(locationSeed = 10, quantity = "2", cost = "3", currency = "PEN"),
            row(locationSeed = 11, quantity = "0", cost = "8", currency = "USD"),
        )
        val mixedPositive = singleProfit(
            row(locationSeed = 10, quantity = "2", cost = "3", currency = "PEN"),
            row(locationSeed = 11, quantity = "1", cost = "8", currency = "USD"),
        )

        assertEquals(ProductProfitStatus.AVAILABLE, withHistoricalZero.status)
        assertEquals(ProductProfitStatus.NON_COMPARABLE_CURRENCY, mixedPositive.status)
        assertTrue(ProductProfitIssue.MIXED_INVENTORY_CURRENCIES in mixedPositive.issues)
        assertNull(mixedPositive.unitProfit)
        assertNull(mixedPositive.potentialProfit)
    }

    @Test
    fun `precio e inventario en monedas distintas nunca inventan FX`() {
        val profit = singleProfit(
            row(locationSeed = 10, quantity = "2", cost = "3", currency = "USD"),
        )

        assertEquals(ProductProfitStatus.NON_COMPARABLE_CURRENCY, profit.status)
        assertTrue(ProductProfitIssue.SALE_PRICE_CURRENCY_MISMATCH in profit.issues)
        assertEquals(CurrencyCode.of("USD"), profit.averageUnitCost?.currency)
        assertNull(profit.unitProfit)
        assertNull(profit.marginPercent)
        assertNull(profit.potentialProfit)
    }

    @Test
    fun `saldo negativo o decimal hostil produce estado invalido sin parseo descontrolado`() {
        val negative = singleProfit(
            row(locationSeed = 10, quantity = "-0.001", cost = "3", currency = "PEN"),
        )
        val oversized = singleProfit(
            row(
                locationSeed = 10,
                quantity = "9".repeat(167),
                cost = "3",
                currency = "PEN",
            ),
        )
        val scientific = singleProfit(
            row(locationSeed = 10, quantity = "1e9", cost = "3", currency = "PEN"),
        )

        assertEquals(ProductProfitStatus.INVALID_INVENTORY, negative.status)
        assertTrue(ProductProfitIssue.NEGATIVE_STOCK in negative.issues)
        listOf(oversized, scientific).forEach { profit ->
            assertEquals(ProductProfitStatus.INVALID_INVENTORY, profit.status)
            assertTrue(ProductProfitIssue.INVALID_PERSISTED_VALUE in profit.issues)
        }
    }

    @Test
    fun `filtra filas de otro negocio antes de agrupar y sumar`() {
        val local = row(locationSeed = 10, quantity = "2", cost = "3", currency = "PEN")
        val foreign = row(
            locationSeed = 11,
            quantity = "999",
            cost = "1",
            currency = "USD",
            businessId = FOREIGN_BUSINESS_ID,
        )

        val profits = productProfitsFromRows(BUSINESS_ID, listOf(local, foreign))

        assertEquals(1, profits.size)
        assertEquals("2", profits.single().totalStockQuantity?.toPlainString())
        assertEquals(ProductProfitStatus.AVAILABLE, profits.single().status)
    }

    @Test
    fun `distingue precio ausente y stock ausente sin convertirlos en cero`() {
        val missingPrice = singleProfit(
            row(
                locationSeed = 10,
                quantity = "2",
                cost = "3",
                currency = "PEN",
                salePriceMinorUnits = null,
                salePriceCurrency = null,
            ),
        )
        val noBalance = singleProfit(
            row(
                locationSeed = null,
                quantity = null,
                cost = null,
                currency = null,
            ),
        )

        assertEquals(ProductProfitStatus.MISSING_SALE_PRICE, missingPrice.status)
        assertTrue(ProductProfitIssue.MISSING_SALE_PRICE in missingPrice.issues)
        assertEquals(ProductProfitStatus.NO_STOCK, noBalance.status)
        assertTrue(ProductProfitIssue.NO_INVENTORY_BALANCE in noBalance.issues)
        assertNull(noBalance.totalStockQuantity)
    }

    private fun singleProfit(vararg rows: ProductProfitReadRow) =
        productProfitsFromRows(BUSINESS_ID, rows.toList()).single()

    private fun row(
        locationSeed: Int?,
        quantity: String?,
        cost: String?,
        currency: String?,
        salePriceMinorUnits: Long? = 600L,
        salePriceCurrency: String? = "PEN",
        businessId: String = BUSINESS_ID.value,
    ) = ProductProfitReadRow(
        businessId = businessId,
        productId = PRODUCT_ID,
        productName = "Producto rentable",
        sku = "SKU-1",
        productStatus = "ACTIVE",
        productVersion = 7L,
        unitCode = "NIU",
        salePriceMinorUnits = salePriceMinorUnits,
        salePriceCurrencyCode = salePriceCurrency,
        locationId = locationSeed?.let(::uuid),
        quantityOnHand = quantity,
        averageUnitCost = cost,
        inventoryCurrencyCode = currency,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID.fromString(uuid(1)))
        val FOREIGN_BUSINESS_ID: String = uuid(2)
        val PRODUCT_ID: String = uuid(3)

        fun uuid(seed: Int): String = "00000000-0000-4000-8000-%012d".format(seed)
    }
}
