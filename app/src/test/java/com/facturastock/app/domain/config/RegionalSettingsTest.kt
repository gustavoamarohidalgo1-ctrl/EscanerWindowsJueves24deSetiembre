package com.facturastock.app.domain.config

import com.facturastock.app.domain.model.CurrencyCode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionalSettingsTest {
    @Test
    fun `los valores iniciales son PEN y es_PE`() {
        val settings = RegionalSettings.peru()

        assertEquals("PEN", settings.defaultCurrency.value)
        assertEquals("es", settings.locale.language)
        assertEquals("PE", settings.locale.country)
        assertEquals("es_PE", settings.localeCode)
    }

    @Test
    fun `admite otra moneda y locale sin alterar los valores iniciales`() {
        val custom = RegionalSettings(
            defaultCurrency = CurrencyCode.of("USD"),
            locale = Locale.forLanguageTag("en-US"),
        )

        assertEquals("USD", custom.defaultCurrency.value)
        assertEquals("en_US", custom.localeCode)
        val unchangedInitialSettings = RegionalSettings.peru()
        assertEquals("PEN", unchangedInitialSettings.defaultCurrency.value)
        assertEquals("es_PE", unchangedInitialSettings.localeCode)
    }
}
