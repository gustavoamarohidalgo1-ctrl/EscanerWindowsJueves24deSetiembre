package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.MAX_CATALOG_PAGE_SIZE
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

enum class ProductDeletionResult {
    DELETED,
    NOT_FOUND,
    STALE,
    HAS_HISTORY,
    HAS_STOCK,
    SHARED_BUSINESS,
}

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

data class ProductBatchCreationResult(
    val created: List<Product>,
    /** Candidatos omitidos porque el negocio ya tenía el mismo nombre normalizado. */
    val alreadyExistingCount: Int,
) {
    init {
        require(alreadyExistingCount >= 0)
    }
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
     * Crea únicamente los nombres todavía ausentes. El adaptador Room sobrescribe esta operación
     * para hacer la comprobación, las inserciones y su outbox dentro de una sola transacción.
     * Así dos escaneos simultáneos no duplican un mismo nombre. Los adaptadores simples conservan
     * una implementación compatible para pruebas.
     */
    suspend fun createBatchSkippingExistingNames(
        businessId: BusinessId,
        products: List<Product>,
    ): ProductBatchCreationResult {
        require(products.all { product -> product.businessId == businessId })
        val distinctNames = products.map { product ->
            product.name.trim().lowercase(Locale.ROOT)
        }
        require(distinctNames.distinct().size == distinctNames.size) {
            "Un lote condicionado no puede repetir nombres normalizados"
        }
        val pending = products.filterIndexed { index, _ ->
            findByNormalizedName(businessId, distinctNames[index]).isEmpty()
        }
        return ProductBatchCreationResult(
            created = createBatch(pending),
            alreadyExistingCount = products.size - pending.size,
        )
    }

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

    /**
     * Elimina definitivamente sólo un producto local sin referencias ni existencias. La
     * identidad, versión, saldos y sincronización se comprueban dentro de una transacción;
     * nunca elimina movimientos, documentos ni referencias para forzar el borrado.
     */
    suspend fun deletePermanently(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
    ): ProductDeletionResult

    /**
     * Lectura acotada por identificadores. El default conserva adaptadores simples; Room
     * sobrescribe con una consulta `IN` para no hacer N lecturas por alias.
     */
    suspend fun findByIds(productIds: Collection<ProductId>): Map<ProductId, Product> {
        if (productIds.isEmpty()) return emptyMap()
        return productIds.distinct().mapNotNull { findById(it) }.associateBy { it.productId }
    }

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

    /**
     * Lee una instantánea completa del catálogo persistido del negocio, incluidos archivados y
     * productos sin existencias; no aplica paginación ni filtros de disponibilidad. Producción
     * consulta directamente el almacenamiento sin esperar las emisiones de una proyección de UI.
     * El default conserva la compatibilidad de adaptadores simples y dobles de prueba.
     */
    suspend fun listForBusiness(businessId: BusinessId): List<Product> =
        observeForBusiness(businessId).first()

    /**
     * Subconjunto de [listForBusiness] con código de barras o SKU, incluidos archivados y
     * agotados. Un producto sin ninguno de los dos nunca coincide con una lectura del escáner,
     * así que cualquier evaluación de identidad sobre este subconjunto equivale a la completa.
     */
    suspend fun listScannerIdentityCandidates(businessId: BusinessId): List<Product> =
        listForBusiness(businessId).filter { it.barcode != null || it.sku != null }

    /** Subconjunto de [listForBusiness] cuyo código guardado mide entre ambos límites. */
    suspend fun listByBarcodeLength(
        businessId: BusinessId,
        minLength: Int,
        maxLength: Int,
    ): List<Product> =
        listForBusiness(businessId).filter { product ->
            product.barcode?.length?.let { it in minLength..maxLength } == true
        }

    /** Archivo lógico sobre la versión vigente; nunca elimina saldos ni historia. */
    suspend fun archive(productId: ProductId): Boolean

    suspend fun restore(productId: ProductId): Boolean

    /**
     * Archivo desde una revisión o diálogo: negocio y versión se comprueban en la misma
     * transacción que cambia el estado. False significa que la identidad o versión ya no
     * coincide; no se cambia el producto ni se publica outbox. El estado ya solicitado solo
     * devuelve true cuando también coincide la versión esperada.
     */
    suspend fun archive(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
    ): Boolean

    /** Reactiva la misma identidad y conserva sus existencias e historia mediante el mismo CAS. */
    suspend fun restore(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
    ): Boolean
}
