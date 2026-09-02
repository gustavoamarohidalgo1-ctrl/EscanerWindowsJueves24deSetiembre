package com.facturastock.app.domain.repository

/** Fuente local de la única página sintética; nunca lee red, galería ni información personal. */
fun interface DemoInvoiceSource {
    suspend fun jpegBytes(): ByteArray
}
