package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceLinesEditDao {
    @Query("SELECT * FROM invoice_line_edits WHERE draftId = :draftId")
    suspend fun findByDraftId(draftId: String): InvoiceLinesEditEntity?

    @Query("SELECT * FROM invoice_line_edits WHERE draftId = :draftId")
    fun observeByDraftId(draftId: String): Flow<InvoiceLinesEditEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(edit: InvoiceLinesEditEntity)

    @Update
    suspend fun update(edit: InvoiceLinesEditEntity): Int

    @Query("DELETE FROM invoice_line_edits WHERE draftId = :draftId")
    suspend fun deleteForDraft(draftId: String): Int
}
