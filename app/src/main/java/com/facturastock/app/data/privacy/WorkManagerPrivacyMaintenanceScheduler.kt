package com.facturastock.app.data.privacy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Programador WorkManager del mantenimiento de privacidad: trabajo periódico único cada 24 h,
 * sin constraint de red (todo el trabajo es local), pero solo con batería no baja. No exige
 * almacenamiento libre: borrar imágenes vencidas puede precisamente recuperarlo. UPDATE
 * conserva una única cadena y aplica constraints nuevos también a una
 * instalación existente; la política de retención se lee fresca en cada ejecución.
 */
@Singleton
class WorkManagerPrivacyMaintenanceScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PrivacyMaintenanceScheduler {

    /** Crea o actualiza la única cadena periódica sin duplicarla. */
    override suspend fun enqueue() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PrivacyMaintenanceWorker>(
                PERIOD_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_HOURS,
                    TimeUnit.HOURS,
                )
                .build(),
        ).await()
    }

    /**
     * Encadena cada despertar detrás de una pasada ya activa. `KEEP` podía perder el evento si
     * el worker en ejecución ya había tomado sus snapshots; `APPEND_OR_REPLACE` garantiza una
     * observación posterior sin cancelar I/O en curso y recupera cadenas terminales.
     */
    override suspend fun enqueueImmediate() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PrivacyMaintenanceWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_HOURS,
                    TimeUnit.HOURS,
                )
                .build(),
        ).await()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "privacy-maintenance"
        const val IMMEDIATE_WORK_NAME = "privacy-maintenance-immediate"
        const val PERIOD_HOURS = 24L
        const val RETRY_BACKOFF_HOURS = 1L
    }
}
