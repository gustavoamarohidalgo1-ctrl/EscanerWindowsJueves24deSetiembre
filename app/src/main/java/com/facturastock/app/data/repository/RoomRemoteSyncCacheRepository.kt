package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.RemoteSyncDao
import com.facturastock.app.data.local.entity.RemoteCatalogChangeEntity
import com.facturastock.app.data.local.entity.RemoteMovementSummaryEntity
import com.facturastock.app.data.local.entity.RemotePurchaseChangeEntity
import com.facturastock.app.data.local.entity.RemoteSyncStateEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.RemoteCatalogApplication
import com.facturastock.app.domain.model.RemoteCatalogApplicationStatus
import com.facturastock.app.domain.model.RemoteCatalogChange
import com.facturastock.app.domain.model.RemoteCatalogEntityType
import com.facturastock.app.domain.model.RemoteMovementSummary
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RemotePurchaseCacheCompletion
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import com.facturastock.app.domain.repository.SyncCursorRepository
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Room es la única fuente que observa la UI para cursores, comparaciones y conflictos remotos. */
@Singleton
class RoomRemoteSyncCacheRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dao: RemoteSyncDao,
    private val dispatchers: DispatcherProvider,
) : RemoteSyncCacheRepository, SyncCursorRepository {

    override suspend fun lastCatalogPulledSeq(businessId: BusinessId): Long =
        withContext(dispatchers.io) {
            storageCatching { dao.findState(businessId.value)?.catalogSeq ?: 0L }
        }

    override suspend fun beginPurchasePull(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
    ): DomainResult<Unit> = persistResult {
        database.withTransaction {
            val state = dao.findState(businessId.value) ?: RemoteSyncStateEntity(businessId.value)
            check(state.purchaseSeq == expectedPreviousSeq) { "Cursor purchase cambió" }
            dao.upsertState(
                state.copy(
                    purchasePulledAt = PurchaseCacheCompletionReceiptCodec.invalidate(
                        state.purchasePulledAt,
                    ),
                ),
            )
        }
    }

    override suspend fun persistPurchasePage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: SyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit> = persistResult {
        checkPurchasePage(expectedPreviousSeq, page)
        database.withTransaction {
            val state = dao.findState(businessId.value) ?: RemoteSyncStateEntity(businessId.value)
            check(state.purchaseSeq == expectedPreviousSeq) { "Cursor purchase cambió" }
            persistPurchaseChanges(businessId, page.changes)
            dao.upsertState(
                state.copy(
                    purchaseSeq = page.nextCursor,
                    purchasePulledAt = if (page.hasMore) {
                        pulledAt.toEpochMilli()
                    } else {
                        PurchaseCacheCompletionReceiptCodec.complete(pulledAt)
                    },
                ),
            )
        }
    }

    override suspend fun persistCatalogPage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: CatalogSyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit> = persistResult {
        checkCatalogPage(expectedPreviousSeq, page)
        database.withTransaction {
            val state = dao.findState(businessId.value) ?: RemoteSyncStateEntity(businessId.value)
            check(state.catalogSeq == expectedPreviousSeq) { "Cursor catálogo cambió" }
            persistCatalogChanges(businessId, page.changes, pulledAt)
            dao.upsertState(
                state.copy(
                    catalogSeq = page.nextCursor,
                    catalogPulledAt = pulledAt.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun purchaseCacheCompletion(
        businessId: BusinessId,
    ): RemotePurchaseCacheCompletion? = withContext(dispatchers.io) {
        storageCatching {
            val state = dao.findState(businessId.value) ?: return@storageCatching null
            val completedAt = PurchaseCacheCompletionReceiptCodec.completionInstant(
                state.purchasePulledAt,
            ) ?: return@storageCatching null
            RemotePurchaseCacheCompletion(
                completeThroughSeq = state.purchaseSeq,
                completedAt = completedAt,
            )
        }
    }

    override suspend fun listPurchaseChanges(
        businessId: BusinessId,
    ): List<RemotePurchaseChange> = withContext(dispatchers.io) {
        storageCatching {
            val rows = database.withTransaction {
                PurchaseChangeRows(
                    headers = dao.listPurchaseChanges(businessId.value),
                    movements = dao.listMovementSummariesForBusiness(businessId.value),
                )
            }
            remotePurchaseChangesFromRows(rows.headers, rows.movements)
        }
    }

    override fun observePurchaseDescriptions(
        businessId: BusinessId,
    ): Flow<Map<String, RemotePurchaseDescription>> =
        dao.observePurchaseChanges(businessId.value)
            .map { headers ->
                // Las descripciones dependen exclusivamente de cabeceras. Room notifica después
                // del commit de la página, por lo que observar además todos los movimientos solo
                // duplicaba memoria y recomputaba el mapa ante datos que no cambian su resultado.
                // La consulta está ordenada por seq ascendente: sobrescribir deja la última
                // versión sin crear una lista por purchase ni dos mapas intermedios.
                buildMap {
                    headers.forEach { header ->
                        put(header.purchaseId, header.toDescription())
                    }
                }
            }.flowOn(dispatchers.io)

    override fun observeCatalogApplications(
        businessId: BusinessId,
    ): Flow<List<RemoteCatalogApplication>> = dao.observeCatalogChanges(businessId.value)
        .map { changes -> changes.map(RemoteCatalogChangeEntity::toDomainApplication) }
        .flowOn(dispatchers.io)

    override suspend fun lastPulledSeq(businessId: BusinessId): Long =
        withContext(dispatchers.io) {
            storageCatching { dao.findState(businessId.value)?.purchaseSeq ?: 0L }
        }

    /**
     * Compatibilidad del puerto: nunca permite saltar por encima de una página no almacenada.
     * El pull normal usa [persistPurchasePage], que escribe datos y cursor atómicamente.
     */
    override suspend fun saveCursor(
        businessId: BusinessId,
        seq: Long,
        pulledAt: Instant,
    ): DomainResult<Unit> = persistResult {
        database.withTransaction {
            val state = dao.findState(businessId.value) ?: RemoteSyncStateEntity(businessId.value)
            check(seq == state.purchaseSeq) { "Cursor sin página durable" }
            // Este puerto no recibió una página terminal y por eso nunca puede acuñar un recibo.
            dao.upsertState(state.copy(purchasePulledAt = pulledAt.toEpochMilli()))
        }
    }

    override fun observeCursor(businessId: BusinessId): Flow<SyncCursor?> =
        dao.observeState(businessId.value).map { state ->
            state?.let { value ->
                PurchaseCacheCompletionReceiptCodec.displayInstant(value.purchasePulledAt)
                    ?.let { pulledAt -> SyncCursor(value.purchaseSeq, pulledAt) }
            }
        }.flowOn(dispatchers.io)

    /**
     * Room reutiliza un único statement para la página (máximo 200 cambios) y conserva el
     * cursor fuera del lote hasta que cabeceras, movimientos y verificaciones terminan. Un
     * ENOSPC o una secuencia reutilizada revierte la transacción completa como antes.
     */
    private suspend fun persistPurchaseChanges(
        businessId: BusinessId,
        changes: List<RemotePurchaseChange>,
    ) {
        if (changes.isEmpty()) return
        val rows = changes.map { change ->
            PersistedPurchaseChange(
                header = change.toEntity(businessId),
                movements = change.movementSummary.mapIndexed { position, movement ->
                    movement.toEntity(businessId, change.seq, position)
                },
            )
        }
        val inserted = dao.insertPurchaseChanges(rows.map(PersistedPurchaseChange::header))
        check(inserted.size == rows.size) { "Resultado de inserción purchase incompleto" }
        val newMovements = buildList {
            rows.forEachIndexed { index, row ->
                if (inserted[index] != -1L) addAll(row.movements)
            }
        }
        if (newMovements.isNotEmpty()) dao.insertMovementSummaries(newMovements)
        rows.forEachIndexed { index, row ->
            if (inserted[index] != -1L) return@forEachIndexed
            check(dao.findPurchaseChange(businessId.value, row.header.seq) == row.header) {
                "Secuencia purchase remota reutilizada"
            }
            check(
                dao.listMovementSummaries(businessId.value, row.header.seq) == row.movements,
            ) {
                "Resumen purchase remoto cambió"
            }
        }
    }

    private suspend fun persistCatalogChanges(
        businessId: BusinessId,
        changes: List<RemoteCatalogChange>,
        pulledAt: Instant,
    ) {
        if (changes.isEmpty()) return
        val incoming = changes.map { change -> change.toEntity(businessId, pulledAt) }
        val inserted = dao.insertCatalogChanges(incoming)
        check(inserted.size == incoming.size) { "Resultado de inserción catálogo incompleto" }
        incoming.forEachIndexed { index, row ->
            if (inserted[index] != -1L) return@forEachIndexed
            val stored = requireNotNull(dao.findCatalogChange(businessId.value, row.seq))
            check(stored.sameRemoteFactAs(row)) { "Secuencia catálogo remota reutilizada" }
        }
    }

    private suspend fun persistResult(block: suspend () -> Unit): DomainResult<Unit> =
        withContext(dispatchers.io) {
            try {
                storageCatching { block() }
                DomainResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (storage: StorageException) {
                DomainResult.Failure(storage.error)
            } catch (_: Exception) {
                DomainResult.Failure(AccountError.Unexpected)
            }
        }
}

private data class PersistedPurchaseChange(
    val header: RemotePurchaseChangeEntity,
    val movements: List<RemoteMovementSummaryEntity>,
)

private data class PurchaseChangeRows(
    val headers: List<RemotePurchaseChangeEntity>,
    val movements: List<RemoteMovementSummaryEntity>,
)

private fun RemotePurchaseChange.toEntity(businessId: BusinessId) = RemotePurchaseChangeEntity(
    cloudBusinessId = businessId.value,
    seq = seq,
    purchaseId = purchaseId,
    status = status.name,
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
)

private fun RemoteMovementSummary.toEntity(
    businessId: BusinessId,
    seq: Long,
    position: Int,
) = RemoteMovementSummaryEntity(
    cloudBusinessId = businessId.value,
    seq = seq,
    position = position,
    productId = productId,
    productName = productName,
    type = type.name,
    quantityDelta = quantityDelta.toPlainString(),
)

private fun RemotePurchaseChangeEntity.toDomain(
    movements: List<RemoteMovementSummary>,
) = RemotePurchaseChange(
    seq = seq,
    purchaseId = purchaseId,
    status = com.facturastock.app.domain.model.PurchaseStatus.valueOf(status),
    documentType = documentType,
    documentSeries = documentSeries,
    documentNumber = documentNumber,
    issueDate = issueDate,
    currency = currency,
    supplierRuc = supplierRuc,
    supplierLegalName = supplierLegalName,
    totalMinorUnits = totalMinorUnits,
    movementSummary = movements,
    receiptId = receiptId,
    syncedAtMillis = syncedAtMillis,
    syncedBy = null,
)

/**
 * Ensambla en O(cabeceras + movimientos); las listas ya llegan ordenadas desde Room. El merge
 * lineal crea únicamente las listas de dominio finales, sin `Pair`, `groupBy` ni listas que
 * retengan por segunda vez todas las entidades de movimiento.
 */
internal fun remotePurchaseChangesFromRows(
    headers: List<RemotePurchaseChangeEntity>,
    movements: List<RemoteMovementSummaryEntity>,
): List<RemotePurchaseChange> {
    if (headers.isEmpty()) return emptyList()
    var movementIndex = 0
    return headers.map { header ->
        val matching = mutableListOf<RemoteMovementSummary>()
        while (movementIndex < movements.size) {
            val movement = movements[movementIndex]
            if (movement.cloudBusinessId != header.cloudBusinessId || movement.seq < header.seq) {
                // La consulta de producción filtra un solo tenant. Esta defensa mantiene fuera
                // cualquier fila ajena u huérfana si una base manipulada llega al ensamblador.
                movementIndex++
                continue
            }
            if (movement.seq > header.seq) break
            matching += RemoteMovementSummary(
                productId = movement.productId,
                productName = movement.productName,
                type = StockMovementType.valueOf(movement.type),
                quantityDelta = BigDecimal(movement.quantityDelta),
            )
            movementIndex++
        }
        header.toDomain(matching)
    }
}

private fun RemotePurchaseChangeEntity.toDescription() = RemotePurchaseDescription(
    purchaseId = purchaseId,
    status = com.facturastock.app.domain.model.PurchaseStatus.valueOf(status),
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

private fun RemoteCatalogChange.toEntity(
    businessId: BusinessId,
    pulledAt: Instant,
) = RemoteCatalogChangeEntity(
    cloudBusinessId = businessId.value,
    seq = seq,
    entityType = entityType.name,
    remoteEntityId = remoteEntityId,
    remoteVersion = remoteVersion,
    mutation = mutation,
    snapshotPayload = snapshotPayload,
    snapshotSha256 = snapshotSha256,
    receiptId = receiptId,
    syncedAtMillis = syncedAt?.toEpochMilli(),
    receivedAt = pulledAt.toEpochMilli(),
)

private fun RemoteCatalogChangeEntity.toDomainApplication() = RemoteCatalogApplication(
    change = RemoteCatalogChange(
        seq = seq,
        entityType = RemoteCatalogEntityType.valueOf(entityType),
        remoteEntityId = remoteEntityId,
        remoteVersion = remoteVersion,
        mutation = mutation,
        snapshotPayload = snapshotPayload,
        snapshotSha256 = snapshotSha256,
        receiptId = receiptId,
        syncedAt = syncedAtMillis?.let(Instant::ofEpochMilli),
    ),
    origin = origin,
    receivedAt = Instant.ofEpochMilli(receivedAt),
    status = RemoteCatalogApplicationStatus.valueOf(applicationStatus),
    localEntityId = localEntityId,
    localVersion = localVersion,
    localSnapshotPayload = localSnapshotPayload,
    conflictCode = conflictCode,
    conflictDetectedAt = conflictDetectedAt?.let(Instant::ofEpochMilli),
    resolution = resolution,
    resolvedAt = resolvedAt?.let(Instant::ofEpochMilli),
)

private fun RemoteCatalogChangeEntity.sameRemoteFactAs(other: RemoteCatalogChangeEntity): Boolean =
    cloudBusinessId == other.cloudBusinessId && seq == other.seq &&
        entityType == other.entityType && remoteEntityId == other.remoteEntityId &&
        remoteVersion == other.remoteVersion && mutation == other.mutation &&
        snapshotPayload == other.snapshotPayload && snapshotSha256 == other.snapshotSha256 &&
        receiptId == other.receiptId && syncedAtMillis == other.syncedAtMillis &&
        origin == other.origin

private fun checkPurchasePage(previousSeq: Long, page: SyncPullPage) {
    check(page.changes.size <= MAX_REMOTE_PAGE_SIZE)
    check(page.nextCursor >= previousSeq)
    if (page.changes.isEmpty()) {
        check(!page.hasMore && page.nextCursor == previousSeq)
        return
    }
    check(page.changes.first().seq - 1L == previousSeq)
    check(page.changes.last().seq == page.nextCursor)
    check(page.changes.zipWithNext().all { (left, right) -> right.seq == left.seq + 1L })
}

private fun checkCatalogPage(previousSeq: Long, page: CatalogSyncPullPage) {
    check(page.changes.size <= MAX_REMOTE_PAGE_SIZE)
    check(page.nextCursor >= previousSeq)
    if (page.changes.isEmpty()) {
        check(!page.hasMore && page.nextCursor == previousSeq)
        return
    }
    check(page.changes.first().seq > previousSeq)
    check(page.changes.last().seq == page.nextCursor)
    check(page.changes.zipWithNext().all { (left, right) -> right.seq > left.seq })
    check(
        page.changes.all { change ->
            change.snapshotSha256 == sha256(change.snapshotPayload)
        },
    )
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val MAX_REMOTE_PAGE_SIZE = 200
