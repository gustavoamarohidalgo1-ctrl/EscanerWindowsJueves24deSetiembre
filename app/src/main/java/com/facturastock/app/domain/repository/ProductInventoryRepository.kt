package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId

interface ProductInventoryRepository {
    suspend fun summaryForProduct(
        businessId: BusinessId,
        productId: ProductId,
    ): ProductInventorySummary
}

