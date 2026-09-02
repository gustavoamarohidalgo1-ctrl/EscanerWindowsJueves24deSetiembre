package com.facturastock.app.feature.sync

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.LocalLedgerSnapshot
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseDocumentIdentity
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import com.facturastock.app.domain.repository.RetryPurchaseBackupResult
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.ObservePurchasesUseCase
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import com.facturastock.app.domain.usecase.ReconcileRemoteLedgerUseCase
import com.facturastock.app.domain.usecase.RecordReconciliationReviewUseCase
import com.facturastock.app.domain.usecase.ResolveSyncConflictUseCase
import com.facturastock.app.domain.usecase.RetryPurchaseBackupUseCase
import com.facturastock.app.domain.usecase.RetrySyncOperationUseCase
import com.facturastock.app.domain.usecase.SyncPullOutcome
import com.facturastock.app.testing.FakeAccountRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeAuditTrailRepository
import com.facturastock.app.testing.FakeFirebaseConnectivity
import com.facturastock.app.testing.FakePurchaseBackupOutboxRepository
import com.facturastock.app.testing.FakePurchaseBackupRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakeRemoteLedgerRepository
import com.facturastock.app.testing.FakeSyncCursorRepository
import com.facturastock.app.testing.FakeSyncReconciliationRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var now: Instant = Instant.parse("2026-08-16T12:00:00Z")
    private val clock = AppClock { now }
    private val configuration = FakeAppConfigurationRepository()
    private val firebaseConnectivity = FakeFirebaseConnectivity()
    private val accountRepository = FakeAccountRepository(connectivity = firebaseConnectivity)
    private val outbox = FakePurchaseBackupOutboxRepository()
    private val cursors = FakeSyncCursorRepository()
    private val remoteLedger = FakeRemoteLedgerRepository(connectivity = firebaseConnectivity)
    private val reconciliation = FakeSyncReconciliationRepository()
    private val auditTrail = FakeAuditTrailRepository()
    private val purchaseReads = FakePurchaseReadRepository()
    private val backups = FakePurchaseBackupRepository()
    private val scheduler = FakePurchaseBackupScheduler()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `expone el estado inicial antes de observar la sesion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            assertEquals(SyncContract.State(), viewModel.uiState.value)
        }

    @Test
    fun `fallo inicial de sesion se recupera al reintentar`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.observeSessionFailure = IllegalStateException("session unavailable")
            val viewModel = createViewModel()
            runCurrent()

            assertNull(viewModel.uiState.value.session)
            assertEquals(
                AccountError.Unexpected,
                viewModel.uiState.value.initialLoadFailure,
            )

            accountRepository.observeSessionFailure = null
            viewModel.onAction(SyncContract.Action.RetryInitialLoad)
            runCurrent()

            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertNull(viewModel.uiState.value.initialLoadFailure)
        }

    @Test
    fun `reintentar el loader revive un observer de compras que ya termino`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateLinkedSession()
            purchaseReads.observePurchasesFailure = IllegalStateException("room observer failed")
            val viewModel = createViewModel()
            runCurrent()

            assertEquals(listOf(LOCAL_BUSINESS_ID), purchaseReads.observedPurchaseBusinesses)
            assertTrue(viewModel.uiState.value.purchases.isEmpty())

            purchaseReads.observePurchasesFailure = null
            viewModel.onAction(SyncContract.Action.RetryInitialLoad)
            runCurrent()

            assertEquals(
                listOf(LOCAL_BUSINESS_ID, LOCAL_BUSINESS_ID),
                purchaseReads.observedPurchaseBusinesses,
            )
            assertNull(viewModel.uiState.value.initialLoadFailure)
        }

    @Test
    fun `sin sesion la sincronizacion no esta disponible y SyncNow no hace nada`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            remoteLedger.available = false
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(AccountSession.SignedOut, state.session)
            assertFalse(state.remoteLedgerAvailable)
            assertFalse(state.syncAvailable)
            assertFalse(state.canPull)
            assertNull(state.cursor)
            assertTrue(state.outbox.isEmpty())

            viewModel.onAction(SyncContract.Action.SyncNow)
            runCurrent()
            assertTrue(remoteLedger.pullCalls.isEmpty())
            assertNull(viewModel.uiState.value.pullOutcome)
        }

    @Test
    fun `con sesion sin enlace no hay sincronizacion aunque el libro remoto exista`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateLocalBusiness()
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state.remoteLedgerAvailable)
            assertNull(state.cloudBusinessId)
            assertFalse(state.syncAvailable)

            viewModel.onAction(SyncContract.Action.SyncNow)
            viewModel.onAction(SyncContract.Action.CompareWithCloud)
            runCurrent()
            assertTrue(remoteLedger.pullCalls.isEmpty())
            assertTrue(reconciliation.loadCalls.isEmpty())
        }

    @Test
    fun `respaldo desactivado conserva enlace y Room pero bloquea pull y comparacion cloud`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            configuration.completeOnboarding(
                LOCAL_BUSINESS_ID,
                AppConfiguration.DEFAULT_TAX_RATE,
                AppConfiguration.DEFAULT_COST_POLICY,
            )
            accountRepository.emitSession(
                AccountSession.Active(
                    uid = UID,
                    email = EMAIL,
                    link = CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = CLOUD_BUSINESS_ID,
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
            outbox.insert(envelope(1))
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state.cloudLinked)
            assertFalse(state.backupEnabled)
            assertFalse(state.syncAvailable)
            assertEquals(listOf(operationId(1)), state.outbox.map { it.operationId })

            viewModel.onAction(SyncContract.Action.SyncNow)
            viewModel.onAction(SyncContract.Action.CompareWithCloud)
            runCurrent()

            assertTrue(remoteLedger.pullCalls.isEmpty())
            assertTrue(reconciliation.loadCalls.isEmpty())
            assertNull(viewModel.uiState.value.pullOutcome)
            assertNull(viewModel.uiState.value.report)
        }

    @Test
    fun `un enlace de otro negocio local bloquea pull reconciliacion y auditoria`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateLocalBusiness()
            accountRepository.emitSession(
                AccountSession.Active(
                    uid = UID,
                    email = EMAIL,
                    link = CloudBusinessLink(
                        localBusinessId = BusinessId.from(uuid(999)),
                        cloudBusinessId = CLOUD_BUSINESS_ID,
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
            val viewModel = createViewModel()
            runCurrent()

            assertNull(viewModel.uiState.value.activeCloudLink)
            assertFalse(viewModel.uiState.value.syncAvailable)

            viewModel.onAction(SyncContract.Action.SyncNow)
            viewModel.onAction(SyncContract.Action.CompareWithCloud)
            viewModel.onAction(SyncContract.Action.RecordReview)
            runCurrent()

            assertTrue(remoteLedger.pullCalls.isEmpty())
            assertTrue(reconciliation.loadCalls.isEmpty())
            assertTrue(auditTrail.events.isEmpty())
        }

    @Test
    fun `sesion vencida corta operaciones remotas y limpia las proyecciones de identidad`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            conflictInOutbox(index = 2)
            cursors.seedCursor(CLOUD_BUSINESS_ID, seq = 77L, pulledAt = now.minusSeconds(600))
            cursors.seedPurchaseChanges(
                CLOUD_BUSINESS_ID,
                listOf(cachedRemoteChange("remote-purchase-2", seq = 77L)),
            )
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            val conflict = viewModel.uiState.value.conflictOperations.single()

            viewModel.onAction(SyncContract.Action.KeepRemoteRequested(conflict))
            runCurrent()
            assertEquals(conflict, viewModel.uiState.value.keepRemoteCandidate)

            accountRepository.emitSession(AccountSession.Expired(email = EMAIL))
            runCurrent()

            val expired = viewModel.uiState.value
            assertEquals(AccountSession.Expired(email = EMAIL), expired.session)
            assertFalse(expired.syncAvailable)
            assertFalse(expired.canPull)
            assertNull(expired.cursor)
            assertNull(expired.keepRemoteCandidate)
            assertTrue(expired.remoteDescriptions.isEmpty())
            assertTrue(expired.outbox.isEmpty())
            assertTrue(expired.purchases.isEmpty())

            val pullCount = remoteLedger.pullCalls.size
            viewModel.onAction(SyncContract.Action.SyncNow)
            viewModel.onAction(SyncContract.Action.RetryConflictOperation(conflict))
            runCurrent()
            assertEquals(pullCount, remoteLedger.pullCalls.size)
            assertTrue(backups.retryRequests.isEmpty())
            assertTrue(outbox.conflictResolutions.isEmpty())
        }

    @Test
    fun `cambio de tenant descarta un pull anterior aunque su callback ignore cancelacion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val pullStarted = CompletableDeferred<Unit>()
            val releasePull = CompletableDeferred<Unit>()
            remoteLedger.beforePull = {
                pullStarted.complete(Unit)
                withContext(NonCancellable) { releasePull.await() }
            }
            remoteLedger.enqueuePage(
                SyncPullPage(changes = listOf(change(1L)), nextCursor = 1L, hasMore = false),
            )
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SyncContract.Action.SyncNow)
            runCurrent()
            assertTrue(pullStarted.isCompleted)
            assertTrue(viewModel.uiState.value.isPulling)

            switchToOtherLinkedIdentity()
            runCurrent()

            val switched = viewModel.uiState.value
            assertEquals(OTHER_LOCAL_BUSINESS_ID, switched.activeBusinessId)
            assertFalse(switched.isPulling)
            assertNull(switched.pullOutcome)
            assertTrue(switched.outbox.isEmpty())
            assertTrue(switched.purchases.isEmpty())
            assertNull(switched.cursor)
            assertTrue(switched.remoteDescriptions.isEmpty())

            releasePull.complete(Unit)
            runCurrent()

            assertNull(viewModel.uiState.value.pullOutcome)
            assertNull(cursors.cursor(CLOUD_BUSINESS_ID))
        }

    @Test
    fun `una accion vieja de otro tenant no puede resolver ni reintentar su operacion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            conflictInOutbox(index = 2)
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            val staleOperation = viewModel.uiState.value.conflictOperations.single()

            switchToOtherLinkedIdentity()
            runCurrent()
            viewModel.onAction(SyncContract.Action.KeepRemoteRequested(staleOperation))
            viewModel.onAction(SyncContract.Action.RetryConflictOperation(staleOperation))
            runCurrent()

            assertTrue(viewModel.uiState.value.outbox.isEmpty())
            assertNull(viewModel.uiState.value.keepRemoteCandidate)
            assertTrue(outbox.conflictResolutions.isEmpty())
            assertTrue(backups.retryRequests.isEmpty())
            assertEquals(
                OutboxOperationStatus.CONFLICT,
                outbox.row(staleOperation.operationId)?.status,
            )
        }

    @Test
    fun `con sesion enlazada observa la outbox el cursor y las descripciones de conflictos`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            outbox.insert(envelope(1))
            conflictInOutbox(index = 2)
            cursors.seedCursor(CLOUD_BUSINESS_ID, seq = 77L, pulledAt = now.minusSeconds(600))
            val description = remoteDescription("remote-purchase-2")
            cursors.seedPurchaseChanges(
                CLOUD_BUSINESS_ID,
                listOf(cachedRemoteChange("remote-purchase-2", seq = 77L)),
            )
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state.syncAvailable)
            assertEquals(SyncCursor(seq = 77L, lastPullAt = now.minusSeconds(600)), state.cursor)
            assertEquals(2, state.outbox.size)
            assertEquals(listOf(operationId(1)), state.pendingOperations.map { it.operationId })
            assertEquals(listOf(operationId(2)), state.conflictOperations.map { it.operationId })
            // La UI observa exclusivamente la réplica Room; no describe por red.
            assertTrue(remoteLedger.describeCalls.isEmpty())
            assertEquals(
                SyncContract.RemoteDescriptionState.Loaded(description),
                state.remoteDescriptions[operationId(2)],
            )
        }

    @Test
    fun `documentos se derivan desde Room por imagen y la purga prevalece sobre la subida`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateLinkedSession()
            configuration.updateDocumentBackupEnabled(true)

            val uploaded = documentEnvelope(index = 31, imageIndex = 1)
            outbox.insert(uploaded)
            outbox.claim(uploaded.operationId, "upload-31", now, now.plusSeconds(30))
            outbox.complete(uploaded.operationId, "upload-31", now)
            val purge = documentEnvelope(
                index = 32,
                imageIndex = 1,
                operationType = "SYNC_DOCUMENT_PURGE",
                entityVersion = 2,
            )
            outbox.insert(purge)

            val failed = documentEnvelope(index = 33, imageIndex = 2)
            outbox.insert(failed)
            outbox.claim(failed.operationId, "upload-33", now, now.plusSeconds(30))
            outbox.fail(
                operationId = failed.operationId,
                claimToken = "upload-33",
                targetStatus = OutboxOperationStatus.FAILED,
                error = "HTTP_500",
                nextAttemptAt = null,
                failedAt = now,
            )

            val localOnly = documentEnvelope(index = 34, imageIndex = 3)
            outbox.insert(localOnly)
            outbox.claim(localOnly.operationId, "upload-34", now, now.plusSeconds(30))
            outbox.resolveSuppressed(localOnly.operationId, "upload-34", now)

            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state.documentBackupEnabled)
            assertTrue(state.pendingOperations.isEmpty())
            assertTrue(state.conflictOperations.isEmpty())
            assertEquals(3, state.documentOperations.size)
            assertEquals(
                SyncContract.DocumentSyncState.PURGE_PENDING,
                state.documentOperations.single { it.operation.entityId == imageId(1) }.state,
            )
            assertEquals(
                purge.operationId,
                state.documentOperations.single { it.operation.entityId == imageId(1) }
                    .operation.operationId,
            )
            assertEquals(
                SyncContract.DocumentSyncState.ERROR,
                state.documentOperations.single { it.operation.entityId == imageId(2) }.state,
            )
            assertEquals(
                SyncContract.DocumentSyncState.LOCAL_ONLY,
                state.documentOperations.single { it.operation.entityId == imageId(3) }.state,
            )
        }

    @Test
    fun `RemoteDescriptionRetry revive observer que muere durante pull y carga en segunda suscripcion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val failFirstSubscription = CompletableDeferred<Unit>()
            var subscriptions = 0
            val cache = object : RemoteSyncCacheRepository by cursors {
                override fun observePurchaseDescriptions(
                    businessId: BusinessId,
                ): Flow<Map<String, RemotePurchaseDescription>> = flow {
                    subscriptions += 1
                    if (subscriptions == 1) {
                        failFirstSubscription.await()
                        error("room observer failed during pull")
                    }
                    emitAll(cursors.observePurchaseDescriptions(businessId))
                }
            }
            conflictInOutbox(index = 2)
            activateLinkedSession()
            val viewModel = createViewModel(remoteSyncCacheRepository = cache)
            runCurrent()
            val conflict = viewModel.uiState.value.conflictOperations.single()

            assertEquals(1, subscriptions)
            assertEquals(
                SyncContract.RemoteDescriptionState.Unavailable,
                viewModel.uiState.value.remoteDescriptions[conflict.operationId],
            )

            remoteLedger.beforePull = { failFirstSubscription.complete(Unit) }
            remoteLedger.enqueuePage(
                SyncPullPage(
                    changes = listOf(cachedRemoteChange("remote-purchase-2", seq = 1L)),
                    nextCursor = 1L,
                    hasMore = false,
                ),
            )
            viewModel.onAction(SyncContract.Action.RemoteDescriptionRetry(conflict))
            runCurrent()

            assertEquals(2, subscriptions)
            assertEquals(1, remoteLedger.pullCalls.size)
            assertEquals(
                SyncContract.RemoteDescriptionState.Loaded(
                    remoteDescription("remote-purchase-2"),
                ),
                viewModel.uiState.value.remoteDescriptions[conflict.operationId],
            )
        }

    @Test
    fun `descripcion Firebase no disponible en modo avion carga al reintentar tras reconexion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            firebaseConnectivity.disconnect()
            conflictInOutbox(index = 2)
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            val conflict = viewModel.uiState.value.conflictOperations.single()

            assertEquals(
                SyncContract.RemoteDescriptionState.Unavailable,
                viewModel.uiState.value.remoteDescriptions[conflict.operationId],
            )
            assertTrue(remoteLedger.describeCalls.isEmpty())

            val description = remoteDescription("remote-purchase-2")
            remoteLedger.enqueuePage(
                SyncPullPage(
                    changes = listOf(cachedRemoteChange("remote-purchase-2", seq = 1L)),
                    nextCursor = 1L,
                    hasMore = false,
                ),
            )
            firebaseConnectivity.reconnect()
            viewModel.onAction(SyncContract.Action.RemoteDescriptionRetry(conflict))
            runCurrent()

            assertTrue(remoteLedger.describeCalls.isEmpty())
            assertEquals(
                SyncContract.RemoteDescriptionState.Loaded(description),
                viewModel.uiState.value.remoteDescriptions[conflict.operationId],
            )
        }

    @Test
    fun `reintentar una operacion FAILED la reencola y lo confirma`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            failedInOutbox(index = 1)
            backups.nextRetryResult = RetryPurchaseBackupResult.Requeued
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            val failed = viewModel.uiState.value.outbox.single()
            assertEquals(OutboxOperationStatus.FAILED, failed.status)

            viewModel.onAction(SyncContract.Action.RetryFailedOperation(failed))
            runCurrent()

            assertEquals(OutboxOperationStatus.PENDING, outbox.row(failed.operationId)?.status)
            assertEquals(1, scheduler.enqueueCount)
            val state = viewModel.uiState.value
            assertNull(state.workingOperationId)
            assertEquals(SyncContract.Feedback.OPERATION_REQUEUED, state.feedback)
            assertNull(state.failure)
        }

    @Test
    fun `reintentar una purga con master apagado despierta el canal de privacidad`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val purge = documentEnvelope(
                index = 41,
                imageIndex = 4,
                operationType = "SYNC_DOCUMENT_PURGE",
                entityVersion = 2,
            )
            outbox.insert(purge)
            outbox.claim(purge.operationId, "purge-41", now, now.plusSeconds(30))
            outbox.fail(
                operationId = purge.operationId,
                claimToken = "purge-41",
                targetStatus = OutboxOperationStatus.FAILED,
                error = "HTTP_500",
                nextAttemptAt = null,
                failedAt = now,
            )
            activateLinkedSession()
            configuration.updateBackupEnabled(false)
            val viewModel = createViewModel()
            runCurrent()
            val failed = viewModel.uiState.value.outbox.single()

            viewModel.onAction(SyncContract.Action.RetryFailedOperation(failed))
            runCurrent()

            assertEquals(OutboxOperationStatus.PENDING, outbox.row(purge.operationId)?.status)
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
            assertEquals(
                SyncContract.Feedback.OPERATION_REQUEUED,
                viewModel.uiState.value.feedback,
            )
        }

    @Test
    fun `KEEP_REMOTE pide confirmacion y resuelve con el actor de la sesion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            conflictInOutbox(index = 2)
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            val conflict = viewModel.uiState.value.conflictOperations.single()

            viewModel.onAction(SyncContract.Action.KeepRemoteRequested(conflict))
            runCurrent()
            assertEquals(conflict, viewModel.uiState.value.keepRemoteCandidate)
            assertTrue(outbox.conflictResolutions.isEmpty())

            viewModel.onAction(SyncContract.Action.KeepRemoteConfirmed)
            runCurrent()

            val state = viewModel.uiState.value
            assertNull(state.keepRemoteCandidate)
            assertEquals(SyncContract.Feedback.CONFLICT_RESOLVED, state.feedback)
            assertNull(state.failure)
            assertEquals(OutboxOperationStatus.RESOLVED, outbox.row(conflict.operationId)!!.status)
            val resolution = outbox.conflictResolutions.single()
            assertEquals(conflict.operationId, resolution.operationId)
            assertEquals(UID, resolution.actorId)
            assertTrue(viewModel.uiState.value.conflictOperations.isEmpty())
        }

    @Test
    fun `RETRY de conflicto reencola el respaldo sin aceptar el remoto`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            conflictInOutbox(index = 2)
            backups.nextRetryResult = RetryPurchaseBackupResult.Requeued
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            val conflict = viewModel.uiState.value.conflictOperations.single()

            viewModel.onAction(SyncContract.Action.RetryConflictOperation(conflict))
            runCurrent()

            assertEquals(listOf(LOCAL_BUSINESS_ID to purchaseId(2)), backups.retryRequests)
            assertEquals(1, scheduler.enqueueCount)
            assertEquals(SyncContract.Feedback.OPERATION_REQUEUED, viewModel.uiState.value.feedback)
            assertNull(viewModel.uiState.value.failure)
            assertTrue(outbox.conflictResolutions.isEmpty())
        }

    @Test
    fun `descartar el dialogo KEEP_REMOTE limpia el candidato sin resolver`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            conflictInOutbox(index = 2)
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            val conflict = viewModel.uiState.value.conflictOperations.single()

            viewModel.onAction(SyncContract.Action.KeepRemoteRequested(conflict))
            runCurrent()
            assertEquals(conflict, viewModel.uiState.value.keepRemoteCandidate)

            viewModel.onAction(SyncContract.Action.DismissDialogs)
            runCurrent()

            assertNull(viewModel.uiState.value.keepRemoteCandidate)
            assertTrue(outbox.conflictResolutions.isEmpty())
            assertEquals(OutboxOperationStatus.CONFLICT, outbox.row(conflict.operationId)!!.status)
        }

    @Test
    fun `SyncNow drena el libro remoto guarda el cursor y expone el resultado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            remoteLedger.enqueuePage(
                SyncPullPage(
                    changes = listOf(change(1L), change(2L)),
                    nextCursor = 2L,
                    hasMore = false,
                ),
            )
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SyncContract.Action.SyncNow)
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isPulling)
            assertEquals(SyncPullOutcome(pulledCount = 2, latestSeq = 2L), state.pullOutcome)
            assertNull(state.failure)
            assertEquals(
                FakeRemoteLedgerRepository.PullCall(CLOUD_BUSINESS_ID, sinceSeq = 0L, limit = 200),
                remoteLedger.pullCalls.single(),
            )
            assertEquals(SyncCursor(seq = 2L, lastPullAt = now), cursors.cursor(CLOUD_BUSINESS_ID))
        }

    @Test
    fun `un fallo del pull se expone como error sin resultado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            remoteLedger.enqueueFailure(AccountError.NetworkUnavailable)
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SyncContract.Action.SyncNow)
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isPulling)
            assertNull(state.pullOutcome)
            assertEquals(AccountError.NetworkUnavailable, state.failure)
            assertTrue(cursors.saveCalls.isEmpty())
        }

    @Test
    fun `modo avion conserva cursor y guion Firebase y reconexion completa el pull`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val previousPull = now.minusSeconds(600)
            cursors.seedCursor(CLOUD_BUSINESS_ID, seq = 40L, pulledAt = previousPull)
            remoteLedger.enqueuePage(
                SyncPullPage(changes = listOf(change(41L)), nextCursor = 41L, hasMore = false),
            )
            outbox.insert(envelope(1))
            activateLinkedSession()
            firebaseConnectivity.disconnect()
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SyncContract.Action.SyncNow)
            runCurrent()

            val offline = viewModel.uiState.value
            assertTrue(offline.syncAvailable)
            assertEquals(AccountError.NetworkUnavailable, offline.failure)
            assertNull(offline.pullOutcome)
            assertEquals(SyncCursor(seq = 40L, lastPullAt = previousPull), offline.cursor)
            assertTrue(cursors.saveCalls.isEmpty())
            assertEquals(listOf(operationId(1)), offline.pendingOperations.map { it.operationId })

            firebaseConnectivity.reconnect()
            viewModel.onAction(SyncContract.Action.SyncNow)
            runCurrent()

            val reconnected = viewModel.uiState.value
            assertNull(reconnected.failure)
            assertEquals(SyncPullOutcome(pulledCount = 1, latestSeq = 41L), reconnected.pullOutcome)
            assertEquals(SyncCursor(seq = 41L, lastPullAt = now), reconnected.cursor)
            assertEquals(
                listOf(
                    FakeRemoteLedgerRepository.PullCall(
                        CLOUD_BUSINESS_ID,
                        sinceSeq = 40L,
                        limit = 200,
                    ),
                    FakeRemoteLedgerRepository.PullCall(
                        CLOUD_BUSINESS_ID,
                        sinceSeq = 40L,
                        limit = 200,
                    ),
                ),
                remoteLedger.pullCalls,
            )
            assertEquals(listOf(operationId(1)), reconnected.pendingOperations.map { it.operationId })
        }

    @Test
    fun `CompareWithCloud muestra el reporte de reconciliacion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            remoteLedger.enqueuePage(
                SyncPullPage(changes = listOf(change(1L)), nextCursor = 1L, hasMore = false),
            )
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SyncContract.Action.CompareWithCloud)
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isComparing)
            assertNull(state.failure)
            val report = state.report!!
            assertEquals(LOCAL_BUSINESS_ID, report.businessId)
            assertEquals(1L, report.latestSeq)
            assertEquals(1, report.remoteOnly.size)
            assertEquals(now, report.generatedAt)
            assertEquals(listOf(LOCAL_BUSINESS_ID), reconciliation.loadCalls)
        }

    @Test
    fun `CompareWithCloud expone una identidad ambigua sin marcarla como solo nube`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val remote = change(1L)
            remoteLedger.enqueuePage(
                SyncPullPage(changes = listOf(remote), nextCursor = 1L, hasMore = false),
            )
            val identity = requireNotNull(
                PurchaseDocumentIdentity.normalized(
                    remote.supplierRuc,
                    remote.documentType,
                    remote.documentSeries,
                    remote.documentNumber,
                ),
            )
            reconciliation.snapshot = LocalLedgerSnapshot(
                localDocuments = mapOf(identity to listOf(purchaseId(1), purchaseId(2))),
                balances = emptyList(),
                products = emptyList(),
            )
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SyncContract.Action.CompareWithCloud)
            runCurrent()

            val report = requireNotNull(viewModel.uiState.value.report)
            assertTrue(report.matched.isEmpty())
            assertTrue(report.remoteOnly.isEmpty())
            assertEquals(listOf(purchaseId(1), purchaseId(2)), report.ambiguous.single().localPurchaseIds)
        }

    @Test
    fun `registrar la revision audita los conteos del reporte`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            remoteLedger.enqueuePage(
                SyncPullPage(changes = listOf(change(1L)), nextCursor = 1L, hasMore = false),
            )
            activateLinkedSession()
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(SyncContract.Action.CompareWithCloud)
            runCurrent()

            viewModel.onAction(SyncContract.Action.RecordReview)
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isRecordingReview)
            assertEquals(SyncContract.Feedback.REVIEW_RECORDED, state.feedback)
            val event = auditTrail.events.single()
            assertEquals(AUDIT_UUID.toString(), event.auditEventId)
            assertEquals(LOCAL_BUSINESS_ID, event.businessId)
            assertEquals(LOCAL_BUSINESS_ID.value, event.entityId)
            assertEquals(AuditEventType.SYNC_RECONCILED, event.eventType)
            assertEquals("1", event.payload.getValue("latestSeq"))
            assertEquals("0", event.payload.getValue("ambiguousCount"))
            assertEquals("1", event.payload.getValue("remoteOnlyCount"))
        }

    private fun createViewModel(
        remoteSyncCacheRepository: RemoteSyncCacheRepository = cursors,
    ): SyncViewModel {
        val retryBackup = RetryPurchaseBackupUseCase(configuration, backups, scheduler)
        return SyncViewModel(
            accountRepository = accountRepository,
            observeAppConfigurationUseCase = ObserveAppConfigurationUseCase(configuration),
            outboxRepository = outbox,
            cursorRepository = cursors,
            remoteLedgerRepository = remoteLedger,
            remoteSyncCacheRepository = remoteSyncCacheRepository,
            observePurchasesUseCase = ObservePurchasesUseCase(configuration, purchaseReads),
            pullRemoteChangesUseCase = PullRemoteChangesUseCase(
                remoteLedger,
                cursors,
                cursors,
                clock,
            ),
            reconcileRemoteLedgerUseCase = ReconcileRemoteLedgerUseCase(
                cursors,
                reconciliation,
                clock,
            ),
            resolveSyncConflictUseCase = ResolveSyncConflictUseCase(outbox, retryBackup, clock),
            recordReconciliationReviewUseCase = RecordReconciliationReviewUseCase(
                auditTrail,
                UuidGenerator { AUDIT_UUID },
                clock,
            ),
            retrySyncOperationUseCase = RetrySyncOperationUseCase(outbox, scheduler, clock),
            dispatcherProvider = dispatchers,
        )
    }

    /** Negocio local activo enlazado a la nube con sesión plena. */
    private suspend fun activateLinkedSession() {
        activateLocalBusiness()
        accountRepository.emitSession(
            AccountSession.Active(
                uid = UID,
                email = EMAIL,
                link = CloudBusinessLink(
                    localBusinessId = LOCAL_BUSINESS_ID,
                    cloudBusinessId = CLOUD_BUSINESS_ID,
                    role = BusinessRole.OWNER,
                ),
            ),
        )
    }

    private suspend fun activateLocalBusiness() {
        configuration.completeOnboarding(
            LOCAL_BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        configuration.updateBackupEnabled(true)
    }

    private suspend fun switchToOtherLinkedIdentity() {
        configuration.enterDemoMode(OTHER_LOCAL_BUSINESS_ID)
        accountRepository.emitSession(
            AccountSession.Active(
                uid = OTHER_UID,
                email = OTHER_EMAIL,
                link = CloudBusinessLink(
                    localBusinessId = OTHER_LOCAL_BUSINESS_ID,
                    cloudBusinessId = OTHER_CLOUD_BUSINESS_ID,
                    role = BusinessRole.OWNER,
                ),
            ),
        )
    }

    /** Deja la operación [index] en CONFLICT con identidad remota conocida. */
    private suspend fun conflictInOutbox(index: Int) {
        outbox.insert(envelope(index))
        outbox.claim(operationId(index), "token-$index", now, now.plusSeconds(30))
        outbox.fail(
            operationId = operationId(index),
            claimToken = "token-$index",
            targetStatus = OutboxOperationStatus.CONFLICT,
            error = "ALREADY_EXISTS",
            nextAttemptAt = null,
            failedAt = now,
            conflictRemotePurchaseId = "remote-purchase-$index",
            conflictReceiptId = "rcpt-remote-$index",
        )
    }

    /** Deja la operación [index] en FAILED tras un intento reclamado. */
    private suspend fun failedInOutbox(index: Int) {
        outbox.insert(envelope(index))
        outbox.claim(operationId(index), "token-$index", now, now.plusSeconds(30))
        outbox.fail(
            operationId = operationId(index),
            claimToken = "token-$index",
            targetStatus = OutboxOperationStatus.FAILED,
            error = "HTTP_422",
            nextAttemptAt = null,
            failedAt = now,
        )
    }

    private fun change(seq: Long): RemotePurchaseChange = RemotePurchaseChange(
        seq = seq,
        purchaseId = "remote-%06d".format(seq),
        status = PurchaseStatus.POSTED,
        documentType = "INVOICE",
        documentSeries = "F001",
        documentNumber = "%08d".format(seq),
        issueDate = "2026-08-15",
        currency = "PEN",
        supplierRuc = "20123456789",
        supplierLegalName = "PROVEEDOR DEMO SAC",
        totalMinorUnits = 1000,
        movementSummary = emptyList(),
        receiptId = "rcpt-$seq",
        syncedAtMillis = now.toEpochMilli(),
        syncedBy = "uid-remoto",
    )

    private fun remoteDescription(purchaseId: String): RemotePurchaseDescription =
        RemotePurchaseDescription(
            purchaseId = purchaseId,
            status = PurchaseStatus.POSTED,
            documentType = "INVOICE",
            documentSeries = "F001",
            documentNumber = "00000002",
            issueDate = "2026-08-15",
            currency = "PEN",
            supplierRuc = "20123456789",
            supplierLegalName = "PROVEEDOR DEMO SAC",
            totalMinorUnits = 1000,
            receiptId = "rcpt-remote-2",
            syncedAtMillis = now.toEpochMilli(),
            syncedBy = null,
        )

    private fun cachedRemoteChange(purchaseId: String, seq: Long): RemotePurchaseChange =
        RemotePurchaseChange(
            seq = seq,
            purchaseId = purchaseId,
            status = PurchaseStatus.POSTED,
            documentType = "INVOICE",
            documentSeries = "F001",
            documentNumber = "00000002",
            issueDate = "2026-08-15",
            currency = "PEN",
            supplierRuc = "20123456789",
            supplierLegalName = "PROVEEDOR DEMO SAC",
            totalMinorUnits = 1000,
            movementSummary = emptyList(),
            receiptId = "rcpt-remote-2",
            syncedAtMillis = now.toEpochMilli(),
            syncedBy = "uid-remoto-no-persistido",
        )

    private companion object {
        const val UID = "uid-sync"
        const val EMAIL = "sync@example.com"
        const val OTHER_UID = "uid-sync-other"
        const val OTHER_EMAIL = "sync-other@example.com"

        val AUDIT_UUID: UUID = UUID.fromString("99999999-9999-4999-8999-999999999999")
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(uuid(901))
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(uuid(902))
        val OTHER_LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(uuid(903))
        val OTHER_CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(uuid(904))

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

        fun operationId(index: Int): String = "00000000-0000-0000-0000-%012d".format(index)

        fun purchaseId(index: Int): PurchaseId = PurchaseId.from(uuid(100 + index))

        fun envelope(index: Int): BackupEnvelope = BackupEnvelope(
            operationId = operationId(index),
            businessId = LOCAL_BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = purchaseId(index),
            idempotencyKey = "sync-purchase:v1:op-$index",
            operationType = "SYNC_PURCHASE",
            payloadVersion = 2,
            payload = "{\"version\":2,\"purchaseId\":\"op-$index\"}",
        )

        fun documentEnvelope(
            index: Int,
            imageIndex: Int,
            operationType: String = "SYNC_DOCUMENT_UPLOAD",
            entityVersion: Long = 1,
        ): BackupEnvelope = BackupEnvelope(
            operationId = operationId(index),
            businessId = LOCAL_BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = purchaseId(1),
            idempotencyKey = "document:$operationType:$imageIndex:$entityVersion",
            operationType = operationType,
            payloadVersion = 1,
            payload = "{\"version\":1}",
            entityType = "DOCUMENT",
            entityId = imageId(imageIndex),
            entityVersion = entityVersion,
        )

        fun imageId(index: Int): String = uuid(700 + index).toString()
    }
}
