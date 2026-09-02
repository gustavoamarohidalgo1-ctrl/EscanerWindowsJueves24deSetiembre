package com.facturastock.app.domain.model

/** Consulta acotada de catálogo. `status = null` incluye activos y archivados. */
data class CatalogSearch(
    val query: String = "",
    val status: CatalogStatus? = null,
    val offset: Int = 0,
    val limit: Int = DEFAULT_CATALOG_PAGE_SIZE,
) {
    init {
        require(offset >= 0) { "offset no puede ser negativo" }
        require(limit in 1..MAX_CATALOG_PAGE_SIZE) {
            "limit debe estar entre 1 y $MAX_CATALOG_PAGE_SIZE"
        }
    }
}

data class CatalogPage<out T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    val limit: Int,
) {
    init {
        require(total >= 0)
        require(offset >= 0)
        require(limit > 0)
        require(items.size <= limit)
    }

    val hasMore: Boolean
        get() = offset + items.size < total
}

enum class CatalogDuplicateField {
    RUC,
    SKU,
    BARCODE,
    CODE,
    NAME,
}

enum class CatalogInvalidField {
    NAME,
    OWNERSHIP,
    UNIT,
    PURCHASE_UNIT,
    LOCATION,
}

sealed interface CatalogMutationResult<out T> {
    data class Saved<T>(val value: T) : CatalogMutationResult<T>
    data class Duplicate(val field: CatalogDuplicateField) : CatalogMutationResult<Nothing>
    data class Invalid(val field: CatalogInvalidField) : CatalogMutationResult<Nothing>
    data object NotFound : CatalogMutationResult<Nothing>

    /**
     * El registro cambió desde que el llamador lo leyó (CAS de versión perdido): nada se
     * escribió y la edición debe rehacerse sobre la versión vigente.
     */
    data object Stale : CatalogMutationResult<Nothing>
}

const val DEFAULT_CATALOG_PAGE_SIZE: Int = 50
const val MAX_CATALOG_PAGE_SIZE: Int = 200
