package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
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
        entryStep = SalesContract.EntryStep.SELL,
        cartId = "00000000-0000-4000-8000-0000000000d1",
        cartContentHash = "a".repeat(64),
        cartLines = listOf(validLine),
        total = total,
    )

    @Test
    fun `pending checkout can be verified after network failure without accepting edits or scans`() {
        val pending = ready.copy(isCheckoutPending = true, failure = SalesContract.Failure.ONLINE_REQUIRED,
            catalogLoadFailed = true, cartLines = listOf(validLine.copy(quantityValid = false)))
        assertTrue(pending.canCheckout)
        assertFalse(pending.canRouteScannerInput)
        assertFalse(pending.copy(isMutating = true).canCheckout)
        assertFalse(pending.copy(cartLoadFailed = true).canCheckout)
        assertTrue(
            pending.copy(
                pendingAssociationBarcode = "753176004930",
                scannerFailure = SalesContract.ScannerFailure.INCOMPLETE,
            ).canCheckout,
        )
    }

    @Test
    fun `entry selectors never accept scans or enable checkout`() {
        assertEquals(SalesContract.EntryStep.SELECT_KIND, SalesContract.State().entryStep)
        listOf(SalesContract.EntryStep.SELECT_KIND, SalesContract.EntryStep.SELECT_MODE).forEach { step ->
            val selector = ready.copy(entryStep = step)
            assertFalse(selector.canCheckout)
            assertFalse(selector.canRouteScannerInput)
        }
        assertFalse(ready.copy(mode = SalesContract.EntryMode.MANUAL).canRouteScannerInput)
    }

    @Test
    fun `checkout only enables for a persisted valid snapshot`() {
        assertTrue(ready.canCheckout)
        assertTrue(ready.copy(searchFailed = true).canCheckout)
        assertFalse(ready.copy(isMutating = true).canCheckout)
        assertFalse(ready.copy(isMutating = true, isProcessingBarcode = true).canCheckout)
        assertFalse(ready.copy(isSavingLineEdits = true).canCheckout)
        assertFalse(ready.copy(hasPendingEdits = true).canCheckout)
        assertFalse(ready.copy(pendingBarcodeCount = 1).canCheckout)
        assertFalse(
            ready.copy(cartLines = listOf(validLine.copy(quantityValid = false))).canCheckout,
        )
        assertFalse(
            ready.copy(cartLines = listOf(validLine.copy(priceValid = false))).canCheckout,
        )
        assertFalse(ready.copy(cartContentHash = null).canCheckout)
        assertFalse(ready.copy(discardEditsReview = true).canCheckout)
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
        assertFalse(ready.copy(isTextInputFocused = true).canRouteScannerInput)
        assertFalse(ready.copy(isMutating = true).canRouteScannerInput)
        assertTrue(ready.copy(isMutating = true, isProcessingBarcode = true).canRouteScannerInput)
    }

    @Test
    fun `checkout waits for unresolved scanner choices or rejected reads`() {
        val option = SalesContract.ProductOption(
            productId = validLine.productId,
            productName = validLine.productName,
            locationId = validLine.locationId,
            locationName = validLine.locationName,
            unitCode = validLine.unitCode,
            availableQuantity = validLine.availableQuantity,
            sku = null,
            barcode = "7753176004930",
        )
        assertFalse(ready.copy(pendingAssociationBarcode = "753176004930").canCheckout)
        assertFalse(ready.copy(pendingLocations = listOf(option)).canCheckout)
        assertFalse(
            ready.copy(
                pendingReplacement = SalesContract.BarcodeReplacement(option, option.barcode, "753176004930"),
            ).canCheckout,
        )
        SalesContract.ScannerFailure.entries.forEach { failure ->
            val unresolved = ready.copy(scannerFailure = failure)
            assertFalse(unresolved.canCheckout)
            assertTrue(unresolved.canRouteScannerInput)
            assertTrue(unresolved.copy(scannerFailure = null).canCheckout)
        }
    }

    @Test
    fun `product registration requires an idle unknown reading and blocks sales input while open`() {
        val unknown = ready.copy(pendingAssociationBarcode = "753176004930")
        assertTrue(unknown.canRegisterProduct)
        assertFalse(ready.canRegisterProduct)
        assertFalse(unknown.copy(pendingBarcodeCount = 1).canRegisterProduct)
        assertFalse(unknown.copy(isMutating = true).canRegisterProduct)
        assertFalse(unknown.copy(hasPendingEdits = true).canRegisterProduct)
        assertFalse(unknown.copy(catalogLoadFailed = true).canRegisterProduct)
        assertFalse(unknown.copy(mode = SalesContract.EntryMode.MANUAL).canRegisterProduct)
        val request = SalesContract.ProductRegistrationRequest(
            requestId = UUID.randomUUID().toString(),
            barcode = requireNotNull(unknown.pendingAssociationBarcode),
            businessId = BusinessId.from(UUID(0L, 1L)),
            saleId = SaleId.from(UUID.fromString(requireNotNull(ready.cartId))),
        )
        val registering = unknown.copy(productRegistration = request)
        assertFalse(registering.canRegisterProduct)
        assertFalse(registering.canRouteScannerInput)
        assertFalse(registering.canCheckout)
    }

    @Test
    fun `pending association allows a new scan but preserves editor and mutation guards`() {
        val associating = ready.copy(pendingAssociationBarcode = "753176004930")

        assertTrue(associating.canRouteScannerInput)
        assertFalse(associating.copy(isTextInputFocused = true).canRouteScannerInput)
        assertFalse(associating.copy(isMutating = true).canRouteScannerInput)
        assertFalse(associating.copy(isSavingLineEdits = true).canRouteScannerInput)
        assertFalse(associating.copy(hasPendingEdits = true).canRouteScannerInput)
        assertFalse(associating.copy(discardEditsReview = true).canRouteScannerInput)
        val option = SalesContract.ProductOption(
            productId = validLine.productId,
            productName = validLine.productName,
            locationId = validLine.locationId,
            locationName = validLine.locationName,
            unitCode = validLine.unitCode,
            availableQuantity = validLine.availableQuantity,
            sku = null,
            barcode = "7753176004930",
        )
        assertFalse(associating.copy(pendingLocations = listOf(option)).canRouteScannerInput)
        assertFalse(
            associating.copy(
                pendingReplacement = SalesContract.BarcodeReplacement(option, option.barcode, "753176004930"),
            ).canRouteScannerInput,
        )
    }

    @Test
    fun `credit checkout requires a normalized debtor but scanner remains available first`() {
        val credit = ready.copy(entryKind = SalesContract.EntryKind.CREDIT)

        assertFalse(credit.canCheckout)
        assertTrue(credit.canRouteScannerInput)
        assertTrue(credit.copy(debtorNameInput = "  María   Quispe  ").canCheckout)
        assertFalse(credit.copy(debtorNameInput = "María Quispe", isMutating = true).canCheckout)
        assertEquals(
            "María Quispe",
            credit.copy(debtorNameInput = "  María   Quispe  ").canonicalDebtorName,
        )
        assertFalse(credit.copy(debtorNameInput = "A").canCheckout)
        assertFalse(credit.copy(debtorNameInput = "A".repeat(121)).canCheckout)
    }
}
