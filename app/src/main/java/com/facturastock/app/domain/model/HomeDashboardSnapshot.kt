package com.facturastock.app.domain.model

import java.time.Instant
import java.time.ZoneId

data class HomeDraftOverview(
    /** Total exacto no terminal; [recent] sigue acotado para que Inicio no cargue todo el flujo. */
    val openCount: Int,
    val recent: List<RecentDraft>,
) {
    init {
        require(openCount >= recent.size)
    }
}

data class HomeInventoryOverview(
    /** Productos activos, incluidos los que todavía no tienen ningún saldo. */
    val productCount: Int,
    /** Productos con al menos una ubicación activa de existencia estrictamente positiva. */
    val availableProductCount: Int,
    /** Unión de: sin existencia positiva, sin precio de venta o con alguna ubicación negativa. */
    val attentionProductCount: Int,
    /** Productos sin ninguna ubicación de existencia positiva; no suma unidades heterogéneas. */
    val withoutStockCount: Int,
    val withoutSalePriceCount: Int,
    val negativeStockCount: Int,
) {
    init {
        require(
            listOf(
                productCount,
                availableProductCount,
                attentionProductCount,
                withoutStockCount,
                withoutSalePriceCount,
                negativeStockCount,
            ).all { it >= 0 },
        )
        require(availableProductCount <= productCount)
        require(attentionProductCount <= productCount)
        require(withoutStockCount <= productCount)
        require(withoutSalePriceCount <= productCount)
        require(negativeStockCount <= productCount)
        require(availableProductCount + withoutStockCount == productCount)
        require(withoutSalePriceCount <= attentionProductCount)
        require(negativeStockCount <= attentionProductCount)
    }
}

data class HomePurchaseOverview(
    /** Compras actualmente POSTED; una compra VOIDED deja de formar parte de este total. */
    val postedCount: Int,
    /** Compras terminales cuya última operación de respaldo está fallida o en conflicto. */
    val syncProblemCount: Int,
) {
    init {
        require(postedCount >= 0)
        require(syncProblemCount >= 0)
    }
}

data class HomeDashboardRead(
    val drafts: HomeDraftOverview,
    val inventory: HomeInventoryOverview,
    val purchases: HomePurchaseOverview,
)

/**
 * Instantánea coherente del centro de operaciones. Todas las listas pertenecen al mismo
 * negocio activo capturado por el caso de uso; nunca mezcla datos de dos tenants durante un
 * cambio entre negocio real y demostración.
 */
data class HomeDashboardSnapshot(
    val business: Business?,
    val isDemoMode: Boolean,
    val zoneId: ZoneId,
    val overview: HomeDashboardRead,
    val observedAt: Instant,
)
