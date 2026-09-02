package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceLineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(line: InvoiceLineEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(lines: List<InvoiceLineEntity>)

    @Update
    suspend fun update(line: InvoiceLineEntity)

    @Query("SELECT * FROM invoice_lines WHERE lineId = :lineId")
    suspend fun findById(lineId: String): InvoiceLineEntity?

    @Query("SELECT * FROM invoice_lines WHERE draftId = :draftId ORDER BY position")
    suspend fun listForDraft(draftId: String): List<InvoiceLineEntity>

    @Query("SELECT * FROM invoice_lines WHERE draftId = :draftId ORDER BY position")
    fun observeForDraft(draftId: String): Flow<List<InvoiceLineEntity>>

    @Query("SELECT COUNT(*) FROM invoice_lines WHERE draftId = :draftId")
    suspend fun countForDraft(draftId: String): Int

    @Query("DELETE FROM invoice_lines WHERE lineId = :lineId")
    suspend fun deleteById(lineId: String): Int

    @Query("DELETE FROM invoice_lines WHERE draftId = :draftId")
    suspend fun deleteForDraft(draftId: String): Int
}
