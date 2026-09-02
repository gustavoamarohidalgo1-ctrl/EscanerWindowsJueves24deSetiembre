package com.facturastock.app.data.sync

import com.facturastock.app.data.repository.SaleContentIdentity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.SharedInventoryChangeKind
import com.facturastock.app.domain.model.id.BusinessId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseSaleWireMapperTest {
    @Test
    fun `post ack maps exact authoritative balances`() {
        val ack = FirebaseSaleWireMapper.postAck(postAck())

        assertEquals(RECEIPT, ack.receiptId)
        assertEquals(1L, ack.seq)
        assertEquals("8", ack.balances.single().quantityOnHand)
        assertEquals("50", ack.balances.single().averageUnitCost)
    }

    @Test
    fun `pull maps full sale and validates movement`() {
        val page = mapPullPage(pullPage())

        assertFalse(page.hasMore)
        assertEquals(1L, page.nextCursor)
        val change = page.changes.single()
        assertEquals(SharedInventoryChangeKind.SALE, change.kind)
        assertEquals(SALE_ID, change.sale?.saleId?.value)
        assertEquals("2", change.sale?.lines?.single()?.quantity.toString())
    }

    @Test
    fun `unknown field is rejected before domain`() {
        val malformed = pullPage().toMutableMap().apply { put("futureField", true) }

        assertThrows(AccountException::class.java) {
            mapPullPage(malformed)
        }
    }

    @Test
    fun `movement with a different delta is rejected`() {
        val page = pullPage()
        val change = (page.getValue("changes") as List<*>).single() as Map<*, *>
        val sale = (change["sale"] as Map<*, *>).toMutableMap()
        val movement = ((sale.getValue("movements") as List<*>).single() as Map<*, *>)
            .toMutableMap()
            .apply { put("quantityDelta", "-1") }
        sale["movements"] = listOf(movement)
        val changed = change.toMutableMap().apply { put("sale", sale) }
        val malformed = page.toMutableMap().apply { put("changes", listOf(changed)) }

        assertThrows(AccountException::class.java) {
            mapPullPage(malformed)
        }
    }

    @Test
    fun `pull rejects a sale from another cloud business`() {
        val page = pullPage()
        val change = (page.getValue("changes") as List<*>).single() as Map<*, *>
        val foreignSale = (change["sale"] as Map<*, *>).toMutableMap()
            .apply { put("businessId", OTHER_BUSINESS_ID) }
        val malformedChange = change.toMutableMap().apply { put("sale", foreignSale) }
        val malformed = page.toMutableMap().apply { put("changes", listOf(malformedChange)) }

        assertThrows(AccountException::class.java) {
            mapPullPage(malformed)
        }
    }

    @Test
    fun `pull rejects a page that does not start after the requested cursor`() {
        assertThrows(AccountException::class.java) {
            FirebaseSaleWireMapper.pullPage(
                raw = pullPage(),
                expectedBusinessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
                expectedPreviousSeq = 1L,
            )
        }
    }

    @Test
    fun `pull rejects a nested sale receipt that differs from its change`() {
        val page = pullPage()
        val change = (page.getValue("changes") as List<*>).single() as Map<*, *>
        val sale = (change["sale"] as Map<*, *>).toMutableMap()
            .apply { put("receiptId", "sale_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") }
        val malformedChange = change.toMutableMap().apply { put("sale", sale) }
        val malformed = page.toMutableMap().apply { put("changes", listOf(malformedChange)) }

        assertThrows(AccountException::class.java) {
            mapPullPage(malformed)
        }
    }

    @Test
    fun `pull rejects a sale whose content hash does not authenticate its lines`() {
        val page = pullPage()
        val change = (page.getValue("changes") as List<*>).single() as Map<*, *>
        val wrongHash = "b".repeat(64)
        val sale = (change["sale"] as Map<*, *>).toMutableMap().apply {
            put("contentHash", wrongHash)
            put("checkoutIdempotencyKey", "sale-checkout:v1:$SALE_ID:1:$wrongHash")
        }
        val malformedChange = change.toMutableMap().apply { put("sale", sale) }
        val malformed = page.toMutableMap().apply { put("changes", listOf(malformedChange)) }

        assertThrows(AccountException::class.java) {
            mapPullPage(malformed)
        }
    }

    @Test
    fun `debt payment ack rejects a payment for a different debt`() {
        val ack = debtPaymentAck()
        val payment = (ack.getValue("payment") as Map<*, *>).toMutableMap().apply {
            put("debtId", OTHER_DEBT_ID)
            put("idempotencyKey", "debt-payment:v1:$OTHER_DEBT_ID:$PAYMENT_ID")
        }
        val malformed = ack.toMutableMap().apply { put("payment", payment) }

        assertThrows(AccountException::class.java) {
            FirebaseSaleWireMapper.debtPaymentAck(malformed)
        }
    }

    @Test
    fun `purchase inventory change accepts purchase receipt and null sale`() {
        val page = pullPage()
        val purchase = linkedMapOf<String, Any?>(
            "kind" to "PURCHASE",
            "seq" to 1L,
            "receiptId" to "rcpt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "sale" to null,
            "balances" to listOf(balance()),
            "syncedAtMillis" to null,
        )
        val wire = page.toMutableMap().apply { put("changes", listOf(purchase)) }

        val mapped = mapPullPage(wire)

        assertEquals(SharedInventoryChangeKind.PURCHASE, mapped.changes.single().kind)
        assertEquals(null, mapped.changes.single().sale)
    }

    @Test
    fun `bootstrap ack accepts its closed receipt and authoritative page zero base`() {
        val raw = linkedMapOf<String, Any?>(
            "receiptId" to "inventory_bootstrap_cccccccccccccccccccccccccccccccc",
            "idempotencyKey" to "inventory-bootstrap:v1:$BUSINESS_ID",
            "status" to "BOOTSTRAPPED",
            "seq" to 1L,
            "balances" to listOf(balance()),
        )

        val ack = FirebaseSaleWireMapper.bootstrapAck(raw)

        assertEquals(1L, ack.seq)
        assertEquals("8", ack.balances.single().quantityOnHand)
    }

    private fun postAck(): Map<String, Any?> = linkedMapOf(
        "receiptId" to RECEIPT,
        "idempotencyKey" to "sync-sale:v1:$SALE_ID",
        "status" to "RECORDED",
        "seq" to 1L,
        "postedAtMillis" to 1_200L,
        "balances" to listOf(balance()),
    )

    private fun debtPaymentAck(): Map<String, Any?> {
        val debtId = SaleContentIdentity.uuid("sale-debt", SALE_ID).toString()
        return linkedMapOf(
            "receiptId" to "debt_payment_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "idempotencyKey" to "debt-payment:v1:$debtId:$PAYMENT_ID",
            "status" to "RECORDED",
            "seq" to 2L,
            "debt" to linkedMapOf(
                "debtId" to debtId,
                "businessId" to BUSINESS_ID,
                "saleId" to SALE_ID,
                "debtorNameSnapshot" to "María Pérez",
                "currency" to "PEN",
                "originalMinorUnits" to 208L,
                "balanceMinorUnits" to 108L,
                "status" to "OPEN",
                "dueAt" to null,
                "version" to 2L,
                "createdAt" to 1_200L,
                "updatedAt" to 1_300L,
                "paidAt" to null,
            ),
            "payment" to linkedMapOf(
                "version" to 1L,
                "paymentId" to PAYMENT_ID,
                "debtId" to debtId,
                "businessId" to BUSINESS_ID,
                "currency" to "PEN",
                "amountMinorUnits" to 100L,
                "method" to "YAPE",
                "note" to null,
                "reference" to null,
                "expectedDebtVersion" to 1L,
                "balanceAfterMinorUnits" to 108L,
                "idempotencyKey" to "debt-payment:v1:$debtId:$PAYMENT_ID",
                "occurredAt" to 1_250L,
                "createdAt" to 1_300L,
            ),
        )
    }

    private fun pullPage(): Map<String, Any?> = linkedMapOf(
        "changes" to listOf(
            linkedMapOf(
                "kind" to "SALE",
                "seq" to 1L,
                "receiptId" to RECEIPT,
                "sale" to sale(),
                "balances" to listOf(balance()),
                "syncedAtMillis" to 1_250L,
            ),
        ),
        "nextCursor" to 1L,
        "hasMore" to false,
        "latestSeq" to 1L,
    )

    private fun sale(): Map<String, Any?> {
        val contentHash = saleContentHash()
        return linkedMapOf(
        "version" to 1L,
        "saleId" to SALE_ID,
        "businessId" to BUSINESS_ID,
        "status" to "POSTED",
        "currency" to "PEN",
        "subtotalMinorUnits" to 200L,
        "discountMinorUnits" to 10L,
        "taxMinorUnits" to 18L,
        "totalMinorUnits" to 208L,
        "contentHash" to contentHash,
        "checkoutIdempotencyKey" to "sale-checkout:v1:$SALE_ID:1:$contentHash",
        "createdAt" to 1_000L,
        "updatedAt" to 1_100L,
        "postedAt" to 1_200L,
        "lines" to listOf(
            linkedMapOf(
                "saleLineId" to SALE_LINE_ID,
                "position" to 0L,
                "productId" to PRODUCT_ID,
                "unitId" to UNIT_ID,
                "locationId" to LOCATION_ID,
                "productName" to "Producto",
                "unitCode" to "UND",
                "locationName" to "Almacén principal",
                "barcode" to null,
                "quantity" to "2",
                "unitPriceMinorUnits" to 100L,
                "discountMinorUnits" to 10L,
                "taxMinorUnits" to 18L,
                "lineTotalMinorUnits" to 208L,
            ),
        ),
        "receiptId" to RECEIPT,
        "seq" to 1L,
        "movements" to listOf(
            linkedMapOf(
                "movementId" to SaleContentIdentity.uuid(
                    "sale-stock-movement",
                    SALE_ID,
                    SALE_LINE_ID,
                ).toString(),
                "saleId" to SALE_ID,
                "saleLineId" to SALE_LINE_ID,
                "productId" to PRODUCT_ID,
                "sourceLocationId" to LOCATION_ID,
                "locationName" to "Almacén principal",
                "canonicalLocationName" to "almacén principal",
                "type" to "SALE",
                "quantityDelta" to "-2",
                "unitCost" to "50",
                "currency" to "PEN",
                "occurredAt" to 1_200L,
            ),
        ),
    )
    }

    private fun mapPullPage(raw: Map<*, *>) = FirebaseSaleWireMapper.pullPage(
        raw = raw,
        expectedBusinessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
        expectedPreviousSeq = 0L,
    )

    private fun saleContentHash(): String = SaleContentIdentity.hash(
        currencyCode = "PEN",
        lines = listOf(
            SaleLineEntity(
                saleLineId = SALE_LINE_ID,
                saleId = SALE_ID,
                productId = PRODUCT_ID,
                unitId = UNIT_ID,
                locationId = LOCATION_ID,
                position = 0,
                productNameSnapshot = "Producto",
                unitCodeSnapshot = "UND",
                locationNameSnapshot = "Almacén principal",
                barcodeSnapshot = null,
                quantity = "2",
                unitPriceMinorUnits = 100L,
                discountMinorUnits = 10L,
                taxMinorUnits = 18L,
                lineTotalMinorUnits = 208L,
                currencyCode = "PEN",
            ),
        ),
    )

    private fun balance(): Map<String, Any?> = linkedMapOf(
        "productId" to PRODUCT_ID,
        "locationName" to "Almacén principal",
        "quantityOnHand" to "8.000",
        "averageUnitCost" to "50.00",
        "currency" to "PEN",
        "version" to 2L,
        "updatedAtMillis" to 1_200L,
        "seq" to 1L,
    )

    private companion object {
        const val BUSINESS_ID = "00000000-0000-0000-0000-000000000001"
        const val OTHER_BUSINESS_ID = "00000000-0000-0000-0000-000000000010"
        const val SALE_ID = "00000000-0000-0000-0000-000000000002"
        const val SALE_LINE_ID = "00000000-0000-0000-0000-000000000003"
        const val PRODUCT_ID = "00000000-0000-0000-0000-000000000004"
        const val UNIT_ID = "00000000-0000-0000-0000-000000000005"
        const val LOCATION_ID = "00000000-0000-0000-0000-000000000006"
        const val PAYMENT_ID = "00000000-0000-0000-0000-000000000007"
        const val OTHER_DEBT_ID = "00000000-0000-0000-0000-000000000008"
        const val RECEIPT = "sale_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
