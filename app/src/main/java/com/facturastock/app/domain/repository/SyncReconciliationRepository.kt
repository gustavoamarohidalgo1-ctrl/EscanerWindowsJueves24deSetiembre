package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.LocalLedgerSnapshot
import com.facturastock.app.domain.model.id.BusinessId

/** Lectura local para la reconciliación diagnóstica; nunca escribe en el libro. */
interface SyncReconciliationRepository {
    /** Compras posteadas/anuladas (por identidad), saldos por producto e identidades de catálogo. */
    suspend fun loadLocalLedgerSnapshot(businessId: BusinessId): LocalLedgerSnapshot
}
