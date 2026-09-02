package com.facturastock.app.domain.model

enum class DraftStatus {
    CREATED,
    CAPTURED,
    OCR_PROCESSING,
    OCR_READY,
    NEEDS_REVIEW,
    READY_TO_POST,
    COMMITTED,
    ERROR,
}

/** Solo estos estados conservan fuentes originales garantizadas y admiten cambiar páginas. */
val DraftStatus.acceptsImageMutations: Boolean
    get() = this == DraftStatus.CREATED || this == DraftStatus.CAPTURED || this == DraftStatus.ERROR

enum class PurchaseStatus {
    DRAFT,
    POSTED,
    VOIDED,
}

/** El borrador funciona como carrito local; una venta publicada es inmutable. */
enum class SaleStatus {
    DRAFT,
    POSTED,
}

/** Motivo contable de una entrada inmutable del libro de existencias. */
enum class StockMovementType {
    PURCHASE,
    SALE,
    ADJUSTMENT,
    VOID,
}

/** Eventos de auditoría relevantes para compras e inventario. */
enum class AuditEventType {
    PURCHASE_POSTED,
    SALE_POSTED,
    PURCHASE_VOIDED,
    PURCHASE_DUPLICATE_OVERRIDE,
    STOCK_ADJUSTED,
    /** Conflicto de respaldo resuelto por una persona conservando el registro de la nube. */
    SYNC_CONFLICT_RESOLVED,
    /** Conflicto optimista de producto/proveedor resuelto sin last-write-wins. */
    CATALOG_SYNC_CONFLICT_RESOLVED,
    /** Revisión de reconciliación local↔nube registrada; nunca ajusta saldos por sí misma. */
    SYNC_RECONCILED,
}

/** Ciclo local de una operación durable pendiente de procesamiento. */
enum class OutboxOperationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    /** Requiere conciliación explícita antes de volver a intentar el respaldo. */
    CONFLICT,
    /**
     * CONFLICT resuelto por una persona conservando el registro remoto: la operación local
     * no se reintenta jamás y la compra local permanece intacta (el documento ya está en la
     * nube, publicado desde otro dispositivo).
     */
    RESOLVED,
}

/** Ciclo de vida de los registros de catálogo (negocio, proveedor, unidad, ubicación, producto). */
enum class CatalogStatus {
    ACTIVE,
    ARCHIVED,
}
