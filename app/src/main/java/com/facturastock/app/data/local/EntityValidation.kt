package com.facturastock.app.data.local

import com.facturastock.app.domain.model.AsciiPatterns
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import java.math.BigDecimal

/**
 * Validaciones compartidas por las entidades Room. Mantienen en la base de datos las mismas
 * invariantes que el dominio exige en memoria:
 *
 * - Los UUID se guardan canónicos: 36 caracteres en minúsculas y distintos del UUID nulo,
 *   la misma regla que los identificadores tipados del dominio.
 * - Las cantidades y los costos unitarios se guardan como texto decimal plano sin signo ni
 *   exponente (`toPlainString`). El texto conserva los ceros declarados, por lo que la escala
 *   sobrevive al ciclo persistir/leer sin convertirse nunca en binario.
 * - El dinero se guarda en unidades menores `Long` (céntimos en PEN), nunca como decimal.
 * - La confianza OCR es un entero de 0 a [MAX_CONFIDENCE] que representa 0.000 a 1.000.
 * - Los enums se persisten por nombre, nunca por ordinal.
 */

const val MAX_CONFIDENCE = 1_000

private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private val PlainDecimal = Regex("^\\d+(\\.\\d+)?$")
private val SignedPlainDecimal = Regex("^-?\\d+(\\.\\d+)?$")
private val RucPattern = Regex("^\\d{11}$")
private const val RUC_LENGTH = 11
private const val SHA256_HEX_LENGTH = 64
private const val MAX_UNIT_CODE_LENGTH = 16
private const val MAX_COSTING_INPUT_CHARACTERS = 166

// Cada fila leída de Room pasa por estas validaciones. El recorrido ASCII resuelve el caso
// normal sin abrir un matcher ICU; el patrón original decide sólo el texto que no reconoce.
private fun isPlainDecimal(value: String): Boolean =
    AsciiPatterns.isPlainDecimal(value) || PlainDecimal.matches(value)

private fun isSignedPlainDecimal(value: String): Boolean =
    AsciiPatterns.isSignedPlainDecimal(value) || SignedPlainDecimal.matches(value)

internal fun requireCanonicalUuid(value: String, field: String): String {
    require(AsciiPatterns.isLowercaseUuid(value) && value != NIL_UUID) {
        "$field debe ser un UUID canónico en minúsculas y distinto del nulo: ${value.take(64)}"
    }
    return value
}

internal fun requireCanonicalUuidOrNull(value: String?, field: String): String? =
    value?.let { requireCanonicalUuid(it, field) }

internal fun requireDecimalText(value: String, field: String, allowZero: Boolean): String {
    require(value.length <= ExactDecimalPolicy.MAX_INPUT_CHARACTERS && isPlainDecimal(value)) {
        "$field debe ser texto decimal plano sin signo ni exponente: ${value.take(64)}"
    }
    val decimal = try {
        BigDecimal(value)
    } catch (exception: NumberFormatException) {
        throw IllegalArgumentException("$field no es un decimal válido: ${value.take(64)}", exception)
    }
    require(ExactDecimalPolicy.supportsValue(decimal)) {
        "$field excede la precisión o escala admitida: ${value.take(64)}"
    }
    require(decimal.signum() > 0 || (allowZero && decimal.signum() == 0)) {
        "$field debe ser positivo${if (allowZero) " o cero" else ""}: ${value.take(64)}"
    }
    return value
}

internal fun requireDecimalTextOrNull(value: String?, field: String, allowZero: Boolean): String? =
    value?.let { requireDecimalText(it, field, allowZero) }

/**
 * Decimal exacto para resultados intermedios de costeo. Dos operandos admitidos por el dominio
 * pueden producir hasta 76 dígitos de precisión y 36 de escala al multiplicarse; persistirlos
 * con el límite ordinario 38/18 obligaría a redondear antes de calcular el promedio.
 */
internal fun requireCostingDecimalText(
    value: String,
    field: String,
    allowZero: Boolean,
    allowNegative: Boolean = false,
): String {
    val plain = if (allowNegative) isSignedPlainDecimal(value) else isPlainDecimal(value)
    require(value.length <= MAX_COSTING_INPUT_CHARACTERS && plain) {
        "$field debe ser texto decimal plano${if (allowNegative) " firmado" else ""} " +
            "sin exponente: ${value.take(64)}"
    }
    val decimal = try {
        BigDecimal(value)
    } catch (exception: NumberFormatException) {
        throw IllegalArgumentException("$field no es un decimal válido: ${value.take(64)}", exception)
    }
    require(
        InventoryCostingDecimalPolicy.supportsPersisted(decimal),
    ) {
        "$field excede precisión ${InventoryCostingDecimalPolicy.MAX_PERSISTED_PRECISION} o " +
            "escala ${InventoryCostingDecimalPolicy.MAX_PERSISTED_SCALE}: ${value.take(64)}"
    }
    require(
        (allowNegative || decimal.signum() >= 0) &&
            (allowZero || decimal.signum() != 0),
    ) {
        "$field debe ser ${if (allowNegative) "distinto de cero" else "positivo"}" +
            if (allowZero) " o cero" else ""
    }
    return value
}

internal fun requireCostingDecimalTextOrNull(
    value: String?,
    field: String,
    allowZero: Boolean,
    allowNegative: Boolean = false,
): String? = value?.let {
    requireCostingDecimalText(it, field, allowZero, allowNegative)
}

internal fun requireSignedNonZeroCostingDecimalText(value: String, field: String): String {
    requireCostingDecimalText(
        value = value,
        field = field,
        allowZero = false,
        allowNegative = true,
    )
    return value
}

/** Valida un decimal plano firmado y exacto, incluido cero (sin exponente ni `+`). */
internal fun requireSignedDecimalText(value: String, field: String): String {
    require(
        value.length <= ExactDecimalPolicy.MAX_INPUT_CHARACTERS && isSignedPlainDecimal(value),
    ) {
        "$field debe ser texto decimal plano firmado sin exponente: ${value.take(64)}"
    }
    val decimal = try {
        BigDecimal(value)
    } catch (exception: NumberFormatException) {
        throw IllegalArgumentException("$field no es un decimal válido: ${value.take(64)}", exception)
    }
    require(ExactDecimalPolicy.supportsValue(decimal)) {
        "$field excede la precisión o escala admitida: ${value.take(64)}"
    }
    return value
}

/** Valida un decimal plano firmado, exacto y distinto de cero (sin exponente ni `+`). */
internal fun requireSignedNonZeroDecimalText(value: String, field: String): String {
    val decimal = BigDecimal(requireSignedDecimalText(value, field))
    require(decimal.signum() != 0) { "$field no puede ser cero: ${value.take(64)}" }
    return value
}

internal fun requireCurrencyCode(value: String, field: String): String =
    CurrencyCode.of(value).value

internal fun requireCurrencyCodeOrNull(value: String?, field: String): String? =
    value?.let { requireCurrencyCode(it, field) }

internal fun requireMinorUnits(value: Long, field: String): Long {
    require(value >= 0L) { "$field no puede ser negativo: $value" }
    return value
}

internal fun requireMinorUnitsOrNull(value: Long?, field: String): Long? =
    value?.let { requireMinorUnits(it, field) }

internal fun requireConfidenceOrNull(value: Int?, field: String): Int? {
    if (value != null) {
        require(value in 0..MAX_CONFIDENCE) {
            "$field debe estar entre 0 y $MAX_CONFIDENCE: $value"
        }
    }
    return value
}

internal fun requireRuc(value: String, field: String): String {
    val trimmed = value.trim()
    require(AsciiPatterns.isAsciiDigits(trimmed, RUC_LENGTH) || RucPattern.matches(trimmed)) { "$field debe ser un RUC de 11 dígitos: ${value.take(32)}" }
    return trimmed
}

internal fun requireRucOrNull(value: String?, field: String): String? =
    value?.let { requireRuc(it, field) }

internal fun requireSha256(value: String, field: String): String {
    require(AsciiPatterns.isLowerHex(value, SHA256_HEX_LENGTH)) { "$field debe ser un hash SHA-256 en minúsculas" }
    return value
}

internal fun requireUnitCode(value: String, field: String): String {
    val normalized = value.trim().uppercase()
    require(AsciiPatterns.isUpperAlphanumeric(normalized, 1, MAX_UNIT_CODE_LENGTH)) { "$field debe ser un código de unidad válido: $value" }
    return normalized
}

internal fun requireDocumentNumberOrNull(value: String?, field: String): String? {
    if (value != null) {
        require(InvoiceDocumentNumber.parseCanonical(value) != null) {
            "$field debe tener formato serie-correlativo (p. ej. F001-00012345): ${value.take(32)}"
        }
    }
    return value
}

internal fun requireIsoDateOrNull(value: String?, field: String): String? {
    if (value != null) {
        val parsed = runCatching { java.time.LocalDate.parse(value) }.getOrNull()
        require(parsed != null) { "$field debe ser una fecha ISO_LOCAL_DATE: ${value.take(32)}" }
    }
    return value
}

internal fun requireText(value: String, field: String, maxLength: Int = 256): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty() && trimmed.length <= maxLength) {
        "$field debe tener entre 1 y $maxLength caracteres"
    }
    return trimmed
}

internal fun requireTextOrNull(value: String?, field: String, maxLength: Int = 256): String? =
    value?.let { requireText(it, field, maxLength) }

/**
 * Compatibilidad de lectura para códigos creados antes del contrato ASCII de
 * [com.facturastock.app.domain.model.BarcodeValue].
 *
 * Las versiones 1..20 admitían texto Unicode ya recortado. Room ejecuta los `init` de las
 * entidades al hidratar esas filas, por lo que este guard conserva exactamente la antigua
 * invariante sin convertirla en una puerta de escritura nueva. Las escrituras de producto pasan
 * por `CatalogCanonicalizer.barcode` y SQLite impide insertar o sustituir un código no ASCII.
 */
internal fun requirePersistedLegacyBarcodeOrNull(
    value: String?,
    field: String,
): String? {
    requireTextOrNull(value, field, 128)
    require(value == value?.trim()?.takeIf(String::isNotEmpty)) {
        "$field heredado debe conservar la forma canónica persistida"
    }
    return value
}

internal fun requireTimestamps(createdAt: Long, updatedAt: Long) {
    require(createdAt >= 0L) { "createdAt no puede ser negativo: $createdAt" }
    require(updatedAt >= createdAt) { "updatedAt ($updatedAt) no puede ser anterior a createdAt ($createdAt)" }
}

internal inline fun <reified T : Enum<T>> requireEnumName(value: String, field: String): String {
    require(enumValues<T>().any { it.name == value }) { "$field tiene un valor desconocido: $value" }
    return value
}
