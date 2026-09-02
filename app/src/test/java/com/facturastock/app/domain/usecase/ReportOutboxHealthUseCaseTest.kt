package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.observability.OperationalAgeBucket
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.testing.FakePurchaseBackupOutboxRepository
import com.facturastock.app.testing.FakeCloudBusinessBindingRepository
import com.facturastock.app.testing.RecordingProductionObservability
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportOutboxHealthUseCaseTest {
    private val now = Instant.parse("2026-08-30T12:00:00Z")
    private val outbox = FakePurchaseBackupOutboxRepository()
    private val bindings = FakeCloudBusinessBindingRepository()
    private val observability = RecordingProductionObservability()
    private val report = ReportOutboxHealthUseCase(
        outbox = outbox,
        cloudBusinessBindings = bindings,
        clock = AppClock { now },
        observability = observability,
    )

    @Test
    fun `empty queue emits one healthy sample without identifiers`() = runTest {
        bindings.seed(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)
        report(configuration(backupEnabled = true))

        val event = observability.records.single().event
        assertEquals(OperationalAction.OUTBOX_HEALTH, event.action)
        assertEquals(OperationalOutcome.SUCCEEDED, event.outcome)
        assertNull(event.ageBucket)
        assertNull(event.identifiers.businessId)
        assertNull(event.identifiers.draftId)
        assertNull(event.identifiers.imageId)
        assertNull(event.identifiers.purchaseId)
        assertNull(event.identifiers.operationId)
    }

    @Test
    fun `old open row remains visible even when transport cannot run`() = runTest {
        bindings.seed(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)
        outbox.insert(
            envelope = testEnvelope(),
            createdAt = now.minusSeconds(8L * 24L * 60L * 60L),
        )

        report(configuration(backupEnabled = true))

        val event = observability.records.single().event
        assertEquals(OperationalOutcome.BLOCKED, event.outcome)
        assertEquals(OperationalAgeBucket.OVER_7_DAYS, event.ageBucket)
        assertNull(event.errorCode)
    }

    @Test
    fun `fresh open row is healthy while retaining its coarse age`() = runTest {
        bindings.seed(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)
        outbox.insert(
            envelope = testEnvelope(),
            createdAt = now.minusSeconds(5L * 60L),
        )

        report(configuration(backupEnabled = true))

        val event = observability.records.single().event
        assertEquals(OperationalOutcome.SUCCEEDED, event.outcome)
        assertEquals(OperationalAgeBucket.UNDER_15_MINUTES, event.ageBucket)
    }

    @Test
    fun `future timestamp is a clock skew bucket instead of a fresh operation`() = runTest {
        bindings.seed(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)
        outbox.insert(
            envelope = testEnvelope(),
            createdAt = now.plusSeconds(60L),
        )

        report(configuration(backupEnabled = true))

        val event = observability.records.single().event
        assertEquals(OperationalOutcome.BLOCKED, event.outcome)
        assertEquals(OperationalAgeBucket.CLOCK_SKEW_OR_FUTURE, event.ageBucket)
    }

    @Test
    fun `disabled backup does not alert on intentional local only history`() = runTest {
        outbox.insert(
            envelope = testEnvelope(),
            createdAt = now.minusSeconds(30L * 24L * 60L * 60L),
        )

        report(configuration(backupEnabled = false))

        val event = observability.records.single().event
        assertEquals(OperationalOutcome.SKIPPED, event.outcome)
        assertNull(event.ageBucket)
    }

    @Test
    fun `diagnostics opt out performs no query and emits nothing`() = runTest {
        outbox.insert(
            envelope = testEnvelope(),
            createdAt = now.minusSeconds(30L * 24L * 60L * 60L),
        )

        report(configuration(backupEnabled = true).copy(diagnosticsEnabled = false))

        assertEquals(0, observability.records.size)
    }

    private fun configuration(backupEnabled: Boolean): AppConfiguration =
        AppConfiguration.defaults().copy(
            onboardingCompleted = true,
            businessId = LOCAL_BUSINESS_ID,
            backupEnabled = backupEnabled,
            diagnosticsEnabled = true,
        )

    private fun testEnvelope(): BackupEnvelope = BackupEnvelope(
        operationId = "00000000-0000-4000-8000-000000000001",
        businessId = LOCAL_BUSINESS_ID,
        targetCloudBusinessId = CLOUD_BUSINESS_ID,
        purchaseId = null,
        idempotencyKey = "outbox-health-test",
        operationType = "SYNC_PRODUCT",
        payloadVersion = 1,
        payload = "{}",
    )

    private companion object {
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000b1"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000c1"),
        )
    }
}
