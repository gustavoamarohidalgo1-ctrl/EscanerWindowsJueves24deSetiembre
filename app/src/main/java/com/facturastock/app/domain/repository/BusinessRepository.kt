package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.id.BusinessId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Puerto del catálogo de negocios. Las implementaciones estampan las marcas de tiempo
 * (`createdAt`/`updatedAt` en create, `updatedAt` en update) y traducen los fallos de disco a
 * `StorageException`.
 */
interface BusinessRepository {
    /** Inserta el negocio estampando `createdAt`/`updatedAt`; devuelve el registro persistido. */
    suspend fun create(business: Business): Business

    /** Actualiza el negocio estampando `updatedAt`; devuelve false si no existía. */
    suspend fun update(business: Business): Boolean

    suspend fun findById(businessId: BusinessId): Business?

    fun observeById(businessId: BusinessId): Flow<Business?> = flow { emit(findById(businessId)) }

    /** Búsqueda puntual por RUC, único globalmente. */
    suspend fun findByRuc(ruc: String): Business?

    /** Emite los negocios ordenados por razón social ante cada cambio. */
    fun observeBusinesses(): Flow<List<Business>>

    /** Elimina por ID; devuelve false si no existía. Los datos del negocio caen en cascada. */
    suspend fun deleteById(businessId: BusinessId): Boolean
}
