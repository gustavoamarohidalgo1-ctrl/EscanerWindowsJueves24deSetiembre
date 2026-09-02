package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Operación pendiente tal como la observa el procesador: sobre más intentos ya consumidos. */
data class PendingBackupOperation(
    val envelope: BackupEnvelope,
    val attemptCount: Int,
    /** Solo se usa localmente para agrupar antiguedad; nunca se emite como timestamp. */
    val createdAt: Instant? = null,
) {
    init {
        require(attemptCount >= 0) { "attemptCount no puede ser negativo: $attemptCount" }
        require(createdAt == null || !createdAt.isBefore(Instant.EPOCH)) {
            "createdAt no puede ser anterior al epoch"
        }
    }
}

/** Vista de la cola para la pantalla de sincronización (sin payload ni rutas). */
data class OutboxOperationView(
    val operationId: String,
    val operationType: String,
    val purchaseId: PurchaseId?,
    val status: OutboxOperationStatus,
    val attemptCount: Int,
    val lastError: String?,
    val nextAttemptAt: Instant?,
    val updatedAt: Instant,
    val conflictRemotePurchaseId: String?,
    val conflictReceiptId: String?,
    val conflictRemoteEntity: RemoteConflictMetadata? = null,
    val entityType: String = "PURCHASE",
    val entityId: String = operationId,
    val entityVersion: Long = 1L,
    val catalogConflictComparison: CatalogConflictComparison? = null,
    val businessId: BusinessId? = null,
    val targetCloudBusinessId: BusinessId? = null,
)

/** Comparación ya minimizada para UI; nunca expone el JSON técnico ni UUIDs de referencias. */
data class CatalogConflictComparison(
    val local: CatalogConflictSide?,
    val remote: CatalogConflictSide?,
)

data class CatalogConflictSide(
    val displayName: String,
    val semanticIdentifier: String?,
    val status: String,
    val version: Long,
    val changedAt: Instant?,
    val origin: String,
)

/**
 * Puerto de la cola durable tal como la necesita el procesador de respaldo. Toda mutación es un
 * CAS condicionado al estado y, una vez reclamada, al `claimToken` del intento: dos workers
 * concurrentes nunca completan ni fallan el mismo intento.
 */
interface PurchaseBackupOutboxRepository {
    /** Reencola claims PROCESSING sin lease o con lease vencido. Devuelve cuántos recuperó. */
    suspend fun recoverExpiredClaims(now: Instant): Int

    /**
     * Operaciones PENDING cuyo `nextAttemptAt` ya venció, en orden de dependencia (FIFO).
     * Una `SYNC_PURCHASE_VOID` solo es elegible cuando el `SYNC_PURCHASE` de la misma compra
     * ya está COMPLETED de forma durable; una dependencia bloqueada no consume el límite.
     */
    suspend fun listReady(
        now: Instant,
        limit: Int,
        onlyDocumentPurges: Boolean = false,
        targetCloudBusinessId: BusinessId? = null,
    ): List<PendingBackupOperation>

    /** Purga multi-negocio limitada a tenants que el UID actual acaba de autorizar. */
    suspend fun listReadyDocumentPurges(
        now: Instant,
        limit: Int,
        authorizedTargetCloudBusinessIds: Set<BusinessId>,
    ): List<PendingBackupOperation> {
        val ready = mutableListOf<PendingBackupOperation>()
        for (target in authorizedTargetCloudBusinessIds) {
            if (ready.size >= limit) break
            // Fallback para fakes/adapters: la implementación Room hace un único IN ordenado.
            ready += listReady(
                now,
                limit - ready.size,
                onlyDocumentPurges = true,
                targetCloudBusinessId = target,
            )
        }
        return ready
    }

    /**
     * Barrera durable previa al envío de una anulación. `true` exige un alta COMPLETED de la
     * misma compra y negocio; PROCESSING, PENDING, FAILED, CONFLICT o RESOLVED no habilitan el
     * efecto compensatorio remoto.
     */
    suspend fun isPurchasePostCompleted(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        targetCloudBusinessId: BusinessId,
    ): Boolean

    /**
     * Reclama la operación para este intento; incrementa `attemptCount` de forma durable.
     * `false` significa que otro worker ganó el CAS o la operación dejó de ser reclamable.
     */
    suspend fun claim(
        operationId: String,
        claimToken: String,
        claimedAt: Instant,
        leaseUntil: Instant,
        targetCloudBusinessId: BusinessId? = null,
    ): Boolean

    /** Marca SYNCED tras un acuse válido. Exige el token del claim; `false` si ya no es dueño. */
    suspend fun complete(
        operationId: String,
        claimToken: String,
        completedAt: Instant,
    ): Boolean

    /**
     * Cierra el intento con fallo. [targetStatus] es `PENDING` (reintento automático programado
     * en [nextAttemptAt]), `FAILED` (permanente; acción manual) o `CONFLICT` (conciliación
     * manual). [error] ya viene sanitizado por el procesador. En CONFLICT, los IDs remotos
     * identifican el registro de la nube que choca (nunca contenido).
     */
    suspend fun fail(
        operationId: String,
        claimToken: String,
        targetStatus: OutboxOperationStatus,
        error: String,
        nextAttemptAt: Instant?,
        failedAt: Instant,
        conflictRemotePurchaseId: String? = null,
        conflictReceiptId: String? = null,
    ): Boolean

    /** Variante que persiste la comparación remota genérica de un conflicto de catálogo. */
    suspend fun failWithRemoteConflict(
        operationId: String,
        claimToken: String,
        targetStatus: OutboxOperationStatus,
        error: String,
        nextAttemptAt: Instant?,
        failedAt: Instant,
        conflictRemotePurchaseId: String? = null,
        conflictReceiptId: String? = null,
        remoteEntity: RemoteConflictMetadata,
    ): Boolean = fail(
        operationId = operationId,
        claimToken = claimToken,
        targetStatus = targetStatus,
        error = error,
        nextAttemptAt = nextAttemptAt,
        failedAt = failedAt,
        conflictRemotePurchaseId = conflictRemotePurchaseId,
        conflictReceiptId = conflictReceiptId,
    )

    /** Cola observable para la pantalla de sincronización (orden de inserción). */
    fun observeOutboxOperations(businessId: BusinessId): Flow<List<OutboxOperationView>>

    /**
     * Resolución humana de un CONFLICT conservando el registro remoto: la operación pasa a
     * RESOLVED (no se reintenta jamás) y en la MISMA transacción se audita la decisión
     * (`SYNC_CONFLICT_RESOLVED`). Si es el alta, sus anulaciones dependientes abiertas también
     * quedan RESOLVED: nunca se envía un efecto contra un registro remoto de identidad distinta.
     * `false` si la operación ya no estaba en CONFLICT.
     */
    suspend fun resolveConflictKeepRemote(
        activeBusinessId: BusinessId,
        operationId: String,
        purchaseId: PurchaseId,
        remotePurchaseId: String?,
        remoteReceiptId: String?,
        actorId: String,
        resolvedAt: Instant,
    ): Boolean

    /** Reintento manual por operationId + identidad tipada, también para catálogo sin compra. */
    suspend fun retryOperation(
        operationId: String,
        businessId: BusinessId,
        entityType: String,
        entityId: String,
        retriedAt: Instant,
    ): Boolean = false

    /**
     * Devuelve el claim a PENDING sin consumir el intento (el transporte declaró no estar
     * disponible antes de cualquier IO). Solo es posible con el token del claim vigente.
     */
    suspend fun release(operationId: String, claimToken: String, releasedAt: Instant): Boolean

    /** PROCESSING → RESOLVED para un candidato documental suprimido antes de salir del equipo. */
    suspend fun resolveSuppressed(
        operationId: String,
        claimToken: String,
        resolvedAt: Instant,
    ): Boolean = false

    /** Próximo intento diferido de la cola pendiente, si existe. */
    suspend fun findNextAttemptAt(
        now: Instant,
        targetCloudBusinessId: BusinessId? = null,
    ): Instant?

    /** Próximo retry documental en cualquier tenant ya fijado; nunca incluye uploads. */
    suspend fun findNextDocumentPurgeAttemptAt(now: Instant): Instant? =
        findNextAttemptAt(now, targetCloudBusinessId = null)

    /** Lease vigente más próximo a vencer; su vencimiento habilita una recuperación en caliente. */
    suspend fun findNextClaimLeaseExpiry(
        now: Instant,
        targetCloudBusinessId: BusinessId? = null,
    ): Instant?

    /** Próximo lease de purga en cualquier tenant fijado; sirve al canal privacy-only. */
    suspend fun findNextDocumentPurgeClaimLeaseExpiry(now: Instant): Instant? =
        findNextClaimLeaseExpiry(now, targetCloudBusinessId = null)

    /**
     * Alta más antigua que aún exige envío, reintento o resolución humana para un tenant ya
     * fijado. Se usa únicamente como bucket grueso; timestamp e identidades no salen.
     */
    suspend fun findOldestOutstandingCreatedAt(targetCloudBusinessId: BusinessId): Instant?
}
