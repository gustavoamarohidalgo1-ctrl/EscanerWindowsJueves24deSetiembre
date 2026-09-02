package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.SupplierEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(supplier: SupplierEntity)

    /**
     * CAS por versión optimista: escribe solo si la fila sigue en la versión que el llamador
     * leyó y la incrementa en la misma sentencia. Cero filas significa edición concurrente
     * perdida; nunca es un error. `businessId` y `createdAt` no cambian.
     */
    @Query(
        "UPDATE suppliers SET legalName = :legalName, ruc = :ruc, tradeName = :tradeName, " +
            "status = :status, version = version + 1, updatedAt = :updatedAt " +
            "WHERE supplierId = :supplierId AND version = :expectedVersion",
    )
    suspend fun update(
        supplierId: String,
        legalName: String,
        ruc: String?,
        tradeName: String?,
        status: String,
        expectedVersion: Long,
        updatedAt: Long,
    ): Int

    /** Aplicación de una versión cloud ya conciliada; no crea un eco en la outbox local. */
    @Query(
        "UPDATE suppliers SET legalName = :legalName, ruc = :ruc, tradeName = :tradeName, " +
            "status = :status, version = :remoteVersion, createdAt = :createdAt, " +
            "updatedAt = :updatedAt " +
            "WHERE supplierId = :supplierId AND businessId = :businessId " +
            "AND version = :expectedLocalVersion",
    )
    suspend fun applyRemoteVersion(
        supplierId: String,
        businessId: String,
        legalName: String,
        ruc: String?,
        tradeName: String?,
        status: String,
        expectedLocalVersion: Long,
        remoteVersion: Long,
        createdAt: Long,
        updatedAt: Long,
    ): Int

    /** KEEP_LOCAL rebasa la versión causal sin cambiar los datos elegidos por la persona. */
    @Query(
        "UPDATE suppliers SET version = :rebasedVersion, updatedAt = :updatedAt " +
            "WHERE supplierId = :supplierId AND businessId = :businessId " +
            "AND version = :expectedLocalVersion",
    )
    suspend fun rebaseVersion(
        supplierId: String,
        businessId: String,
        expectedLocalVersion: Long,
        rebasedVersion: Long,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM suppliers WHERE supplierId = :supplierId")
    suspend fun findById(supplierId: String): SupplierEntity?

    @Query("SELECT * FROM suppliers WHERE businessId = :businessId AND ruc = :ruc")
    suspend fun findByRuc(businessId: String, ruc: String): SupplierEntity?

    @Query(
        "SELECT * FROM suppliers " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(legalName) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(tradeName) LIKE :pattern ESCAPE '\\' OR " +
            "ruc LIKE :pattern ESCAPE '\\') " +
            "ORDER BY LOWER(legalName), supplierId LIMIT :limit OFFSET :offset",
    )
    suspend fun searchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): List<SupplierEntity>

    @Query(
        "SELECT * FROM suppliers " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(legalName) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(tradeName) LIKE :pattern ESCAPE '\\' OR " +
            "ruc LIKE :pattern ESCAPE '\\') " +
            "ORDER BY LOWER(legalName), supplierId LIMIT :limit OFFSET :offset",
    )
    fun observeSearchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<SupplierEntity>>

    @Query(
        "SELECT COUNT(*) FROM suppliers " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(legalName) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(tradeName) LIKE :pattern ESCAPE '\\' OR " +
            "ruc LIKE :pattern ESCAPE '\\')",
    )
    suspend fun countSearch(businessId: String, pattern: String, status: String?): Int

    @Query(
        "SELECT COUNT(*) FROM suppliers " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "LOWER(legalName) LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(tradeName) LIKE :pattern ESCAPE '\\' OR " +
            "ruc LIKE :pattern ESCAPE '\\')",
    )
    fun observeCountSearch(
        businessId: String,
        pattern: String,
        status: String?,
    ): Flow<Int>

    @Query("SELECT * FROM suppliers WHERE businessId = :businessId ORDER BY legalName")
    fun observeForBusiness(businessId: String): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE businessId = :businessId ORDER BY supplierId")
    suspend fun listForBusiness(businessId: String): List<SupplierEntity>

    @Query("SELECT COUNT(*) FROM suppliers WHERE businessId = :businessId")
    suspend fun countForBusiness(businessId: String): Int

    /** Archivo/restauración con el mismo CAS y avance de versión que una edición normal. */
    @Query(
        "UPDATE suppliers SET status = :status, version = version + 1, updatedAt = :updatedAt " +
            "WHERE supplierId = :supplierId AND status != :status AND version = :expectedVersion",
    )
    suspend fun setStatus(
        supplierId: String,
        status: String,
        expectedVersion: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM suppliers WHERE supplierId = :supplierId")
    suspend fun deleteById(supplierId: String): Int
}

/**
 * Conveniencia entidad → CAS: la `version` que trae la entidad es la versión esperada (la que
 * el llamador leyó); el incremento ocurre en SQL. Room no enlaza campos de entidad en `@Query`,
 * así que la forma explícita vive en el DAO y esta extensión conserva la llamada natural.
 */
suspend fun SupplierDao.updateCas(supplier: SupplierEntity): Int = update(
    supplierId = supplier.supplierId,
    legalName = supplier.legalName,
    ruc = supplier.ruc,
    tradeName = supplier.tradeName,
    status = supplier.status,
    expectedVersion = supplier.version,
    updatedAt = supplier.updatedAt,
)
