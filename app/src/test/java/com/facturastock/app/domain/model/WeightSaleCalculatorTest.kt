package com.facturastock.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightSaleCalculatorTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun sevenSolesAtEightPerKiloProducesExactlyPointEightSevenFiveKilos() {
        val amount = Money.fromMajor("7.00", pen)
        val price = Money.fromMajor("8.00", pen)
        val quantity = requireNotNull(WeightSaleCalculator.fromAmount(amount, price))

        assertEquals(Quantity.of("0.875"), quantity)
        assertEquals(amount, calculateSaleLineGross(price, quantity))
        assertEquals(amount, WeightSaleCalculator.fromQuantity(quantity, price))
    }

    @Test
    fun recurringDivisionKeepsTheRequestedCentInsteadOfRoundingWeightToGrams() {
        val amount = Money.fromMajor("1.00", pen)
        val price = Money.fromMajor("3.00", pen)
        val quantity = requireNotNull(WeightSaleCalculator.fromAmount(amount, price))

        assertEquals(Quantity.of("0.333333333333333333"), quantity)
        assertEquals(amount, calculateSaleLineGross(price, quantity))
    }

    @Test
    fun fractionalKilosUseExistingHalfUpRoundingAndRejectAnUnchargeableWeight() {
        assertEquals(
            Money.fromMajor("3.00", pen),
            WeightSaleCalculator.fromQuantity(Quantity.of("0.375"), Money.fromMajor("8.00", pen)),
        )
        val price = Money.fromMajor("1.00", pen)
        assertEquals(Money.ofMinor(1L, pen), WeightSaleCalculator.fromQuantity(Quantity.of("0.005"), price))
        assertNull(WeightSaleCalculator.fromQuantity(Quantity.of("0.004"), price))
    }

    @Test
    fun oneCentAtTheLargestCatalogPriceIsStillRepresentableAndChargesOneCent() {
        val amount = Money.ofMinor(1L, pen)
        val price = Money.ofMinor(ProductSalePricePolicy.MAX_MINOR_UNITS, pen)
        val quantity = requireNotNull(WeightSaleCalculator.fromAmount(amount, price))

        assertEquals(Quantity.of("0.000000000000000111"), quantity)
        assertEquals(amount, calculateSaleLineGross(price, quantity))
    }

    @Test
    fun longMaximumAmountRemainsRepresentableAndQuantityOverflowReturnsNull() {
        val amount = Money.ofMinor(Long.MAX_VALUE, pen)
        val price = Money.ofMinor(1L, pen)
        val quantity = requireNotNull(WeightSaleCalculator.fromAmount(amount, price))

        assertEquals(Quantity.of(Long.MAX_VALUE.toString()), quantity)
        assertEquals(amount, WeightSaleCalculator.fromQuantity(quantity, price))
        assertNull(WeightSaleCalculator.fromQuantity(Quantity.of("9223372036854775808"), price))
    }

    @Test
    fun zeroNegativeAndNonInteroperablePricesCannotBecomeWeightSales() {
        val amount = Money.ofMinor(100L, pen)
        for (minorUnits in listOf(0L, -1L, ProductSalePricePolicy.MAX_MINOR_UNITS + 1L, Long.MAX_VALUE)) {
            val price = Money.ofMinor(minorUnits, pen)
            assertNull(WeightSaleCalculator.fromAmount(amount, price))
            assertNull(WeightSaleCalculator.fromQuantity(Quantity.of("1"), price))
        }
        val validPrice = Money.ofMinor(100L, pen)
        assertNull(WeightSaleCalculator.fromAmount(Money.zero(pen), validPrice))
        assertNull(WeightSaleCalculator.fromAmount(Money.ofMinor(-1L, pen), validPrice))
        assertNull(WeightSaleCalculator.fromAmount(Money.ofMinor(Long.MIN_VALUE, pen), validPrice))
    }

    @Test
    fun amountsInAnotherCurrencyAreRejectedAndMatchingCurrencyScalesRoundTrip() {
        assertNull(
            WeightSaleCalculator.fromAmount(
                Money.ofMinor(100L, pen),
                Money.ofMinor(300L, CurrencyCode.of("USD")),
            ),
        )
        for (currency in listOf(pen, CurrencyCode.of("JPY"), CurrencyCode.of("BHD"))) {
            for (amountMinor in listOf(1L, 7L, 100L, Long.MAX_VALUE)) {
                for (priceMinor in listOf(1L, 3L, 8L, ProductSalePricePolicy.MAX_MINOR_UNITS)) {
                    val amount = Money.ofMinor(amountMinor, currency)
                    val price = Money.ofMinor(priceMinor, currency)
                    val quantity = WeightSaleCalculator.fromAmount(amount, price)
                    assertNotNull("$amountMinor / $priceMinor $currency", quantity)
                    assertEquals(amount, calculateSaleLineGross(price, requireNotNull(quantity)))
                }
            }
        }
    }

    @Test
    fun integerWeightsHaveSupportedScaleAndKilogramEligibilityIsExplicit() {
        val amount = Money.fromMajor("8000.00", pen)
        val quantity = WeightSaleCalculator.fromAmount(amount, Money.fromMajor("8.00", pen))
        assertEquals(Quantity.of("1000"), quantity)
        for (code in listOf("KGM", "KG", "kgm", "kg", " KgM ")) {
            assertTrue(code, WeightSaleCalculator.isKilogramUnit(code))
        }
        for (code in listOf("", "NIU", "GRM", "G", "KILO", "CAJA", "KG/L")) {
            assertFalse(code, WeightSaleCalculator.isKilogramUnit(code))
        }
    }
}
