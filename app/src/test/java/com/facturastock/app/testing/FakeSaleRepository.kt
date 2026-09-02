package com.facturastock.app.testing

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SaleCart
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.SaleSummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.OpenedSaleCart
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake mínimo y reactivo de ventas. Las proyecciones recientes están aisladas por negocio y
 * respetan el mismo orden y límite que Room. Las mutaciones se pueden programar con handlers sin
 * inventar datos de catálogo que el comando de venta no contiene.
 */
class FakeSaleRepository : SaleRepository {
    private val cartFlows = mutableMapOf<SaleId, MutableStateFlow<SaleCart?>>()
    private val recentPostedFlows =
        mutableMapOf<BusinessId, MutableStateFlow<List<SaleSummary>>>()
    private val postedProfitFlows =
        mutableMapOf<BusinessId, MutableStateFlow<List<RealizedSaleProfit>>>()
    private val mutableObservedSaleIds = mutableListOf<SaleId>()
    private val mutableObservedRecentRequests = mutableListOf<Pair<BusinessId, Int>>()
    private val mutableObservedProfitRequests =
        mutableListOf<Triple<BusinessId, Instant, Instant>>()

    val observedSaleIds: List<SaleId>
        get() = mutableObservedSaleIds.toList()

    val observedRecentRequests: List<Pair<BusinessId, Int>>
        get() = mutableObservedRecentRequests.toList()

    val observedProfitRequests: List<Triple<BusinessId, Instant, Instant>>
        get() = mutableObservedProfitRequests.toList()

    var createOrResumeHandler:
        (suspend (BusinessId, CurrencyCode) -> OpenedSaleCart)? = null
    var saveLineHandler:
        (suspend (BusinessId, SaveSaleCartLineCommand) -> SaleCartMutationResult)? = null
    var removeLineHandler:
        (suspend (BusinessId, SaleId, SaleLineId, Long) -> SaleCartMutationResult)? = null
    var checkoutHandler:
        (suspend (BusinessId, CheckoutSaleCommand) -> CheckoutSaleResult)? = null

    override fun observe(saleId: SaleId): Flow<SaleCart?> {
        mutableObservedSaleIds += saleId
        return cartFor(saleId)
    }

    override fun observeRecentPosted(
        businessId: BusinessId,
        limit: Int,
    ): Flow<List<SaleSummary>> {
        require(limit in 1..50) { "limit debe estar entre 1 y 50" }
        mutableObservedRecentRequests += businessId to limit
        return recentPostedFor(businessId).map { sales ->
            sales.sortedWith(RECENT_POSTED_ORDER).take(limit)
        }
    }

    override fun observePostedProfits(
        businessId: BusinessId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<RealizedSaleProfit>> {
        require(startInclusive < endExclusive)
        mutableObservedProfitRequests += Triple(businessId, startInclusive, endExclusive)
        return postedProfitsFor(businessId).map { sales ->
            sales.filter { it.postedAt >= startInclusive && it.postedAt < endExclusive }
                .sortedWith(POSTED_PROFIT_ORDER)
        }
    }

    override suspend fun createOrResume(
        businessId: BusinessId,
        currency: CurrencyCode,
    ): OpenedSaleCart {
        val matchingDrafts = cartFlows.values
            .mapNotNull { it.value }
            .filter { cart ->
                cart.businessId == businessId &&
                    cart.currency == currency &&
                    cart.status == SaleStatus.DRAFT
            }
        require(matchingDrafts.size <= 1) {
            "El fake no admite más de un carrito DRAFT por negocio y moneda"
        }
        val existing = matchingDrafts.singleOrNull()
        val handler = createOrResumeHandler
        val result = existing?.let { OpenedSaleCart(it, created = false) }
            ?: requireNotNull(handler) {
                "Configura createOrResumeHandler o publica un carrito DRAFT"
            }(businessId, currency)
        replaceCart(result.cart)
        return result
    }

    override suspend fun saveLine(
        businessId: BusinessId,
        command: SaveSaleCartLineCommand,
    ): SaleCartMutationResult {
        val handler = requireNotNull(saveLineHandler) {
            "Configura saveLineHandler para esta prueba"
        }
        return handler(businessId, command).also(::publishSavedCart)
    }

    override suspend fun removeLine(
        businessId: BusinessId,
        saleId: SaleId,
        saleLineId: SaleLineId,
        expectedVersion: Long,
    ): SaleCartMutationResult {
        val handler = requireNotNull(removeLineHandler) {
            "Configura removeLineHandler para esta prueba"
        }
        return handler(businessId, saleId, saleLineId, expectedVersion)
            .also(::publishSavedCart)
    }

    override suspend fun checkout(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
    ): CheckoutSaleResult {
        val handler = requireNotNull(checkoutHandler) {
            "Configura checkoutHandler para esta prueba"
        }
        return handler(businessId, command)
    }

    fun replaceCart(cart: SaleCart) {
        cartFor(cart.saleId).value = cart
    }

    fun removeCart(saleId: SaleId) {
        cartFor(saleId).value = null
    }

    /** Publica la proyección sin exigir construir las líneas completas del agregado. */
    fun replaceRecentPosted(
        businessId: BusinessId,
        sales: List<SaleSummary>,
    ) {
        require(sales.map(SaleSummary::saleId).distinct().size == sales.size) {
            "La proyección no puede repetir saleId"
        }
        recentPostedFor(businessId).value = sales.toList()
    }

    fun replacePostedProfits(
        businessId: BusinessId,
        sales: List<RealizedSaleProfit>,
    ) {
        require(sales.map(RealizedSaleProfit::saleId).distinct().size == sales.size) {
            "La proyección no puede repetir saleId"
        }
        postedProfitsFor(businessId).value = sales.toList()
    }

    private fun publishSavedCart(result: SaleCartMutationResult) {
        if (result is SaleCartMutationResult.Saved) replaceCart(result.cart)
    }

    private fun cartFor(saleId: SaleId): MutableStateFlow<SaleCart?> =
        cartFlows.getOrPut(saleId) { MutableStateFlow(null) }

    private fun recentPostedFor(
        businessId: BusinessId,
    ): MutableStateFlow<List<SaleSummary>> = recentPostedFlows.getOrPut(businessId) {
        MutableStateFlow(emptyList())
    }

    private fun postedProfitsFor(
        businessId: BusinessId,
    ): MutableStateFlow<List<RealizedSaleProfit>> = postedProfitFlows.getOrPut(businessId) {
        MutableStateFlow(emptyList())
    }

    private companion object {
        val RECENT_POSTED_ORDER = compareByDescending<SaleSummary> { it.postedAt }
            .thenByDescending { it.saleId.value }
        val POSTED_PROFIT_ORDER = compareByDescending<RealizedSaleProfit> { it.postedAt }
            .thenByDescending { it.saleId.value }
    }
}
