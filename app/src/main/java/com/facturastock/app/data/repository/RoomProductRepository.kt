package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceLinesEditCodec
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
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
import com.facturastock.app.domain.repository.ProductDeletionResult
import com.facturastock.app.domain.repository.ProductBatchCreationResult
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import java.util.Locale
import java.math.BigDecimal
import java.io.IOException
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
    /**
     * Verificación de lectura dentro de la misma transacción que escribe: si la fila confirmada
     * no coincide exactamente con lo que se intentó guardar, el guardado falla de forma
     * explícita en lugar de anunciar un éxito sin evidencia en disco. Un SELECT por clave
     * primaria no aporta latencia perceptible frente a esa garantía.
     */
    private suspend fun verifyPersisted(expected: List<ProductEntity>) {
        expected.forEach { entity ->
            val stored = productDao.findById(entity.productId)
            if (stored == null || stored != entity) {
                throw StorageException(
                    StorageError.Unavailable,
                    IllegalStateException(
                        "la relectura del producto no coincide con lo escrito",
                    ),
                )
            }
        }
    }

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
                verifyPersisted(listOf(entity))
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
                    verifyPersisted(entities)
                }
                entities.map(ProductEntity::toDomain)
            }
            // El lote y todos sus mensajes ya son durables; WorkManager solo despierta el envío.
            backupScheduler.enqueueBestEffort()
            created
        }

    override suspend fun createBatchSkippingExistingNames(
        businessId: BusinessId,
        products: List<Product>,
    ): ProductBatchCreationResult = withContext(dispatchers.io) {
        if (products.isEmpty()) {
            return@withContext ProductBatchCreationResult(emptyList(), 0)
        }
        require(products.all { product -> product.businessId == businessId })
        require(products.map(Product::productId).distinct().size == products.size) {
            "Un lote de productos no puede repetir productId"
        }
        val normalizedNames = products.map { product ->
            product.name.trim().lowercase(Locale.ROOT)
        }
        require(normalizedNames.distinct().size == normalizedNames.size) {
            "Un lote condicionado no puede repetir nombres normalizados"
        }

        val result = storageCatching {
            val now = clock.now()
            val candidates = products.map { product ->
                product.copy(createdAt = now, updatedAt = now, version = 1L).toEntity()
            }
            database.withTransaction {
                // Room serializa sus transacciones. La lectura y la escritura ocurren bajo el
                // mismo turno de escritura, por lo que otro escaneo no puede intercalarse entre
                // ambas y crear el mismo nombre.
                val pending = candidates.filter { entity ->
                    productDao.findByNormalizedName(
                        businessId = businessId.value,
                        normalizedName = entity.normalizedName,
                    ).isEmpty()
                }
                pending.forEach { entity ->
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
                productDao.insertAll(pending)
                pending.forEach { entity ->
                    database.outboxOperationDao().insert(catalogOutbox(entity, 0L))
                }
                verifyPersisted(pending)
                ProductBatchCreationResult(
                    created = pending.map(ProductEntity::toDomain),
                    alreadyExistingCount = candidates.size - pending.size,
                )
            }
        }
        if (result.created.isNotEmpty()) backupScheduler.enqueueBestEffort()
        result
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
                if (requested.version != existing.version || requested.version == Long.MAX_VALUE) {
                    return@withTransaction false
                }
                val entity = product.copy(
                    createdAt = java.time.Instant.ofEpochMilli(existing.createdAt),
                    updatedAt = java.time.Instant.ofEpochMilli(
                        maxOf(clock.now().toEpochMilli(), existing.updatedAt),
                    ),
                ).toEntity()
                requireReferencesBelongToBusiness(entity)
                requireChangedReferencesAreActive(existing, entity)
                // CAS: la versión que viaja en la entidad es la que el llamador leyó; si otro
                // guardado ganó la carrera, la fila no coincide y el update honestamente falla.
                if (productDao.updateCas(entity) == 0) return@withTransaction false
                val stored = entity.copy(version = entity.version + 1L)
                verifyPersisted(listOf(stored))
                database.outboxOperationDao().insert(
                    catalogOutbox(stored, expectedVersion = entity.version),
                )
                true
            }
        }
        if (updated) backupScheduler.enqueueBestEffort()
        updated
    }

    override suspend fun deletePermanently(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
    ): ProductDeletionResult = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val stored = productDao.findById(productId.value)
                    ?: return@withTransaction ProductDeletionResult.NOT_FOUND
                if (stored.businessId != businessId.value) {
                    return@withTransaction ProductDeletionResult.NOT_FOUND
                }
                if (expectedVersion < 1L || stored.version != expectedVersion) {
                    return@withTransaction ProductDeletionResult.STALE
                }
                val outbox = database.outboxOperationDao()
                if (database.cloudBusinessBindingDao().findByLocal(businessId.value) != null ||
                    productDao.hasRemoteDeletionReferences(businessId.value, productId.value) ||
                    outbox.hasRemoteDeletionRisk(businessId.value)
                ) {
                    return@withTransaction ProductDeletionResult.SHARED_BUSINESS
                }
                if (productDao.hasDeletionReferences(productId.value) ||
                    hasSnapshotDeletionReferences(businessId, productId)
                ) {
                    return@withTransaction ProductDeletionResult.HAS_HISTORY
                }
                val balances = database.inventoryDao().listBalancesForProduct(businessId.value, productId.value)
                // No SUM ni CAST a REAL: posiciones opuestas nunca se cancelan para autorizar
                // el borrado, y hasta el decimal no cero más pequeño conserva sus existencias.
                if (balances.any { BigDecimal(it.quantityOnHand).signum() != 0 }) {
                    return@withTransaction ProductDeletionResult.HAS_STOCK
                }
                outbox.deleteNeverAttemptedLocalProduct(businessId.value, productId.value)
                val deletedBalances = productDao.deleteUnusedBalances(businessId.value, productId.value)
                if (deletedBalances != balances.size ||
                    productDao.deletePermanently(businessId.value, productId.value, expectedVersion) != 1 ||
                    productDao.findById(productId.value) != null ||
                    outbox.hasProductOperations(businessId.value, productId.value) ||
                    database.inventoryDao().listBalancesForProduct(businessId.value, productId.value).isNotEmpty()
                ) {
                    // También revierte la limpieza si un trigger ignora o altera la escritura.
                    throw StorageException(
                        StorageError.Unavailable,
                        IllegalStateException("la relectura no confirma la eliminación del producto"),
                    )
                }
                ProductDeletionResult.DELETED
            }
        }
    }

    private suspend fun hasSnapshotDeletionReferences(businessId: BusinessId, productId: ProductId): Boolean {
        for (entity in productDao.listDeletionEditCandidates(businessId.value, productId.value)) {
            if (!InvoiceLinesEditCodec.supports(entity.payloadCodecVersion) ||
                InvoiceLinesEditCodec.sha256(entity.payload) != entity.payloadSha256
            ) return true
            val snapshot = try {
                InvoiceLinesEditCodec.decode(entity.payload)
            } catch (_: IOException) {
                return true
            }
            // Incluye tombstones restaurables y productos staged; nunca se reescribe el editor.
            if (snapshot.lines.any { it.linkedProductId == productId || it.stagedProduct?.productId == productId }) {
                return true
            }
        }
        for (entity in productDao.listDeletionPreparedCandidates(businessId.value, productId.value)) {
            if (!PreparedPurchaseCodec.supports(entity.payloadCodecVersion) ||
                PreparedPurchaseCodec.sha256(entity.payload) != entity.payloadSha256
            ) return true
            val snapshot = try {
                PreparedPurchaseCodec.decode(entity.payload)
            } catch (_: IOException) {
                return true
            }
            if (snapshot.lines.any { it.productId == productId }) return true
        }
        return false
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
                verifyPersisted(listOf(stored))
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

    override suspend fun findByIds(productIds: Collection<ProductId>): Map<ProductId, Product> =
        withContext(dispatchers.io) {
            if (productIds.isEmpty()) return@withContext emptyMap()
            storageCatching {
                productIds
                    .map { it.value }
                    .distinct()
                    .chunked(IN_QUERY_CHUNK)
                    .flatMap { chunk -> productDao.findByIds(chunk) }
                    .map { it.toDomain() }
                    .associateBy { it.productId }
            }
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
            // Compara filas antes de reconstruir el catalogo cuando Room invalida por otro negocio.
            .distinctUntilChanged()
            .map { list -> list.map { it.toDomain() } }
            // Room invalida por tabla, incluso por escrituras de otro negocio. No propagar una
            // lista identica evita reconstrucciones costosas en consumidores como Ventas.
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override suspend fun listForBusiness(businessId: BusinessId): List<Product> =
        withContext(dispatchers.io) {
            storageCatching { productDao.listForBusiness(businessId.value).map(ProductEntity::toDomain) }
        }

    override suspend fun archive(productId: ProductId): Boolean = setStatus(
        productId,
        CatalogStatus.ARCHIVED,
    )

    override suspend fun restore(productId: ProductId): Boolean = setStatus(
        productId,
        CatalogStatus.ACTIVE,
    )

    override suspend fun archive(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
    ): Boolean = setStatus(productId, CatalogStatus.ARCHIVED, businessId, expectedVersion)

    override suspend fun restore(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
    ): Boolean = setStatus(productId, CatalogStatus.ACTIVE, businessId, expectedVersion)

    private suspend fun setStatus(
        productId: ProductId,
        status: CatalogStatus,
        expectedBusinessId: BusinessId? = null,
        expectedVersion: Long? = null,
    ): Boolean =
        withContext(dispatchers.io) {
            val mutation = storageCatching {
                database.withTransaction {
                    val existing = productDao.findById(productId.value)
                        ?: return@withTransaction StatusMutation(success = false, changed = false)
                    if ((expectedBusinessId != null && existing.businessId != expectedBusinessId.value) ||
                        (expectedVersion != null && (expectedVersion < 1L || existing.version != expectedVersion))
                    ) {
                        return@withTransaction StatusMutation(success = false, changed = false)
                    }
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
                            verifyPersisted(listOf(stored))
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

    /** La revisión previa de UI puede quedar obsoleta mientras se archiva una referencia. */
    private suspend fun requireChangedReferencesAreActive(
        existing: ProductEntity,
        requested: ProductEntity,
    ) {
        val inactiveUnit = requested.unitId != existing.unitId &&
            database.unitDao().findById(requested.unitId)?.status != CatalogStatus.ACTIVE.name
        val inactivePurchaseUnit = requested.purchaseUnitId != null &&
            requested.purchaseUnitId != existing.purchaseUnitId &&
            database.unitDao().findById(requested.purchaseUnitId)?.status != CatalogStatus.ACTIVE.name
        val inactiveLocation = requested.locationId != null &&
            requested.locationId != existing.locationId &&
            database.inventoryLocationDao().findById(requested.locationId)?.status != CatalogStatus.ACTIVE.name
        if (inactiveUnit || inactivePurchaseUnit || inactiveLocation) {
            throw catalogOwnershipConflict("una referencia nueva del producto debe seguir activa al guardar")
        }
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

private const val IN_QUERY_CHUNK = 100
