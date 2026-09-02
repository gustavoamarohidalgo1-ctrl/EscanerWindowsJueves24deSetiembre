package com.facturastock.app.data.sync

import com.facturastock.app.data.repository.CatalogSnapshotCodec
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.RemoteCatalogChange
import com.facturastock.app.domain.model.RemoteCatalogEntityType
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Codec estricto entre el JSON canónico de Room y los tipos simples aceptados por Functions. */
internal object FirebaseCatalogWireMapper {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun pushDocument(
        operationType: String,
        payload: String,
        remoteEntityId: String? = null,
    ): Map<String, Any?> {
        val document = parseObject(payload)
        require(document.keys == DOCUMENT_KEYS)
        val documentVersion = document.requiredLong("version")
        when (operationType) {
            SYNC_PRODUCT -> require(documentVersion in PRODUCT_DOCUMENT_VERSIONS)
            SYNC_SUPPLIER -> require(documentVersion == SUPPLIER_DOCUMENT_VERSION)
            else -> throw IllegalArgumentException("operationType")
        }
        val localEntityId = document.requiredString("entityId")
        require(CANONICAL_UUID.matches(localEntityId))
        val wireEntityId = remoteEntityId ?: localEntityId
        require(CANONICAL_UUID.matches(wireEntityId))
        val expectedVersion = document.requiredLong("expectedVersion")
        val targetVersion = document.requiredLong("targetVersion")
        require(expectedVersion in 0 until MAX_SAFE_INTEGER)
        require(targetVersion == expectedVersion + 1L)
        require(document.requiredString("mutation") == UPSERT)
        val snapshot = document["snapshot"] as? JsonObject
            ?: throw IllegalArgumentException("snapshot")
        val snapshotPayload = snapshot.toString()
        val canonical = when (operationType) {
            SYNC_PRODUCT -> CatalogSnapshotCodec.decodeProduct(snapshotPayload)?.also { product ->
                require(product.includesSalePrice == (documentVersion == 2L))
            }
            SYNC_SUPPLIER -> CatalogSnapshotCodec.decodeSupplier(snapshotPayload)
            else -> null
        }
        requireNotNull(canonical)
        // El envelope generado por Room es congelado; no se normaliza una forma distinta.
        require(document.toString() == payload)
        return linkedMapOf(
            "version" to documentVersion,
            "entityId" to wireEntityId,
            "expectedVersion" to expectedVersion,
            "targetVersion" to targetVersion,
            "mutation" to UPSERT,
            "snapshot" to snapshot.toFirebaseMap(),
        )
    }

    fun pullPage(data: Map<*, *>): CatalogSyncPullPage = try {
        require(data.stringKeys() == PULL_PAGE_KEYS)
        val rawChanges = data["changes"] as? List<*> ?: error("changes")
        require(rawChanges.size <= MAX_PAGE_SIZE)
        val changes = rawChanges.map { raw -> pullChange(raw as? Map<*, *> ?: error("change")) }
        val nextCursor = data["nextCursor"].safeLong()
        val hasMore = data["hasMore"] as? Boolean ?: error("hasMore")
        require(nextCursor in 0..MAX_SAFE_INTEGER)
        require(changes.zipWithNext().all { (left, right) -> right.seq > left.seq })
        require(changes.all { it.seq <= nextCursor })
        require(changes.lastOrNull()?.seq == nextCursor || changes.isEmpty())
        require(!hasMore || changes.isNotEmpty())
        CatalogSyncPullPage(changes, nextCursor, hasMore)
    } catch (failure: AccountException) {
        throw failure
    } catch (failure: Exception) {
        throw AccountException(AccountError.Unexpected, failure)
    }

    private fun pullChange(data: Map<*, *>): RemoteCatalogChange {
        require(data.stringKeys() == CHANGE_KEYS)
        val type = RemoteCatalogEntityType.valueOf(data["entityType"] as? String ?: error("type"))
        val snapshotPayload = data["snapshotPayload"] as? String ?: error("snapshot")
        require(snapshotPayload.length <= MAX_SNAPSHOT_CHARS)
        val canonical = when (type) {
            RemoteCatalogEntityType.PRODUCT -> CatalogSnapshotCodec.decodeProduct(snapshotPayload)
            RemoteCatalogEntityType.SUPPLIER -> CatalogSnapshotCodec.decodeSupplier(snapshotPayload)
        }
        requireNotNull(canonical)
        val snapshotSha256 = data["snapshotSha256"] as? String ?: error("sha")
        require(sha256(snapshotPayload) == snapshotSha256)
        val syncedAt = when (val value = data["syncedAtMillis"]) {
            null -> null
            else -> Instant.ofEpochMilli(value.safeLong().also { require(it >= 0L) })
        }
        return RemoteCatalogChange(
            seq = data["seq"].safeLong(),
            entityType = type,
            remoteEntityId = data["entityId"] as? String ?: error("entityId"),
            remoteVersion = data["remoteVersion"].safeLong(),
            mutation = data["mutation"] as? String ?: error("mutation"),
            snapshotPayload = snapshotPayload,
            snapshotSha256 = snapshotSha256,
            receiptId = data["receiptId"] as? String ?: error("receiptId"),
            syncedAt = syncedAt,
        )
    }

    private fun parseObject(payload: String): JsonObject {
        require(payload.isNotEmpty() && payload.length <= MAX_DOCUMENT_CHARS)
        return json.parseToJsonElement(payload).jsonObject
    }

    private fun JsonObject.requiredString(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: error(key)
        require(primitive.isString)
        return primitive.content
    }

    private fun JsonObject.requiredLong(key: String): Long {
        val primitive = this[key] as? JsonPrimitive ?: error(key)
        require(!primitive.isString && primitive.booleanOrNull == null)
        return primitive.longOrNull ?: error(key)
    }

    private fun JsonObject.toFirebaseMap(): Map<String, Any?> = entries.associateTo(linkedMapOf()) {
        (key, value) -> key to value.toFirebaseValue()
    }

    private fun JsonElement.toFirebaseValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> toFirebaseMap()
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            else -> throw IllegalArgumentException("Número no entero")
        }
        else -> throw IllegalArgumentException("Array no permitido")
    }

    private fun Map<*, *>.stringKeys(): Set<String> {
        require(keys.all { it is String })
        return keys.mapTo(linkedSetOf()) { it as String }
    }

    private fun Any?.safeLong(): Long = when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        is Float -> takeIf { isFinite() && it % 1f == 0f }?.toLong()
        is Double -> takeIf { isFinite() && it % 1.0 == 0.0 }?.toLong()
        else -> null
    }?.also { require(it in 0..MAX_SAFE_INTEGER) } ?: error("entero seguro")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val SYNC_PRODUCT = "SYNC_PRODUCT"
    private const val SYNC_SUPPLIER = "SYNC_SUPPLIER"
    private const val SUPPLIER_DOCUMENT_VERSION = 1L
    private val PRODUCT_DOCUMENT_VERSIONS = 1L..2L
    private const val UPSERT = "UPSERT"
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val MAX_PAGE_SIZE = 200
    private const val MAX_DOCUMENT_CHARS = 128_000
    private const val MAX_SNAPSHOT_CHARS = 64_000
    private val CANONICAL_UUID =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val DOCUMENT_KEYS = setOf(
        "version", "entityId", "expectedVersion", "targetVersion", "mutation", "snapshot",
    )
    private val PULL_PAGE_KEYS = setOf("changes", "nextCursor", "hasMore")
    private val CHANGE_KEYS = setOf(
        "seq", "entityType", "entityId", "remoteVersion", "mutation", "snapshotPayload",
        "snapshotSha256", "receiptId", "syncedAtMillis",
    )
}
