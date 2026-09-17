package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.SaleVoidEntity

@Dao
interface SaleVoidDao {
    @Query("SELECT * FROM sale_voids WHERE businessId = :businessId AND saleId = :saleId")
    suspend fun findBySaleId(
        businessId: String,
        saleId: String,
    ): SaleVoidEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: SaleVoidEntity)
}
