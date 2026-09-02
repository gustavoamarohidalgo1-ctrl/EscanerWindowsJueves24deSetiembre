package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.BackupBackoffPolicy
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.testing.FakePurchaseBackupOutboxRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fault injection en los puertos del drenador. No abre sockets ni pretende sustituir las pruebas
 * de Firebase Emulator: reproduce de forma determinista las ventanas que son imposibles de
 * ordenar con una red real (efecto remoto confirmado + ACK perdido, dos bases locales y reloj
 * civil alterado).
 */
class OutboxReplayFaultInjectionTest {
    private var now = Instant.parse("2026-08-30T12:00:00Z")

    @Test
    fun `efecto remoto seguido de ACK perdido se reenvia sin duplicar el efecto`() = runTest {
        val outbox = FakePurchaseBackupOutboxRepository().apply { insert(envelope()) }
        val remote = IdempotentRemoteTransport(dropFirstAcknowledgement = true)

        val interrupted = processor(outbox, remote, tokenSeed = 1)()
            as OutboxPassResult.Drained

        assertEquals(1, interrupted.scheduledRetries)
        assertEquals(1, remote.effectCount)
        assertEquals(1, remote.deliveryCount)
        assertEquals(OutboxOperationStatus.PENDING, outbox.row(OPERATION_ID)?.status)

        // Equivale a un proceso nuevo: se reconstruye el caso de uso y solo se conserva la fila
        // durable. El servidor vuelve a recibir la misma clave y devuelve el recibo ya creado.
        now = requireNotNull(outbox.row(OPERATION_ID)?.nextAttemptAt)
        val recovered = processor(outbox, remote, tokenSeed = 2)()
            as OutboxPassResult.Drained

        assertEquals(1, recovered.completed)
        assertEquals(1, remote.effectCount)
        assertEquals(2, remote.deliveryCount)
        assertEquals(listOf(IDEMPOTENCY_KEY, IDEMPOTENCY_KEY), remote.deliveredKeys)
        val completed = requireNotNull(outbox.row(OPERATION_ID))
        assertEquals(OutboxOperationStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attemptCount)
        assertNull(completed.lastError)
    }

    @Test
    fun `dos dispositivos con outboxes independientes convergen en un efecto remoto`() = runTest {
        val firstOutbox = FakePurchaseBackupOutboxRepository().apply { insert(envelope()) }
        val secondEnvelope = envelope().copy(operationId = SECOND_OPERATION_ID)
        val secondOutbox = FakePurchaseBackupOutboxRepository().apply { insert(secondEnvelope) }
        val remote = IdempotentRemoteTransport()

        coroutineScope {
            launch { processor(firstOutbox, remote, tokenSeed = 3)() }
            launch { processor(secondOutbox, remote, tokenSeed = 4)() }
        }

        assertEquals(2, remote.deliveryCount)
        assertEquals(1, remote.effectCount)
        assertEquals(setOf(IDEMPOTENCY_KEY), remote.deliveredKeys.toSet())
        assertEquals(OutboxOperationStatus.COMPLETED, firstOutbox.row(OPERATION_ID)?.status)
        assertEquals(
            OutboxOperationStatus.COMPLETED,
            secondOutbox.row(SECOND_OPERATION_ID)?.status,
        )
    }

    @Test
    fun `reloj atrasado no roba lease vivo y salto posterior recupera el claim`() = runTest {
        val outbox = FakePurchaseBackupOutboxRepository().apply { insert(envelope()) }
        val remote = IdempotentRemoteTransport()
        val claimedAt = now
        val leaseUntil = claimedAt.plusMillis(BackupBackoffPolicy.CLAIM_LEASE_MILLIS)
        assertTrue(
            outbox.claim(
                operationId = OPERATION_ID,
                claimToken = "claim-del-proceso-muerto",
                claimedAt = claimedAt,
                leaseUntil = leaseUntil,
            ),
        )

        now = claimedAt.minusSeconds(86_400)
        val rewound = processor(outbox, remote, tokenSeed = 5)()
            as OutboxPassResult.Drained

        assertEquals(0, rewound.processed)
        assertEquals(leaseUntil, rewound.nextWakeupAt)
        assertEquals(OutboxOperationStatus.PROCESSING, outbox.row(OPERATION_ID)?.status)
        assertEquals(0, remote.deliveryCount)

        now = leaseUntil.plusMillis(1)
        val advanced = processor(outbox, remote, tokenSeed = 6)()
            as OutboxPassResult.Drained

        assertEquals(1, advanced.completed)
        assertEquals(1, remote.effectCount)
        assertEquals(1, remote.deliveryCount)
        assertEquals(2, outbox.row(OPERATION_ID)?.attemptCount)
    }

    @Test
    fun `429 y 5xx conservan clave y backoff durable hasta el ACK`() = runTest {
        val outbox = FakePurchaseBackupOutboxRepository().apply { insert(envelope()) }
        val transport = ScriptedTransport(
            BackupTransportResult.TransientFailure("RESOURCE_EXHAUSTED"),
            BackupTransportResult.TransientFailure("INTERNAL"),
            acknowledgement(),
        )
        val processor = processor(outbox, transport, tokenSeed = 7)

        val rateLimited = processor() as OutboxPassResult.Drained
        assertEquals(1, rateLimited.scheduledRetries)
        assertEquals("RESOURCE_EXHAUSTED", outbox.row(OPERATION_ID)?.lastError)
        now = requireNotNull(outbox.row(OPERATION_ID)?.nextAttemptAt)

        val serverFailure = processor() as OutboxPassResult.Drained
        assertEquals(1, serverFailure.scheduledRetries)
        assertEquals("INTERNAL", outbox.row(OPERATION_ID)?.lastError)
        now = requireNotNull(outbox.row(OPERATION_ID)?.nextAttemptAt)

        val acknowledged = processor() as OutboxPassResult.Drained
        assertEquals(1, acknowledged.completed)
        assertEquals(3, outbox.row(OPERATION_ID)?.attemptCount)
        assertEquals(OutboxOperationStatus.COMPLETED, outbox.row(OPERATION_ID)?.status)
        assertEquals(List(3) { IDEMPOTENCY_KEY }, transport.deliveredKeys)
    }

    private fun processor(
        outbox: FakePurchaseBackupOutboxRepository,
        transport: PurchaseBackupTransport,
        tokenSeed: Int,
    ) = ProcessPurchaseBackupOutboxUseCase(
        outbox = outbox,
        transport = transport,
        clock = AppClock { now },
        uuidGenerator = UuidGenerator {
            UUID.fromString("00000000-0000-4000-8000-${"%012d".format(tokenSeed)}")
        },
    )

    private class IdempotentRemoteTransport(
        private var dropFirstAcknowledgement: Boolean = false,
    ) : PurchaseBackupTransport {
        override val configured: Boolean = true
        private val mutex = Mutex()
        private val receiptsByKey = linkedMapOf<String, String>()
        private val deliveries = mutableListOf<String>()

        val effectCount: Int get() = receiptsByKey.size
        val deliveryCount: Int get() = deliveries.size
        val deliveredKeys: List<String> get() = deliveries.toList()

        override suspend fun send(envelope: BackupEnvelope): BackupTransportResult =
            mutex.withLock {
                deliveries += envelope.idempotencyKey
                val receipt = receiptsByKey.getOrPut(envelope.idempotencyKey) {
                    "receipt-${receiptsByKey.size + 1}"
                }
                if (dropFirstAcknowledgement) {
                    dropFirstAcknowledgement = false
                    BackupTransportResult.TransientFailure("NETWORK_UNAVAILABLE")
                } else {
                    BackupTransportResult.Acknowledged(receipt, envelope.idempotencyKey)
                }
            }
    }

    private class ScriptedTransport(
        vararg results: BackupTransportResult,
    ) : PurchaseBackupTransport {
        override val configured: Boolean = true
        private val remaining = ArrayDeque(results.toList())
        val deliveredKeys = mutableListOf<String>()

        override suspend fun send(envelope: BackupEnvelope): BackupTransportResult {
            deliveredKeys += envelope.idempotencyKey
            return remaining.removeFirst()
        }
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000008001"
        const val SECOND_OPERATION_ID = "00000000-0000-4000-8000-000000008002"
        const val IDEMPOTENCY_KEY = "sync-purchase:v1:10000000-0000-4000-8000-000000008003"
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000008004"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000008005"),
        )
        val PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("10000000-0000-4000-8000-000000008003"),
        )

        fun envelope() = BackupEnvelope(
            operationId = OPERATION_ID,
            businessId = BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = PURCHASE_ID,
            idempotencyKey = IDEMPOTENCY_KEY,
            operationType = "SYNC_PURCHASE",
            payloadVersion = 3,
            payload = "{\"version\":3,\"purchaseId\":\"${PURCHASE_ID.value}\"}",
        )

        fun acknowledgement() = BackupTransportResult.Acknowledged(
            receiptId = "receipt-final",
            echoedIdempotencyKey = IDEMPOTENCY_KEY,
        )
    }
}
