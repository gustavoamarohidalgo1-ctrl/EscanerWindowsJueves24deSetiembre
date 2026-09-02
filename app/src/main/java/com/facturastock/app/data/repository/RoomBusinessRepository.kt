package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.dao.BusinessDao
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.BusinessRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomBusinessRepository @Inject constructor(
    private val businessDao: BusinessDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : BusinessRepository {
    override suspend fun create(business: Business): Business = withContext(dispatchers.io) {
        storageCatching {
            val now = clock.now()
            val stamped = business.copy(createdAt = now, updatedAt = now)
            businessDao.insert(stamped.toEntity())
            stamped
        }
    }

    override suspend fun update(business: Business): Boolean = withContext(dispatchers.io) {
        storageCatching {
            val stamped = business.copy(updatedAt = clock.now())
            businessDao.update(stamped.toEntity()) > 0
        }
    }

    override suspend fun findById(businessId: BusinessId): Business? = withContext(dispatchers.io) {
        storageCatching { businessDao.findById(businessId.value)?.toDomain() }
    }

    override fun observeById(businessId: BusinessId): Flow<Business?> =
        businessDao.observeById(businessId.value)
            .map { entity -> entity?.toDomain() }
            .flowOn(dispatchers.io)

    override suspend fun findByRuc(ruc: String): Business? = withContext(dispatchers.io) {
        storageCatching { businessDao.findByRuc(ruc)?.toDomain() }
    }

    override fun observeBusinesses(): Flow<List<Business>> =
        businessDao.observeAll()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun deleteById(businessId: BusinessId): Boolean = withContext(dispatchers.io) {
        storageCatching { businessDao.deleteById(businessId.value) > 0 }
    }
}
