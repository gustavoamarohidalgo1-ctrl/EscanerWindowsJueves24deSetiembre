package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.BackupBackoffPolicy
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.DocumentUploadSource
import com.facturastock.app.domain.repository.PendingBackupOperation
import com.facturastock.app.domain.repository.PreparedDocumentUpload
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.testing.FakeFirebaseConnectivity
import com.facturastock.app.testing.FakePurchaseBackupOutboxRepository
import com.facturastock.app.testing.FakePurchaseBackupTransport
import com.facturastock.app.testing.RecordingProductionObservability
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProcessPurchaseBackupOutboxUseCaseTest {
    private var now: Instant = Instant.parse("2026-08-08T12:00:00Z")
    private val clock = AppClock { now }
    private val outbox = FakePurchaseBackupOutboxRepository()
    private val firebaseConnectivity = FakeFirebaseConnectivity()
    private val transport = FakePurchaseBackupTransport(connectivity = firebaseConnectivity)
    private var tokenCounter = 0
    private val uuidGenerator = UuidGenerator {
        UUID.fromString("00000000-0000-0000-0000-${"%012d".format(++tokenCounter)}")
    }
    private val observability = RecordingProductionObservability()
    private val processor = ProcessPurchaseBackupOutboxUseCase(
        outbox = outbox,
        transport = transport,
        clock = clock,
        uuidGenerator = uuidGenerator,
        observability = observability,
    )

    @Test
    fun `sin transporte configurado la cola queda intacta y nada se envia`() = runTest {
        transport.configured = false
        outbox.insert(envelope(1))

        val result = processor()

        assertEquals(OutboxPassResult.TransportUnavailable, result)
        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, row.status)
        assertEquals(0, row.attemptCount)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `adapter que devuelve otro tenant no reclama ni envia la fila`() = runTest {
        outbox.insert(envelope(1))
        val otherTenant = requireNotNull(
            BusinessId.parse("00000000-0000-4000-8000-0000000000c2"),
        )
        var claimCalls = 0
        val corruptAdapter = object : PurchaseBackupOutboxRepository by outbox {
            override suspend fun listReady(
                now: Instant,
                limit: Int,
                onlyDocumentPurges: Boolean,
                targetCloudBusinessId: BusinessId?,
            ): List<PendingBackupOperation> = outbox.listReady(
                now = now,
                limit = limit,
                onlyDocumentPurges = onlyDocumentPurges,
                targetCloudBusinessId = targetCloudBusinessId,
            ).map { pending ->
                pending.copy(
                    envelope = pending.envelope.copy(targetCloudBusinessId = otherTenant),
                )
            }

            override suspend fun claim(
                operationId: String,
                claimToken: String,
                claimedAt: Instant,
                leaseUntil: Instant,
                targetCloudBusinessId: BusinessId?,
            ): Boolean {
                claimCalls++
                return outbox.claim(
                    operationId = operationId,
                    claimToken = claimToken,
                    claimedAt = claimedAt,
                    leaseUntil = leaseUntil,
                    targetCloudBusinessId = targetCloudBusinessId,
                )
            }
        }
        val guardedProcessor = ProcessPurchaseBackupOutboxUseCase(
            outbox = corruptAdapter,
            transport = transport,
            clock = clock,
            uuidGenerator = uuidGenerator,
        )

        val result = guardedProcessor(
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
        ) as OutboxPassResult.Drained

        assertEquals(0, result.processed)
        assertEquals(false, result.reachedBatchLimit)
        assertEquals(0, claimCalls)
        assertTrue(transport.sent.isEmpty())
        assertEquals(0, outbox.row(operationId(1))?.attemptCount)
    }

    @Test
    fun `acuse valido marca COMPLETED y una segunda pasada no reenvia`() = runTest {
        outbox.insert(envelope(1))

        val first = processor() as OutboxPassResult.Drained

        assertEquals(1, first.completed)
        assertEquals(1, first.processed)
        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.COMPLETED, row.status)
        assertEquals(now, row.completedAt)
        assertNull(row.claimToken)
        assertNull(row.nextAttemptAt)

        val second = processor() as OutboxPassResult.Drained
        assertEquals(0, second.processed)
        assertEquals(1, transport.sent.size)
        val audit = observability.records.single().event
        assertEquals(OperationalAction.BACKUP_SYNC, audit.action)
        assertEquals(OperationalOutcome.SUCCEEDED, audit.outcome)
        assertEquals(BUSINESS_ID, audit.identifiers.businessId)
        assertEquals(envelope(1).purchaseId, audit.identifiers.purchaseId)
        assertEquals(operationId(1), audit.identifiers.operationId)
    }

    @Test
    fun `upload documental conserva derivado hasta que el ACK queda COMPLETED en Room`() = runTest {
        val purchase = envelope(1)
        outbox.insert(purchase)
        processor()
        val upload = documentUploadEnvelope(2, purchase)
        outbox.insert(upload)
        val artifacts = RecordingDocumentUploadPreparer()
        val documentProcessor = processorWith(
            outboxRepository = outbox,
            documentUploadPreparer = artifacts,
        )

        val result = documentProcessor() as OutboxPassResult.Drained

        assertEquals(1, result.completed)
        assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(upload.operationId)?.status)
        assertEquals(listOf(upload.entityId), artifacts.discarded)
    }

    @Test
    fun `ACK sin ganar CAS local conserva derivado reproducible para el reintento`() = runTest {
        val purchase = envelope(1)
        outbox.insert(purchase)
        processor()
        val upload = documentUploadEnvelope(2, purchase)
        outbox.insert(upload)
        val artifacts = RecordingDocumentUploadPreparer()
        val losingComplete = object : PurchaseBackupOutboxRepository by outbox {
            override suspend fun complete(
                operationId: String,
                claimToken: String,
                completedAt: Instant,
            ): Boolean = false
        }
        val documentProcessor = processorWith(
            outboxRepository = losingComplete,
            documentUploadPreparer = artifacts,
        )

        val result = documentProcessor() as OutboxPassResult.Drained

        assertEquals(0, result.completed)
        assertTrue(artifacts.discarded.isEmpty())
        assertEquals(OutboxOperationStatus.PROCESSING, outbox.row(upload.operationId)?.status)
    }

    @Test
    fun `fallo de limpieza posterior al CAS no revierte COMPLETED`() = runTest {
        val purchase = envelope(1)
        outbox.insert(purchase)
        processor()
        val upload = documentUploadEnvelope(2, purchase)
        outbox.insert(upload)
        val artifacts = RecordingDocumentUploadPreparer().apply {
            discardFailure = IllegalStateException("filesystem unavailable")
        }

        val result = processorWith(
            outboxRepository = outbox,
            documentUploadPreparer = artifacts,
        )() as OutboxPassResult.Drained

        assertEquals(1, result.completed)
        assertEquals(listOf(upload.entityId), artifacts.discarded)
        assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(upload.operationId)?.status)
    }

    @Test
    fun `cancelacion justo tras CAS aun intenta limpiar el derivado`() = runTest {
        val purchase = envelope(1)
        outbox.insert(purchase)
        processor()
        val upload = documentUploadEnvelope(2, purchase)
        outbox.insert(upload)
        val artifacts = RecordingDocumentUploadPreparer()
        val cancellingComplete = object : PurchaseBackupOutboxRepository by outbox {
            override suspend fun complete(
                operationId: String,
                claimToken: String,
                completedAt: Instant,
            ): Boolean {
                val completed = outbox.complete(operationId, claimToken, completedAt)
                currentCoroutineContext().cancel(
                    CancellationException("cancelled after durable complete"),
                )
                return completed
            }
        }

        val attempt = async {
            processorWith(
                outboxRepository = cancellingComplete,
                documentUploadPreparer = artifacts,
            )()
        }
        try {
            attempt.await()
            fail("La cancelación posterior al CAS debió propagarse")
        } catch (_: CancellationException) {
            // La limpieza NonCancellable ya se ejecutó antes de propagar al padre.
        }

        assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(upload.operationId)?.status)
        assertEquals(listOf(upload.entityId), artifacts.discarded)
    }

    @Test
    fun `un acuse con clave idempotente distinta nunca confirma el respaldo`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = { BackupTransportResult.Acknowledged("receipt-1", "sync-purchase:v1:otra") }

        val result = processor() as OutboxPassResult.Drained

        assertEquals(1, result.permanentlyFailed)
        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.FAILED, row.status)
        assertEquals(ProcessPurchaseBackupOutboxUseCase.ERROR_INVALID_ACK, row.lastError)
        assertNull(row.completedAt)
        assertNull(row.nextAttemptAt)
    }

    @Test
    fun `5xx reintenta con backoff exponencial y al quinto intento queda FAILED manual`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = { BackupTransportResult.TransientFailure("HTTP_503") }

        val first = processor() as OutboxPassResult.Drained
        assertEquals(1, first.scheduledRetries)
        var row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, row.status)
        assertEquals(1, row.attemptCount)
        assertEquals(now.plusMillis(30_000L), row.nextAttemptAt)
        assertEquals("HTTP_503", row.lastError)
        assertEquals(now.plusMillis(30_000L), first.nextWakeupAt)
        assertEquals(
            OperationalErrorCode.BACKEND_5XX,
            observability.records.single().event.errorCode,
        )

        now = now.plusMillis(30_000L)
        processor()
        row = outbox.row(operationId(1))!!
        assertEquals(2, row.attemptCount)
        assertEquals(now.plusMillis(60_000L), row.nextAttemptAt)

        now = now.plusMillis(60_000L)
        processor()
        now = now.plusMillis(120_000L)
        processor()
        row = outbox.row(operationId(1))!!
        assertEquals(4, row.attemptCount)
        assertEquals(now.plusMillis(240_000L), row.nextAttemptAt)

        now = now.plusMillis(240_000L)
        val fifth = processor() as OutboxPassResult.Drained
        assertEquals(1, fifth.permanentlyFailed)
        row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.FAILED, row.status)
        assertEquals(5, row.attemptCount)
        assertNull(row.nextAttemptAt)

        // FAILED ya no es reclamable: solo el reintento manual la devuelve a la cola.
        val sixth = processor() as OutboxPassResult.Drained
        assertEquals(0, sixth.processed)
        assertEquals(5, transport.sent.size)
    }

    @Test
    fun `alta transitoria bloquea su void durablemente sin frenar compras independientes`() =
        runTest {
            val purchase = envelope(1)
            val void = voidEnvelope(operationIndex = 2, purchase = purchase)
            val independentPurchase = envelope(3)
            outbox.insert(purchase)
            // Se inserta antes que la compra independiente para demostrar que una dependencia
            // bloqueada no consume el límite de selección ni produce head-of-line blocking.
            outbox.insert(void)
            outbox.insert(independentPurchase)
            var firstPurchaseAttempt = true
            transport.onSend = { sent ->
                if (sent.operationId == purchase.operationId && firstPurchaseAttempt) {
                    firstPurchaseAttempt = false
                    BackupTransportResult.TransientFailure("HTTP_503")
                } else {
                    BackupTransportResult.Acknowledged("receipt-${sent.operationId}", sent.idempotencyKey)
                }
            }

            val first = processor(batchLimit = 2) as OutboxPassResult.Drained

            assertEquals(1, first.scheduledRetries)
            assertEquals(1, first.completed)
            assertTrue(first.reachedBatchLimit)
            assertEquals(
                listOf(purchase.operationId, independentPurchase.operationId),
                transport.sent.map { it.operationId },
            )
            assertEquals(OutboxOperationStatus.PENDING, outbox.row(void.operationId)!!.status)
            assertEquals(0, outbox.row(void.operationId)!!.attemptCount)
            assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(independentPurchase.operationId)!!.status)

            // Una pasada futura antes del backoff tampoco puede reclamar ni enviar el void.
            val beforeBackoff = processor(batchLimit = 2) as OutboxPassResult.Drained
            assertEquals(0, beforeBackoff.processed)
            assertEquals(2, transport.sent.size)
            assertEquals(0, outbox.row(void.operationId)!!.attemptCount)

            now = requireNotNull(outbox.row(purchase.operationId)!!.nextAttemptAt)
            val postRetry = processor(batchLimit = 2) as OutboxPassResult.Drained

            assertEquals(1, postRetry.completed)
            assertTrue(postRetry.reachedBatchLimit)
            assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(purchase.operationId)!!.status)
            // La consulta posterior detecta que el alta recién completada desbloqueó trabajo,
            // pero esa anulación aún no fue reclamada en el snapshot anterior.
            assertEquals(3, transport.sent.size)
            assertEquals(0, outbox.row(void.operationId)!!.attemptCount)

            val voidPass = processor(batchLimit = 2) as OutboxPassResult.Drained

            assertEquals(1, voidPass.completed)
            assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(void.operationId)!!.status)
            assertEquals(
                listOf(
                    purchase.operationId,
                    independentPurchase.operationId,
                    purchase.operationId,
                    void.operationId,
                ),
                transport.sent.map { it.operationId },
            )
        }

    @Test
    fun `modo avion programa reintento y reconexion conserva la clave idempotente`() =
        runTest {
            val expected = envelope(1)
            outbox.insert(expected)
            firebaseConnectivity.disconnect()

            val offline = processor() as OutboxPassResult.Drained

            assertEquals(1, offline.scheduledRetries)
            var row = outbox.row(expected.operationId)!!
            assertEquals(OutboxOperationStatus.PENDING, row.status)
            assertEquals("NETWORK_UNAVAILABLE", row.lastError)
            assertEquals(now.plusMillis(30_000L), row.nextAttemptAt)
            assertEquals(listOf(expected), transport.sent)

            now = row.nextAttemptAt!!
            firebaseConnectivity.reconnect()
            val reconnected = processor() as OutboxPassResult.Drained

            assertEquals(1, reconnected.completed)
            row = outbox.row(expected.operationId)!!
            assertEquals(OutboxOperationStatus.COMPLETED, row.status)
            assertEquals(2, row.attemptCount)
            assertNull(row.lastError)
            assertEquals(listOf(expected, expected), transport.sent)
            assertEquals(1, transport.sent.map { it.idempotencyKey }.distinct().size)
        }

    @Test
    fun `una razon no sanitaria se sustituye por el codigo cerrado de su clase`() = runTest {
        outbox.insert(envelope(1))
        val sensitiveReason = "502 Bad Gateway: <html>nginx</html> S/ 1,234.56"
        transport.onSend = {
            BackupTransportResult.TransientFailure(sensitiveReason)
        }

        processor()

        assertEquals(
            ProcessPurchaseBackupOutboxUseCase.ERROR_TRANSIENT,
            outbox.row(operationId(1))!!.lastError,
        )
        val audit = observability.records.single().event
        assertEquals(OperationalAction.BACKUP_SYNC, audit.action)
        assertEquals(OperationalOutcome.RETRY_SCHEDULED, audit.outcome)
        assertEquals(OperationalErrorCode.TRANSIENT_FAILURE, audit.errorCode)
        assertEquals(operationId(1), audit.identifiers.operationId)
        assertEquals(false, audit.toString().contains(sensitiveReason))
    }

    @Test
    fun `429 se agrega como rate limit sin filtrar detalles del transporte`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = {
            BackupTransportResult.TransientFailure("RESOURCE_EXHAUSTED")
        }

        processor()

        val audit = observability.records.single().event
        assertEquals(OperationalOutcome.RETRY_SCHEDULED, audit.outcome)
        assertEquals(OperationalErrorCode.BACKEND_RATE_LIMITED, audit.errorCode)
        assertEquals("RESOURCE_EXHAUSTED", outbox.row(operationId(1))!!.lastError)
    }

    @Test
    fun `fallo permanente queda FAILED sin proxima ejecucion`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = { BackupTransportResult.PermanentFailure("HTTP_422") }

        val result = processor() as OutboxPassResult.Drained

        assertEquals(1, result.permanentlyFailed)
        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.FAILED, row.status)
        assertEquals("HTTP_422", row.lastError)
        assertNull(row.nextAttemptAt)
        assertEquals(0, (processor() as OutboxPassResult.Drained).processed)
    }

    @Test
    fun `conflicto queda CONFLICT y no reintenta por si solo`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = { BackupTransportResult.Conflict("HTTP_409") }

        val result = processor() as OutboxPassResult.Drained

        assertEquals(1, result.conflicts)
        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.CONFLICT, row.status)
        assertEquals("HTTP_409", row.lastError)
        assertNull(row.nextAttemptAt)
        assertNull(row.conflictRemotePurchaseId)
        assertNull(row.conflictReceiptId)
        assertEquals(0, (processor() as OutboxPassResult.Drained).processed)
        val audit = observability.records.single().event
        assertEquals(OperationalAction.BACKUP_SYNC, audit.action)
        assertEquals(OperationalOutcome.CONFLICT, audit.outcome)
        assertEquals(OperationalErrorCode.INTEGRITY_CONFLICT, audit.errorCode)
        assertEquals(false, audit.toString().contains("HTTP_409"))
        assertEquals(operationId(1), audit.identifiers.operationId)
    }

    @Test
    fun `conflicto con identidad remota conserva los IDs del registro de la nube`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = {
            BackupTransportResult.Conflict(
                reason = "ALREADY_EXISTS",
                remotePurchaseId = "20000000-0000-4000-8000-0000000000c1",
                remoteReceiptId = "receipt-remoto-1",
            )
        }

        val result = processor() as OutboxPassResult.Drained

        assertEquals(1, result.conflicts)
        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.CONFLICT, row.status)
        assertEquals("ALREADY_EXISTS", row.lastError)
        assertEquals("20000000-0000-4000-8000-0000000000c1", row.conflictRemotePurchaseId)
        assertEquals("receipt-remoto-1", row.conflictReceiptId)
    }

    @Test
    fun `dos procesadores concurrentes nunca envian la misma operacion dos veces`() = runTest {
        repeat(10) { index -> outbox.insert(envelope(index + 1)) }
        transport.onSend = { envelope ->
            // Fuerza el intercalado: sin el CAS de claim ambos procesadores enviarían todo.
            yield()
            BackupTransportResult.Acknowledged("receipt-${envelope.operationId}", envelope.idempotencyKey)
        }
        val secondProcessor = ProcessPurchaseBackupOutboxUseCase(
            outbox = outbox,
            transport = transport,
            clock = clock,
            uuidGenerator = uuidGenerator,
        )

        kotlinx.coroutines.coroutineScope {
            launch { processor() }
            launch { secondProcessor() }
        }

        val sentIds = transport.sent.map { it.operationId }
        assertEquals(10, sentIds.size)
        assertEquals(10, sentIds.distinct().size)
        (1..10).forEach { index ->
            assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(operationId(index))!!.status)
        }
    }

    @Test
    fun `lease vigente se respeta y lease vencido se recupera en caliente`() = runTest {
        outbox.insert(envelope(1))
        val leaseUntil = now.plusMillis(BackupBackoffPolicy.CLAIM_LEASE_MILLIS)
        assertTrue(outbox.claim(operationId(1), "token-muerto", now, leaseUntil))

        // El lease sigue vigente: la pasada no toca el claim, pero pide despertar al vencer.
        val respected = processor() as OutboxPassResult.Drained
        assertEquals(0, respected.processed)
        assertEquals(leaseUntil, respected.nextWakeupAt)
        var row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PROCESSING, row.status)
        assertEquals("token-muerto", row.claimToken)

        // Lease vencido: la recuperación en caliente lo devuelve a PENDING y se completa.
        now = leaseUntil.plusMillis(1L)
        val recovered = processor() as OutboxPassResult.Drained
        assertEquals(1, recovered.completed)
        row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.COMPLETED, row.status)
        assertNull(row.claimToken)
        // El intento del claim muerto se conservó: la recuperación nunca reinicia el contador.
        assertEquals(2, row.attemptCount)
    }

    @Test
    fun `transporte no disponible a mitad de pasada libera el claim sin consumir intento`() = runTest {
        outbox.insert(envelope(1))
        outbox.insert(envelope(2))
        transport.onSend = { BackupTransportResult.Unavailable }

        val result = processor() as OutboxPassResult.Drained

        assertEquals(1, result.released)
        assertEquals(1, result.processed)
        val released = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, released.status)
        assertEquals(0, released.attemptCount)
        assertNull(released.claimToken)
        val notAttempted = outbox.row(operationId(2))!!
        assertEquals(OutboxOperationStatus.PENDING, notAttempted.status)
        assertEquals(0, notAttempted.attemptCount)
        assertNull(notAttempted.claimToken)
        assertEquals(listOf(envelope(1)), transport.sent)
    }

    @Test
    fun `desactivar respaldo entre operaciones deja el resto pendiente sin reclamar`() = runTest {
        outbox.insert(envelope(1))
        outbox.insert(envelope(2))
        var gateChecks = 0

        val result = processor(
            processingAllowed = { ++gateChecks == 1 },
        ) as OutboxPassResult.Drained

        assertEquals(1, result.completed)
        assertEquals(false, result.reachedBatchLimit)
        assertEquals(listOf(envelope(1)), transport.sent)
        assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(operationId(1))?.status)
        val untouched = outbox.row(operationId(2))!!
        assertEquals(OutboxOperationStatus.PENDING, untouched.status)
        assertEquals(0, untouched.attemptCount)
        assertNull(untouched.claimToken)
    }

    @Test
    fun `cambio de tenant tras claim libera intento y detiene la pasada sin enviar`() = runTest {
        outbox.insert(envelope(1))
        outbox.insert(envelope(2))
        var targetChecks = 0

        val result = processor(
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            // La primera lectura precede al CAS; la segunda simula el cambio de sesión en
            // esa ventana estrecha, antes de que empiece cualquier IO remoto.
            targetAvailable = { ++targetChecks == 1 },
        ) as OutboxPassResult.Drained

        assertEquals(true, result.targetTemporarilyUnavailable)
        assertEquals(1, result.released)
        assertEquals(1, result.processed)
        assertEquals(false, result.reachedBatchLimit)
        assertTrue(transport.sent.isEmpty())
        val released = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, released.status)
        assertEquals(0, released.attemptCount)
        assertNull(released.claimToken)
        val untouched = outbox.row(operationId(2))!!
        assertEquals(OutboxOperationStatus.PENDING, untouched.status)
        assertEquals(0, untouched.attemptCount)
        assertNull(untouched.claimToken)
    }

    @Test
    fun `fallo al releer tenant tras claim libera la fila antes de propagar`() = runTest {
        outbox.insert(envelope(1))
        var targetChecks = 0

        try {
            processor(
                targetCloudBusinessId = CLOUD_BUSINESS_ID,
                targetAvailable = {
                    if (++targetChecks == 1) true else error("Room no disponible")
                },
            )
            fail("La indisponibilidad de Room debió propagarse para reintento del worker")
        } catch (expected: IllegalStateException) {
            assertEquals("Room no disponible", expected.message)
        }

        val released = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, released.status)
        assertEquals(0, released.attemptCount)
        assertNull(released.claimToken)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `cambio de sesión dentro del transporte no convierte un 4xx en FAILED`() = runTest {
        outbox.insert(envelope(1))
        var targetStillAvailable = true
        transport.onSend = {
            targetStillAvailable = false
            BackupTransportResult.PermanentFailure("PERMISSION_DENIED")
        }

        val result = processor(
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            targetAvailable = { targetStillAvailable },
        ) as OutboxPassResult.Drained

        assertEquals(true, result.targetTemporarilyUnavailable)
        assertEquals(1, result.released)
        assertEquals(0, result.permanentlyFailed)
        val released = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, released.status)
        assertEquals(0, released.attemptCount)
        assertNull(released.lastError)
        assertNull(released.claimToken)
        assertEquals(listOf(envelope(1)), transport.sent)
    }

    @Test
    fun `acuse invalido durante cambio de sesión se reevalua sin fijar FAILED`() = runTest {
        outbox.insert(envelope(1))
        var targetStillAvailable = true
        transport.onSend = {
            targetStillAvailable = false
            BackupTransportResult.Acknowledged("receipt-ajeno", "clave-ajena")
        }

        val result = processor(
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            targetAvailable = { targetStillAvailable },
        ) as OutboxPassResult.Drained

        assertEquals(1, result.released)
        assertEquals(0, result.permanentlyFailed)
        val released = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, released.status)
        assertEquals(0, released.attemptCount)
        assertNull(released.lastError)
    }

    @Test
    fun `canal de privacidad procesa solo purge y deja compras y uploads pendientes`() = runTest {
        val prerequisite = envelope(1)
        outbox.insert(prerequisite)
        processor()
        val pendingPurchase = envelope(2)
        val pendingUpload = documentUploadEnvelope(3, prerequisite)
        val pendingPurge = documentPurgeEnvelope(4, prerequisite)
        outbox.insert(pendingPurchase)
        outbox.insert(pendingUpload)
        outbox.insert(pendingPurge)

        val result = processor(onlyDocumentPurges = true) as OutboxPassResult.Drained

        assertEquals(1, result.completed)
        assertEquals(
            listOf(prerequisite.operationId, pendingPurge.operationId),
            transport.sent.map { it.operationId },
        )
        assertEquals(
            OutboxOperationStatus.COMPLETED,
            outbox.row(pendingPurge.operationId)?.status,
        )
        assertEquals(
            OutboxOperationStatus.PENDING,
            outbox.row(pendingPurchase.operationId)?.status,
        )
        assertEquals(
            OutboxOperationStatus.PENDING,
            outbox.row(pendingUpload.operationId)?.status,
        )
    }

    @Test
    fun `denegacion permanente de un tenant no bloquea la purga autorizada del siguiente`() =
        runTest {
            val purchaseX = envelope(1)
            val purchaseY = envelope(2).copy(targetCloudBusinessId = OTHER_CLOUD_BUSINESS_ID)
            outbox.insert(purchaseX)
            outbox.insert(purchaseY)
            processor()
            transport.sent.clear()
            val denied = documentPurgeEnvelope(3, purchaseX)
            val allowed = documentPurgeEnvelope(4, purchaseY)
            outbox.insert(denied)
            outbox.insert(allowed)
            transport.onSend = { pending ->
                if (pending.targetCloudBusinessId == CLOUD_BUSINESS_ID) {
                    BackupTransportResult.PermanentFailure("PERMISSION_DENIED")
                } else {
                    BackupTransportResult.Acknowledged(
                        "receipt-${pending.operationId}",
                        pending.idempotencyKey,
                    )
                }
            }

            val result = processor(
                onlyDocumentPurges = true,
                targetCloudBusinessId = null,
                authorizedDocumentPurgeTargets = linkedSetOf(
                    CLOUD_BUSINESS_ID,
                    OTHER_CLOUD_BUSINESS_ID,
                ),
            ) as OutboxPassResult.Drained

            assertEquals(1, result.permanentlyFailed)
            assertEquals(1, result.completed)
            assertEquals(OutboxOperationStatus.FAILED, outbox.row(denied.operationId)?.status)
            assertEquals("PERMISSION_DENIED", outbox.row(denied.operationId)?.lastError)
            assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(allowed.operationId)?.status)
            assertEquals(
                listOf(denied.operationId, allowed.operationId),
                transport.sent.map { it.operationId },
            )
        }

    @Test
    fun `excepcion del transporte es transitoria y queda sanitizada`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = { throw RuntimeException("detalle interno del socket") }

        val result = processor() as OutboxPassResult.Drained

        assertEquals(1, result.scheduledRetries)
        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PENDING, row.status)
        assertEquals(ProcessPurchaseBackupOutboxUseCase.ERROR_TRANSPORT_EXCEPTION, row.lastError)
        assertEquals(now.plusMillis(30_000L), row.nextAttemptAt)
    }

    @Test
    fun `la cancelacion se relanza intacta y el claim queda protegido por su lease`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = { throw CancellationException("padre cancelado") }

        try {
            processor()
            fail("La cancelación debió propagarse")
        } catch (expected: CancellationException) {
            // Regla 16: nunca se convierte en error visible.
        }

        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PROCESSING, row.status)
        assertEquals(1, row.attemptCount)
        assertEquals(
            now.plusMillis(BackupBackoffPolicy.CLAIM_LEASE_MILLIS),
            row.claimLeaseUntil,
        )
    }

    @Test
    fun `cancelacion marcada por el transporte nunca confirma despues de devolver`() = runTest {
        outbox.insert(envelope(1))
        transport.onSend = { envelope ->
            currentCoroutineContext().cancel(CancellationException("worker detenido"))
            BackupTransportResult.Acknowledged("receipt-1", envelope.idempotencyKey)
        }

        val attempt = async { processor() }
        try {
            attempt.await()
            fail("La cancelación marcada por el transporte debió propagarse")
        } catch (expected: CancellationException) {
            // El checkpoint posterior al transporte protege el commit de la outbox.
        }

        val row = outbox.row(operationId(1))!!
        assertEquals(OutboxOperationStatus.PROCESSING, row.status)
        assertEquals(1, row.attemptCount)
        assertNull(row.completedAt)
        assertTrue(row.claimToken != null)
    }

    private fun processorWith(
        outboxRepository: PurchaseBackupOutboxRepository,
        documentUploadPreparer: DocumentUploadPreparer,
    ): ProcessPurchaseBackupOutboxUseCase = ProcessPurchaseBackupOutboxUseCase(
        outbox = outboxRepository,
        transport = transport,
        clock = clock,
        uuidGenerator = uuidGenerator,
        observability = observability,
        documentUploadPreparer = documentUploadPreparer,
    )

    private class RecordingDocumentUploadPreparer : DocumentUploadPreparer {
        val discarded = mutableListOf<String>()
        var discardFailure: Exception? = null

        override suspend fun prepare(source: DocumentUploadSource): PreparedDocumentUpload? = null

        override suspend fun discard(imageId: String): PrivateImageDeletionResult {
            discarded += imageId
            discardFailure?.let { throw it }
            return PrivateImageDeletionResult.DELETED
        }
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000c1"),
        )
        val OTHER_CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000c2"),
        )

        fun operationId(index: Int): String = "00000000-0000-0000-0000-${"%012d".format(index)}"

        fun envelope(index: Int): BackupEnvelope = BackupEnvelope(
            operationId = operationId(index),
            businessId = BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = PurchaseId.from(UUID.fromString("10000000-0000-0000-0000-${"%012d".format(index)}")),
            idempotencyKey = "sync-purchase:v1:op-$index",
            operationType = "SYNC_PURCHASE",
            payloadVersion = 2,
            payload = "{\"version\":2,\"purchaseId\":\"op-$index\"}",
        )

        fun voidEnvelope(
            operationIndex: Int,
            purchase: BackupEnvelope,
        ): BackupEnvelope = BackupEnvelope(
            operationId = operationId(operationIndex),
            businessId = purchase.businessId,
            targetCloudBusinessId = purchase.targetCloudBusinessId,
            purchaseId = requireNotNull(purchase.purchaseId),
            idempotencyKey = "sync-purchase-void:v1:${requireNotNull(purchase.purchaseId).value}",
            operationType = "SYNC_PURCHASE_VOID",
            payloadVersion = 1,
            payload = "{\"version\":1,\"purchaseId\":\"${requireNotNull(purchase.purchaseId).value}\"}",
        )

        fun documentUploadEnvelope(
            operationIndex: Int,
            purchase: BackupEnvelope,
        ): BackupEnvelope = BackupEnvelope(
            operationId = operationId(operationIndex),
            businessId = purchase.businessId,
            targetCloudBusinessId = purchase.targetCloudBusinessId,
            purchaseId = requireNotNull(purchase.purchaseId),
            idempotencyKey = "document-upload:v1:$operationIndex",
            operationType = "SYNC_DOCUMENT_UPLOAD",
            payloadVersion = 1,
            payload = "{\"version\":1}",
            entityType = "DOCUMENT",
            entityId = "30000000-0000-4000-8000-${"%012d".format(operationIndex)}",
            entityVersion = 1L,
        )

        fun documentPurgeEnvelope(
            operationIndex: Int,
            purchase: BackupEnvelope,
        ): BackupEnvelope = BackupEnvelope(
            operationId = operationId(operationIndex),
            businessId = purchase.businessId,
            targetCloudBusinessId = purchase.targetCloudBusinessId,
            purchaseId = requireNotNull(purchase.purchaseId),
            idempotencyKey = "document-purge:v1:$operationIndex",
            operationType = "SYNC_DOCUMENT_PURGE",
            payloadVersion = 1,
            payload = "{\"version\":1}",
            entityType = "DOCUMENT",
            entityId = "40000000-0000-4000-8000-${"%012d".format(operationIndex)}",
            entityVersion = 2L,
        )
    }
}
