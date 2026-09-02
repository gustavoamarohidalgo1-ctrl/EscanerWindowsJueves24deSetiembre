package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryLocationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(location: InventoryLocationEntity)

    @Update
    suspend fun update(location: InventoryLocationEntity): Int

    @Query("SELECT * FROM inventory_locations WHERE locationId = :locationId")
    suspend fun findById(locationId: String): InventoryLocationEntity?

    @Query(
        "SELECT * FROM inventory_locations " +
            "WHERE businessId = :businessId AND name = :name",
    )
    suspend fun findByName(businessId: String, name: String): InventoryLocationEntity?

    /** Match cross-device por nombre trim/case-insensitive; más de uno es ambigüedad explícita. */
    @Query(
        "SELECT * FROM inventory_locations WHERE businessId = :businessId " +
            "AND LOWER(TRIM(name)) = LOWER(TRIM(:name)) ORDER BY locationId ASC LIMIT 2",
    )
    suspend fun findByCanonicalName(
        businessId: String,
        name: String,
    ): List<InventoryLocationEntity>

    @Query(
        "SELECT * FROM inventory_locations WHERE businessId = :businessId " +
            "ORDER BY LOWER(name), locationId",
    )
    suspend fun listForBusiness(businessId: String): List<InventoryLocationEntity>

    @Query(
        "SELECT * FROM inventory_locations WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) " +
            "AND LOWER(name) LIKE :pattern ESCAPE '\\' " +
            "ORDER BY LOWER(name), locationId LIMIT :limit OFFSET :offset",
    )
    suspend fun searchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): List<InventoryLocationEntity>

    @Query(
        "SELECT * FROM inventory_locations WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) " +
            "AND LOWER(name) LIKE :pattern ESCAPE '\\' " +
            "ORDER BY LOWER(name), locationId LIMIT :limit OFFSET :offset",
    )
    fun observeSearchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<InventoryLocationEntity>>

    @Query(
        "SELECT COUNT(*) FROM inventory_locations WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) " +
            "AND LOWER(name) LIKE :pattern ESCAPE '\\'",
    )
    suspend fun countSearch(businessId: String, pattern: String, status: String?): Int

    @Query(
        "SELECT COUNT(*) FROM inventory_locations WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) " +
            "AND LOWER(name) LIKE :pattern ESCAPE '\\'",
    )
    fun observeCountSearch(
        businessId: String,
        pattern: String,
        status: String?,
    ): Flow<Int>

    @Query("SELECT * FROM inventory_locations WHERE businessId = :businessId ORDER BY name")
    fun observeForBusiness(businessId: String): Flow<List<InventoryLocationEntity>>

    @Query("SELECT COUNT(*) FROM inventory_locations WHERE businessId = :businessId")
    suspend fun countForBusiness(businessId: String): Int

    @Query(
        "UPDATE inventory_locations SET status = :status, updatedAt = :updatedAt " +
            "WHERE locationId = :locationId AND status != :status",
    )
    suspend fun setStatus(locationId: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM inventory_locations WHERE locationId = :locationId")
    suspend fun deleteById(locationId: String): Int
}
