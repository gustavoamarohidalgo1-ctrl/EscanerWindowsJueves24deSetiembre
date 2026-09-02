package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.ReconciliationReport
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.reconcileRemoteLedger
import com.facturastock.app.domain.repository.AuditEventWrite
import com.facturastock.app.domain.repository.AuditPayloadKey
import com.facturastock.app.domain.repository.AuditTrailRepository
import com.facturastock.app.domain.repository.CatalogOutboxConflictResolution
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.RemoteCatalogApplicationRepository
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import com.facturastock.app.domain.repository.RemotePurchaseCacheCompletion
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import com.facturastock.app.domain.repository.SharedInventoryApplicationRepository
import com.facturastock.app.domain.repository.SyncCursorRepository
import com.facturastock.app.domain.repository.SyncReconciliationRepository
import com.facturastock.app.domain.repository.enqueueBestEffort
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resultado del pull incremental: cuántos cambios llegaron y hasta qué seq quedó el cursor. */
data class SyncPullOutcome(
    val pulledCount: Int,
    val latestSeq: Long,
    val catalogPulledCount: Int = 0,
    val catalogLatestSeq: Long = 0,
    val catalogAppliedCount: Int = 0,
    val catalogConflictCount: Int = 0,
    val inventoryPulledCount: Int = 0,
    val inventoryLatestSeq: Long = 0,
    val remoteSalesAppliedCount: Int = 0,
)

/**
 * Pull incremental: drena páginas del libro remoto desde el cursor guardado y solo entonces
 * lo avanza. Es lectura réplica: jamás escribe en el libro local.
 */
class PullRemoteChangesUseCase(
    private val remoteLedger: RemoteLedgerRepository,
    private val cursors: SyncCursorRepository,
    private val cache: RemoteSyncCacheRepository,
    private val clock: AppClock,
    private val remoteCatalog: RemoteCatalogRepository? = null,
    private val catalogApplications: RemoteCatalogApplicationRepository? = null,
    private val cloudBusinessBindings: CloudBusinessBindingRepository? = null,
    private val remoteSales: RemoteSaleSyncRepository? = null,
    private val inventoryApplications: SharedInventoryApplicationRepository? = null,
) {
    suspend operator fun invoke(businessId: BusinessId): DomainResult<SyncPullOutcome> {
        if (!remoteLedger.available) return DomainResult.Failure(AccountError.Unavailable)
        var since = cursors.lastPulledSeq(businessId)
        when (val begun = cache.beginPurchasePull(businessId, since)) {
            is DomainResult.Failure -> return begun
            is DomainResult.Success -> Unit
        }
        var pulled = 0
        var pages = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val page = remoteLedger.pullChanges(businessId, since, PAGE_LIMIT)) {
                is DomainResult.Failure -> return page
                is DomainResult.Success -> {
                    currentCoroutineContext().ensureActive()
                    val value = page.value
                    if (!value.isValidAfter(since)) {
                        return DomainResult.Failure(AccountError.Unexpected)
                    }
                    when (
                        val persisted = cache.persistPurchasePage(
                            businessId = businessId,
                            expectedPreviousSeq = since,
                            page = value,
                            pulledAt = clock.now(),
                        )
                    ) {
                        is DomainResult.Failure -> return persisted
                        is DomainResult.Success -> Unit
                    }
                    pulled += value.changes.size
                    since = value.nextCursor
                    pages++
                    if (!value.hasMore) break
                    if (pages >= MAX_PAGES) {
                        return DomainResult.Failure(AccountError.Unexpected)
                    }
                }
            }
        }
        return DomainResult.Success(SyncPullOutcome(pulled, since))
    }

    /** Push/pull completo del enlace: transporte y cache usan cloud ID; aplicación usa local ID. */
    suspend operator fun invoke(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ): DomainResult<SyncPullOutcome> {
        if (
            cloudBusinessBindings != null &&
            !cloudBusinessBindings.matches(localBusinessId, cloudBusinessId)
        ) {
            return DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
        }
        // Catálogo primero: los hechos de inventario referencian productos cloud y ubicaciones
        // semánticas que deben estar resueltos antes de tocar saldos o ventas locales.
        var catalogPulled = 0
        var catalogLatest = 0L
        var catalogApplied = 0
        var catalogConflicts = 0
        val catalogRemote = remoteCatalog
        val applications = catalogApplications
        if (catalogRemote != null && applications != null) {
            if (!catalogRemote.available) return DomainResult.Failure(AccountError.Unavailable)
            var since = cache.lastCatalogPulledSeq(cloudBusinessId)
            var pages = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                when (
                    val result = catalogRemote.pullCatalogChanges(
                        cloudBusinessId,
                        since,
                        PAGE_LIMIT,
                    )
                ) {
                    is DomainResult.Failure -> return result
                    is DomainResult.Success -> {
                        val page = result.value
                        if (!page.isValidAfter(since)) {
                            return DomainResult.Failure(AccountError.Unexpected)
                        }
                        when (
                            val persisted = cache.persistCatalogPage(
                                businessId = cloudBusinessId,
                                expectedPreviousSeq = since,
                                page = page,
                                pulledAt = clock.now(),
                            )
                        ) {
                            is DomainResult.Failure -> return persisted
                            is DomainResult.Success -> Unit
                        }
                        catalogPulled += page.changes.size
                        since = page.nextCursor
                        pages++
                        if (!page.hasMore) break
                        if (pages >= MAX_PAGES) {
                            return DomainResult.Failure(AccountError.Unexpected)
                        }
                    }
                }
            }
            catalogLatest = since
            when (
                val result = applications.applyPending(
                    localBusinessId = localBusinessId,
                    cloudBusinessId = cloudBusinessId,
                    appliedAt = clock.now(),
                )
            ) {
                is DomainResult.Failure -> return result
                is DomainResult.Success -> {
                    catalogApplied = result.value.applied
                    catalogConflicts = result.value.conflicts
                }
            }
        }

        var inventoryPulled = 0
        var inventoryLatest = 0L
        var salesApplied = 0
        val inventoryRemote = remoteSales
        val inventoryLocal = inventoryApplications
        if (inventoryRemote != null && inventoryLocal != null) {
            if (!inventoryRemote.available) return DomainResult.Failure(AccountError.Unavailable)
            var since = inventoryLocal.lastAppliedSeq(cloudBusinessId)
            var pages = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                when (
                    val result = inventoryRemote.pullInventoryChanges(
                        cloudBusinessId = cloudBusinessId,
                        sinceSeq = since,
                        limit = PAGE_LIMIT,
                    )
                ) {
                    is DomainResult.Failure -> return result
                    is DomainResult.Success -> {
                        val page = result.value
                        if (!page.isValidAfter(since)) {
                            return DomainResult.Failure(AccountError.Unexpected)
                        }
                        when (
                            val applied = inventoryLocal.applyPage(
                                localBusinessId = localBusinessId,
                                cloudBusinessId = cloudBusinessId,
                                expectedPreviousSeq = since,
                                page = page,
                                appliedAt = clock.now(),
                            )
                        ) {
                            is DomainResult.Failure -> return applied
                            is DomainResult.Success -> salesApplied += applied.value
                        }
                        inventoryPulled += page.changes.size
                        since = page.nextCursor
                        pages++
                        if (!page.hasMore) break
                        if (pages >= MAX_PAGES) {
                            return DomainResult.Failure(AccountError.Unexpected)
                        }
                    }
                }
            }
            inventoryLatest = since
        }

        val purchase = when (val result = invoke(cloudBusinessId)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
        return DomainResult.Success(
            purchase.copy(
                catalogPulledCount = catalogPulled,
                catalogLatestSeq = catalogLatest,
                catalogAppliedCount = catalogApplied,
                catalogConflictCount = catalogConflicts,
                inventoryPulledCount = inventoryPulled,
                inventoryLatestSeq = inventoryLatest,
                remoteSalesAppliedCount = salesApplied,
            ),
        )
    }

    private companion object {
        const val PAGE_LIMIT = 200
        const val MAX_PAGES = 500
    }
}

private fun CatalogSyncPullPage.isValidAfter(since: Long): Boolean {
    if (changes.isEmpty()) return !hasMore && nextCursor == since
    if (changes.first().seq <= since || changes.last().seq != nextCursor) return false
    return changes.zipWithNext().all { (left, right) -> right.seq > left.seq }
}

private fun com.facturastock.app.domain.model.SharedInventoryPullPage.isValidAfter(
    since: Long,
): Boolean {
    if (changes.isEmpty()) return !hasMore && nextCursor == since
    if (changes.first().seq - 1L != since || changes.last().seq != nextCursor) return false
    return changes.zipWithNext().all { (left, right) -> right.seq == left.seq + 1L }
}

/**
 * Reconciliación diagnóstica sobre el espejo durable. Solo lee datos ligados a un recibo de página
 * terminal y verifica que cubran exactamente `1..completeThroughSeq`. Un pull fallido, cancelado,
 * limitado o legacy carece de ese recibo y falla cerrado antes de cargar el libro local. No mueve
 * el cursor ni escribe nada: cualquier acción posterior usa los flujos auditados existentes.
 */
class ReconcileRemoteLedgerUseCase(
    private val cache: RemoteSyncCacheRepository,
    private val reconciliation: SyncReconciliationRepository,
    private val clock: AppClock,
    private val cloudBusinessBindings: CloudBusinessBindingRepository? = null,
) {
    suspend operator fun invoke(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ): DomainResult<ReconciliationReport> {
        if (
            cloudBusinessBindings != null &&
            !cloudBusinessBindings.matches(localBusinessId, cloudBusinessId)
        ) {
            return DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
        }
        currentCoroutineContext().ensureActive()
        val completion = cache.purchaseCacheCompletion(cloudBusinessId)
            ?: return DomainResult.Failure(AccountError.Unexpected)
        currentCoroutineContext().ensureActive()
        val changes = cache.listPurchaseChanges(cloudBusinessId)
        if (!completion.exactlyCovers(changes)) {
            return DomainResult.Failure(AccountError.Unexpected)
        }
        currentCoroutineContext().ensureActive()
        val snapshot = reconciliation.loadLocalLedgerSnapshot(localBusinessId)
        return DomainResult.Success(
            reconcileRemoteLedger(localBusinessId, changes, snapshot, clock.now()),
        )
    }
}

/** El servidor nunca puede adelantar el cursor más allá de los cambios entregados. */
private fun com.facturastock.app.domain.model.SyncPullPage.isValidAfter(since: Long): Boolean {
    if (changes.isEmpty()) return !hasMore && nextCursor == since
    if (changes.first().seq - 1L != since || changes.last().seq != nextCursor) return false
    return changes.zipWithNext().all { (left, right) -> right.seq == left.seq + 1L }
}

private fun RemotePurchaseCacheCompletion.exactlyCovers(
    changes: List<RemotePurchaseChange>,
): Boolean {
    if (completeThroughSeq == 0L) return changes.isEmpty()
    if (
        changes.isEmpty() ||
        changes.first().seq != 1L ||
        changes.last().seq != completeThroughSeq ||
        changes.size.toLong() != completeThroughSeq
    ) {
        return false
    }
    return changes.zipWithNext().all { (left, right) -> right.seq == left.seq + 1L }
}

/** Opciones permitidas ante un CONFLICT; nunca hay resolución automática. */
enum class SyncConflictResolution {
    /** El documento ya está en la nube (otro dispositivo): se audita y no se reintenta. */
    KEEP_REMOTE,

    /** Reencola la misma operación (solo tiene sentido tras corregir la causa). */
    RETRY,
}

sealed interface ResolveSyncConflictResult {
    data object Resolved : ResolveSyncConflictResult
    data object Requeued : ResolveSyncConflictResult
    data object NotInConflict : ResolveSyncConflictResult
    data object NotFound : ResolveSyncConflictResult
}

/**
 * Resolución explícita de un CONFLICT de respaldo. KEEP_REMOTE pasa la operación a RESOLVED
 * con auditoría de la decisión en la misma transacción; RETRY usa el reintento existente.
 * La compra local nunca se borra ni se modifica aquí.
 */
class ResolveSyncConflictUseCase(
    private val outbox: PurchaseBackupOutboxRepository,
    private val retryBackup: RetryPurchaseBackupUseCase,
    private val clock: AppClock,
    private val catalogConflicts: RemoteCatalogApplicationRepository? = null,
    private val scheduler: PurchaseBackupScheduler = DisabledPurchaseBackupScheduler,
) {
    suspend operator fun invoke(
        activeBusinessId: BusinessId,
        operation: OutboxOperationView,
        resolution: SyncConflictResolution,
        actorId: String,
    ): ResolveSyncConflictResult {
        if (operation.businessId != activeBusinessId) {
            return ResolveSyncConflictResult.NotFound
        }
        if (operation.status != OutboxOperationStatus.CONFLICT) {
            return ResolveSyncConflictResult.NotInConflict
        }
        if (operation.entityType != PURCHASE_ENTITY_TYPE) {
            return resolveCatalog(activeBusinessId, operation, resolution, actorId)
        }
        return when (resolution) {
            SyncConflictResolution.KEEP_REMOTE -> {
                val purchaseId = operation.purchaseId
                    ?: return ResolveSyncConflictResult.NotFound
                val resolved = outbox.resolveConflictKeepRemote(
                    activeBusinessId = activeBusinessId,
                    operationId = operation.operationId,
                    purchaseId = purchaseId,
                    remotePurchaseId = operation.conflictRemotePurchaseId,
                    remoteReceiptId = operation.conflictReceiptId,
                    actorId = actorId,
                    resolvedAt = clock.now(),
                )
                if (resolved) {
                    ResolveSyncConflictResult.Resolved
                } else {
                    ResolveSyncConflictResult.NotInConflict
                }
            }
            SyncConflictResolution.RETRY -> {
                val purchaseId = operation.purchaseId
                    ?: return ResolveSyncConflictResult.NotFound
                when (retryBackup.forBusiness(activeBusinessId, purchaseId)) {
                    RetryActivePurchaseBackupResult.Requeued -> ResolveSyncConflictResult.Requeued
                    RetryActivePurchaseBackupResult.AlreadyPending,
                    RetryActivePurchaseBackupResult.AlreadySynced,
                    -> ResolveSyncConflictResult.NotInConflict
                    RetryActivePurchaseBackupResult.NotFound,
                    RetryActivePurchaseBackupResult.NoActiveBusiness,
                    -> ResolveSyncConflictResult.NotFound
                }
            }
        }
    }

    private suspend fun resolveCatalog(
        activeBusinessId: BusinessId,
        operation: OutboxOperationView,
        resolution: SyncConflictResolution,
        actorId: String,
    ): ResolveSyncConflictResult {
        val repository = catalogConflicts ?: return ResolveSyncConflictResult.NotFound
        val catalogResolution = when (resolution) {
            SyncConflictResolution.KEEP_REMOTE -> CatalogOutboxConflictResolution.APPLY_REMOTE
            SyncConflictResolution.RETRY -> CatalogOutboxConflictResolution.KEEP_LOCAL
        }
        return when (
            val result = repository.resolveOutboxConflict(
                activeBusinessId = activeBusinessId,
                operation = operation,
                resolution = catalogResolution,
                actorId = actorId,
                resolvedAt = clock.now(),
            )
        ) {
            is DomainResult.Failure -> if (result.error == AccountError.Conflict) {
                ResolveSyncConflictResult.NotInConflict
            } else {
                ResolveSyncConflictResult.NotFound
            }
            is DomainResult.Success -> if (result.value) {
                scheduler.enqueueBestEffort()
                if (resolution == SyncConflictResolution.RETRY) {
                    ResolveSyncConflictResult.Requeued
                } else {
                    ResolveSyncConflictResult.Resolved
                }
            } else {
                ResolveSyncConflictResult.NotInConflict
            }
        }
    }
}

private const val PURCHASE_ENTITY_TYPE = "PURCHASE"

/**
 * Registra en la bitácora que una persona revisó la reconciliación (conteos, no contenido).
 * La revisión NO ajusta saldos: cualquier corrección usa su flujo con su propia auditoría.
 */
class RecordReconciliationReviewUseCase(
    private val auditTrail: AuditTrailRepository,
    private val uuids: UuidGenerator,
    private val clock: AppClock,
) {
    suspend operator fun invoke(report: ReconciliationReport) {
        val localBusinessId = report.businessId
        auditTrail.record(
            AuditEventWrite(
                auditEventId = uuids.newUuid().toString(),
                businessId = localBusinessId,
                purchaseId = null,
                eventType = AuditEventType.SYNC_RECONCILED,
                entityType = "business",
                entityId = localBusinessId.value,
                payload = mapOf(
                    AuditPayloadKey.VERSION to "1",
                    AuditPayloadKey.LATEST_SEQ to report.latestSeq.toString(),
                    AuditPayloadKey.MATCHED_COUNT to report.matched.size.toString(),
                    AuditPayloadKey.AMBIGUOUS_COUNT to report.ambiguous.size.toString(),
                    AuditPayloadKey.REMOTE_ONLY_COUNT to report.remoteOnly.size.toString(),
                    AuditPayloadKey.BALANCE_DIFFERENCES_COUNT to
                        report.balanceDifferences.size.toString(),
                    AuditPayloadKey.UNLINKED_PRODUCTS_COUNT to
                        report.unlinkedRemoteProducts.size.toString(),
                    AuditPayloadKey.AMBIGUOUS_PRODUCTS_COUNT to
                        report.ambiguousRemoteProducts.size.toString(),
                ),
                occurredAt = clock.now(),
            ),
        )
    }
}
