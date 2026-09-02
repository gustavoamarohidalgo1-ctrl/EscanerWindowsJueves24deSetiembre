package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SalesTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun unpricedDraftLineIsExplicitAndContributesZeroToTotals() {
        val line = line(unitPrice = null, lineTotal = null)
        val cart = SaleCart(
            saleId = SaleId.from(uuid(1)),
            businessId = BusinessId.from(uuid(2)),
            status = SaleStatus.DRAFT,
            currency = pen,
            lines = listOf(line),
            subtotal = Money.zero(pen),
            discount = Money.zero(pen),
            tax = Money.zero(pen),
            total = Money.zero(pen),
            contentHash = "a".repeat(64),
            version = 1L,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            postedAt = null,
        )

        assertNull(cart.lines.single().unitPrice)
        assertEquals(0L, cart.total.minorUnits)
    }

    @Test
    fun explicitFractionalPriceUsesClosedHalfUpMinorUnitPolicy() {
        val gross = calculateSaleLineGross(
            unitPrice = Money.ofMinor(101L, pen),
            quantity = Quantity.of("1.005"),
        )

        assertEquals(102L, gross.minorUnits)
    }

    @Test
    fun zeroPriceIsNotAHiddenSentinelOrFreeSale() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveSaleCartLineCommand(
                saleId = SaleId.from(uuid(1)),
                expectedVersion = 0L,
                productId = ProductId.from(uuid(3)),
                locationId = LocationId.from(uuid(4)),
                quantity = Quantity.of("1"),
                unitPrice = Money.zero(pen),
            )
        }
    }

    @Test
    fun unpricedCommandRejectsDiscountOrTax() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveSaleCartLineCommand(
                saleId = SaleId.from(uuid(1)),
                expectedVersion = 0L,
                productId = ProductId.from(uuid(3)),
                locationId = LocationId.from(uuid(4)),
                quantity = Quantity.of("1"),
                unitPrice = null,
                discount = Money.ofMinor(1L, pen),
            )
        }
    }

    @Test
    fun postedSaleRejectsEmptyCart() {
        assertThrows(IllegalArgumentException::class.java) {
            cart(
                status = SaleStatus.POSTED,
                lines = emptyList(),
                postedAt = Instant.EPOCH,
            )
        }
    }

    @Test
    fun postedSaleRejectsLineWithoutConfirmedPrice() {
        assertThrows(IllegalArgumentException::class.java) {
            cart(
                status = SaleStatus.POSTED,
                lines = listOf(line(unitPrice = null, lineTotal = null)),
                postedAt = Instant.EPOCH,
            )
        }
    }

    private fun cart(
        status: SaleStatus,
        lines: List<SaleCartLine>,
        postedAt: Instant?,
    ): SaleCart = SaleCart(
        saleId = SaleId.from(uuid(1)),
        businessId = BusinessId.from(uuid(2)),
        status = status,
        currency = pen,
        lines = lines,
        subtotal = Money.zero(pen),
        discount = Money.zero(pen),
        tax = Money.zero(pen),
        total = Money.zero(pen),
        contentHash = "a".repeat(64),
        version = 1L,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        postedAt = postedAt,
    )

    private fun line(unitPrice: Money?, lineTotal: Money?): SaleCartLine = SaleCartLine(
        saleLineId = SaleLineId.from(uuid(5)),
        productId = ProductId.from(uuid(3)),
        unitId = UnitId.from(uuid(6)),
        locationId = LocationId.from(uuid(4)),
        position = 0,
        productName = "Producto",
        unitCode = "NIU",
        locationName = "Principal",
        barcode = null,
        quantity = Quantity.of("1"),
        unitPrice = unitPrice,
        discount = Money.zero(pen),
        tax = Money.zero(pen),
        lineTotal = lineTotal,
    )

    private fun uuid(seed: Int): UUID = UUID(seed.toLong(), seed.toLong())
}
