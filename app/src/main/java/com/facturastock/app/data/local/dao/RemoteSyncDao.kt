package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.facturastock.app.data.local.entity.RemoteCatalogChangeEntity
import com.facturastock.app.data.local.entity.RemoteMovementSummaryEntity
import com.facturastock.app.data.local.entity.RemotePurchaseChangeEntity
import com.facturastock.app.data.local.entity.RemoteSyncStateEntity
import kotlinx.coroutines.flow.Flow

/** Espejo Room append-only del pull y cursores que solo avanzan dentro de una transacción. */
@Dao
interface RemoteSyncDao {
    @Query("SELECT * FROM remote_sync_states WHERE cloudBusinessId = :cloudBusinessId")
    suspend fun findState(cloudBusinessId: String): RemoteSyncStateEntity?

    @Query("SELECT * FROM remote_sync_states WHERE cloudBusinessId = :cloudBusinessId")
    fun observeState(cloudBusinessId: String): Flow<RemoteSyncStateEntity?>

    @Upsert
    suspend fun upsertState(state: RemoteSyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPurchaseChange(change: RemotePurchaseChangeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPurchaseChanges(changes: List<RemotePurchaseChangeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovementSummaries(movements: List<RemoteMovementSummaryEntity>)

    @Query(
        "SELECT * FROM remote_purchase_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId AND seq = :seq",
    )
    suspend fun findPurchaseChange(
        cloudBusinessId: String,
        seq: Long,
    ): RemotePurchaseChangeEntity?

    @Query(
        "SELECT * FROM remote_movement_summaries " +
            "WHERE cloudBusinessId = :cloudBusinessId AND seq = :seq ORDER BY position ASC",
    )
    suspend fun listMovementSummaries(
        cloudBusinessId: String,
        seq: Long,
    ): List<RemoteMovementSummaryEntity>

    @Query(
        "SELECT * FROM remote_purchase_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId ORDER BY seq ASC",
    )
    suspend fun listPurchaseChanges(cloudBusinessId: String): List<RemotePurchaseChangeEntity>

    /** Carga todos los movimientos del tenant en una sola consulta para evitar N+1 al leer pull. */
    @Query(
        "SELECT * FROM remote_movement_summaries " +
            "WHERE cloudBusinessId = :cloudBusinessId ORDER BY seq ASC, position ASC",
    )
    suspend fun listMovementSummariesForBusiness(
        cloudBusinessId: String,
    ): List<RemoteMovementSummaryEntity>

    @Query(
        "SELECT * FROM remote_purchase_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId ORDER BY seq ASC",
    )
    fun observePurchaseChanges(
        cloudBusinessId: String,
    ): Flow<List<RemotePurchaseChangeEntity>>

    @Query(
        "SELECT * FROM remote_movement_summaries " +
            "WHERE cloudBusinessId = :cloudBusinessId ORDER BY seq ASC, position ASC",
    )
    fun observeMovementSummaries(
        cloudBusinessId: String,
    ): Flow<List<RemoteMovementSummaryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCatalogChange(change: RemoteCatalogChangeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCatalogChanges(changes: List<RemoteCatalogChangeEntity>): List<Long>

    @Query(
        "SELECT * FROM remote_catalog_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId AND seq = :seq",
    )
    suspend fun findCatalogChange(
        cloudBusinessId: String,
        seq: Long,
    ): RemoteCatalogChangeEntity?

    @Query(
        "SELECT * FROM remote_catalog_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId AND applicationStatus = 'PENDING' " +
            "ORDER BY seq ASC",
    )
    suspend fun listPendingCatalogChanges(
        cloudBusinessId: String,
    ): List<RemoteCatalogChangeEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM remote_catalog_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId AND entityType = :entityType " +
            "AND remoteEntityId = :remoteEntityId AND seq < :seq " +
            "AND applicationStatus = 'CONFLICT')",
    )
    suspend fun hasUnresolvedCatalogPredecessor(
        cloudBusinessId: String,
        entityType: String,
        remoteEntityId: String,
        seq: Long,
    ): Boolean

    @Query(
        "SELECT * FROM remote_catalog_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId AND entityType = :entityType " +
            "AND remoteEntityId = :remoteEntityId AND localEntityId IS NOT NULL " +
            "AND applicationStatus IN ('APPLIED','RESOLVED') " +
            "ORDER BY seq DESC LIMIT 1",
    )
    suspend fun findLatestCatalogMapping(
        cloudBusinessId: String,
        entityType: String,
        remoteEntityId: String,
    ): RemoteCatalogChangeEntity?

    @Query(
        "SELECT * FROM remote_catalog_changes " +
            "WHERE cloudBusinessId = :cloudBusinessId ORDER BY seq ASC",
    )
    fun observeCatalogChanges(
        cloudBusinessId: String,
    ): Flow<List<RemoteCatalogChangeEntity>>

    @Query(
        "UPDATE remote_catalog_changes SET applicationStatus = 'APPLIED', " +
            "localEntityId = :localEntityId, localVersion = :localVersion, " +
            "localSnapshotPayload = :localSnapshotPayload " +
            "WHERE cloudBusinessId = :cloudBusinessId AND seq = :seq " +
            "AND applicationStatus = 'PENDING'",
    )
    suspend fun markCatalogApplied(
        cloudBusinessId: String,
        seq: Long,
        localEntityId: String,
        localVersion: Long,
        localSnapshotPayload: String?,
    ): Int

    @Query(
        "UPDATE remote_catalog_changes SET applicationStatus = 'CONFLICT', " +
            "localEntityId = :localEntityId, localVersion = :localVersion, " +
            "localSnapshotPayload = :localSnapshotPayload, conflictCode = :conflictCode, " +
            "conflictDetectedAt = :detectedAt " +
            "WHERE cloudBusinessId = :cloudBusinessId AND seq = :seq " +
            "AND applicationStatus = 'PENDING'",
    )
    suspend fun markCatalogConflict(
        cloudBusinessId: String,
        seq: Long,
        localEntityId: String?,
        localVersion: Long?,
        localSnapshotPayload: String?,
        conflictCode: String,
        detectedAt: Long,
    ): Int

    @Query(
        "UPDATE remote_catalog_changes SET applicationStatus = 'RESOLVED', " +
            "resolution = :resolution, resolvedAt = :resolvedAt " +
            "WHERE cloudBusinessId = :cloudBusinessId AND seq = :seq " +
            "AND applicationStatus = 'CONFLICT'",
    )
    suspend fun resolveCatalogConflict(
        cloudBusinessId: String,
        seq: Long,
        resolution: String,
        resolvedAt: Long,
    ): Int
}
