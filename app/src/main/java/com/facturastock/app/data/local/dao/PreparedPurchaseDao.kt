package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PreparedPurchaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(purchase: PreparedPurchaseEntity)

    @Query("SELECT * FROM prepared_purchases WHERE draftId = :draftId")
    suspend fun find(draftId: String): PreparedPurchaseEntity?

    @Query("SELECT * FROM prepared_purchases WHERE draftId = :draftId")
    fun observe(draftId: String): Flow<PreparedPurchaseEntity?>

    @Query("DELETE FROM prepared_purchases WHERE draftId = :draftId")
    suspend fun deleteForDraft(draftId: String): Int

    /** CAS de publicación: solo un borrador en revisión editable puede pasar a READY_TO_POST. */
    @Query(
        "UPDATE invoice_drafts SET status = :readyStatus, updatedAt = :updatedAt " +
            "WHERE draftId = :draftId AND status = :reviewStatus " +
            "AND activeOcrRunId IS NULL AND confirmedPurchaseId IS NULL",
    )
    suspend fun markReadyToPostIfReviewing(
        draftId: String,
        reviewStatus: String,
        readyStatus: String,
        updatedAt: Long,
    ): Int

    /** CAS de reapertura: solo un borrador preparado puede volver a edición. */
    @Query(
        "UPDATE invoice_drafts SET status = :reviewStatus, updatedAt = :updatedAt " +
            "WHERE draftId = :draftId AND status = :readyStatus",
    )
    suspend fun reopenIfReady(
        draftId: String,
        readyStatus: String,
        reviewStatus: String,
        updatedAt: Long,
    ): Int
}
