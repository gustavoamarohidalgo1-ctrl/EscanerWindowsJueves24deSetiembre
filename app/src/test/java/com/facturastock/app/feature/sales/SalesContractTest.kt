package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import java.math.BigDecimal
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesContractTest {
    private val total = Money.ofMinor(350L, CurrencyCode.of("PEN"))
    private val validLine = SalesContract.CartLine(
        lineId = "00000000-0000-4000-8000-0000000000c1",
        productId = ProductId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000a1"),
        ),
        productName = "Producto",
        locationId = LocationId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000b1"),
        ),
        locationName = "Principal",
        unitCode = "NIU",
        availableQuantity = BigDecimal.TEN,
        quantityInput = "1",
        unitPriceInput = "3.50",
        lineTotal = total,
    )
    private val ready = SalesContract.State(
        isLoading = false,
        cartId = "00000000-0000-4000-8000-0000000000d1",
        cartContentHash = "a".repeat(64),
        cartLines = listOf(validLine),
        total = total,
    )

    @Test
    fun `checkout only enables for a persisted valid snapshot`() {
        assertTrue(ready.canCheckout)
        assertTrue(ready.copy(searchFailed = true).canCheckout)
        assertFalse(ready.copy(isSavingLineEdits = true).canCheckout)
        assertFalse(ready.copy(hasPendingEdits = true).canCheckout)
        assertFalse(
            ready.copy(cartLines = listOf(validLine.copy(quantityValid = false))).canCheckout,
        )
        assertFalse(
            ready.copy(cartLines = listOf(validLine.copy(priceValid = false))).canCheckout,
        )
        assertFalse(ready.copy(cartContentHash = null).canCheckout)
        assertFalse(ready.copy(catalogLoadFailed = true).canCheckout)
        assertFalse(ready.copy(cartLoadFailed = true).canCheckout)
    }

    @Test
    fun `scanner retries input failures but remains blocked by structural state`() {
        assertTrue(ready.copy(failure = SalesContract.Failure.INVALID_BARCODE).canRouteScannerInput)
        assertTrue(ready.copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE).canRouteScannerInput)
        assertFalse(ready.copy(failure = SalesContract.Failure.SAVE_FAILED).canRouteScannerInput)
        assertFalse(ready.copy(isSavingLineEdits = true).canRouteScannerInput)
        assertFalse(ready.copy(hasPendingEdits = true).canRouteScannerInput)
        assertFalse(ready.copy(discardEditsReview = true).canRouteScannerInput)
    }

    @Test
    fun `credit checkout requires a normalized debtor but scanner remains available first`() {
        val credit = ready.copy(entryKind = SalesContract.EntryKind.CREDIT)

        assertFalse(credit.canCheckout)
        assertTrue(credit.canRouteScannerInput)
        assertTrue(credit.copy(debtorNameInput = "  María   Quispe  ").canCheckout)
        assertEquals(
            "María Quispe",
            credit.copy(debtorNameInput = "  María   Quispe  ").canonicalDebtorName,
        )
        assertFalse(credit.copy(debtorNameInput = "A").canCheckout)
        assertFalse(credit.copy(debtorNameInput = "A".repeat(121)).canCheckout)
    }
}
