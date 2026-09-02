package com.facturastock.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.data.repository.RoomPurchaseBackupOutboxRepository
import com.facturastock.app.data.repository.RoomCloudBusinessBindingRepository
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.DisabledBusinessMembershipRepository
import com.facturastock.app.domain.repository.DisabledCatalogSyncBootstrapRepository
import com.facturastock.app.domain.repository.DisabledDocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.PendingBackupOperation
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCase
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import com.facturastock.app.testing.NoOpSyncCursorDouble
import com.facturastock.app.testing.SignedOutAccountRepositoryDouble
import com.facturastock.app.testing.TestAppConfigurationRepository
import com.facturastock.app.testing.UnavailableRemoteLedgerDouble
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba el drenado WorkManager de la outbox sin red real: el transporte es un doble con guion
 * y la conectividad se simula con el TestDriver (constraints satisfechas = "reconectar").
 */
@RunWith(AndroidJUnit4::class)
class PurchaseBackupSyncWorkerTest {
    private lateinit var context: Context
    private lateinit var database: FacturaStockDatabase
    private lateinit var transport: ScriptedTransport
    private lateinit var processor: ProcessPurchaseBackupOutboxUseCase
    private lateinit var scheduler: WorkManagerPurchaseBackupScheduler
    private lateinit var pullRemoteChanges: PullRemoteChangesUseCase
    private lateinit var cloudBusinessBindings: CloudBusinessBindingRepository
    private var now: Instant = NOW

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java)
            .addCallback(postingPersistenceCallback)
            .build()
        database.businessDao().insert(
            BusinessEntity(
                businessId = BUSINESS_ID,
                legalName = "Negocio worker de prueba",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
        now = NOW
        cloudBusinessBindings = RoomCloudBusinessBindingRepository(
            database = database,
            bindings = database.cloudBusinessBindingDao(),
            outbox = database.outboxOperationDao(),
            catalogLinks = database.catalogSyncLinkDao(),
            dispatchers = TEST_DISPATCHERS,
        )
        transport = ScriptedTransport()
        processor = ProcessPurchaseBackupOutboxUseCase(
            outbox = RoomPurchaseBackupOutboxRepository(
                database = database,
                outbox = database.outboxOperationDao(),
                auditEvents = database.auditEventDao(),
                uuids = UuidGenerator { UUID.randomUUID() },
                dispatchers = TEST_DISPATCHERS,
            ),
            transport = transport,
            clock = AppClock { now },
            uuidGenerator = UuidGenerator { UUID.randomUUID() },
        )
        scheduler = WorkManagerPurchaseBackupScheduler(
            context,
            transport,
            AppClock { now },
            FixedConfigurationRepository(),
        )
        // El destino está fijado y el pull devuelve una página vacía; no hay red real.
        pullRemoteChanges = PullRemoteChangesUseCase(
            remoteLedger = EmptyRemoteLedgerDouble(),
            cursors = NoOpSyncCursorDouble(),
            cache = NoOpSyncCursorDouble(),
            clock = AppClock { now },
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setWorkerFactory(
                    newWorkerFactory(processor, scheduler),
                )
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reconnectingRunsTheEnqueuedWorkAndMarksTheOperationSynced() = runBlocking {
        seedPendingOperation()
        scheduler.enqueue()
        val workManager = WorkManager.getInstance(context)
        val work = workManager
            .getWorkInfosForUniqueWork(WorkManagerPurchaseBackupScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
            .single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)

        // "Reconectar": la constraint de red queda satisfecha y el trabajo corre.
        val driver = WorkManagerTestInitHelper.getTestDriver(context)
            ?: error("TestDriver no disponible")
        driver.setAllConstraintsMet(work.id)

        awaitTerminalState(workManager, work.id)
        val row = database.outboxOperationDao().findById(OPERATION_ID)
        assertEquals(OutboxOperationStatus.COMPLETED.name, row?.status)
        assertEquals(1, transport.sent.size)
        Unit
    }

    @Test
    fun enqueueTwicePreservesBothWakesInTheUniqueChain() = runBlocking {
        seedPendingOperation()
        scheduler.enqueue()
        scheduler.enqueue()

        val works = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerPurchaseBackupScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
        assertEquals(2, works.size)
    }

    @Test
    fun enqueuedWorkWaitsForTheNetworkConstraint() = runBlocking {
        seedPendingOperation()
        scheduler.enqueue()
        val workManager = WorkManager.getInstance(context)
        val work = workManager
            .getWorkInfosForUniqueWork(WorkManagerPurchaseBackupScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
            .single()

        // Sin constraint satisfecha el trabajo espera durable: sigue ENQUEUED y la operación
        // conserva su estado PENDING. La durabilidad ante reinicio la garantiza la base de
        // WorkManager en el dispositivo; en la app, la fila Room y la recuperación de arranque
        // (cubiertas por OfflineRoomRestartRepositoryTest) reconstruyen el encolado.
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(work.constraints.requiredNetworkType == androidx.work.NetworkType.CONNECTED)
        assertTrue(work.constraints.requiresBatteryNotLow())
        assertTrue(work.constraints.requiresStorageNotLow())
        val row = database.outboxOperationDao().findById(OPERATION_ID)
        assertEquals(OutboxOperationStatus.PENDING.name, row?.status)
        assertTrue(transport.sent.isEmpty())
        Unit
    }

    @Test
    fun privacyPurgeAppendsEveryWakeToItsOwnConnectedOnlyChain() = runBlocking {
        scheduler.enqueuePrivacyPurge()
        scheduler.enqueuePrivacyPurge()

        val workManager = WorkManager.getInstance(context)
        val purgeWorks = workManager
            .getWorkInfosForUniqueWork(
                WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_WORK_NAME,
            )
            .get(5, TimeUnit.SECONDS)
        assertEquals(2, purgeWorks.size)
        val purge = purgeWorks.first()
        assertEquals(androidx.work.NetworkType.CONNECTED, purge.constraints.requiredNetworkType)
        assertFalse(purge.constraints.requiresBatteryNotLow())
        assertFalse(purge.constraints.requiresStorageNotLow())

        // El canal mínimo no ocupa ni hereda el request ordinario más restrictivo.
        assertTrue(
            workManager.getWorkInfosForUniqueWork(
                WorkManagerPurchaseBackupScheduler.UNIQUE_WORK_NAME,
            ).get(5, TimeUnit.SECONDS).isEmpty(),
        )

        scheduler.enqueuePrivacyPurgeAt(now.plusSeconds(30))
        val followUp = workManager.getWorkInfosByTag(
            WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
        ).get(5, TimeUnit.SECONDS).single()
        assertEquals(androidx.work.NetworkType.CONNECTED, followUp.constraints.requiredNetworkType)
        assertFalse(followUp.constraints.requiresBatteryNotLow())
        assertFalse(followUp.constraints.requiresStorageNotLow())
    }

    @Test
    fun independentDeadlinesPreserveTheEarlyWakeInBothOrderingDirections() = runBlocking {
        val oneMinute = now.plusSeconds(60)
        val oneHour = now.plusSeconds(3_600)

        // Regular: tardío primero, temprano después.
        scheduler.enqueueAt(oneHour)
        scheduler.enqueueAt(oneMinute)
        // Privacy: temprano primero, tardío después.
        scheduler.enqueuePrivacyPurgeAt(oneMinute)
        scheduler.enqueuePrivacyPurgeAt(oneHour)

        val workManager = WorkManager.getInstance(context)
        val regular = workManager.getWorkInfosByTag(
            WorkManagerPurchaseBackupScheduler.FOLLOW_UP_WORK_TAG,
        ).get(5, TimeUnit.SECONDS)
        val privacy = workManager.getWorkInfosByTag(
            WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
        ).get(5, TimeUnit.SECONDS)
        assertEquals(2, regular.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(2, privacy.count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun sameExternalDeadlineDeduplicatesButRunningWorkCanCreateOneSuccessor() = runBlocking {
        val deadline = now.plusSeconds(60)
        scheduler.enqueueAt(deadline)
        scheduler.enqueueAt(deadline)
        scheduler.enqueuePrivacyPurgeAt(deadline)
        scheduler.enqueuePrivacyPurgeAt(deadline)

        val workManager = WorkManager.getInstance(context)
        assertEquals(
            1,
            workManager.getWorkInfosByTag(
                WorkManagerPurchaseBackupScheduler.FOLLOW_UP_WORK_TAG,
            ).get(5, TimeUnit.SECONDS).count { it.state == WorkInfo.State.ENQUEUED },
        )
        assertEquals(
            1,
            workManager.getWorkInfosByTag(
                WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
            ).get(5, TimeUnit.SECONDS).count { it.state == WorkInfo.State.ENQUEUED },
        )

        scheduler.enqueueSuccessorAt(deadline, "running-regular-id")
        scheduler.enqueueSuccessorAt(deadline, "running-regular-id")
        scheduler.enqueuePrivacyPurgeSuccessorAt(deadline, "running-privacy-id")
        scheduler.enqueuePrivacyPurgeSuccessorAt(deadline, "running-privacy-id")
        assertEquals(
            2,
            workManager.getWorkInfosByTag(
                WorkManagerPurchaseBackupScheduler.FOLLOW_UP_WORK_TAG,
            ).get(5, TimeUnit.SECONDS).count { it.state == WorkInfo.State.ENQUEUED },
        )
        assertEquals(
            2,
            workManager.getWorkInfosByTag(
                WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
            ).get(5, TimeUnit.SECONDS).count { it.state == WorkInfo.State.ENQUEUED },
        )
    }

    @Test
    fun cancellingChannelsCancelsEveryIndependentlyNamedDeadline() = runBlocking {
        scheduler.enqueueAt(now.plusSeconds(60))
        scheduler.enqueueAt(now.plusSeconds(3_600))
        scheduler.enqueuePrivacyPurgeAt(now.plusSeconds(60))
        scheduler.enqueuePrivacyPurgeAt(now.plusSeconds(3_600))
        val workManager = WorkManager.getInstance(context)

        scheduler.cancelRegular()
        val regular = workManager.getWorkInfosByTag(
            WorkManagerPurchaseBackupScheduler.FOLLOW_UP_WORK_TAG,
        ).get(5, TimeUnit.SECONDS)
        val privacyBeforeCancelAll = workManager.getWorkInfosByTag(
            WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
        ).get(5, TimeUnit.SECONDS)
        assertTrue(regular.all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(2, privacyBeforeCancelAll.count { it.state == WorkInfo.State.ENQUEUED })

        scheduler.cancelAll()
        val privacy = workManager.getWorkInfosByTag(
            WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
        ).get(5, TimeUnit.SECONDS)
        assertTrue(privacy.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun withoutTransportConfiguredNothingIsEnqueued() = runBlocking {
        transport.configured = false
        seedPendingOperation()
        scheduler.enqueue()
        scheduler.enqueueAt(now.plusMillis(30_000L))
        scheduler.enqueuePrivacyPurge()
        scheduler.enqueuePrivacyPurgeAt(now.plusMillis(30_000L))

        val workManager = WorkManager.getInstance(context)
        assertTrue(
            workManager.getWorkInfosForUniqueWork(WorkManagerPurchaseBackupScheduler.UNIQUE_WORK_NAME)
                .get(5, TimeUnit.SECONDS).isEmpty(),
        )
        assertTrue(
            workManager.getWorkInfosByTag(WorkManagerPurchaseBackupScheduler.FOLLOW_UP_WORK_TAG)
                .get(5, TimeUnit.SECONDS).isEmpty(),
        )
        assertTrue(
            workManager.getWorkInfosForUniqueWork(
                WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_WORK_NAME,
            ).get(5, TimeUnit.SECONDS).isEmpty(),
        )
        assertTrue(
            workManager.getWorkInfosByTag(
                WorkManagerPurchaseBackupScheduler.PRIVACY_PURGE_FOLLOW_UP_WORK_TAG,
            ).get(5, TimeUnit.SECONDS).isEmpty(),
        )
    }

    @Test
    fun privacyAuthorizationExcludesReaderWithoutDroppingAnotherWritableTenant() = runBlocking {
        val roomOutbox = RoomPurchaseBackupOutboxRepository(
            database = database,
            outbox = database.outboxOperationDao(),
            auditEvents = database.auditEventDao(),
            uuids = UuidGenerator { UUID.randomUUID() },
            dispatchers = TEST_DISPATCHERS,
        )
        val observedTargets = mutableListOf<Set<BusinessId>>()
        val recordingOutbox = object : PurchaseBackupOutboxRepository by roomOutbox {
            override suspend fun listReadyDocumentPurges(
                now: Instant,
                limit: Int,
                authorizedTargetCloudBusinessIds: Set<BusinessId>,
            ): List<PendingBackupOperation> {
                observedTargets += authorizedTargetCloudBusinessIds
                return emptyList()
            }
        }
        val privacyProcessor = ProcessPurchaseBackupOutboxUseCase(
            outbox = recordingOutbox,
            transport = transport,
            clock = AppClock { now },
            uuidGenerator = UuidGenerator { UUID.randomUUID() },
        )
        val memberships = object : BusinessMembershipRepository by
            DisabledBusinessMembershipRepository {
            override suspend fun listMyMemberships(
                expectedUid: String,
            ): DomainResult<List<CloudMembership>> = DomainResult.Success(
                listOf(
                    CloudMembership(
                        businessId = requireNotNull(BusinessId.parse(CLOUD_BUSINESS_ID)),
                        businessDisplayName = "Solo lectura",
                        role = BusinessRole.READER,
                    ),
                    CloudMembership(
                        businessId = requireNotNull(BusinessId.parse(OTHER_CLOUD_BUSINESS_ID)),
                        businessDisplayName = "Puede purgar",
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
        }

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            workerProcessor = privacyProcessor,
            membershipRepository = memberships,
            inputData = workDataOf(PurchaseBackupSyncWorker.INPUT_PURGE_ONLY to true),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(observedTargets.isNotEmpty())
        assertTrue(
            observedTargets.all {
                it == setOf(requireNotNull(BusinessId.parse(OTHER_CLOUD_BUSINESS_ID)))
            },
        )
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun workerRetriesWithWorkManagerBackoffWhenThePassFails() = runBlocking {
        // La pasada entera falla por ENOSPC: nunca se convierte en éxito ni pérdida de cola.
        val failingProcessor = ProcessPurchaseBackupOutboxUseCase(
            outbox = FailingOutboxRepository(StorageError.InsufficientSpace),
            transport = transport,
            clock = AppClock { now },
            uuidGenerator = UuidGenerator { UUID.randomUUID() },
        )

        val result = buildWorker(RecordingScheduler(), failingProcessor).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        Unit
    }

    @Test
    fun storageFullWhileReadingTheBackupGateRetriesBeforeTouchingTheQueue() = runBlocking {
        seedPendingOperation()

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = FailingCurrentConfigurationRepository(),
        ).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val row = database.outboxOperationDao().findById(OPERATION_ID)
        assertEquals(OutboxOperationStatus.PENDING.name, row?.status)
        assertEquals(0, row?.attemptCount)
        assertTrue(transport.sent.isEmpty())
        Unit
    }

    @Test
    fun storageFullWhilePinningTenantRetriesBeforeClaimOrNetwork() = runBlocking {
        seedPendingOperation()

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            bindings = ThrowingCloudBusinessBindingRepository(),
        ).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val row = database.outboxOperationDao().findById(OPERATION_ID)
        assertEquals(OutboxOperationStatus.PENDING.name, row?.status)
        assertEquals(0, row?.attemptCount)
        assertNull(row?.targetCloudBusinessId)
        assertTrue(transport.sent.isEmpty())
        Unit
    }

    @Test
    fun documentOptOutIsReconciledLocallyBeforeABlockedSessionLink() = runBlocking {
        val lifecycle = RecordingDocumentLifecycle()

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = FixedConfigurationRepository(),
            bindings = ConflictingCloudBusinessBindingRepository(),
            documentLifecycle = lifecycle,
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(now), lifecycle.withdrawAllCalls)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun workerSchedulesFollowUpAtTheNextDeferredAttempt() = runBlocking {
        transport.script = { BackupTransportResult.TransientFailure("HTTP_500") }
        seedPendingOperation()
        val recording = RecordingScheduler()

        val result = buildWorker(recording).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val row = database.outboxOperationDao().findById(OPERATION_ID)
        assertEquals(OutboxOperationStatus.PENDING.name, row?.status)
        assertEquals(now.plusMillis(30_000L).toEpochMilli(), row?.nextAttemptAt)
        assertEquals(listOf(now.plusMillis(30_000L)), recording.followUps)
    }

    @Test
    fun earlyFollowUpUsesRunningWorkSuccessorForTheSameDeadline() = runBlocking {
        val deadline = now.plusSeconds(3_600)
        seedPendingOperation(nextAttemptAt = deadline)
        val recording = RecordingScheduler()

        val result = buildWorker(
            recordingScheduler = recording,
            inputData = workDataOf(
                PurchaseBackupSyncWorker.INPUT_SCHEDULED_WAKE_AT_EPOCH_MILLIS to
                    deadline.toEpochMilli(),
            ),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(recording.followUps.isEmpty())
        assertEquals(listOf(deadline), recording.successorFollowUps.map { it.first })
        assertTrue(recording.successorFollowUps.single().second.isNotBlank())
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun workManagerStorageFailureWhileSchedulingFollowUpKeepsOutboxAndRetries() = runBlocking {
        transport.script = { BackupTransportResult.TransientFailure("HTTP_500") }
        seedPendingOperation()
        val recording = RecordingScheduler().apply {
            enqueueAtFailure = IllegalStateException("workmanager database full")
        }

        val result = buildWorker(recording).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val row = database.outboxOperationDao().findById(OPERATION_ID)
        assertEquals(OutboxOperationStatus.PENDING.name, row?.status)
        assertEquals(now.plusMillis(30_000L).toEpochMilli(), row?.nextAttemptAt)
    }

    @Test
    fun workerWithUnavailableTransportSucceedsWithoutTouchingTheQueue() = runBlocking {
        transport.configured = false
        seedPendingOperation()
        val recording = RecordingScheduler()

        assertEquals(ListenableWorker.Result.success(), buildWorker(recording).doWork())

        val row = database.outboxOperationDao().findById(OPERATION_ID)
        assertEquals(OutboxOperationStatus.PENDING.name, row?.status)
        assertEquals(0, row?.attemptCount)
        assertEquals(0, recording.enqueueCount)
        assertTrue(recording.followUps.isEmpty())
        assertNull(row?.completedAt)
    }

    @Test
    fun sessionSwitchMidBatchLeavesNextTenantRowPendingWithoutPermanentFailure() = runBlocking {
        seedPendingOperation()
        seedPendingOperation(
            operationId = SECOND_OPERATION_ID,
            idempotencyKey = SECOND_IDEMPOTENCY_KEY,
        )
        val accounts = MutableSessionAccountRepository(activeSession())
        val observability = RecordingObservability()
        val remote = FailingRemoteLedger(AccountError.NetworkUnavailable)
        transport.script = { envelope ->
            if (envelope.operationId == OPERATION_ID) {
                accounts.session.value = activeSession(
                    localBusinessId = OTHER_LOCAL_BUSINESS_ID,
                    cloudBusinessId = OTHER_CLOUD_BUSINESS_ID,
                )
            }
            BackupTransportResult.Acknowledged(
                "receipt-${envelope.operationId}",
                envelope.idempotencyKey,
            )
        }

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            observability = observability,
            accountRepository = accounts,
            pull = PullRemoteChangesUseCase(
                remoteLedger = remote,
                cursors = NoOpSyncCursorDouble(),
                cache = NoOpSyncCursorDouble(),
                clock = AppClock { now },
            ),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(OPERATION_ID), transport.sent.map { it.operationId })
        assertEquals(
            OutboxOperationStatus.COMPLETED.name,
            database.outboxOperationDao().findById(OPERATION_ID)?.status,
        )
        val untouched = database.outboxOperationDao().findById(SECOND_OPERATION_ID)
        assertEquals(OutboxOperationStatus.PENDING.name, untouched?.status)
        assertEquals(0, untouched?.attemptCount)
        assertNull(untouched?.claimToken)
        assertNull(untouched?.lastError)
        assertEquals(CLOUD_BUSINESS_ID, untouched?.targetCloudBusinessId)
        assertTrue(remote.pulledBusinessIds.isEmpty())
        assertTrue(observability.events.none { it.outcome == OperationalOutcome.FAILED })
        assertEquals(
            OperationalOutcome.SKIPPED,
            observability.events.single().outcome,
        )
        assertEquals(
            OperationalErrorCode.TRANSPORT_UNAVAILABLE,
            observability.events.single().errorCode,
        )
    }

    @Test
    fun retryableLinkedPullRetriesAndRecordsOnlyInternalBusinessIdentity() = runBlocking {
        val observability = RecordingObservability()
        val remote = FailingRemoteLedger(AccountError.NetworkUnavailable)
        val configuration = FixedConfigurationRepository()

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = configuration,
            observability = observability,
            accountRepository = activeAccountRepository(),
            pull = PullRemoteChangesUseCase(
                remoteLedger = remote,
                cursors = NoOpSyncCursorDouble(),
                cache = NoOpSyncCursorDouble(),
                clock = AppClock { now },
            ),
        ).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(listOf(CLOUD_BUSINESS_ID), remote.pulledBusinessIds.map { it.value })
        val audit = observability.events.single()
        assertEquals(OperationalAction.BACKUP_SYNC, audit.action)
        assertEquals(OperationalOutcome.RETRY_SCHEDULED, audit.outcome)
        assertEquals(OperationalErrorCode.ACCOUNT_NETWORK_UNAVAILABLE, audit.errorCode)
        assertEquals(BUSINESS_ID, audit.identifiers.businessId?.value)
        assertNull(audit.identifiers.purchaseId)
        assertNull(audit.identifiers.operationId)
    }

    @Test
    fun permanentLinkedPullFailsAuditButKeepsTheSuccessfulPushResult() = runBlocking {
        val observability = RecordingObservability()
        val remote = FailingRemoteLedger(AccountError.PermissionDenied)

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = FixedConfigurationRepository(),
            observability = observability,
            accountRepository = activeAccountRepository(),
            pull = PullRemoteChangesUseCase(
                remoteLedger = remote,
                cursors = NoOpSyncCursorDouble(),
                cache = NoOpSyncCursorDouble(),
                clock = AppClock { now },
            ),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val audit = observability.events.single()
        assertEquals(OperationalAction.BACKUP_SYNC, audit.action)
        assertEquals(OperationalOutcome.FAILED, audit.outcome)
        assertEquals(OperationalErrorCode.ACCOUNT_PERMISSION_DENIED, audit.errorCode)
        assertEquals(BUSINESS_ID, audit.identifiers.businessId?.value)
    }

    @Test
    fun disablingBackupAfterDrainSkipsPullBeforeAnyRemoteRead() = runBlocking {
        val remote = FailingRemoteLedger(AccountError.NetworkUnavailable)
        val configuration = DisableBeforePullConfigurationRepository()

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = configuration,
            accountRepository = activeAccountRepository(),
            pull = PullRemoteChangesUseCase(
                remoteLedger = remote,
                cursors = NoOpSyncCursorDouble(),
                cache = NoOpSyncCursorDouble(),
                clock = AppClock { now },
            ),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(remote.pulledBusinessIds.isEmpty())
        // Inicio de pasada, revalidación L→X al cerrar el snapshot y compuerta previa al pull.
        assertEquals(3, configuration.currentReads)
    }

    @Test
    fun tenFullPassesUseWorkManagerRetryWithoutAnIgnoredUniqueKeepEnqueue() = runBlocking {
        val outbox = AlwaysFullUnclaimableOutboxRepository()
        val observability = RecordingObservability()
        val recordingScheduler = RecordingScheduler()
        val fullProcessor = ProcessPurchaseBackupOutboxUseCase(
            outbox = outbox,
            transport = transport,
            clock = AppClock { now },
            uuidGenerator = UuidGenerator { UUID.randomUUID() },
            observability = observability,
        )

        val result = buildWorker(
            recordingScheduler = recordingScheduler,
            workerProcessor = fullProcessor,
            configuration = FixedConfigurationRepository(),
            observability = observability,
        ).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(PurchaseBackupSyncWorker.MAX_PASSES_PER_RUN, outbox.listReadyCalls)
        assertEquals(0, recordingScheduler.enqueueCount)
        assertTrue(recordingScheduler.followUps.isEmpty())
        val audit = observability.events.single()
        assertEquals(OperationalAction.BACKUP_SYNC, audit.action)
        assertEquals(OperationalOutcome.RETRY_SCHEDULED, audit.outcome)
        assertEquals(OperationalErrorCode.TRANSIENT_FAILURE, audit.errorCode)
        assertEquals(BUSINESS_ID, audit.identifiers.businessId?.value)
    }

    @Test
    fun restartAfterBothOptInsBootstrapsRetainedDocumentsOnceBeforeDrain() = runBlocking {
        val lifecycle = RecordingDocumentLifecycle()
        val configuration = FixedConfigurationRepository(
            AppConfiguration.defaults().copy(
                onboardingCompleted = true,
                businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
                backupEnabled = true,
                documentBackupEnabled = true,
                imageRetentionPolicy = ImageRetentionPolicy.KEEP,
            ),
        )

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = configuration,
            documentLifecycle = lifecycle,
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            listOf(BootstrapCall(BUSINESS_ID, postedAfterExclusive = null, requestedAt = now)),
            lifecycle.bootstrapCalls,
        )
    }

    @Test
    fun restartUsesFreshRetentionCutoffAndRetriesIfBootstrapCannotBecomeDurable() = runBlocking {
        val lifecycle = RecordingDocumentLifecycle().apply {
            bootstrapFailure = StorageException(StorageError.InsufficientSpace)
        }
        val configuration = FixedConfigurationRepository(
            AppConfiguration.defaults().copy(
                onboardingCompleted = true,
                businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
                backupEnabled = true,
                documentBackupEnabled = true,
                imageRetentionPolicy = ImageRetentionPolicy.DAYS_30,
            ),
        )

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = configuration,
            documentLifecycle = lifecycle,
        ).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(
            listOf(
                BootstrapCall(
                    BUSINESS_ID,
                    postedAfterExclusive = now.minusSeconds(30L * 24L * 60L * 60L),
                    requestedAt = now,
                ),
            ),
            lifecycle.bootstrapCalls,
        )
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun privacyRequestWithBackupEnabledSkipsBootstrapAndPull() = runBlocking {
        val lifecycle = RecordingDocumentLifecycle()
        val remote = FailingRemoteLedger(AccountError.NetworkUnavailable)
        val configuration = FixedConfigurationRepository(
            AppConfiguration.defaults().copy(
                onboardingCompleted = true,
                businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
                backupEnabled = true,
                documentBackupEnabled = true,
                imageRetentionPolicy = ImageRetentionPolicy.KEEP,
            ),
        )

        val result = buildWorker(
            recordingScheduler = RecordingScheduler(),
            configuration = configuration,
            documentLifecycle = lifecycle,
            pull = PullRemoteChangesUseCase(
                remoteLedger = remote,
                cursors = NoOpSyncCursorDouble(),
                cache = NoOpSyncCursorDouble(),
                clock = AppClock { now },
            ),
            inputData = workDataOf(PurchaseBackupSyncWorker.INPUT_PURGE_ONLY to true),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(lifecycle.bootstrapCalls.isEmpty())
        assertTrue(remote.pulledBusinessIds.isEmpty())
        assertTrue(transport.sent.isEmpty())
    }

    private fun buildWorker(
        recordingScheduler: PurchaseBackupScheduler,
        workerProcessor: ProcessPurchaseBackupOutboxUseCase = processor,
        configuration: AppConfigurationRepository = FixedConfigurationRepository(),
        observability: ProductionObservability = DisabledProductionObservability,
        accountRepository: AccountRepository = activeAccountRepository(),
        pull: PullRemoteChangesUseCase = pullRemoteChanges,
        bindings: CloudBusinessBindingRepository = cloudBusinessBindings,
        membershipRepository: BusinessMembershipRepository = authorizedMembershipRepository(),
        documentLifecycle: DocumentBackupLifecycleRepository =
            DisabledDocumentBackupLifecycleRepository,
        inputData: Data = Data.EMPTY,
    ): CoroutineWorker =
        TestListenableWorkerBuilder<PurchaseBackupSyncWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(
                newWorkerFactory(
                    processor = workerProcessor,
                    scheduler = recordingScheduler,
                    configuration = configuration,
                    observability = observability,
                    accountRepository = accountRepository,
                    pull = pull,
                    bindings = bindings,
                    membershipRepository = membershipRepository,
                    documentLifecycle = documentLifecycle,
                ),
            )
            .build()

    /**
     * Factoría con los dobles de la prueba; la configuración usa el estado compartido de
     * androidTest (respaldo activado por defecto) y el mantenimiento de privacidad nunca se
     * instancia aquí, así que su proveedor lanza si alguien lo pidiera.
     */
    private fun newWorkerFactory(
        processor: ProcessPurchaseBackupOutboxUseCase,
        scheduler: PurchaseBackupScheduler,
        configuration: AppConfigurationRepository = FixedConfigurationRepository(),
        observability: ProductionObservability = DisabledProductionObservability,
        accountRepository: AccountRepository = activeAccountRepository(),
        membershipRepository: BusinessMembershipRepository = authorizedMembershipRepository(),
        bindings: CloudBusinessBindingRepository = cloudBusinessBindings,
        pull: PullRemoteChangesUseCase = pullRemoteChanges,
        documentLifecycle: DocumentBackupLifecycleRepository =
            DisabledDocumentBackupLifecycleRepository,
    ): BackupSyncWorkerFactory = BackupSyncWorkerFactory(
        processor = Provider { processor },
        scheduler = Provider { scheduler },
        observability = Provider { observability },
        accountRepository = Provider { accountRepository },
        membershipRepository = Provider { membershipRepository },
        pullRemoteChanges = Provider { pull },
        appConfiguration = Provider { configuration },
        cloudBusinessBindings = Provider { bindings },
        catalogBootstrap = Provider { DisabledCatalogSyncBootstrapRepository },
        documentLifecycle = Provider { documentLifecycle },
        appClock = Provider { AppClock { now } },
        privacyMaintenance = Provider {
            error("PrivacyMaintenanceWorker no se usa en esta prueba")
        },
    )

    /** La pasada entera falla antes de cualquier claim, como un almacenamiento no disponible. */
    private class FailingOutboxRepository(
        private val error: StorageError = StorageError.Unavailable,
    ) : PurchaseBackupOutboxRepository {
        override suspend fun recoverExpiredClaims(now: Instant): Int = throw failure()
        override suspend fun listReady(
            now: Instant,
            limit: Int,
            onlyDocumentPurges: Boolean,
            targetCloudBusinessId: BusinessId?,
        ) = throw failure()
        override suspend fun isPurchasePostCompleted(
            businessId: BusinessId,
            purchaseId: PurchaseId,
            targetCloudBusinessId: BusinessId,
        ) = throw failure()
        override suspend fun claim(
            operationId: String,
            claimToken: String,
            claimedAt: Instant,
            leaseUntil: Instant,
            targetCloudBusinessId: BusinessId?,
        ) = throw failure()
        override suspend fun complete(operationId: String, claimToken: String, completedAt: Instant) =
            throw failure()
        override suspend fun fail(
            operationId: String,
            claimToken: String,
            targetStatus: OutboxOperationStatus,
            error: String,
            nextAttemptAt: Instant?,
            failedAt: Instant,
            conflictRemotePurchaseId: String?,
            conflictReceiptId: String?,
        ) = throw failure()
        override fun observeOutboxOperations(businessId: BusinessId) = throw failure()
        override suspend fun resolveConflictKeepRemote(
            activeBusinessId: BusinessId,
            operationId: String,
            purchaseId: PurchaseId,
            remotePurchaseId: String?,
            remoteReceiptId: String?,
            actorId: String,
            resolvedAt: Instant,
        ) = throw failure()
        override suspend fun release(operationId: String, claimToken: String, releasedAt: Instant) =
            throw failure()
        override suspend fun findNextAttemptAt(
            now: Instant,
            targetCloudBusinessId: BusinessId?,
        ) = throw failure()
        override suspend fun findNextClaimLeaseExpiry(
            now: Instant,
            targetCloudBusinessId: BusinessId?,
        ) = throw failure()
        override suspend fun findOldestOutstandingCreatedAt(
            targetCloudBusinessId: BusinessId,
        ): Instant? = throw failure()

        private fun failure(): Nothing = throw StorageException(error)
    }

    /** DataStore no pudo leer la compuerta porque el volumen está lleno. */
    private class FailingCurrentConfigurationRepository(
        delegate: AppConfigurationRepository = TestAppConfigurationRepository(),
    ) : AppConfigurationRepository by delegate {
        override suspend fun current() = throw StorageException(StorageError.InsufficientSpace)
    }

    private class ThrowingCloudBusinessBindingRepository : CloudBusinessBindingRepository {
        override suspend fun bindOnce(
            localBusinessId: BusinessId,
            cloudBusinessId: BusinessId,
            boundAt: Instant,
        ): CloudBusinessBindingResult =
            throw StorageException(StorageError.InsufficientSpace)

        override suspend fun targetFor(localBusinessId: BusinessId): BusinessId? =
            throw StorageException(StorageError.InsufficientSpace)

        override suspend fun matches(
            localBusinessId: BusinessId,
            cloudBusinessId: BusinessId,
        ): Boolean = throw StorageException(StorageError.InsufficientSpace)
    }

    private class ConflictingCloudBusinessBindingRepository : CloudBusinessBindingRepository {
        override suspend fun bindOnce(
            localBusinessId: BusinessId,
            cloudBusinessId: BusinessId,
            boundAt: Instant,
        ): CloudBusinessBindingResult = CloudBusinessBindingResult.LocalBusinessAlreadyBound

        override suspend fun targetFor(localBusinessId: BusinessId): BusinessId? = null

        override suspend fun matches(
            localBusinessId: BusinessId,
            cloudBusinessId: BusinessId,
        ): Boolean = false
    }

    private class FixedConfigurationRepository(
        private val value: AppConfiguration = AppConfiguration.defaults().copy(
            onboardingCompleted = true,
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            backupEnabled = true,
        ),
        delegate: AppConfigurationRepository = TestAppConfigurationRepository(),
    ) : AppConfigurationRepository by delegate {
        override fun observe(): Flow<AppConfiguration> = flowOf(value)
        override suspend fun current(): AppConfiguration = value
    }

    /** Primera lectura habilita el pass; la segunda simula el toggle antes del pull. */
    private class DisableBeforePullConfigurationRepository(
        delegate: AppConfigurationRepository = TestAppConfigurationRepository(),
    ) : AppConfigurationRepository by delegate {
        var currentReads: Int = 0
            private set

        private val enabled = AppConfiguration.defaults().copy(
            onboardingCompleted = true,
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            backupEnabled = true,
        )

        override fun observe(): Flow<AppConfiguration> = flowOf(enabled)

        override suspend fun current(): AppConfiguration {
            currentReads++
            return if (currentReads == 1) enabled else enabled.copy(backupEnabled = false)
        }
    }

    private fun activeAccountRepository(): AccountRepository =
        object : AccountRepository by SignedOutAccountRepositoryDouble() {
            override val available: Boolean = true
            override fun observeSession(): Flow<AccountSession> = flowOf(activeSession())
        }

    private fun authorizedMembershipRepository(): BusinessMembershipRepository =
        object : BusinessMembershipRepository by DisabledBusinessMembershipRepository {
            override suspend fun listMyMemberships(
                expectedUid: String,
            ): DomainResult<List<CloudMembership>> = DomainResult.Success(
                listOf(
                    CloudMembership(
                        businessId = requireNotNull(BusinessId.parse(CLOUD_BUSINESS_ID)),
                        businessDisplayName = "Negocio de prueba",
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
        }

    private fun activeSession(
        localBusinessId: String = BUSINESS_ID,
        cloudBusinessId: String = CLOUD_BUSINESS_ID,
    ): AccountSession.Active = AccountSession.Active(
        uid = "internal-worker-user",
        email = "private.worker@example.pe",
        link = CloudBusinessLink(
            localBusinessId = requireNotNull(BusinessId.parse(localBusinessId)),
            cloudBusinessId = requireNotNull(BusinessId.parse(cloudBusinessId)),
            role = BusinessRole.OWNER,
        ),
    )

    private class MutableSessionAccountRepository(initial: AccountSession) :
        AccountRepository by SignedOutAccountRepositoryDouble() {
        override val available: Boolean = true
        val session = MutableStateFlow(initial)
        override fun observeSession(): Flow<AccountSession> = session
    }

    private class FailingRemoteLedger(
        private val error: AccountError,
    ) : RemoteLedgerRepository {
        override val available: Boolean = true
        val pulledBusinessIds = mutableListOf<BusinessId>()

        override suspend fun pullChanges(
            businessId: BusinessId,
            sinceSeq: Long,
            limit: Int,
        ): DomainResult<SyncPullPage> {
            pulledBusinessIds += businessId
            return DomainResult.Failure(error)
        }

        override suspend fun describeRemotePurchase(
            businessId: BusinessId,
            remotePurchaseId: String,
        ): DomainResult<RemotePurchaseDescription?> = DomainResult.Failure(error)
    }

    private class EmptyRemoteLedgerDouble : RemoteLedgerRepository {
        override val available: Boolean = true

        override suspend fun pullChanges(
            businessId: BusinessId,
            sinceSeq: Long,
            limit: Int,
        ): DomainResult<SyncPullPage> = DomainResult.Success(
            SyncPullPage(changes = emptyList(), nextCursor = sinceSeq, hasMore = false),
        )

        override suspend fun describeRemotePurchase(
            businessId: BusinessId,
            remotePurchaseId: String,
        ): DomainResult<RemotePurchaseDescription?> = DomainResult.Success(null)
    }

    private class RecordingObservability : ProductionObservability {
        val events = mutableListOf<OperationalAuditEvent>()

        override suspend fun record(event: OperationalAuditEvent, failure: Throwable?) {
            events += event
        }

        override suspend fun updateConsent(enabled: Boolean) = Unit
    }

    private data class BootstrapCall(
        val businessId: String,
        val postedAfterExclusive: Instant?,
        val requestedAt: Instant,
    )

    private class RecordingDocumentLifecycle : DocumentBackupLifecycleRepository {
        val bootstrapCalls = mutableListOf<BootstrapCall>()
        val withdrawCalls = mutableListOf<Pair<String, Instant>>()
        val withdrawAllCalls = mutableListOf<Instant>()
        var bootstrapFailure: Exception? = null

        override suspend fun ensurePurge(
            businessId: BusinessId,
            image: RetainedImageRef,
            requestedAt: Instant,
        ): DocumentPurgeIntentResult = DocumentPurgeIntentResult.NOT_REQUIRED

        override suspend fun ensureRetainedUploads(
            businessId: BusinessId,
            postedAfterExclusive: Instant?,
            requestedAt: Instant,
        ): Int {
            bootstrapCalls += BootstrapCall(
                businessId = businessId.value,
                postedAfterExclusive = postedAfterExclusive,
                requestedAt = requestedAt,
            )
            bootstrapFailure?.let { throw it }
            return 0
        }

        override suspend fun withdrawOpenUploads(
            businessId: BusinessId,
            requestedAt: Instant,
        ): Int {
            withdrawCalls += businessId.value to requestedAt
            return 0
        }

        override suspend fun withdrawAllOpenUploads(requestedAt: Instant): Int {
            withdrawAllCalls += requestedAt
            return 0
        }
    }

    /** Siempre devuelve un lote lleno, pero simula que otro worker ganó todos los CAS. */
    private class AlwaysFullUnclaimableOutboxRepository : PurchaseBackupOutboxRepository {
        var listReadyCalls: Int = 0

        override suspend fun recoverExpiredClaims(now: Instant): Int = 0

        override suspend fun listReady(
            now: Instant,
            limit: Int,
            onlyDocumentPurges: Boolean,
            targetCloudBusinessId: BusinessId?,
        ): List<PendingBackupOperation> {
            listReadyCalls++
            return List(limit) { index ->
                PendingBackupOperation(
                    envelope = BackupEnvelope(
                        operationId = "00000000-0000-4000-8000-%012d".format(index + 1),
                        businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
                        targetCloudBusinessId = requireNotNull(
                            BusinessId.parse(CLOUD_BUSINESS_ID),
                        ),
                        purchaseId = null,
                        idempotencyKey = "full-pass-$index",
                        operationType = "SYNC_PURCHASE",
                        payloadVersion = 2,
                        payload = "{\"version\":2}",
                    ),
                    attemptCount = 0,
                )
            }
        }

        override suspend fun isPurchasePostCompleted(
            businessId: BusinessId,
            purchaseId: PurchaseId,
            targetCloudBusinessId: BusinessId,
        ): Boolean = false

        override suspend fun claim(
            operationId: String,
            claimToken: String,
            claimedAt: Instant,
            leaseUntil: Instant,
            targetCloudBusinessId: BusinessId?,
        ): Boolean = false

        override suspend fun complete(
            operationId: String,
            claimToken: String,
            completedAt: Instant,
        ): Boolean = false

        override suspend fun fail(
            operationId: String,
            claimToken: String,
            targetStatus: OutboxOperationStatus,
            error: String,
            nextAttemptAt: Instant?,
            failedAt: Instant,
            conflictRemotePurchaseId: String?,
            conflictReceiptId: String?,
        ): Boolean = false

        override fun observeOutboxOperations(
            businessId: BusinessId,
        ): Flow<List<OutboxOperationView>> = flowOf(emptyList())

        override suspend fun resolveConflictKeepRemote(
            activeBusinessId: BusinessId,
            operationId: String,
            purchaseId: PurchaseId,
            remotePurchaseId: String?,
            remoteReceiptId: String?,
            actorId: String,
            resolvedAt: Instant,
        ): Boolean = false

        override suspend fun release(
            operationId: String,
            claimToken: String,
            releasedAt: Instant,
        ): Boolean = false

        override suspend fun findNextAttemptAt(
            now: Instant,
            targetCloudBusinessId: BusinessId?,
        ): Instant? = null
        override suspend fun findNextClaimLeaseExpiry(
            now: Instant,
            targetCloudBusinessId: BusinessId?,
        ): Instant? = null
        override suspend fun findOldestOutstandingCreatedAt(
            targetCloudBusinessId: BusinessId,
        ): Instant? = null
    }

    private fun seedPendingOperation(
        operationId: String = OPERATION_ID,
        idempotencyKey: String = IDEMPOTENCY_KEY,
        nextAttemptAt: Instant = now,
    ) = runBlocking {
        database.outboxOperationDao().insert(
            OutboxOperationEntity(
                operationId = operationId,
                businessId = BUSINESS_ID,
                purchaseId = null,
                entityType = "PRODUCT",
                entityId = operationId,
                entityVersion = 1,
                idempotencyKey = idempotencyKey,
                operationType = "SYNC_PRODUCT",
                payload = "{\"version\":1}",
                status = OutboxOperationStatus.PENDING.name,
                attemptCount = 0,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
                nextAttemptAt = nextAttemptAt.toEpochMilli(),
                payloadVersion = 1,
            ),
        )
    }

    private fun awaitTerminalState(workManager: WorkManager, id: UUID) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val state = workManager.getWorkInfoById(id).get(5, TimeUnit.SECONDS)?.state
            if (state != null && state.isFinished) return
            Thread.sleep(50L)
        }
        fail("El trabajo no alcanzó un estado terminal")
    }

    /** Transporte con guion; por defecto confirma con el eco idempotente correcto. */
    private class ScriptedTransport : PurchaseBackupTransport {
        override var configured: Boolean = true
        val sent = mutableListOf<BackupEnvelope>()
        var script: (BackupEnvelope) -> BackupTransportResult = { envelope ->
            BackupTransportResult.Acknowledged("receipt-1", envelope.idempotencyKey)
        }

        override suspend fun send(envelope: BackupEnvelope): BackupTransportResult {
            sent += envelope
            return script(envelope)
        }
    }

    private class RecordingScheduler : PurchaseBackupScheduler {
        var enqueueCount = 0
        var cancelAllCount = 0
        var enqueueAtFailure: Exception? = null
        val followUps = mutableListOf<Instant>()
        val successorFollowUps = mutableListOf<Pair<Instant, String>>()

        override suspend fun enqueue() {
            enqueueCount++
        }

        override suspend fun enqueueAt(attemptAt: Instant) {
            enqueueAtFailure?.let { throw it }
            followUps += attemptAt
        }

        override suspend fun enqueueSuccessorAt(attemptAt: Instant, runningWorkId: String) {
            enqueueAtFailure?.let { throw it }
            successorFollowUps += attemptAt to runningWorkId
        }

        override suspend fun cancelAll() {
            cancelAllCount++
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-14T18:00:00Z")
        const val BUSINESS_ID = "00000000-0000-4000-8000-000000004201"
        const val OPERATION_ID = "00000000-0000-4000-8000-000000004202"
        const val SECOND_OPERATION_ID = "00000000-0000-4000-8000-000000004203"
        const val CLOUD_BUSINESS_ID = "00000000-0000-4000-8000-000000004299"
        const val OTHER_LOCAL_BUSINESS_ID = "00000000-0000-4000-8000-000000004301"
        const val OTHER_CLOUD_BUSINESS_ID = "00000000-0000-4000-8000-000000004399"
        const val IDEMPOTENCY_KEY = "sync-purchase:v1:worker-test"
        const val SECOND_IDEMPOTENCY_KEY = "sync-purchase:v1:worker-test-2"
        val TEST_DISPATCHERS = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }
    }
}
