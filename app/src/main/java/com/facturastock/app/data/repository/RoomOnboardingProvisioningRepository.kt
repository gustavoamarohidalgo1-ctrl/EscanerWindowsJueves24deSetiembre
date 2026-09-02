package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.OnboardingProvision
import com.facturastock.app.domain.repository.OnboardingProvisioningRepository
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Aprovisiona el agregado inicial bajo un único writer Room. Las identidades reservadas hacen que
 * el camino normal sea idempotente; las búsquedas semánticas reparan el grafo parcial que pudiera
 * haber dejado la implementación secuencial anterior a esta unidad de trabajo.
 */
class RoomOnboardingProvisioningRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
) : OnboardingProvisioningRepository {

    override suspend fun provision(provision: OnboardingProvision): Business =
        withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    val business = resolveBusiness(provision)
                    ensureWarehouse(provision.warehouse, business)
                    ensureDefaultUnit(provision.defaultUnit, business)
                    business.toDomain()
                }
            }
        }

    override suspend fun findBusiness(businessId: BusinessId): Business? =
        withContext(dispatchers.io) {
            storageCatching { database.businessDao().findById(businessId.value)?.toDomain() }
        }

    private suspend fun resolveBusiness(provision: OnboardingProvision): BusinessEntity {
        val requested = provision.business.toEntity()
        database.businessDao().findById(requested.businessId)?.let { reserved ->
            if (
                reserved.legalName != requested.legalName ||
                reserved.ruc != requested.ruc ||
                reserved.tradeName != requested.tradeName
            ) {
                conflict("El ID del negocio reservado no corresponde al aprovisionamiento")
            }
            requireActive(reserved.status, "El negocio reservado no está activo")
            return reserved
        }

        requested.ruc?.let { ruc ->
            database.businessDao().findByRuc(ruc)?.let { legacy ->
                requireActive(legacy.status, "El negocio legacy del RUC no está activo")
                return legacy
            }
        }

        if (requested.ruc == null) {
            val legacy = database.businessDao().findWithoutRucByLegalName(requested.legalName)
            if (legacy.size > 1) {
                conflict("Hay más de un negocio legacy sin RUC con la misma razón social")
            }
            legacy.singleOrNull()?.let { existing ->
                requireActive(existing.status, "El negocio legacy sin RUC no está activo")
                return existing
            }
        }

        database.businessDao().insert(requested)
        return requested
    }

    private suspend fun ensureWarehouse(
        requested: InventoryLocation,
        business: BusinessEntity,
    ): InventoryLocationEntity {
        val dao = database.inventoryLocationDao()
        dao.findById(requested.locationId.value)?.let { reserved ->
            if (
                reserved.businessId != business.businessId ||
                reserved.name != requested.name
            ) {
                conflict("El ID del almacén reservado no corresponde al aprovisionamiento")
            }
            requireActive(reserved.status, "El almacén reservado no está activo")
            return reserved
        }
        val locationKey = canonicalLocationName(requested.name)
        val legacyMatches = dao.listForBusiness(business.businessId)
            .filter { canonicalLocationName(it.name) == locationKey }
        if (legacyMatches.size > 1) {
            conflict("Hay más de un almacén inicial legacy con el mismo nombre normalizado")
        }
        legacyMatches.singleOrNull()?.let { legacy ->
            requireActive(legacy.status, "El almacén inicial legacy no está activo")
            return legacy
        }
        val rebound = requested.copy(
            businessId = requireNotNull(BusinessId.parse(business.businessId)),
        ).toEntity()
        dao.insert(rebound)
        return rebound
    }

    private suspend fun ensureDefaultUnit(
        requested: UnitOfMeasure,
        business: BusinessEntity,
    ): UnitEntity {
        val dao = database.unitDao()
        dao.findById(requested.unitId.value)?.let { reserved ->
            if (
                reserved.businessId != business.businessId ||
                reserved.code != requested.code ||
                reserved.name != requested.name ||
                reserved.symbol != requested.symbol
            ) {
                conflict("El ID de la unidad reservada no corresponde al aprovisionamiento")
            }
            requireActive(reserved.status, "La unidad reservada no está activa")
            return reserved
        }
        dao.findByCode(business.businessId, requested.code)?.let { legacy ->
            requireActive(legacy.status, "La unidad inicial legacy no está activa")
            return legacy
        }
        val rebound = requested.copy(
            businessId = requireNotNull(BusinessId.parse(business.businessId)),
        ).toEntity()
        dao.insert(rebound)
        return rebound
    }

    private fun requireActive(status: String, message: String) {
        if (status != CatalogStatus.ACTIVE.name) conflict(message)
    }

    private fun conflict(message: String): Nothing = throw StorageException(
        StorageError.ConstraintConflict(message),
    )
}
