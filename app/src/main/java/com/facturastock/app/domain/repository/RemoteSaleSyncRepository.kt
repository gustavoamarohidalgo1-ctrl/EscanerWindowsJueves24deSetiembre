package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SharedInventoryBalance
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import java.time.Instant

/** Resultado cerrado de la autorizacion remota previa al commit local de una venta compartida. */
sealed interface RemoteSalePostResult {
    /** No hay enlace cloud activo; el flavor conserva el checkout local existente. */
    data object NotRequired : RemoteSalePostResult

    data class Authorized(
        val saleId: SaleId,
        val receiptId: String,
        val seq: Long,
        val postedAt: Instant,
        /** IDs ya traducidos de vuelta al catalogo local que originó la solicitud. */
        val balances: List<AuthorizedLocalBalance>,
    ) : RemoteSalePostResult

    data class InsufficientStock(
        val productId: ProductId,
        val locationId: LocationId,
        val requested: Quantity,
        val available: String,
    ) : RemoteSalePostResult

    /** El negocio tiene historia cloud anterior a los saldos compartidos y necesita migracion. */
    data object InventoryMigrationRequired : RemoteSalePostResult

    /** El negocio esta enlazado: para evitar sobreventa no se permite confirmar sin servidor. */
    data object OnlineRequired : RemoteSalePostResult

    /** Auth, rol, catalogo o contrato remoto no permiten confirmar el hecho. */
    data object Rejected : RemoteSalePostResult
}

data class AuthorizedLocalBalance(
    val productId: ProductId,
    val locationId: LocationId,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currencyCode: String,
    val remoteVersion: Long,
    val updatedAt: Instant,
)

interface RemoteSaleSyncRepository {
    val available: Boolean

    suspend fun postSale(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): RemoteSalePostResult

    suspend fun pullInventoryChanges(
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SharedInventoryPullPage>
}

/** Default seguro para construcciones JVM/directas del repositorio local. */
object DisabledRemoteSaleSyncRepository : RemoteSaleSyncRepository {
    override val available: Boolean = false

    override suspend fun postSale(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): RemoteSalePostResult = RemoteSalePostResult.NotRequired

    override suspend fun pullInventoryChanges(
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SharedInventoryPullPage> = DomainResult.Failure(AccountError.Unavailable)
}

interface SharedInventoryApplicationRepository {
    suspend fun lastAppliedSeq(cloudBusinessId: BusinessId): Long

    /** Aplica hechos y cursor en una unica transaccion Room. */
    suspend fun applyPage(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        expectedPreviousSeq: Long,
        page: SharedInventoryPullPage,
        appliedAt: Instant,
    ): DomainResult<Int>
}
