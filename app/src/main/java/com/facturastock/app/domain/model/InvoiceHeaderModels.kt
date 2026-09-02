package com.facturastock.app.domain.model

/** Tipos de comprobante que el extractor puede afirmar a partir de texto explícito. */
enum class PurchaseDocumentType {
    INVOICE,
    SALES_RECEIPT,
    CREDIT_NOTE,
    DEBIT_NOTE,
}

/**
 * Número de comprobante separado sin convertir sus componentes a número, para conservar ceros.
 */
data class InvoiceDocumentNumber(
    val series: String,
    val correlative: String,
) {
    init {
        require(SERIES_PATTERN.matches(series)) { "Serie de comprobante inválida" }
        require(CORRELATIVE_PATTERN.matches(correlative)) { "Correlativo de comprobante inválido" }
    }

    val normalized: String
        get() = "$series-$correlative"

    companion object {
        private val CANONICAL_PATTERN = Regex("^([A-Z0-9]{1,4})-(\\d{1,12})$")
        private val SERIES_PATTERN = Regex("^[A-Z0-9]{1,4}$")
        private val CORRELATIVE_PATTERN = Regex("^\\d{1,12}$")

        fun parseCanonical(input: String): InvoiceDocumentNumber? {
            val match = CANONICAL_PATTERN.matchEntire(input) ?: return null
            return InvoiceDocumentNumber(
                series = match.groupValues[1],
                correlative = match.groupValues[2],
            )
        }
    }
}
