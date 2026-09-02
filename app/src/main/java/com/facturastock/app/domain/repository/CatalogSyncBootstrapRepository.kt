package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.id.BusinessId

/** Reconstruye de forma idempotente la outbox maestra de catálogos locales legados. */
interface CatalogSyncBootstrapRepository {
    /** Devuelve la cantidad de snapshots nuevos creados dentro de una única transacción Room. */
    suspend fun ensurePendingSnapshots(businessId: BusinessId): Int
}

data object DisabledCatalogSyncBootstrapRepository : CatalogSyncBootstrapRepository {
    override suspend fun ensurePendingSnapshots(businessId: BusinessId): Int = 0
}
