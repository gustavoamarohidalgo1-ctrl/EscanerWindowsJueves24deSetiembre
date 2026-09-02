package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant

/** Proyección sanitaria de la outbox; no expone el payload del comprobante. */
data class PurchaseBackupSnapshot(
    val purchaseId: PurchaseId,
    val state: PurchaseSyncState,
    val attemptCount: Int,
    val updatedAt: Instant,
    val lastError: String?,
) {
    init {
        require(attemptCount >= 0)
        require(lastError == null || lastError.isNotBlank())
    }
}
