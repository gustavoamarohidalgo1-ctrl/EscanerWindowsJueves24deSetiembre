package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.DraftId
import java.time.Instant

/** Campos editables de la revisión de cabecera, con orden estable para persistencia. */
enum class InvoiceHeaderEditField(val stableOrder: Int) {
    SUPPLIER_RUC(10),
    SUPPLIER_LEGAL_NAME(20),
    DOCUMENT_TYPE(30),
    DOCUMENT_SERIES(40),
    DOCUMENT_NUMBER(50),
    ISSUE_DATE(60),
    CURRENCY(70),
    SUBTOTAL(80),
    IGV(90),
    OTHER_CHARGES(100),
    TOTAL(110),
}

/**
 * Formulario durable de revisión. Todos los valores son texto deliberadamente: un autosave debe
 * conservar también estados parciales o todavía inválidos (por ejemplo `20/` o `12,`). La
 * proyección tipada de [InvoiceDraft] se actualiza por separado solo con valores ya resueltos.
 *
 * [touchedFields] registra una edición o confirmación humana explícita, no mera presencia ni solo
 * foco: un campo confirmado y luego vaciado permanece distinguible de otro que nunca fue revisado.
 * [revision] ordena escrituras concurrentes; puede comenzar en cero y solo una revisión
 * estrictamente mayor reemplaza a la persistida.
 */
data class InvoiceHeaderEdit(
    val draftId: DraftId,
    val supplierRuc: String? = null,
    val supplierLegalName: String? = null,
    val documentType: String? = null,
    val documentSeries: String? = null,
    val documentNumber: String? = null,
    val issueDate: String? = null,
    val currency: String? = null,
    val subtotal: String? = null,
    val igv: String? = null,
    val otherCharges: String? = null,
    val total: String? = null,
    val revision: Long,
    val touchedFields: Set<InvoiceHeaderEditField> = emptySet(),
    val updatedAt: Instant,
) {
    init {
        require(revision >= 0L) { "revision no puede ser negativa: $revision" }
        require(!updatedAt.isBefore(Instant.EPOCH)) { "updatedAt no puede ser anterior al epoch" }
        validatePartial(supplierRuc, "supplierRuc", 64)
        validatePartial(supplierLegalName, "supplierLegalName", 512)
        validatePartial(documentType, "documentType", 64)
        validatePartial(documentSeries, "documentSeries", 32)
        validatePartial(documentNumber, "documentNumber", 32)
        validatePartial(issueDate, "issueDate", 64)
        validatePartial(currency, "currency", 16)
        validatePartial(subtotal, "subtotal", 64)
        validatePartial(igv, "igv", 64)
        validatePartial(otherCharges, "otherCharges", 64)
        validatePartial(total, "total", 64)
    }
}

private fun validatePartial(value: String?, field: String, maxLength: Int) {
    require(value == null || (value.length <= maxLength && '\u0000' !in value)) {
        "$field excede $maxLength caracteres o contiene NUL"
    }
}
