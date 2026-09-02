package com.facturastock.app.testing

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contrato de los dobles que representan Auth, Functions y Firestore en las pruebas JVM.
 *
 * Un único interruptor simula modo avión sin sockets ni esperas reales. El corte debe impedir
 * consumir el siguiente resultado guionado: al reconectar, cada fake continúa exactamente en el
 * punto previo al fallo de red.
 */
class FirebaseTestDoublesContractTest {

    @Test
    fun `modo avion compartido no consume guiones y reconexion los reanuda`() = runTest {
        val connectivity = FakeFirebaseConnectivity()
        val expired = AccountSession.Expired(EMAIL)
        val active = AccountSession.Active(
            uid = UID,
            email = EMAIL,
            link = CloudBusinessLink(
                LOCAL_BUSINESS_ID,
                CLOUD_BUSINESS_ID,
                BusinessRole.OWNER,
            ),
        )
        val account = FakeAccountRepository(
            initialSession = expired,
            connectivity = connectivity,
        ).apply {
            nextRecoverSessionResult = DomainResult.Success(active)
        }
        val page = SyncPullPage(changes = emptyList(), nextCursor = 0L, hasMore = false)
        val remoteLedger = FakeRemoteLedgerRepository(connectivity = connectivity).apply {
            enqueuePage(page)
        }
        var transportScriptCalls = 0
        val conflict = BackupTransportResult.Conflict(
            reason = "ALREADY_EXISTS",
            remotePurchaseId = "remote-purchase-1",
            remoteReceiptId = "remote-receipt-1",
        )
        val transport = FakePurchaseBackupTransport(connectivity = connectivity).apply {
            onSend = {
                transportScriptCalls++
                conflict
            }
        }

        connectivity.disconnect()

        assertEquals(
            DomainResult.Failure(AccountError.NetworkUnavailable),
            account.recoverSession(),
        )
        assertEquals(expired, account.session)
        assertEquals(
            DomainResult.Failure(AccountError.NetworkUnavailable),
            remoteLedger.pullChanges(CLOUD_BUSINESS_ID, sinceSeq = 0L, limit = 200),
        )
        assertEquals(
            BackupTransportResult.TransientFailure("NETWORK_UNAVAILABLE"),
            transport.send(ENVELOPE),
        )
        assertEquals(0, transportScriptCalls)

        connectivity.reconnect()

        assertEquals(DomainResult.Success(active), account.recoverSession())
        assertEquals(active, account.session)
        assertEquals(
            DomainResult.Success(page),
            remoteLedger.pullChanges(CLOUD_BUSINESS_ID, sinceSeq = 0L, limit = 200),
        )
        assertEquals(conflict, transport.send(ENVELOPE))
        assertEquals(1, transportScriptCalls)
        assertEquals(2, account.recoverSessionCalls)
        assertEquals(2, remoteLedger.pullCalls.size)
        assertEquals(listOf(ENVELOPE, ENVELOPE), transport.sent)
    }

    @Test
    fun `falta de configuracion prevalece y nunca ejecuta el guion del transporte`() = runTest {
        var scriptCalls = 0
        val transport = FakePurchaseBackupTransport(configured = false).apply {
            onSend = {
                scriptCalls++
                BackupTransportResult.Acknowledged("receipt-1", it.idempotencyKey)
            }
        }

        assertEquals(BackupTransportResult.Unavailable, transport.send(ENVELOPE))
        assertEquals(0, scriptCalls)
    }

    private companion object {
        const val UID = "uid-firebase-fake"
        const val EMAIL = "fake@example.com"

        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
        )
        val ENVELOPE = BackupEnvelope(
            operationId = "30000000-0000-4000-8000-000000000003",
            businessId = LOCAL_BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = PurchaseId.from(
                UUID.fromString("40000000-0000-4000-8000-000000000004"),
            ),
            idempotencyKey = "sync-purchase:v1:fake-contract",
            operationType = "SYNC_PURCHASE",
            payloadVersion = 2,
            payload = "{\"version\":2}",
        )
    }
}
