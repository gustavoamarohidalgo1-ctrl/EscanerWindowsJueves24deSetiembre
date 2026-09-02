package com.facturastock.app.domain.config

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.id.BusinessId
import java.math.BigDecimal
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigurationTest {
    @Test
    fun `TaxRate acepta el rango 0 a 100 con decimales exactos`() {
        assertEquals(BigDecimal("0"), TaxRate(BigDecimal("0")).percent)
        assertEquals(BigDecimal("18.5"), TaxRate(BigDecimal("18.5")).percent)
        assertEquals(BigDecimal("100"), TaxRate(BigDecimal("100")).percent)
    }

    @Test
    fun `TaxRate rechaza valores fuera de rango con error de dominio`() {
        val negative = assertThrows(DomainRuleViolation::class.java) {
            TaxRate(BigDecimal("-0.01"))
        }
        assertEquals(ValidationError.InvalidTaxRate("-0.01"), negative.error)

        val above = assertThrows(DomainRuleViolation::class.java) {
            TaxRate(BigDecimal("100.01"))
        }
        assertEquals(ValidationError.InvalidTaxRate("100.01"), above.error)
    }

    @Test
    fun `los defaults son primer uso peruano`() {
        val defaults = AppConfiguration.defaults()

        assertFalse(defaults.onboardingCompleted)
        assertNull(defaults.businessId)
        assertNull(defaults.demoBusinessId)
        assertFalse(defaults.isDemoMode)
        assertNull(defaults.activeBusinessId)
        assertEquals(TaxRate(BigDecimal("18")), defaults.taxRate)
        assertEquals(CostPolicy.NET, defaults.costPolicy)
        assertEquals(CurrencyCode.of("PEN"), defaults.currency)
        assertEquals(ZoneId.of("America/Lima"), defaults.zoneId)
        assertFalse(defaults.backupEnabled)
        assertFalse(defaults.documentBackupEnabled)
        assertFalse(defaults.diagnosticsEnabled)
        assertFalse(defaults.biometricLockEnabled)
    }

    @Test
    fun `activeBusinessId prioriza el negocio demo y isDemoMode lo refleja`() {
        val businessId = BusinessId.from(
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        )
        val demoId = BusinessId.from(
            UUID.fromString("223e4567-e89b-42d3-a456-426614174000"),
        )
        val base = AppConfiguration.defaults().copy(onboardingCompleted = true, businessId = businessId)

        assertEquals(businessId, base.activeBusinessId)
        assertFalse(base.isDemoMode)

        val demo = base.copy(demoBusinessId = demoId)
        assertTrue(demo.isDemoMode)
        assertEquals(demoId, demo.activeBusinessId)

        val restored = demo.copy(demoBusinessId = null)
        assertEquals(businessId, restored.activeBusinessId)
        assertFalse(restored.isDemoMode)
    }
}
