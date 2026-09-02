package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.InvoiceHeaderEditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceHeaderEditDao {
    @Query("SELECT * FROM invoice_header_edits WHERE draftId = :draftId")
    suspend fun findByDraftId(draftId: String): InvoiceHeaderEditEntity?

    @Query("SELECT * FROM invoice_header_edits WHERE draftId = :draftId")
    fun observeByDraftId(draftId: String): Flow<InvoiceHeaderEditEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(edit: InvoiceHeaderEditEntity)

    @Update
    suspend fun update(edit: InvoiceHeaderEditEntity): Int

    @Query("DELETE FROM invoice_header_edits WHERE draftId = :draftId")
    suspend fun deleteForDraft(draftId: String): Int
}
