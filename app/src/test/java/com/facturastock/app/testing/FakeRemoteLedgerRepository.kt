package com.facturastock.app.testing

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RemoteLedgerRepository

/**
 * Fake programable del libro remoto: las páginas de pull se encolan con [enqueuePage] y
 * [enqueueFailure] y se consumen en orden; agotado el guion responde [defaultPage]. Registra
 * cada llamada (seqs pedidos y descripciones solicitadas) para verificar el drenado. No abre
 * sockets ni archivos; [available] simula el flavor local o la falta de configuración.
 */
class FakeRemoteLedgerRepository(
    override var available: Boolean = true,
    private val connectivity: FakeFirebaseConnectivity = FakeFirebaseConnectivity(),
) : RemoteLedgerRepository {

    /** Llamada a [pullChanges], tal como llegó. */
    data class PullCall(
        val businessId: BusinessId,
        val sinceSeq: Long,
        val limit: Int,
    )

    private val scriptedPages = ArrayDeque<DomainResult<SyncPullPage>>()

    /** Página fija opcional al agotar el guion; null modela backend sin cambios en ese cursor. */
    var defaultPage: SyncPullPage? = null

    /** Resultado estable de las descripciones puntuales; ajústalo antes de la acción. */
    var describeResult: DomainResult<RemotePurchaseDescription?> = DomainResult.Success(null)

    /** Hook suspendible previo al resultado, útil para probar cancelación sin sockets. */
    var beforePull: suspend (PullCall) -> Unit = {}

    /** Pulls pedidos, en orden de llegada. */
    val pullCalls = mutableListOf<PullCall>()

    /** Descripciones pedidas (negocio, purchaseId remoto), en orden de llegada. */
    val describeCalls = mutableListOf<Pair<BusinessId, String>>()

    fun enqueuePage(page: SyncPullPage) {
        scriptedPages.addLast(DomainResult.Success(page))
    }

    fun enqueueFailure(error: FacturaStockError) {
        scriptedPages.addLast(DomainResult.Failure(error))
    }

    override suspend fun pullChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SyncPullPage> {
        val call = PullCall(businessId, sinceSeq, limit)
        pullCalls += call
        cloudFailure<SyncPullPage>()?.let { return it }
        beforePull(call)
        return scriptedPages.removeFirstOrNull()
            ?: DomainResult.Success(
                defaultPage
                    ?: SyncPullPage(
                        changes = emptyList(),
                        nextCursor = sinceSeq,
                        hasMore = false,
                    ),
            )
    }

    override suspend fun describeRemotePurchase(
        businessId: BusinessId,
        remotePurchaseId: String,
    ): DomainResult<RemotePurchaseDescription?> {
        describeCalls += businessId to remotePurchaseId
        cloudFailure<RemotePurchaseDescription?>()?.let { return it }
        return describeResult
    }

    /** Un corte de red no consume el siguiente resultado Firebase programado. */
    private fun <T> cloudFailure(): DomainResult<T>? = when {
        !available -> DomainResult.Failure(AccountError.Unavailable)
        !connectivity.isConnected -> DomainResult.Failure(AccountError.NetworkUnavailable)
        else -> null
    }
}
