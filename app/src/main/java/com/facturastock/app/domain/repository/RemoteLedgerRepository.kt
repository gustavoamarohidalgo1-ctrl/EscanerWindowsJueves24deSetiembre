package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId

/**
 * Puerto de lectura del libro remoto (pull). Solo lectura: el libro remoto lo escriben las
 * Cloud Functions; esta vista jamás modifica ni fusiona nada.
 */
interface RemoteLedgerRepository {
    /** `false` en el flavor local o sin sesión/configuración: no hay libro remoto. */
    val available: Boolean

    /** Página de cambios con `seq > sinceSeq`, ordenados por seq (tope [limit]). */
    suspend fun pullChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SyncPullPage>

    /** Descripción puntual del registro remoto en conflicto con una operación local. */
    suspend fun describeRemotePurchase(
        businessId: BusinessId,
        remotePurchaseId: String,
    ): DomainResult<RemotePurchaseDescription?>
}
