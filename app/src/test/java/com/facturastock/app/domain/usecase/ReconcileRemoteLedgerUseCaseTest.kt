package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.LocalLedgerSnapshot
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.PurchaseDocumentIdentity
import com.facturastock.app.domain.model.reconcileRemoteLedger
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.testing.FakeRemoteLedgerRepository
import com.facturastock.app.testing.FakeSyncCursorRepository
import com.facturastock.app.testing.FakeSyncReconciliationRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcileRemoteLedgerUseCaseTest {
    private var now: Instant = Instant.parse("2026-08-16T12:00:00Z")
    private val clock = AppClock { now }
    private val remoteLedger = FakeRemoteLedgerRepository()
    private val cache = FakeSyncCursorRepository()
    private val reconciliation = FakeSyncReconciliationRepository()
    private val reconcile = ReconcileRemoteLedgerUseCase(cache, reconciliation, clock)

    @Test
    fun `agrega varias paginas desde seq 0 y reconcilia contra la instantanea local`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(1..200L), nextCursor = 200L, hasMore = true),
        )
        remoteLedger.enqueuePage(
            SyncPullPage(
                changes = listOf(
                    change(201L, purchaseId = "remote-match"),
                    change(202L, purchaseId = "remote-only"),
                ),
                nextCursor = 202L,
                hasMore = false,
            ),
        )
        // La compra de seq 201 existe en el libro local por su identidad documental normalizada.
        val matchedIdentity = requireNotNull(
            PurchaseDocumentIdentity.normalized("20123456789", "INVOICE", "F001", "00000201"),
        )
        reconciliation.snapshot = LocalLedgerSnapshot(
            localDocuments = mapOf(matchedIdentity to listOf(LOCAL_PURCHASE_ID)),
            balances = emptyList(),
            products = emptyList(),
        )

        assertTrue(
            PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID) is
                DomainResult.Success,
        )
        assertEquals(
            202L,
            cache.purchaseCacheCompletion(CLOUD_BUSINESS_ID)?.completeThroughSeq,
        )
        val result = reconcile(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)

        val report = (result as DomainResult.Success).value
        // El pull comenzó en 0 y el reporte solo consume el espejo tras la página terminal.
        assertEquals(
            listOf(
                FakeRemoteLedgerRepository.PullCall(CLOUD_BUSINESS_ID, sinceSeq = 0L, limit = 200),
                FakeRemoteLedgerRepository.PullCall(CLOUD_BUSINESS_ID, sinceSeq = 200L, limit = 200),
            ),
            remoteLedger.pullCalls,
        )
        assertEquals(listOf(LOCAL_BUSINESS_ID), reconciliation.loadCalls)
        assertEquals(LOCAL_BUSINESS_ID, report.businessId)
        assertEquals(202L, report.latestSeq)
        assertEquals(now, report.generatedAt)
        assertEquals(LOCAL_PURCHASE_ID, report.matched.single().localPurchaseId)
        assertEquals("remote-match", report.matched.single().remote.purchaseId)
        assertEquals(201, report.remoteOnly.size)
        assertTrue(report.remoteOnly.any { it.purchaseId == "remote-only" })
    }

    @Test
    fun `pull falla cerrado tras 10000 cambios sin emitir reporte parcial`() = runTest {
        repeat(510) { page ->
            // listChanges aplica internamente una ventana máxima de 20 aunque el cliente pida 200.
            val first = page * 20L + 1
            remoteLedger.enqueuePage(
                SyncPullPage(
                    changes = changes(first until first + 20L),
                    nextCursor = first + 19L,
                    hasMore = true,
                ),
            )
        }

        val result = PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID)

        assertEquals(DomainResult.Failure(AccountError.Unexpected), result)
        assertEquals(500, remoteLedger.pullCalls.size)
        assertEquals(9_980L, remoteLedger.pullCalls.last().sinceSeq)
        assertEquals(null, cache.purchaseCacheCompletion(CLOUD_BUSINESS_ID))
        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            reconcile(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID),
        )
        assertTrue(reconciliation.loadCalls.isEmpty())
    }

    @Test
    fun `un fallo de pull se propaga sin cargar la instantanea local`() = runTest {
        remoteLedger.enqueueFailure(AccountError.SessionExpired)

        val result = PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID)

        assertEquals(DomainResult.Failure(AccountError.SessionExpired), result)
        assertEquals(null, cache.purchaseCacheCompletion(CLOUD_BUSINESS_ID))
        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            reconcile(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID),
        )
        assertTrue(reconciliation.loadCalls.isEmpty())
    }

    @Test
    fun `cache completa sigue siendo explicable sin red`() = runTest {
        remoteLedger.enqueuePage(SyncPullPage(emptyList(), nextCursor = 0L, hasMore = false))
        assertTrue(
            PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID) is
                DomainResult.Success,
        )
        remoteLedger.available = false

        val result = reconcile(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)

        assertTrue(result is DomainResult.Success)
        assertEquals(1, remoteLedger.pullCalls.size)
        assertEquals(listOf(LOCAL_BUSINESS_ID), reconciliation.loadCalls)
    }

    @Test
    fun `fallo entre paginas conserva prefijo durable pero bloquea todo diagnostico`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(1..200L), nextCursor = 200L, hasMore = true),
        )
        remoteLedger.enqueueFailure(AccountError.NetworkUnavailable)

        assertEquals(
            DomainResult.Failure(AccountError.NetworkUnavailable),
            PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID),
        )
        assertEquals(200, cache.listPurchaseChanges(CLOUD_BUSINESS_ID).size)
        assertEquals(null, cache.purchaseCacheCompletion(CLOUD_BUSINESS_ID))

        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            reconcile(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID),
        )
        assertTrue(reconciliation.loadCalls.isEmpty())
    }

    @Test
    fun `nuevo intento fallido invalida un recibo completo anterior`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = listOf(change(1L)), nextCursor = 1L, hasMore = false),
        )
        assertTrue(
            PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID) is
                DomainResult.Success,
        )
        assertEquals(1L, cache.purchaseCacheCompletion(CLOUD_BUSINESS_ID)?.completeThroughSeq)

        remoteLedger.enqueueFailure(AccountError.NetworkUnavailable)
        assertEquals(
            DomainResult.Failure(AccountError.NetworkUnavailable),
            PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID),
        )

        assertEquals(null, cache.purchaseCacheCompletion(CLOUD_BUSINESS_ID))
        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            reconcile(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID),
        )
        assertTrue(reconciliation.loadCalls.isEmpty())
    }

    @Test
    fun `recibo inconsistente con filas de cache falla antes de leer libro local`() = runTest {
        cache.seedPurchaseChanges(CLOUD_BUSINESS_ID, changes(1..2L))
        cache.seedPurchaseCompletion(CLOUD_BUSINESS_ID, completeThroughSeq = 3L, completedAt = now)

        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            reconcile(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID),
        )
        assertTrue(reconciliation.loadCalls.isEmpty())
    }

    @Test
    fun `tenant distinto al binding no lee cache ni libro local`() = runTest {
        val guarded = ReconcileRemoteLedgerUseCase(
            cache = cache,
            reconciliation = reconciliation,
            clock = clock,
            cloudBusinessBindings = RejectingCloudBusinessBindings,
        )

        assertEquals(
            DomainResult.Failure(AccountError.CloudBusinessAlreadyBound),
            guarded(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID),
        )
        assertTrue(reconciliation.loadCalls.isEmpty())
    }

    @Test
    fun `cancelacion al devolver una pagina evita persistirla y leer la copia local`() = runTest {
        remoteLedger.enqueuePage(
            SyncPullPage(changes = changes(1..200L), nextCursor = 200L, hasMore = false),
        )
        remoteLedger.beforePull = {
            currentCoroutineContext().cancel(CancellationException("usuario salió"))
        }

        val attempt = async {
            PullRemoteChangesUseCase(remoteLedger, cache, cache, clock)(CLOUD_BUSINESS_ID)
        }
        try {
            attempt.await()
            throw AssertionError("La reconciliación cancelada no debe devolver reporte")
        } catch (expected: CancellationException) {
            // No se transforma en Failure ni se construye un reporte parcial.
        }

        assertEquals(1, remoteLedger.pullCalls.size)
        assertTrue(reconciliation.loadCalls.isEmpty())
    }

    @Test
    fun `seeded ledger histories are order independent and keep only the greatest seq`() {
        val random = Random(0x4C_45_44_47)
        val emptyLocal = LocalLedgerSnapshot(
            localDocuments = emptyMap(),
            balances = emptyList(),
            products = emptyList(),
        )

        repeat(1_000) { sample ->
            var seq = 0L
            val history = buildList {
                repeat(random.nextInt(from = 1, until = 31)) { purchaseIndex ->
                    repeat(random.nextInt(from = 1, until = 5)) {
                        seq++
                        add(
                            change(
                                seq = seq,
                                purchaseId = "remote-$sample-$purchaseIndex",
                            ).copy(
                                status = if (random.nextBoolean()) {
                                    PurchaseStatus.POSTED
                                } else {
                                    PurchaseStatus.VOIDED
                                },
                            ),
                        )
                    }
                }
            }
            val expectedLatest = history
                .groupBy { it.purchaseId }
                .values
                .map { changes -> changes.maxBy { it.seq } }
                .sortedBy { it.seq }
            val ordered = reconcileRemoteLedger(
                LOCAL_BUSINESS_ID,
                history,
                emptyLocal,
                now,
            )
            val shuffled = reconcileRemoteLedger(
                LOCAL_BUSINESS_ID,
                history.shuffled(random),
                emptyLocal,
                now,
            )

            assertEquals("seed=0x4C454447 sample=$sample", expectedLatest, ordered.remoteOnly)
            assertEquals("seed=0x4C454447 sample=$sample", seq, ordered.latestSeq)
            assertEquals("seed=0x4C454447 sample=$sample", ordered, shuffled)
        }
    }

    private fun changes(seqs: LongRange): List<RemotePurchaseChange> = seqs.map(::change)

    private fun change(seq: Long, purchaseId: String = "remote-%06d".format(seq)): RemotePurchaseChange =
        RemotePurchaseChange(
            seq = seq,
            purchaseId = purchaseId,
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

    private companion object {
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
        )
        val LOCAL_PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("10000000-0000-0000-0000-0000000000c9"),
        )
    }
}
