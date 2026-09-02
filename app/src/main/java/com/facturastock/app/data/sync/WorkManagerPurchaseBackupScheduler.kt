package com.facturastock.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Programador WorkManager del drenado de la outbox.
 *
 * - Dos canales inmediatos únicos: cada wake ordinario o de privacidad se encadena con
 *   APPEND_OR_REPLACE para no perder una fila creada al final de una pasada RUNNING.
 * - Los follow-ups externos deduplican por deadline. Solo un request que despertó antes de su
 *   propio T crea un sucesor con el ID del work RUNNING; evita tanto el lost-wake de KEEP como
 *   la amplificación de requests repetidos. Las cancelaciones barren cada canal por tag.
 * - Ambos requieren red. Solo el respaldo ordinario espera batería/almacenamiento no bajos; una
 *   purga debe poder liberar datos remotos incluso cuando Android reporta poco espacio.
 * - Los work requests persisten en la base de datos de WorkManager: un reinicio del teléfono
 *   no elimina la cola.
 * - Sin transporte configurado todo es un no-op: la cola Room queda en PENDING_SYNC y
 *   WorkManager no acumula trabajos que solo podrían fracasar.
 * - Con el respaldo desactivado (`backupEnabled`) no se encola el canal ordinario; la outbox
 *   comercial queda PENDING_SYNC, mientras los tombstones de privacidad conservan su canal.
 */
@Singleton
class WorkManagerPurchaseBackupScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val transport: PurchaseBackupTransport,
    private val clock: AppClock,
    private val appConfigurationRepository: AppConfigurationRepository,
) : PurchaseBackupScheduler {

    override suspend fun enqueue() {
        if (!transport.configured || !regularWakeIsEnabled()) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            regularRequest(initialDelayMillis = 0L),
        ).await()
    }

    override suspend fun enqueueAt(attemptAt: Instant) {
        if (!transport.configured || !regularWakeIsEnabled()) return
        val delayMillis = (attemptAt.toEpochMilli() - clock.now().toEpochMilli())
            .coerceAtLeast(0L)
        val request = regularRequest(
            initialDelayMillis = delayMillis,
            tag = FOLLOW_UP_WORK_TAG,
            scheduledWakeAt = attemptAt,
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            deadlineWorkName(FOLLOW_UP_WORK_NAME, attemptAt),
            ExistingWorkPolicy.KEEP,
            request,
        ).await()
    }

    override suspend fun enqueueSuccessorAt(attemptAt: Instant, runningWorkId: String) {
        if (!transport.configured || !regularWakeIsEnabled()) return
        val delayMillis = (attemptAt.toEpochMilli() - clock.now().toEpochMilli())
            .coerceAtLeast(0L)
        WorkManager.getInstance(context).enqueueUniqueWork(
            successorWorkName(FOLLOW_UP_WORK_NAME, attemptAt, runningWorkId),
            ExistingWorkPolicy.KEEP,
            regularRequest(
                initialDelayMillis = delayMillis,
                tag = FOLLOW_UP_WORK_TAG,
                scheduledWakeAt = attemptAt,
            ),
        ).await()
    }

    override suspend fun enqueuePrivacyPurge() {
        if (!transport.configured) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRIVACY_PURGE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            privacyPurgeRequest(initialDelayMillis = 0L),
        ).await()
    }

    override suspend fun enqueuePrivacyPurgeAt(attemptAt: Instant) {
        if (!transport.configured) return
        val delayMillis = (attemptAt.toEpochMilli() - clock.now().toEpochMilli())
            .coerceAtLeast(0L)
        val request = privacyPurgeRequest(
            initialDelayMillis = delayMillis,
            tag = PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
            scheduledWakeAt = attemptAt,
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            deadlineWorkName(PRIVACY_PURGE_FOLLOW_UP_WORK_NAME, attemptAt),
            ExistingWorkPolicy.KEEP,
            request,
        ).await()
    }

    override suspend fun enqueuePrivacyPurgeSuccessorAt(
        attemptAt: Instant,
        runningWorkId: String,
    ) {
        if (!transport.configured) return
        val delayMillis = (attemptAt.toEpochMilli() - clock.now().toEpochMilli())
            .coerceAtLeast(0L)
        WorkManager.getInstance(context).enqueueUniqueWork(
            successorWorkName(PRIVACY_PURGE_FOLLOW_UP_WORK_NAME, attemptAt, runningWorkId),
            ExistingWorkPolicy.KEEP,
            privacyPurgeRequest(
                initialDelayMillis = delayMillis,
                tag = PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
                scheduledWakeAt = attemptAt,
            ),
        ).await()
    }

    override suspend fun cancelRegular() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME).await()
        WorkManager.getInstance(context).cancelAllWorkByTag(FOLLOW_UP_WORK_TAG).await()
    }

    override suspend fun cancelAll() {
        cancelRegular()
        WorkManager.getInstance(context).cancelUniqueWork(PRIVACY_PURGE_WORK_NAME).await()
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(PRIVACY_PURGE_FOLLOW_UP_WORK_TAG)
            .await()
    }

    /**
     * Lee el gate después de cualquier persistencia de preferencia que originó este wake. Ante
     * un fallo de lectura se encola (fail-open): el worker vuelve a leer y la outbox no se pierde.
     */
    private suspend fun regularWakeIsEnabled(): Boolean = try {
        appConfigurationRepository.current().backupEnabled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    private fun regularRequest(
        initialDelayMillis: Long,
        tag: String? = null,
        scheduledWakeAt: Instant? = null,
    ): OneTimeWorkRequest = request(
        initialDelayMillis = initialDelayMillis,
        purgeOnly = false,
        tag = tag,
        scheduledWakeAt = scheduledWakeAt,
        constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            // El respaldo ya es durable: puede esperar sin competir con el usuario cuando la
            // batería está baja ni escribir cuando Android reporta poco espacio.
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build(),
    )

    /**
     * Una purga libera datos remotos y debe poder avanzar precisamente cuando el dispositivo
     * tiene poco almacenamiento. Solo requiere red; no hereda las restricciones del respaldo.
     */
    private fun privacyPurgeRequest(
        initialDelayMillis: Long,
        tag: String? = null,
        scheduledWakeAt: Instant? = null,
    ): OneTimeWorkRequest = request(
        initialDelayMillis = initialDelayMillis,
        purgeOnly = true,
        tag = tag,
        scheduledWakeAt = scheduledWakeAt,
        constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build(),
    )

    private fun request(
        initialDelayMillis: Long,
        purgeOnly: Boolean,
        tag: String?,
        scheduledWakeAt: Instant?,
        constraints: Constraints,
    ): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<PurchaseBackupSyncWorker>()
            .setInputData(
                if (scheduledWakeAt == null) {
                    workDataOf(PurchaseBackupSyncWorker.INPUT_PURGE_ONLY to purgeOnly)
                } else {
                    workDataOf(
                        PurchaseBackupSyncWorker.INPUT_PURGE_ONLY to purgeOnly,
                        PurchaseBackupSyncWorker.INPUT_SCHEDULED_WAKE_AT_EPOCH_MILLIS to
                            scheduledWakeAt.toEpochMilli(),
                    )
                },
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WORKER_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
        if (initialDelayMillis > 0L) {
            builder.setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
        }
        if (tag != null) builder.addTag(tag)
        return builder.build()
    }

    private fun deadlineWorkName(prefix: String, attemptAt: Instant): String =
        "$prefix-${attemptAt.toEpochMilli()}"

    private fun successorWorkName(
        prefix: String,
        attemptAt: Instant,
        runningWorkId: String,
    ): String = "$prefix-${attemptAt.toEpochMilli()}-after-$runningWorkId"

    companion object {
        const val UNIQUE_WORK_NAME = "purchase-backup-sync"
        const val FOLLOW_UP_WORK_NAME = "purchase-backup-sync-followup"
        const val PRIVACY_PURGE_WORK_NAME = "purchase-backup-privacy-purge"
        const val PRIVACY_PURGE_FOLLOW_UP_WORK_NAME =
            "purchase-backup-privacy-purge-followup"
        const val FOLLOW_UP_WORK_TAG = "purchase-backup-sync-followups"
        const val PRIVACY_PURGE_FOLLOW_UP_WORK_TAG =
            "purchase-backup-privacy-purge-followups"
        const val WORKER_BACKOFF_SECONDS = 30L
    }
}
