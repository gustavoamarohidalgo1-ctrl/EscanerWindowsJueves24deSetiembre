package com.facturastock.app.data.spark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkPurchaseDuplicateOverrideTest {
    @Test
    fun `identity and override slot hashes stay byte compatible with Blaze`() {
        val document = identityDocument()
        val identityId = SparkPurchaseDuplicateOverrideCodec.identityDocumentId(document)

        assertEquals(
            "41e06a91e59a16f1cac9f8e4b172cdbefa23fcd5513e8e1eb63e742ed04e5849",
            identityId,
        )
        assertEquals(
            "1da38312a9d3cdeaa1160237f59c02fe6689252934253ea96c53e93b82d1b77f",
            SparkPurchaseDuplicateOverrideCodec.slotDocumentId(identityId, SOURCE_DRAFT_ID),
        )
    }

    @Test
    fun `reason is validated but excluded from persisted and replay hashes`() {
        val first = parseOverride("Recepción duplicada autorizada inicialmente")
        val replay = parseOverride("Otro motivo válido conserva exactamente el mismo replay")

        assertEquals(first, replay)
        assertEquals(
            SparkPurchaseDuplicateOverrideCodec.hashMap(first),
            SparkPurchaseDuplicateOverrideCodec.hashMap(replay),
        )
        assertEquals(
            mapOf(
                "existingPurchaseId" to TARGET_PURCHASE_ID,
                "sourceDraftId" to SOURCE_DRAFT_ID,
                "auditEventId" to OVERRIDE_AUDIT_ID,
                "authorizedRole" to "OWNER",
            ),
            SparkPurchaseDuplicateOverrideCodec.persistedMap(first),
        )
        assertFalse(
            SparkPurchaseDuplicateOverrideCodec.persistedMap(first).toString().contains("motivo"),
        )
    }

    @Test
    fun `override parser rejects self target and audit mismatch`() {
        assertThrows(IllegalArgumentException::class.java) {
            SparkPurchaseDuplicateOverrideCodec.parse(
                raw = overrideMap(
                    reason = "Motivo con longitud válida para la excepción",
                    existingPurchaseId = NEW_PURCHASE_ID,
                ),
                purchaseId = NEW_PURCHASE_ID,
                auditEventIds = listOf(OVERRIDE_AUDIT_ID, POSTED_AUDIT_ID),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SparkPurchaseDuplicateOverrideCodec.parse(
                raw = overrideMap("Motivo con longitud válida para la excepción"),
                purchaseId = NEW_PURCHASE_ID,
                auditEventIds = listOf(POSTED_AUDIT_ID),
            )
        }
    }

    @Test
    fun `target and primary index must represent the same terminal purchase`() {
        val identityId = SparkPurchaseDuplicateOverrideCodec.identityDocumentId(identityDocument())
        val target = identityDocument() + mapOf(
            "businessId" to BUSINESS_ID,
            "status" to "VOIDED",
            "receiptId" to RECEIPT_ID,
        )

        assertTrue(
            SparkPurchaseDuplicateOverrideCodec.targetMatches(
                target = target,
                businessId = BUSINESS_ID,
                identityDocumentId = identityId,
            ),
        )
        assertFalse(
            SparkPurchaseDuplicateOverrideCodec.targetMatches(
                target = target + ("documentNumber" to "78"),
                businessId = BUSINESS_ID,
                identityDocumentId = identityId,
            ),
        )
        assertTrue(
            SparkPurchaseDuplicateOverrideCodec.primaryIndexMatches(
                index = mapOf(
                    "slot" to "PRIMARY",
                    "purchaseId" to TARGET_PURCHASE_ID,
                    "receiptId" to RECEIPT_ID,
                ),
                targetPurchaseId = TARGET_PURCHASE_ID,
                targetReceiptId = RECEIPT_ID,
            ),
        )
    }

    @Test
    fun `purchase catalog preflight requires a synced active product`() {
        assertTrue(
            SparkPurchaseCatalogPreflight.isActiveProduct(
                mapOf(
                    "entityType" to "PRODUCT",
                    "snapshot" to mapOf("status" to "ACTIVE"),
                ),
            ),
        )
        assertFalse(SparkPurchaseCatalogPreflight.isActiveProduct(null))
        assertFalse(
            SparkPurchaseCatalogPreflight.isActiveProduct(
                mapOf(
                    "entityType" to "PRODUCT",
                    "snapshot" to mapOf("status" to "INACTIVE"),
                ),
            ),
        )
        assertFalse(
            SparkPurchaseCatalogPreflight.isActiveProduct(
                mapOf(
                    "entityType" to "SUPPLIER",
                    "snapshot" to mapOf("status" to "ACTIVE"),
                ),
            ),
        )
    }

    private fun parseOverride(reason: String): SparkPurchaseDuplicateOverride = requireNotNull(
        SparkPurchaseDuplicateOverrideCodec.parse(
            raw = overrideMap(reason),
            purchaseId = NEW_PURCHASE_ID,
            auditEventIds = listOf(OVERRIDE_AUDIT_ID, POSTED_AUDIT_ID),
        ),
    )

    private fun overrideMap(
        reason: String,
        existingPurchaseId: String = TARGET_PURCHASE_ID,
    ): Map<String, Any?> = linkedMapOf(
        "existingPurchaseId" to existingPurchaseId,
        "sourceDraftId" to SOURCE_DRAFT_ID,
        "auditEventId" to OVERRIDE_AUDIT_ID,
        "reason" to reason,
    )

    private fun identityDocument(): Map<String, Any?> = linkedMapOf(
        "supplierRuc" to "20123456789",
        "documentType" to "INVOICE",
        "documentSeries" to "F001",
        "documentNumber" to "77",
    )

    private companion object {
        const val BUSINESS_ID = "11111111-1111-4111-8111-111111111111"
        const val NEW_PURCHASE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val TARGET_PURCHASE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SOURCE_DRAFT_ID = "22222222-2222-4222-8222-222222222222"
        const val OVERRIDE_AUDIT_ID = "33333333-3333-4333-8333-333333333333"
        const val POSTED_AUDIT_ID = "44444444-4444-4444-8444-444444444444"
        const val RECEIPT_ID = "rcpt_55555555555555555555555555555555"
    }
}
