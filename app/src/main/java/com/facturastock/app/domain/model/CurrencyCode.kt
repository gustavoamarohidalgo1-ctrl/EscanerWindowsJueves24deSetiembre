package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@JvmInline
value class CurrencyCode private constructor(val value: String) {
    val defaultFractionDigits: Int
        get() = fractionDigitsByCode.getOrPut(value) { Currency.getInstance(value).defaultFractionDigits }

    override fun toString(): String = value

    companion object {
        fun of(input: String): CurrencyCode {
            // Las filas persistidas ya traen el código canónico: se evita recortar y copiar.
            val normalized = if (AsciiPatterns.isUpperLetters(input, ISO_CODE_LENGTH)) {
                input
            } else {
                input.trim().uppercase(Locale.ROOT)
            }
            if (!AsciiPatterns.isUpperLetters(normalized, ISO_CODE_LENGTH)) {
                throw invalidCurrency(input)
            }
            validatedCodes[normalized]?.let { return CurrencyCode(it) }

            val currency = try {
                Currency.getInstance(normalized)
            } catch (exception: IllegalArgumentException) {
                throw invalidCurrency(input, exception)
            }

            if (currency.defaultFractionDigits < 0) {
                throw invalidCurrency(input)
            }

            validatedCodes.putIfAbsent(normalized, currency.currencyCode)
            return CurrencyCode(currency.currencyCode)
        }

        private const val ISO_CODE_LENGTH = 3

        // Cada fila con moneda pasa por aquí; en Android Currency consulta ICU en cada llamada.
        // Sólo se guardan códigos ISO ya aceptados, así que ambos mapas quedan acotados.
        private val validatedCodes = ConcurrentHashMap<String, String>()
        private val fractionDigitsByCode = ConcurrentHashMap<String, Int>()

        private fun invalidCurrency(
            input: String,
            cause: Throwable? = null,
        ): DomainRuleViolation = DomainRuleViolation(
            error = ValidationError.InvalidCurrencyCode(input),
            cause = cause,
        )
    }
}
