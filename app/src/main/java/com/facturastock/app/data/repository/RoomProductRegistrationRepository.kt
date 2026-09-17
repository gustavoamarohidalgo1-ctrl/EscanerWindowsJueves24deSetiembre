package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.CatalogInvalidField
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRegistrationRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.repository.enqueueBestEffort
import com.facturastock.app.domain.usecase.SaveProductCatalogUseCase
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject

/** Usa las mismas validaciones, outbox y libro de existencias que los catálogos existentes. */
class RoomProductRegistrationRepository
    @Inject
    constructor(
        private val database: FacturaStockDatabase,
        private val products: ProductRepository,
        units: UnitRepository,
        locations: InventoryLocationRepository,
        private val inventory: ProductInventoryRepository,
        private val dispatchers: DispatcherProvider,
        private val backupScheduler: PurchaseBackupScheduler = DisabledPurchaseBackupScheduler,
    ) : ProductRegistrationRepository {
        private val saveProduct = SaveProductCatalogUseCase(products, units, locations)

        override suspend fun register(
            product: Product,
            quantity: BigDecimal,
            unitCost: UnitCost,
        ): CatalogMutationResult<Product> =
            withContext(dispatchers.io) {
                val initialQuantity = Quantity.of(quantity)
                val initialCost = UnitCost.of(unitCost.amount, unitCost.currency)
                product.salePrice?.let { salePrice ->
                    if (salePrice.currency != initialCost.currency) {
                        throw DomainRuleViolation(
                            ValidationError.CurrencyMismatch(initialCost.currency, salePrice.currency),
                        )
                    }
                }
                val locationId =
                    product.locationId
                        ?: return@withContext CatalogMutationResult.Invalid(CatalogInvalidField.LOCATION)
                val result =
                    storageCatching {
                        database.withTransaction {
                            // El chequeo y la inserción están serializados; un retry no se convierte en edición.
                            if (products.findById(product.productId) != null) {
                                return@withTransaction CatalogMutationResult.Stale
                            }
                            val business = database.businessDao().findById(product.businessId.value)
                            if (
                                business == null || business.status != CatalogStatus.ACTIVE.name ||
                                product.status != CatalogStatus.ACTIVE ||
                                database.cloudBusinessBindingDao().findByLocal(product.businessId.value) != null
                            ) {
                                return@withTransaction CatalogMutationResult.Invalid(CatalogInvalidField.OWNERSHIP)
                            }
                            val saved = saveProduct(product)
                            if (saved is CatalogMutationResult.Saved) {
                                inventory.addStock(
                                    businessId = product.businessId,
                                    productId = saved.value.productId,
                                    locationId = locationId,
                                    quantityToAdd = initialQuantity.value,
                                    currency = initialCost.currency,
                                    unitCost = initialCost.amount,
                                    idempotencyKey = "product-registration:v1:${product.productId.value}",
                                )
                            }
                            saved
                        }
                    }
                // El catálogo puede despertar el worker dentro de la transacción anidada; aquí el
                // segundo wake-up garantiza que ya estén confirmados tanto producto como existencias.
                if (result is CatalogMutationResult.Saved) backupScheduler.enqueueBestEffort()
                result
            }
    }
