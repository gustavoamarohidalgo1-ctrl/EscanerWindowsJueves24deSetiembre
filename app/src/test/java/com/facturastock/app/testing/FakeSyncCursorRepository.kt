package com.facturastock.app.testing

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.RemoteCatalogApplication
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RemotePurchaseCacheCompletion
import com.facturastock.app.domain.repository.SyncCursorRepository
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake local del cursor de pull: un [MutableStateFlow] por negocio, igual que la fila Room que
 * sustituye. Registra los guardados efectivos y admite un fallo programado de un solo uso;
 * [seedCursor] siembra el estado previo de una prueba sin contar como guardado.
 */
class FakeSyncCursorRepository : SyncCursorRepository, RemoteSyncCacheRepository {
    override suspend fun lastCatalogPulledSeq(businessId: BusinessId): Long =
        catalogCursors[businessId] ?: 0L

    /** Guardado efectivo del cursor, tal como llegó. */
    data class SaveCall(
        val businessId: BusinessId,
        val seq: Long,
        val pulledAt: Instant,
    )

    data class CatalogSaveCall(
        val businessId: BusinessId,
        val expectedPreviousSeq: Long,
        val page: CatalogSyncPullPage,
        val pulledAt: Instant,
    )

    private val cursorFlows = mutableMapOf<BusinessId, MutableStateFlow<SyncCursor?>>()
    private val purchaseChanges = mutableMapOf<BusinessId, MutableList<RemotePurchaseChange>>()
    private val descriptionFlows =
        mutableMapOf<BusinessId, MutableStateFlow<Map<String, RemotePurchaseDescription>>>()
    private val catalogFlows =
        mutableMapOf<BusinessId, MutableStateFlow<List<RemoteCatalogApplication>>>()
    private val catalogCursors = mutableMapOf<BusinessId, Long>()
    private val purchaseCompletions = mutableMapOf<BusinessId, RemotePurchaseCacheCompletion>()

    /** Fallo que devolverá el próximo [saveCursor]; se consume una vez. */
    var nextSaveFailure: FacturaStockError? = null
    var nextBeginFailure: FacturaStockError? = null

    /** Guardados efectivos, en orden de llegada. Los fallos programados no se registran. */
    val saveCalls = mutableListOf<SaveCall>()
    val catalogSaveCalls = mutableListOf<CatalogSaveCall>()
    val beginCalls = mutableListOf<Pair<BusinessId, Long>>()

    override suspend fun lastPulledSeq(businessId: BusinessId): Long =
        cursorFlows[businessId]?.value?.seq ?: 0L

    override suspend fun saveCursor(
        businessId: BusinessId,
        seq: Long,
        pulledAt: Instant,
    ): DomainResult<Unit> {
        val failure = nextSaveFailure
        if (failure != null) {
            nextSaveFailure = null
            return DomainResult.Failure(failure)
        }
        saveCalls += SaveCall(businessId, seq, pulledAt)
        flowFor(businessId).value = SyncCursor(seq, pulledAt)
        purchaseCompletions.remove(businessId)
        return DomainResult.Success(Unit)
    }

    override fun observeCursor(businessId: BusinessId): Flow<SyncCursor?> = flowFor(businessId)

    override suspend fun beginPurchasePull(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
    ): DomainResult<Unit> {
        if (lastPulledSeq(businessId) != expectedPreviousSeq) {
            return DomainResult.Failure(com.facturastock.app.domain.error.AccountError.Unexpected)
        }
        val failure = nextBeginFailure
        if (failure != null) {
            nextBeginFailure = null
            return DomainResult.Failure(failure)
        }
        beginCalls += businessId to expectedPreviousSeq
        purchaseCompletions.remove(businessId)
        return DomainResult.Success(Unit)
    }

    override suspend fun persistPurchasePage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: SyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit> {
        if (lastPulledSeq(businessId) != expectedPreviousSeq) {
            return DomainResult.Failure(com.facturastock.app.domain.error.AccountError.Unexpected)
        }
        val failure = nextSaveFailure
        if (failure != null) {
            nextSaveFailure = null
            return DomainResult.Failure(failure)
        }
        val updated = purchaseChanges.getOrPut(businessId) { mutableListOf() }.toMutableList()
        page.changes.forEach { change ->
            val existing = updated.singleOrNull { it.seq == change.seq }
            if (existing != null && existing != change) {
                return DomainResult.Failure(
                    com.facturastock.app.domain.error.AccountError.Unexpected,
                )
            }
            if (existing == null) updated += change
        }
        purchaseChanges[businessId] = updated
        saveCalls += SaveCall(businessId, page.nextCursor, pulledAt)
        flowFor(businessId).value = SyncCursor(page.nextCursor, pulledAt)
        if (page.hasMore) {
            purchaseCompletions.remove(businessId)
        } else {
            purchaseCompletions[businessId] = RemotePurchaseCacheCompletion(
                completeThroughSeq = page.nextCursor,
                completedAt = pulledAt,
            )
        }
        descriptionFlowFor(businessId).value = updated
            .groupBy(RemotePurchaseChange::purchaseId)
            .mapValues { (_, changes) -> changes.maxBy(RemotePurchaseChange::seq) }
            .mapValues { (_, change) -> change.toDescription() }
        return DomainResult.Success(Unit)
    }

    override suspend fun purchaseCacheCompletion(
        businessId: BusinessId,
    ): RemotePurchaseCacheCompletion? = purchaseCompletions[businessId]

    override suspend fun persistCatalogPage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: CatalogSyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit> {
        if (lastCatalogPulledSeq(businessId) != expectedPreviousSeq) {
            return DomainResult.Failure(com.facturastock.app.domain.error.AccountError.Unexpected)
        }
        val failure = nextSaveFailure
        if (failure != null) {
            nextSaveFailure = null
            return DomainResult.Failure(failure)
        }
        catalogSaveCalls += CatalogSaveCall(businessId, expectedPreviousSeq, page, pulledAt)
        catalogCursors[businessId] = page.nextCursor
        return DomainResult.Success(Unit)
    }

    override suspend fun listPurchaseChanges(businessId: BusinessId): List<RemotePurchaseChange> =
        purchaseChanges[businessId].orEmpty().sortedBy(RemotePurchaseChange::seq)

    override fun observePurchaseDescriptions(
        businessId: BusinessId,
    ): Flow<Map<String, RemotePurchaseDescription>> = descriptionFlowFor(businessId)

    override fun observeCatalogApplications(
        businessId: BusinessId,
    ): Flow<List<RemoteCatalogApplication>> =
        catalogFlows.getOrPut(businessId) { MutableStateFlow(emptyList()) }

    /** Cursor vigente del negocio, para aserciones; `null` si nunca se guardó ni sembró. */
    fun cursor(businessId: BusinessId): SyncCursor? = cursorFlows[businessId]?.value

    /** Siembra un cursor sin registrar un guardado (estado previo a la prueba). */
    fun seedCursor(businessId: BusinessId, seq: Long, pulledAt: Instant) {
        flowFor(businessId).value = SyncCursor(seq, pulledAt)
        purchaseCompletions.remove(businessId)
    }

    fun seedPurchaseChanges(businessId: BusinessId, changes: List<RemotePurchaseChange>) {
        purchaseChanges[businessId] = changes.toMutableList()
        descriptionFlowFor(businessId).value = changes
            .groupBy(RemotePurchaseChange::purchaseId)
            .mapValues { (_, versions) -> versions.maxBy(RemotePurchaseChange::seq) }
            .mapValues { (_, change) -> change.toDescription() }
    }

    fun seedPurchaseCompletion(
        businessId: BusinessId,
        completeThroughSeq: Long,
        completedAt: Instant,
    ) {
        purchaseCompletions[businessId] = RemotePurchaseCacheCompletion(
            completeThroughSeq = completeThroughSeq,
            completedAt = completedAt,
        )
    }

    fun seedCatalogCursor(businessId: BusinessId, seq: Long) {
        catalogCursors[businessId] = seq
    }

    private fun flowFor(businessId: BusinessId): MutableStateFlow<SyncCursor?> =
        cursorFlows.getOrPut(businessId) { MutableStateFlow(null) }

    private fun descriptionFlowFor(
        businessId: BusinessId,
    ): MutableStateFlow<Map<String, RemotePurchaseDescription>> =
        descriptionFlows.getOrPut(businessId) { MutableStateFlow(emptyMap()) }
}

private fun RemotePurchaseChange.toDescription() = RemotePurchaseDescription(
    purchaseId = purchaseId,
    status = status,
    documentType = documentType,
    documentSeries = documentSeries,
    documentNumber = documentNumber,
    issueDate = issueDate,
    currency = currency,
    supplierRuc = supplierRuc,
    supplierLegalName = supplierLegalName,
    totalMinorUnits = totalMinorUnits,
    receiptId = receiptId,
    syncedAtMillis = syncedAtMillis,
    syncedBy = null,
)
