package com.facturastock.app.data.spark

import com.facturastock.app.data.sync.FirebaseCatalogWireMapper
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.google.firebase.Timestamp
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Documento canónico validado que el writer Spark puede aplicar en una transacción CAS. */
internal data class SparkCatalogMutation(
    val operationType: String,
    val entityType: String,
    val collection: String,
    val documentVersion: Long,
    val entityId: String,
    val expectedVersion: Long,
    val targetVersion: Long,
    val mutation: String,
    val snapshot: Map<String, Any?>,
    val snapshotPayload: String,
    val snapshotSha256: String,
)

/** Clave semántica compatible con los índices únicos que mantiene catalogSync.js. */
internal data class SparkCatalogSemanticKey(
    val collection: String,
    val value: String,
    val documentId: String,
)

internal data class SparkCatalogSemanticIndexOwner(
    val entityId: String,
    val version: Long,
)

internal sealed interface SparkCatalogSemanticIndexPlan {
    data class Apply(
        val upserts: Set<SparkCatalogSemanticKey>,
        val removals: Set<SparkCatalogSemanticKey>,
    ) : SparkCatalogSemanticIndexPlan

    data class Conflict(
        val key: SparkCatalogSemanticKey,
        val ownerEntityId: String,
    ) : SparkCatalogSemanticIndexPlan
}

/**
 * Contrato compartido por el push y el pull directos. Reutiliza el codec estricto de Functions,
 * conserva el JSON original del snapshot y genera identificadores deterministas sin PII.
 */
internal object SparkCatalogCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun parseMutation(
        operationType: String,
        payload: String,
        remoteEntityId: String? = null,
    ): SparkCatalogMutation {
        val document = FirebaseCatalogWireMapper.pushDocument(
            operationType = operationType,
            payload = payload,
            remoteEntityId = remoteEntityId,
        )
        val snapshotPayload = json.parseToJsonElement(payload)
            .jsonObject
            .getValue(SNAPSHOT)
            .toString()
        @Suppress("UNCHECKED_CAST")
        val snapshot = document[SNAPSHOT] as? Map<String, Any?>
            ?: throw IllegalArgumentException(SNAPSHOT)
        val entityType = when (operationType) {
            SYNC_PRODUCT -> PRODUCT
            SYNC_SUPPLIER -> SUPPLIER
            else -> throw IllegalArgumentException(OPERATION_TYPE)
        }
        return SparkCatalogMutation(
            operationType = operationType,
            entityType = entityType,
            collection = if (entityType == PRODUCT) PRODUCTS else SUPPLIERS,
            documentVersion = document.requiredLong(VERSION),
            entityId = document.requiredString(ENTITY_ID),
            expectedVersion = document.requiredLong(EXPECTED_VERSION),
            targetVersion = document.requiredLong(TARGET_VERSION),
            mutation = document.requiredString(MUTATION),
            snapshot = snapshot,
            snapshotPayload = snapshotPayload,
            snapshotSha256 = sha256(snapshotPayload),
        )
    }

    /** Acuse estable: una repetición de la misma clave siempre confirma el mismo resultado. */
    fun receiptId(idempotencyKey: String): String {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_LENGTH)
        return RECEIPT_PREFIX + sha256("$RECEIPT_NAMESPACE$idempotencyKey").take(RECEIPT_HEX_LENGTH)
    }

    /** ID opaco de la operación; nunca usa la clave causal como path de Firestore. */
    fun operationDocumentId(idempotencyKey: String): String {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_LENGTH)
        // Misma identidad que catalogSync.js para que Blaze reconozca operaciones Spark previas.
        return sha256(idempotencyKey)
    }

    /** Hash estable para detectar una reutilización de clave con contenido distinto. */
    fun requestHash(
        businessId: String,
        idempotencyKey: String,
        payloadVersion: Int,
        mutation: SparkCatalogMutation,
    ): String {
        require(CANONICAL_UUID.matches(businessId))
        require(payloadVersion >= 1)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_LENGTH)
        // Orden y forma exactos de canonicalRequest en functions/catalogSync.js. Un upgrade a
        // Blaze puede así reproducir el mismo requestHash y devolver el ACK Spark ya guardado.
        val canonicalRequest = buildString {
            append("{\"businessId\":")
            append(JsonPrimitive(businessId))
            append(",\"idempotencyKey\":")
            append(JsonPrimitive(idempotencyKey))
            append(",\"operationType\":")
            append(JsonPrimitive(mutation.operationType))
            append(",\"payloadVersion\":")
            append(payloadVersion)
            append(",\"document\":{\"version\":")
            append(mutation.documentVersion)
            append(",\"entityId\":")
            append(JsonPrimitive(mutation.entityId))
            append(",\"expectedVersion\":")
            append(mutation.expectedVersion)
            append(",\"targetVersion\":")
            append(mutation.targetVersion)
            append(",\"mutation\":")
            append(JsonPrimitive(mutation.mutation))
            append(",\"snapshot\":")
            append(mutation.snapshotPayload)
            append("}}")
        }
        return sha256(canonicalRequest)
    }

    /**
     * Deriva exactamente los índices de unicidad de Functions. El valor nunca se persiste en el
     * documento índice: solo se usa para obtener su ID SHA-256, por lo que el upgrade a Blaze no
     * necesita migrar ni reinterpretar esos documentos.
     */
    fun semanticKeys(
        entityType: String,
        snapshot: Map<String, Any?>,
    ): Set<SparkCatalogSemanticKey> {
        val values = when (entityType) {
            PRODUCT -> listOfNotNull(
                snapshot.semanticText(SKU, MAX_SKU_LENGTH, uppercase = true)?.let { value ->
                    PRODUCT_SKU_INDEX to value
                },
                snapshot.semanticText(BARCODE, MAX_BARCODE_LENGTH)?.let { value ->
                    PRODUCT_BARCODE_INDEX to value
                },
            )
            SUPPLIER -> listOfNotNull(
                snapshot.semanticText(RUC, RUC_LENGTH)?.also { value ->
                    require(RUC_PATTERN.matches(value))
                }?.let { value -> SUPPLIER_RUC_INDEX to value },
            )
            else -> throw IllegalArgumentException(ENTITY_TYPE)
        }
        return values.mapTo(linkedSetOf()) { (collection, value) ->
            SparkCatalogSemanticKey(
                collection = collection,
                value = value,
                documentId = sha256(value),
            )
        }.also { keys ->
            require(keys.size == values.size)
        }
    }

    /** Plan CAS puro: nunca roba un índice ajeno y libera únicamente claves del snapshot viejo. */
    fun semanticIndexPlan(
        entityId: String,
        currentVersion: Long,
        oldKeys: Set<SparkCatalogSemanticKey>,
        newKeys: Set<SparkCatalogSemanticKey>,
        owners: Map<SparkCatalogSemanticKey, SparkCatalogSemanticIndexOwner?>,
    ): SparkCatalogSemanticIndexPlan {
        require(CANONICAL_UUID.matches(entityId))
        require(currentVersion in 0 until MAX_SAFE_INTEGER)
        val allKeys = oldKeys + newKeys
        require(owners.keys == allKeys)
        owners.values.filterNotNull().forEach { owner ->
            require(CANONICAL_UUID.matches(owner.entityId))
            require(owner.version in 1..MAX_SAFE_INTEGER)
        }
        (newKeys + oldKeys).firstOrNull { key ->
            owners.getValue(key)?.entityId?.let { ownerId -> ownerId != entityId } == true
        }?.let { key ->
            return SparkCatalogSemanticIndexPlan.Conflict(
                key = key,
                ownerEntityId = requireNotNull(owners.getValue(key)).entityId,
            )
        }
        require(owners.values.filterNotNull().all { owner ->
            owner.version == currentVersion
        })
        return SparkCatalogSemanticIndexPlan.Apply(
            upserts = newKeys,
            removals = oldKeys - newKeys,
        )
    }

    /**
     * Convierte documents directos a la misma página cerrada que devuelve listCatalogChanges.
     * La validación final sigue centralizada en [FirebaseCatalogWireMapper].
     */
    fun pullPage(
        documents: List<SparkCatalogChangeDocument>,
        sinceSeq: Long,
        hasMore: Boolean,
    ): CatalogSyncPullPage {
        require(sinceSeq >= 0L)
        require(documents.size <= MAX_PAGE_SIZE)
        require(documents.all { it.seq > sinceSeq })
        val changes = documents.map { document -> document.toCallableWire() }
        val nextCursor = documents.lastOrNull()?.seq ?: sinceSeq
        return FirebaseCatalogWireMapper.pullPage(
            mapOf(
                "changes" to changes,
                "nextCursor" to nextCursor,
                "hasMore" to hasMore,
            ),
        )
    }

    /** Decodifica solo el esquema exacto permitido por las rules del modo Spark. */
    fun decodeChange(
        documentId: String,
        data: Map<String, Any?>,
        expectedOwnerUid: String,
    ): SparkCatalogChangeDocument = try {
        require(data.keys == CHANGE_KEYS)
        require(data.requiredLong(SCHEMA_VERSION) == SPARK_SCHEMA_VERSION)
        val receiptId = data.requiredString(RECEIPT_ID)
        require(documentId == receiptId && RECEIPT_ID_PATTERN.matches(receiptId))
        val ownerUid = data.requiredString(OWNER_UID)
        require(ownerUid == expectedOwnerUid && ownerUid.length <= MAX_UID_LENGTH)
        val entityType = data.requiredString(ENTITY_TYPE)
        require(entityType == PRODUCT || entityType == SUPPLIER)
        val entityId = data.requiredString(ENTITY_ID)
        require(CANONICAL_UUID.matches(entityId))
        val mutation = data.requiredString(MUTATION)
        require(mutation == UPSERT)
        val snapshotPayload = data.requiredString(SNAPSHOT_PAYLOAD)
        require(snapshotPayload.isNotEmpty() && snapshotPayload.length <= MAX_SNAPSHOT_CHARS)
        @Suppress("UNCHECKED_CAST")
        val snapshot = data[SNAPSHOT] as? Map<String, Any?> ?: error(SNAPSHOT)
        // Verifica que el Map compatible con Blaze y el JSON exacto de pull sean equivalentes.
        require(firebaseJsonElement(snapshot) == json.parseToJsonElement(snapshotPayload))
        val snapshotSha256 = data.requiredString(SNAPSHOT_SHA256)
        require(SHA256_PATTERN.matches(snapshotSha256))
        require(sha256(snapshotPayload) == snapshotSha256)
        val syncedAt = data[SYNCED_AT] as? Timestamp ?: error(SYNCED_AT)
        SparkCatalogChangeDocument(
            seq = data.requiredLong(SEQ).also { require(it > 0L) },
            entityType = entityType,
            entityId = entityId,
            version = data.requiredLong(VERSION).also { require(it > 0L) },
            mutation = mutation,
            snapshotPayload = snapshotPayload,
            snapshotSha256 = snapshotSha256,
            receiptId = receiptId,
            syncedAtMillis = syncedAt.toDate().time.also { require(it >= 0L) },
        )
    } catch (failure: AccountException) {
        throw failure
    } catch (failure: Exception) {
        throw AccountException(AccountError.Unexpected, failure)
    }

    /** Forma JSON semántica; la igualdad de JsonObject no depende del orden del Map del SDK. */
    private fun firebaseJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Byte, is Short, is Int, is Long -> JsonPrimitive((value as Number).toLong())
        is Map<*, *> -> JsonObject(value.entries.associate { (key, child) ->
            require(key is String)
            key to firebaseJsonElement(child)
        })
        else -> throw IllegalArgumentException("valor Firestore no canónico")
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (this[key] as? String)?.takeIf(String::isNotEmpty) ?: error(key)

    private fun Map<String, Any?>.requiredLong(key: String): Long = when (val value = this[key]) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it in 0..MAX_SAFE_INTEGER } ?: error(key)

    private fun Map<String, Any?>.semanticText(
        key: String,
        maximumLength: Int,
        uppercase: Boolean = false,
    ): String? {
        require(containsKey(key))
        val value = this[key] ?: return null
        require(value is String && value.isNotEmpty() && value.length <= maximumLength)
        require(value == value.trim())
        if (uppercase) require(value == value.uppercase())
        return value
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val SYNC_PRODUCT = "SYNC_PRODUCT"
    private const val SYNC_SUPPLIER = "SYNC_SUPPLIER"
    private const val PRODUCT = "PRODUCT"
    private const val SUPPLIER = "SUPPLIER"
    private const val PRODUCTS = "products"
    private const val SUPPLIERS = "suppliers"
    private const val PRODUCT_SKU_INDEX = "productSkuIndex"
    private const val PRODUCT_BARCODE_INDEX = "productBarcodeIndex"
    private const val SUPPLIER_RUC_INDEX = "supplierRucIndex"
    private const val UPSERT = "UPSERT"
    private const val OPERATION_TYPE = "operationType"
    private const val SCHEMA_VERSION = "schemaVersion"
    private const val SPARK_SCHEMA_VERSION = 1L
    private const val OWNER_UID = "ownerUid"
    private const val SEQ = "seq"
    private const val ENTITY_TYPE = "entityType"
    private const val ENTITY_ID = "entityId"
    private const val VERSION = "version"
    private const val EXPECTED_VERSION = "expectedVersion"
    private const val TARGET_VERSION = "targetVersion"
    private const val MUTATION = "mutation"
    private const val SNAPSHOT = "snapshot"
    private const val SNAPSHOT_PAYLOAD = "snapshotPayload"
    private const val SNAPSHOT_SHA256 = "snapshotSha256"
    private const val RECEIPT_ID = "receiptId"
    private const val SYNCED_AT = "syncedAt"
    private const val SKU = "sku"
    private const val BARCODE = "barcode"
    private const val RUC = "ruc"
    private const val RECEIPT_PREFIX = "rcpt_"
    private const val RECEIPT_NAMESPACE = "facturastock:spark-catalog-receipt:v1:"
    private const val RECEIPT_HEX_LENGTH = 32
    private const val MAX_IDEMPOTENCY_LENGTH = 256
    private const val MAX_UID_LENGTH = 128
    private const val MAX_PAGE_SIZE = 200
    private const val MAX_SNAPSHOT_CHARS = 64_000
    private const val MAX_SKU_LENGTH = 64
    private const val MAX_BARCODE_LENGTH = 128
    private const val RUC_LENGTH = 11
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val RECEIPT_ID_PATTERN = Regex("^rcpt_[0-9a-f]{32}$")
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val RUC_PATTERN = Regex("^[0-9]{11}$")
    private val CANONICAL_UUID =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val CHANGE_KEYS = setOf(
        SCHEMA_VERSION,
        SEQ,
        ENTITY_TYPE,
        ENTITY_ID,
        VERSION,
        MUTATION,
        SNAPSHOT,
        SNAPSHOT_PAYLOAD,
        SNAPSHOT_SHA256,
        RECEIPT_ID,
        SYNCED_AT,
        OWNER_UID,
    )
}

internal data class SparkCatalogChangeDocument(
    val seq: Long,
    val entityType: String,
    val entityId: String,
    val version: Long,
    val mutation: String,
    val snapshotPayload: String,
    val snapshotSha256: String,
    val receiptId: String,
    val syncedAtMillis: Long,
) {
    fun toCallableWire(): Map<String, Any?> = linkedMapOf(
        "seq" to seq,
        "entityType" to entityType,
        "entityId" to entityId,
        "remoteVersion" to version,
        "mutation" to mutation,
        "snapshotPayload" to snapshotPayload,
        "snapshotSha256" to snapshotSha256,
        "receiptId" to receiptId,
        "syncedAtMillis" to syncedAtMillis,
    )
}
