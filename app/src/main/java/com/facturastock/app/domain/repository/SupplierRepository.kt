package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.MAX_CATALOG_PAGE_SIZE
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SupplierId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Puerto del catálogo de proveedores. Las implementaciones estampan las marcas de tiempo
 * (`createdAt`/`updatedAt` en create, `updatedAt` en update) y traducen los fallos de disco a
 * `StorageException`.
 */
interface SupplierRepository {
    /** Inserta el proveedor estampando `createdAt`/`updatedAt`; devuelve el registro persistido. */
    suspend fun create(supplier: Supplier): Supplier

    /**
     * Actualiza el proveedor estampando `updatedAt`; devuelve false si no existía o si la
     * `version` del proveedor ya no es la persistida (concurrencia optimista: otra edición
     * ganó la carrera y nada se escribió).
     */
    suspend fun update(supplier: Supplier): Boolean

    suspend fun findById(supplierId: SupplierId): Supplier?

    /** Búsqueda puntual por RUC dentro del negocio. */
    suspend fun findByRuc(businessId: BusinessId, ruc: String): Supplier?

    /**
     * Busca por texto libre (insensible a mayúsculas) sobre razón social, nombre comercial y
     * RUC, dentro del negocio.
     */
    suspend fun search(businessId: BusinessId, search: CatalogSearch): CatalogPage<Supplier>

    fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<Supplier>> = flow { emit(search(businessId, search)) }

    /** Compatibilidad acotada para resolución puntual; nunca carga el catálogo completo. */
    suspend fun search(businessId: BusinessId, query: String): List<Supplier> =
        search(businessId, CatalogSearch(query = query, limit = MAX_CATALOG_PAGE_SIZE)).items

    /** Emite los proveedores del negocio ordenados por razón social ante cada cambio. */
    fun observeForBusiness(businessId: BusinessId): Flow<List<Supplier>>

    suspend fun archive(supplierId: SupplierId): Boolean

    suspend fun restore(supplierId: SupplierId): Boolean
}
