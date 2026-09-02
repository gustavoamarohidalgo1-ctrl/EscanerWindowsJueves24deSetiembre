package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.CapturedPagePublicationEntity

@Dao
interface CapturedPagePublicationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(publication: CapturedPagePublicationEntity)

    @Query("SELECT * FROM captured_page_publications WHERE imageId = :imageId")
    suspend fun findByImageId(imageId: String): CapturedPagePublicationEntity?
}
