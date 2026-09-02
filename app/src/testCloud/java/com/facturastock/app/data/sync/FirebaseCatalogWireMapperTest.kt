package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.RemoteCatalogEntityType
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseCatalogWireMapperTest {
    @Test
    fun `push conserva snapshot semantico y nunca agrega businessId ni UUIDs locales`() {
        val document = FirebaseCatalogWireMapper.pushDocument("SYNC_PRODUCT", PRODUCT_DOCUMENT)

        assertEquals(DOCUMENT_KEYS, document.keys)
        assertEquals(ENTITY_ID, document["entityId"])
        val snapshot = document["snapshot"] as Map<*, *>
        assertEquals(600L, snapshot["salePriceMinorUnits"])
        assertEquals("PEN", snapshot["salePriceCurrencyCode"])
        assertEquals(setOf("code", "name", "symbol", "status"),
            (snapshot["inventoryUnit"] as Map<*, *>).keys)
        assertFalse(snapshot.containsKey("businessId"))
        assertFalse(snapshot.containsKey("unitId"))
        assertFalse(snapshot.containsKey("locationId"))
    }

    @Test
    fun `push rechaza campos extra y forma no canonica`() {
        assertThrows(IllegalArgumentException::class.java) {
            FirebaseCatalogWireMapper.pushDocument(
                "SYNC_PRODUCT",
                PRODUCT_DOCUMENT.dropLast(1) + ",\"businessId\":\"local\"}",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirebaseCatalogWireMapper.pushDocument(
                "SYNC_PRODUCT",
                PRODUCT_DOCUMENT.replace("\"code\":\"UND\"", "\"code\":\"und\""),
            )
        }
    }

    @Test
    fun `push conserva compatibilidad product v1 pero no mezcla version y forma de snapshot`() {
        val legacyDocument = PRODUCT_DOCUMENT
            .replace("\"version\":2", "\"version\":1")
            .replace(",\"salePriceMinorUnits\":600,\"salePriceCurrencyCode\":\"PEN\"", "")

        val mapped = FirebaseCatalogWireMapper.pushDocument("SYNC_PRODUCT", legacyDocument)

        assertEquals(1L, mapped["version"])
        assertThrows(IllegalArgumentException::class.java) {
            FirebaseCatalogWireMapper.pushDocument(
                "SYNC_PRODUCT",
                PRODUCT_DOCUMENT.replace("\"version\":2", "\"version\":1"),
            )
        }
    }

    @Test
    fun `pull valida respuesta cerrada integridad y tipos`() {
        val page = FirebaseCatalogWireMapper.pullPage(page(snapshot = PRODUCT_SNAPSHOT))

        assertEquals(1L, page.nextCursor)
        assertEquals(RemoteCatalogEntityType.PRODUCT, page.changes.single().entityType)
        assertEquals(PRODUCT_SNAPSHOT, page.changes.single().snapshotPayload)
    }

    @Test
    fun `pull rechaza checksum o campo inesperado`() {
        assertThrows(AccountException::class.java) {
            FirebaseCatalogWireMapper.pullPage(page(snapshot = PRODUCT_SNAPSHOT, hash = "0".repeat(64)))
        }
        assertThrows(AccountException::class.java) {
            FirebaseCatalogWireMapper.pullPage(page(snapshot = PRODUCT_SNAPSHOT) + ("uid" to "secret"))
        }
    }

    @Test
    fun `pull rechaza secuencia regresiva o cursor que no corresponde`() {
        val first = change(2L, PRODUCT_SNAPSHOT)
        val second = change(1L, PRODUCT_SNAPSHOT).toMutableMap().apply {
            this["receiptId"] = "cat_second"
        }
        assertThrows(AccountException::class.java) {
            FirebaseCatalogWireMapper.pullPage(
                mapOf("changes" to listOf(first, second), "nextCursor" to 1L, "hasMore" to false),
            )
        }
        assertThrows(AccountException::class.java) {
            FirebaseCatalogWireMapper.pullPage(
                mapOf("changes" to listOf(change(1L, PRODUCT_SNAPSHOT)),
                    "nextCursor" to 2L, "hasMore" to false),
            )
        }
    }

    private fun page(snapshot: String, hash: String = sha256(snapshot)): Map<String, Any?> = mapOf(
        "changes" to listOf(change(1L, snapshot, hash)),
        "nextCursor" to 1L,
        "hasMore" to false,
    )

    private fun change(seq: Long, snapshot: String, hash: String = sha256(snapshot)) = linkedMapOf(
        "seq" to seq,
        "entityType" to "PRODUCT",
        "entityId" to ENTITY_ID,
        "remoteVersion" to 1L,
        "mutation" to "UPSERT",
        "snapshotPayload" to snapshot,
        "snapshotSha256" to hash,
        "receiptId" to "cat_receipt_$seq",
        "syncedAtMillis" to 1_700_000_000_000L,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ENTITY_ID = "11111111-1111-4111-8111-111111111111"
        const val PRODUCT_SNAPSHOT = "{\"name\":\"Arroz\",\"sku\":\"SKU-1\"," +
            "\"barcode\":null,\"salePriceMinorUnits\":600," +
            "\"salePriceCurrencyCode\":\"PEN\"," +
            "\"inventoryUnit\":{\"code\":\"UND\",\"name\":\"Unidad\"," +
            "\"symbol\":\"u\",\"status\":\"ACTIVE\"},\"purchaseUnit\":null," +
            "\"purchaseFactor\":null,\"location\":null,\"status\":\"ACTIVE\"," +
            "\"createdAt\":1000,\"updatedAt\":1000}"
        const val PRODUCT_DOCUMENT = "{\"version\":2,\"entityId\":\"$ENTITY_ID\"," +
            "\"expectedVersion\":0,\"targetVersion\":1,\"mutation\":\"UPSERT\"," +
            "\"snapshot\":$PRODUCT_SNAPSHOT}"
        val DOCUMENT_KEYS = setOf(
            "version", "entityId", "expectedVersion", "targetVersion", "mutation", "snapshot",
        )
    }
}
