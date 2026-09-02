package com.facturastock.app.domain.repository

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Puerto de la configuración global de la app (preferencias no relacionales). Las
 * implementaciones emiten los defaults de [AppConfiguration] cuando aún no se ha escrito
 * nada, toleran valores corruptos devolviendo el default de la clave afectada y traducen los
 * fallos de E/S a `StorageException`.
 */
interface AppConfigurationRepository {
    /** Emite la configuración ante cada cambio; la primera emisión refleja lo persistido. */
    fun observe(): Flow<AppConfiguration>

    /**
     * Invalida un fallo de observación ya publicado y solicita una lectura nueva. Las
     * implementaciones sin una fuente compartida no necesitan hacer nada.
     */
    fun retryObservation() = Unit

    /** Lee la configuración actual una sola vez. */
    suspend fun current(): AppConfiguration

    /**
     * Reserva identidades estables antes de escribir Room. La implementación DataStore serializa
     * invocaciones concurrentes y devuelve la reserva anterior tras muerte de proceso o reintento.
     * El default conserva compatibilidad para adaptadores de prueba que no persisten checkpoints.
     */
    suspend fun reserveOnboardingProvision(
        proposed: OnboardingProvisionIds,
    ): OnboardingReservation {
        val configuration = current()
        return if (configuration.onboardingCompleted && configuration.businessId != null) {
            OnboardingReservation.AlreadyCompleted(configuration.businessId)
        } else {
            OnboardingReservation.Pending(proposed)
        }
    }

    /**
     * Finaliza solo la reserva que autorizó el grafo Room. Repetir la misma finalización es éxito;
     * una reserva distinta debe fallar cerrado para no enlazar configuración y negocio ajenos.
     */
    suspend fun finalizeOnboardingProvision(
        reservation: OnboardingProvisionIds,
        provisionedBusinessId: BusinessId,
        taxRate: TaxRate,
        costPolicy: CostPolicy,
    ) {
        completeOnboarding(provisionedBusinessId, taxRate, costPolicy)
    }

    /**
     * Marca el onboarding como completado y fija el negocio real junto a la tasa de impuesto
     * y la política de costos elegidas.
     */
    suspend fun completeOnboarding(businessId: BusinessId, taxRate: TaxRate, costPolicy: CostPolicy)

    /** Actualiza solo la tasa de impuesto; aplica a cálculos futuros. */
    suspend fun updateTaxRate(rate: TaxRate)

    /** Actualiza solo la política de costos; aplica a cálculos futuros. */
    suspend fun updateCostPolicy(policy: CostPolicy)

    /**
     * Actualiza solo la política de retención de imágenes. Gobierna archivos de imagen desde
     * el momento del cambio: las pasadas de mantenimiento y los hooks de OCR/confirmación la
     * leen fresca; jamás toca registros contables.
     */
    suspend fun updateImageRetentionPolicy(policy: ImageRetentionPolicy)

    /**
     * Persiste la intención explícita de retirar todas las imágenes locales. El checkpoint se
     * escribe antes de tocar archivos para que muerte de proceso, OCR activo o ENOSPC no
     * conviertan una solicitud global en una pasada única olvidable.
     */
    suspend fun requestForceImageDeletion(requestedAt: Instant) = Unit

    /** Cutoff durable; capturas creadas después jamás pertenecen a una solicitud anterior. */
    suspend fun forceImageDeletionRequestedAt(): Instant? = null

    /** Confirma el checkpoint solo cuando ya no queda trabajo transitorio de esa intención. */
    suspend fun clearForceImageDeletionRequest() = Unit

    /**
     * Activa o desactiva el respaldo comercial. Al desactivarse se detiene el envío/descarga de
     * datos comerciales, pero los tombstones explícitos de privacidad conservan un canal mínimo
     * de salida para que una solicitud de borrado remoto no dependa de reactivar el respaldo.
     */
    suspend fun updateBackupEnabled(enabled: Boolean)

    /** Opt-in para transferir documentos cifrados; independiente del respaldo estructurado. */
    suspend fun updateDocumentBackupEnabled(enabled: Boolean)

    /** Opt-in independiente del respaldo; por defecto ningún diagnóstico sale del equipo. */
    suspend fun updateDiagnosticsEnabled(enabled: Boolean)

    /** Activa el bloqueo local con biometría fuerte o credencial del dispositivo. */
    suspend fun updateBiometricLockEnabled(enabled: Boolean)

    /** Activa el modo demostración fijando el negocio demo, sin tocar el negocio real. */
    suspend fun enterDemoMode(demoBusinessId: BusinessId)

    /** Sale del modo demostración eliminando la referencia al negocio demo. */
    suspend fun exitDemoMode()
}
