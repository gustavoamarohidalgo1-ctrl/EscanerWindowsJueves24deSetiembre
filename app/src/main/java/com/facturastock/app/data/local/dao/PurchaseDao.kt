package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.PurchaseEntity
import kotlinx.coroutines.flow.Flow

/** Fila plana de encabezado/proveedor/imagen que el repositorio agrupa por compra. */
data class PurchaseDuplicateCandidateRow(
    val purchaseId: String,
    val businessId: String,
    val sourceDraftId: String,
    val supplierId: String,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: String,
    val currencyCode: String,
    val totalMinorUnits: Long,
    val status: String,
    val imageSha256: String?,
)

/** Una fila por compra: agregados y último estado se unen sin materializar payloads de outbox. */
data class PurchaseReadSummaryRow(
    val purchaseId: String,
    val businessId: String,
    val sourceDraftId: String,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: String,
    val currencyCode: String,
    val totalMinorUnits: Long,
    val status: String,
    val syncStatus: String?,
    val syncLastError: String?,
    val syncUpdatedAt: Long?,
    val lineCount: Int,
    val productCount: Int,
    val createdProductCount: Int,
    val existingProductCount: Int,
    val unknownProductCount: Int,
    val postedAt: Long?,
    /** Cursor inmutable usado solo por la consulta keyset de la UI. */
    val historyCreatedAt: Long,
)

data class PurchaseHomeCountsRow(
    val postedCount: Int,
    val syncProblemCount: Int,
)

/** Cabecera financiera completa para el agregado histórico de detalle. */
data class PurchaseReadHeaderRow(
    val purchaseId: String,
    val businessId: String,
    val sourceDraftId: String,
    val supplierId: String,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: String,
    val currencyCode: String,
    val subtotalMinorUnits: Long,
    val taxMinorUnits: Long,
    val otherChargesMinorUnits: Long,
    val totalMinorUnits: Long,
    val status: String,
    val syncStatus: String?,
    val syncLastError: String?,
    val syncUpdatedAt: Long?,
    val lineCount: Int,
    val productCount: Int,
    val createdProductCount: Int,
    val existingProductCount: Int,
    val unknownProductCount: Int,
    val postedAt: Long?,
    val duplicateOverrideOfPurchaseId: String?,
    val duplicateOverrideReason: String?,
    val duplicateOverrideActorId: String?,
    val duplicateOverrideRole: String?,
)

/**
 * Acceso al encabezado durable de una compra.
 *
 * La compra se crea una sola vez. Después de insertarla, las únicas mutaciones expuestas son los
 * dos cambios de estado con compare-and-set: publicar y anular. Las restricciones únicas de la
 * tabla son la última barrera frente a reintentos concurrentes por borrador, documento o clave de
 * idempotencia.
 */
@Dao
interface PurchaseDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(purchase: PurchaseEntity)

    @Query("SELECT * FROM purchases WHERE purchaseId = :purchaseId")
    suspend fun findById(purchaseId: String): PurchaseEntity?

    @Query("SELECT * FROM purchases WHERE idempotencyKey = :idempotencyKey")
    suspend fun findByIdempotencyKey(idempotencyKey: String): PurchaseEntity?

    @Query("SELECT * FROM purchases WHERE sourceDraftId = :sourceDraftId")
    suspend fun findBySourceDraftId(sourceDraftId: String): PurchaseEntity?

    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN p.status = 'POSTED' THEN 1 ELSE 0 END), 0) " +
            "AS postedCount, " +
            "COALESCE(SUM(CASE WHEN (SELECT o.status FROM outbox_operations o " +
            "WHERE o.businessId = p.businessId AND o.purchaseId = p.purchaseId " +
            "AND o.entityType = 'PURCHASE' " +
            "ORDER BY o.createdAt DESC, o.operationId DESC LIMIT 1) " +
            "IN ('FAILED','CONFLICT') THEN 1 ELSE 0 END), 0) AS syncProblemCount " +
            "FROM purchases p WHERE p.businessId = :businessId " +
            "AND p.status IN ('POSTED','VOIDED')",
    )
    fun observeHomeCounts(businessId: String): Flow<PurchaseHomeCountsRow>

    @Query(
        "WITH line_stats AS (SELECT pl.purchaseId, COUNT(*) AS lineCount, " +
            "COUNT(DISTINCT pl.productId) AS productCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'CREATED_IN_DRAFT' " +
            "THEN pl.productId END) AS createdProductCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'EXISTING' " +
            "THEN pl.productId END) AS existingProductCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'UNKNOWN_LEGACY' " +
            "THEN pl.productId END) AS unknownProductCount " +
            "FROM purchase_lines pl " +
            "INNER JOIN purchases stats_purchase " +
            "ON stats_purchase.purchaseId = pl.purchaseId " +
            "WHERE stats_purchase.businessId = :businessId GROUP BY pl.purchaseId) " +
            "SELECT p.purchaseId, p.businessId, p.sourceDraftId, " +
            "COALESCE(d.supplierRucNormalized, s.ruc) AS supplierRuc, " +
            "COALESCE(d.supplierLegalNameNormalized, s.legalName) AS supplierLegalName, " +
            "p.documentType, p.documentSeries, p.documentNumber, p.issueDate, " +
            "p.currencyCode, p.totalMinorUnits, p.status, p.postedAt, " +
            "p.createdAt AS historyCreatedAt, " +
            "o.status AS syncStatus, o.lastError AS syncLastError, o.updatedAt AS syncUpdatedAt, " +
            "COALESCE(ls.lineCount, 0) AS lineCount, " +
            "COALESCE(ls.productCount, 0) AS productCount, " +
            "COALESCE(ls.createdProductCount, 0) AS createdProductCount, " +
            "COALESCE(ls.existingProductCount, 0) AS existingProductCount, " +
            "COALESCE(ls.unknownProductCount, 0) AS unknownProductCount " +
            "FROM purchases p " +
            "INNER JOIN suppliers s ON s.supplierId = p.supplierId AND s.businessId = p.businessId " +
            "INNER JOIN invoice_drafts d ON d.draftId = p.sourceDraftId AND d.businessId = p.businessId " +
            "LEFT JOIN line_stats ls ON ls.purchaseId = p.purchaseId " +
            "LEFT JOIN outbox_operations o ON o.operationId = (" +
            "SELECT latest.operationId FROM outbox_operations latest " +
            "WHERE latest.purchaseId = p.purchaseId AND latest.entityType = 'PURCHASE' " +
            "ORDER BY latest.createdAt DESC, latest.operationId DESC LIMIT 1) " +
            "WHERE p.businessId = :businessId AND p.status IN ('POSTED','VOIDED') " +
            "ORDER BY COALESCE(p.postedAt, p.createdAt) DESC, p.purchaseId DESC",
    )
    fun observeReadSummaries(businessId: String): Flow<List<PurchaseReadSummaryRow>>

    /**
     * Señal barata de invalidación para la ventana visible. Room vuelve a emitir ante cualquier
     * escritura en purchases/outbox aunque el conteo permanezca igual; el repositorio reconstruye
     * entonces las páginas solicitadas y evita mezclar cursores anteriores con filas nuevas.
     */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM purchases p WHERE p.businessId = :businessId " +
            "AND p.status IN ('POSTED','VOIDED')) + " +
            "(SELECT COUNT(*) FROM outbox_operations o WHERE o.businessId = :businessId " +
            "AND o.entityType = 'PURCHASE')",
    )
    fun observeReadSummaryInvalidations(businessId: String): Flow<Long>

    /**
     * Lote keyset de la UI. `createdAt` es inmutable y el UUID desempata de forma total; no se usa
     * OFFSET. Estado y sincronización se resuelven en SQL. La búsqueda textual exacta se conserva
     * en Kotlin sobre lotes acotados para mantener case-folding Unicode y `%`/`_` literales.
     */
    @Query(
        "WITH page_candidates AS (SELECT p.purchaseId, p.businessId, p.sourceDraftId, " +
            "COALESCE(d.supplierRucNormalized, s.ruc) AS supplierRuc, " +
            "COALESCE(d.supplierLegalNameNormalized, s.legalName) AS supplierLegalName, " +
            "p.documentType, p.documentSeries, p.documentNumber, p.issueDate, " +
            "p.currencyCode, p.totalMinorUnits, p.status, p.postedAt, " +
            "p.createdAt AS historyCreatedAt, " +
            "o.status AS syncStatus, o.lastError AS syncLastError, " +
            "o.updatedAt AS syncUpdatedAt FROM purchases p " +
            "INNER JOIN suppliers s ON s.supplierId = p.supplierId " +
            "AND s.businessId = p.businessId " +
            "INNER JOIN invoice_drafts d ON d.draftId = p.sourceDraftId " +
            "AND d.businessId = p.businessId " +
            "LEFT JOIN outbox_operations o ON o.operationId = (" +
            "SELECT latest.operationId FROM outbox_operations latest " +
            "WHERE latest.purchaseId = p.purchaseId AND latest.entityType = 'PURCHASE' " +
            "ORDER BY latest.createdAt DESC, latest.operationId DESC LIMIT 1) " +
            "WHERE p.businessId = :businessId AND p.status IN ('POSTED','VOIDED') " +
            "AND (:status IS NULL OR p.status = :status) " +
            "AND (:syncStatus IS NULL OR (:syncStatus = '' AND o.status IS NULL) " +
            "OR o.status = :syncStatus) " +
            "AND (:beforeCreatedAt IS NULL OR p.createdAt < :beforeCreatedAt OR " +
            "(p.createdAt = :beforeCreatedAt AND p.purchaseId < :beforePurchaseId)) " +
            "ORDER BY p.createdAt DESC, p.purchaseId DESC LIMIT :limit), " +
            "line_stats AS (SELECT pl.purchaseId, COUNT(*) AS lineCount, " +
            "COUNT(DISTINCT pl.productId) AS productCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'CREATED_IN_DRAFT' " +
            "THEN pl.productId END) AS createdProductCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'EXISTING' " +
            "THEN pl.productId END) AS existingProductCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'UNKNOWN_LEGACY' " +
            "THEN pl.productId END) AS unknownProductCount FROM purchase_lines pl " +
            "INNER JOIN page_candidates pc ON pc.purchaseId = pl.purchaseId " +
            "GROUP BY pl.purchaseId) " +
            "SELECT pc.purchaseId, pc.businessId, pc.sourceDraftId, pc.supplierRuc, " +
            "pc.supplierLegalName, pc.documentType, pc.documentSeries, pc.documentNumber, " +
            "pc.issueDate, pc.currencyCode, pc.totalMinorUnits, pc.status, pc.syncStatus, " +
            "pc.syncLastError, pc.syncUpdatedAt, COALESCE(ls.lineCount, 0) AS lineCount, " +
            "COALESCE(ls.productCount, 0) AS productCount, " +
            "COALESCE(ls.createdProductCount, 0) AS createdProductCount, " +
            "COALESCE(ls.existingProductCount, 0) AS existingProductCount, " +
            "COALESCE(ls.unknownProductCount, 0) AS unknownProductCount, pc.postedAt, " +
            "pc.historyCreatedAt FROM page_candidates pc " +
            "LEFT JOIN line_stats ls ON ls.purchaseId = pc.purchaseId " +
            "ORDER BY pc.historyCreatedAt DESC, pc.purchaseId DESC",
    )
    suspend fun listReadSummaryPage(
        businessId: String,
        status: String?,
        syncStatus: String?,
        beforeCreatedAt: Long?,
        beforePurchaseId: String?,
        limit: Int,
    ): List<PurchaseReadSummaryRow>

    @Query(
        "WITH line_stats AS (SELECT pl.purchaseId, COUNT(*) AS lineCount, " +
            "COUNT(DISTINCT pl.productId) AS productCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'CREATED_IN_DRAFT' " +
            "THEN pl.productId END) AS createdProductCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'EXISTING' " +
            "THEN pl.productId END) AS existingProductCount, " +
            "COUNT(DISTINCT CASE WHEN pl.productProvenance = 'UNKNOWN_LEGACY' " +
            "THEN pl.productId END) AS unknownProductCount " +
            "FROM purchase_lines pl WHERE pl.purchaseId = :purchaseId " +
            "GROUP BY pl.purchaseId), " +
            "latest_purchase_outbox AS (SELECT o.status, o.lastError, o.updatedAt " +
            "FROM outbox_operations o WHERE o.purchaseId = :purchaseId " +
            "AND o.entityType = 'PURCHASE' " +
            "ORDER BY o.createdAt DESC, o.operationId DESC LIMIT 1) " +
            "SELECT p.purchaseId, p.businessId, p.sourceDraftId, p.supplierId, " +
            "COALESCE(d.supplierRucNormalized, s.ruc) AS supplierRuc, " +
            "COALESCE(d.supplierLegalNameNormalized, s.legalName) AS supplierLegalName, " +
            "p.documentType, p.documentSeries, p.documentNumber, p.issueDate, p.currencyCode, " +
            "p.subtotalMinorUnits, p.taxMinorUnits, p.otherChargesMinorUnits, " +
            "p.totalMinorUnits, p.status, p.postedAt, " +
            "p.duplicateOverrideOfPurchaseId, p.duplicateOverrideReason, " +
            "p.duplicateOverrideActorId, p.duplicateOverrideRole, " +
            "o.status AS syncStatus, o.lastError AS syncLastError, " +
            "o.updatedAt AS syncUpdatedAt, " +
            "COALESCE(ls.lineCount, 0) AS lineCount, " +
            "COALESCE(ls.productCount, 0) AS productCount, " +
            "COALESCE(ls.createdProductCount, 0) AS createdProductCount, " +
            "COALESCE(ls.existingProductCount, 0) AS existingProductCount, " +
            "COALESCE(ls.unknownProductCount, 0) AS unknownProductCount " +
            "FROM purchases p " +
            "INNER JOIN suppliers s ON s.supplierId = p.supplierId AND s.businessId = p.businessId " +
            "INNER JOIN invoice_drafts d ON d.draftId = p.sourceDraftId AND d.businessId = p.businessId " +
            "LEFT JOIN line_stats ls ON ls.purchaseId = p.purchaseId " +
            "LEFT JOIN latest_purchase_outbox o ON 1 = 1 " +
            "WHERE p.businessId = :businessId AND p.purchaseId = :purchaseId " +
            "AND p.status IN ('POSTED','VOIDED') LIMIT 1",
    )
    fun observeReadHeader(
        businessId: String,
        purchaseId: String,
    ): Flow<PurchaseReadHeaderRow?>

    @Query(
        "SELECT * FROM purchases WHERE businessId = :businessId " +
            "AND supplierId = :supplierId AND documentType = :documentType " +
            "AND documentSeries = :documentSeries AND documentNumber = :documentNumber " +
            "AND documentIdentitySlot = 'PRIMARY'",
    )
    suspend fun findByDocument(
        businessId: String,
        supplierId: String,
        documentType: String,
        documentSeries: String,
        documentNumber: String,
    ): PurchaseEntity?

    @Query(
        "SELECT p.purchaseId, p.businessId, p.sourceDraftId, p.supplierId, " +
            "COALESCE(d.supplierRucNormalized, s.ruc) AS supplierRuc, " +
            "COALESCE(d.supplierLegalNameNormalized, s.legalName) AS supplierLegalName, " +
            "p.documentType, p.documentSeries, p.documentNumber, p.issueDate, " +
            "p.currencyCode, p.totalMinorUnits, p.status, i.sha256 AS imageSha256 " +
            "FROM purchases p INNER JOIN suppliers s ON s.supplierId = p.supplierId " +
            "INNER JOIN invoice_drafts d ON d.draftId = p.sourceDraftId " +
            "LEFT JOIN invoice_images i ON i.draftId = p.sourceDraftId " +
            "WHERE p.purchaseId = :purchaseId " +
            "ORDER BY i.pageIndex ASC",
    )
    suspend fun findRecordedRows(purchaseId: String): List<PurchaseDuplicateCandidateRow>

    @Query(
        "SELECT p.purchaseId, p.businessId, p.sourceDraftId, p.supplierId, " +
            "COALESCE(d.supplierRucNormalized, s.ruc) AS supplierRuc, " +
            "COALESCE(d.supplierLegalNameNormalized, s.legalName) AS supplierLegalName, " +
            "p.documentType, p.documentSeries, p.documentNumber, p.issueDate, " +
            "p.currencyCode, p.totalMinorUnits, p.status, i.sha256 AS imageSha256 " +
            "FROM purchases p INNER JOIN suppliers s ON s.supplierId = p.supplierId " +
            "INNER JOIN invoice_drafts d ON d.draftId = p.sourceDraftId " +
            "LEFT JOIN invoice_images i ON i.draftId = p.sourceDraftId " +
            "WHERE p.businessId = :businessId AND p.documentType = :documentType " +
            "AND p.status IN ('POSTED','VOIDED') AND (" +
            "(:supplierId IS NOT NULL AND p.supplierId = :supplierId) OR " +
            "(:supplierRuc != '' AND " +
            "COALESCE(d.supplierRucNormalized, s.ruc) = :supplierRuc)) " +
            "ORDER BY p.createdAt DESC, p.purchaseId DESC, i.pageIndex ASC",
    )
    suspend fun listDuplicateCandidateRows(
        businessId: String,
        supplierId: String?,
        supplierRuc: String,
        documentType: String,
    ): List<PurchaseDuplicateCandidateRow>

    @Query(
        "SELECT * FROM purchases WHERE businessId = :businessId " +
            "ORDER BY createdAt DESC, purchaseId DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun listForBusiness(
        businessId: String,
        limit: Int,
        offset: Int,
    ): List<PurchaseEntity>

    @Query(
        "SELECT * FROM purchases WHERE businessId = :businessId AND status = :status " +
            "ORDER BY createdAt DESC, purchaseId DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun listForBusinessByStatus(
        businessId: String,
        status: String,
        limit: Int,
        offset: Int,
    ): List<PurchaseEntity>

    /** CAS `DRAFT -> POSTED`; un reintento o una versión obsoleta afecta cero filas. */
    @Query(
        "UPDATE purchases SET status = :postedStatus, postedAt = :postedAt, " +
            "updatedAt = :updatedAt WHERE purchaseId = :purchaseId " +
            "AND businessId = :businessId AND status = :draftStatus " +
            "AND updatedAt = :expectedUpdatedAt AND postedAt IS NULL AND voidedAt IS NULL " +
            "AND :postedAt >= createdAt AND :updatedAt >= :postedAt",
    )
    suspend fun markPosted(
        purchaseId: String,
        businessId: String,
        draftStatus: String,
        postedStatus: String,
        expectedUpdatedAt: Long,
        postedAt: Long,
        updatedAt: Long,
    ): Int

    /** CAS `POSTED -> VOIDED`; una compra ya anulada nunca se vuelve a mutar. */
    @Query(
        "UPDATE purchases SET status = :voidedStatus, voidedAt = :voidedAt, " +
            "updatedAt = :updatedAt WHERE purchaseId = :purchaseId " +
            "AND businessId = :businessId AND status = :postedStatus " +
            "AND updatedAt = :expectedUpdatedAt AND postedAt IS NOT NULL AND voidedAt IS NULL " +
            "AND :voidedAt >= postedAt AND :updatedAt >= :voidedAt",
    )
    suspend fun markVoided(
        purchaseId: String,
        businessId: String,
        postedStatus: String,
        voidedStatus: String,
        expectedUpdatedAt: Long,
        voidedAt: Long,
        updatedAt: Long,
    ): Int

    /**
     * Imágenes retenidas de las compras terminales del negocio para el mantenimiento de
     * privacidad (retención por antigüedad y migración de cifrado). POSTED y VOIDED comparten
     * la misma política; `postedAt` es no nulo por invariante de entidad en ambos estados.
     */
    @Query(
        "SELECT p.businessId, p.purchaseId, p.sourceDraftId, i.imageId, i.filePath, " +
            "p.postedAt, i.createdAt AS imageCreatedAt " +
            "FROM purchases p " +
            "INNER JOIN invoice_images i " +
            "ON i.draftId = p.sourceDraftId AND i.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.status IN ('POSTED','VOIDED') " +
            "ORDER BY p.purchaseId, i.pageIndex, i.imageId",
    )
    suspend fun listRetainedImagesForRetention(businessId: String): List<RetainedImageRow>

    /** Hook post-commit: solo las páginas de la compra recién confirmada, sin escanear historial. */
    @Query(
        "SELECT p.businessId, p.purchaseId, p.sourceDraftId, i.imageId, i.filePath, " +
            "p.postedAt, i.createdAt AS imageCreatedAt " +
            "FROM purchases p " +
            "INNER JOIN invoice_images i " +
            "ON i.draftId = p.sourceDraftId AND i.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.sourceDraftId = :draftId " +
            "AND p.status IN ('POSTED','VOIDED') " +
            "ORDER BY i.pageIndex, i.imageId",
    )
    suspend fun listRetainedImagesForDraft(
        businessId: String,
        draftId: String,
    ): List<RetainedImageRow>

    /** Todas las imágenes terminales: la política de retención es global a la instalación. */
    @Query(
        "SELECT p.businessId, p.purchaseId, p.sourceDraftId, i.imageId, i.filePath, " +
            "p.postedAt, i.createdAt AS imageCreatedAt " +
            "FROM purchases p " +
            "INNER JOIN invoice_images i " +
            "ON i.draftId = p.sourceDraftId AND i.businessId = p.businessId " +
            "WHERE p.status IN ('POSTED','VOIDED') " +
            "ORDER BY p.businessId, p.purchaseId, i.pageIndex, i.imageId",
    )
    suspend fun listAllRetainedImagesForRetention(): List<RetainedImageRow>

    /**
     * Fotos aún retenidas que pueden bootstrapearse al activar el respaldo documental.
     *
     * El anti-join excluye en SQLite cualquier identidad que ya tenga upload o tombstone
     * durable. Así una pasada idempotente no materializa todo el histórico ni ejecuta dos
     * búsquedas de outbox por cada imagen ya atendida.
     */
    @Query(
        "SELECT p.purchaseId, p.postedAt, i.imageId, i.filePath, i.sha256, i.mimeType, " +
            "i.widthPx, i.heightPx, i.fileSizeBytes, i.rotationDegrees " +
            "FROM purchases p INNER JOIN invoice_images i " +
            "ON i.draftId = p.sourceDraftId AND i.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.status IN ('POSTED','VOIDED') " +
            "AND (:postedAfterExclusive IS NULL OR p.postedAt > :postedAfterExclusive) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations existing " +
            "WHERE existing.businessId = p.businessId " +
            "AND existing.entityType = :documentEntityType " +
            "AND existing.entityId = i.imageId " +
            "AND existing.entityVersion IN (:uploadEntityVersion, :purgeEntityVersion)) " +
            "ORDER BY p.purchaseId, i.pageIndex, i.imageId",
    )
    suspend fun listRetainedDocumentCandidates(
        businessId: String,
        postedAfterExclusive: Long?,
        documentEntityType: String,
        uploadEntityVersion: Long,
        purgeEntityVersion: Long,
    ): List<RetainedDocumentCandidateRow>

    /**
     * Identidades documentales de las compras terminales del negocio para la reconciliación
     * diagnóstica local↔nube. El RUC efectivo es el mismo que viaja en el respaldo: el
     * normalizado del borrador cuando existe, si no el del proveedor.
     */
    @Query(
        "SELECT p.purchaseId, " +
            "COALESCE(d.supplierRucNormalized, s.ruc) AS supplierRuc, " +
            "p.documentType, p.documentSeries, p.documentNumber " +
            "FROM purchases p " +
            "INNER JOIN suppliers s ON s.supplierId = p.supplierId AND s.businessId = p.businessId " +
            "INNER JOIN invoice_drafts d ON d.draftId = p.sourceDraftId " +
            "AND d.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.status IN ('POSTED','VOIDED') " +
            "ORDER BY p.createdAt ASC, p.purchaseId ASC",
    )
    suspend fun listLedgerDocumentIdentities(businessId: String): List<LedgerDocumentIdentityRow>
}

/** Proyección de identidad documental de una compra terminal, para reconciliación. */
data class LedgerDocumentIdentityRow(
    val purchaseId: String,
    val supplierRuc: String?,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
)

/** Proyección plana de una imagen retenida de una compra terminal, para mantenimiento. */
data class RetainedImageRow(
    val businessId: String,
    val purchaseId: String,
    val sourceDraftId: String,
    val imageId: String,
    val filePath: String,
    val postedAt: Long,
    val imageCreatedAt: Long,
)

data class RetainedDocumentCandidateRow(
    val purchaseId: String,
    val postedAt: Long,
    val imageId: String,
    val filePath: String,
    val sha256: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
    val rotationDegrees: Int,
)
