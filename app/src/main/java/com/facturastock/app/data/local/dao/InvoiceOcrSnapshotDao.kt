package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotPageEntity

@Dao
interface InvoiceOcrSnapshotDao {
    @Query("SELECT * FROM invoice_ocr_snapshots WHERE draftId = :draftId")
    suspend fun findHeader(draftId: String): InvoiceOcrSnapshotEntity?

    @Query(
        "SELECT * FROM invoice_ocr_snapshot_pages " +
            "WHERE draftId = :draftId ORDER BY pageIndex ASC",
    )
    suspend fun findPages(draftId: String): List<InvoiceOcrSnapshotPageEntity>

    @Query("DELETE FROM invoice_ocr_snapshots WHERE draftId = :draftId")
    suspend fun deleteByDraftId(draftId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHeader(header: InvoiceOcrSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPages(pages: List<InvoiceOcrSnapshotPageEntity>)

    /** CAS que autoriza la publicación; las inserciones posteriores comparten su transacción. */
    @Query(
        "UPDATE invoice_drafts SET status = :readyStatus, activeOcrRunId = NULL, " +
            "lastError = NULL, updatedAt = :updatedAt " +
            "WHERE draftId = :draftId AND status = :processingStatus " +
            "AND activeOcrRunId = :runId AND confirmedPurchaseId IS NULL",
    )
    suspend fun markReadyIfRunIsActive(
        draftId: String,
        runId: String,
        processingStatus: String,
        readyStatus: String,
        updatedAt: Long,
    ): Int
}
