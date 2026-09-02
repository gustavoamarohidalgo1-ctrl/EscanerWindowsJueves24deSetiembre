package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.dao.SupplierProductAliasDao
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSupplierProductAliasRepository @Inject constructor(
    private val supplierProductAliasDao: SupplierProductAliasDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : SupplierProductAliasRepository {
    override suspend fun create(alias: SupplierProductAlias): SupplierProductAlias =
        withContext(dispatchers.io) {
            storageCatching {
                val now = clock.now()
                val stamped = alias.copy(createdAt = now, updatedAt = now)
                val entity = stamped.toEntity()
                requireReferencesBelongToBusiness(entity)
                supplierProductAliasDao.insert(entity)
                stamped
            }
        }

    override suspend fun update(alias: SupplierProductAlias): Boolean = withContext(dispatchers.io) {
        storageCatching {
            val stamped = alias.copy(updatedAt = clock.now())
            val entity = stamped.toEntity()
            val existing = supplierProductAliasDao.findById(entity.aliasId)
                ?: return@storageCatching false
            if (existing.businessId != entity.businessId) {
                throw ownershipConflict("un alias no puede cambiar de negocio")
            }
            requireReferencesBelongToBusiness(entity)
            supplierProductAliasDao.update(entity) > 0
        }
    }

    override suspend fun findById(aliasId: AliasId): SupplierProductAlias? =
        withContext(dispatchers.io) {
            storageCatching { supplierProductAliasDao.findById(aliasId.value)?.toDomain() }
        }

    override suspend fun findByNormalizedAlias(
        businessId: BusinessId,
        alias: String,
    ): List<SupplierProductAlias> = withContext(dispatchers.io) {
        storageCatching {
            val normalized = alias.trim().lowercase(Locale.ROOT)
            supplierProductAliasDao.findByNormalizedAlias(businessId.value, normalized)
                .map { it.toDomain() }
        }
    }

    override fun observeForProduct(productId: ProductId): Flow<List<SupplierProductAlias>> =
        supplierProductAliasDao.observeForProduct(productId.value)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun listForProduct(productId: ProductId): List<SupplierProductAlias> =
        withContext(dispatchers.io) {
            storageCatching {
                supplierProductAliasDao.listForProduct(productId.value).map { it.toDomain() }
            }
        }

    override suspend fun deleteById(aliasId: AliasId): Boolean = withContext(dispatchers.io) {
        storageCatching { supplierProductAliasDao.deleteById(aliasId.value) > 0 }
    }

    private suspend fun requireReferencesBelongToBusiness(
        alias: SupplierProductAliasEntity,
    ) {
        if (
            !supplierProductAliasDao.referencesBelongToBusiness(
                alias.businessId,
                alias.supplierId,
                alias.productId,
            )
        ) {
            throw ownershipConflict(
                "proveedor y producto del alias deben pertenecer al mismo negocio",
            )
        }
    }

    private fun ownershipConflict(detail: String): StorageException =
        StorageException(StorageError.ConstraintConflict(detail))
}
