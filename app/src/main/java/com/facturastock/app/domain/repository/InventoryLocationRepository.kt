package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Puerto del catálogo de ubicaciones de inventario. Las implementaciones estampan las marcas
 * de tiempo (`createdAt`/`updatedAt` en create, `updatedAt` en update) y traducen los fallos
 * de disco a `StorageException`.
 */
interface InventoryLocationRepository {
    /** Inserta la ubicación estampando `createdAt`/`updatedAt`; devuelve el registro persistido. */
    suspend fun create(location: InventoryLocation): InventoryLocation

    /** Actualiza la ubicación estampando `updatedAt`; devuelve false si no existía. */
    suspend fun update(location: InventoryLocation): Boolean

    suspend fun findById(locationId: LocationId): InventoryLocation?

    /** Búsqueda puntual por nombre exacto dentro del negocio. */
    suspend fun findByName(businessId: BusinessId, name: String): InventoryLocation?

    suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<InventoryLocation>

    fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<InventoryLocation>> = flow { emit(search(businessId, search)) }

    /** Emite las ubicaciones del negocio ordenadas por nombre ante cada cambio. */
    fun observeForBusiness(businessId: BusinessId): Flow<List<InventoryLocation>>

    suspend fun archive(locationId: LocationId): Boolean

    suspend fun restore(locationId: LocationId): Boolean
}
