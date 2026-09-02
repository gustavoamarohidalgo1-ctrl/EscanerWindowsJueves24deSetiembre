package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.ProductDao
import com.facturastock.app.data.local.dao.updateCas
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.MAX_CATALOG_PAGE_SIZE
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomProductRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val productDao: ProductDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
    private val backupScheduler: PurchaseBackupScheduler = DisabledPurchaseBackupScheduler,
) : ProductRepository {
    override suspend fun create(product: Product): Product = withContext(dispatchers.io) {
        val created = storageCatching {
            val now = clock.now()
            val stamped = product.copy(createdAt = now, updatedAt = now, version = 1L)
            val entity = stamped.toEntity()
            database.withTransaction {
                requireReferencesBelongToBusiness(entity)
                if (
                    !productDao.referencesAreActiveForCreate(
                        entity.businessId,
                        entity.unitId,
                        entity.purchaseUnitId,
                        entity.locationId,
                    )
                ) {
                    throw catalogOwnershipConflict(
                        "un producto nuevo solo puede usar unidad y almacén activos",
                    )
                }
                productDao.insert(entity)
                database.outboxOperationDao().insert(catalogOutbox(entity, 0L))
            }
            entity.toDomain()
        }
        // Room ya contiene tanto el producto como su operación. WorkManager es solo el wake-up.
        backupScheduler.enqueueBestEffort()
        created
    }

    override suspend fun createBatch(products: List<Product>): List<Product> =
        withContext(dispatchers.io) {
            if (products.isEmpty()) return@withContext emptyList()
            require(products.map(Product::productId).distinct().size == products.size) {
                "Un lote de productos no puede repetir productId"
            }
            val created = storageCatching {
                val now = clock.now()
                val entities = products.map { product ->
                    product.copy(createdAt = now, updatedAt = now, version = 1L).toEntity()
                }
                database.withTransaction {
                    entities.forEach { entity ->
                        requireReferencesBelongToBusiness(entity)
                        if (
                            !productDao.referencesAreActiveForCreate(
                                entity.businessId,
                                entity.unitId,
                                entity.purchaseUnitId,
                                entity.locationId,
                            )
                        ) {
                            throw catalogOwnershipConflict(
                                "un producto nuevo solo puede usar unidad y almacén activos",
                            )
                        }
                    }
                    productDao.insertAll(entities)
                    entities.forEach { entity ->
                        database.outboxOperationDao().insert(catalogOutbox(entity, 0L))
                    }
                }
                entities.map(ProductEntity::toDomain)
            }
            // El lote y todos sus mensajes ya son durables; WorkManager solo despierta el envío.
            backupScheduler.enqueueBestEffort()
            created
        }

    override suspend fun update(product: Product): Boolean = withContext(dispatchers.io) {
        val updated = storageCatching {
            database.withTransaction {
                val requested = product.toEntity()
                val existing = productDao.findById(requested.productId)
                    ?: return@withTransaction false
                if (existing.businessId != requested.businessId) {
                    throw catalogOwnershipConflict("un producto no puede cambiar de negocio")
                }
                if (requested.version == Long.MAX_VALUE) return@withTransaction false
                val entity = product.copy(
                    createdAt = java.time.Instant.ofEpochMilli(existing.createdAt),
                    updatedAt = java.time.Instant.ofEpochMilli(
                        maxOf(clock.now().toEpochMilli(), existing.updatedAt),
                    ),
                ).toEntity()
                requireReferencesBelongToBusiness(entity)
                // CAS: la versión que viaja en la entidad es la que el llamador leyó; si otro
                // guardado ganó la carrera, la fila no coincide y el update honestamente falla.
                if (productDao.updateCas(entity) == 0) return@withTransaction false
                val stored = entity.copy(version = entity.version + 1L)
                database.outboxOperationDao().insert(
                    catalogOutbox(stored, expectedVersion = entity.version),
                )
                true
            }
        }
        if (updated) backupScheduler.enqueueBestEffort()
        updated
    }

    override suspend fun updateSalePrice(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
        salePrice: Money,
    ): ProductSalePriceMutationResult = withContext(dispatchers.io) {
        if (expectedVersion < 1L || !ProductSalePricePolicy.supports(salePrice)) {
            return@withContext ProductSalePriceMutationResult.InvalidPrice
        }
        val mutation = storageCatching {
            database.withTransaction {
                val existing = productDao.findById(productId.value)
                    ?: return@withTransaction ProductSalePriceMutationResult.NotFound
                if (existing.businessId != businessId.value) {
                    return@withTransaction ProductSalePriceMutationResult.NotFound
                }
                if (existing.status != CatalogStatus.ACTIVE.name) {
                    return@withTransaction ProductSalePriceMutationResult.Inactive
                }
                if (existing.version != expectedVersion || existing.version == Long.MAX_VALUE) {
                    return@withTransaction ProductSalePriceMutationResult.Stale
                }
                if (
                    existing.salePriceMinorUnits == salePrice.minorUnits &&
                    existing.salePriceCurrencyCode == salePrice.currency.value
                ) {
                    return@withTransaction ProductSalePriceMutationResult.Unchanged(existing.toDomain())
                }
                val updatedAt = maxOf(clock.now().toEpochMilli(), existing.updatedAt)
                if (
                    productDao.updateSalePrice(
                        productId = existing.productId,
                        businessId = existing.businessId,
                        expectedVersion = existing.version,
                        salePriceMinorUnits = salePrice.minorUnits,
                        salePriceCurrencyCode = salePrice.currency.value,
                        updatedAt = updatedAt,
                    ) != 1
                ) {
                    return@withTransaction ProductSalePriceMutationResult.Stale
                }
                val stored = existing.copy(
                    salePriceMinorUnits = salePrice.minorUnits,
                    salePriceCurrencyCode = salePrice.currency.value,
                    version = existing.version + 1L,
                    updatedAt = updatedAt,
                )
                database.outboxOperationDao().insert(
                    catalogOutbox(stored, expectedVersion = existing.version),
                )
                ProductSalePriceMutationResult.Updated(stored.toDomain())
            }
        }
        if (mutation is ProductSalePriceMutationResult.Updated) {
            backupScheduler.enqueueBestEffort()
        }
        mutation
    }

    override suspend fun findById(productId: ProductId): Product? = withContext(dispatchers.io) {
        storageCatching { productDao.findById(productId.value)?.toDomain() }
    }

    override suspend fun findBySku(businessId: BusinessId, sku: String): Product? =
        withContext(dispatchers.io) {
            storageCatching {
                val canonical = CatalogCanonicalizer.sku(sku) ?: return@storageCatching null
                productDao.findBySku(businessId.value, canonical)?.toDomain()
            }
        }

    override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product? =
        withContext(dispatchers.io) {
            storageCatching {
                val canonical = CatalogCanonicalizer.barcode(barcode) ?: return@storageCatching null
                productDao.findByBarcode(businessId.value, canonical)?.toDomain()
            }
        }

    override suspend fun findByNormalizedName(
        businessId: BusinessId,
        normalizedName: String,
    ): List<Product> = withContext(dispatchers.io) {
        storageCatching {
            val normalized = normalizedName.trim().lowercase(Locale.ROOT)
            productDao.findByNormalizedName(businessId.value, normalized).map { it.toDomain() }
        }
    }

    override suspend fun searchActiveByName(
        businessId: BusinessId,
        query: String,
        limit: Int,
    ): List<Product> = withContext(dispatchers.io) {
        require(limit in 1..MAX_CATALOG_PAGE_SIZE)
        storageCatching {
            productDao.searchActiveByName(
                businessId = businessId.value,
                pattern = catalogLikePattern(query),
                limit = limit,
            ).map(ProductEntity::toDomain)
        }
    }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<Product> =
        withContext(dispatchers.io) {
            storageCatching {
                val pattern = catalogLikePattern(search.query)
                val status = search.status?.name
                CatalogPage(
                    items = productDao.searchPage(
                        businessId.value,
                        pattern,
                        status,
                        search.limit,
                        search.offset,
                    ).map { it.toDomain() },
                    total = productDao.countSearch(businessId.value, pattern, status),
                    offset = search.offset,
                    limit = search.limit,
                )
            }
        }

    override fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<Product>> {
        val pattern = catalogLikePattern(search.query)
        val status = search.status?.name
        return combine(
            productDao.observeSearchPage(
                businessId.value,
                pattern,
                status,
                search.limit,
                search.offset,
            ),
            productDao.observeCountSearch(businessId.value, pattern, status),
        ) { items, total ->
            CatalogPage(
                items = items.map(ProductEntity::toDomain),
                total = total,
                offset = search.offset,
                limit = search.limit,
            )
        }.distinctUntilChanged().flowOn(dispatchers.io)
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<Product>> =
        productDao.observeForBusiness(businessId.value)
            .map { list -> list.map { it.toDomain() } }
            // Room invalida por tabla, incluso por escrituras de otro negocio. No propagar una
            // lista identica evita reconstrucciones costosas en consumidores como Ventas.
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override suspend fun archive(productId: ProductId): Boolean = setStatus(
        productId,
        CatalogStatus.ARCHIVED,
    )

    override suspend fun restore(productId: ProductId): Boolean = setStatus(
        productId,
        CatalogStatus.ACTIVE,
    )

    private suspend fun setStatus(productId: ProductId, status: CatalogStatus): Boolean =
        withContext(dispatchers.io) {
            val mutation = storageCatching {
                database.withTransaction {
                    val existing = productDao.findById(productId.value)
                        ?: return@withTransaction StatusMutation(success = false, changed = false)
                    if (existing.status == status.name) {
                        StatusMutation(success = true, changed = false)
                    } else if (existing.version == Long.MAX_VALUE) {
                        StatusMutation(success = false, changed = false)
                    } else {
                        val updatedAt = maxOf(clock.now().toEpochMilli(), existing.updatedAt)
                        val changed = productDao.setStatus(
                            productId = productId.value,
                            status = status.name,
                            expectedVersion = existing.version,
                            updatedAt = updatedAt,
                        ) == 1
                        if (!changed) {
                            StatusMutation(success = false, changed = false)
                        } else {
                            val stored = existing.copy(
                                status = status.name,
                                version = existing.version + 1L,
                                updatedAt = updatedAt,
                            )
                            database.outboxOperationDao().insert(
                                catalogOutbox(stored, existing.version),
                            )
                            StatusMutation(success = true, changed = true)
                        }
                    }
                }
            }
            if (mutation.changed) backupScheduler.enqueueBestEffort()
            mutation.success
        }

    private suspend fun requireReferencesBelongToBusiness(
        product: ProductEntity,
    ) {
        if (
            !productDao.referencesBelongToBusiness(
                product.businessId,
                product.unitId,
                product.purchaseUnitId,
                product.locationId,
            )
        ) {
            throw catalogOwnershipConflict(
                "unidad, unidad de compra y almacén deben pertenecer al negocio del producto",
            )
        }
    }

    private fun catalogOwnershipConflict(detail: String): StorageException =
        StorageException(StorageError.ConstraintConflict(detail))

    /** Congela referencias semánticas; UUIDs locales de unidad/almacén nunca salen al wire. */
    private suspend fun catalogOutbox(
        product: ProductEntity,
        expectedVersion: Long,
    ) = database.catalogSyncLinkDao().findByLocal(
        product.businessId,
        PRODUCT_ENTITY_TYPE,
        product.productId,
    ).let { link ->
        CatalogSyncOutbox.product(
            entity = product,
            expectedVersion = expectedVersion,
            inventoryUnit = requireNotNull(database.unitDao().findById(product.unitId)) {
                "La unidad de inventario dejó de existir"
            },
            purchaseUnit = product.purchaseUnitId?.let { unitId ->
                requireNotNull(database.unitDao().findById(unitId)) {
                    "La unidad de compra dejó de existir"
                }
            },
            location = product.locationId?.let { locationId ->
                requireNotNull(database.inventoryLocationDao().findById(locationId)) {
                    "El almacén dejó de existir"
                }
            },
            remoteEntityId = link?.remoteEntityId,
        )
    }
}

private data class StatusMutation(val success: Boolean, val changed: Boolean)
