package com.facturastock.app.testing

import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.InventoryReadRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake reactivo de las proyecciones de inventario. Cada negocio y cada producto tienen su propio
 * StateFlow, igual que las consultas Room que sustituye. El diagnóstico se programa con
 * [diagnosticReport] o, cuando la prueba necesita observar la ventana en curso, con
 * [diagnoseHandler]; nunca muta los saldos observados, tal como exige la lectura de solo lectura.
 */
class FakeInventoryReadRepository : InventoryReadRepository {
    private val inventoryFlows = mutableMapOf<BusinessId, MutableStateFlow<List<InventoryReadItem>>>()
    private val detailFlows =
        mutableMapOf<Pair<BusinessId, ProductId>, MutableStateFlow<InventoryProductDetail?>>()

    private val mutableObservedInventoryBusinesses = mutableListOf<BusinessId>()
    private val mutableObservedProducts = mutableListOf<Pair<BusinessId, ProductId>>()
    private val mutableDiagnoseCalls = mutableListOf<BusinessId>()

    val observedInventoryBusinesses: List<BusinessId>
        get() = mutableObservedInventoryBusinesses.toList()

    val observedProducts: List<Pair<BusinessId, ProductId>>
        get() = mutableObservedProducts.toList()

    val diagnoseCalls: List<BusinessId>
        get() = mutableDiagnoseCalls.toList()

    /** Informe que devuelve [diagnose] cuando no hay [diagnoseHandler]. */
    var diagnosticReport: InventoryDiagnosticReport =
        InventoryDiagnosticReport(Instant.parse("2026-08-20T10:00:00Z"), emptyList())

    /** Reemplaza la respuesta del diagnóstico; puede suspender para probar el reintento doble. */
    var diagnoseHandler: (suspend (BusinessId) -> InventoryDiagnosticReport)? = null

    override fun observeInventory(businessId: BusinessId): Flow<List<InventoryReadItem>> {
        mutableObservedInventoryBusinesses += businessId
        return inventoryFor(businessId)
    }

    override fun observeProduct(
        businessId: BusinessId,
        productId: ProductId,
    ): Flow<InventoryProductDetail?> {
        mutableObservedProducts += businessId to productId
        return detailFor(businessId, productId)
    }

    override suspend fun diagnose(businessId: BusinessId): InventoryDiagnosticReport {
        mutableDiagnoseCalls += businessId
        return diagnoseHandler?.invoke(businessId) ?: diagnosticReport
    }

    /** Publica la proyección completa del negocio, como una reemisión de la consulta Room. */
    fun replaceInventory(
        businessId: BusinessId,
        items: List<InventoryReadItem>,
    ) {
        require(items.all { it.businessId == businessId })
        require(items.map(InventoryReadItem::productId).distinct().size == items.size)
        inventoryFor(businessId).value = items.toList()
    }

    fun setProductDetail(
        businessId: BusinessId,
        productId: ProductId,
        detail: InventoryProductDetail?,
    ) {
        require(detail == null || detail.item.businessId == businessId)
        require(detail == null || detail.item.productId == productId)
        detailFor(businessId, productId).value = detail
    }

    private fun inventoryFor(
        businessId: BusinessId,
    ): MutableStateFlow<List<InventoryReadItem>> = inventoryFlows.getOrPut(businessId) {
        MutableStateFlow(emptyList())
    }

    private fun detailFor(
        businessId: BusinessId,
        productId: ProductId,
    ): MutableStateFlow<InventoryProductDetail?> = detailFlows.getOrPut(businessId to productId) {
        MutableStateFlow(null)
    }
}
