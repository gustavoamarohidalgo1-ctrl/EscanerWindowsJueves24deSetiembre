package com.facturastock.app.domain.model

/**
 * Borrador reciente listo para mostrar en listas: conserva el [InvoiceDraft] completo y
 * añade [supplierName], la razón social del proveedor resuelta por `supplierId` (null si el
 * borrador aún no tiene proveedor o este ya no existe en el catálogo).
 */
data class RecentDraft(
    val draft: InvoiceDraft,
    val supplierName: String?,
)
