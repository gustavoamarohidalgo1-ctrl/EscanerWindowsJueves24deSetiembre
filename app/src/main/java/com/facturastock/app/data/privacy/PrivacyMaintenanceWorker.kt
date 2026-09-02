package com.facturastock.app.data.privacy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.facturastock.app.domain.usecase.RunPrivacyMaintenanceUseCase
import kotlinx.coroutines.CancellationException

/**
 * Worker del mantenimiento diario de privacidad. Toda la decisión vive en
 * [RunPrivacyMaintenanceUseCase]; aquí solo se traduce el resultado. Una etapa o archivo cuyo
 * objetivo no pudo confirmarse solicita retry con el backoff de WorkManager, hasta tres intentos
 * para mantenimiento ordinario. Una orden global durable nunca se terminaliza por conteo: sigue
 * reintentando hasta consumir su checkpoint. La cancelación se propaga.
 */
class PrivacyMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runPrivacyMaintenance: RunPrivacyMaintenanceUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val report = runPrivacyMaintenance()
        if (
            PrivacyMaintenanceRetryPolicy.shouldRetry(
                forceDeletionStillPending = report.forceDeletionStillPending,
                hasRetryableLocalWork = report.hasRetryableLocalWork,
                runAttemptCount = runAttemptCount,
            )
        ) {
            Result.retry()
        } else {
            Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Sin reporte no se puede demostrar que el checkpoint global esté ausente. WorkManager
        // aplica backoff; un fallo de DataStore/Room jamás se convierte en éxito por conteo.
        Result.retry()
    }
}

internal object PrivacyMaintenanceRetryPolicy {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(runAttemptCount: Int): Boolean =
        runAttemptCount.coerceAtLeast(0) + 1 < MAX_ATTEMPTS

    fun shouldRetry(
        forceDeletionStillPending: Boolean,
        hasRetryableLocalWork: Boolean,
        runAttemptCount: Int,
    ): Boolean = forceDeletionStillPending ||
        (hasRetryableLocalWork && shouldRetry(runAttemptCount))
}
