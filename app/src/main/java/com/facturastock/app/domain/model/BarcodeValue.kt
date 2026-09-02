package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError

/**
 * Valor canónico de un código de barras escrito o leído localmente.
 *
 * El contrato es deliberadamente agnóstico a la simbología: no exige longitud ni checksum de
 * GTIN/EAN/UPC. Conserva ceros iniciales, mayúsculas/minúsculas y espacios interiores; únicamente
 * recorta espacios ASCII (`U+0020`) de los bordes. El resultado siempre contiene entre 1 y 128
 * caracteres ASCII imprimibles (`U+0020..U+007E`). Por tanto, controles, marcas bidi y cualquier
 * carácter Unicode no ASCII se rechazan en vez de normalizarse silenciosamente.
 */
@JvmInline
value class BarcodeValue private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MIN_LENGTH: Int = 1
        const val MAX_LENGTH: Int = 128

        /** Canonicaliza [input] o lanza un error de validación cerrado. */
        fun of(input: String): BarcodeValue {
            val canonical = input.trim(ASCII_SPACE)
            if (
                canonical.length !in MIN_LENGTH..MAX_LENGTH ||
                canonical.any { character -> character.code !in PRINTABLE_ASCII_RANGE }
            ) {
                throw DomainRuleViolation(ValidationError.InvalidBarcode)
            }
            return BarcodeValue(canonical)
        }

        /** Variante de consulta: devuelve `null` y nunca expone el valor rechazado. */
        fun parse(input: String): BarcodeValue? =
            try {
                of(input)
            } catch (_: DomainRuleViolation) {
                null
            }

        /**
         * Canonicaliza un campo opcional. `null`, vacío o solo espacios ASCII representan ausencia;
         * cualquier otra entrada debe cumplir el mismo contrato estricto que [of].
         */
        fun optionalOf(input: String?): BarcodeValue? {
            if (input == null || input.all { character -> character == ASCII_SPACE }) return null
            return of(input)
        }

        fun isValid(input: String): Boolean = parse(input) != null

        fun isValidOptional(input: String?): Boolean =
            input == null || input.all { character -> character == ASCII_SPACE } || isValid(input)

        private const val ASCII_SPACE: Char = ' '
        private val PRINTABLE_ASCII_RANGE: IntRange = 0x20..0x7E
    }
}
