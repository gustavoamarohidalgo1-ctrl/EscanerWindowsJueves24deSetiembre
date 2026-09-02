package com.facturastock.app.domain.model

/**
 * Evaluación local del RUC peruano en dos niveles:
 *
 * - [isWellFormed] exige exactamente 11 dígitos ASCII (tras recortar espacios extremos). Es la
 *   condición bloqueante para guardar.
 * - [hasValidChecksum] comprueba la consistencia matemática del dígito de control por módulo 11
 *   (pesos 5, 4, 3, 2, 7, 6, 5, 4, 3, 2 sobre los primeros 10 dígitos). Se usa como advertencia
 *   no bloqueante: el usuario decide guardar con el aviso visible y la app jamás modifica el RUC
 *   ingresado.
 *
 * Ninguna de estas reglas confirma que el RUC exista, esté activo o pertenezca a una persona.
 * Esta clase no consulta SUNAT ni otra fuente externa.
 */
object RucValidator {
    private val Weights = intArrayOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)

    fun isWellFormed(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.length == 11 && trimmed.all { character -> character in '0'..'9' }
    }

    fun hasValidChecksum(input: String): Boolean {
        val trimmed = input.trim()
        if (!isWellFormed(trimmed)) return false
        val sum = Weights.indices.sumOf { index -> (trimmed[index] - '0') * Weights[index] }
        val complement = 11 - (sum % 11)
        val expected = when (complement) {
            10 -> 0
            11 -> 1
            else -> complement
        }
        return trimmed[10] - '0' == expected
    }
}
