package com.facturastock.app.testing

import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import java.time.Instant

/** Fake local: registra los encolados sin WorkManager, conectividad ni reloj de pared. */
class FakePurchaseBackupScheduler : PurchaseBackupScheduler {
    var enqueueFailure: Exception? = null
    var privacyEnqueueFailure: Exception? = null
    var beforeEnqueue: suspend () -> Unit = {}
    var beforePrivacyEnqueue: suspend () -> Unit = {}

    var enqueueCount: Int = 0
        private set

    var privacyEnqueueCount: Int = 0
        private set

    var cancelAllCount: Int = 0
        private set

    /** Instantes absolutos programados vía `enqueueAt`, en orden de llamada. */
    val followUps = mutableListOf<Instant>()

    override suspend fun enqueue() {
        beforeEnqueue()
        enqueueFailure?.let { throw it }
        enqueueCount++
    }

    override suspend fun enqueueAt(attemptAt: Instant) {
        followUps += attemptAt
    }

    override suspend fun enqueuePrivacyPurge() {
        beforePrivacyEnqueue()
        (privacyEnqueueFailure ?: enqueueFailure)?.let { throw it }
        privacyEnqueueCount++
    }

    override suspend fun cancelAll() {
        cancelAllCount++
    }
}
