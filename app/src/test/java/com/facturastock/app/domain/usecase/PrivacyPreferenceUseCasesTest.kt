package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.DisabledDocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDraftFileStore
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class PrivacyPreferenceUseCasesTest {

    @Test
    fun `backup off despierta privacidad aunque falle cancelar el canal regular`() = runTest {
        val configuration = configuredRepository().apply { updateBackupEnabled(true) }
        val scheduler = RecordingBackupScheduler().apply {
            cancelRegularFailure = IllegalStateException("workmanager lleno")
        }

        val result = UpdateBackupEnabledUseCase(configuration, scheduler)(false)

        assertFalse(configuration.current().backupEnabled)
        assertEquals(1, scheduler.cancelRegularCount)
        assertEquals(1, scheduler.privacyWakeCount)
        assertEquals(BackupPreferenceUpdate(enabled = false, schedulerUpdated = false), result)
    }

    @Test
    fun `cancelacion tras persistir backup off no puede perder el wake de privacidad`() = runTest {
        val configuration = configuredRepository().apply { updateBackupEnabled(true) }
        val scheduler = RecordingBackupScheduler().apply {
            cancelRegularFailure = CancellationException("pantalla cerrada")
        }

        try {
            UpdateBackupEnabledUseCase(configuration, scheduler)(false)
            fail("La cancelacion original debio propagarse")
        } catch (_: CancellationException) {
            // La preferencia y el wake son efectos ya confirmados; el Job sigue cancelado.
        }

        assertFalse(configuration.current().backupEnabled)
        assertEquals(1, scheduler.cancelRegularCount)
        assertEquals(1, scheduler.privacyWakeCount)
    }

    @Test
    fun `documentos off despierta purga aunque Room falle despues del commit`() = runTest {
        val configuration = configuredRepository().apply { updateDocumentBackupEnabled(true) }
        val scheduler = RecordingBackupScheduler()
        val lifecycle = object : DocumentBackupLifecycleRepository by
            DisabledDocumentBackupLifecycleRepository {
            override suspend fun withdrawAllOpenUploads(
                requestedAt: Instant,
            ): Int = throw IllegalStateException("room no disponible")
        }

        try {
            UpdateDocumentBackupEnabledUseCase(
                appConfigurationRepository = configuration,
                documentLifecycle = lifecycle,
                purchaseBackupScheduler = scheduler,
            )(false)
            fail("El fallo Room debe seguir visible al caller")
        } catch (_: IllegalStateException) {
            // El finally no degrada el error, solo garantiza el wake post-commit.
        }

        assertFalse(configuration.current().documentBackupEnabled)
        assertEquals(1, scheduler.privacyWakeCount)
    }

    @Test
    fun `cancelar retencion post confirmacion conserva el wake inmediato`() = runTest {
        val base = configuredRepository()
        val cancellingConfiguration = object : AppConfigurationRepository by base {
            override suspend fun current(): AppConfiguration =
                throw CancellationException("navegacion cerrada")
        }
        val maintenance = RecordingPrivacyMaintenanceScheduler()

        try {
            ApplyImageRetentionAfterConfirmUseCase(
                appConfigurationRepository = cancellingConfiguration,
                draftFileStore = FakeDraftFileStore(),
                privacyMaintenanceScheduler = maintenance,
            )(DRAFT_ID, BUSINESS_ID)
            fail("La cancelacion original debio propagarse")
        } catch (_: CancellationException) {
            // El hito de compra ya ocurrio y WorkManager fue despertado en NonCancellable.
        }

        assertEquals(1, maintenance.immediateWakeCount)
    }

    private suspend fun configuredRepository(): FakeAppConfigurationRepository =
        FakeAppConfigurationRepository().apply {
            completeOnboarding(
                businessId = BUSINESS_ID,
                taxRate = TaxRate(BigDecimal("18")),
                costPolicy = CostPolicy.GROSS,
            )
        }

    private class RecordingBackupScheduler : PurchaseBackupScheduler {
        var cancelRegularFailure: Exception? = null
        var cancelRegularCount = 0
        var privacyWakeCount = 0

        override suspend fun enqueue() = Unit
        override suspend fun enqueueAt(attemptAt: Instant) = Unit
        override suspend fun enqueuePrivacyPurge() {
            privacyWakeCount++
        }
        override suspend fun cancelRegular() {
            cancelRegularCount++
            cancelRegularFailure?.let { throw it }
        }
        override suspend fun cancelAll() = Unit
    }

    private class RecordingPrivacyMaintenanceScheduler : PrivacyMaintenanceScheduler {
        var immediateWakeCount = 0
        override suspend fun enqueue() = Unit
        override suspend fun enqueueImmediate() {
            immediateWakeCount++
        }
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000009101"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-000000009102"),
        )
    }
}
