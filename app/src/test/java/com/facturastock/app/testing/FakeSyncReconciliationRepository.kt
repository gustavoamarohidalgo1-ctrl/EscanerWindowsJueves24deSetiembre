package com.facturastock.app.testing

import com.facturastock.app.domain.model.LocalLedgerSnapshot
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.SyncReconciliationRepository

/**
 * Fake local de la instantánea para reconciliación: sirve [snapshot] a cualquier negocio y
 * registra cada carga. Es solo lectura, igual que el puerto que sustituye.
 */
class FakeSyncReconciliationRepository : SyncReconciliationRepository {

    /** Instantánea servida en cada carga; ajústala antes de la acción. */
    var snapshot: LocalLedgerSnapshot = LocalLedgerSnapshot(
        localDocuments = emptyMap(),
        balances = emptyList(),
        products = emptyList(),
    )

    /** Negocios para los que se cargó la instantánea, en orden de llegada. */
    val loadCalls = mutableListOf<BusinessId>()

    override suspend fun loadLocalLedgerSnapshot(businessId: BusinessId): LocalLedgerSnapshot {
        loadCalls += businessId
        return snapshot
    }
}
