package com.facturastock.app.domain.model

/**
 * Comprobaciones ASCII para los formatos que se validan al leer cada fila (UUID, decimales
 * planos, hashes, monedas). Una expresión regular de Android abre un matcher ICU en cada
 * llamada; con miles de filas por pantalla ese costo dominaba la lectura de inventario.
 *
 * Cada función acepta exactamente lo que acepta su patrón equivalente con caracteres ASCII.
 * Donde el patrón usa `\d`, el llamador conserva la expresión original como respaldo para el
 * texto que no reconoce este recorrido, de modo que nunca se rechaza algo antes aceptado.
 */
internal object AsciiPatterns {
    /** `^\d+(\.\d+)?$` limitado a dígitos ASCII. */
    fun isPlainDecimal(value: CharSequence): Boolean = isUnsignedDecimalFrom(value, 0)

    /** `^-?\d+(\.\d+)?$` limitado a dígitos ASCII. */
    fun isSignedPlainDecimal(value: CharSequence): Boolean = isUnsignedDecimalFrom(value, if (value.isNotEmpty() && value[0] == '-') 1 else 0)

    /** `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`. */
    fun isLowercaseUuid(value: CharSequence): Boolean {
        if (value.length != UUID_LENGTH) return false
        for (index in 0 until UUID_LENGTH) {
            val char = value[index]
            val valid =
                when (index) {
                    8, 13, 18, 23 -> char == '-'
                    else -> isLowerHex(char)
                }
            if (!valid) return false
        }
        return true
    }

    /** `^[0-9a-f]{length}$`. */
    fun isLowerHex(
        value: CharSequence,
        length: Int,
    ): Boolean {
        if (value.length != length) return false
        for (index in 0 until length) {
            if (!isLowerHex(value[index])) return false
        }
        return true
    }

    /** `^[0-9]{length}$`. */
    fun isAsciiDigits(
        value: CharSequence,
        length: Int,
    ): Boolean {
        if (value.length != length) return false
        for (index in 0 until length) {
            if (value[index] !in '0'..'9') return false
        }
        return true
    }

    /** `^[A-Z]{length}$`. */
    fun isUpperLetters(
        value: CharSequence,
        length: Int,
    ): Boolean {
        if (value.length != length) return false
        for (index in 0 until length) {
            if (value[index] !in 'A'..'Z') return false
        }
        return true
    }

    /** `^[A-Z0-9]{minLength,maxLength}$`. */
    fun isUpperAlphanumeric(
        value: CharSequence,
        minLength: Int,
        maxLength: Int,
    ): Boolean {
        if (value.length !in minLength..maxLength) return false
        for (index in value.indices) {
            val char = value[index]
            if (char !in 'A'..'Z' && char !in '0'..'9') return false
        }
        return true
    }

    private fun isUnsignedDecimalFrom(
        value: CharSequence,
        start: Int,
    ): Boolean {
        val length = value.length
        var index = start
        while (index < length && value[index] in '0'..'9') index++
        if (index == start) return false
        if (index == length) return true
        if (value[index] != '.') return false
        val fractionStart = ++index
        while (index < length && value[index] in '0'..'9') index++
        return index == length && index > fractionStart
    }

    private fun isLowerHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'

    private const val UUID_LENGTH = 36
}
