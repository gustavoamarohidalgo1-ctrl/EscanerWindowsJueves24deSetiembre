package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Cola durable mutable. La operación de negocio inserta el registro dentro de su transacción y un
 * worker lo reclama con CAS antes de realizar IO. Completar o fallar exige conservar el estado que
 * acredita ese claim, para que dos workers nunca finalicen el mismo intento.
 */
@Dao
interface OutboxOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: OutboxOperationEntity)

    @Query("SELECT * FROM outbox_operations WHERE operationId = :operationId")
    suspend fun findById(operationId: String): OutboxOperationEntity?

    /** Una cuenta local no puede borrar identidades que ya pudieron alcanzar la nube. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox_operations WHERE businessId = :businessId AND (" +
            "targetCloudBusinessId IS NOT NULL OR remoteEntityId IS NOT NULL OR " +
            "attemptCount != 0 OR (status != 'PENDING' AND NOT (status = 'RESOLVED' " +
            "AND operationType = 'SYNC_DOCUMENT_UPLOAD' AND entityType = 'DOCUMENT' " +
            "AND entityVersion = 1)) OR completedAt IS NOT NULL OR " +
            "claimToken IS NOT NULL OR claimLeaseUntil IS NOT NULL OR " +
            "conflictRemotePurchaseId IS NOT NULL OR conflictReceiptId IS NOT NULL OR " +
            "conflictRemoteEntityId IS NOT NULL OR conflictCloudBusinessId IS NOT NULL))",
    )
    suspend fun hasRemoteDeletionRisk(businessId: String): Boolean

    /** Cancela únicamente UPSERT locales nunca reclamados, dentro del borrado del producto. */
    @Query(
        "DELETE FROM outbox_operations WHERE businessId = :businessId " +
            "AND entityType = 'PRODUCT' AND entityId = :productId AND operationType = 'SYNC_PRODUCT' " +
            "AND targetCloudBusinessId IS NULL AND remoteEntityId IS NULL " +
            "AND status = 'PENDING' AND attemptCount = 0 AND completedAt IS NULL " +
            "AND claimToken IS NULL AND claimLeaseUntil IS NULL",
    )
    suspend fun deleteNeverAttemptedLocalProduct(businessId: String, productId: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox_operations WHERE businessId = :businessId " +
            "AND entityType = 'PRODUCT' AND entityId = :productId)",
    )
    suspend fun hasProductOperations(businessId: String, productId: String): Boolean

    @Query(
        "SELECT * FROM outbox_operations WHERE operationId = :operationId " +
            "AND businessId = :businessId",
    )
    suspend fun findByIdForBusiness(
        operationId: String,
        businessId: String,
    ): OutboxOperationEntity?

    @Query("SELECT * FROM outbox_operations WHERE idempotencyKey = :idempotencyKey")
    suspend fun findByIdempotencyKey(idempotencyKey: String): OutboxOperationEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox_operations WHERE businessId = :businessId " +
            "AND targetCloudBusinessId IS NULL " +
            "AND NOT (status = 'PENDING' AND attemptCount = 0 " +
            "AND completedAt IS NULL AND claimToken IS NULL AND claimLeaseUntil IS NULL) " +
            "AND NOT (operationType = 'SYNC_DOCUMENT_UPLOAD' " +
            "AND entityType = 'DOCUMENT' AND entityVersion = 1 " +
            "AND status = 'RESOLVED' AND attemptCount = 0 " +
            "AND completedAt IS NULL AND claimToken IS NULL AND claimLeaseUntil IS NULL))",
    )
    suspend fun hasUnsafeUnpinnedLegacy(businessId: String): Boolean

    @Query(
        "SELECT COUNT(*) FROM outbox_operations WHERE businessId = :businessId " +
            "AND targetCloudBusinessId IS NULL AND status = 'PENDING' AND attemptCount = 0 " +
            "AND completedAt IS NULL AND claimToken IS NULL AND claimLeaseUntil IS NULL",
    )
    suspend fun countNeverAttemptedUnpinned(businessId: String): Int

    @Query(
        "UPDATE outbox_operations SET targetCloudBusinessId = :targetCloudBusinessId, " +
            "updatedAt = MAX(updatedAt, :boundAt) WHERE businessId = :businessId " +
            "AND targetCloudBusinessId IS NULL AND status = 'PENDING' AND attemptCount = 0 " +
            "AND completedAt IS NULL AND claimToken IS NULL AND claimLeaseUntil IS NULL",
    )
    suspend fun bindNeverAttemptedUnpinned(
        businessId: String,
        targetCloudBusinessId: String,
        boundAt: Long,
    ): Int

    @Query(
        "SELECT * FROM outbox_operations WHERE businessId = :businessId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "AND entityVersion = :entityVersion ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun findForEntityVersion(
        businessId: String,
        entityType: String,
        entityId: String,
        entityVersion: Long,
    ): OutboxOperationEntity?

    @Query(
        "SELECT * FROM outbox_operations WHERE businessId = :businessId " +
            "AND operationType = :operationType AND status IN (:statuses) " +
            "ORDER BY createdAt, operationId",
    )
    suspend fun listByTypeAndStatuses(
        businessId: String,
        operationType: String,
        statuses: List<String>,
    ): List<OutboxOperationEntity>

    /**
     * Uploads que todavía requieren reconciliación de opt-out. Incluye un intento ya marcado
     * RESOLVED por la compuerta fresca de consentimiento, pero excluye en SQL cualquier imagen
     * que ya tenga su sucesor PURGE durable para no releer todo el historial en cada pasada.
     */
    @Query(
        "SELECT upload.* FROM outbox_operations upload " +
            "WHERE upload.businessId = :businessId " +
            "AND upload.operationType = :uploadOperationType " +
            "AND upload.entityType = :entityType " +
            "AND upload.entityVersion = :uploadEntityVersion " +
            "AND upload.status IN (:statuses) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations purge " +
            "WHERE purge.businessId = upload.businessId " +
            "AND purge.entityType = upload.entityType " +
            "AND purge.entityId = upload.entityId " +
            "AND purge.entityVersion = :purgeEntityVersion " +
            "AND purge.operationType = :purgeOperationType) " +
            "ORDER BY upload.createdAt, upload.operationId",
    )
    suspend fun listDocumentUploadsNeedingWithdrawal(
        businessId: String,
        uploadOperationType: String,
        purgeOperationType: String,
        entityType: String,
        uploadEntityVersion: Long,
        purgeEntityVersion: Long,
        statuses: List<String>,
    ): List<OutboxOperationEntity>

    /** Misma reconciliación, sin filtrar el tenant local visible en la UI. */
    @Query(
        "SELECT upload.* FROM outbox_operations upload " +
            "WHERE upload.operationType = :uploadOperationType " +
            "AND upload.entityType = :entityType " +
            "AND upload.entityVersion = :uploadEntityVersion " +
            "AND upload.status IN (:statuses) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations purge " +
            "WHERE purge.businessId = upload.businessId " +
            "AND purge.entityType = upload.entityType " +
            "AND purge.entityId = upload.entityId " +
            "AND purge.entityVersion = :purgeEntityVersion " +
            "AND purge.operationType = :purgeOperationType) " +
            "ORDER BY upload.businessId, upload.createdAt, upload.operationId",
    )
    suspend fun listAllDocumentUploadsNeedingWithdrawal(
        uploadOperationType: String,
        purgeOperationType: String,
        entityType: String,
        uploadEntityVersion: Long,
        purgeEntityVersion: Long,
        statuses: List<String>,
    ): List<OutboxOperationEntity>

    @Query(
        "SELECT DISTINCT entityId FROM outbox_operations " +
            "WHERE operationType = :operationType AND entityType = :entityType " +
            "AND entityVersion = :entityVersion AND status IN (:statuses)",
    )
    suspend fun listEntityIdsByTypeAndStatuses(
        operationType: String,
        entityType: String,
        entityVersion: Long,
        statuses: List<String>,
    ): List<String>

    @Query(
        "UPDATE outbox_operations SET status = :resolvedStatus, updatedAt = :resolvedAt, " +
            "nextAttemptAt = NULL, lastError = NULL, claimToken = NULL, claimLeaseUntil = NULL " +
            "WHERE operationId = :operationId AND status IN (:openStatuses) " +
            "AND completedAt IS NULL AND :resolvedAt >= updatedAt",
    )
    suspend fun resolveOpenWithoutClaim(
        operationId: String,
        openStatuses: List<String>,
        resolvedStatus: String,
        resolvedAt: Long,
    ): Int

    @Query(
        "SELECT * FROM outbox_operations WHERE businessId = :businessId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "ORDER BY entityVersion DESC, createdAt DESC LIMIT 1",
    )
    suspend fun findLatestForEntity(
        businessId: String,
        entityType: String,
        entityId: String,
    ): OutboxOperationEntity?

    @Query(
        "SELECT * FROM outbox_operations WHERE businessId = :businessId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "AND entityVersion > :afterVersion AND status IN ('PENDING','FAILED','CONFLICT') " +
            "AND completedAt IS NULL ORDER BY entityVersion ASC, createdAt ASC",
    )
    suspend fun listOpenCatalogSuccessors(
        businessId: String,
        entityType: String,
        entityId: String,
        afterVersion: Long,
    ): List<OutboxOperationEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox_operations WHERE businessId = :businessId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "AND status NOT IN ('COMPLETED','RESOLVED'))",
    )
    suspend fun hasOpenForEntity(
        businessId: String,
        entityType: String,
        entityId: String,
    ): Boolean

    /** La UI nunca consulta red: observa la última verdad de respaldo que quedó en Room. */
    @Query(
        "SELECT * FROM outbox_operations WHERE businessId = :businessId " +
            "AND purchaseId = :purchaseId " +
            "AND entityType = 'PURCHASE' " +
            "ORDER BY createdAt DESC, operationId DESC LIMIT 1",
    )
    fun observeLatestForPurchase(
        businessId: String,
        purchaseId: String,
    ): Flow<OutboxOperationEntity?>

    @Query(
        "SELECT * FROM outbox_operations WHERE businessId = :businessId " +
            "AND purchaseId = :purchaseId " +
            "AND entityType = 'PURCHASE' " +
            "ORDER BY createdAt DESC, operationId DESC LIMIT 1",
    )
    suspend fun findLatestForPurchase(
        businessId: String,
        purchaseId: String,
    ): OutboxOperationEntity?

    /**
     * Objetivo causal del reintento por compra. Mientras no exista un alta COMPLETED, un
     * SYNC_PURCHASE FAILED/CONFLICT tiene prioridad sobre una anulación posterior PENDING; así
     * la UI no intenta reencolar el efecto dependiente que aún no puede salir.
     */
    @Query(
        "SELECT candidate.* FROM outbox_operations candidate " +
            "WHERE candidate.businessId = :businessId " +
            "AND candidate.purchaseId = :purchaseId " +
            "AND candidate.entityType = 'PURCHASE' " +
            "ORDER BY CASE WHEN candidate.operationType = :purchaseOperationType " +
            "AND candidate.status IN (:failedStatus, :conflictStatus) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations completedPost " +
            "WHERE completedPost.businessId = candidate.businessId " +
            "AND completedPost.purchaseId = candidate.purchaseId " +
            "AND completedPost.entityType = 'PURCHASE' " +
            "AND completedPost.operationType = :purchaseOperationType " +
            "AND completedPost.status = :completedStatus " +
            "AND completedPost.completedAt IS NOT NULL) THEN 0 ELSE 1 END ASC, " +
            "candidate.createdAt DESC, candidate.operationId DESC LIMIT 1",
    )
    suspend fun findRetryTargetForPurchase(
        businessId: String,
        purchaseId: String,
        purchaseOperationType: String,
        failedStatus: String,
        conflictStatus: String,
        completedStatus: String,
    ): OutboxOperationEntity?

    @Query(
        "SELECT * FROM outbox_operations WHERE status = :pendingStatus " +
            "AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) " +
            "ORDER BY COALESCE(nextAttemptAt, createdAt) ASC, createdAt ASC, operationId ASC " +
            "LIMIT :limit",
    )
    suspend fun listReady(
        pendingStatus: String,
        now: Long,
        limit: Int,
    ): List<OutboxOperationEntity>

    /**
     * Selección causal para el worker: una anulación bloqueada no ocupa el límite ni puede
     * ocultar altas independientes que sí están listas. La comprobación se repite antes del
     * claim porque otro adaptador del puerto podría no resolver la dependencia en esta consulta.
     */
    @Query(
            "SELECT candidate.* FROM outbox_operations candidate " +
            "WHERE candidate.status = :pendingStatus " +
            "AND ((:targetCloudBusinessId IS NULL AND " +
            "candidate.targetCloudBusinessId IS NULL) OR " +
            "candidate.targetCloudBusinessId = :targetCloudBusinessId) " +
            "AND (candidate.nextAttemptAt IS NULL OR candidate.nextAttemptAt <= :now) " +
            "AND (:onlyDocumentPurges = 0 OR " +
            "candidate.operationType = :documentPurgeOperationType) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations predecessor " +
            "WHERE predecessor.businessId = candidate.businessId " +
            "AND predecessor.entityType = candidate.entityType " +
            "AND predecessor.entityId = candidate.entityId " +
            "AND predecessor.entityVersion < candidate.entityVersion " +
            "AND NOT ((predecessor.status = :completedStatus " +
            "AND predecessor.completedAt IS NOT NULL) OR " +
            "(candidate.entityType != 'PURCHASE' AND predecessor.status = 'RESOLVED'))) " +
            "AND (candidate.operationType NOT IN " +
            "(:voidOperationType, :documentUploadOperationType, :documentPurgeOperationType) OR " +
            "(candidate.purchaseId IS NOT NULL AND EXISTS (" +
            "SELECT 1 FROM outbox_operations prerequisite " +
            "WHERE prerequisite.businessId = candidate.businessId " +
            "AND prerequisite.purchaseId = candidate.purchaseId " +
            "AND prerequisite.operationType = :purchaseOperationType " +
            "AND ((candidate.targetCloudBusinessId IS NULL AND " +
            "prerequisite.targetCloudBusinessId IS NULL) OR " +
            "prerequisite.targetCloudBusinessId = candidate.targetCloudBusinessId) " +
            "AND prerequisite.status = :completedStatus " +
            "AND prerequisite.completedAt IS NOT NULL))) " +
            "ORDER BY COALESCE(candidate.nextAttemptAt, candidate.createdAt) ASC, " +
            "candidate.createdAt ASC, candidate.operationId ASC LIMIT :limit",
    )
    suspend fun listCausallyReady(
        pendingStatus: String,
        targetCloudBusinessId: String? = null,
        completedStatus: String,
        purchaseOperationType: String,
        voidOperationType: String,
        documentUploadOperationType: String = "SYNC_DOCUMENT_UPLOAD",
        documentPurgeOperationType: String = "SYNC_DOCUMENT_PURGE",
        onlyDocumentPurges: Boolean = false,
        now: Long,
        limit: Int,
    ): List<OutboxOperationEntity>

    /**
     * Canal de privacidad multi-negocio. Solo selecciona tombstones con tenant inmutable; nunca
     * una operación comercial ni una fila legacy sin destino demostrable.
     */
    @Query(
        "SELECT candidate.* FROM outbox_operations candidate " +
            "WHERE candidate.status = :pendingStatus " +
            "AND candidate.targetCloudBusinessId IS NOT NULL " +
            "AND candidate.operationType = :documentPurgeOperationType " +
            "AND (candidate.nextAttemptAt IS NULL OR candidate.nextAttemptAt <= :now) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations predecessor " +
            "WHERE predecessor.businessId = candidate.businessId " +
            "AND predecessor.entityType = candidate.entityType " +
            "AND predecessor.entityId = candidate.entityId " +
            "AND predecessor.entityVersion < candidate.entityVersion " +
            "AND NOT ((predecessor.status = :completedStatus " +
            "AND predecessor.completedAt IS NOT NULL) OR " +
            "predecessor.status = 'RESOLVED')) " +
            "AND candidate.purchaseId IS NOT NULL AND EXISTS (" +
            "SELECT 1 FROM outbox_operations prerequisite " +
            "WHERE prerequisite.businessId = candidate.businessId " +
            "AND prerequisite.purchaseId = candidate.purchaseId " +
            "AND prerequisite.operationType = :purchaseOperationType " +
            "AND prerequisite.targetCloudBusinessId = candidate.targetCloudBusinessId " +
            "AND prerequisite.status = :completedStatus " +
            "AND prerequisite.completedAt IS NOT NULL) " +
            "ORDER BY COALESCE(candidate.nextAttemptAt, candidate.createdAt) ASC, " +
            "candidate.createdAt ASC, candidate.operationId ASC LIMIT :limit",
    )
    suspend fun listReadyDocumentPurgesAcrossTargets(
        pendingStatus: String,
        completedStatus: String,
        purchaseOperationType: String,
        documentPurgeOperationType: String = "SYNC_DOCUMENT_PURGE",
        now: Long,
        limit: Int,
    ): List<OutboxOperationEntity>

    @Query(
        "SELECT candidate.* FROM outbox_operations candidate " +
            "WHERE candidate.status = :pendingStatus " +
            "AND candidate.targetCloudBusinessId IN (:authorizedTargets) " +
            "AND candidate.operationType = :documentPurgeOperationType " +
            "AND (candidate.nextAttemptAt IS NULL OR candidate.nextAttemptAt <= :now) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations predecessor " +
            "WHERE predecessor.businessId = candidate.businessId " +
            "AND predecessor.entityType = candidate.entityType " +
            "AND predecessor.entityId = candidate.entityId " +
            "AND predecessor.entityVersion < candidate.entityVersion " +
            "AND NOT ((predecessor.status = :completedStatus " +
            "AND predecessor.completedAt IS NOT NULL) OR predecessor.status = 'RESOLVED')) " +
            "AND candidate.purchaseId IS NOT NULL AND EXISTS (" +
            "SELECT 1 FROM outbox_operations prerequisite " +
            "WHERE prerequisite.businessId = candidate.businessId " +
            "AND prerequisite.purchaseId = candidate.purchaseId " +
            "AND prerequisite.operationType = :purchaseOperationType " +
            "AND prerequisite.targetCloudBusinessId = candidate.targetCloudBusinessId " +
            "AND prerequisite.status = :completedStatus " +
            "AND prerequisite.completedAt IS NOT NULL) " +
            "ORDER BY COALESCE(candidate.nextAttemptAt, candidate.createdAt) ASC, " +
            "candidate.createdAt ASC, candidate.operationId ASC LIMIT :limit",
    )
    suspend fun listReadyDocumentPurgesForTargets(
        pendingStatus: String,
        completedStatus: String,
        purchaseOperationType: String,
        documentPurgeOperationType: String = "SYNC_DOCUMENT_PURGE",
        authorizedTargets: List<String>,
        now: Long,
        limit: Int,
    ): List<OutboxOperationEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox_operations prerequisite " +
            "WHERE prerequisite.businessId = :businessId " +
            "AND prerequisite.purchaseId = :purchaseId " +
            "AND prerequisite.operationType = :purchaseOperationType " +
            "AND ((:targetCloudBusinessId IS NULL AND " +
            "prerequisite.targetCloudBusinessId IS NULL) OR " +
            "prerequisite.targetCloudBusinessId = :targetCloudBusinessId) " +
            "AND prerequisite.status = :completedStatus " +
            "AND prerequisite.completedAt IS NOT NULL)",
    )
    suspend fun isPurchasePostCompleted(
        businessId: String,
        purchaseId: String,
        purchaseOperationType: String,
        completedStatus: String,
        targetCloudBusinessId: String? = null,
    ): Boolean

    /**
     * CAS de claim; incrementar intentos aquí hace el contador resistente a cierres del proceso.
     * El token y su lease identifican al dueño del intento: solo quien conoce `claimToken` puede
     * completar o fallar después, y un lease vencido permite recuperar el claim sin esperar al
     * próximo arranque del proceso.
     */
    @Query(
        "UPDATE outbox_operations SET status = :claimedStatus, " +
            "attemptCount = attemptCount + 1, updatedAt = :claimedAt, lastError = NULL, " +
            "claimToken = :claimToken, claimLeaseUntil = :claimLeaseUntil " +
            "WHERE operationId = :operationId AND status = :pendingStatus " +
            "AND ((:targetCloudBusinessId IS NULL AND targetCloudBusinessId IS NULL) OR " +
            "targetCloudBusinessId = :targetCloudBusinessId) " +
            "AND (nextAttemptAt IS NULL OR nextAttemptAt <= :claimedAt) " +
            "AND completedAt IS NULL AND :claimedAt >= updatedAt " +
            "AND NOT EXISTS (SELECT 1 FROM outbox_operations predecessor " +
            "WHERE predecessor.businessId = outbox_operations.businessId " +
            "AND predecessor.entityType = outbox_operations.entityType " +
            "AND predecessor.entityId = outbox_operations.entityId " +
            "AND predecessor.entityVersion < outbox_operations.entityVersion " +
            "AND NOT ((predecessor.status = 'COMPLETED' " +
            "AND predecessor.completedAt IS NOT NULL) OR " +
            "(outbox_operations.entityType != 'PURCHASE' " +
            "AND predecessor.status = 'RESOLVED')))",
    )
    suspend fun claim(
        operationId: String,
        pendingStatus: String,
        claimedStatus: String,
        claimedAt: Long,
        claimToken: String,
        claimLeaseUntil: Long,
        targetCloudBusinessId: String? = null,
    ): Int

    @Query(
        "UPDATE outbox_operations SET status = :completedStatus, completedAt = :completedAt, " +
            "updatedAt = :completedAt, nextAttemptAt = NULL, lastError = NULL, " +
            "claimToken = NULL, claimLeaseUntil = NULL " +
            "WHERE operationId = :operationId AND status = :claimedStatus " +
            "AND claimToken = :claimToken " +
            "AND completedAt IS NULL AND :completedAt >= updatedAt",
    )
    suspend fun complete(
        operationId: String,
        claimedStatus: String,
        completedStatus: String,
        completedAt: Long,
        claimToken: String,
    ): Int

    @Query(
        "UPDATE outbox_operations SET status = :resolvedStatus, updatedAt = :resolvedAt, " +
            "nextAttemptAt = NULL, lastError = NULL, claimToken = NULL, claimLeaseUntil = NULL " +
            "WHERE operationId = :operationId AND status = :claimedStatus " +
            "AND claimToken = :claimToken AND completedAt IS NULL " +
            "AND :resolvedAt >= updatedAt",
    )
    suspend fun resolveSuppressed(
        operationId: String,
        claimedStatus: String,
        resolvedStatus: String,
        resolvedAt: Long,
        claimToken: String,
    ): Int

    @Query(
        "UPDATE outbox_operations SET status = :failedStatus, lastError = :lastError, " +
            "nextAttemptAt = :nextAttemptAt, updatedAt = :failedAt, " +
            "claimToken = NULL, claimLeaseUntil = NULL, " +
            "conflictRemotePurchaseId = :conflictRemotePurchaseId, " +
            "conflictReceiptId = :conflictReceiptId, " +
            "conflictRemoteEntityId = :conflictRemoteEntityId, " +
            "conflictRemoteVersion = :conflictRemoteVersion, " +
            "conflictRemoteSnapshotPayload = :conflictRemoteSnapshotPayload, " +
            "conflictRemoteSyncedAt = :conflictRemoteSyncedAt, " +
            "conflictRemoteOrigin = :conflictRemoteOrigin, " +
            "conflictCloudBusinessId = :conflictCloudBusinessId " +
            "WHERE operationId = :operationId AND status = :claimedStatus " +
            "AND claimToken = :claimToken " +
            "AND completedAt IS NULL AND :failedAt >= updatedAt " +
            "AND (:nextAttemptAt IS NULL OR :nextAttemptAt >= :failedAt)",
    )
    suspend fun fail(
        operationId: String,
        claimedStatus: String,
        failedStatus: String,
        lastError: String,
        nextAttemptAt: Long?,
        failedAt: Long,
        claimToken: String,
        conflictRemotePurchaseId: String? = null,
        conflictReceiptId: String? = null,
        conflictRemoteEntityId: String? = null,
        conflictRemoteVersion: Long? = null,
        conflictRemoteSnapshotPayload: String? = null,
        conflictRemoteSyncedAt: Long? = null,
        conflictRemoteOrigin: String? = null,
        conflictCloudBusinessId: String? = null,
    ): Int

    /**
     * Reintento explícito e idempotente. Solo ERROR/CONFLICT vuelven a la cola; una operación
     * completada o reclamada por otro proceso no puede retroceder por un doble toque. Al salir
     * de CONFLICT se descartan los IDs del registro remoto: ya no describen el intento vigente.
     */
    @Query(
        "UPDATE outbox_operations SET status = :pendingStatus, lastError = NULL, " +
            "nextAttemptAt = NULL, updatedAt = MAX(updatedAt, :retriedAt), " +
            "conflictRemotePurchaseId = NULL, conflictReceiptId = NULL, " +
            "conflictRemoteEntityId = NULL, conflictRemoteVersion = NULL, " +
            "conflictRemoteSnapshotPayload = NULL, conflictRemoteSyncedAt = NULL, " +
            "conflictRemoteOrigin = NULL, conflictCloudBusinessId = NULL " +
            "WHERE operationId = :operationId AND businessId = :businessId " +
            "AND purchaseId = :purchaseId AND entityType = 'PURCHASE' " +
            "AND status IN (:failedStatus, :conflictStatus) " +
            "AND completedAt IS NULL",
    )
    suspend fun retryFailedOrConflicted(
        operationId: String,
        businessId: String,
        purchaseId: String,
        failedStatus: String,
        conflictStatus: String,
        pendingStatus: String,
        retriedAt: Long,
    ): Int

    /** Reintento manual por identidad genérica; no depende de que exista purchaseId. */
    @Query(
        "UPDATE outbox_operations SET status = :pendingStatus, lastError = NULL, " +
            "nextAttemptAt = NULL, updatedAt = MAX(updatedAt, :retriedAt), " +
            "conflictRemotePurchaseId = NULL, conflictReceiptId = NULL, " +
            "conflictRemoteEntityId = NULL, conflictRemoteVersion = NULL, " +
            "conflictRemoteSnapshotPayload = NULL, conflictRemoteSyncedAt = NULL, " +
            "conflictRemoteOrigin = NULL, conflictCloudBusinessId = NULL " +
            "WHERE operationId = :operationId AND businessId = :businessId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "AND status IN (:failedStatus, :conflictStatus) AND completedAt IS NULL",
    )
    suspend fun retryOperation(
        operationId: String,
        businessId: String,
        entityType: String,
        entityId: String,
        failedStatus: String,
        conflictStatus: String,
        pendingStatus: String,
        retriedAt: Long,
    ): Int

    /** Cierra prefijos locales superados por la versión remota durante una resolución explícita. */
    @Query(
        "UPDATE outbox_operations SET status = :resolvedStatus, completedAt = NULL, " +
            "nextAttemptAt = NULL, claimToken = NULL, claimLeaseUntil = NULL, " +
            "updatedAt = MAX(updatedAt, :resolvedAt) " +
            "WHERE businessId = :businessId AND entityType = :entityType " +
            "AND entityId = :entityId AND entityVersion <= :throughVersion " +
            "AND status IN (:pendingStatus, :failedStatus, :conflictStatus) " +
            "AND completedAt IS NULL",
    )
    suspend fun resolveCatalogPrefix(
        businessId: String,
        entityType: String,
        entityId: String,
        throughVersion: Long,
        pendingStatus: String,
        failedStatus: String,
        conflictStatus: String,
        resolvedStatus: String,
        resolvedAt: Long,
    ): Int

    /** APPLY_REMOTE descarta toda la cadena local abierta; ninguna versión vieja saldrá después. */
    @Query(
        "UPDATE outbox_operations SET status = :resolvedStatus, completedAt = NULL, " +
            "nextAttemptAt = NULL, claimToken = NULL, claimLeaseUntil = NULL, " +
            "updatedAt = MAX(updatedAt, :resolvedAt) " +
            "WHERE businessId = :businessId AND entityType = :entityType " +
            "AND entityId = :entityId AND status IN (:pendingStatus, :failedStatus, :conflictStatus) " +
            "AND completedAt IS NULL",
    )
    suspend fun resolveAllOpenCatalogOperations(
        businessId: String,
        entityType: String,
        entityId: String,
        pendingStatus: String,
        failedStatus: String,
        conflictStatus: String,
        resolvedStatus: String,
        resolvedAt: Long,
    ): Int

    /**
     * Reenlaza una edición todavía no enviada al agregado cloud elegido por la persona. La
     * identidad causal [entityId] local y el UUID de operación no cambian.
     */
    @Query(
        "UPDATE outbox_operations SET remoteEntityId = :remoteEntityId, " +
            "idempotencyKey = :idempotencyKey, payload = :payload, " +
            "updatedAt = MAX(updatedAt, :updatedAt) " +
            "WHERE operationId = :operationId AND businessId = :businessId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "AND status IN ('PENDING','FAILED','CONFLICT') AND completedAt IS NULL",
    )
    suspend fun rebindCatalogOperation(
        operationId: String,
        businessId: String,
        entityType: String,
        entityId: String,
        remoteEntityId: String,
        idempotencyKey: String,
        payload: String,
        updatedAt: Long,
    ): Int

    /**
     * Un cierre forzado puede dejar un claim PROCESSING sin dueño. La recuperación devuelve a
     * PENDING conservando `attemptCount`; el envío remoto deberá usar idempotencyKey. Solo se
     * recuperan claims sin lease (anteriores a v15) o con lease ya vencido: un worker vivo con
     * lease vigente jamás pierde su intento. Sirve al arranque del proceso y en caliente.
     */
    @Query(
        "UPDATE outbox_operations SET status = :pendingStatus, lastError = NULL, " +
            "nextAttemptAt = NULL, updatedAt = MAX(updatedAt, :recoveredAt), " +
            "claimToken = NULL, claimLeaseUntil = NULL " +
            "WHERE status = :processingStatus AND completedAt IS NULL " +
            "AND (claimLeaseUntil IS NULL OR claimLeaseUntil <= :recoveredAt)",
    )
    suspend fun recoverInterrupted(
        processingStatus: String,
        pendingStatus: String,
        recoveredAt: Long,
    ): Int

    /** Próximo intento programado de la cola pendiente; alimenta el follow-up del worker. */
    @Query(
        "SELECT MIN(nextAttemptAt) FROM outbox_operations WHERE status = :pendingStatus " +
            "AND ((:targetCloudBusinessId IS NULL AND targetCloudBusinessId IS NULL) OR " +
            "targetCloudBusinessId = :targetCloudBusinessId) " +
            "AND completedAt IS NULL AND nextAttemptAt IS NOT NULL AND nextAttemptAt > :now",
    )
    suspend fun findNextAttemptAt(
        pendingStatus: String,
        now: Long,
        targetCloudBusinessId: String? = null,
    ): Long?

    @Query(
        "SELECT MIN(nextAttemptAt) FROM outbox_operations WHERE status = :pendingStatus " +
            "AND operationType = :documentPurgeOperationType " +
            "AND targetCloudBusinessId IS NOT NULL AND completedAt IS NULL " +
            "AND nextAttemptAt IS NOT NULL AND nextAttemptAt > :now",
    )
    suspend fun findNextDocumentPurgeAttemptAt(
        pendingStatus: String,
        documentPurgeOperationType: String = "SYNC_DOCUMENT_PURGE",
        now: Long,
    ): Long?

    /**
     * Devolución sin consumo: el transporte se declaró no disponible antes de cualquier IO.
     * Restaura PENDING y descuenta el intento que nunca salió; solo posible con el token
     * vigente del claim. Un cierre entre claim y release conserva el intento consumido.
     */
    @Query(
        "UPDATE outbox_operations SET status = :pendingStatus, " +
            "attemptCount = attemptCount - 1, updatedAt = :releasedAt, " +
            "claimToken = NULL, claimLeaseUntil = NULL " +
            "WHERE operationId = :operationId AND status = :processingStatus " +
            "AND claimToken = :claimToken AND completedAt IS NULL AND attemptCount > 0",
    )
    suspend fun release(
        operationId: String,
        processingStatus: String,
        pendingStatus: String,
        claimToken: String,
        releasedAt: Long,
    ): Int

    /** Lease vigente más próximo a vencer; su vencimiento habilita una recuperación en caliente. */
    @Query(
        "SELECT MIN(claimLeaseUntil) FROM outbox_operations WHERE status = :processingStatus " +
            "AND ((:targetCloudBusinessId IS NULL AND targetCloudBusinessId IS NULL) OR " +
            "targetCloudBusinessId = :targetCloudBusinessId) " +
            "AND completedAt IS NULL AND claimLeaseUntil IS NOT NULL AND claimLeaseUntil > :now",
    )
    suspend fun findNextClaimLeaseExpiry(
        processingStatus: String,
        now: Long,
        targetCloudBusinessId: String? = null,
    ): Long?

    @Query(
        "SELECT MIN(claimLeaseUntil) FROM outbox_operations WHERE status = :processingStatus " +
            "AND operationType = :documentPurgeOperationType " +
            "AND targetCloudBusinessId IS NOT NULL AND completedAt IS NULL " +
            "AND claimLeaseUntil IS NOT NULL AND claimLeaseUntil > :now",
    )
    suspend fun findNextDocumentPurgeClaimLeaseExpiry(
        processingStatus: String,
        documentPurgeOperationType: String = "SYNC_DOCUMENT_PURGE",
        now: Long,
    ): Long?

    /** Operación abierta más antigua del tenant fijado, sin materializar payload ni IDs. */
    @Query(
        "SELECT MIN(createdAt) FROM outbox_operations " +
            "WHERE targetCloudBusinessId = :targetCloudBusinessId " +
            "AND status IN (:openStatuses) AND completedAt IS NULL",
    )
    suspend fun findOldestOutstandingCreatedAt(
        openStatuses: List<String>,
        targetCloudBusinessId: String,
    ): Long?

    /** Cola observable del negocio en orden de inserción, para la pantalla de sincronización. */
    @Query(
        "SELECT * FROM outbox_operations WHERE businessId = :businessId " +
            "ORDER BY createdAt ASC, operationId ASC",
    )
    fun observeForBusiness(businessId: String): Flow<List<OutboxOperationEntity>>

    /**
     * Proyección ligera para UI. Los payloads de operaciones normales pueden acercarse a 1 MB y no
     * deben materializarse al abrir Sincronización. Solo un conflicto de catálogo necesita el
     * payload local para construir la comparación humana.
     */
    @Query(
        "SELECT operationId, businessId, purchaseId, operationType, status, attemptCount, " +
            "lastError, nextAttemptAt, updatedAt, conflictRemotePurchaseId, conflictReceiptId, " +
            "conflictRemoteEntityId, conflictRemoteVersion, conflictRemoteSnapshotPayload, " +
            "conflictRemoteSyncedAt, conflictRemoteOrigin, conflictCloudBusinessId, entityType, " +
            "entityId, entityVersion, targetCloudBusinessId, " +
            "CASE WHEN status = 'CONFLICT' THEN payload ELSE NULL END " +
            "AS localConflictPayload " +
            "FROM outbox_operations WHERE businessId = :businessId " +
            "ORDER BY createdAt ASC, operationId ASC",
    )
    fun observeViewsForBusiness(businessId: String): Flow<List<OutboxOperationViewRow>>

    /**
     * Resolución humana de un CONFLICT conservando el registro remoto: la operación pasa a
     * RESOLVED y jamás se reintenta (completedAt queda NULL: solo COMPLETED lo fija). El CAS
     * exige el estado CONFLICT: cero filas significa que otra decisión llegó primero. Los IDs
     * del registro remoto se conservan como contexto durable de la decisión.
     */
    @Query(
        "UPDATE outbox_operations SET status = :resolvedStatus, completedAt = NULL, " +
            "nextAttemptAt = NULL, claimToken = NULL, claimLeaseUntil = NULL, " +
            "updatedAt = :resolvedAt " +
            "WHERE operationId = :operationId AND businessId = :businessId " +
            "AND status = :conflictStatus " +
            "AND completedAt IS NULL AND :resolvedAt >= updatedAt",
    )
    suspend fun resolveConflictKeepRemote(
        operationId: String,
        businessId: String,
        conflictStatus: String,
        resolvedStatus: String,
        resolvedAt: Long,
    ): Int

    /**
     * KEEP_REMOTE sobre el alta cierra también sus voids que nunca pudieron salir. Enviar esa
     * compensación sería incorrecto: el conflicto puede identificar otra compra remota.
     */
    @Query(
            "UPDATE outbox_operations SET status = :resolvedStatus, completedAt = NULL, " +
            "nextAttemptAt = NULL, lastError = NULL, claimToken = NULL, claimLeaseUntil = NULL, " +
            "conflictRemotePurchaseId = NULL, conflictReceiptId = NULL, " +
            "conflictRemoteEntityId = NULL, conflictRemoteVersion = NULL, " +
            "conflictRemoteSnapshotPayload = NULL, conflictRemoteSyncedAt = NULL, " +
            "conflictRemoteOrigin = NULL, conflictCloudBusinessId = NULL, " +
            "updatedAt = :resolvedAt " +
            "WHERE businessId = :businessId AND purchaseId = :purchaseId " +
            "AND operationType = :voidOperationType " +
            "AND status IN (:pendingStatus, :failedStatus, :conflictStatus) " +
            "AND completedAt IS NULL AND :resolvedAt >= updatedAt",
    )
    suspend fun resolveDependentVoidsAfterKeepRemote(
        businessId: String,
        purchaseId: String,
        voidOperationType: String,
        pendingStatus: String,
        failedStatus: String,
        conflictStatus: String,
        resolvedStatus: String,
        resolvedAt: Long,
    ): Int
}

/** Campos estrictamente necesarios para presentar y resolver la cola, sin el sobre de transporte. */
data class OutboxOperationViewRow(
    val operationId: String,
    val businessId: String,
    val purchaseId: String?,
    val operationType: String,
    val status: String,
    val attemptCount: Int,
    val lastError: String?,
    val nextAttemptAt: Long?,
    val updatedAt: Long,
    val conflictRemotePurchaseId: String?,
    val conflictReceiptId: String?,
    val conflictRemoteEntityId: String?,
    val conflictRemoteVersion: Long?,
    val conflictRemoteSnapshotPayload: String?,
    val conflictRemoteSyncedAt: Long?,
    val conflictRemoteOrigin: String?,
    val conflictCloudBusinessId: String?,
    val entityType: String,
    val entityId: String,
    val entityVersion: Long,
    val targetCloudBusinessId: String?,
    val localConflictPayload: String?,
)
