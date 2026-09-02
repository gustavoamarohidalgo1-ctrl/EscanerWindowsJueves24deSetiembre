package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CatalogSyncBootstrapRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/** Semilla transaccional para catálogos creados antes de que existiera la outbox maestra. */
@Singleton
class RoomCatalogSyncBootstrapRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
) : CatalogSyncBootstrapRepository {
    override suspend fun ensurePendingSnapshots(businessId: BusinessId): Int =
        withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    if (database.businessDao().findById(businessId.value) == null) {
                        return@withTransaction 0
                    }
                    var inserted = 0
                    database.productDao().listForBusiness(businessId.value).forEach { product ->
                        inserted += bootstrapProduct(product)
                    }
                    database.supplierDao().listForBusiness(businessId.value).forEach { supplier ->
                        inserted += bootstrapSupplier(supplier)
                    }
                    inserted
                }
            }
        }

    private suspend fun bootstrapProduct(product: ProductEntity): Int {
        val plan = plan(product.businessId, PRODUCT_ENTITY_TYPE, product.productId, product.version)
            ?: return 0
        // Todas las referencias se validan antes de tocar la versión; un catálogo legado
        // incompleto queda intacto y visible para diagnóstico.
        val inventoryUnit = database.unitDao().findById(product.unitId) ?: return 0
        val purchaseUnit = product.purchaseUnitId?.let {
            database.unitDao().findById(it) ?: return 0
        }
        val location = product.locationId?.let {
            database.inventoryLocationDao().findById(it) ?: return 0
        }
        val stored = if (plan.targetVersion == product.version) {
            product
        } else {
            if (
                database.productDao().rebaseVersion(
                    productId = product.productId,
                    businessId = product.businessId,
                    expectedLocalVersion = product.version,
                    rebasedVersion = plan.targetVersion,
                    updatedAt = product.updatedAt,
                ) != 1
            ) {
                return 0
            }
            requireNotNull(database.productDao().findById(product.productId))
        }
        database.outboxOperationDao().insert(
            CatalogSyncOutbox.product(
                entity = stored,
                expectedVersion = plan.expectedVersion,
                inventoryUnit = inventoryUnit,
                purchaseUnit = purchaseUnit,
                location = location,
                remoteEntityId = plan.remoteEntityId,
            ),
        )
        return 1
    }

    private suspend fun bootstrapSupplier(supplier: SupplierEntity): Int {
        val plan = plan(
            supplier.businessId,
            SUPPLIER_ENTITY_TYPE,
            supplier.supplierId,
            supplier.version,
        ) ?: return 0
        val stored = if (plan.targetVersion == supplier.version) {
            supplier
        } else {
            if (
                database.supplierDao().rebaseVersion(
                    supplierId = supplier.supplierId,
                    businessId = supplier.businessId,
                    expectedLocalVersion = supplier.version,
                    rebasedVersion = plan.targetVersion,
                    updatedAt = supplier.updatedAt,
                ) != 1
            ) {
                return 0
            }
            requireNotNull(database.supplierDao().findById(supplier.supplierId))
        }
        database.outboxOperationDao().insert(
            CatalogSyncOutbox.supplier(
                entity = stored,
                expectedVersion = plan.expectedVersion,
                remoteEntityId = plan.remoteEntityId,
            ),
        )
        return 1
    }

    private suspend fun plan(
        businessId: String,
        entityType: String,
        entityId: String,
        localVersion: Long,
    ): BootstrapPlan? {
        val outbox = database.outboxOperationDao()
        if (outbox.findForEntityVersion(businessId, entityType, entityId, localVersion) != null) {
            return null
        }
        if (outbox.hasOpenForEntity(businessId, entityType, entityId)) return null
        val link = database.catalogSyncLinkDao().findByLocal(businessId, entityType, entityId)
        val latest = outbox.findLatestForEntity(businessId, entityType, entityId)
        val acknowledgedVersion = when {
            link != null -> link.remoteVersion
            latest?.isAcknowledged() == true -> latest.entityVersion
            latest == null -> 0L
            else -> return null
        }
        if (localVersion <= acknowledgedVersion || acknowledgedVersion == Long.MAX_VALUE) {
            return null
        }
        return BootstrapPlan(
            expectedVersion = acknowledgedVersion,
            targetVersion = acknowledgedVersion + 1L,
            remoteEntityId = link?.remoteEntityId,
        )
    }
}

private data class BootstrapPlan(
    val expectedVersion: Long,
    val targetVersion: Long,
    val remoteEntityId: String?,
)

private fun OutboxOperationEntity.isAcknowledged(): Boolean =
    status == OutboxOperationStatus.COMPLETED.name && completedAt != null
