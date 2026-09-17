package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Lecturas offline-first y diagnostico del libro local. */
interface InventoryReadRepository {
    fun observeInventory(businessId: BusinessId): Flow<List<InventoryReadItem>>

    /** Cabecera y saldos actuales; las lecturas de venta no necesitan materializar el historial. */
    fun observeProductItem(
        businessId: BusinessId,
        productId: ProductId,
    ): Flow<InventoryReadItem?> = observeProduct(businessId, productId).map { it?.item }

    /** Null tambien cubre un productId valido que pertenece a otro negocio. */
    fun observeProduct(
        businessId: BusinessId,
        productId: ProductId,
    ): Flow<InventoryProductDetail?>

    /** Reproduce el libro dentro de una instantanea Room y solo compara la proyeccion. */
    suspend fun diagnose(businessId: BusinessId): InventoryDiagnosticReport
}
