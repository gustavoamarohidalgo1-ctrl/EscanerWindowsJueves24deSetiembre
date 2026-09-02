package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.flow.Flow

/**
 * Puerto del catálogo de alias proveedor-producto. Las implementaciones estampan las marcas de
 * tiempo (`createdAt`/`updatedAt` en create, `updatedAt` en update) y traducen los fallos de
 * disco a `StorageException`.
 */
interface SupplierProductAliasRepository {
    /** Inserta el alias estampando `createdAt`/`updatedAt`; devuelve el registro persistido. */
    suspend fun create(alias: SupplierProductAlias): SupplierProductAlias

    /** Actualiza el alias estampando `updatedAt`; devuelve false si no existía. */
    suspend fun update(alias: SupplierProductAlias): Boolean

    suspend fun findById(aliasId: AliasId): SupplierProductAlias?

    /**
     * Búsqueda puntual por alias dentro del negocio; la implementación normaliza el texto
     * (sin espacios extremos, en minúsculas) antes de comparar.
     */
    suspend fun findByNormalizedAlias(
        businessId: BusinessId,
        alias: String,
    ): List<SupplierProductAlias>

    /** Emite los alias del producto ordenados por texto de alias ante cada cambio. */
    fun observeForProduct(productId: ProductId): Flow<List<SupplierProductAlias>>

    /** Lectura puntual ordenada para el detalle de producto. */
    suspend fun listForProduct(productId: ProductId): List<SupplierProductAlias>

    /** Elimina por ID; devuelve false si no existía. */
    suspend fun deleteById(aliasId: AliasId): Boolean
}
