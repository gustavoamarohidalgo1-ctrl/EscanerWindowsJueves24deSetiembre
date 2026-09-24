package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.CatalogSyncLinkDao
import com.facturastock.app.data.local.dao.CloudBusinessBindingDao
import com.facturastock.app.data.local.dao.OutboxOperationDao
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.withContext

/** Implementación serializada: crear binding y fijar las filas pre-link es una transacción. */
class RoomCloudBusinessBindingRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val bindings: CloudBusinessBindingDao,
    private val outbox: OutboxOperationDao,
    private val catalogLinks: CatalogSyncLinkDao,
    private val dispatchers: DispatcherProvider,
) : CloudBusinessBindingRepository {

    override suspend fun bindOnce(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        boundAt: Instant,
    ): CloudBusinessBindingResult = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val existing = bindings.findByLocal(localBusinessId.value)
                if (existing != null) {
                    if (existing.cloudBusinessId != cloudBusinessId.value) {
                        return@withTransaction CloudBusinessBindingResult.LocalBusinessAlreadyBound
                    }
                    return@withTransaction if (hasAmbiguousLocations(localBusinessId)) {
                        CloudBusinessBindingResult.AmbiguousInventoryLocations
                    } else {
                        CloudBusinessBindingResult.AlreadyBound
                    }
                }
                val cloudOwner = bindings.findByCloud(cloudBusinessId.value)
                if (cloudOwner != null) {
                    return@withTransaction CloudBusinessBindingResult.CloudBusinessAlreadyBound
                }

                // Un CatalogSyncLink v19 ya fija identidad de tenant aunque la tabla global aún
                // no existiera. Nunca se adopta un enlace de sesión que lo contradiga.
                val catalogTargets = catalogLinks.listCloudBusinessIds(localBusinessId.value)
                if (catalogTargets.size > 1) {
                    return@withTransaction CloudBusinessBindingResult.LegacyDestinationUnknown
                }
                if (catalogTargets.singleOrNull()?.let { it != cloudBusinessId.value } == true) {
                    return@withTransaction CloudBusinessBindingResult.LocalBusinessAlreadyBound
                }

                // El nombre canónico es la identidad de un almacén entre dispositivos. Bases
                // antiguas podían contener variantes que el índice exacto de SQLite no detecta;
                // no se enlazan hasta resolver esa ambigüedad para evitar bloquear el feed.
                if (hasAmbiguousLocations(localBusinessId)) {
                    return@withTransaction CloudBusinessBindingResult.AmbiguousInventoryLocations
                }

                // attemptCount>0 o un estado distinto de PENDING significa que el wire pudo
                // haber salido (incluido ACK perdido). La única excepción es un upload
                // documental RESOLVED con cero intentos: el opt-out lo suprimió localmente antes
                // del primer binding y esa fila nunca se bindea ni se revive.
                if (outbox.hasUnsafeUnpinnedLegacy(localBusinessId.value)) {
                    return@withTransaction CloudBusinessBindingResult.LegacyDestinationUnknown
                }
                val eligible = outbox.countNeverAttemptedUnpinned(localBusinessId.value)
                bindings.insert(
                    CloudBusinessBindingEntity(
                        localBusinessId = localBusinessId.value,
                        cloudBusinessId = cloudBusinessId.value,
                        createdAt = boundAt.toEpochMilli(),
                        boundLegacyOperationCount = eligible,
                    ),
                )
                val bound = outbox.bindNeverAttemptedUnpinned(
                    businessId = localBusinessId.value,
                    targetCloudBusinessId = cloudBusinessId.value,
                    boundAt = boundAt.toEpochMilli(),
                )
                check(bound == eligible) { "La fijación del tenant no cubrió toda la outbox" }
                CloudBusinessBindingResult.Bound
            }
            }
        }

    private suspend fun hasAmbiguousLocations(localBusinessId: BusinessId): Boolean {
        val keys = database.inventoryLocationDao().listForBusiness(localBusinessId.value)
            .map { canonicalLocationName(it.name) }
        return keys.distinct().size != keys.size
    }

    override suspend fun targetFor(localBusinessId: BusinessId): BusinessId? =
        withContext(dispatchers.io) {
            storageCatching {
                bindings.findByLocal(localBusinessId.value)?.cloudBusinessId?.let { raw ->
                    BusinessId.parse(raw) ?: error("Binding cloud local inconsistente")
                }
            }
        }

    override suspend fun matches(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            bindings.findByLocal(localBusinessId.value)?.cloudBusinessId == cloudBusinessId.value &&
                !hasAmbiguousLocations(localBusinessId)
        }
    }
}
