package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.MAX_CATALOG_PAGE_SIZE
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface ProductSalePriceMutationResult {
    data class Updated(val product: Product) : ProductSalePriceMutationResult
    data class Unchanged(val product: Product) : ProductSalePriceMutationResult
    data object NoActiveBusiness : ProductSalePriceMutationResult
    data object NotFound : ProductSalePriceMutationResult
    data object Inactive : ProductSalePriceMutationResult
    data object Stale : ProductSalePriceMutationResult
    data object InvalidPrice : ProductSalePriceMutationResult
    data class CurrencyMismatch(
        val expected: CurrencyCode,
        val actual: CurrencyCode,
    ) : ProductSalePriceMutationResult
}

/**
 * Puerto del catálogo de productos. Las implementaciones estampan las marcas de tiempo
 * (`createdAt`/`updatedAt` en create, `updatedAt` en update) y traducen los fallos de disco a
 * `StorageException`.
 */
interface ProductRepository {
    /** Inserta el producto estampando `createdAt`/`updatedAt`; devuelve el registro persistido. */
    suspend fun create(product: Product): Product

    /**
     * Inserta un lote conservando el orden de entrada. Este default mantiene compatibles los
     * adaptadores simples; las implementaciones de producción deben sobrescribirlo para que el
     * catálogo y sus operaciones de sincronización se publiquen atómicamente.
     */
    suspend fun createBatch(products: List<Product>): List<Product> = products.map { create(it) }

    /**
     * Actualiza el producto estampando `updatedAt`; devuelve false si no existía o si la
     * `version` del producto ya no es la persistida (concurrencia optimista: otra edición
     * ganó la carrera y nada se escribió).
     */
    suspend fun update(product: Product): Boolean

    /** CAS focal: nunca reemplaza un precio leído desde una versión obsoleta. */
    suspend fun updateSalePrice(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
        salePrice: Money,
    ): ProductSalePriceMutationResult

    suspend fun findById(productId: ProductId): Product?

    /** Búsqueda puntual por SKU dentro del negocio. */
    suspend fun findBySku(businessId: BusinessId, sku: String): Product?

    /** Búsqueda puntual por código de barras dentro del negocio. */
    suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product?

    /**
     * Búsqueda puntual por nombre normalizado (`trim` + minúsculas) dentro del negocio. Las
     * implementaciones normalizan [normalizedName] antes de comparar. Devuelve lista porque el
     * nombre no es único: varios resultados representan una coincidencia exacta ambigua. El
     * orden es estable por nombre e id para que una ambigüedad sea reproducible.
     */
    suspend fun findByNormalizedName(businessId: BusinessId, normalizedName: String): List<Product>

    /**
     * Preselección acotada exclusivamente por nombre de productos activos. No consulta SKU,
     * código de barras ni alias, para que una búsqueda manual por nombre no cambie de semántica.
     */
    suspend fun searchActiveByName(
        businessId: BusinessId,
        query: String,
        limit: Int = MAX_CATALOG_PAGE_SIZE,
    ): List<Product>

    /** Busca nombre, SKU o código de barras, siempre paginado y acotado. */
    suspend fun search(businessId: BusinessId, search: CatalogSearch): CatalogPage<Product>

    /** Página reactiva y acotada; producción invalida desde Room ante cualquier cambio. */
    fun observeSearch(
        businessId: BusinessId,
        search: CatalogSearch,
    ): Flow<CatalogPage<Product>> = flow { emit(search(businessId, search)) }

    /** Compatibilidad acotada para vinculación; nunca carga más de 200 productos. */
    suspend fun search(businessId: BusinessId, query: String): List<Product> =
        search(businessId, CatalogSearch(query = query, limit = MAX_CATALOG_PAGE_SIZE)).items

    /** Emite los productos del negocio ordenados por nombre ante cada cambio. */
    fun observeForBusiness(businessId: BusinessId): Flow<List<Product>>

    suspend fun archive(productId: ProductId): Boolean

    suspend fun restore(productId: ProductId): Boolean
}
