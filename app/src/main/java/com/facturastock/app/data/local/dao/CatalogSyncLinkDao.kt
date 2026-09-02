package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.CatalogSyncLinkEntity

@Dao
interface CatalogSyncLinkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(link: CatalogSyncLinkEntity)

    @Query(
        "SELECT * FROM catalog_sync_links WHERE localBusinessId = :localBusinessId " +
            "AND entityType = :entityType AND localEntityId = :localEntityId",
    )
    suspend fun findByLocal(
        localBusinessId: String,
        entityType: String,
        localEntityId: String,
    ): CatalogSyncLinkEntity?

    @Query(
        "SELECT * FROM catalog_sync_links WHERE cloudBusinessId = :cloudBusinessId " +
            "AND entityType = :entityType AND remoteEntityId = :remoteEntityId",
    )
    suspend fun findByRemote(
        cloudBusinessId: String,
        entityType: String,
        remoteEntityId: String,
    ): CatalogSyncLinkEntity?

    @Query(
        "SELECT DISTINCT cloudBusinessId FROM catalog_sync_links " +
            "WHERE localBusinessId = :localBusinessId ORDER BY cloudBusinessId",
    )
    suspend fun listCloudBusinessIds(localBusinessId: String): List<String>

    /** Solo la misma pareja puede avanzar; una remapeada exige conflicto explícito. */
    @Query(
        "UPDATE catalog_sync_links SET remoteVersion = MAX(remoteVersion, :remoteVersion), " +
            "updatedAt = MAX(updatedAt, :updatedAt) WHERE localBusinessId = :localBusinessId " +
            "AND cloudBusinessId = :cloudBusinessId AND entityType = :entityType " +
            "AND localEntityId = :localEntityId AND remoteEntityId = :remoteEntityId",
    )
    suspend fun advanceVersion(
        localBusinessId: String,
        cloudBusinessId: String,
        entityType: String,
        localEntityId: String,
        remoteEntityId: String,
        remoteVersion: Long,
        updatedAt: Long,
    ): Int

    /** El ACK no necesita conocer otra vez el cloud ID: la pareja local ya lo fijó. */
    @Query(
        "UPDATE catalog_sync_links SET remoteVersion = MAX(remoteVersion, :remoteVersion), " +
            "updatedAt = MAX(updatedAt, :updatedAt) WHERE localBusinessId = :localBusinessId " +
            "AND entityType = :entityType AND localEntityId = :localEntityId " +
            "AND remoteEntityId = :remoteEntityId",
    )
    suspend fun advanceVersionByLocal(
        localBusinessId: String,
        entityType: String,
        localEntityId: String,
        remoteEntityId: String,
        remoteVersion: Long,
        updatedAt: Long,
    ): Int
}
