package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.repository.InventoryLocationRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomInventoryLocationRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : InventoryLocationRepository {
    private val inventoryLocationDao get() = database.inventoryLocationDao()

    override suspend fun create(location: InventoryLocation): InventoryLocation =
        withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    val now = clock.now()
                    val stamped = location.copy(createdAt = now, updatedAt = now)
                    val entity = stamped.toEntity()
                    requireCanonicalNameAvailable(entity)
                    inventoryLocationDao.insert(entity)
                    stamped
                }
            }
        }

    override suspend fun update(location: InventoryLocation): Boolean = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val stamped = location.copy(updatedAt = clock.now())
                val entity = stamped.toEntity()
                val existing = inventoryLocationDao.findById(stamped.locationId.value)
                    ?: return@withTransaction false
                if (existing.businessId != stamped.businessId.value) {
                    throw StorageException(
                        StorageError.ConstraintConflict("un almacén no puede cambiar de negocio"),
                    )
                }
                if (
                    existing.name != stamped.name &&
                    database.cloudBusinessBindingDao().findByLocal(existing.businessId) != null
                ) {
                    throw StorageException(
                        StorageError.ConstraintConflict(
                            "un almacén enlazado a inventario compartido no puede cambiar de nombre",
                        ),
                    )
                }
                requireCanonicalNameAvailable(entity)
                inventoryLocationDao.update(entity) > 0
            }
        }
    }

    override suspend fun findById(locationId: LocationId): InventoryLocation? =
        withContext(dispatchers.io) {
            storageCatching { inventoryLocationDao.findById(locationId.value)?.toDomain() }
        }

    override suspend fun findByName(businessId: BusinessId, name: String): InventoryLocation? =
        withContext(dispatchers.io) {
            storageCatching { inventoryLocationDao.findByName(businessId.value, name)?.toDomain() }
        }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<InventoryLocation> = withContext(dispatchers.io) {
        storageCatching {
            val pattern = catalogLikePattern(search.query)
            val status = search.status?.name
            CatalogPage(
                items = inventoryLocationDao.searchPage(
                    businessId.value,
                    pattern,
                    status,
                    search.limit,
                    search.offset,
                ).map { it.toDomain() },
                total = inventoryLocationDao.countSearch(businessId.value, pattern, status),
                offset = search.offset,
                limit = search.limit,
            )
        }
    }

    override fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<InventoryLocation>> {
        val pattern = catalogLikePattern(search.query)
        val status = search.status?.name
        return combine(
            inventoryLocationDao.observeSearchPage(
                businessId.value,
                pattern,
                status,
                search.limit,
                search.offset,
            ),
            inventoryLocationDao.observeCountSearch(businessId.value, pattern, status),
        ) { items, total ->
            CatalogPage(
                items = items.map { it.toDomain() },
                total = total,
                offset = search.offset,
                limit = search.limit,
            )
        }.distinctUntilChanged().flowOn(dispatchers.io)
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<InventoryLocation>> =
        inventoryLocationDao.observeForBusiness(businessId.value)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun archive(locationId: LocationId): Boolean =
        setStatus(locationId, CatalogStatus.ARCHIVED)

    override suspend fun restore(locationId: LocationId): Boolean =
        setStatus(locationId, CatalogStatus.ACTIVE)

    private suspend fun setStatus(locationId: LocationId, status: CatalogStatus): Boolean =
        withContext(dispatchers.io) {
            storageCatching {
                val existing = inventoryLocationDao.findById(locationId.value)
                    ?: return@storageCatching false
                if (existing.status == status.name) true else {
                    inventoryLocationDao.setStatus(
                        locationId.value,
                        status.name,
                        clock.now().toEpochMilli(),
                    ) == 1
                }
            }
        }

    private suspend fun requireCanonicalNameAvailable(candidate: InventoryLocationEntity) {
        val canonical = canonicalLocationName(candidate.name)
        val conflict = inventoryLocationDao.listForBusiness(candidate.businessId).any { existing ->
            existing.locationId != candidate.locationId &&
                canonicalLocationName(existing.name) == canonical
        }
        if (conflict) {
            throw StorageException(
                StorageError.ConstraintConflict(
                    "ya existe un almacén con el mismo nombre normalizado",
                ),
            )
        }
    }
}
