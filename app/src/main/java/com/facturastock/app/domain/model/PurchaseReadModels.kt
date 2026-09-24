package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Estado observable de la copia local y su operación durable de sincronización. */
enum class PurchaseSyncState {
    /** Existe únicamente como dato local; todavía no se encoló ningún respaldo. */
    DRAFT,
    /** La copia local ya es durable y espera conectividad/worker de respaldo. */
    PENDING_SYNC,
    SYNCING,
    SYNCED,
    ERROR,
    /** El respaldo remoto exige una resolución explícita; la copia local no se descarta. */
    CONFLICT,
    /**
     * Conflicto resuelto por una persona conservando el registro de la nube (publicado desde
     * otro dispositivo); la copia local permanece y el respaldo local no se reintenta.
     */
    RESOLVED,
}

/** Proyección liviana de Room para la lista de compras. */
data class PurchaseReadSummary(
    val purchaseId: PurchaseId,
    val businessId: BusinessId,
    val sourceDraftId: DraftId,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val documentType: PurchaseDocumentType,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: LocalDate,
    val currency: CurrencyCode,
    val total: Money,
    val status: PurchaseStatus,
    val syncState: PurchaseSyncState,
    val lineCount: Int,
    val productCount: Int,
    val postedAt: Instant?,
    /** Productos distintos creados dentro de este borrador y publicados con la compra. */
    val createdProductCount: Int = 0,
    /** Productos distintos que ya existían en el catálogo al vincular la compra. */
    val existingProductCount: Int = 0,
    /** Productos históricos cuya procedencia no puede reconstruirse sin inventar datos. */
    val unknownProductCount: Int = productCount,
    /** Error sanitario de la última operación de respaldo; nunca contiene el comprobante. */
    val lastSyncError: String? = null,
    /** Última transición persistida de la outbox; sobrevive a reinicios del proceso. */
    val lastSyncAttemptAt: Instant? = null,
) {
    init {
        require(lineCount >= 0)
        require(productCount in 0..lineCount)
        require(createdProductCount >= 0)
        require(existingProductCount >= 0)
        require(unknownProductCount >= 0)
        require(createdProductCount + existingProductCount + unknownProductCount == productCount) {
            "Los conteos de procedencia deben sumar productCount"
        }
        require(total.currency == currency)
        require(lastSyncError == null || lastSyncError.isNotBlank())
    }

    val canonicalDocumentNumber: String
        get() = "$documentSeries-$documentNumber"
}

/**
 * Ventana solicitada exclusivamente por la UI del historial. [visiblePages] controla cuántas
 * páginas debe reconstruir el repositorio mediante keyset; no afecta al flujo completo usado por
 * exportación y mantenimiento de privacidad.
 *
 * El texto conserva la búsqueda histórica de la app: substring literal, sin comodines, con
 * `lowercase(Locale.ROOT)` sobre proveedor, RUC, comprobante canónico y fecha ISO. Los filtros de
 * estado y sincronización pueden aplicarse antes en SQL porque su comparación es exacta.
 */
data class PurchaseHistoryRequest(
    val query: String = "",
    val status: PurchaseStatus? = null,
    val syncState: PurchaseSyncState? = null,
    val pageSize: Int,
    val visiblePages: Int,
) {
    private val normalizedQuery = query.trim().lowercase(Locale.ROOT)

    init {
        require(pageSize in 1..100)
        require(visiblePages >= 1)
    }

    val visibleItemLimit: Int
        get() = Math.multiplyExact(pageSize, visiblePages)

    fun matches(purchase: PurchaseReadSummary): Boolean {
        if (status != null && purchase.status != status) return false
        if (syncState != null && purchase.syncState != syncState) return false
        return normalizedQuery.isEmpty() ||
            purchase.supplierLegalName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            purchase.supplierRuc.orEmpty().contains(normalizedQuery) ||
            purchase.canonicalDocumentNumber.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            purchase.issueDate.toString().contains(normalizedQuery)
    }
}

/** Resultado acotado para Compose; [hasMore] significa que existe otra coincidencia real. */
data class PurchaseHistoryPage(
    val items: List<PurchaseReadSummary>,
    val hasMore: Boolean,
)

/** Línea histórica congelada; conserva tanto la lectura como el costo aplicado a inventario. */
data class PurchaseReadLine(
    val purchaseLineId: String,
    val position: Int,
    val productId: ProductId,
    val productName: String,
    val unitId: UnitId,
    val unitCode: String,
    val unitSymbol: String?,
    val rawText: String,
    val description: String,
    val quantity: BigDecimal,
    val readUnitCost: UnitCost,
    val tax: Money,
    val total: Money,
    val appliedUnitCost: UnitCost?,
    /** Total de costo exacto aplicado a inventario; null solo para historia anterior a v13. */
    val appliedCostTotal: BigDecimal? = null,
    val inventoryQuantity: BigDecimal?,
    val discount: BigDecimal?,
    val productProvenance: PurchaseProductProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
    /** Decisión tributaria congelada; null solo para historia anterior al esquema de costeo. */
    val taxTreatment: InventoryTaxTreatment? = null,
    /** Evidencia tributaria congelada; viaja junto con [taxTreatment] o ambas quedan ausentes. */
    val taxEvidence: InventoryTaxEvidence? = null,
) {
    init {
        require(position >= 0)
        require(productName.isNotBlank())
        require(description.isNotBlank())
        require(quantity.signum() > 0)
        require(tax.currency == total.currency && readUnitCost.currency == total.currency)
        require(appliedUnitCost == null || appliedUnitCost.currency == total.currency)
        require(appliedCostTotal == null || appliedCostTotal.signum() >= 0)
        require((taxTreatment == null) == (taxEvidence == null)) {
            "Tratamiento y evidencia tributaria deben estar ambos presentes o ausentes"
        }
        require(taxTreatment != InventoryTaxTreatment.UNKNOWN) {
            "Una compra publicada no puede exponer tratamiento tributario UNKNOWN"
        }
    }
}

/** Movimiento append-only enlazado a la compra y a una única línea. */
data class PurchaseReadMovement(
    val movementId: String,
    val purchaseId: PurchaseId,
    val purchaseLineId: String,
    val productId: ProductId,
    val productName: String,
    val locationId: LocationId,
    val locationName: String,
    val type: StockMovementType,
    val quantityDelta: BigDecimal,
    val unitCost: UnitCost?,
    val occurredAt: Instant,
)

/** Entrada de la bitácora durable. El payload se conserva en Room, no se expone como texto UI. */
data class PurchaseReadAuditEvent(
    val auditEventId: String,
    val eventType: AuditEventType,
    val entityType: String,
    val entityId: String,
    val occurredAt: Instant,
)

/** Evento business-wide; reconciliación y conflictos de catálogo no tienen purchaseId. */
data class BusinessAuditEventRead(
    val auditEventId: String,
    val purchaseId: PurchaseId?,
    val eventType: AuditEventType,
    val entityType: String,
    val entityId: String,
    val occurredAt: Instant,
)

/** Excepción exacta durable leída desde la compra, nunca reconstruida desde el AuditEvent. */
data class PurchaseReadDuplicateOverride(
    val existingPurchaseId: PurchaseId,
    val reason: String,
    val actorId: String,
    val actorRole: PurchaseOverrideRole,
) {
    init {
        require(reason == reason.trim() && reason.length in 10..500)
        require(actorId.isNotBlank() && actorId.length <= 128)
        require(actorRole == PurchaseOverrideRole.OWNER || actorRole == PurchaseOverrideRole.MANAGER)
    }
}

/** Metadatos Room de una imagen que sigue retenida en el almacenamiento privado. */
data class PurchaseRetainedImage(
    val imageId: ImageId,
    val pageIndex: Int,
    val relativeFilePath: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int,
    val cropLeftFraction: Int?,
    val cropTopFraction: Int?,
    val cropRightFraction: Int?,
    val cropBottomFraction: Int?,
)

/** Agregado de solo lectura que reconstruye el resultado publicado desde Flows de Room. */
data class PurchaseReadDetail(
    val summary: PurchaseReadSummary,
    val supplierId: SupplierId,
    val subtotal: Money,
    val tax: Money,
    val otherCharges: Money,
    /** Conciliación exacta: suma de líneas + ajuste = total; nunca se oculta si no es cero. */
    val adjustment: Money?,
    val lines: List<PurchaseReadLine>,
    val movements: List<PurchaseReadMovement>,
    val auditEvents: List<PurchaseReadAuditEvent>,
    val images: List<PurchaseRetainedImage>,
    val preparedLogicalHash: String,
    val acceptedWarnings: List<String>,
    /** Motivo humano congelado; null solo para compras históricas anteriores al codec v3. */
    val adjustmentReason: String? = null,
    /** Metadatos de una excepción de duplicado exacto; null para la identidad primaria. */
    val duplicateOverride: PurchaseReadDuplicateOverride? = null,
) {
    init {
        val currency = summary.currency
        require(listOf(subtotal, tax, otherCharges, summary.total).all { it.currency == currency })
        require(adjustment == null || adjustment.currency == currency)
        require(adjustmentReason == null || adjustmentReason.isNotBlank())
        require(adjustmentReason == null || adjustment != null) {
            "No puede existir motivo sin un ajuste visible"
        }
        require(lines.size == summary.lineCount)
        require(lines.map(PurchaseReadLine::position) == lines.indices.toList())
        require(lines.all { it.total.currency == currency })
        require(movements.all { it.purchaseId == summary.purchaseId })
        require(AsciiPatterns.isLowerHex(preparedLogicalHash, 64))
        require(
            lines.groupBy(PurchaseReadLine::productId).values.all { productLines ->
                productLines.map(PurchaseReadLine::productProvenance).distinct().size == 1
            },
        ) { "Un producto no puede mezclar procedencias dentro de una compra" }
        val distinctProductsByProvenance = lines
            .distinctBy(PurchaseReadLine::productId)
            .groupingBy(PurchaseReadLine::productProvenance)
            .eachCount()
        require(
            distinctProductsByProvenance[PurchaseProductProvenance.CREATED_IN_DRAFT].orZero() ==
                summary.createdProductCount,
        ) { "El conteo de productos creados diverge de las líneas" }
        require(
            distinctProductsByProvenance[PurchaseProductProvenance.EXISTING].orZero() ==
                summary.existingProductCount,
        ) { "El conteo de productos existentes diverge de las líneas" }
        require(
            distinctProductsByProvenance[PurchaseProductProvenance.UNKNOWN_LEGACY].orZero() ==
                summary.unknownProductCount,
        ) { "El conteo de productos sin procedencia diverge de las líneas" }
    }

    val productCount: Int
        get() = summary.productCount
}

private fun Int?.orZero(): Int = this ?: 0
