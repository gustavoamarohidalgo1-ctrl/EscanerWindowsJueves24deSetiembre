package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.util.Currency
import java.util.Locale

@JvmInline
value class CurrencyCode private constructor(val value: String) {
    val defaultFractionDigits: Int
        get() = Currency.getInstance(value).defaultFractionDigits

    override fun toString(): String = value

    companion object {
        private val isoCodePattern = Regex("^[A-Z]{3}$")

        fun of(input: String): CurrencyCode {
            val normalized = input.trim().uppercase(Locale.ROOT)
            if (!isoCodePattern.matches(normalized)) {
                throw invalidCurrency(input)
            }

            val currency = try {
                Currency.getInstance(normalized)
            } catch (exception: IllegalArgumentException) {
                throw invalidCurrency(input, exception)
            }

            if (currency.defaultFractionDigits < 0) {
                throw invalidCurrency(input)
            }

            return CurrencyCode(currency.currencyCode)
        }

        private fun invalidCurrency(
            input: String,
            cause: Throwable? = null,
        ): DomainRuleViolation = DomainRuleViolation(
            error = ValidationError.InvalidCurrencyCode(input),
            cause = cause,
        )
    }
}

