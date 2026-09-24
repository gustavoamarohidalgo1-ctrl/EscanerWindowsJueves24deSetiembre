package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.SupplierDao
import com.facturastock.app.data.local.dao.updateCas
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSupplierRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val supplierDao: SupplierDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
    private val backupScheduler: PurchaseBackupScheduler = DisabledPurchaseBackupScheduler,
) : SupplierRepository {
    override suspend fun create(supplier: Supplier): Supplier = withContext(dispatchers.io) {
        val created = storageCatching {
            val now = clock.now()
            val stamped = supplier.copy(createdAt = now, updatedAt = now, version = 1L)
            val entity = stamped.toEntity()
            database.withTransaction {
                supplierDao.insert(entity)
                database.outboxOperationDao().insert(catalogOutbox(entity, 0L))
            }
            entity.toDomain()
        }
        backupScheduler.enqueueBestEffort()
        created
    }

    override suspend fun update(supplier: Supplier): Boolean = withContext(dispatchers.io) {
        val updated = storageCatching {
            database.withTransaction {
                val requested = supplier.toEntity()
                val existing = supplierDao.findById(requested.supplierId)
                    ?: return@withTransaction false
                if (existing.businessId != requested.businessId) {
                    throw StorageException(
                        StorageError.ConstraintConflict("un proveedor no puede cambiar de negocio"),
                    )
                }
                if (requested.version == Long.MAX_VALUE) return@withTransaction false
                val entity = supplier.copy(
                    createdAt = java.time.Instant.ofEpochMilli(existing.createdAt),
                    updatedAt = java.time.Instant.ofEpochMilli(
                        maxOf(clock.now().toEpochMilli(), existing.updatedAt),
                    ),
                ).toEntity()
                // CAS por versión leída: una edición sobre datos obsoletos afecta cero filas.
                if (supplierDao.updateCas(entity) == 0) return@withTransaction false
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

    override suspend fun findById(supplierId: SupplierId): Supplier? = withContext(dispatchers.io) {
        storageCatching { supplierDao.findById(supplierId.value)?.toDomain() }
    }

    override suspend fun findByRuc(businessId: BusinessId, ruc: String): Supplier? =
        withContext(dispatchers.io) {
            storageCatching {
                val canonical = CatalogCanonicalizer.ruc(ruc) ?: return@storageCatching null
                supplierDao.findByRuc(businessId.value, canonical)?.toDomain()
            }
        }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<Supplier> =
        withContext(dispatchers.io) {
            storageCatching {
                val pattern = catalogLikePattern(search.query)
                val status = search.status?.name
                CatalogPage(
                    items = supplierDao.searchPage(
                        businessId.value,
                        pattern,
                        status,
                        search.limit,
                        search.offset,
                    ).map { it.toDomain() },
                    total = supplierDao.countSearch(businessId.value, pattern, status),
                    offset = search.offset,
                    limit = search.limit,
                )
            }
        }

    override fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<Supplier>> {
        val pattern = catalogLikePattern(search.query)
        val status = search.status?.name
        return combine(
            supplierDao.observeSearchPage(
                businessId.value,
                pattern,
                status,
                search.limit,
                search.offset,
            ),
            supplierDao.observeCountSearch(businessId.value, pattern, status),
        ) { items, total ->
            CatalogPage(
                items = items.map { it.toDomain() },
                total = total,
                offset = search.offset,
                limit = search.limit,
            )
        }.distinctUntilChanged().flowOn(dispatchers.io)
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<Supplier>> =
        supplierDao.observeForBusiness(businessId.value)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun archive(supplierId: SupplierId): Boolean = setStatus(
        supplierId,
        CatalogStatus.ARCHIVED,
    )

    override suspend fun restore(supplierId: SupplierId): Boolean = setStatus(
        supplierId,
        CatalogStatus.ACTIVE,
    )

    private suspend fun setStatus(supplierId: SupplierId, status: CatalogStatus): Boolean =
        withContext(dispatchers.io) {
            val mutation = storageCatching {
                database.withTransaction {
                    val existing = supplierDao.findById(supplierId.value)
                        ?: return@withTransaction SupplierStatusMutation(
                            success = false,
                            changed = false,
                        )
                    if (existing.status == status.name) {
                        SupplierStatusMutation(success = true, changed = false)
                    } else if (existing.version == Long.MAX_VALUE) {
                        SupplierStatusMutation(success = false, changed = false)
                    } else {
                        val updatedAt = maxOf(clock.now().toEpochMilli(), existing.updatedAt)
                        val changed = supplierDao.setStatus(
                            supplierId = supplierId.value,
                            status = status.name,
                            expectedVersion = existing.version,
                            updatedAt = updatedAt,
                        ) == 1
                        if (!changed) {
                            SupplierStatusMutation(success = false, changed = false)
                        } else {
                            val stored = existing.copy(
                                status = status.name,
                                version = existing.version + 1L,
                                updatedAt = updatedAt,
                            )
                            database.outboxOperationDao().insert(
                                catalogOutbox(stored, existing.version),
                            )
                            SupplierStatusMutation(success = true, changed = true)
                        }
                    }
                }
            }
            if (mutation.changed) backupScheduler.enqueueBestEffort()
            mutation.success
        }

    private suspend fun catalogOutbox(
        supplier: SupplierEntity,
        expectedVersion: Long,
    ) = CatalogSyncOutbox.supplier(
        entity = supplier,
        expectedVersion = expectedVersion,
        remoteEntityId = database.catalogSyncLinkDao().findByLocal(
            supplier.businessId,
            SUPPLIER_ENTITY_TYPE,
            supplier.supplierId,
        )?.remoteEntityId,
    )
}

private data class SupplierStatusMutation(val success: Boolean, val changed: Boolean)
