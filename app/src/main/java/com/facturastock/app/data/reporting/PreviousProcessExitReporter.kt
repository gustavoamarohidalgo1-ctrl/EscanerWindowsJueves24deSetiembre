package com.facturastock.app.data.reporting

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Informa en el siguiente arranque solo la categoria cerrada de una salida anormal anterior.
 *
 * Nunca abre `traceInputStream` ni lee `description`, y no publica instante, PID, proceso o status.
 * Esos campos se codifican únicamente en un SHA-256 local para distinguir dos salidas cercanas.
 * El checkpoint es un ring acotado de hashes, independiente del orden del reloj de pared.
 */
@Singleton
class PreviousProcessExitReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun report(observability: ProductionObservability) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val storedFingerprints = try {
            decodeProcessExitFingerprintRing(preferences.getString(FINGERPRINT_RING, null))
        } catch (_: ClassCastException) {
            emptyList()
        }
        val history = try {
            activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                ALL_AVAILABLE_HISTORY,
            )
        } catch (_: RuntimeException) {
            return
        }
        // Android entrega primero las salidas recientes. Procesarlas al revés conserva una
        // secuencia de observación estable sin inferir orden a partir del timestamp.
        val records = history.asReversed()
            .map { exit ->
                ProcessExitRecord(
                    timestamp = exit.timestamp.coerceAtLeast(0L),
                    pid = exit.pid,
                    reason = exit.reason,
                    status = exit.status,
                    processName = exit.processName.orEmpty(),
                )
            }
            .distinctBy(::fingerprintProcessExitRecord)
        val storedSet = storedFingerprints.toHashSet()
        val handled = mutableListOf<String>()
        var completedHistory = false
        try {
            records.forEach { record ->
                val fingerprint = fingerprintProcessExitRecord(record)
                if (fingerprint !in storedSet) {
                    processExitErrorCode(record.reason)?.let { code ->
                        observability.record(
                            OperationalAuditEvent(
                                action = OperationalAction.PROCESS_EXIT,
                                outcome = OperationalOutcome.FAILED,
                                errorCode = code,
                            ),
                        )
                    }
                }
                // Las salidas normales también se recuerdan para no releerlas indefinidamente.
                handled += fingerprint
            }
            completedHistory = true
        } finally {
            val ring = checkpointProcessExitFingerprints(
                stored = storedFingerprints,
                handled = handled,
                completedHistory = completedHistory,
            )
            // commit() reduce duplicados si el proceso muere inmediatamente después. Si falla,
            // el comportamiento seguro es at-least-once: la señal puede repetirse, no perderse.
            preferences.edit()
                .putString(FINGERPRINT_RING, ring.joinToString(FINGERPRINT_SEPARATOR))
                .remove(LAST_SEEN_TIMESTAMP)
                .remove(SEEN_EXIT_MARKERS_V2)
                .commit()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "private_process_exit_checkpoint"
        const val LAST_SEEN_TIMESTAMP = "last_seen_timestamp"
        const val SEEN_EXIT_MARKERS_V2 = "seen_exit_markers_v2"
        const val FINGERPRINT_RING = "seen_exit_fingerprints_v3"
        const val FINGERPRINT_SEPARATOR = ","
        const val ALL_AVAILABLE_HISTORY = 0
    }
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
