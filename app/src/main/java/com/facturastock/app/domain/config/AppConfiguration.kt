package com.facturastock.app.domain.config

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import java.math.BigDecimal
import java.time.ZoneId

/**
 * Política de costos del negocio: [NET] registra costos sin IGV; [GROSS] registra costos con
 * el IGV incluido.
 */
enum class CostPolicy {
    NET,
    GROSS,
}

/**
 * Tasa de impuesto en porcentaje (p. ej. 18 para el IGV peruano). Se conserva como decimal
 * exacto y debe estar entre 0 y 100.
 */
data class TaxRate(val percent: BigDecimal) {
    init {
        if (percent < BigDecimal.ZERO || percent > BigDecimal("100")) {
            throw DomainRuleViolation(ValidationError.InvalidTaxRate(percent.toPlainString()))
        }
    }
}

/**
 * Configuración global de la app. Vive fuera de Room (preferencias no relacionales) y se
 * aplica a cálculos futuros: las compras ya registradas nunca se recalculan.
 *
 * El negocio activo es [activeBusinessId]: el negocio demo cuando el modo demostración está
 * activo, si no el negocio real. El id real jamás se sobrescribe al entrar al modo demo.
 */
data class AppConfiguration(
    val onboardingCompleted: Boolean,
    val businessId: BusinessId?,
    val demoBusinessId: BusinessId?,
    val taxRate: TaxRate,
    val costPolicy: CostPolicy,
    val currency: CurrencyCode,
    val zoneId: ZoneId,
    /**
     * Política de retención de las fotos de comprobantes. Solo gobierna archivos de imagen;
     * los registros contables nunca se borran por retención.
     */
    val imageRetentionPolicy: ImageRetentionPolicy = DEFAULT_IMAGE_RETENTION_POLICY,
    /**
     * Interruptor del respaldo comercial: al desactivarse no se suben ni descargan registros,
     * pero las solicitudes explícitas de borrado remoto sí conservan un canal mínimo de salida.
     */
    val backupEnabled: Boolean = DEFAULT_BACKUP_ENABLED,
    /**
     * Opt-in separado para respaldar documentos/imágenes cifrados. El respaldo estructurado
     * puede seguir activo sin transferir la foto del comprobante.
     */
    val documentBackupEnabled: Boolean = DEFAULT_DOCUMENT_BACKUP_ENABLED,
    /** Opt-in explícito para analítica operacional y fallos redactados. */
    val diagnosticsEnabled: Boolean = DEFAULT_DIAGNOSTICS_ENABLED,
    /** Bloqueo local opcional mediante biometría fuerte o credencial del dispositivo. */
    val biometricLockEnabled: Boolean = DEFAULT_BIOMETRIC_LOCK_ENABLED,
) {
    val isDemoMode: Boolean
        get() = demoBusinessId != null

    val activeBusinessId: BusinessId?
        get() = demoBusinessId ?: businessId

    companion object {
        val DEFAULT_TAX_RATE: TaxRate = TaxRate(BigDecimal("18"))
        val DEFAULT_COST_POLICY: CostPolicy = CostPolicy.NET
        val DEFAULT_CURRENCY: CurrencyCode = CurrencyCode.of("PEN")
        val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("America/Lima")
        val DEFAULT_IMAGE_RETENTION_POLICY: ImageRetentionPolicy = ImageRetentionPolicy.KEEP
        const val DEFAULT_BACKUP_ENABLED: Boolean = false
        const val DEFAULT_DOCUMENT_BACKUP_ENABLED: Boolean = false
        const val DEFAULT_DIAGNOSTICS_ENABLED: Boolean = false
        const val DEFAULT_BIOMETRIC_LOCK_ENABLED: Boolean = false

        fun defaults(): AppConfiguration = AppConfiguration(
            onboardingCompleted = false,
            businessId = null,
            demoBusinessId = null,
            taxRate = DEFAULT_TAX_RATE,
            costPolicy = DEFAULT_COST_POLICY,
            currency = DEFAULT_CURRENCY,
            zoneId = DEFAULT_ZONE_ID,
        )
    }
}
