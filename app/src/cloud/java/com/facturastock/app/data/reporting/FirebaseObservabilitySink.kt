package com.facturastock.app.data.reporting

import android.content.Context
import android.os.Bundle
import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sink cloud detrás de [ObservabilitySink]. Analytics recibe únicamente la allowlist común y no
 * fija propiedades de usuario. Crashlytics está integrado para completar el artefacto Firebase,
 * pero su captura automática permanece siempre apagada: no existe un filtro fiable para sanear
 * crashes fatales/ANR antes de persistirlos. Tampoco se registra ninguna excepción manual.
 */
@Singleton
class FirebaseObservabilitySink @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runtime: FirebaseBackupRuntime,
) : ObservabilitySink {

    override fun setCollectionEnabled(enabled: Boolean) {
        // Habilitar necesita una instancia; deshabilitar solo actualiza el gate si todavía no
        // existe. Runtime serializa esta decisión con initializeApp y elimina la carrera cold-start.
        if (enabled && runtime.ready() == null) {
            error("Firebase observability could not be initialized safely")
        }
        runtime.updateObservabilityCollection(enabled)
    }

    override fun emit(
        eventName: String,
        attributes: Map<String, String>,
        isFailure: Boolean,
    ) {
        val sanitized = ObservabilityAllowlist.sanitize(eventName, attributes) ?: return
        if (runtime.ready() == null) return

        runtime.emitIfObservabilityEnabled {
            FirebaseAnalytics.getInstance(context).logEvent(
                sanitized.eventName,
                Bundle().apply {
                    sanitized.attributes.forEach { (key, value) -> putString(key, value) }
                },
            )
        }
    }
}
