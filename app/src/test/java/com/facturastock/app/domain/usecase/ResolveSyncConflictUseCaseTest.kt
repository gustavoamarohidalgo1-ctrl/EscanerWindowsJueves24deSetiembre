package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.CatalogApplicationOutcome
import com.facturastock.app.domain.repository.CatalogOutboxConflictResolution
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.RemoteCatalogApplicationRepository
import com.facturastock.app.domain.repository.RetryPurchaseBackupResult
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakePurchaseBackupOutboxRepository
import com.facturastock.app.testing.FakePurchaseBackupRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveSyncConflictUseCaseTest {
    private var now: Instant = Instant.parse("2026-08-16T12:00:00Z")
    private val clock = AppClock { now }
    private val outbox = FakePurchaseBackupOutboxRepository()
    private val configuration = FakeAppConfigurationRepository()
    private val backups = FakePurchaseBackupRepository()
    private val scheduler = FakePurchaseBackupScheduler()
    private val retryBackup = RetryPurchaseBackupUseCase(configuration, backups, scheduler)
    private val resolveConflict = ResolveSyncConflictUseCase(outbox, retryBackup, clock)

    @Test
    fun `una operacion que no esta en CONFLICT devuelve NotInConflict sin tocar nada`() = runTest {
        val operation = operationView(index = 1, status = OutboxOperationStatus.FAILED)

        assertEquals(
            ResolveSyncConflictResult.NotInConflict,
            resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.KEEP_REMOTE, ACTOR_ID),
        )
        assertEquals(
            ResolveSyncConflictResult.NotInConflict,
            resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID),
        )
        assertEquals(0, outbox.size)
        assertTrue(outbox.conflictResolutions.isEmpty())
        assertTrue(backups.retryRequests.isEmpty())
        assertEquals(0, scheduler.enqueueCount)
    }

    @Test
    fun `KEEP_REMOTE resuelve y registra los IDs del conflicto y el actor`() = runTest {
        val operation = conflictInOutbox(index = 1)
        val dependentVoid = voidEnvelope(index = 2, purchaseId = purchaseId(1))
        outbox.insert(dependentVoid)

        val result = resolveConflict(
            BUSINESS_ID,
            operation,
            SyncConflictResolution.KEEP_REMOTE,
            ACTOR_ID,
        )

        assertEquals(ResolveSyncConflictResult.Resolved, result)
        val resolution = outbox.conflictResolutions.single()
        assertEquals(operation.operationId, resolution.operationId)
        assertEquals(purchaseId(1), resolution.purchaseId)
        assertEquals(REMOTE_PURCHASE_ID, resolution.remotePurchaseId)
        assertEquals(REMOTE_RECEIPT_ID, resolution.remoteReceiptId)
        assertEquals(ACTOR_ID, resolution.actorId)
        assertEquals(now, resolution.resolvedAt)
        assertEquals(OutboxOperationStatus.RESOLVED, outbox.row(operation.operationId)!!.status)
        assertEquals(
            OutboxOperationStatus.RESOLVED,
            outbox.row(dependentVoid.operationId)!!.status,
        )
        // KEEP_REMOTE nunca reintenta el respaldo.
        assertTrue(backups.retryRequests.isEmpty())
        assertEquals(0, scheduler.enqueueCount)
    }

    @Test
    fun `KEEP_REMOTE con CAS perdido devuelve NotInConflict y no registra la decision`() = runTest {
        // La vista dice CONFLICT pero la fila real sigue PENDING: otro camino ya la movió.
        outbox.insert(envelope(1))
        val operation = operationView(index = 1, status = OutboxOperationStatus.CONFLICT)

        val result = resolveConflict(
            BUSINESS_ID,
            operation,
            SyncConflictResolution.KEEP_REMOTE,
            ACTOR_ID,
        )

        assertEquals(ResolveSyncConflictResult.NotInConflict, result)
        assertTrue(outbox.conflictResolutions.isEmpty())
        assertEquals(OutboxOperationStatus.PENDING, outbox.row(operation.operationId)!!.status)
    }

    @Test
    fun `una operacion de otro negocio se rechaza antes de resolver por operationId`() = runTest {
        val operation = conflictInOutbox(index = 1)

        val result = resolveConflict(
            OTHER_BUSINESS_ID,
            operation,
            SyncConflictResolution.KEEP_REMOTE,
            ACTOR_ID,
        )

        assertEquals(ResolveSyncConflictResult.NotFound, result)
        assertTrue(outbox.conflictResolutions.isEmpty())
        assertEquals(OutboxOperationStatus.CONFLICT, outbox.row(operation.operationId)?.status)
    }

    @Test
    fun `RETRY delega en el reintento existente y mapea Requeued`() = runTest {
        activateBusiness()
        backups.nextRetryResult = RetryPurchaseBackupResult.Requeued
        val operation = conflictInOutbox(index = 1)

        val result = resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID)

        assertEquals(ResolveSyncConflictResult.Requeued, result)
        assertEquals(listOf(BUSINESS_ID to purchaseId(1)), backups.retryRequests)
        assertEquals(1, scheduler.enqueueCount)
        // El reintento no resuelve el conflicto en la outbox durable.
        assertTrue(outbox.conflictResolutions.isEmpty())
        assertEquals(OutboxOperationStatus.CONFLICT, outbox.row(operation.operationId)!!.status)
    }

    @Test
    fun `RETRY conserva Requeued si WorkManager no puede persistir el despertar`() = runTest {
        activateBusiness()
        backups.nextRetryResult = RetryPurchaseBackupResult.Requeued
        scheduler.enqueueFailure = IllegalStateException("workmanager storage unavailable")
        val operation = conflictInOutbox(index = 1)

        val result = resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID)

        assertEquals(ResolveSyncConflictResult.Requeued, result)
        assertEquals(listOf(BUSINESS_ID to purchaseId(1)), backups.retryRequests)
    }

    @Test
    fun `RETRY pendiente o ya respaldada se reporta como NotInConflict`() = runTest {
        activateBusiness()
        val operation = conflictInOutbox(index = 1)

        backups.nextRetryResult = RetryPurchaseBackupResult.AlreadyPending
        assertEquals(
            ResolveSyncConflictResult.NotInConflict,
            resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID),
        )

        backups.nextRetryResult = RetryPurchaseBackupResult.AlreadySynced
        assertEquals(
            ResolveSyncConflictResult.NotInConflict,
            resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID),
        )

        assertEquals(2, backups.retryRequests.size)
        assertEquals(0, scheduler.enqueueCount)
    }

    @Test
    fun `RETRY con compra inexistente se reporta como NotFound`() = runTest {
        activateBusiness()
        backups.nextRetryResult = RetryPurchaseBackupResult.NotFound
        val operation = conflictInOutbox(index = 1)

        val result = resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID)

        assertEquals(ResolveSyncConflictResult.NotFound, result)
    }

    @Test
    fun `RETRY usa el negocio capturado aunque la configuracion cambie durante la accion`() = runTest {
        backups.nextRetryResult = RetryPurchaseBackupResult.Requeued
        val operation = conflictInOutbox(index = 1)

        val result = resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID)

        assertEquals(ResolveSyncConflictResult.Requeued, result)
        assertEquals(listOf(BUSINESS_ID to purchaseId(1)), backups.retryRequests)
        assertEquals(1, scheduler.enqueueCount)
    }

    @Test
    fun `una operacion en CONFLICT sin purchaseId se reporta como NotFound`() = runTest {
        val operation = operationView(index = 1, status = OutboxOperationStatus.CONFLICT)
            .copy(purchaseId = null)

        assertEquals(
            ResolveSyncConflictResult.NotFound,
            resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.KEEP_REMOTE, ACTOR_ID),
        )
        assertEquals(
            ResolveSyncConflictResult.NotFound,
            resolveConflict(BUSINESS_ID, operation, SyncConflictResolution.RETRY, ACTOR_ID),
        )
        assertTrue(outbox.conflictResolutions.isEmpty())
        assertTrue(backups.retryRequests.isEmpty())
    }

    @Test
    fun `conflicto catalogo obsoleto se conserva como NotInConflict y no despierta worker`() =
        runTest {
            val staleRepository = object : RemoteCatalogApplicationRepository {
                override suspend fun applyPending(
                    localBusinessId: BusinessId,
                    cloudBusinessId: BusinessId,
                    appliedAt: Instant,
                ): DomainResult<CatalogApplicationOutcome> = error("No debe aplicar pull")

                override suspend fun resolveOutboxConflict(
                    activeBusinessId: BusinessId,
                    operation: OutboxOperationView,
                    resolution: CatalogOutboxConflictResolution,
                    actorId: String,
                    resolvedAt: Instant,
                ): DomainResult<Boolean> = DomainResult.Failure(AccountError.Conflict)
            }
            val useCase = ResolveSyncConflictUseCase(
                outbox = outbox,
                retryBackup = retryBackup,
                clock = clock,
                catalogConflicts = staleRepository,
                scheduler = scheduler,
            )
            val staleOperation = operationView(1, OutboxOperationStatus.CONFLICT).copy(
                operationType = "SYNC_PRODUCT",
                purchaseId = null,
                entityType = "PRODUCT",
                entityId = "20000000-0000-4000-8000-000000000001",
                businessId = BUSINESS_ID,
            )

            assertEquals(
                ResolveSyncConflictResult.NotInConflict,
                useCase(BUSINESS_ID, staleOperation, SyncConflictResolution.KEEP_REMOTE, ACTOR_ID),
            )
            assertEquals(0, scheduler.enqueueCount)
        }

    /** Deja una operación CONFLICT real en la outbox y devuelve su vista para el caso de uso. */
    private suspend fun conflictInOutbox(index: Int): OutboxOperationView {
        outbox.insert(envelope(index))
        outbox.claim(operationId(index), "token-$index", now, now.plusSeconds(30))
        outbox.fail(
            operationId = operationId(index),
            claimToken = "token-$index",
            targetStatus = OutboxOperationStatus.CONFLICT,
            error = "ALREADY_EXISTS",
            nextAttemptAt = null,
            failedAt = now,
            conflictRemotePurchaseId = REMOTE_PURCHASE_ID,
            conflictReceiptId = REMOTE_RECEIPT_ID,
        )
        return operationView(index, OutboxOperationStatus.CONFLICT)
    }

    private suspend fun activateBusiness() {
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun operationView(
        index: Int,
        status: OutboxOperationStatus,
    ): OutboxOperationView = OutboxOperationView(
        operationId = operationId(index),
        operationType = "SYNC_PURCHASE",
        purchaseId = purchaseId(index),
        status = status,
        attemptCount = 1,
        lastError = "ALREADY_EXISTS",
        nextAttemptAt = null,
        updatedAt = now,
        conflictRemotePurchaseId = REMOTE_PURCHASE_ID,
        conflictReceiptId = REMOTE_RECEIPT_ID,
        businessId = BUSINESS_ID,
    )

    private companion object {
        const val ACTOR_ID = "uid-actor"
        const val REMOTE_PURCHASE_ID = "remote-purchase-1"
        const val REMOTE_RECEIPT_ID = "rcpt-remote-1"

        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000c1"),
        )
        val OTHER_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000c2"),
        )

        fun operationId(index: Int): String = "00000000-0000-0000-0000-${"%012d".format(index)}"

        fun purchaseId(index: Int): PurchaseId =
            PurchaseId.from(UUID.fromString("10000000-0000-0000-0000-${"%012d".format(index)}"))

        fun envelope(index: Int): BackupEnvelope = BackupEnvelope(
            operationId = operationId(index),
            businessId = BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = purchaseId(index),
            idempotencyKey = "sync-purchase:v1:op-$index",
            operationType = "SYNC_PURCHASE",
            payloadVersion = 2,
            payload = "{\"version\":2,\"purchaseId\":\"op-$index\"}",
        )

        fun voidEnvelope(index: Int, purchaseId: PurchaseId): BackupEnvelope = BackupEnvelope(
            operationId = operationId(index),
            businessId = BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = purchaseId,
            idempotencyKey = "sync-purchase-void:v1:${purchaseId.value}",
            operationType = "SYNC_PURCHASE_VOID",
            payloadVersion = 1,
            payload = "{\"version\":1,\"purchaseId\":\"${purchaseId.value}\"}",
        )
    }
}
