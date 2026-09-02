package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceImageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(image: InvoiceImageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(images: List<InvoiceImageEntity>)

    @Query(
        "UPDATE invoice_images SET " +
            "rotationDegrees = (rotationDegrees + 90) % 360, " +
            "cropLeftFraction = CASE WHEN cropBottomFraction IS NULL THEN NULL " +
            "ELSE 10000 - cropBottomFraction END, " +
            "cropTopFraction = cropLeftFraction, " +
            "cropRightFraction = CASE WHEN cropTopFraction IS NULL THEN NULL " +
            "ELSE 10000 - cropTopFraction END, " +
            "cropBottomFraction = cropRightFraction " +
            "WHERE imageId = :imageId",
    )
    suspend fun rotate90WithCrop(imageId: String): Int

    @Query(
        "UPDATE invoice_images SET " +
            "cropLeftFraction = :left, cropTopFraction = :top, " +
            "cropRightFraction = :right, cropBottomFraction = :bottom " +
            "WHERE imageId = :imageId",
    )
    suspend fun setCrop(
        imageId: String,
        left: Int?,
        top: Int?,
        right: Int?,
        bottom: Int?,
    ): Int

    @Query("SELECT * FROM invoice_images WHERE imageId = :imageId")
    suspend fun findById(imageId: String): InvoiceImageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM invoice_images WHERE filePath = :filePath)")
    suspend fun isPathReferenced(filePath: String): Boolean

    @Query("SELECT COUNT(*) FROM invoice_images WHERE filePath = :filePath")
    suspend fun countPathReferences(filePath: String): Int

    @Query("SELECT imageId FROM invoice_images WHERE filePath = :filePath ORDER BY imageId")
    suspend fun listImageIdsReferencingPath(filePath: String): List<String>

    @Query("SELECT * FROM invoice_images WHERE draftId = :draftId ORDER BY pageIndex")
    suspend fun listForDraft(draftId: String): List<InvoiceImageEntity>

    @Query(
        "SELECT * FROM invoice_images " +
            "WHERE draftId = :draftId AND pageIndex = :pageIndex",
    )
    suspend fun findByDraftAndPage(draftId: String, pageIndex: Int): InvoiceImageEntity?

    @Query("SELECT * FROM invoice_images WHERE businessId = :businessId AND sha256 = :sha256")
    suspend fun findByHash(businessId: String, sha256: String): List<InvoiceImageEntity>

    @Query("SELECT * FROM invoice_images WHERE draftId = :draftId ORDER BY pageIndex")
    fun observeForDraft(draftId: String): Flow<List<InvoiceImageEntity>>

    @Query(
        "SELECT i.* FROM invoice_images i " +
            "INNER JOIN invoice_drafts d ON d.draftId = i.draftId " +
            "WHERE i.draftId = :draftId AND i.businessId = :businessId " +
            "AND d.businessId = :businessId ORDER BY i.pageIndex, i.imageId",
    )
    fun observeForCommittedPurchase(
        businessId: String,
        draftId: String,
    ): Flow<List<InvoiceImageEntity>>

    @Query("SELECT COUNT(*) FROM invoice_images WHERE draftId = :draftId")
    suspend fun countForDraft(draftId: String): Int

    @Query("DELETE FROM invoice_images WHERE imageId = :imageId")
    suspend fun deleteById(imageId: String): Int

    @Query("DELETE FROM invoice_images WHERE draftId = :draftId")
    suspend fun deleteForDraft(draftId: String): Int

    @Query(
        "UPDATE invoice_images SET pageIndex = :pageIndex " +
            "WHERE imageId = :imageId",
    )
    suspend fun updatePageIndex(imageId: String, pageIndex: Int): Int
}
