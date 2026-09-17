package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateReason
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Claves permitidas en la bitácora contable; no incluye texto libre, importes ni hashes. */
object AuditPayloadKey {
    const val VERSION = "version"
    const val PURCHASE_ID = "purchaseId"
    const val DRAFT_ID = "draftId"
    const val EXISTING_PURCHASE_ID = "existingPurchaseId"
    const val PRODUCT_ID = "productId"
    const val LOCATION_ID = "locationId"
    const val OPERATION_ID = "operationId"
    const val REMOTE_PURCHASE_ID = "remotePurchaseId"
    const val REMOTE_RECEIPT_ID = "remoteReceiptId"
    const val ENTITY_TYPE = "entityType"
    const val ENTITY_ID = "entityId"
    const val REMOTE_ENTITY_ID = "remoteEntityId"
    const val LOCAL_VERSION = "localVersion"
    const val REMOTE_VERSION = "remoteVersion"
    const val ALIASES_CREATED_COUNT = "aliasesCreatedCount"
    const val ALIASES_SKIPPED_COUNT = "aliasesSkippedCount"
    const val ADJUSTMENT_APPLIED = "adjustmentApplied"
    const val DUPLICATE_KIND = "duplicateKind"
    const val DUPLICATE_REASON_CODES = "duplicateReasonCodes"
    const val ACTOR_ROLE = "actorRole"
    const val NEGATIVE_IMPACT_COUNT = "negativeImpactCount"
    const val NEGATIVE_STOCK_POLICY = "negativeStockPolicy"
    const val AVERAGE_UNIT_COST_POLICY = "averageUnitCostPolicy"
    const val ADJUSTMENT_KIND = "adjustmentKind"
    const val RESOLUTION = "resolution"
    const val LATEST_SEQ = "latestSeq"
    const val MATCHED_COUNT = "matchedCount"
    const val AMBIGUOUS_COUNT = "ambiguousCount"
    const val REMOTE_ONLY_COUNT = "remoteOnlyCount"
    const val BALANCE_DIFFERENCES_COUNT = "balanceDifferencesCount"
    const val UNLINKED_PRODUCTS_COUNT = "unlinkedProductsCount"
    const val AMBIGUOUS_PRODUCTS_COUNT = "ambiguousProductsCount"
}

/** Política cerrada por tipo de evento, compartida por use cases y builders Room directos. */
object AuditPayloadPolicy {
    private data class Rule(
        val required: Set<String>,
        val optional: Set<String> = emptySet(),
    )

    private val rules = mapOf(
        AuditEventType.PURCHASE_POSTED to Rule(
            required = setOf(
                AuditPayloadKey.VERSION,
                AuditPayloadKey.PURCHASE_ID,
                AuditPayloadKey.DRAFT_ID,
                AuditPayloadKey.ALIASES_CREATED_COUNT,
                AuditPayloadKey.ALIASES_SKIPPED_COUNT,
                AuditPayloadKey.ADJUSTMENT_APPLIED,
            ),
        ),
        AuditEventType.SALE_POSTED to Rule(
            required = setOf(AuditPayloadKey.VERSION),
        ),
        AuditEventType.SALE_VOIDED to Rule(
            required = setOf(AuditPayloadKey.VERSION, AuditPayloadKey.ACTOR_ROLE),
        ),
        AuditEventType.PURCHASE_VOIDED to Rule(
            required = setOf(
                AuditPayloadKey.VERSION,
                AuditPayloadKey.PURCHASE_ID,
                AuditPayloadKey.ACTOR_ROLE,
                AuditPayloadKey.NEGATIVE_IMPACT_COUNT,
                AuditPayloadKey.NEGATIVE_STOCK_POLICY,
                AuditPayloadKey.AVERAGE_UNIT_COST_POLICY,
            ),
        ),
        AuditEventType.PURCHASE_DUPLICATE_OVERRIDE to Rule(
            required = setOf(
                AuditPayloadKey.VERSION,
                AuditPayloadKey.PURCHASE_ID,
                AuditPayloadKey.DRAFT_ID,
                AuditPayloadKey.EXISTING_PURCHASE_ID,
                AuditPayloadKey.DUPLICATE_KIND,
                AuditPayloadKey.DUPLICATE_REASON_CODES,
                AuditPayloadKey.ACTOR_ROLE,
            ),
        ),
        AuditEventType.STOCK_ADJUSTED to Rule(
            required = setOf(
                AuditPayloadKey.VERSION,
                AuditPayloadKey.PRODUCT_ID,
                AuditPayloadKey.LOCATION_ID,
                AuditPayloadKey.ADJUSTMENT_KIND,
                AuditPayloadKey.ACTOR_ROLE,
            ),
        ),
        AuditEventType.SYNC_CONFLICT_RESOLVED to Rule(
            required = setOf(
                AuditPayloadKey.VERSION,
                AuditPayloadKey.OPERATION_ID,
                AuditPayloadKey.PURCHASE_ID,
                AuditPayloadKey.RESOLUTION,
            ),
            optional = setOf(
                AuditPayloadKey.REMOTE_PURCHASE_ID,
                AuditPayloadKey.REMOTE_RECEIPT_ID,
            ),
        ),
        AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED to Rule(
            required = setOf(
                AuditPayloadKey.VERSION,
                AuditPayloadKey.OPERATION_ID,
                AuditPayloadKey.ENTITY_TYPE,
                AuditPayloadKey.ENTITY_ID,
                AuditPayloadKey.REMOTE_ENTITY_ID,
                AuditPayloadKey.LOCAL_VERSION,
                AuditPayloadKey.REMOTE_VERSION,
                AuditPayloadKey.RESOLUTION,
            ),
        ),
        AuditEventType.SYNC_RECONCILED to Rule(
            required = setOf(
                AuditPayloadKey.VERSION,
                AuditPayloadKey.LATEST_SEQ,
                AuditPayloadKey.MATCHED_COUNT,
                AuditPayloadKey.AMBIGUOUS_COUNT,
                AuditPayloadKey.REMOTE_ONLY_COUNT,
                AuditPayloadKey.BALANCE_DIFFERENCES_COUNT,
                AuditPayloadKey.UNLINKED_PRODUCTS_COUNT,
                AuditPayloadKey.AMBIGUOUS_PRODUCTS_COUNT,
            ),
        ),
    )

    fun requireValid(eventType: AuditEventType, payload: Map<String, String>) {
        val rule = requireNotNull(rules[eventType]) { "Tipo de auditoría sin política: $eventType" }
        require(payload.keys.containsAll(rule.required)) {
            "Faltan claves requeridas para $eventType"
        }
        require(payload.keys.all { it in rule.required || it in rule.optional }) {
            "El payload contiene claves no permitidas para $eventType"
        }
        payload.forEach { (key, value) ->
            require(value.isAllowedAuditValue(key)) { "Valor inválido para clave auditada $key" }
        }
    }

    fun encode(eventType: AuditEventType, payload: Map<String, String>): String {
        requireValid(eventType, payload)
        return buildString {
            append('{')
            payload.entries.sortedBy(Map.Entry<String, String>::key)
                .forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    append('"').append(key.jsonEscaped()).append("\":\"")
                        .append(value.jsonEscaped()).append('"')
                }
            append('}')
        }
    }

    fun requireValidEnvelope(event: AuditEventWrite) {
        require(event.auditEventId.isCanonicalUuid()) { "auditEventId debe ser UUID canónico" }
        require(event.entityId.isCanonicalUuid()) { "entityId debe ser UUID canónico" }
        val expectedEntityType = when (event.eventType) {
            AuditEventType.PURCHASE_POSTED,
            AuditEventType.PURCHASE_VOIDED,
            AuditEventType.PURCHASE_DUPLICATE_OVERRIDE,
            -> "PURCHASE"
            AuditEventType.SALE_POSTED,
            AuditEventType.SALE_VOIDED,
            -> "SALE"
            AuditEventType.STOCK_ADJUSTED -> "STOCK_BALANCE"
            AuditEventType.SYNC_CONFLICT_RESOLVED -> "purchase"
            AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED -> event.entityType.also {
                require(it == "PRODUCT" || it == "SUPPLIER")
            }
            AuditEventType.SYNC_RECONCILED -> "business"
        }
        require(event.entityType == expectedEntityType) {
            "entityType inválido para ${event.eventType}"
        }
        when (event.eventType) {
            AuditEventType.PURCHASE_POSTED,
            AuditEventType.PURCHASE_VOIDED,
            AuditEventType.PURCHASE_DUPLICATE_OVERRIDE,
            AuditEventType.SYNC_CONFLICT_RESOLVED,
            -> require(event.purchaseId?.value == event.entityId) {
                "La auditoría de compra debe referir el mismo purchaseId"
            }
            AuditEventType.STOCK_ADJUSTED -> require(event.purchaseId == null)
            AuditEventType.SALE_POSTED,
            AuditEventType.SALE_VOIDED,
            -> require(event.purchaseId == null)
            AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED -> require(event.purchaseId == null)
            AuditEventType.SYNC_RECONCILED -> require(
                event.purchaseId == null && event.entityId == event.businessId.value,
            ) { "La reconciliación debe referir el negocio auditado" }
        }
    }

    private fun String.isAllowedAuditValue(key: String): Boolean = when (key) {
        AuditPayloadKey.VERSION -> this == "1"
        AuditPayloadKey.PURCHASE_ID,
        AuditPayloadKey.DRAFT_ID,
        AuditPayloadKey.EXISTING_PURCHASE_ID,
        AuditPayloadKey.PRODUCT_ID,
        AuditPayloadKey.LOCATION_ID,
        AuditPayloadKey.OPERATION_ID,
        AuditPayloadKey.REMOTE_PURCHASE_ID,
        AuditPayloadKey.ENTITY_ID,
        AuditPayloadKey.REMOTE_ENTITY_ID,
        -> isCanonicalUuid()
        AuditPayloadKey.REMOTE_RECEIPT_ID -> RECEIPT_ID.matches(this)
        AuditPayloadKey.ALIASES_CREATED_COUNT,
        AuditPayloadKey.ALIASES_SKIPPED_COUNT,
        AuditPayloadKey.NEGATIVE_IMPACT_COUNT,
        AuditPayloadKey.LATEST_SEQ,
        AuditPayloadKey.MATCHED_COUNT,
        AuditPayloadKey.AMBIGUOUS_COUNT,
        AuditPayloadKey.REMOTE_ONLY_COUNT,
        AuditPayloadKey.BALANCE_DIFFERENCES_COUNT,
        AuditPayloadKey.UNLINKED_PRODUCTS_COUNT,
        AuditPayloadKey.AMBIGUOUS_PRODUCTS_COUNT,
        AuditPayloadKey.LOCAL_VERSION,
        AuditPayloadKey.REMOTE_VERSION,
        -> NON_NEGATIVE_INTEGER.matches(this)
        AuditPayloadKey.ADJUSTMENT_APPLIED -> this == "true" || this == "false"
        AuditPayloadKey.DUPLICATE_REASON_CODES -> isNotEmpty() &&
            split(',').all { code ->
                PurchaseDuplicateReason.entries.any { reason -> reason.name == code }
            }
        AuditPayloadKey.DUPLICATE_KIND ->
            PurchaseDuplicateKind.entries.any { kind -> kind.name == this }
        AuditPayloadKey.ACTOR_ROLE ->
            PurchaseOverrideRole.entries.any { role -> role.name == this }
        AuditPayloadKey.NEGATIVE_STOCK_POLICY -> this == "ALLOW_WITH_VISIBLE_WARNING"
        AuditPayloadKey.AVERAGE_UNIT_COST_POLICY -> this == "PRESERVE_CURRENT"
        AuditPayloadKey.ADJUSTMENT_KIND -> this == "MANUAL"
        AuditPayloadKey.ENTITY_TYPE -> this == "PRODUCT" || this == "SUPPLIER"
        AuditPayloadKey.RESOLUTION ->
            this == "KEEP_REMOTE" || this == "APPLY_REMOTE" || this == "KEEP_LOCAL"
        else -> false
    }

    private fun String.isCanonicalUuid(): Boolean {
        if (length != 36 || this != lowercase(Locale.ROOT)) return false
        val parsed = runCatching(UUID::fromString).getOrNull() ?: return false
        return parsed != UUID(0L, 0L) && parsed.toString() == this
    }

    private val NON_NEGATIVE_INTEGER = Regex("0|[1-9][0-9]{0,18}")
    private val RECEIPT_ID = Regex("rcpt_[0-9a-f]{32}")

    private fun String.jsonEscaped(): String = buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
}

/** Evento de auditoría a persistir; el payload son pares clave-valor sin datos sensibles. */
data class AuditEventWrite(
    val auditEventId: String,
    val businessId: BusinessId,
    val purchaseId: PurchaseId?,
    val eventType: AuditEventType,
    val entityType: String,
    val entityId: String,
    val payload: Map<String, String>,
    val occurredAt: Instant,
) {
    init {
        AuditPayloadPolicy.requireValid(eventType, payload)
        AuditPayloadPolicy.requireValidEnvelope(this)
    }
}

/** Bitácora durable: registra decisiones humanas de sincronización (nunca las ejecuta). */
interface AuditTrailRepository {
    suspend fun record(event: AuditEventWrite)
}
