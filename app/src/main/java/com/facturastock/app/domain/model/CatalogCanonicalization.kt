package com.facturastock.app.domain.model

import java.util.Locale

/** Formas persistidas usadas por los índices únicos de catálogo. */
object CatalogCanonicalizer {
    fun ruc(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

    fun sku(value: String?): String? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.uppercase(Locale.ROOT)

    /** Preserva ceros y capitalización; delega forma y límites al contrato reusable. */
    fun barcode(value: String?): String? = BarcodeValue.optionalOf(value)?.value

    fun unitCode(value: String): String = value.trim().uppercase(Locale.ROOT)
}
