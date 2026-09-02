package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.ParsedInvoiceResultEntity

@Dao
interface ParsedInvoiceDao {
    @Query("SELECT * FROM invoice_parsed_results WHERE draftId = :draftId")
    suspend fun findByDraftId(draftId: String): ParsedInvoiceResultEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(result: ParsedInvoiceResultEntity)

    /**
     * CAS final de publicación. Las inserciones del audit trail y de las líneas se ejecutan en
     * la misma transacción y se revierten si este UPDATE no reclama exactamente el snapshot.
     */
    @Query(
        "UPDATE invoice_drafts SET " +
            "status = :needsReviewStatus, supplierId = :supplierId, " +
            "supplierRucRaw = :supplierRucRaw, supplierRucNormalized = :supplierRucNormalized, " +
            "supplierLegalNameRaw = :supplierLegalNameRaw, " +
            "supplierLegalNameNormalized = :supplierLegalNameNormalized, " +
            "documentType = :documentType, " +
            "documentNumberRaw = :documentNumberRaw, " +
            "documentNumberNormalized = :documentNumberNormalized, " +
            "issueDateRaw = :issueDateRaw, issueDateNormalized = :issueDateNormalized, " +
            "currencyCode = :currencyCode, subtotalMinorUnits = :subtotalMinorUnits, " +
            "taxMinorUnits = :taxMinorUnits, otherChargesMinorUnits = :otherChargesMinorUnits, " +
            "totalMinorUnits = :totalMinorUnits, " +
            "headerConfidence = :headerConfidence, activeOcrRunId = NULL, " +
            "lastError = NULL, updatedAt = :updatedAt " +
            "WHERE draftId = :draftId AND businessId = :businessId " +
            "AND status = :ocrReadyStatus AND activeOcrRunId IS NULL " +
            "AND confirmedPurchaseId IS NULL AND EXISTS (" +
            "SELECT 1 FROM invoice_ocr_snapshots snapshot " +
            "WHERE snapshot.draftId = :draftId AND snapshot.runId = :runId)",
    )
    suspend fun publishProjectionIfReady(
        draftId: String,
        runId: String,
        businessId: String,
        ocrReadyStatus: String,
        needsReviewStatus: String,
        supplierId: String?,
        supplierRucRaw: String?,
        supplierRucNormalized: String?,
        supplierLegalNameRaw: String?,
        supplierLegalNameNormalized: String?,
        documentType: String?,
        documentNumberRaw: String?,
        documentNumberNormalized: String?,
        issueDateRaw: String?,
        issueDateNormalized: String?,
        currencyCode: String?,
        subtotalMinorUnits: Long?,
        taxMinorUnits: Long?,
        otherChargesMinorUnits: Long?,
        totalMinorUnits: Long?,
        headerConfidence: Int?,
        updatedAt: Long,
    ): Int
}
