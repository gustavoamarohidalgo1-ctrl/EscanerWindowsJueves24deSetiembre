package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SaleCart
import com.facturastock.app.domain.model.SaleSummary
import com.facturastock.app.domain.model.normalizeDebtorName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class SaveSaleCartLineCommand(
    val saleId: SaleId,
    val expectedVersion: Long,
    /** Nulo crea; presente reemplaza exactamente esa línea del mismo borrador. */
    val saleLineId: SaleLineId? = null,
    val productId: ProductId,
    val locationId: LocationId,
    val quantity: Quantity,
    /** Nulo permite agregar por escáner y completar el precio después. */
    val unitPrice: Money? = null,
    val discount: Money? = null,
    val tax: Money? = null,
) {
    init {
        require(expectedVersion >= 0L) { "expectedVersion no puede ser negativa" }
        if (unitPrice == null) {
            require(discount == null && tax == null) {
                "Una línea sin precio no admite descuento ni impuesto"
            }
        } else {
            require(unitPrice.minorUnits > 0L) { "La venta gratuita no está soportada en v1" }
            require(discount == null || discount.minorUnits >= 0L)
            require(tax == null || tax.minorUnits >= 0L)
            require(listOfNotNull(unitPrice, discount, tax).map(Money::currency).distinct().size == 1) {
                "Todos los importes deben compartir moneda"
            }
        }
    }
}

data class CheckoutSaleCommand(
    val saleId: SaleId,
    val expectedVersion: Long,
    val expectedContentHash: String,
    /** Nulo mantiene la venta al contado; presente publica una deuda por el total completo. */
    val debtorName: String? = null,
    val debtDueAt: Instant? = null,
) {
    init {
        require(expectedVersion >= 0L) { "expectedVersion no puede ser negativa" }
        require(HASH.matches(expectedContentHash)) {
            "expectedContentHash debe ser SHA-256 canónico"
        }
        debtorName?.let(::normalizeDebtorName)
        require(debtorName != null || debtDueAt == null) {
            "debtDueAt solo corresponde a una venta a crédito"
        }
        debtDueAt?.let { require(it.toEpochMilli() >= 0L) { "debtDueAt no puede ser negativa" } }
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
    }
}

sealed interface CreateSaleCartResult {
    data class Created(val cart: SaleCart) : CreateSaleCartResult
    data class Resumed(val cart: SaleCart) : CreateSaleCartResult
    data object NoActiveBusiness : CreateSaleCartResult
}

sealed interface SaleCartMutationResult {
    /** El servidor puede haber confirmado: reintentar el checkout antes de editar el carrito. */
    data object CheckoutPending : SaleCartMutationResult
    data class Saved(val cart: SaleCart) : SaleCartMutationResult
    data object NoActiveBusiness : SaleCartMutationResult
    data object NotFound : SaleCartMutationResult
    data object NotDraft : SaleCartMutationResult
    data object Stale : SaleCartMutationResult
    data object LineNotFound : SaleCartMutationResult
    data object DuplicateProductLocation : SaleCartMutationResult
    data object ProductUnavailable : SaleCartMutationResult
    data object LocationUnavailable : SaleCartMutationResult
    data object CurrencyMismatch : SaleCartMutationResult
    /** El precio/importe individual era válido, pero sus cálculos exactos no caben o no cuadran. */
    data object InvalidTotals : SaleCartMutationResult
}

sealed interface CheckoutSaleResult {
    data class Posted(val saleId: SaleId) : CheckoutSaleResult
    data class AlreadyPosted(val saleId: SaleId) : CheckoutSaleResult
    data object NoActiveBusiness : CheckoutSaleResult
    data object NotFound : CheckoutSaleResult
    data object Stale : CheckoutSaleResult
    data object CartChanged : CheckoutSaleResult
    data object EmptyCart : CheckoutSaleResult
    data class IncompleteLine(val saleLineId: SaleLineId) : CheckoutSaleResult
    data object ProductUnavailable : CheckoutSaleResult
    data object LocationUnavailable : CheckoutSaleResult
    data class InsufficientStock(
        val productId: ProductId,
        val locationId: LocationId,
        val requested: Quantity,
        val available: String,
    ) : CheckoutSaleResult
    data object RetryableConflict : CheckoutSaleResult
    /** El negocio esta enlazado: confirmar sin servidor podria vender dos veces el mismo stock. */
    data object OnlineRequired : CheckoutSaleResult
    /** La historia cloud anterior necesita inicializar saldos autoritativos una sola vez. */
    data object InventoryMigrationRequired : CheckoutSaleResult
    /** El servidor rechazo rol, catalogo o forma del hecho; nunca se confirma solo en local. */
    data object RemoteRejected : CheckoutSaleResult
    /** El agregado persistido no puede recalcularse de forma exacta y segura. */
    data object InvalidTotals : CheckoutSaleResult
    /** Nombre, fecha o total no permiten crear una cuenta por cobrar segura. */
    data object InvalidCreditTerms : CheckoutSaleResult
}

interface SaleRepository {
    fun observe(saleId: SaleId): Flow<SaleCart?>

    fun observeRecentPosted(businessId: BusinessId, limit: Int = 50): Flow<List<SaleSummary>>

    /**
     * Observa ventas confirmadas dentro del intervalo semiabierto solicitado. El costo proviene
     * exclusivamente del movimiento de stock histórico asociado a cada línea.
     */
    fun observePostedProfits(
        businessId: BusinessId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<RealizedSaleProfit>>

    suspend fun createOrResume(businessId: BusinessId, currency: CurrencyCode): OpenedSaleCart

    suspend fun saveLine(
        businessId: BusinessId,
        command: SaveSaleCartLineCommand,
    ): SaleCartMutationResult

    suspend fun removeLine(
        businessId: BusinessId,
        saleId: SaleId,
        saleLineId: SaleLineId,
        expectedVersion: Long,
    ): SaleCartMutationResult

    suspend fun checkout(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
    ): CheckoutSaleResult
}

data class OpenedSaleCart(val cart: SaleCart, val created: Boolean)
