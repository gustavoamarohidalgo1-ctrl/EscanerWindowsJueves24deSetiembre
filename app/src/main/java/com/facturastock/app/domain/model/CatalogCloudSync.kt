package com.facturastock.app.domain.model

import java.time.Instant

enum class RemoteCatalogEntityType { PRODUCT, SUPPLIER }

enum class RemoteCatalogApplicationStatus { PENDING, APPLIED, CONFLICT, RESOLVED }

/** Hecho de catálogo remoto cerrado; [snapshotPayload] es JSON canónico ya validado. */
data class RemoteCatalogChange(
    val seq: Long,
    val entityType: RemoteCatalogEntityType,
    val remoteEntityId: String,
    val remoteVersion: Long,
    val mutation: String,
    val snapshotPayload: String,
    val snapshotSha256: String,
    val receiptId: String,
    val syncedAt: Instant?,
) {
    init {
        require(seq in 1..MAX_SAFE_SYNC_SEQUENCE)
        require(CANONICAL_UUID.matches(remoteEntityId))
        require(remoteVersion >= 1L)
        require(mutation == "UPSERT")
        require(snapshotPayload.isNotEmpty() && snapshotPayload.length <= 64_000)
        require(SHA256.matches(snapshotSha256))
        require(receiptId.isNotBlank() && receiptId.length <= 256)
    }
}

data class CatalogSyncPullPage(
    val changes: List<RemoteCatalogChange>,
    val nextCursor: Long,
    val hasMore: Boolean,
) {
    init {
        require(nextCursor in 0..MAX_SAFE_SYNC_SEQUENCE)
        require(!hasMore || changes.isNotEmpty())
        changes.lastOrNull()?.let { require(it.seq == nextCursor) }
    }
}

/** Comparación durable que la UI puede observar sin volver a consultar la red. */
data class RemoteCatalogApplication(
    val change: RemoteCatalogChange,
    val origin: String,
    val receivedAt: Instant,
    val status: RemoteCatalogApplicationStatus,
    val localEntityId: String?,
    val localVersion: Long?,
    val localSnapshotPayload: String?,
    val conflictCode: String?,
    val conflictDetectedAt: Instant?,
    val resolution: String?,
    val resolvedAt: Instant?,
)

private val CANONICAL_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
