package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.dao.UnitDao
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.UnitRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomUnitRepository @Inject constructor(
    private val unitDao: UnitDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : UnitRepository {
    override suspend fun create(unit: UnitOfMeasure): UnitOfMeasure = withContext(dispatchers.io) {
        storageCatching {
            val now = clock.now()
            val stamped = unit.copy(createdAt = now, updatedAt = now)
            val entity = stamped.toEntity()
            unitDao.insert(entity)
            entity.toDomain()
        }
    }

    override suspend fun update(unit: UnitOfMeasure): Boolean = withContext(dispatchers.io) {
        storageCatching {
            val stamped = unit.copy(updatedAt = clock.now())
            val existing = unitDao.findById(stamped.unitId.value) ?: return@storageCatching false
            if (existing.businessId != stamped.businessId.value) {
                throw StorageException(
                    StorageError.ConstraintConflict("una unidad no puede cambiar de negocio"),
                )
            }
            unitDao.update(stamped.toEntity()) > 0
        }
    }

    override suspend fun findById(unitId: UnitId): UnitOfMeasure? = withContext(dispatchers.io) {
        storageCatching { unitDao.findById(unitId.value)?.toDomain() }
    }

    override suspend fun findByCode(businessId: BusinessId, code: String): UnitOfMeasure? =
        withContext(dispatchers.io) {
            storageCatching {
                unitDao.findByCode(businessId.value, CatalogCanonicalizer.unitCode(code))?.toDomain()
            }
        }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<UnitOfMeasure> = withContext(dispatchers.io) {
        storageCatching {
            val pattern = catalogLikePattern(search.query)
            val status = search.status?.name
            CatalogPage(
                items = unitDao.searchPage(
                    businessId.value,
                    pattern,
                    status,
                    search.limit,
                    search.offset,
                ).map { it.toDomain() },
                total = unitDao.countSearch(businessId.value, pattern, status),
                offset = search.offset,
                limit = search.limit,
            )
        }
    }

    override fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<UnitOfMeasure>> {
        val pattern = catalogLikePattern(search.query)
        val status = search.status?.name
        return combine(
            unitDao.observeSearchPage(
                businessId.value,
                pattern,
                status,
                search.limit,
                search.offset,
            ),
            unitDao.observeCountSearch(businessId.value, pattern, status),
        ) { items, total ->
            CatalogPage(
                items = items.map { it.toDomain() },
                total = total,
                offset = search.offset,
                limit = search.limit,
            )
        }.distinctUntilChanged().flowOn(dispatchers.io)
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<UnitOfMeasure>> =
        unitDao.observeForBusiness(businessId.value)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun archive(unitId: UnitId): Boolean = setStatus(unitId, CatalogStatus.ARCHIVED)

    override suspend fun restore(unitId: UnitId): Boolean = setStatus(unitId, CatalogStatus.ACTIVE)

    private suspend fun setStatus(unitId: UnitId, status: CatalogStatus): Boolean =
        withContext(dispatchers.io) {
            storageCatching {
                val existing = unitDao.findById(unitId.value) ?: return@storageCatching false
                if (existing.status == status.name) true else {
                    unitDao.setStatus(unitId.value, status.name, clock.now().toEpochMilli()) == 1
                }
            }
        }
}
