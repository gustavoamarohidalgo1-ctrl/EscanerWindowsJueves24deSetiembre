package com.facturastock.app.data.sync

import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sin nube no hay transporte de respaldo: igual que el programador del flavor `local` en
 * Android (que nunca encolaba trabajo sin transporte configurado), la outbox queda en local.
 */
@Singleton
class NoOpPurchaseBackupScheduler @Inject constructor() : PurchaseBackupScheduler {
    override suspend fun enqueue() = Unit

    override suspend fun enqueueAt(attemptAt: Instant) = Unit

    override suspend fun cancelAll() = Unit
}
