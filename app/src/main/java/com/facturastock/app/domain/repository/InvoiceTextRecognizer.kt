package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.InvoiceTextDocument

/**
 * Puerto OCR local. La lista recibida define el orden de páginas y la implementación no puede
 * exponer tipos de Android ni del motor de reconocimiento.
 */
fun interface InvoiceTextRecognizer {
    suspend fun recognize(pages: List<OcrImageFile>): InvoiceTextDocument
}
