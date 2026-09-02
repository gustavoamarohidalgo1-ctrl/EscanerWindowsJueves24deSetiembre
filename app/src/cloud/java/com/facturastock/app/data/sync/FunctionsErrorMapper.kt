package com.facturastock.app.data.sync

import com.facturastock.app.domain.repository.BackupTransportResult
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException

/**
 * Traduce fallos del callable a resultados cerrados del transporte. Las razones son siempre
 * códigos cerrados `[A-Z0-9_]`: el procesador los persiste tal cual en `lastError`, sin
 * mensajes del SDK ni cuerpos de respuesta.
 */
object FunctionsErrorMapper {

    fun fromException(error: Throwable): BackupTransportResult = when (error) {
        is FirebaseFunctionsException -> fromCode(error.code.name, error.message)
        is FirebaseNetworkException -> fromCode(NETWORK_UNAVAILABLE)
        is IOException -> fromCode(NETWORK_UNAVAILABLE)
        else -> fromCode(UNEXPECTED)
    }

    fun fromCode(code: String, backendMessage: String? = null): BackupTransportResult = when {
        code == "UNAVAILABLE" && backendMessage in CAUSAL_TRANSIENT_REASONS ->
            BackupTransportResult.TransientFailure(requireNotNull(backendMessage))
        // El destino puede recuperarse solo: reintento con backoff.
        code in TRANSIENT_CODES -> BackupTransportResult.TransientFailure(code)
        // El destino conoce un estado incompatible: conciliación explícita.
        code in CONFLICT_CODES -> BackupTransportResult.Conflict(code)
        // Excepción acotada sin clasificar (BACKUP_SYNC.md): reintenta; el tope de 5
        // intentos del procesador la convierte en acción manual si persiste.
        code == UNEXPECTED -> BackupTransportResult.TransientFailure(code)
        // El resto (INVALID_ARGUMENT, PERMISSION_DENIED, UNAUTHENTICATED, NOT_FOUND, …) no
        // cambia reintentando: acción manual.
        else -> BackupTransportResult.PermanentFailure(code)
    }

    private const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
    private const val UNEXPECTED = "UNEXPECTED_TRANSPORT_ERROR"
    private val CAUSAL_TRANSIENT_REASONS = setOf("DUPLICATE_TARGET_NOT_SYNCED")
    private val TRANSIENT_CODES = setOf(
        "UNAVAILABLE",
        "DEADLINE_EXCEEDED",
        "INTERNAL",
        "RESOURCE_EXHAUSTED",
        NETWORK_UNAVAILABLE,
    )
    private val CONFLICT_CODES = setOf("ALREADY_EXISTS", "ABORTED", "FAILED_PRECONDITION")
}
