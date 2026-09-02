package com.facturastock.app.data.spark

/**
 * Excepcion de identidad documental autorizada por el propietario.
 *
 * `reason` solo participa en la validacion local. Igual que Blaze, nunca se persiste ni forma
 * parte del hash idempotente para que un replay pueda cambiar el texto sin crear otro hecho.
 */
internal data class SparkPurchaseDuplicateOverride(
    val existingPurchaseId: String,
    val sourceDraftId: String,
    val auditEventId: String,
)

internal object SparkPurchaseDuplicateOverrideCodec {
    fun parse(
        raw: Any?,
        purchaseId: String,
        auditEventIds: List<String>,
    ): SparkPurchaseDuplicateOverride? {
        if (raw == null) {
            require(auditEventIds.size == 1)
            return null
        }
        val value = raw.stringMap()
        require(value.keys == INPUT_KEYS)
        val existingPurchaseId = value.requiredString(EXISTING_PURCHASE_ID)
        val sourceDraftId = value.requiredString(SOURCE_DRAFT_ID)
        val auditEventId = value.requiredString(AUDIT_EVENT_ID)
        val reason = value.requiredString(REASON)
        require(CANONICAL_UUID.matches(existingPurchaseId) && existingPurchaseId != purchaseId)
        require(CANONICAL_UUID.matches(sourceDraftId))
        require(CANONICAL_UUID.matches(auditEventId))
        require(auditEventIds.size == 2 && auditEventIds.count { it == auditEventId } == 1)
        require(reason == reason.trim() && reason.length in REASON_MIN_LENGTH..REASON_MAX_LENGTH)
        return SparkPurchaseDuplicateOverride(
            existingPurchaseId = existingPurchaseId,
            sourceDraftId = sourceDraftId,
            auditEventId = auditEventId,
        )
    }

    /** Misma identidad que `documentIdentityHash()` en Functions. */
    fun identityDocumentId(document: Map<String, Any?>): String = SparkFirestoreSchema.sha256(
        SparkFirestoreSchema.jsonStringify(
            listOf(
                document[SUPPLIER_RUC] as? String ?: "",
                document.requiredString(DOCUMENT_TYPE),
                document.requiredString(DOCUMENT_SERIES),
                document.requiredString(DOCUMENT_NUMBER),
            ),
        ),
    )

    /** Mismo slot secundario que `duplicateOverrideSlotHash()` en Functions. */
    fun slotDocumentId(identityDocumentId: String, sourceDraftId: String): String =
        SparkFirestoreSchema.sha256(
            SparkFirestoreSchema.jsonStringify(
                listOf(OVERRIDE, identityDocumentId, sourceDraftId),
            ),
        )

    fun persistedMap(value: SparkPurchaseDuplicateOverride?): Map<String, Any?>? = value?.let {
        linkedMapOf(
            EXISTING_PURCHASE_ID to it.existingPurchaseId,
            SOURCE_DRAFT_ID to it.sourceDraftId,
            AUDIT_EVENT_ID to it.auditEventId,
            AUTHORIZED_ROLE to OWNER,
        )
    }

    fun hashMap(value: SparkPurchaseDuplicateOverride?): Map<String, Any?>? = value?.let {
        linkedMapOf(
            EXISTING_PURCHASE_ID to it.existingPurchaseId,
            SOURCE_DRAFT_ID to it.sourceDraftId,
            AUDIT_EVENT_ID to it.auditEventId,
        )
    }

    fun targetMatches(
        target: Map<String, Any?>,
        businessId: String,
        identityDocumentId: String,
    ): Boolean = target[BUSINESS_ID] == businessId &&
        target[STATUS] in TARGET_STATUSES &&
        runCatching { identityDocumentId(target) }.getOrNull() == identityDocumentId

    fun primaryIndexMatches(
        index: Map<String, Any?>,
        targetPurchaseId: String,
        targetReceiptId: String?,
    ): Boolean = index[SLOT] == PRIMARY &&
        index[PURCHASE_ID] == targetPurchaseId &&
        targetReceiptId != null && index[RECEIPT_ID] == targetReceiptId

    private fun Any?.stringMap(): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: throw IllegalArgumentException(DUPLICATE_OVERRIDE)
        require(raw.keys.all { it is String })
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (this[key] as? String)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(key)

    private val INPUT_KEYS = setOf(
        EXISTING_PURCHASE_ID,
        SOURCE_DRAFT_ID,
        AUDIT_EVENT_ID,
        REASON,
    )
    private val TARGET_STATUSES = setOf(POSTED, VOIDED)
    private val CANONICAL_UUID =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private const val REASON_MIN_LENGTH = 10
    private const val REASON_MAX_LENGTH = 500
    private const val DUPLICATE_OVERRIDE = "duplicateOverride"
    private const val EXISTING_PURCHASE_ID = "existingPurchaseId"
    private const val SOURCE_DRAFT_ID = "sourceDraftId"
    private const val AUDIT_EVENT_ID = "auditEventId"
    private const val REASON = "reason"
    private const val AUTHORIZED_ROLE = "authorizedRole"
    private const val OWNER = "OWNER"
    private const val SUPPLIER_RUC = "supplierRuc"
    private const val DOCUMENT_TYPE = "documentType"
    private const val DOCUMENT_SERIES = "documentSeries"
    private const val DOCUMENT_NUMBER = "documentNumber"
    private const val BUSINESS_ID = "businessId"
    private const val PURCHASE_ID = "purchaseId"
    private const val RECEIPT_ID = "receiptId"
    private const val STATUS = "status"
    private const val SLOT = "slot"
    private const val PRIMARY = "PRIMARY"
    private const val OVERRIDE = "OVERRIDE"
    private const val POSTED = "POSTED"
    private const val VOIDED = "VOIDED"
}

internal object SparkPurchaseCatalogPreflight {
    fun isActiveProduct(data: Map<String, Any?>?): Boolean {
        if (data?.get(ENTITY_TYPE) != PRODUCT) return false
        val snapshot = data[SNAPSHOT] as? Map<*, *> ?: return false
        return snapshot[STATUS] == ACTIVE
    }

    private const val ENTITY_TYPE = "entityType"
    private const val PRODUCT = "PRODUCT"
    private const val SNAPSHOT = "snapshot"
    private const val STATUS = "status"
    private const val ACTIVE = "ACTIVE"
}
