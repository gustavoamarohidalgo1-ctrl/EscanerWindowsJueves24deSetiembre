package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.dao.InventoryDao
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ProductInventoryPosition
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.ProductInventoryRepository
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.withContext

class RoomProductInventoryRepository @Inject constructor(
    private val inventoryDao: InventoryDao,
    private val dispatchers: DispatcherProvider,
) : ProductInventoryRepository {
    override suspend fun summaryForProduct(
        businessId: BusinessId,
        productId: ProductId,
    ): ProductInventorySummary = withContext(dispatchers.io) {
        storageCatching {
            ProductInventorySummary(
                inventoryDao.listBalancesForProduct(businessId.value, productId.value).map { balance ->
                    val currency = CurrencyCode.of(balance.currencyCode)
                    ProductInventoryPosition(
                        locationId = LocationId.from(UUID.fromString(balance.locationId)),
                        quantityOnHand = BigDecimal(balance.quantityOnHand),
                        averageUnitCost = UnitCost.of(balance.averageUnitCost, currency),
                    )
                },
            )
        }
    }
}

