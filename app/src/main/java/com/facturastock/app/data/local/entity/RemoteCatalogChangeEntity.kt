package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireText
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE

/** Cambio remoto cerrado de PRODUCT/SUPPLIER listo para aplicación CAS explícita. */
@Entity(
    tableName = "remote_catalog_changes",
    primaryKeys = ["cloudBusinessId", "seq"],
    indices = [
        Index(value = ["cloudBusinessId", "applicationStatus", "seq"]),
        Index(
            value = [
                "cloudBusinessId",
                "entityType",
                "remoteEntityId",
                "applicationStatus",
                "seq",
            ],
        ),
    ],
)
data class RemoteCatalogChangeEntity(
    val cloudBusinessId: String,
    val seq: Long,
    val entityType: String,
    val remoteEntityId: String,
    val remoteVersion: Long,
    val mutation: String,
    val snapshotPayload: String,
    val snapshotSha256: String,
    val receiptId: String,
    val syncedAtMillis: Long?,
    /** Origen cerrado; nunca se persiste UID, token, correo ni identificador del dispositivo. */
    val origin: String = "CLOUD",
    val receivedAt: Long,
    /** Proyección local mutable; el hecho remoto anterior permanece intacto. */
    val applicationStatus: String = "PENDING",
    val localEntityId: String? = null,
    val localVersion: Long? = null,
    val localSnapshotPayload: String? = null,
    val conflictCode: String? = null,
    val conflictDetectedAt: Long? = null,
    val resolution: String? = null,
    val resolvedAt: Long? = null,
) {
    init {
        requireCanonicalUuid(cloudBusinessId, "cloudBusinessId")
        require(seq in 1..MAX_SAFE_SYNC_SEQUENCE)
        require(entityType == "PRODUCT" || entityType == "SUPPLIER")
        requireCanonicalUuid(remoteEntityId, "remoteEntityId")
        require(remoteVersion >= 1L)
        require(mutation == "UPSERT")
        requireText(snapshotPayload, "snapshotPayload", 64_000)
        require(snapshotSha256.matches(SHA256))
        requireText(receiptId, "receiptId", 256)
        require(syncedAtMillis == null || syncedAtMillis >= 0L)
        require(origin == "CLOUD")
        require(receivedAt >= 0L)
        require(applicationStatus in APPLICATION_STATUSES)
        require((localEntityId == null) == (localVersion == null))
        localEntityId?.let { requireCanonicalUuid(it, "localEntityId") }
        require(localVersion == null || localVersion >= 1L)
        localSnapshotPayload?.let { requireText(it, "localSnapshotPayload", 64_000) }
        require(conflictCode == null || conflictCode in CONFLICT_CODES)
        require(conflictDetectedAt == null || conflictDetectedAt >= receivedAt)
        require(resolution == null || resolution in RESOLUTIONS)
        require(resolvedAt == null || resolvedAt >= (conflictDetectedAt ?: receivedAt))
        when (applicationStatus) {
            "PENDING" -> require(
                localEntityId == null && localSnapshotPayload == null && conflictCode == null &&
                    conflictDetectedAt == null && resolution == null && resolvedAt == null,
            )
            "APPLIED" -> require(
                localEntityId != null && conflictCode == null && conflictDetectedAt == null &&
                    resolution == null && resolvedAt == null,
            )
            "CONFLICT" -> require(
                conflictCode != null && conflictDetectedAt != null && resolution == null &&
                    resolvedAt == null,
            )
            "RESOLVED" -> require(
                conflictCode != null && conflictDetectedAt != null && resolution != null &&
                    resolvedAt != null,
            )
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val APPLICATION_STATUSES = setOf("PENDING", "APPLIED", "CONFLICT", "RESOLVED")
        val CONFLICT_CODES = setOf(
            "VERSION_MISMATCH",
            "SEMANTIC_KEY_COLLISION",
            "REFERENCE_MISSING",
            "REFERENCE_AMBIGUOUS",
            "LOCAL_CHANGES",
            "INVALID_PAYLOAD",
            "INTEGRITY_MISMATCH",
        )
        val RESOLUTIONS = setOf("KEEP_LOCAL", "APPLY_REMOTE")
    }
}
