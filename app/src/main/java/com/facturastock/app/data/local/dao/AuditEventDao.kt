package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow

/** Proyección de timeline: el payload potencialmente grande se queda en Room. */
data class PurchaseAuditReadRow(
    val auditEventId: String,
    val eventType: String,
    val entityType: String,
    val entityId: String,
    val occurredAt: Long,
)

data class BusinessAuditReadRow(
    val auditEventId: String,
    val purchaseId: String?,
    val eventType: String,
    val entityType: String,
    val entityId: String,
    val occurredAt: Long,
)

/** Bitácora append-only: los hechos se corrigen con un evento posterior, nunca sobrescribiéndolos. */
@Dao
interface AuditEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AuditEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(events: List<AuditEventEntity>)

    @Query(
        "SELECT * FROM audit_events WHERE businessId = :businessId " +
            "AND purchaseId = :purchaseId " +
            "ORDER BY occurredAt ASC, auditEventId ASC",
    )
    suspend fun listForPurchase(
        businessId: String,
        purchaseId: String,
    ): List<AuditEventEntity>

    @Query(
        "SELECT auditEventId, eventType, entityType, entityId, occurredAt " +
            "FROM audit_events WHERE businessId = :businessId " +
            "AND purchaseId = :purchaseId " +
            "ORDER BY occurredAt ASC, auditEventId ASC",
    )
    fun observeForPurchase(
        businessId: String,
        purchaseId: String,
    ): Flow<List<PurchaseAuditReadRow>>

    @Query(
        "SELECT auditEventId, purchaseId, eventType, entityType, entityId, occurredAt " +
            "FROM audit_events WHERE businessId = :businessId " +
            "ORDER BY occurredAt ASC, auditEventId ASC",
    )
    suspend fun listReadEventsForBusiness(businessId: String): List<BusinessAuditReadRow>

    @Query(
        "SELECT * FROM audit_events WHERE businessId = :businessId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "ORDER BY occurredAt ASC, auditEventId ASC",
    )
    suspend fun listForEntity(
        businessId: String,
        entityType: String,
        entityId: String,
    ): List<AuditEventEntity>
}
