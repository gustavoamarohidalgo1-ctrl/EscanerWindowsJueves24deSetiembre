package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.UnitId

/** Identidades reservadas antes de cruzar de DataStore al grafo relacional de onboarding. */
data class OnboardingProvisionIds(
    val businessId: BusinessId,
    val warehouseId: LocationId,
    val defaultUnitId: UnitId,
)

/** Resultado atómico de reservar identidades en la configuración de la instalación. */
sealed interface OnboardingReservation {
    data class Pending(val ids: OnboardingProvisionIds) : OnboardingReservation

    data class AlreadyCompleted(val businessId: BusinessId) : OnboardingReservation
}

/** Grafo completo que Room debe crear o reconocer como el mismo aprovisionamiento. */
data class OnboardingProvision(
    val business: Business,
    val warehouse: InventoryLocation,
    val defaultUnit: UnitOfMeasure,
) {
    init {
        require(warehouse.businessId == business.businessId) {
            "El almacén inicial debe pertenecer al negocio reservado"
        }
        require(defaultUnit.businessId == business.businessId) {
            "La unidad inicial debe pertenecer al negocio reservado"
        }
    }

    val ids: OnboardingProvisionIds = OnboardingProvisionIds(
        businessId = business.businessId,
        warehouseId = warehouse.locationId,
        defaultUnitId = defaultUnit.unitId,
    )
}

/**
 * Unidad de trabajo relacional del onboarding. Una implementación debe crear negocio, almacén y
 * unidad en una sola transacción y reconocer reintentos de las mismas identidades sin duplicar.
 */
interface OnboardingProvisioningRepository {
    suspend fun provision(provision: OnboardingProvision): Business

    /** Lectura usada cuando DataStore demuestra que otra invocación ya finalizó el onboarding. */
    suspend fun findBusiness(businessId: BusinessId): Business?
}
