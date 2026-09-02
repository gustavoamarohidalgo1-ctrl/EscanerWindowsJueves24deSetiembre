package com.facturastock.app.data.reporting

import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAgeBucket
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import java.util.Locale

/**
 * Frontera ya redactada hacia un adaptador de telemetría. Nunca recibe Throwable ni datos de
 * dominio; la implementación de flavor solo puede emitir nombre y atributos de la allowlist.
 */
interface ObservabilitySink {
    fun setCollectionEnabled(enabled: Boolean)

    fun emit(
        eventName: String,
        attributes: Map<String, String>,
        isFailure: Boolean,
    )
}

data class SanitizedObservabilityEvent(
    val eventName: String,
    val attributes: Map<String, String>,
)

/** Allowlist defensiva y testeable aplicada incluso a mapas construidos por código confiable. */
object ObservabilityAllowlist {
    const val OUTCOME: String = "outcome"
    const val ERROR_CODE: String = "error_code"
    const val AGE_BUCKET: String = "age_bucket"

    val allowedAttributeKeys: Set<String> = setOf(
        OUTCOME,
        ERROR_CODE,
        AGE_BUCKET,
    )
    private val allowedEventNames = OperationalAction.entries.mapTo(mutableSetOf()) { it.wireName() }
    private val allowedOutcomes = OperationalOutcome.entries.mapTo(mutableSetOf()) { it.name }
    private val allowedErrorCodes = OperationalErrorCode.entries.mapTo(mutableSetOf()) { it.name }
    private val allowedAgeBuckets = OperationalAgeBucket.entries.mapTo(mutableSetOf()) { it.name }

    /**
     * Claves desconocidas (incluidas `message`, `cause` y `stacktrace`) se descartan. Valores
     * fuera de su catálogo también desaparecen; sin nombre/outcome válidos no se emite nada.
     */
    fun sanitize(
        eventName: String,
        rawAttributes: Map<String, String>,
    ): SanitizedObservabilityEvent? {
        if (eventName !in allowedEventNames) return null
        val sanitized = rawAttributes.entries
            .asSequence()
            .filter { (key, _) -> key in allowedAttributeKeys }
            .filter { (key, value) -> value.isAllowedFor(key) }
            .associateTo(linkedMapOf()) { it.toPair() }
        if (sanitized[OUTCOME] == null) return null
        return SanitizedObservabilityEvent(eventName, sanitized)
    }

    fun eventName(action: OperationalAction): String = action.wireName()

    private fun String.isAllowedFor(key: String): Boolean = when (key) {
        OUTCOME -> this in allowedOutcomes
        ERROR_CODE -> this in allowedErrorCodes
        AGE_BUCKET -> this in allowedAgeBuckets
        else -> false
    }

    private fun OperationalAction.wireName(): String = name.lowercase(Locale.ROOT)
}
