package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCanonicalUuidOrNull
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireText
import com.facturastock.app.domain.model.AuditEventType

/** Evento durable de auditoría asociado opcionalmente a una compra. */
@Entity(
    tableName = "audit_events",
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
        Index(value = ["businessId", "occurredAt", "auditEventId"]),
        Index(value = ["purchaseId", "occurredAt", "auditEventId"]),
        Index(
            value = ["businessId", "entityType", "entityId", "occurredAt", "auditEventId"],
        ),
    ],
)
data class AuditEventEntity(
    @PrimaryKey val auditEventId: String,
    val businessId: String,
    val purchaseId: String? = null,
    val eventType: String,
    val entityType: String,
    val entityId: String,
    val payload: String,
    val occurredAt: Long,
) {
    init {
        requireCanonicalUuid(auditEventId, "auditEventId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuidOrNull(purchaseId, "purchaseId")
        requireEnumName<AuditEventType>(eventType, "eventType")
        requireText(entityType, "entityType", 128)
        requireCanonicalUuid(entityId, "entityId")
        requireText(payload, "payload", 1_000_000)
        require(occurredAt >= 0L) { "occurredAt no puede ser negativo: $occurredAt" }
    }
}
