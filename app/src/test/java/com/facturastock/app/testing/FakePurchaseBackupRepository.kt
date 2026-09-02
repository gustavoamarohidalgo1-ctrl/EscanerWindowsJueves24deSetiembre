package com.facturastock.app.testing

import com.facturastock.app.domain.model.PurchaseBackupSnapshot
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PurchaseBackupRepository
import com.facturastock.app.domain.repository.RetryPurchaseBackupResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake local: no abre sockets y permite verificar que la UI solo reencola la outbox. */
class FakePurchaseBackupRepository : PurchaseBackupRepository {
    private val flows = mutableMapOf<Pair<BusinessId, PurchaseId>, MutableStateFlow<PurchaseBackupSnapshot?>>()

    var nextRetryResult: RetryPurchaseBackupResult = RetryPurchaseBackupResult.Requeued
    var recoveredInterrupted: Int = 0
    val retryRequests = mutableListOf<Pair<BusinessId, PurchaseId>>()

    override fun observe(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): Flow<PurchaseBackupSnapshot?> = flowFor(businessId, purchaseId)

    override suspend fun retry(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): RetryPurchaseBackupResult {
        retryRequests += businessId to purchaseId
        return nextRetryResult
    }

    override suspend fun recoverInterrupted(): Int = recoveredInterrupted

    fun emit(businessId: BusinessId, snapshot: PurchaseBackupSnapshot?) {
        val purchaseId = snapshot?.purchaseId ?: return
        flowFor(businessId, purchaseId).value = snapshot
    }

    private fun flowFor(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): MutableStateFlow<PurchaseBackupSnapshot?> = flows.getOrPut(businessId to purchaseId) {
        MutableStateFlow(null)
    }
}
