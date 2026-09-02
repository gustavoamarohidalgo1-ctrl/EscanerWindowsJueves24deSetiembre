package com.facturastock.app.domain.config

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.CurrencyCode
import java.util.Locale

data class RegionalSettings(
    val defaultCurrency: CurrencyCode,
    val locale: Locale,
) {
    init {
        if (locale.language.isBlank() || locale.country.isBlank()) {
            throw DomainRuleViolation(
                ValidationError.InvalidLocale(locale.toLanguageTag()),
            )
        }
    }

    val localeCode: String
        get() = locale.toString()

    companion object {
        const val INITIAL_CURRENCY_CODE = "PEN"
        const val INITIAL_LANGUAGE = "es"
        const val INITIAL_COUNTRY = "PE"

        fun peru(): RegionalSettings = RegionalSettings(
            defaultCurrency = CurrencyCode.of(INITIAL_CURRENCY_CODE),
            locale = Locale.forLanguageTag("$INITIAL_LANGUAGE-$INITIAL_COUNTRY"),
        )
    }
}
