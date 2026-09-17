package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDraftDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(draft: InvoiceDraftEntity)

    @Update
    suspend fun update(draft: InvoiceDraftEntity)

    @Query("SELECT * FROM invoice_drafts WHERE draftId = :draftId")
    suspend fun findById(draftId: String): InvoiceDraftEntity?

    @Query(
        "SELECT * FROM invoice_drafts " +
            "WHERE businessId = :businessId AND status = :status " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun listForBusinessByStatus(businessId: String, status: String): List<InvoiceDraftEntity>

    @Query("SELECT * FROM invoice_drafts WHERE draftId = :draftId")
    fun observeById(draftId: String): Flow<InvoiceDraftEntity?>

    @Query("SELECT * FROM invoice_drafts WHERE businessId = :businessId ORDER BY updatedAt DESC")
    fun observeForBusiness(businessId: String): Flow<List<InvoiceDraftEntity>>

    /**
     * Read-model de Home: Room limita antes de materializar entidades y resuelve el proveedor
     * en la misma consulta. El segundo predicado del JOIN evita exponer datos de otro tenant
     * incluso ante una fila legada inconsistente. `draftId` desempata el orden de forma estable.
     */
    @Query(
        "SELECT d.*, s.legalName AS supplierName FROM invoice_drafts d " +
            "LEFT JOIN suppliers s ON s.supplierId = d.supplierId " +
            "AND s.businessId = d.businessId " +
            "WHERE d.businessId = :businessId AND d.confirmedPurchaseId IS NULL " +
            "AND d.status != 'COMMITTED' " +
            "ORDER BY d.updatedAt DESC, d.draftId DESC LIMIT :limit",
    )
    fun observeRecentForBusiness(
        businessId: String,
        limit: Int,
    ): Flow<List<RecentDraftReadRow>>

    @Query(
        "SELECT COUNT(*) FROM invoice_drafts " +
            "WHERE businessId = :businessId AND confirmedPurchaseId IS NULL " +
            "AND status != 'COMMITTED'",
    )
    fun observeOpenCountForBusiness(businessId: String): Flow<Int>

    @Query(
        "SELECT * FROM invoice_drafts " +
            "WHERE businessId = :businessId AND status = :status " +
            "ORDER BY updatedAt DESC",
    )
    fun observeForBusinessByStatus(
        businessId: String,
        status: String,
    ): Flow<List<InvoiceDraftEntity>>

    @Query("UPDATE invoice_drafts SET updatedAt = :updatedAt WHERE draftId = :draftId")
    suspend fun touch(draftId: String, updatedAt: Long)

    /**
     * Último paso de cualquier mutación de páginas. La imagen ya modificada decide si el flujo
     * vuelve a CAPTURED o a CREATED y se vacía toda proyección que dependía del OCR anterior.
     * El predicado terminal hace que una confirmación concurrente gane sin permitir reapertura;
     * el MAX impide retroceder el reloj si `updatedAt` se obtuvo antes de esperar la transacción.
     */
    @Query(
        "UPDATE invoice_drafts SET status = CASE WHEN EXISTS (" +
            "SELECT 1 FROM invoice_images i WHERE i.draftId = :draftId" +
            ") THEN :capturedStatus ELSE :createdStatus END, " +
            "supplierId = NULL, supplierRucRaw = NULL, supplierRucNormalized = NULL, " +
            "supplierLegalNameRaw = NULL, supplierLegalNameNormalized = NULL, " +
            "documentType = NULL, documentNumberRaw = NULL, documentNumberNormalized = NULL, " +
            "issueDateRaw = NULL, issueDateNormalized = NULL, currencyCode = NULL, " +
            "subtotalMinorUnits = NULL, taxMinorUnits = NULL, otherChargesMinorUnits = NULL, " +
            "totalMinorUnits = NULL, headerConfidence = NULL, activeOcrRunId = NULL, " +
            "lastError = NULL, updatedAt = MAX(updatedAt, :updatedAt) " +
            "WHERE draftId = :draftId AND confirmedPurchaseId IS NULL " +
            "AND status IN (:createdStatus, :capturedStatus, :errorStatus)",
    )
    suspend fun resetAfterImageMutation(
        draftId: String,
        createdStatus: String,
        capturedStatus: String,
        errorStatus: String,
        updatedAt: Long,
    ): Int

    /**
     * CAS interno del reemplazo de foto del escaneo rápido. Solo abre los estados explícitos
     * que pueden resultar de OCR/importación; el llamador ya comprobó que existe una sola página.
     * Se ejecuta dentro de la misma transacción que invalida derivados y publica el reemplazo.
     */
    @Query(
        "UPDATE invoice_drafts SET status = :capturedStatus " +
            "WHERE draftId = :draftId AND confirmedPurchaseId IS NULL " +
            "AND activeOcrRunId IS NULL AND status IN (" +
            ":capturedStatus, :errorStatus, :ocrReadyStatus, :reviewStatus)",
    )
    suspend fun claimSoleInvoiceScanRetake(
        draftId: String,
        capturedStatus: String,
        errorStatus: String,
        ocrReadyStatus: String,
        reviewStatus: String,
    ): Int

    /** Avanza el timestamp global sin hacerlo retroceder frente a otra parte del agregado. */
    @Query(
        "UPDATE invoice_drafts SET updatedAt = MAX(updatedAt, :updatedAt) " +
            "WHERE draftId = :draftId",
    )
    suspend fun touchAtLeast(draftId: String, updatedAt: Long): Int

    @Query(
        "UPDATE invoice_drafts SET status = :processingStatus, activeOcrRunId = :runId, " +
            "lastError = NULL, updatedAt = :updatedAt " +
            "WHERE draftId = :draftId AND status IN (:capturedStatus, :errorStatus) " +
            "AND activeOcrRunId IS NULL AND confirmedPurchaseId IS NULL",
    )
    suspend fun beginOcrRun(
        draftId: String,
        runId: String,
        capturedStatus: String,
        errorStatus: String,
        processingStatus: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE invoice_drafts SET status = :newStatus, activeOcrRunId = NULL, " +
            "lastError = :lastError, updatedAt = :updatedAt " +
            "WHERE draftId = :draftId AND status = :processingStatus " +
            "AND activeOcrRunId = :runId AND confirmedPurchaseId IS NULL",
    )
    suspend fun finishOcrRun(
        draftId: String,
        runId: String,
        processingStatus: String,
        newStatus: String,
        lastError: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE invoice_drafts SET status = :capturedStatus, activeOcrRunId = NULL, " +
            "lastError = NULL, updatedAt = :updatedAt " +
            "WHERE draftId = :draftId AND status = :processingStatus " +
            "AND confirmedPurchaseId IS NULL AND (" +
            "(:expectedRunId IS NULL AND activeOcrRunId IS NULL) OR activeOcrRunId = :expectedRunId)",
    )
    suspend fun resetInterruptedOcr(
        draftId: String,
        expectedRunId: String?,
        processingStatus: String,
        capturedStatus: String,
        updatedAt: Long,
    ): Int

    /**
     * Reclama el fallback manual y cancela lógicamente cualquier callback OCR tardío. Un fallo
     * de reconocimiento exige ausencia de snapshot; un fallo de parser exige `OCR_READY` y
     * snapshot presente. En ambos casos se rechaza un parseo o formulario ya publicado.
     */
    @Query(
        "UPDATE invoice_drafts SET status = :reviewStatus, activeOcrRunId = NULL, " +
            "lastError = NULL, supplierId = NULL, supplierRucRaw = NULL, " +
            "supplierRucNormalized = NULL, supplierLegalNameRaw = NULL, " +
            "supplierLegalNameNormalized = NULL, documentType = NULL, " +
            "documentNumberRaw = NULL, documentNumberNormalized = NULL, issueDateRaw = NULL, " +
            "issueDateNormalized = NULL, currencyCode = NULL, subtotalMinorUnits = NULL, " +
            "taxMinorUnits = NULL, otherChargesMinorUnits = NULL, totalMinorUnits = NULL, " +
            "headerConfidence = NULL, updatedAt = MAX(updatedAt, :updatedAt) " +
            "WHERE draftId = :draftId AND confirmedPurchaseId IS NULL AND (" +
            "(status IN (:errorStatus, :processingStatus) AND NOT EXISTS (" +
            "SELECT 1 FROM invoice_ocr_snapshots s WHERE s.draftId = :draftId)) OR " +
            "(status = :ocrReadyStatus AND EXISTS (" +
            "SELECT 1 FROM invoice_ocr_snapshots s WHERE s.draftId = :draftId))) " +
            "AND NOT EXISTS (SELECT 1 FROM invoice_parsed_results p WHERE p.draftId = :draftId) " +
            "AND NOT EXISTS (SELECT 1 FROM invoice_header_edits h WHERE h.draftId = :draftId) " +
            "AND NOT EXISTS (SELECT 1 FROM invoice_line_edits l WHERE l.draftId = :draftId)",
    )
    suspend fun enterManualReviewIfProcessingFailed(
        draftId: String,
        errorStatus: String,
        processingStatus: String,
        ocrReadyStatus: String,
        reviewStatus: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM invoice_drafts WHERE businessId = :businessId")
    suspend fun countForBusiness(businessId: String): Int

    @Query("SELECT draftId FROM invoice_drafts")
    suspend fun listAllDraftIds(): List<String>

    /** Originales de todos los borradores sin compra confirmada, en todos los negocios. */
    @Query(
        "SELECT i.* FROM invoice_images i " +
            "INNER JOIN invoice_drafts d ON d.draftId = i.draftId " +
            "WHERE d.confirmedPurchaseId IS NULL " +
            "ORDER BY d.businessId ASC, d.draftId ASC, i.pageIndex ASC, i.imageId ASC",
    )
    suspend fun listOpenDraftImages(): List<InvoiceImageEntity>

    /** Originales de borradores abiertos que ya publicaron OCR/revisión/preparación. */
    @Query(
        "SELECT i.* FROM invoice_images i " +
            "INNER JOIN invoice_drafts d ON d.draftId = i.draftId " +
            "WHERE d.confirmedPurchaseId IS NULL AND d.status IN (:statuses) " +
            "AND EXISTS (SELECT 1 FROM invoice_ocr_snapshots s " +
            "WHERE s.draftId = d.draftId " +
            "AND s.pageCount = (SELECT COUNT(*) FROM invoice_ocr_snapshot_pages p " +
            "WHERE p.draftId = d.draftId) " +
            "AND s.pageCount = (SELECT COUNT(*) FROM invoice_images a " +
            "WHERE a.draftId = d.draftId) " +
            "AND NOT EXISTS (SELECT 1 FROM invoice_images a " +
            "WHERE a.draftId = d.draftId AND NOT EXISTS (" +
            "SELECT 1 FROM invoice_ocr_snapshot_pages p " +
            "WHERE p.draftId = a.draftId AND p.pageIndex = a.pageIndex " +
            "AND p.sourceImageId = a.imageId)) " +
            ") " +
            "ORDER BY d.draftId ASC, i.pageIndex ASC",
    )
    suspend fun listImagesForDraftStatuses(statuses: List<String>): List<InvoiceImageEntity>

    @Query("SELECT draftId FROM invoice_drafts WHERE confirmedPurchaseId IS NOT NULL")
    suspend fun listCommittedDraftIds(): List<String>

    @Query("DELETE FROM invoice_drafts WHERE draftId = :draftId")
    suspend fun deleteById(draftId: String): Int
}

data class RecentDraftReadRow(
    @Embedded val draft: InvoiceDraftEntity,
    val supplierName: String?,
)
