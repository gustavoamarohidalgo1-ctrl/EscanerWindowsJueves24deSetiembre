package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.PurchaseBackupSnapshot
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import kotlinx.coroutines.flow.Flow

/** Resultado cerrado del comando local de reintento; nunca depende de conectividad. */
sealed interface RetryPurchaseBackupResult {
    data object Requeued : RetryPurchaseBackupResult
    data object AlreadyPending : RetryPurchaseBackupResult
    data object AlreadySynced : RetryPurchaseBackupResult
    data object NotFound : RetryPurchaseBackupResult
}

/**
 * Puerto de la outbox local. El respaldo remoto futuro solo podrá escribir esta cola; la UI
 * observa Room y nunca la respuesta de red directamente.
 */
interface PurchaseBackupRepository {
    fun observe(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): Flow<PurchaseBackupSnapshot?>

    suspend fun retry(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): RetryPurchaseBackupResult

    /** Reencola claims abandonados por un cierre del proceso. */
    suspend fun recoverInterrupted(): Int
}
