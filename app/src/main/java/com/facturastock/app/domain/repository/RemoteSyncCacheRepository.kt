package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.RemoteCatalogApplication
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Recibo durable de que un pull alcanzó una página terminal para este cursor. */
data class RemotePurchaseCacheCompletion(
    val completeThroughSeq: Long,
    val completedAt: Instant,
) {
    init {
        require(completeThroughSeq in 0L..MAX_SAFE_SYNC_SEQUENCE)
    }
}

/**
 * Espejo durable del pull. Cada página y su cursor se confirman en una única transacción Room;
 * si la escritura falla, el cursor no avanza y la misma página puede repetirse sin daño.
 */
interface RemoteSyncCacheRepository {
    suspend fun lastCatalogPulledSeq(businessId: BusinessId): Long

    /**
     * Invalida el recibo completo antes de pedir la primera página. Conserva el cursor para poder
     * reanudar, pero un fallo, cancelación o límite posterior ya no puede promover el prefijo
     * durable a diagnóstico.
     */
    suspend fun beginPurchasePull(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
    ): DomainResult<Unit>

    suspend fun persistPurchasePage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: SyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit>

    suspend fun persistCatalogPage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: CatalogSyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit>

    /** `null` significa nunca completada, parcial, fallida, legacy o estado inconsistente. */
    suspend fun purchaseCacheCompletion(
        businessId: BusinessId,
    ): RemotePurchaseCacheCompletion?

    suspend fun listPurchaseChanges(businessId: BusinessId): List<RemotePurchaseChange>

    fun observePurchaseDescriptions(
        businessId: BusinessId,
    ): Flow<Map<String, RemotePurchaseDescription>>

    fun observeCatalogApplications(
        businessId: BusinessId,
    ): Flow<List<RemoteCatalogApplication>>
}
