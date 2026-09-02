package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.testing.FakeAccountRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebasePurchaseBackupTransportTest {

    @Test
    fun `logout o cambio de enlace es indisponible temporal y nunca permanente`() {
        val mismatches = listOf(
            AccountSession.SignedOut,
            active(link = null),
            active(link = link(LOCAL_BUSINESS_ID, OTHER_CLOUD_BUSINESS_ID)),
            active(link = link(OTHER_LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)),
        )

        mismatches.forEach { session ->
            assertEquals(
                BackupTransportResult.Unavailable,
                backupTenantGuard(session, ENVELOPE),
            )
        }
    }

    @Test
    fun `enlace exacto conserva el destino cloud fijado por Room`() {
        assertNull(
            backupTenantGuard(
                active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)),
                ENVELOPE,
            ),
        )
    }

    @Test
    fun `misma empresa y enlace no permiten cambiar la cuenta que inicio el envio`() {
        val initiating = active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID), uid = "uid-a")
        val replacement = active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID), uid = "uid-b")

        assertNull(backupTenantGuard(initiating, ENVELOPE, expectedUid = "uid-a"))
        assertEquals(
            BackupTransportResult.Unavailable,
            backupTenantGuard(replacement, ENVELOPE, expectedUid = "uid-a"),
        )
    }

    @Test
    fun `UNAUTHENTICATED recupera la sesion una vez y reintenta hasta ACK`() = runTest {
        val account = FakeAccountRepository(active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)))
        var attempts = 0

        val result = executeWithFirebaseSessionRecovery(
            accountRepository = account,
            isUnauthenticated = { it is TestUnauthenticatedException },
            attempt = {
                attempts++
                if (attempts == 1) throw unauthenticated()
                BackupTransportResult.Acknowledged("receipt-recovered", ENVELOPE.idempotencyKey)
            },
            mapFailure = { BackupTransportResult.PermanentFailure("UNEXPECTED_FAILURE") },
        )

        assertEquals(
            BackupTransportResult.Acknowledged("receipt-recovered", ENVELOPE.idempotencyKey),
            result,
        )
        assertEquals(2, attempts)
        assertEquals(1, account.recoverSessionCalls)
    }

    @Test
    fun `fallo al recuperar UNAUTHENTICATED exige reingreso sin segundo envio`() = runTest {
        val account = FakeAccountRepository(active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID))).apply {
            nextRecoverSessionResult = DomainResult.Failure(AccountError.SessionExpired)
        }
        var attempts = 0

        val result = executeWithFirebaseSessionRecovery(
            accountRepository = account,
            isUnauthenticated = { it is TestUnauthenticatedException },
            attempt = {
                attempts++
                throw unauthenticated()
            },
            mapFailure = { BackupTransportResult.PermanentFailure("UNEXPECTED_FAILURE") },
        )

        assertEquals(BackupTransportResult.TransientFailure("SESSION_EXPIRED"), result)
        assertEquals(1, attempts)
        assertEquals(1, account.recoverSessionCalls)
    }

    @Test
    fun `segundo UNAUTHENTICATED no renueva otra vez y conserva fallo transitorio`() = runTest {
        val account = FakeAccountRepository(active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)))
        var attempts = 0

        val result = executeWithFirebaseSessionRecovery(
            accountRepository = account,
            isUnauthenticated = { it is TestUnauthenticatedException },
            attempt = {
                attempts++
                throw unauthenticated()
            },
            mapFailure = { BackupTransportResult.PermanentFailure("UNEXPECTED_FAILURE") },
        )

        assertEquals(BackupTransportResult.TransientFailure("SESSION_EXPIRED"), result)
        assertEquals(2, attempts)
        assertEquals(1, account.recoverSessionCalls)
    }

    @Test
    fun `un fallo distinto de autenticacion se mapea sin renovar ni repetir`() = runTest {
        val account = FakeAccountRepository(active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)))
        var attempts = 0

        val result = executeWithFirebaseSessionRecovery(
            accountRepository = account,
            isUnauthenticated = { it is TestUnauthenticatedException },
            attempt = {
                attempts++
                throw TestNetworkException()
            },
            mapFailure = { failure ->
                assertEquals(TestNetworkException::class, failure::class)
                BackupTransportResult.TransientFailure("NETWORK_UNAVAILABLE")
            },
        )

        assertEquals(BackupTransportResult.TransientFailure("NETWORK_UNAVAILABLE"), result)
        assertEquals(1, attempts)
        assertEquals(0, account.recoverSessionCalls)
    }

    @Test
    fun `cancelacion se propaga sin mapear renovar ni repetir`() = runTest {
        val account = FakeAccountRepository(active(link(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)))
        var attempts = 0
        var propagated = false

        try {
            executeWithFirebaseSessionRecovery(
                accountRepository = account,
                isUnauthenticated = { it is TestUnauthenticatedException },
                attempt = {
                    attempts++
                    throw CancellationException("test cancellation")
                },
                mapFailure = { BackupTransportResult.PermanentFailure("MUST_NOT_MAP") },
            )
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertEquals(1, attempts)
        assertEquals(0, account.recoverSessionCalls)
    }

    private fun active(
        link: CloudBusinessLink?,
        uid: String = "transport-tenant-guard",
    ): AccountSession.Active = AccountSession.Active(
        uid = uid,
        email = "transport@example.pe",
        link = link,
    )

    private fun link(local: BusinessId, cloud: BusinessId): CloudBusinessLink =
        CloudBusinessLink(
            localBusinessId = local,
            cloudBusinessId = cloud,
            role = BusinessRole.OWNER,
        )

    private fun unauthenticated(): Exception = TestUnauthenticatedException()

    private class TestUnauthenticatedException : Exception("UNAUTHENTICATED")

    private class TestNetworkException : Exception("NETWORK")

    private companion object {
        val LOCAL_BUSINESS_ID = businessId("00000000-0000-4000-8000-000000005101")
        val CLOUD_BUSINESS_ID = businessId("00000000-0000-4000-8000-000000005102")
        val OTHER_LOCAL_BUSINESS_ID = businessId("00000000-0000-4000-8000-000000005103")
        val OTHER_CLOUD_BUSINESS_ID = businessId("00000000-0000-4000-8000-000000005104")

        val ENVELOPE = BackupEnvelope(
            operationId = "00000000-0000-4000-8000-000000005105",
            businessId = LOCAL_BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = null,
            idempotencyKey = "tenant-guard-v1",
            operationType = "SYNC_PRODUCT",
            payloadVersion = 1,
            payload = "{\"version\":1}",
            entityType = "PRODUCT",
            entityId = "00000000-0000-4000-8000-000000005106",
        )

        fun businessId(raw: String): BusinessId = BusinessId.from(UUID.fromString(raw))
    }
}
