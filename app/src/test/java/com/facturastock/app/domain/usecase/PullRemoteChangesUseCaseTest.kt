package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.RemoteCatalogChange
import com.facturastock.app.domain.model.RemoteCatalogEntityType
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CatalogApplicationOutcome
import com.facturastock.app.domain.repository.CatalogOutboxConflictResolution
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.RemoteCatalogApplicationRepository
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import com.facturastock.app.testing.FakeRemoteLedgerRepository
import com.facturastock.app.testing.FakeSyncCursorRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRemoteChangesUseCaseTest {
    private var now: Instant = Instant.parse("2026-08-16T12:00:00Z")
    private val clock = AppClock { now }
    private val remoteLedger = FakeRemoteLedgerRepository()
    private val cursors = FakeSyncCursorRepository()
    private val pullRemoteChanges = PullRemoteChangesUseCase(remoteLedger, cursors, cursors, clock)

    @Test
    fun `drena varias paginas y guarda solo el nextCursor entregado`() = runTest {
        // Página llena (PAGE_LIMIT=200) seguida de una corta: el drenado avanza por último seq.
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(1..200L), nextCursor = 200L, hasMore = true),
        )
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(201..250L), nextCursor = 250L, hasMore = false),
        )

        val result = pullRemoteChanges(BUSINESS_ID)

        val outcome = (result as DomainResult.Success).value
        assertEquals(250, outcome.pulledCount)
        assertEquals(250L, outcome.latestSeq)
        assertEquals(
            listOf(
                FakeRemoteLedgerRepository.PullCall(BUSINESS_ID, sinceSeq = 0L, limit = 200),
                FakeRemoteLedgerRepository.PullCall(BUSINESS_ID, sinceSeq = 200L, limit = 200),
            ),
            remoteLedger.pullCalls,
        )
        assertEquals(
            listOf(
                FakeSyncCursorRepository.SaveCall(BUSINESS_ID, seq = 200L, pulledAt = now),
                FakeSyncCursorRepository.SaveCall(BUSINESS_ID, seq = 250L, pulledAt = now),
            ),
            cursors.saveCalls,
        )
        assertEquals(SyncCursor(seq = 250L, lastPullAt = now), cursors.cursor(BUSINESS_ID))
    }

    @Test
    fun `pull sin cambios conserva el cursor solicitado`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = emptyList(), nextCursor = 0L, hasMore = false),
        )

        val result = pullRemoteChanges(BUSINESS_ID)

        assertEquals(
            DomainResult.Success(SyncPullOutcome(pulledCount = 0, latestSeq = 0L)),
            result,
        )
        assertEquals(
            listOf(FakeSyncCursorRepository.SaveCall(BUSINESS_ID, seq = 0L, pulledAt = now)),
            cursors.saveCalls,
        )
    }

    @Test
    fun `pagina vacia no puede adelantar cursor a una secuencia no entregada`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = emptyList(), nextCursor = 42L, hasMore = false),
        )

        val result = pullRemoteChanges(BUSINESS_ID)

        assertEquals(DomainResult.Failure(AccountError.Unexpected), result)
        assertTrue(cursors.saveCalls.isEmpty())
    }

    @Test
    fun `el drenado se reanuda desde el cursor guardado`() = runTest {
        cursors.seedCursor(BUSINESS_ID, seq = 100L, pulledAt = now.minusSeconds(3600))
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(101..130L), nextCursor = 130L, hasMore = false),
        )

        val result = pullRemoteChanges(BUSINESS_ID)

        assertEquals(
            DomainResult.Success(SyncPullOutcome(pulledCount = 30, latestSeq = 130L)),
            result,
        )
        assertEquals(
            FakeRemoteLedgerRepository.PullCall(BUSINESS_ID, sinceSeq = 100L, limit = 200),
            remoteLedger.pullCalls.single(),
        )
    }

    @Test
    fun `fake sin guion conserva un cursor no cero como el backend`() = runTest {
        cursors.seedCursor(BUSINESS_ID, seq = 100L, pulledAt = now.minusSeconds(3600))

        val result = pullRemoteChanges(BUSINESS_ID)

        assertEquals(
            DomainResult.Success(SyncPullOutcome(pulledCount = 0, latestSeq = 100L)),
            result,
        )
        assertEquals(100L, cursors.cursor(BUSINESS_ID)?.seq)
    }

    @Test
    fun `rechaza una pagina cuya primera secuencia ya fue consumida`() = runTest {
        cursors.seedCursor(BUSINESS_ID, seq = 10L, pulledAt = now.minusSeconds(3600))
        remoteLedger.enqueuePage(
            SyncPullPage(changes = listOf(change(10L)), nextCursor = 10L, hasMore = false),
        )

        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            pullRemoteChanges(BUSINESS_ID),
        )
        assertTrue(cursors.saveCalls.isEmpty())
    }

    @Test
    fun `rechaza un hueco de secuencia antes de acuñar completitud`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = listOf(change(2L)), nextCursor = 2L, hasMore = false),
        )

        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            pullRemoteChanges(BUSINESS_ID),
        )
        assertTrue(cursors.saveCalls.isEmpty())
        assertEquals(null, cursors.purchaseCacheCompletion(BUSINESS_ID))
    }

    @Test
    fun `fallo al invalidar recibo ocurre antes de consultar la red`() = runTest {
        cursors.nextBeginFailure = StorageError.InsufficientSpace

        assertEquals(
            DomainResult.Failure(StorageError.InsufficientSpace),
            pullRemoteChanges(BUSINESS_ID),
        )
        assertTrue(remoteLedger.pullCalls.isEmpty())
        assertTrue(cursors.saveCalls.isEmpty())
    }

    @Test
    fun `rechaza secuencias duplicadas o descendentes`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(
                changes = listOf(change(1L), change(1L)),
                nextCursor = 1L,
                hasMore = false,
            ),
        )
        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            pullRemoteChanges(BUSINESS_ID),
        )

        val anotherRemote = FakeRemoteLedgerRepository()
        val anotherCursors = FakeSyncCursorRepository()
        anotherRemote.enqueuePage(
            SyncPullPage(
                changes = listOf(change(2L), change(1L)),
                nextCursor = 1L,
                hasMore = false,
            ),
        )
        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            PullRemoteChangesUseCase(anotherRemote, anotherCursors, anotherCursors, clock)(
                BUSINESS_ID,
            ),
        )
        assertTrue(anotherCursors.saveCalls.isEmpty())
    }

    @Test
    fun `un fallo posterior conserva la primera pagina durable y reanuda desde ella`() = runTest {
        cursors.seedCursor(BUSINESS_ID, seq = 5L, pulledAt = now.minusSeconds(3600))
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(6..205L), nextCursor = 205L, hasMore = true),
        )
        remoteLedger.enqueueFailure(AccountError.NetworkUnavailable)

        val result = pullRemoteChanges(BUSINESS_ID)

        assertEquals(DomainResult.Failure(AccountError.NetworkUnavailable), result)
        assertEquals(listOf(205L), cursors.saveCalls.map { it.seq })
        assertEquals(205L, cursors.cursor(BUSINESS_ID)!!.seq)
    }

    @Test
    fun `un fallo al guardar el cursor se propaga tras drenar`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(1..3L), nextCursor = 3L, hasMore = false),
        )
        cursors.nextSaveFailure = StorageError.InsufficientSpace

        val result = pullRemoteChanges(BUSINESS_ID)

        assertEquals(DomainResult.Failure(StorageError.InsufficientSpace), result)
    }

    @Test
    fun `sin libro remoto disponible el pull es Unavailable y no lee ni guarda nada`() = runTest {
        remoteLedger.available = false

        val result = pullRemoteChanges(BUSINESS_ID)

        assertEquals(DomainResult.Failure(AccountError.Unavailable), result)
        assertTrue(remoteLedger.pullCalls.isEmpty())
        assertTrue(cursors.saveCalls.isEmpty())
    }

    @Test
    fun `tenant distinto al binding falla antes de red cursor cache o aplicacion`() = runTest {
        val remoteCatalog = FakeRemoteCatalogRepository()
        val applications = RecordingCatalogApplications()
        val useCase = PullRemoteChangesUseCase(
            remoteLedger = remoteLedger,
            cursors = cursors,
            cache = cursors,
            clock = clock,
            remoteCatalog = remoteCatalog,
            catalogApplications = applications,
            cloudBusinessBindings = RejectingCloudBusinessBindings,
        )

        assertEquals(
            DomainResult.Failure(AccountError.CloudBusinessAlreadyBound),
            useCase(LOCAL_BUSINESS_ID, BUSINESS_ID),
        )
        assertTrue(remoteLedger.pullCalls.isEmpty())
        assertTrue(cursors.saveCalls.isEmpty())
        assertTrue(cursors.catalogSaveCalls.isEmpty())
        assertTrue(remoteCatalog.calls.isEmpty())
        assertTrue(applications.calls.isEmpty())
    }

    @Test
    fun `cancelacion marcada al volver una pagina no avanza cursor ni pide otra pagina`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(1..200L), nextCursor = 200L, hasMore = true),
        )
        remoteLedger.beforePull = {
            currentCoroutineContext().cancel(CancellationException("pantalla cerrada"))
        }

        val attempt = async { pullRemoteChanges(BUSINESS_ID) }
        try {
            attempt.await()
            throw AssertionError("El pull cancelado no debe devolver un resultado")
        } catch (expected: CancellationException) {
            // El checkpoint posterior al fake evita consumir/procesar otra página.
        }

        assertEquals(1, remoteLedger.pullCalls.size)
        assertTrue(cursors.saveCalls.isEmpty())
        assertEquals(null, cursors.cursor(BUSINESS_ID))
    }

    @Test
    fun `catalog pull keys cache by cloud id and applies only to local id after persistence`() =
        runTest {
            remoteLedger.enqueuePage(SyncPullPage(emptyList(), 0, false))
            val remoteCatalog = FakeRemoteCatalogRepository().apply {
                pages += DomainResult.Success(
                    CatalogSyncPullPage(listOf(catalogChange(1)), 1, false),
                )
            }
            val applications = RecordingCatalogApplications()
            val useCase = PullRemoteChangesUseCase(
                remoteLedger = remoteLedger,
                cursors = cursors,
                cache = cursors,
                clock = clock,
                remoteCatalog = remoteCatalog,
                catalogApplications = applications,
            )

            val result = useCase(LOCAL_BUSINESS_ID, BUSINESS_ID) as DomainResult.Success

            assertEquals(1, result.value.catalogPulledCount)
            assertEquals(1, result.value.catalogAppliedCount)
            assertEquals(listOf(BUSINESS_ID), remoteCatalog.calls.map { it.businessId })
            assertEquals(listOf(BUSINESS_ID), cursors.catalogSaveCalls.map { it.businessId })
            assertEquals(
                listOf(RecordingCatalogApplications.Call(LOCAL_BUSINESS_ID, BUSINESS_ID)),
                applications.calls,
            )
        }

    @Test
    fun `invalid catalog page never advances cache or invokes local application`() = runTest {
        remoteLedger.enqueuePage(SyncPullPage(emptyList(), 0, false))
        cursors.seedCatalogCursor(BUSINESS_ID, 5)
        val remoteCatalog = FakeRemoteCatalogRepository().apply {
            pages += DomainResult.Success(
                CatalogSyncPullPage(listOf(catalogChange(5)), 5, false),
            )
        }
        val applications = RecordingCatalogApplications()
        val useCase = PullRemoteChangesUseCase(
            remoteLedger,
            cursors,
            cursors,
            clock,
            remoteCatalog,
            applications,
        )

        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            useCase(LOCAL_BUSINESS_ID, BUSINESS_ID),
        )
        assertTrue(cursors.catalogSaveCalls.isEmpty())
        assertTrue(applications.calls.isEmpty())
    }

    private fun changes(seqs: LongRange): List<RemotePurchaseChange> = seqs.map(::change)

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

    private fun catalogChange(seq: Long) = RemoteCatalogChange(
        seq = seq,
        entityType = RemoteCatalogEntityType.PRODUCT,
        remoteEntityId = "00000000-0000-4000-8000-%012d".format(seq),
        remoteVersion = seq,
        mutation = "UPSERT",
        snapshotPayload = "{}",
        snapshotSha256 = "0".repeat(64),
        receiptId = "catalog-$seq",
        syncedAt = now,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
        )
    }
}

internal object RejectingCloudBusinessBindings : CloudBusinessBindingRepository {
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

private class FakeRemoteCatalogRepository : RemoteCatalogRepository {
    data class Call(val businessId: BusinessId, val since: Long, val limit: Int)

    override val available: Boolean = true
    val pages = ArrayDeque<DomainResult<CatalogSyncPullPage>>()
    val calls = mutableListOf<Call>()

    override suspend fun pullCatalogChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<CatalogSyncPullPage> {
        calls += Call(businessId, sinceSeq, limit)
        return pages.removeFirstOrNull()
            ?: DomainResult.Success(CatalogSyncPullPage(emptyList(), sinceSeq, false))
    }
}

private class RecordingCatalogApplications : RemoteCatalogApplicationRepository {
    data class Call(val localBusinessId: BusinessId, val cloudBusinessId: BusinessId)

    val calls = mutableListOf<Call>()

    override suspend fun applyPending(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        appliedAt: Instant,
    ): DomainResult<CatalogApplicationOutcome> {
        calls += Call(localBusinessId, cloudBusinessId)
        return DomainResult.Success(CatalogApplicationOutcome(1, 0, 0))
    }

    override suspend fun resolveOutboxConflict(
        activeBusinessId: BusinessId,
        operation: OutboxOperationView,
        resolution: CatalogOutboxConflictResolution,
        actorId: String,
        resolvedAt: Instant,
    ): DomainResult<Boolean> = DomainResult.Success(false)
}
