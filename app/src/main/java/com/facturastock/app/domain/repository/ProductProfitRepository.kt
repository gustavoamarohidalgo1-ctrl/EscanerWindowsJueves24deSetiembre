package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.id.BusinessId
import kotlinx.coroutines.flow.Flow

/** Lectura offline-first de ganancias estimadas; nunca convierte ni mezcla monedas. */
interface ProductProfitRepository {
    fun observeForBusiness(businessId: BusinessId): Flow<List<ProductProfit>>
}
