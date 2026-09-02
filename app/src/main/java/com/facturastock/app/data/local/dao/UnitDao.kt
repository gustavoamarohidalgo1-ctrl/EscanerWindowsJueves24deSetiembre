package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.UnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UnitDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(unit: UnitEntity)

    @Update
    suspend fun update(unit: UnitEntity): Int

    @Query("SELECT * FROM units WHERE unitId = :unitId")
    suspend fun findById(unitId: String): UnitEntity?

    /** Lectura acotada por las 100 líneas activas máximas de una revisión. */
    @Query("SELECT * FROM units WHERE unitId IN (:unitIds)")
    suspend fun findByIds(unitIds: List<String>): List<UnitEntity>

    @Query("SELECT * FROM units WHERE businessId = :businessId AND code = :code")
    suspend fun findByCode(businessId: String, code: String): UnitEntity?

    @Query(
        "SELECT * FROM units WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(code) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(name) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(symbol) LIKE :pattern ESCAPE '\\') " +
            "ORDER BY code, unitId LIMIT :limit OFFSET :offset",
    )
    suspend fun searchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): List<UnitEntity>

    @Query(
        "SELECT * FROM units WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(code) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(name) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(symbol) LIKE :pattern ESCAPE '\\') " +
            "ORDER BY code, unitId LIMIT :limit OFFSET :offset",
    )
    fun observeSearchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<UnitEntity>>

    @Query(
        "SELECT COUNT(*) FROM units WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(code) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(name) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(symbol) LIKE :pattern ESCAPE '\\')",
    )
    suspend fun countSearch(businessId: String, pattern: String, status: String?): Int

    @Query(
        "SELECT COUNT(*) FROM units WHERE businessId = :businessId " +
            "AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(code) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(name) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(symbol) LIKE :pattern ESCAPE '\\')",
    )
    fun observeCountSearch(
        businessId: String,
        pattern: String,
        status: String?,
    ): Flow<Int>

    @Query("SELECT * FROM units WHERE businessId = :businessId ORDER BY code")
    fun observeForBusiness(businessId: String): Flow<List<UnitEntity>>

    @Query("SELECT COUNT(*) FROM units WHERE businessId = :businessId")
    suspend fun countForBusiness(businessId: String): Int

    @Query("SELECT unitId FROM units WHERE businessId = :businessId AND unitId IN (:ids)")
    suspend fun existingIdsForBusiness(businessId: String, ids: List<String>): List<String>

    @Query(
        "UPDATE units SET status = :status, updatedAt = :updatedAt " +
            "WHERE unitId = :unitId AND status != :status",
    )
    suspend fun setStatus(unitId: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM units WHERE unitId = :unitId")
    suspend fun deleteById(unitId: String): Int
}
