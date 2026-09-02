package com.facturastock.app.ui.format

import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.config.AppConfiguration
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap
import java.util.Currency
import java.util.Locale

/** Locale de presentación de la app: español de Perú, coherente con los valores iniciales. */
private val FacturaStockLocale: Locale = Locale.forLanguageTag("es-PE")
private val IsoCurrencyCode = Regex("^[A-Z]{3}$")
private val CurrencySymbols = ConcurrentHashMap<String, String>()
private val LocalizedDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(FacturaStockLocale)
private val LocalizedDateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(FacturaStockLocale)
private val CurrencyFormats = ThreadLocal.withInitial {
    mutableMapOf<String, NumberFormat>()
}

/**
 * Formatea dinero para mostrarlo en pantalla: símbolo de la moneda según el locale de
 * presentación (PEN → "S/") y la escala exacta ISO 4217 del importe (p. ej. "S/ 1,234.56").
 */
fun Money.formatForDisplay(): String {
    val format = checkNotNull(CurrencyFormats.get()).getOrPut(currency.value) {
        NumberFormat.getCurrencyInstance(FacturaStockLocale).apply {
            currency = Currency.getInstance(this@formatForDisplay.currency.value)
            minimumFractionDigits = scale
            maximumFractionDigits = scale
        }
    }
    return format.format(toMajor())
}

/** Igual que [formatForDisplay], pero hace visible el signo positivo de una conciliación. */
fun Money.formatSignedForDisplay(): String {
    val formatted = formatForDisplay()
    return if (minorUnits > 0L) "+$formatted" else formatted
}

/** Símbolo localizado para etiquetas de campos (PEN → "S/"); conserva el código inválido. */
fun currencyLabelForDisplay(currencyCode: String): String {
    val normalized = currencyCode.trim()
    if (!IsoCurrencyCode.matches(normalized)) return normalized
    return CurrencySymbols.getOrPut(normalized) {
        runCatching {
            Currency.getInstance(normalized).getSymbol(FacturaStockLocale)
        }.getOrDefault(normalized)
    }
}

/** Convierte un decimal canónico y su moneda al mismo formato usado en toda la aplicación. */
fun formatCurrencyAmountForDisplay(currencyCode: String, amount: String): String? {
    val normalized = currencyCode.trim()
    if (normalized.isBlank() || amount.isBlank()) return null
    // El contrato de la pantalla admite código o símbolo. Un símbolo como "S/" no debe
    // recorrer una excepción de Currency por cada importe de cada tarjeta durante el scroll.
    if (!IsoCurrencyCode.matches(normalized)) return "$normalized $amount"
    return runCatching {
        Money.fromMajor(amount, CurrencyCode.of(normalized)).formatForDisplay()
    }.getOrNull()
}

/** Fecha localizada en español (p. ej. "1 ago 2026"). */
fun LocalDate.formatForDisplay(): String = LocalizedDateFormatter.format(this)

/** Fecha y hora localizadas en español (p. ej. "1 ago 2026, 12:30"). */
fun Instant.formatForDisplay(zoneId: ZoneId = AppConfiguration.DEFAULT_ZONE_ID): String =
    LocalizedDateTimeFormatter.format(atZone(zoneId))
