package com.facturastock.app.data.privacy

import com.facturastock.app.core.coroutines.ApplicationScope
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.domain.usecase.RunPrivacyMaintenanceUseCase
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Equivalente de escritorio del programador WorkManager: una pasada periódica cada 24 h mientras
 * la aplicación está abierta y pasadas inmediatas encadenadas (nunca dos a la vez). Conserva la
 * misma política de reintentos que el worker Android.
 */
@Singleton
class DesktopPrivacyMaintenanceScheduler @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val runPrivacyMaintenance: Provider<RunPrivacyMaintenanceUseCase>,
) : PrivacyMaintenanceScheduler {
    private val passLock = Mutex()
    private var periodic: Job? = null

    override suspend fun enqueue() {
        synchronized(this) {
            if (periodic?.isActive == true) return
            periodic = scope.launch {
                while (true) {
                    delay(PERIOD)
                    runWithRetries()
                }
            }
        }
    }

    override suspend fun enqueueImmediate() {
        scope.launch { runWithRetries() }
    }

    private suspend fun runWithRetries() {
        var attempt = 0
        while (true) {
            val retry = passLock.withLock {
                try {
                    val report = runPrivacyMaintenance.get().invoke()
                    PrivacyMaintenanceRetryPolicy.shouldRetry(
                        forceDeletionStillPending = report.forceDeletionStillPending,
                        hasRetryableLocalWork = report.hasRetryableLocalWork,
                        runAttemptCount = attempt,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    true
                }
            }
            if (!retry) return
            attempt += 1
            delay(backoff(attempt))
        }
    }

    private fun backoff(attempt: Int): Duration =
        (RETRY_BACKOFF * (1 shl (attempt - 1).coerceIn(0, 5))).coerceAtMost(MAX_BACKOFF)

    private companion object {
        val PERIOD = 24.hours
        val RETRY_BACKOFF = 5.minutes
        val MAX_BACKOFF = 6.hours
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
