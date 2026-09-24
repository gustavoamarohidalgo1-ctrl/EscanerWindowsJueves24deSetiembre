package com.facturastock.app.data.reporting

import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Windows no conserva un historial de salidas anormales equivalente a `ApplicationExitInfo`.
 * El reporter queda como no-op y los helpers de huella se conservan para el formato compartido.
 */
@Singleton
class PreviousProcessExitReporter @Inject constructor() {
    @Suppress("UNUSED_PARAMETER")
    suspend fun report(observability: ProductionObservability) = Unit
}

internal data class ProcessExitRecord(
    val timestamp: Long,
    val pid: Int,
    val reason: Int,
    val status: Int,
    val processName: String,
) {
    init {
        require(timestamp >= 0L)
    }
}

/** Hash local con longitudes prefijadas; ningún campo de [ProcessExitRecord] sale del equipo. */
internal fun fingerprintProcessExitRecord(record: ProcessExitRecord): String {
    val processName = record.processName.toByteArray(StandardCharsets.UTF_8)
    val encoded = ByteBuffer.allocate(Long.SIZE_BYTES + 4 * Int.SIZE_BYTES + processName.size)
        .putLong(record.timestamp)
        .putInt(record.pid)
        .putInt(record.reason)
        .putInt(record.status)
        .putInt(processName.size)
        .put(processName)
        .array()
    return MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun decodeProcessExitFingerprintRing(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    if (raw.length > MAX_LOCAL_CHECKPOINT_CHARS) return emptyList()
    val fingerprints = raw.split(',')
    if (fingerprints.any { !it.matches(Regex("[0-9a-f]{64}")) }) {
        return emptyList()
    }
    return fingerprints.distinct()
}

internal fun mergeProcessExitFingerprintRing(
    stored: List<String>,
    handled: List<String>,
    limit: Int,
): List<String> {
    require(limit >= 1)
    val refreshed = handled.toHashSet()
    return (stored.filterNot { it in refreshed } + handled)
        .distinct()
        .takeLast(limit)
}

/**
 * Tras recorrer toda la ventana, Android ya proporciona el límite: conservar exactamente esa
 * ventana evita reemitir entradas antiguas si contiene más de un umbral local arbitrario.
 */
internal fun checkpointProcessExitFingerprints(
    stored: List<String>,
    handled: List<String>,
    completedHistory: Boolean,
): List<String> = if (completedHistory) {
    handled.distinct()
} else {
    (stored + handled).distinct()
}

private const val MAX_LOCAL_CHECKPOINT_CHARS = 1_000_000

/** Salidas normales o solicitadas se ignoran; solo se observan fallos accionables. */
internal fun processExitErrorCode(reason: Int): OperationalErrorCode? = when (reason) {
    ApplicationExitInfo.REASON_ANR -> OperationalErrorCode.PROCESS_EXIT_ANR
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    -> OperationalErrorCode.PROCESS_EXIT_CRASH
    ApplicationExitInfo.REASON_LOW_MEMORY -> OperationalErrorCode.PROCESS_EXIT_LOW_MEMORY
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
        OperationalErrorCode.PROCESS_EXIT_RESOURCE_LIMIT
    ApplicationExitInfo.REASON_SIGNALED,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    ApplicationExitInfo.REASON_OTHER,
    -> OperationalErrorCode.PROCESS_EXIT_SYSTEM_FAILURE
    else -> null
}

/** Códigos de `android.app.ApplicationExitInfo`, conservados para el formato de huellas. */
internal object ApplicationExitInfo {
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_DEPENDENCY_DIED = 12
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_OTHER = 13
    const val REASON_PACKAGE_UPDATED = 16
}
