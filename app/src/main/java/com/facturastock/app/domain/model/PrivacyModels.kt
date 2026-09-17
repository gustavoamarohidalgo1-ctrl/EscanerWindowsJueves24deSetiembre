package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Política global de retención de las fotos de comprobantes. La retención solo toca ARCHIVOS
 * de imagen: los registros contables (compra, líneas, movimientos, auditoría) jamás se borran,
 * de modo que el detalle de una compra sin foto muestra su comprobante con un marcador de
 * "imagen no retenida".
 *
 * [retentionDays] fija la ventana de conservación tras la publicación; null significa que el
 * borrado no depende de la antigüedad (se decide en un hito del flujo o nunca).
 */
enum class ImageRetentionPolicy(val retentionDays: Long?) {
    /** La foto se borra en cuanto el run OCR queda publicado; la compra se confirma sin ella. */
    AFTER_OCR(null),

    /** La foto sobrevive al OCR para revisión y se borra al confirmar la compra. */
    AFTER_CONFIRM(null),

    /** La foto confirmada se conserva 30 días desde `postedAt` y luego se borra. */
    DAYS_30(30L),

    /** La foto confirmada se conserva 90 días desde `postedAt` y luego se borra. */
    DAYS_90(90L),

    /** Conservación indefinida (default): la foto acompaña siempre al registro contable. */
    KEEP(null),
}

/**
 * Decisor puro de retención de imágenes: sin filesystem ni reloj de pared, de modo que los
 * bordes temporales se prueban con instantes fijos. Todas las decisiones son sobre archivos;
 * ninguna toca filas de la base de datos.
 */
object ImageRetentionDecider {
    /** true si la política exige borrar los originales al publicarse el run OCR. */
    fun shouldDeleteAfterOcr(policy: ImageRetentionPolicy): Boolean =
        policy == ImageRetentionPolicy.AFTER_OCR

    /** true si la política exige borrar el árbol de imágenes al confirmar la compra. */
    fun shouldDeleteAfterConfirm(policy: ImageRetentionPolicy): Boolean =
        policy == ImageRetentionPolicy.AFTER_CONFIRM

    /**
     * true si una imagen que sigue retenida para una compra terminal (POSTED o VOIDED) debe
     * borrarse en [now]. Con [ImageRetentionPolicy.DAYS_30]/[ImageRetentionPolicy.DAYS_90] el
     * borrado ocurre exactamente cuando `postedAt + retentionDays <= now`. Con
     * [ImageRetentionPolicy.AFTER_OCR]/[ImageRetentionPolicy.AFTER_CONFIRM] la compra ya pasó
     * su hito de borrado, así que una imagen que sobrevivió (cambio de política posterior o
     * un hook fallido) se borra en la próxima pasada. [ImageRetentionPolicy.KEEP] nunca borra.
     */
    fun shouldDeleteRetainedImage(
        policy: ImageRetentionPolicy,
        postedAt: Instant,
        now: Instant,
    ): Boolean {
        val days = policy.retentionDays ?: return policy != ImageRetentionPolicy.KEEP
        val deleteAt = postedAt.plus(Duration.ofDays(days))
        return !deleteAt.isAfter(now)
    }
}

/** Resultado cerrado de intentar retirar una imagen del almacenamiento privado. */
enum class PrivateImageDeletionResult {
    /** Esta llamada eliminó un archivo regular existente. */
    DELETED,

    /** La ruta era válida y el archivo ya no existía; el objetivo local está cumplido. */
    ALREADY_ABSENT,

    /** La ruta/forma fue rechazada de manera permanente; repetir no la vuelve segura. */
    REJECTED_UNSAFE,

    /** La ruta era válida, pero un fallo transitorio impidió confirmar el borrado. */
    FAILED,
}

/** Estado autenticado del envelope local de una imagen retenida. */
enum class RetainedImageEncryptionState {
    /** Archivo regular en claro dentro del sandbox; puede migrarse a AES-GCM. */
    PLAINTEXT,

    /** Envelope FSE1 completo cuyo tag AES-GCM se verificó con la clave del dispositivo. */
    ENCRYPTED,

    /** Parece un envelope FSE, pero su versión, tamaño, nonce o tag no es válido. */
    CORRUPT,

    /** La ruta no existe, no es regular o no pudo leerse. */
    UNAVAILABLE,
}

/**
 * Estado barato usado exclusivamente para clasificar la migración histórica. A diferencia de
 * [RetainedImageEncryptionState], [ENVELOPED] no afirma que el tag GCM ya fue autenticado ni que
 * la publicación quedó durable; por ello mantenimiento debe volver a pasar por `encryptInPlace`
 * antes de declarar satisfecha esa ruta. Las lecturas para UI/exportación también autentican el
 * contenido completo.
 */
enum class RetainedImageMigrationState {
    PLAINTEXT,
    ENVELOPED,
    /** La ruta válida ya no tiene archivo: no queda plaintext que migrar. */
    ABSENT,
    CORRUPT,
    UNAVAILABLE,
}

/** Etapas independientes cuyo fallo no debe impedir que las demás limpiezas continúen. */
enum class PrivacyMaintenanceStep {
    STALE_IMPORTS,
    STALE_CACHE,
    STALE_PRIVATE_TEMPS,
    ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS,
    ORPHAN_DRAFT_DIRECTORIES,
    UNREFERENCED_DRAFT_IMAGES,
    COMMITTED_OCR_RUNS,
    DISCARDED_OCR_RUNS,
    FORCED_OCR_RUNS,
}

/** Resultado cerrado de barrer árboles OCR seleccionados explícitamente. */
data class OcrVersionSweepReport(
    val attempted: Int = 0,
    val deleted: Int = 0,
    val alreadyAbsent: Int = 0,
    val failed: Int = 0,
    val retryableFailed: Int = failed,
) {
    init {
        require(
            attempted >= 0 && deleted >= 0 && alreadyAbsent >= 0 && failed >= 0 &&
                retryableFailed >= 0,
        )
        require(attempted == deleted + alreadyAbsent + failed) {
            "Cada árbol OCR intentado debe tener un resultado cerrado"
        }
        require(retryableFailed <= failed)
    }
}

/** Resultado cerrado reutilizable para barridos físicos por elemento. */
data class PrivateFileSweepReport(
    val attempted: Int = 0,
    val deleted: Int = 0,
    val alreadyAbsent: Int = 0,
    val failed: Int = 0,
    val retryableFailed: Int = failed,
) {
    init {
        require(
            attempted >= 0 && deleted >= 0 && alreadyAbsent >= 0 && failed >= 0 &&
                retryableFailed >= 0,
        )
        require(attempted == deleted + alreadyAbsent + failed)
        require(retryableFailed <= failed)
    }
}

/**
 * Contadores reales de una pasada de mantenimiento de privacidad. Cada campo cuenta archivos o
 * directorios efectivamente eliminados/cifrados en ESA pasada; nada se infiere ni se estima.
 */
data class RetentionSweepReport(
    /** Temporales `import-*.tmp` huérfanos eliminados. */
    val staleImports: Int,
    /** Archivos de caché/miniatura sin uso por más de una hora, eliminados. */
    val staleCacheEntries: Int,
    /** Temporales internos conocidos de OCR/cifrado con más de una hora, eliminados. */
    val stalePrivateTemps: Int,
    /** Directorios `draft_images/{draftId}` sin borrador en la base de datos, eliminados. */
    val orphanDraftDirs: Int,
    /** Finales de imagen sin ninguna fila Room, típicamente por crash entre archivo y commit. */
    val unreferencedDraftImagesDeleted: Int = 0,
    /** Árboles `ocr/` de borradores ya confirmados, eliminados. */
    val committedOcrRuns: Int,
    /** Árboles OCR invalidados por una mutación posterior de imágenes. */
    val discardedOcrRuns: Int = 0,
    /** Originales de borradores con OCR publicado cuyo borrado se intentó por AFTER_OCR. */
    val afterOcrDeleteAttempted: Int,
    /** Originales de borradores con OCR publicado eliminados en esta pasada. */
    val afterOcrDeleted: Int,
    /** Originales de borradores con OCR publicado que ya estaban ausentes. */
    val afterOcrAlreadyAbsent: Int,
    /** Originales de borradores con OCR publicado cuyo borrado no pudo confirmarse. */
    val afterOcrDeleteFailed: Int,
    /** Subconjunto transitorio de [afterOcrDeleteFailed] que sí admite retry automático. */
    val afterOcrDeleteRetryableFailed: Int = afterOcrDeleteFailed,
    /** Originales de borradores abiertos incluidos por el borrado manual global. */
    val draftDeleteAttempted: Int = 0,
    /** Originales de borradores abiertos eliminados por el borrado manual. */
    val draftDeleted: Int = 0,
    /** Originales de borradores abiertos que ya estaban ausentes. */
    val draftAlreadyAbsent: Int = 0,
    /** Originales de borradores abiertos cuyo borrado manual no pudo confirmarse. */
    val draftDeleteFailed: Int = 0,
    /** Subconjunto transitorio de [draftDeleteFailed] que mantiene vivo el borrado global. */
    val draftDeleteRetryableFailed: Int = draftDeleteFailed,
    /** Imágenes para las que esta pasada intentó confirmar ausencia local. */
    val retentionDeleteAttempted: Int,
    /** Candidatos para los que se comprobó si era necesaria una purga remota durable. */
    val purgeIntentAttempted: Int,
    /** Intenciones `SYNC_DOCUMENT_PURGE` que quedaron durables antes del borrado local. */
    val purgeIntentDurable: Int,
    /** Candidatos que nunca tuvieron una operación de subida y no requieren purga remota. */
    val purgeIntentNotRequired: Int,
    /** Candidatos cuya intención de purga no pudo confirmarse; no se borraron localmente. */
    val purgeIntentFailed: Int,
    /** Operaciones legacy cuyo tenant remoto no puede demostrarse; requieren revisión manual. */
    val purgeIntentLegacyDestinationUnknown: Int = 0,
    /** Imágenes retenidas eliminadas por la política de retención (o por borrado forzado). */
    val retentionDeleted: Int,
    /** Candidatos cuya ruta válida ya estaba ausente al ejecutar la pasada. */
    val retentionAlreadyAbsent: Int,
    /** Candidatos cuyo borrado/ausencia no pudo confirmarse. */
    val retentionDeleteFailed: Int,
    /** Subconjunto transitorio de [retentionDeleteFailed] que sí admite retry automático. */
    val retentionDeleteRetryableFailed: Int = retentionDeleteFailed,
    /** Imágenes retenidas que esta pasada cifró en reposo (migración idempotente). */
    val encryptedMigrated: Int,
    /** Supervivientes cuya comprobación/cifrado en reposo se intentó. */
    val encryptionAttempted: Int,
    /** Supervivientes ya cifradas cuya autenticidad y publicación durable se confirmaron. */
    val encryptionAlreadySatisfied: Int,
    /** Supervivientes cuyo cifrado en reposo no pudo confirmarse. */
    val encryptionFailed: Int,
    /** Subconjunto transitorio de [encryptionFailed] que sí debe reintentarse con backoff. */
    val encryptionRetryableFailed: Int = encryptionFailed,
    /** Etapas de barrido que lanzaron; las demás se ejecutaron igualmente. */
    val failedSteps: Set<PrivacyMaintenanceStep>,
    /** Subconjunto de [failedSteps] que puede mejorar automáticamente al repetir. */
    val retryableFailedSteps: Set<PrivacyMaintenanceStep> = failedSteps,
    /** Árboles OCR incluidos por el borrado manual global. */
    val forcedOcrDeleteAttempted: Int = 0,
    val forcedOcrDeleted: Int = 0,
    val forcedOcrAlreadyAbsent: Int = 0,
    val forcedOcrDeleteFailed: Int = 0,
    /** Subconjunto transitorio que puede mejorar al repetir el borrado manual. */
    val forcedOcrDeleteRetryableFailed: Int = forcedOcrDeleteFailed,
    /** Derivados cifrados `.fse` sin upload abierto eliminados en esta pasada. */
    val orphanDocumentArtifactsDeleted: Int = 0,
    /** Derivados cifrados `.fse` cuyo borrado no pudo confirmarse. */
    val orphanDocumentArtifactsFailed: Int = 0,
    /** La intención durable global sigue viva y el worker no debe aplicar su corte ordinario. */
    val forceDeletionStillPending: Boolean = false,
) {
    init {
        require(
            staleImports >= 0 && staleCacheEntries >= 0 && stalePrivateTemps >= 0 &&
                orphanDraftDirs >= 0 &&
                unreferencedDraftImagesDeleted >= 0 &&
                committedOcrRuns >= 0 && afterOcrDeleteAttempted >= 0 &&
                discardedOcrRuns >= 0 &&
                afterOcrDeleted >= 0 && afterOcrAlreadyAbsent >= 0 &&
                afterOcrDeleteFailed >= 0 && afterOcrDeleteRetryableFailed >= 0 &&
                draftDeleteAttempted >= 0 && draftDeleted >= 0 &&
                draftAlreadyAbsent >= 0 && draftDeleteFailed >= 0 &&
                draftDeleteRetryableFailed >= 0,
        )
        require(
            afterOcrDeleteAttempted ==
                afterOcrDeleted + afterOcrAlreadyAbsent + afterOcrDeleteFailed,
        ) { "Cada intento AFTER_OCR debe tener un resultado cerrado" }
        require(draftDeleteAttempted == draftDeleted + draftAlreadyAbsent + draftDeleteFailed) {
            "Cada intento de borrado de borrador debe tener un resultado cerrado"
        }
        require(afterOcrDeleteRetryableFailed <= afterOcrDeleteFailed)
        require(draftDeleteRetryableFailed <= draftDeleteFailed)
        require(
            retentionDeleteAttempted >= 0 && purgeIntentAttempted >= 0 &&
                purgeIntentDurable >= 0 && purgeIntentNotRequired >= 0 &&
                purgeIntentFailed >= 0 && purgeIntentLegacyDestinationUnknown >= 0 &&
                retentionDeleted >= 0 &&
                retentionAlreadyAbsent >= 0 && retentionDeleteFailed >= 0 &&
                retentionDeleteRetryableFailed >= 0 &&
                encryptedMigrated >= 0 && encryptionAttempted >= 0 &&
                encryptionAlreadySatisfied >= 0 && encryptionFailed >= 0 &&
                encryptionRetryableFailed >= 0 &&
                orphanDocumentArtifactsDeleted >= 0 && orphanDocumentArtifactsFailed >= 0,
        )
        require(
            forcedOcrDeleteAttempted >= 0 && forcedOcrDeleted >= 0 &&
                forcedOcrAlreadyAbsent >= 0 && forcedOcrDeleteFailed >= 0 &&
                forcedOcrDeleteRetryableFailed >= 0,
        )
        require(
            forcedOcrDeleteAttempted ==
                forcedOcrDeleted + forcedOcrAlreadyAbsent + forcedOcrDeleteFailed,
        ) { "Cada árbol OCR del borrado manual debe tener un resultado cerrado" }
        require(forcedOcrDeleteRetryableFailed <= forcedOcrDeleteFailed)
        require(purgeIntentAttempted == retentionDeleteAttempted) {
            "Cada candidato local debe comprobar primero su necesidad de purga remota"
        }
        require(
            purgeIntentAttempted ==
                purgeIntentDurable + purgeIntentNotRequired + purgeIntentFailed +
                purgeIntentLegacyDestinationUnknown,
        ) { "Cada comprobación de purga debe tener un resultado cerrado" }
        require(
            retentionDeleteAttempted ==
                retentionDeleted + retentionAlreadyAbsent + retentionDeleteFailed,
        ) { "Cada intento de borrado debe tener un resultado cerrado" }
        require(retentionDeleteRetryableFailed <= retentionDeleteFailed) {
            "Los fallos reintentables deben ser un subconjunto de los fallos de borrado"
        }
        require(
            encryptionAttempted ==
                encryptedMigrated + encryptionAlreadySatisfied + encryptionFailed,
        ) { "Cada intento de cifrado debe tener un resultado cerrado" }
        require(encryptionRetryableFailed <= encryptionFailed) {
            "Los fallos reintentables deben ser un subconjunto de los fallos de cifrado"
        }
        require(retryableFailedSteps.all { step -> step in failedSteps }) {
            "Los pasos reintentables deben ser un subconjunto de los pasos fallidos"
        }
    }

    /** true si al menos una etapa o un archivo no pudo confirmar su objetivo local. */
    val hasUnconfirmedLocalWork: Boolean
        get() = failedSteps.isNotEmpty() || afterOcrDeleteFailed > 0 || draftDeleteFailed > 0 ||
            forcedOcrDeleteFailed > 0 ||
            purgeIntentFailed > 0 || purgeIntentLegacyDestinationUnknown > 0 ||
            retentionDeleteFailed > 0 || encryptionFailed > 0 ||
            orphanDocumentArtifactsFailed > 0

    /** Trabajo que puede mejorar automáticamente; corrupción permanente no crea retry loops. */
    val hasRetryableLocalWork: Boolean
        get() = retryableFailedSteps.isNotEmpty() || afterOcrDeleteRetryableFailed > 0 ||
            draftDeleteRetryableFailed > 0 ||
            forcedOcrDeleteRetryableFailed > 0 ||
            purgeIntentFailed > 0 || retentionDeleteRetryableFailed > 0 ||
            encryptionRetryableFailed > 0 ||
            orphanDocumentArtifactsFailed > 0 || forceDeletionStillPending

    /** Trabajo transitorio que impide consumir la intención durable de «borrar todas». */
    val hasRetryableForceDeletionWork: Boolean
        get() = draftDeleteRetryableFailed > 0 || forcedOcrDeleteRetryableFailed > 0 ||
            retryableFailedSteps.isNotEmpty() || purgeIntentFailed > 0 ||
            retentionDeleteRetryableFailed > 0 || encryptionRetryableFailed > 0 ||
            orphanDocumentArtifactsFailed > 0
}

/** Referencia plana a una imagen que sigue retenida para una compra terminal. */
data class RetainedImageRef(
    val businessId: BusinessId,
    val purchaseId: PurchaseId,
    val draftId: DraftId,
    /** Identidad durable de Room/Storage; jamás se deriva de la ruta mutable del archivo. */
    val imageId: ImageId,
    /** Ruta relativa al almacenamiento privado; nunca absoluta. */
    val relativeFilePath: String,
    val postedAt: Instant,
    /** Fecha estable de la fuente; permite que un cutoff sobreviva a open→confirmed. */
    val sourceImageCreatedAt: Instant = postedAt,
)

/** Resumen real devuelto por la eliminación de cuenta en la nube. */
data class AccountDeletionSummary(
    /** IDs de negocios de la nube borrados por completo (el usuario era su único miembro). */
    val businessesDeleted: List<String>,
    /** Membresías propias eliminadas en negocios que sobreviven. */
    val membershipsRemoved: Int,
    /** El servidor aceptó el trabajo durable, pero todavía no confirmó su finalización. */
    val isPending: Boolean = false,
) {
    init {
        require(membershipsRemoved >= 0)
    }
}

/**
 * Exportación contable auto-descriptiva del negocio activo. Incluye catálogos, compras, saldos,
 * libro de movimientos y metadatos de auditoría; no se presenta como una descarga integral de
 * cuenta o de borradores. [excludedData] enumera exactamente lo que queda fuera. Nunca contiene
 * rutas privadas, credenciales, tokens ni bytes de imágenes. Los importes permanecen exactos.
 */
data class UserDataExport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportKind: String = EXPORT_KIND,
    val excludedData: List<String> = EXCLUDED_DATA,
    val imageFilesIncluded: Boolean = false,
    val exportedAt: Instant,
    /** null cuando la app todavía no tiene negocio activo (exportación vacía). */
    val business: ExportedBusiness?,
    val suppliers: List<ExportedSupplier>,
    val units: List<ExportedUnit>,
    val inventoryLocations: List<ExportedInventoryLocation>,
    val products: List<ExportedProduct>,
    val supplierProductAliases: List<ExportedSupplierProductAlias>,
    val purchases: List<ExportedPurchase>,
    val inventoryBalances: List<ExportedInventoryBalance>,
    val stockMovements: List<ExportedStockMovement>,
    val auditEvents: List<ExportedAuditEvent>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "schemaVersion debe ser $SCHEMA_VERSION" }
        require(exportKind == EXPORT_KIND) { "exportKind no soportado: $exportKind" }
        require(excludedData == EXCLUDED_DATA) { "excludedData debe describir el alcance real" }
        require(!imageFilesIncluded) { "Este formato no incluye archivos binarios de imágenes" }
    }

    companion object {
        const val SCHEMA_VERSION = 4
        const val EXPORT_KIND = "ACCOUNTING_LEDGER"
        val EXCLUDED_DATA: List<String> = listOf(
            "IMAGE_FILE_BYTES",
            "DRAFTS_AND_OCR_ARTIFACTS",
            "SALE_HEADERS_AND_LINES",
            "DEBTS_AND_PAYMENTS",
            "APP_PREFERENCES",
            "SYNC_TRANSPORT_STATE",
            "ACCOUNT_PROFILE_AND_MEMBERSHIPS",
            "AUTH_CREDENTIALS_AND_TOKENS",
            "CACHE_AND_TEMPORARY_FILES",
        )
    }
}

/** Resultado verificable del destino SAF; nunca reduce un fallo parcial a éxito. */
enum class UserDataExportWriteStatus {
    WRITTEN,
    SOURCE_UNAVAILABLE,
    FAILED_DESTINATION_CLEAN,
    FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
}

data class UserDataExportWriteResult(
    val status: UserDataExportWriteStatus,
    val export: UserDataExport? = null,
) {
    init {
        require((status == UserDataExportWriteStatus.WRITTEN) == (export != null)) {
            "Solo WRITTEN puede transportar la exportación confirmada"
        }
    }
}

data class ExportedBusiness(
    val businessId: String,
    val legalName: String,
    val ruc: String?,
    val tradeName: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ExportedSupplier(
    val supplierId: String,
    val legalName: String,
    val ruc: String?,
    val tradeName: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class ExportedUnit(
    val unitId: String,
    val code: String,
    val name: String,
    val symbol: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ExportedInventoryLocation(
    val locationId: String,
    val name: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ExportedProduct(
    val productId: String,
    val unitId: String,
    val locationId: String?,
    val name: String,
    val sku: String?,
    val barcode: String?,
    val purchaseUnitId: String?,
    val purchaseFactor: BigDecimal?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class ExportedSupplierProductAlias(
    val aliasId: String,
    val supplierId: String,
    val productId: String,
    val alias: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ExportedPurchase(
    val purchaseId: String,
    val sourceDraftId: String,
    val supplierId: String,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: LocalDate,
    val currency: CurrencyCode,
    val subtotal: Money,
    val tax: Money,
    val otherCharges: Money,
    val adjustment: Money?,
    val adjustmentReason: String?,
    val total: Money,
    val status: String,
    val postedAt: Instant?,
    val preparedLogicalHash: String,
    val acceptedWarnings: List<String>,
    val duplicateOverride: ExportedDuplicateOverride?,
    val lines: List<ExportedPurchaseLine>,
    /** Metadatos no sensibles; jamás contiene la ruta privada ni bytes del comprobante. */
    val retainedImages: List<ExportedRetainedImage>,
)

data class ExportedDuplicateOverride(
    val existingPurchaseId: String,
    val reason: String,
    val actorId: String,
    val actorRole: String,
)

data class ExportedRetainedImage(
    val imageId: String,
    val pageIndex: Int,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int,
    val cropLeftFraction: Int?,
    val cropTopFraction: Int?,
    val cropRightFraction: Int?,
    val cropBottomFraction: Int?,
)

data class ExportedPurchaseLine(
    val purchaseLineId: String,
    val position: Int,
    val productId: String,
    val productName: String,
    val unitId: String,
    val unitCode: String,
    val unitSymbol: String?,
    val rawText: String,
    val description: String,
    val quantity: BigDecimal,
    val readUnitCost: BigDecimal,
    val appliedUnitCost: BigDecimal?,
    val inventoryQuantity: BigDecimal?,
    val discount: BigDecimal?,
    val taxMinorUnits: Long,
    val totalMinorUnits: Long,
    val productProvenance: String,
    val taxTreatment: String?,
    val taxEvidenceType: String?,
    val taxEvidenceValue: BigDecimal?,
)

data class ExportedInventoryBalance(
    val productId: String,
    val locationId: String,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: BigDecimal,
    val currency: String,
    val version: Long,
    val updatedAt: Instant,
    val alerts: List<String>,
)

data class ExportedStockMovement(
    val movementId: String,
    val productId: String,
    val locationId: String,
    val type: String,
    val quantityDelta: BigDecimal,
    val unitCost: BigDecimal?,
    val currency: String?,
    val purchaseId: String?,
    val purchaseLineId: String?,
    val purchaseDocumentNumber: String?,
    val occurredAt: Instant,
    val createdAt: Instant,
    val alerts: List<String>,
)

data class ExportedAuditEvent(
    val auditEventId: String,
    val purchaseId: String?,
    val eventType: String,
    val entityType: String,
    val entityId: String,
    val occurredAt: Instant,
)
