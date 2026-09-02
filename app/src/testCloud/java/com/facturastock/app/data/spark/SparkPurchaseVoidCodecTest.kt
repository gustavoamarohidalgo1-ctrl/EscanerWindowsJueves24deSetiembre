package com.facturastock.app.data.spark

import com.facturastock.app.data.sync.FirebasePurchaseVoidDocumentMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SparkPurchaseVoidCodecTest {
    @Test
    fun `remote remap inversion is sorted and hashes exactly like Blaze`() {
        val localPayload = SparkFirestoreSchema.jsonStringify(
            linkedMapOf(
                "version" to 1L,
                "purchaseId" to PURCHASE_ID,
                "impactHash" to "a".repeat(64),
                "actorId" to ACTOR_ID,
                "role" to "OWNER",
                "reason" to "Anulación válida con orden remoto invertido",
                "negativeStockPolicy" to "ALLOW_WITH_VISIBLE_WARNING",
                "averageUnitCostPolicy" to "PRESERVE_CURRENT",
                "negativeImpactCount" to 1L,
                "impacts" to listOf(
                    impact(
                        productId = LOCAL_PRODUCT_ONE,
                        locationId = LOCATION_ONE,
                        current = "5",
                        reversal = "-1",
                        resulting = "4",
                        average = "2.5",
                        negative = false,
                    ),
                    impact(
                        productId = LOCAL_PRODUCT_TWO,
                        locationId = LOCATION_TWO,
                        current = "1",
                        reversal = "-2",
                        resulting = "-1",
                        average = "3",
                        negative = true,
                    ),
                ),
            ),
        )
        val remapped = FirebasePurchaseVoidDocumentMapper.remap(
            payload = localPayload,
            remoteProductIds = mapOf(
                LOCAL_PRODUCT_ONE to REMOTE_PRODUCT_HIGH,
                LOCAL_PRODUCT_TWO to REMOTE_PRODUCT_LOW,
            ),
        )

        val prepared = SparkPurchaseVoidCodec.prepare(
            payloadText = remapped,
            expectedPurchaseId = PURCHASE_ID,
            idempotencyKey = "sync-purchase-void:v1:$PURCHASE_ID",
        )

        assertEquals(REMOTE_PRODUCT_LOW, prepared.impacts.first().productId)
        assertEquals(REMOTE_PRODUCT_HIGH, prepared.impacts.last().productId)
        assertEquals(EXPECTED_CANONICAL_PAYLOAD, prepared.canonicalPayloadText)
        assertEquals(
            "fd7cf18b07b6076bebc7f5e5b2e6f2ccf467c592a2d65f1bac07c824f74db024",
            prepared.payloadHash,
        )
    }

    @Test
    fun `void requires the exact opposite of every grouped purchase movement`() {
        val prepared = preparedVoid(
            impact(
                productId = REMOTE_PRODUCT_LOW,
                locationId = LOCATION_TWO,
                current = "5",
                reversal = "-3.50",
                resulting = "1.50",
                average = "3",
                negative = false,
            ),
        )
        val purchase = purchase(
            movement(REMOTE_PRODUCT_LOW, LOCATION_TWO, "1.25"),
            movement(REMOTE_PRODUCT_LOW, LOCATION_TWO, "2.250"),
        )

        SparkPurchaseVoidCodec.validatePurchaseSemantics(purchase, prepared)
    }

    @Test
    fun `void rejects omitted extra or arbitrary reversal before writes`() {
        val purchase = purchase(
            movement(REMOTE_PRODUCT_LOW, LOCATION_TWO, "2"),
            movement(REMOTE_PRODUCT_HIGH, LOCATION_ONE, "1"),
        )
        val omitted = preparedVoid(
            impact(
                productId = REMOTE_PRODUCT_LOW,
                locationId = LOCATION_TWO,
                current = "5",
                reversal = "-2",
                resulting = "3",
                average = "3",
                negative = false,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SparkPurchaseVoidCodec.validatePurchaseSemantics(purchase, omitted)
        }

        val arbitrary = preparedVoid(
            impact(
                productId = REMOTE_PRODUCT_LOW,
                locationId = LOCATION_TWO,
                current = "5",
                reversal = "-2.5",
                resulting = "2.5",
                average = "3",
                negative = false,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SparkPurchaseVoidCodec.validatePurchaseSemantics(
                purchase(movement(REMOTE_PRODUCT_LOW, LOCATION_TWO, "2")),
                arbitrary,
            )
        }
    }

    @Test
    fun `credit note requires negative originals and a positive reversal`() {
        val prepared = preparedVoid(
            impact(
                productId = REMOTE_PRODUCT_LOW,
                locationId = LOCATION_TWO,
                current = "1",
                reversal = "2",
                resulting = "3",
                average = "3",
                negative = false,
            ),
        )
        SparkPurchaseVoidCodec.validatePurchaseSemantics(
            purchase(
                movement(REMOTE_PRODUCT_LOW, LOCATION_TWO, "-2"),
                documentType = "CREDIT_NOTE",
            ),
            prepared,
        )
        assertThrows(SparkMutationFailure.CorruptRemoteData::class.java) {
            SparkPurchaseVoidCodec.validatePurchaseSemantics(
                purchase(
                    movement(REMOTE_PRODUCT_LOW, LOCATION_TWO, "2"),
                    documentType = "CREDIT_NOTE",
                ),
                prepared,
            )
        }
    }

    private fun preparedVoid(vararg impacts: Map<String, Any?>): SparkPreparedVoid {
        val negativeCount = impacts.count { it["negative"] == true }.toLong()
        return SparkPurchaseVoidCodec.prepare(
            payloadText = SparkFirestoreSchema.jsonStringify(
                linkedMapOf(
                    "version" to 1L,
                    "purchaseId" to PURCHASE_ID,
                    "impactHash" to "a".repeat(64),
                    "actorId" to ACTOR_ID,
                    "role" to "OWNER",
                    "reason" to "Anulación semántica válida",
                    "negativeStockPolicy" to "ALLOW_WITH_VISIBLE_WARNING",
                    "averageUnitCostPolicy" to "PRESERVE_CURRENT",
                    "negativeImpactCount" to negativeCount,
                    "impacts" to impacts.toList(),
                ),
            ),
            expectedPurchaseId = PURCHASE_ID,
            idempotencyKey = "sync-purchase-void:v1:$PURCHASE_ID",
        )
    }

    private fun purchase(
        vararg movements: Map<String, Any?>,
        documentType: String = "INVOICE",
    ): Map<String, Any?> = mapOf(
        "documentType" to documentType,
        "movements" to movements.toList(),
    )

    private fun movement(
        productId: String,
        locationId: String,
        quantity: String,
    ): Map<String, Any?> = mapOf(
        "type" to "PURCHASE",
        "productId" to productId,
        "locationId" to locationId,
        "quantityDelta" to quantity,
    )

    private fun impact(
        productId: String,
        locationId: String,
        current: String,
        reversal: String,
        resulting: String,
        average: String,
        negative: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "productId" to productId,
        "locationId" to locationId,
        "currentQuantity" to current,
        "reversalQuantity" to reversal,
        "resultingQuantity" to resulting,
        "currentAverageUnitCost" to average,
        "currency" to "PEN",
        "balanceVersion" to 0L,
        "negative" to negative,
    )

    private companion object {
        const val PURCHASE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val ACTOR_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val LOCAL_PRODUCT_ONE = "11111111-1111-4111-8111-111111111111"
        const val LOCAL_PRODUCT_TWO = "22222222-2222-4222-8222-222222222222"
        const val REMOTE_PRODUCT_HIGH = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        const val REMOTE_PRODUCT_LOW = "00000000-0000-4000-8000-000000000001"
        const val LOCATION_ONE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val LOCATION_TWO = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val EXPECTED_CANONICAL_PAYLOAD =
            "{\"version\":1,\"purchaseId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"," +
                "\"impactHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"," +
                "\"actorId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\",\"role\":\"OWNER\"," +
                "\"reason\":\"Anulación válida con orden remoto invertido\"," +
                "\"negativeStockPolicy\":\"ALLOW_WITH_VISIBLE_WARNING\"," +
                "\"averageUnitCostPolicy\":\"PRESERVE_CURRENT\",\"negativeImpactCount\":1," +
                "\"impacts\":[{\"productId\":\"00000000-0000-4000-8000-000000000001\"," +
                "\"locationId\":\"dddddddd-dddd-4ddd-8ddd-dddddddddddd\"," +
                "\"currentQuantity\":\"1\",\"reversalQuantity\":\"-2\"," +
                "\"resultingQuantity\":\"-1\",\"currentAverageUnitCost\":\"3\"," +
                "\"currency\":\"PEN\",\"balanceVersion\":0,\"negative\":true}," +
                "{\"productId\":\"ffffffff-ffff-4fff-8fff-ffffffffffff\"," +
                "\"locationId\":\"cccccccc-cccc-4ccc-8ccc-cccccccccccc\"," +
                "\"currentQuantity\":\"5\",\"reversalQuantity\":\"-1\"," +
                "\"resultingQuantity\":\"4\",\"currentAverageUnitCost\":\"2.5\"," +
                "\"currency\":\"PEN\",\"balanceVersion\":0,\"negative\":false}]}"
    }
}
