package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierProductAliasDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alias: SupplierProductAliasEntity)

    @Update
    suspend fun update(alias: SupplierProductAliasEntity): Int

    @Query("SELECT * FROM supplier_product_aliases WHERE aliasId = :aliasId")
    suspend fun findById(aliasId: String): SupplierProductAliasEntity?

    @Query(
        "SELECT CASE WHEN " +
            "EXISTS(SELECT 1 FROM suppliers WHERE supplierId = :supplierId " +
            "AND businessId = :businessId) AND " +
            "EXISTS(SELECT 1 FROM products WHERE productId = :productId " +
            "AND businessId = :businessId) THEN 1 ELSE 0 END",
    )
    suspend fun referencesBelongToBusiness(
        businessId: String,
        supplierId: String,
        productId: String,
    ): Boolean

    @Query(
        "SELECT * FROM supplier_product_aliases " +
            "WHERE businessId = :businessId AND aliasNormalized = :aliasNormalized",
    )
    suspend fun findByNormalizedAlias(
        businessId: String,
        aliasNormalized: String,
    ): List<SupplierProductAliasEntity>

    @Query("SELECT * FROM supplier_product_aliases WHERE productId = :productId ORDER BY alias")
    fun observeForProduct(productId: String): Flow<List<SupplierProductAliasEntity>>

    @Query("SELECT * FROM supplier_product_aliases WHERE productId = :productId ORDER BY alias")
    suspend fun listForProduct(productId: String): List<SupplierProductAliasEntity>

    @Query("SELECT COUNT(*) FROM supplier_product_aliases WHERE businessId = :businessId")
    suspend fun countForBusiness(businessId: String): Int

    @Query("SELECT COUNT(*) FROM supplier_product_aliases WHERE productId = :productId")
    suspend fun countForProduct(productId: String): Int

    @Query("DELETE FROM supplier_product_aliases WHERE aliasId = :aliasId")
    suspend fun deleteById(aliasId: String): Int
}
