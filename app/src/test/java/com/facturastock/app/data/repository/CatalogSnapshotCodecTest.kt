package com.facturastock.app.data.repository

import com.facturastock.app.domain.model.CatalogStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSnapshotCodecTest {

    @Test
    fun `purchase factor accepts exact canonical bounds without losing trailing zeros`() {
        listOf(
            "1",
            "12.00",
            "0.000000000000000001",
            "99999999999999999999.999999999999999999",
        ).forEach { factor ->
            val payload = CatalogSnapshotCodec.encode(productSnapshot(factor))

            assertEquals(factor, CatalogSnapshotCodec.decodeProduct(payload)?.purchaseFactor)
        }
    }

    @Test
    fun `purchase factor fails closed outside cloud decimal policy`() {
        listOf(
            "0",
            "0.0",
            "01",
            "01.5",
            "1e1",
            "-1",
            "999999999999999999999999999999999999999",
            "0.0000000000000000001",
        ).forEach { factor ->
            val payload = CatalogSnapshotCodec.encode(productSnapshot(factor))

            assertNull("factor inesperadamente aceptado: $factor", CatalogSnapshotCodec.decodeProduct(payload))
        }
    }

    @Test
    fun `product v2 round trip carries an exact optional sale price pair`() {
        val snapshot = productSnapshot("12").copy(
            salePriceMinorUnits = 1_234L,
            salePriceCurrencyCode = "PEN",
        )

        val payload = CatalogSnapshotCodec.encode(snapshot)
        val decoded = requireNotNull(CatalogSnapshotCodec.decodeProduct(payload))

        assertTrue(decoded.includesSalePrice)
        assertEquals(1_234L, decoded.salePriceMinorUnits)
        assertEquals("PEN", decoded.salePriceCurrencyCode)
    }

    @Test
    fun `legacy product snapshot remains readable and explicitly lacks sale price`() {
        val current = CatalogSnapshotCodec.encode(productSnapshot("12"))
        val legacy = current.replace(
            ",\"salePriceMinorUnits\":null,\"salePriceCurrencyCode\":null",
            "",
        )

        val decoded = requireNotNull(CatalogSnapshotCodec.decodeProduct(legacy))

        assertFalse(decoded.includesSalePrice)
        assertNull(decoded.salePriceMinorUnits)
        assertNull(decoded.salePriceCurrencyCode)
    }

    @Test
    fun `product price decoder fails closed on half pair unsafe integer or unknown currency`() {
        val valid = CatalogSnapshotCodec.encode(
            productSnapshot("12").copy(
                salePriceMinorUnits = 600L,
                salePriceCurrencyCode = "PEN",
            ),
        )
        val invalidPayloads = listOf(
            valid.replace("\"salePriceCurrencyCode\":\"PEN\"", "\"salePriceCurrencyCode\":null"),
            valid.replace("\"salePriceMinorUnits\":600", "\"salePriceMinorUnits\":9007199254740992"),
            valid.replace("\"salePriceCurrencyCode\":\"PEN\"", "\"salePriceCurrencyCode\":\"ZZZ\""),
        )

        invalidPayloads.forEach { payload -> assertNull(CatalogSnapshotCodec.decodeProduct(payload)) }
    }

    private fun productSnapshot(factor: String) = CatalogProductSnapshot(
        name = "Producto",
        sku = "SKU-1",
        barcode = null,
        inventoryUnit = CatalogUnitSnapshot("NIU", "Unidad", "un", ACTIVE),
        purchaseUnit = CatalogUnitSnapshot("CJA", "Caja", "cja", ACTIVE),
        purchaseFactor = factor,
        location = null,
        status = ACTIVE,
        createdAt = 1_000,
        updatedAt = 2_000,
    )

    private companion object {
        val ACTIVE = CatalogStatus.ACTIVE.name
    }
}
