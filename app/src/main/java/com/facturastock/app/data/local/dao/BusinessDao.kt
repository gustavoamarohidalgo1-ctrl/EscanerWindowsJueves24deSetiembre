package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.BusinessEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(business: BusinessEntity)

    @Update
    suspend fun update(business: BusinessEntity): Int

    @Query("SELECT * FROM businesses WHERE businessId = :businessId")
    suspend fun findById(businessId: String): BusinessEntity?

    @Query("SELECT * FROM businesses WHERE businessId = :businessId")
    fun observeById(businessId: String): Flow<BusinessEntity?>

    @Query("SELECT * FROM businesses WHERE ruc = :ruc")
    suspend fun findByRuc(ruc: String): BusinessEntity?

    /** Recuperación acotada de onboardings legacy sin RUC; dos filas significan ambigüedad. */
    @Query(
        "SELECT * FROM businesses WHERE ruc IS NULL AND legalName = :legalName " +
            "ORDER BY businessId ASC LIMIT 2",
    )
    suspend fun findWithoutRucByLegalName(legalName: String): List<BusinessEntity>

    @Query("SELECT * FROM businesses ORDER BY legalName")
    fun observeAll(): Flow<List<BusinessEntity>>

    @Query("SELECT COUNT(*) FROM businesses")
    suspend fun count(): Int

    @Query("DELETE FROM businesses WHERE businessId = :businessId")
    suspend fun deleteById(businessId: String): Int
}
