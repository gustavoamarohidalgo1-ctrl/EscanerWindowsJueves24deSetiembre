package com.facturastock.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCanonicalUuidOrNull
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.OutboxOperationStatus

/** Operación local durable para procesamiento eventual e idempotente. */
@Entity(
    tableName = "outbox_operations",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["purchaseId"],
            childColumns = ["purchaseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["businessId", "status", "nextAttemptAt"]),
        Index(value = ["purchaseId", "createdAt", "operationId"]),
        Index(value = ["entityType", "entityId", "entityVersion"]),
        Index(value = ["targetCloudBusinessId", "status", "nextAttemptAt"]),
        Index(value = ["targetCloudBusinessId", "status", "completedAt", "createdAt"]),
    ],
)
data class OutboxOperationEntity(
    @PrimaryKey val operationId: String,
    val businessId: String,
    val purchaseId: String? = null,
    val idempotencyKey: String,
    val operationType: String,
    val payload: String,
    val status: String = OutboxOperationStatus.PENDING.name,
    val attemptCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val nextAttemptAt: Long? = null,
    val completedAt: Long? = null,
    val lastError: String? = null,
    val payloadVersion: Int = 1,
    val claimToken: String? = null,
    val claimLeaseUntil: Long? = null,
    /**
     * Identificadores del registro remoto que chocó (solo IDs; nunca contenido). Solo tienen
     * sentido en CONFLICT y se conservan al pasar a RESOLVED como contexto de la decisión.
     */
    val conflictRemotePurchaseId: String? = null,
    val conflictReceiptId: String? = null,
    /** Comparación cloud genérica para conflictos optimistas de catálogo. */
    val conflictRemoteEntityId: String? = null,
    val conflictRemoteVersion: Long? = null,
    val conflictRemoteSnapshotPayload: String? = null,
    val conflictRemoteSyncedAt: Long? = null,
    val conflictRemoteOrigin: String? = null,
    /** Destino cloud validado que produjo el conflicto; evita enlazar contra otra cuenta. */
    val conflictCloudBusinessId: String? = null,
    /** Tipo e ID estables del agregado; permiten usar la misma outbox para catálogo. */
    @ColumnInfo(defaultValue = "'PURCHASE'")
    val entityType: String = "PURCHASE",
    @ColumnInfo(defaultValue = "''")
    val entityId: String = purchaseId ?: operationId,
    /** Versión optimista del agregado de catálogo; distinta de [payloadVersion]. */
    @ColumnInfo(defaultValue = "1")
    val entityVersion: Long = 1,
    /** UUID canónico del agregado cloud cuando difiere de [entityId] local. */
    val remoteEntityId: String? = null,
    /** Tenant cloud fijado por Room; nunca se deriva del enlace de sesión al enviar. */
    val targetCloudBusinessId: String? = null,
) {
    init {
        requireCanonicalUuid(operationId, "operationId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuidOrNull(purchaseId, "purchaseId")
        requireText(idempotencyKey, "idempotencyKey", 256)
        require(idempotencyKey == idempotencyKey.trim()) {
            "idempotencyKey no puede tener espacios exteriores"
        }
        requireText(operationType, "operationType", 128)
        requireText(payload, "payload", 1_000_000)
        requireEnumName<OutboxOperationStatus>(status, "status")
        require(attemptCount >= 0) { "attemptCount no puede ser negativo: $attemptCount" }
        requireTimestamps(createdAt, updatedAt)
        require(nextAttemptAt == null || nextAttemptAt >= 0L) {
            "nextAttemptAt no puede ser negativo"
        }
        require(completedAt == null || completedAt in createdAt..updatedAt) {
            "completedAt debe estar entre createdAt y updatedAt"
        }
        requireTextOrNull(lastError, "lastError", 2_000)
        require((status == OutboxOperationStatus.COMPLETED.name) == (completedAt != null)) {
            "completedAt debe existir exactamente cuando status es COMPLETED"
        }
        require(payloadVersion >= 1) { "payloadVersion debe ser >= 1: $payloadVersion" }
        requireCanonicalUuidOrNull(claimToken, "claimToken")
        require(claimLeaseUntil == null || claimLeaseUntil >= 0L) {
            "claimLeaseUntil no puede ser negativo"
        }
        require((claimToken == null) == (claimLeaseUntil == null)) {
            "claimToken y claimLeaseUntil deben coexistir"
        }
        requireTextOrNull(conflictRemotePurchaseId, "conflictRemotePurchaseId", 128)
        requireTextOrNull(conflictReceiptId, "conflictReceiptId", 256)
        requireCanonicalUuidOrNull(conflictRemoteEntityId, "conflictRemoteEntityId")
        require(conflictRemoteVersion == null || conflictRemoteVersion >= 1L)
        requireTextOrNull(
            conflictRemoteSnapshotPayload,
            "conflictRemoteSnapshotPayload",
            64_000,
        )
        require(conflictRemoteSyncedAt == null || conflictRemoteSyncedAt >= 0L)
        require(conflictRemoteOrigin == null || conflictRemoteOrigin == "CLOUD")
        requireCanonicalUuidOrNull(conflictCloudBusinessId, "conflictCloudBusinessId")
        require(entityType in ENTITY_TYPES) { "entityType no soportado: $entityType" }
        requireCanonicalUuid(entityId, "entityId")
        require(entityVersion >= 1L) { "entityVersion debe ser positiva" }
        requireCanonicalUuidOrNull(remoteEntityId, "remoteEntityId")
        requireCanonicalUuidOrNull(targetCloudBusinessId, "targetCloudBusinessId")
        require(remoteEntityId == null || entityType in CATALOG_ENTITY_TYPES) {
            "remoteEntityId solo corresponde a catálogo"
        }
        when (operationType) {
            "SYNC_PURCHASE" -> require(
                entityType == "PURCHASE" && purchaseId != null && purchaseId == entityId &&
                    entityVersion == 1L,
            ) { "SYNC_PURCHASE exige PURCHASE, purchaseId coincidente y versión 1" }
            "SYNC_PURCHASE_VOID" -> require(
                entityType == "PURCHASE" && purchaseId != null && purchaseId == entityId &&
                    entityVersion == 2L,
            ) { "SYNC_PURCHASE_VOID exige PURCHASE, purchaseId coincidente y versión 2" }
            "SYNC_PRODUCT" -> require(
                entityType == "PRODUCT" && purchaseId == null,
            ) { "SYNC_PRODUCT exige PRODUCT sin purchaseId" }
            "SYNC_SUPPLIER" -> require(
                entityType == "SUPPLIER" && purchaseId == null,
            ) { "SYNC_SUPPLIER exige SUPPLIER sin purchaseId" }
            "SYNC_DOCUMENT_UPLOAD" -> require(
                entityType == "DOCUMENT" && purchaseId != null && entityVersion == 1L,
            ) { "SYNC_DOCUMENT_UPLOAD exige DOCUMENT, purchaseId y versión 1" }
            "SYNC_DOCUMENT_PURGE" -> require(
                entityType == "DOCUMENT" && purchaseId != null && entityVersion == 2L,
            ) { "SYNC_DOCUMENT_PURGE exige DOCUMENT, purchaseId y versión 2" }
            else -> error("operationType no soportado: $operationType")
        }
        require(
            (
                conflictRemotePurchaseId == null && conflictReceiptId == null &&
                    conflictRemoteEntityId == null && conflictRemoteVersion == null &&
                    conflictRemoteSnapshotPayload == null && conflictRemoteSyncedAt == null &&
                    conflictRemoteOrigin == null && conflictCloudBusinessId == null
                ) ||
                status == OutboxOperationStatus.CONFLICT.name ||
                status == OutboxOperationStatus.RESOLVED.name,
        ) {
            "Los identificadores de conflicto solo existen en CONFLICT o RESOLVED"
        }
        require(
            conflictRemoteEntityId == null ||
                (
                    conflictRemoteVersion != null && conflictRemoteSnapshotPayload != null &&
                        conflictRemoteOrigin == "CLOUD" && conflictCloudBusinessId != null
                    ),
        ) { "El conflicto remoto de catálogo debe ser una comparación completa" }
    }

    private companion object {
        val ENTITY_TYPES = setOf("PURCHASE", "PRODUCT", "SUPPLIER", "DOCUMENT")
        val CATALOG_ENTITY_TYPES = setOf("PRODUCT", "SUPPLIER")
    }
}
