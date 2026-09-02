package com.facturastock.app.data.reporting

import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.observability.toOperationalErrorCode
import com.facturastock.app.domain.repository.AppConfigurationRepository
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Única capa que decide si una señal puede salir del dispositivo. Lee consentimiento fresco,
 * construye atributos cerrados, vuelve a aplicar la allowlist y aísla por completo fallos del
 * sink. Si DataStore no puede confirmar el opt-in, la decisión segura es no emitir.
 */
@Singleton
class ConsentAwareProductionObservability @Inject constructor(
    private val configuration: AppConfigurationRepository,
    private val sink: ObservabilitySink,
) : ProductionObservability {

    private val consentGate = Mutex()

    @Volatile
    private var runtimeConsentOverride: Boolean? = null

    /** Solo cambia a true después de que el sink confirmó el opt-in en este proceso. */
    private var sinkCollectionEnabled = false

    override suspend fun record(event: OperationalAuditEvent, failure: Throwable?) {
        if (runtimeConsentOverride == false) return
        val consented = try {
            configuration.current().diagnosticsEnabled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        if (!consented) return

        val attributes = buildMap {
            put(ObservabilityAllowlist.OUTCOME, event.outcome.name)
            (event.errorCode ?: failure?.toOperationalErrorCode())?.let { code ->
                put(ObservabilityAllowlist.ERROR_CODE, code.name)
            }
            event.ageBucket?.let { bucket ->
                put(ObservabilityAllowlist.AGE_BUCKET, bucket.name)
            }
        }
        val sanitized = ObservabilityAllowlist.sanitize(
            eventName = ObservabilityAllowlist.eventName(event.action),
            rawAttributes = attributes,
        ) ?: return

        // Emisión y cambios de consentimiento se linealizan. Si el opt-out gana el lock,
        // ningún record que ya hubiera leído la preferencia puede emitir ni reactivar el SDK.
        consentGate.withLock {
            if (runtimeConsentOverride == false) return@withLock
            // WorkManager puede iniciar un proceso sin Activity ni primer frame. En ese caso no
            // existe syncInitialConsent(), así que el consentimiento fresco de DataStore debe
            // aplicarse aquí antes del primer evento. Un fallo conserva el estado fail-closed y
            // el evento siguiente vuelve a intentarlo.
            if (!sinkCollectionEnabled && !applyConsentToSink(true)) return@withLock
            // El sink es estrictamente best-effort. Incluso un CancellationException lanzado
            // por un SDK defectuoso no cambia el resultado funcional ya calculado.
            try {
                sink.emit(
                    eventName = sanitized.eventName,
                    attributes = sanitized.attributes,
                    isFailure = event.outcome in setOf(
                        OperationalOutcome.FAILED,
                        OperationalOutcome.CONFLICT,
                        OperationalOutcome.RETRY_SCHEDULED,
                    ),
                )
            } catch (_: Throwable) {
                // Mejor esfuerzo: observabilidad jamás bloquea captura, edición ni contabilidad.
            }
        }
    }

    override suspend fun updateConsent(enabled: Boolean) {
        consentGate.withLock {
            runtimeConsentOverride = enabled
            applyConsentToSink(enabled)
        }
    }

    override suspend fun syncInitialConsent(enabled: Boolean) {
        consentGate.withLock {
            // La lectura de arranque puede haber empezado antes que un opt-out en Ajustes. Una
            // decisión explícita dentro de esta sesión siempre gana sobre ese snapshot antiguo.
            if (runtimeConsentOverride != null) return@withLock
            runtimeConsentOverride = enabled
            applyConsentToSink(enabled)
        }
    }

    private fun applyConsentToSink(enabled: Boolean): Boolean {
        // El opt-out cierra primero la autorización local para que un fallo del SDK nunca deje
        // pasar otra emisión dentro de este proceso.
        if (!enabled) sinkCollectionEnabled = false
        return try {
            sink.setCollectionEnabled(enabled)
            sinkCollectionEnabled = enabled
            true
        } catch (_: Throwable) {
            sinkCollectionEnabled = false
            // La preferencia local sigue siendo autoritativa; record() la comprueba de nuevo y
            // nunca emite si el sink no pudo confirmar el opt-in.
            false
        }
    }
}
