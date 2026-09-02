package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.UnitId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Puerto del catálogo de unidades de medida. Las implementaciones estampan las marcas de
 * tiempo (`createdAt`/`updatedAt` en create, `updatedAt` en update) y traducen los fallos de
 * disco a `StorageException`.
 */
interface UnitRepository {
    /** Inserta la unidad estampando `createdAt`/`updatedAt`; devuelve el registro persistido. */
    suspend fun create(unit: UnitOfMeasure): UnitOfMeasure

    /** Actualiza la unidad estampando `updatedAt`; devuelve false si no existía. */
    suspend fun update(unit: UnitOfMeasure): Boolean

    suspend fun findById(unitId: UnitId): UnitOfMeasure?

    /** Búsqueda puntual por código (normalizado a mayúsculas) dentro del negocio. */
    suspend fun findByCode(businessId: BusinessId, code: String): UnitOfMeasure?

    suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<UnitOfMeasure>

    fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<UnitOfMeasure>> = flow { emit(search(businessId, search)) }

    /** Emite las unidades del negocio ordenadas por código ante cada cambio. */
    fun observeForBusiness(businessId: BusinessId): Flow<List<UnitOfMeasure>>

    suspend fun archive(unitId: UnitId): Boolean

    suspend fun restore(unitId: UnitId): Boolean
}
