package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity

@Dao
interface CloudBusinessBindingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(binding: CloudBusinessBindingEntity)

    @Query(
        "SELECT * FROM cloud_business_bindings WHERE localBusinessId = :localBusinessId",
    )
    suspend fun findByLocal(localBusinessId: String): CloudBusinessBindingEntity?

    @Query(
        "SELECT * FROM cloud_business_bindings WHERE cloudBusinessId = :cloudBusinessId",
    )
    suspend fun findByCloud(cloudBusinessId: String): CloudBusinessBindingEntity?
}
