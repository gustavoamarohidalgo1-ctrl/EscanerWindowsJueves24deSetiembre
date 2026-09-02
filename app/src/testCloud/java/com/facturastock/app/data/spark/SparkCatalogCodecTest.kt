package com.facturastock.app.data.spark

import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.RemoteCatalogEntityType
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkCatalogCodecTest {
    @Test
    fun `mutation reutiliza snapshot Functions y genera ids deterministas`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)

        assertEquals(PRODUCT, mutation.entityType)
        assertEquals(PRODUCTS, mutation.collection)
        assertEquals(0L, mutation.expectedVersion)
        assertEquals(1L, mutation.targetVersion)
        assertEquals(PRODUCT_SNAPSHOT, mutation.snapshotPayload)
        assertEquals(600L, mutation.snapshot["salePriceMinorUnits"])

        val receipt = SparkCatalogCodec.receiptId(IDEMPOTENCY_KEY)
        assertTrue(Regex("^rcpt_[0-9a-f]{32}$").matches(receipt))
        assertEquals(receipt, SparkCatalogCodec.receiptId(IDEMPOTENCY_KEY))
        assertEquals(
            SparkCatalogCodec.operationDocumentId(IDEMPOTENCY_KEY),
            SparkCatalogCodec.operationDocumentId(IDEMPOTENCY_KEY),
        )
        assertNotEquals(
            SparkCatalogCodec.operationDocumentId(IDEMPOTENCY_KEY),
            SparkCatalogCodec.operationDocumentId("$IDEMPOTENCY_KEY-other"),
        )
    }

    @Test
    fun `supplier conserva coleccion tipo y snapshot canonico`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_SUPPLIER, SUPPLIER_DOCUMENT)

        assertEquals(SUPPLIER, mutation.entityType)
        assertEquals(SUPPLIERS, mutation.collection)
        assertEquals(SUPPLIER_SNAPSHOT, mutation.snapshotPayload)
        assertEquals("20123456789", mutation.snapshot["ruc"])
    }

    @Test
    fun `pull directo conserva JSON exacto y pasa codec cerrado existente`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)
        val receiptId = SparkCatalogCodec.receiptId(IDEMPOTENCY_KEY)
        val data = changeData(mutation, receiptId)

        val document = SparkCatalogCodec.decodeChange(receiptId, data, OWNER_UID)
        val page = SparkCatalogCodec.pullPage(listOf(document), sinceSeq = 0L, hasMore = false)

        assertEquals(1L, page.nextCursor)
        assertEquals(RemoteCatalogEntityType.PRODUCT, page.changes.single().entityType)
        assertEquals(PRODUCT_SNAPSHOT, page.changes.single().snapshotPayload)
    }

    @Test
    fun `pull rechaza owner ajeno snapshot divergente y campos extra`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)
        val receiptId = SparkCatalogCodec.receiptId(IDEMPOTENCY_KEY)
        val valid = changeData(mutation, receiptId)

        assertThrows(AccountException::class.java) {
            SparkCatalogCodec.decodeChange(receiptId, valid, "otro-uid")
        }
        assertThrows(AccountException::class.java) {
            SparkCatalogCodec.decodeChange(
                receiptId,
                valid + ("snapshotPayload" to PRODUCT_SNAPSHOT.replace("Arroz", "Azúcar")),
                OWNER_UID,
            )
        }
        assertThrows(AccountException::class.java) {
            SparkCatalogCodec.decodeChange(receiptId, valid + ("unexpected" to true), OWNER_UID)
        }
    }

    @Test
    fun `request hash liga tenant version y contenido`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)
        val first = SparkCatalogCodec.requestHash(
            BUSINESS_ID,
            IDEMPOTENCY_KEY,
            payloadVersion = 2,
            mutation,
        )
        val replay = SparkCatalogCodec.requestHash(
            BUSINESS_ID,
            IDEMPOTENCY_KEY,
            payloadVersion = 2,
            mutation,
        )

        assertEquals(first, replay)
        // Hash producido por JSON.stringify(canonicalRequest) en functions/catalogSync.js.
        assertEquals("2de73728a8551e7d66658ca87fa2eb9e4fb8f4ea7815a65293f8a79dae71d94c", first)
        assertEquals(
            "13da0d7c83f04a1337a370aafe23de314f2a19341085a0eb7b35b2a75524274c",
            SparkCatalogCodec.operationDocumentId(IDEMPOTENCY_KEY),
        )
        assertNotEquals(
            first,
            SparkCatalogCodec.requestHash(
                OTHER_BUSINESS_ID,
                IDEMPOTENCY_KEY,
                payloadVersion = 2,
                mutation,
            ),
        )
    }

    @Test
    fun `indices semanticos usan paths y documentos compatibles con Functions`() {
        val product = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)
        val supplier = SparkCatalogCodec.parseMutation(SYNC_SUPPLIER, SUPPLIER_DOCUMENT)

        val productKeys = SparkCatalogCodec.semanticKeys(PRODUCT, product.snapshot)
        val supplierKeys = SparkCatalogCodec.semanticKeys(SUPPLIER, supplier.snapshot)

        assertEquals(1, productKeys.size)
        assertEquals("productSkuIndex", productKeys.single().collection)
        assertEquals("SKU-1", productKeys.single().value)
        assertEquals(
            "75bbb0f60c30207dda479ca30a7444f3b2f4a27a14157797ccc6635e6ed5f827",
            productKeys.single().documentId,
        )
        assertEquals("supplierRucIndex", supplierKeys.single().collection)
        assertEquals("20123456789", supplierKeys.single().value)
        assertEquals(
            "f58588409c59f52938cf5bd3ab930917c6eb67f05ddbd190bea4f6e386a66eb1",
            supplierKeys.single().documentId,
        )
    }

    @Test
    fun `plan semantico cambia y elimina indices viejos atomicamente`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)
        val oldKeys = SparkCatalogCodec.semanticKeys(PRODUCT, mutation.snapshot)
        val updatedSnapshot = LinkedHashMap(mutation.snapshot).apply {
            this["sku"] = "SKU-2"
            this["barcode"] = "7751234567890"
        }
        val newKeys = SparkCatalogCodec.semanticKeys(PRODUCT, updatedSnapshot)
        val owners = (oldKeys + newKeys).associateWith { key ->
            if (key in oldKeys) SparkCatalogSemanticIndexOwner(ENTITY_ID, 1L) else null
        }

        val plan = SparkCatalogCodec.semanticIndexPlan(
            entityId = ENTITY_ID,
            currentVersion = 1L,
            oldKeys = oldKeys,
            newKeys = newKeys,
            owners = owners,
        ) as SparkCatalogSemanticIndexPlan.Apply

        assertEquals(newKeys, plan.upserts)
        assertEquals(oldKeys, plan.removals)
        assertEquals(setOf("productSkuIndex", "productBarcodeIndex"),
            plan.upserts.mapTo(linkedSetOf()) { it.collection })
    }

    @Test
    fun `plan semantico detecta colision cross-device y nunca roba el indice`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)
        val newKeys = SparkCatalogCodec.semanticKeys(PRODUCT, mutation.snapshot)
        val owners = newKeys.associateWith {
            SparkCatalogSemanticIndexOwner(OTHER_ENTITY_ID, 3L)
        }

        val plan = SparkCatalogCodec.semanticIndexPlan(
            entityId = ENTITY_ID,
            currentVersion = 0L,
            oldKeys = emptySet(),
            newKeys = newKeys,
            owners = owners,
        ) as SparkCatalogSemanticIndexPlan.Conflict

        assertEquals(OTHER_ENTITY_ID, plan.ownerEntityId)
        assertEquals(newKeys.single(), plan.key)
    }

    @Test
    fun `plan semantico rechaza un indice propio con version divergente`() {
        val mutation = SparkCatalogCodec.parseMutation(SYNC_PRODUCT, PRODUCT_DOCUMENT)
        val keys = SparkCatalogCodec.semanticKeys(PRODUCT, mutation.snapshot)

        assertThrows(IllegalArgumentException::class.java) {
            SparkCatalogCodec.semanticIndexPlan(
                entityId = ENTITY_ID,
                currentVersion = 2L,
                oldKeys = keys,
                newKeys = keys,
                owners = keys.associateWith {
                    SparkCatalogSemanticIndexOwner(ENTITY_ID, 1L)
                },
            )
        }
    }

    private fun changeData(
        mutation: SparkCatalogMutation,
        receiptId: String,
    ): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 1L,
        "seq" to 1L,
        "entityType" to mutation.entityType,
        "entityId" to mutation.entityId,
        "version" to mutation.targetVersion,
        "mutation" to mutation.mutation,
        "snapshot" to mutation.snapshot,
        "snapshotPayload" to mutation.snapshotPayload,
        "snapshotSha256" to mutation.snapshotSha256,
        "receiptId" to receiptId,
        "syncedAt" to Timestamp(1_700_000_000L, 0),
        "ownerUid" to OWNER_UID,
    )

    private companion object {
        const val OWNER_UID = "owner-uid"
        const val BUSINESS_ID = "22222222-2222-4222-8222-222222222222"
        const val OTHER_BUSINESS_ID = "33333333-3333-4333-8333-333333333333"
        const val ENTITY_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_ENTITY_ID = "44444444-4444-4444-8444-444444444444"
        const val SYNC_PRODUCT = "SYNC_PRODUCT"
        const val SYNC_SUPPLIER = "SYNC_SUPPLIER"
        const val PRODUCT = "PRODUCT"
        const val SUPPLIER = "SUPPLIER"
        const val PRODUCTS = "products"
        const val SUPPLIERS = "suppliers"
        const val IDEMPOTENCY_KEY = "sync-product:v2:$ENTITY_ID:1"
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
        const val SUPPLIER_SNAPSHOT = "{\"legalName\":\"Distribuidora SAC\"," +
            "\"ruc\":\"20123456789\",\"tradeName\":\"Distribuidora\"," +
            "\"status\":\"ACTIVE\",\"createdAt\":1000,\"updatedAt\":1000}"
        const val SUPPLIER_DOCUMENT = "{\"version\":1,\"entityId\":\"$ENTITY_ID\"," +
            "\"expectedVersion\":0,\"targetVersion\":1,\"mutation\":\"UPSERT\"," +
            "\"snapshot\":$SUPPLIER_SNAPSHOT}"
    }
}
