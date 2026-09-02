package com.facturastock.app.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import java.math.BigDecimal
import java.time.ZoneId

/**
 * Claves del DataStore de preferencias no relacionales (`app_settings`) y mapeo tolerante a
 * [AppConfiguration]: una clave ausente toma el default de [AppConfiguration] y un valor
 * corrupto (ID no canónico, decimal ilegible, enum desconocido) vuelve al default de esa
 * clave en lugar de romper la lectura.
 */
internal object AppSettingsDataStore {
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val BUSINESS_ID = stringPreferencesKey("business_id")
    val PENDING_ONBOARDING_BUSINESS_ID = stringPreferencesKey("pending_onboarding_business_id")
    val PENDING_ONBOARDING_WAREHOUSE_ID = stringPreferencesKey("pending_onboarding_warehouse_id")
    val PENDING_ONBOARDING_UNIT_ID = stringPreferencesKey("pending_onboarding_unit_id")
    val COMPLETED_ONBOARDING_BUSINESS_ID = stringPreferencesKey("completed_onboarding_business_id")
    val COMPLETED_ONBOARDING_WAREHOUSE_ID = stringPreferencesKey("completed_onboarding_warehouse_id")
    val COMPLETED_ONBOARDING_UNIT_ID = stringPreferencesKey("completed_onboarding_unit_id")
    val DEMO_BUSINESS_ID = stringPreferencesKey("demo_business_id")
    val TAX_RATE_PERCENT = stringPreferencesKey("tax_rate_percent")
    val COST_POLICY = stringPreferencesKey("cost_policy")
    val CURRENCY = stringPreferencesKey("currency")
    val TIMEZONE = stringPreferencesKey("timezone")
    val IMAGE_RETENTION_POLICY = stringPreferencesKey("image_retention_policy")
    val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
    val DOCUMENT_BACKUP_ENABLED = booleanPreferencesKey("document_backup_enabled")
    val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
    val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
    val FORCE_IMAGE_DELETION_REQUESTED_AT = longPreferencesKey("force_image_deletion_requested_at")

    fun toAppConfiguration(preferences: Preferences): AppConfiguration {
        val defaults = AppConfiguration.defaults()
        return AppConfiguration(
            onboardingCompleted = preferences[ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
            businessId = preferences[BUSINESS_ID]?.let(BusinessId::parse),
            demoBusinessId = preferences[DEMO_BUSINESS_ID]?.let(BusinessId::parse),
            taxRate = preferences[TAX_RATE_PERCENT]
                ?.let { raw -> runCatching { TaxRate(BigDecimal(raw)) }.getOrNull() }
                ?: defaults.taxRate,
            costPolicy = preferences[COST_POLICY]
                ?.let { raw -> runCatching { CostPolicy.valueOf(raw) }.getOrNull() }
                ?: defaults.costPolicy,
            currency = preferences[CURRENCY]
                ?.let { raw -> runCatching { CurrencyCode.of(raw) }.getOrNull() }
                ?: defaults.currency,
            zoneId = preferences[TIMEZONE]
                ?.let { raw -> runCatching { ZoneId.of(raw) }.getOrNull() }
                ?: defaults.zoneId,
            imageRetentionPolicy = preferences[IMAGE_RETENTION_POLICY]
                ?.let { raw -> runCatching { ImageRetentionPolicy.valueOf(raw) }.getOrNull() }
                ?: defaults.imageRetentionPolicy,
            backupEnabled = preferences[BACKUP_ENABLED] ?: defaults.backupEnabled,
            documentBackupEnabled = preferences[DOCUMENT_BACKUP_ENABLED]
                ?: defaults.documentBackupEnabled,
            diagnosticsEnabled = preferences[DIAGNOSTICS_ENABLED] ?: defaults.diagnosticsEnabled,
            biometricLockEnabled = preferences[BIOMETRIC_LOCK_ENABLED]
                ?: defaults.biometricLockEnabled,
        )
    }
}
