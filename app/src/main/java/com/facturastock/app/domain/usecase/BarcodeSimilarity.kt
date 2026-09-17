package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.BarcodeValue

/**
 * Evidencia conservadora para sugerir un producto cuando faltan dígitos en alguno de sus códigos.
 *
 * No corrige códigos, verifica checksums ni autoriza un enlace: toda sugerencia necesita revisión
 * humana. Los ceros iniciales cuentan como dígitos y las entradas se comparan sin normalizarlas.
 */
object BarcodeSimilarity {
    /**
     * Devuelve entre 1 y 3 si el código corto resulta de omitir esa cantidad de dígitos del largo,
     * conservando el orden. La omisión puede estar en la lectura o en el código guardado.
     *
     * Solo admite dígitos ASCII, un código largo de al menos 8 caracteres y uno corto de al menos
     * 5, ambos dentro del límite de [BarcodeValue]. Una coincidencia exacta devuelve `null` porque
     * debe resolverse mediante la búsqueda exacta. Tiempo O(n) y memoria adicional O(1).
     */
    fun missingDigits(
        scanned: String,
        stored: String,
    ): Int? {
        if (scanned.length > BarcodeValue.MAX_LENGTH || stored.length > BarcodeValue.MAX_LENGTH) {
            return null
        }
        val shorter = if (scanned.length < stored.length) scanned else stored
        val longer = if (scanned.length < stored.length) stored else scanned
        val missing = longer.length - shorter.length
        if (missing !in 1..3 || shorter.length < 5 || longer.length < 8) return null
        if (shorter.any { it !in '0'..'9' } || longer.any { it !in '0'..'9' }) return null

        var matched = 0
        for (digit in longer) {
            if (digit == shorter[matched]) {
                matched++
                if (matched == shorter.length) return missing
            }
        }
        return null
    }
}
