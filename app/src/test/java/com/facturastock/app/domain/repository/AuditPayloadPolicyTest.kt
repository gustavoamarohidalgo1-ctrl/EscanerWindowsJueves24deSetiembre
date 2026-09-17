package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditPayloadPolicyTest {

    @Test
    fun `every durable audit type accepts only its closed schema`() {
        val validPayloads = validPayloads()
        assertEquals(AuditEventType.entries.toSet(), validPayloads.keys)
        validPayloads.forEach { (type, payload) ->
            AuditPayloadPolicy.requireValid(type, payload)
            val encoded = AuditPayloadPolicy.encode(type, payload)

            assertTrue(encoded.startsWith("{"))
            assertTrue(encoded.endsWith("}"))
            payload.forEach { (key, value) ->
                assertTrue(encoded.contains("\"$key\":\"$value\""))
            }
        }
    }

    @Test
    fun `free text throwable fields and unknown keys are rejected rather than redacted in place`() {
        val allowed = validPayloads().getValue(AuditEventType.PURCHASE_POSTED)
        listOf(
            "reason",
            "amount",
            "message",
            "cause",
            "stacktrace",
            "rawOcr",
            "supplierRuc",
            UNKNOWN_KEY_SENTINEL,
        ).forEach { unknownKey ->
            assertThrows(IllegalArgumentException::class.java) {
                AuditPayloadPolicy.requireValid(
                    AuditEventType.PURCHASE_POSTED,
                    allowed + (unknownKey to SENSITIVE_VALUE_SENTINEL),
                )
            }
        }
    }

    @Test
    fun `allowed keys reject emails paths amounts free text and noncanonical ids`() {
        val allowed = validPayloads().getValue(AuditEventType.PURCHASE_POSTED)
        listOf(
            AuditPayloadKey.PURCHASE_ID to "private.user@example.pe",
            AuditPayloadKey.DRAFT_ID to "content://invoice/private/page-1.jpg",
            AuditPayloadKey.ALIASES_CREATED_COUNT to "S/ 731.45",
            AuditPayloadKey.ALIASES_SKIPPED_COUNT to "-1",
            AuditPayloadKey.ADJUSTMENT_APPLIED to SENSITIVE_VALUE_SENTINEL,
        ).forEach { (key, sensitiveValue) ->
            assertThrows(IllegalArgumentException::class.java) {
                AuditPayloadPolicy.requireValid(
                    AuditEventType.PURCHASE_POSTED,
                    allowed + (key to sensitiveValue),
                )
            }
        }
    }

    @Test
    fun `closed enum and policy fields reject secret content and unknown duplicate reasons`() {
        val adversarialValues = listOf(
            Triple(
                AuditEventType.PURCHASE_VOIDED,
                AuditPayloadKey.ACTOR_ROLE,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.PURCHASE_VOIDED,
                AuditPayloadKey.NEGATIVE_STOCK_POLICY,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.PURCHASE_VOIDED,
                AuditPayloadKey.AVERAGE_UNIT_COST_POLICY,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.PURCHASE_DUPLICATE_OVERRIDE,
                AuditPayloadKey.DUPLICATE_KIND,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.PURCHASE_DUPLICATE_OVERRIDE,
                AuditPayloadKey.DUPLICATE_REASON_CODES,
                "SAME_BUSINESS,$SECRET_CONTENT",
            ),
            Triple(
                AuditEventType.PURCHASE_DUPLICATE_OVERRIDE,
                AuditPayloadKey.ACTOR_ROLE,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.STOCK_ADJUSTED,
                AuditPayloadKey.ADJUSTMENT_KIND,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.STOCK_ADJUSTED,
                AuditPayloadKey.ACTOR_ROLE,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.SYNC_CONFLICT_RESOLVED,
                AuditPayloadKey.RESOLUTION,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.SYNC_CONFLICT_RESOLVED,
                AuditPayloadKey.REMOTE_RECEIPT_ID,
                SECRET_CONTENT,
            ),
            Triple(
                AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED,
                AuditPayloadKey.RESOLUTION,
                SECRET_CONTENT,
            ),
        )

        adversarialValues.forEach { (eventType, key, value) ->
            assertThrows(IllegalArgumentException::class.java) {
                AuditPayloadPolicy.requireValid(
                    eventType,
                    validPayloads().getValue(eventType) + (key to value),
                )
            }
        }
    }

    @Test
    fun `encoded audit never contains sentinels when the outbox source data is private`() {
        val encoded = AuditPayloadPolicy.encode(
            AuditEventType.PURCHASE_POSTED,
            validPayloads().getValue(AuditEventType.PURCHASE_POSTED),
        )

        assertFalse(encoded.contains(UNKNOWN_KEY_SENTINEL))
        assertFalse(encoded.contains(SENSITIVE_VALUE_SENTINEL))
        assertFalse(encoded.contains("message"))
        assertFalse(encoded.contains("stacktrace"))
    }

    @Test
    fun `audit envelope requires canonical internal identities and matching purchase entity`() {
        val valid = AuditEventWrite(
            auditEventId = AUDIT_ID,
            businessId = BusinessId.from(UUID.fromString(BUSINESS_ID)),
            purchaseId = PurchaseId.from(UUID.fromString(PURCHASE_ID)),
            eventType = AuditEventType.PURCHASE_POSTED,
            entityType = "PURCHASE",
            entityId = PURCHASE_ID,
            payload = validPayloads().getValue(AuditEventType.PURCHASE_POSTED),
            occurredAt = Instant.EPOCH,
        )
        assertEquals(PURCHASE_ID, valid.entityId)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(auditEventId = "audit-$SENSITIVE_VALUE_SENTINEL")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(entityId = OTHER_PURCHASE_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(entityType = "invoice/$SENSITIVE_VALUE_SENTINEL")
        }
    }

    private fun validPayloads(): Map<AuditEventType, Map<String, String>> = mapOf(
        AuditEventType.PURCHASE_POSTED to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.PURCHASE_ID to PURCHASE_ID,
            AuditPayloadKey.DRAFT_ID to DRAFT_ID,
            AuditPayloadKey.ALIASES_CREATED_COUNT to "1",
            AuditPayloadKey.ALIASES_SKIPPED_COUNT to "0",
            AuditPayloadKey.ADJUSTMENT_APPLIED to "true",
        ),
        AuditEventType.SALE_POSTED to mapOf(
            AuditPayloadKey.VERSION to "1",
        ),
        AuditEventType.SALE_VOIDED to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.ACTOR_ROLE to "OWNER",
        ),
        AuditEventType.PURCHASE_VOIDED to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.PURCHASE_ID to PURCHASE_ID,
            AuditPayloadKey.ACTOR_ROLE to "OWNER",
            AuditPayloadKey.NEGATIVE_IMPACT_COUNT to "0",
            AuditPayloadKey.NEGATIVE_STOCK_POLICY to "ALLOW_WITH_VISIBLE_WARNING",
            AuditPayloadKey.AVERAGE_UNIT_COST_POLICY to "PRESERVE_CURRENT",
        ),
        AuditEventType.PURCHASE_DUPLICATE_OVERRIDE to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.PURCHASE_ID to PURCHASE_ID,
            AuditPayloadKey.DRAFT_ID to DRAFT_ID,
            AuditPayloadKey.EXISTING_PURCHASE_ID to OTHER_PURCHASE_ID,
            AuditPayloadKey.DUPLICATE_KIND to "EXACT",
            AuditPayloadKey.DUPLICATE_REASON_CODES to "SAME_BUSINESS,SAME_SUPPLIER",
            AuditPayloadKey.ACTOR_ROLE to "OWNER",
        ),
        AuditEventType.STOCK_ADJUSTED to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.PRODUCT_ID to PRODUCT_ID,
            AuditPayloadKey.LOCATION_ID to LOCATION_ID,
            AuditPayloadKey.ADJUSTMENT_KIND to "MANUAL",
            AuditPayloadKey.ACTOR_ROLE to "MANAGER",
        ),
        AuditEventType.SYNC_CONFLICT_RESOLVED to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.OPERATION_ID to OPERATION_ID,
            AuditPayloadKey.PURCHASE_ID to PURCHASE_ID,
            AuditPayloadKey.RESOLUTION to "KEEP_REMOTE",
            AuditPayloadKey.REMOTE_PURCHASE_ID to OTHER_PURCHASE_ID,
            AuditPayloadKey.REMOTE_RECEIPT_ID to "rcpt_0123456789abcdef0123456789abcdef",
        ),
        AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.OPERATION_ID to OPERATION_ID,
            AuditPayloadKey.ENTITY_TYPE to "PRODUCT",
            AuditPayloadKey.ENTITY_ID to PRODUCT_ID,
            AuditPayloadKey.REMOTE_ENTITY_ID to OTHER_PRODUCT_ID,
            AuditPayloadKey.LOCAL_VERSION to "2",
            AuditPayloadKey.REMOTE_VERSION to "1",
            AuditPayloadKey.RESOLUTION to "KEEP_LOCAL",
        ),
        AuditEventType.SYNC_RECONCILED to mapOf(
            AuditPayloadKey.VERSION to "1",
            AuditPayloadKey.LATEST_SEQ to "7",
            AuditPayloadKey.MATCHED_COUNT to "4",
            AuditPayloadKey.AMBIGUOUS_COUNT to "2",
            AuditPayloadKey.REMOTE_ONLY_COUNT to "1",
            AuditPayloadKey.BALANCE_DIFFERENCES_COUNT to "2",
            AuditPayloadKey.UNLINKED_PRODUCTS_COUNT to "0",
            AuditPayloadKey.AMBIGUOUS_PRODUCTS_COUNT to "1",
        ),
    )

    private companion object {
        const val BUSINESS_ID = "10000000-0000-4000-8000-000000000001"
        const val PURCHASE_ID = "10000000-0000-4000-8000-000000000002"
        const val OTHER_PURCHASE_ID = "10000000-0000-4000-8000-000000000003"
        const val DRAFT_ID = "10000000-0000-4000-8000-000000000004"
        const val PRODUCT_ID = "10000000-0000-4000-8000-000000000005"
        const val OTHER_PRODUCT_ID = "10000000-0000-4000-8000-000000000009"
        const val LOCATION_ID = "10000000-0000-4000-8000-000000000006"
        const val OPERATION_ID = "10000000-0000-4000-8000-000000000007"
        const val AUDIT_ID = "10000000-0000-4000-8000-000000000008"
        const val UNKNOWN_KEY_SENTINEL = "unknown_private_ruc_20123456789"
        const val SENSITIVE_VALUE_SENTINEL = "private.user@example.pe bearer-secret-token"
        const val SECRET_CONTENT = "SECRET_CONTENT"
    }
}
