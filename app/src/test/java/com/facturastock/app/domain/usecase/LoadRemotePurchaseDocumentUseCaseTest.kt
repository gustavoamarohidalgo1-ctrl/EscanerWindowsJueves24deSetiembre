package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDocumentDownloadResult
import com.facturastock.app.domain.repository.RemoteDocumentReference
import com.facturastock.app.testing.FakeAppConfigurationRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadRemotePurchaseDocumentUseCaseTest {
    private val configuration = FakeAppConfigurationRepository()
    private val lifecycle = FakeDocumentLifecycle()
    private val archive = FakeRemoteDocumentArchive()
    private val useCase = LoadRemotePurchaseDocumentUseCase(configuration, lifecycle, archive)

    @Test
    fun `default opt outs never inspect Room or remote archive`() = runTest {
        activate()

        assertEquals(
            RemotePurchaseDocumentLoadResult.NotEligible,
            useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID),
        )
        assertEquals(0, lifecycle.backedUpChecks)
        assertTrue(archive.references.isEmpty())
    }

    @Test
    fun `both opt ins still require a completed upload without purge`() = runTest {
        activateWithDocumentBackup()
        lifecycle.backedUp = false

        assertEquals(
            RemotePurchaseDocumentLoadResult.NotEligible,
            useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID),
        )
        assertEquals(1, lifecycle.backedUpChecks)
        assertTrue(archive.references.isEmpty())
    }

    @Test
    fun `eligible image is downloaded only with its typed tenant identities`() = runTest {
        activateWithDocumentBackup()
        lifecycle.backedUp = true
        val expected = byteArrayOf(7, 2, 6, 4)
        archive.nextResult = RemoteDocumentDownloadResult.Downloaded(expected.copyOf())

        val result = useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID)

        assertArrayEquals(
            expected,
            (result as RemotePurchaseDocumentLoadResult.Downloaded).jpeg,
        )
        assertEquals(
            listOf(RemoteDocumentReference(BUSINESS_ID, PURCHASE_ID, IMAGE_ID)),
            archive.references,
        )
        assertEquals(3, lifecycle.backedUpChecks)
    }

    @Test
    fun `opt out durante el primer check se relee antes de iniciar el IO remoto`() = runTest {
        activateWithDocumentBackup()
        lifecycle.backedUp = true
        val checkStarted = CompletableDeferred<Unit>()
        val resumeCheck = CompletableDeferred<Unit>()
        lifecycle.onBackedUpCheck = { checkNumber ->
            if (checkNumber == 1) {
                checkStarted.complete(Unit)
                resumeCheck.await()
            }
        }

        val pending = async { useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID) }
        checkStarted.await()
        configuration.updateDocumentBackupEnabled(false)
        resumeCheck.complete(Unit)

        assertEquals(RemotePurchaseDocumentLoadResult.NotEligible, pending.await())
        assertEquals(1, lifecycle.backedUpChecks)
        assertTrue(archive.references.isEmpty())
    }

    @Test
    fun `opt out durante descarga sanea bytes antes de entregar resultado`() = runTest {
        activateWithDocumentBackup()
        lifecycle.backedUp = true
        val remoteBytes = byteArrayOf(9, 8, 7, 6)
        val downloadStarted = CompletableDeferred<Unit>()
        val resumeDownload = CompletableDeferred<Unit>()
        archive.nextResult = RemoteDocumentDownloadResult.Downloaded(remoteBytes)
        archive.beforeResult = {
            downloadStarted.complete(Unit)
            resumeDownload.await()
        }

        val pending = async { useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID) }
        downloadStarted.await()
        configuration.updateBackupEnabled(false)
        resumeDownload.complete(Unit)

        assertEquals(RemotePurchaseDocumentLoadResult.NotEligible, pending.await())
        assertTrue(remoteBytes.all { it == 0.toByte() })
        assertEquals(2, lifecycle.backedUpChecks)
    }

    @Test
    fun `purga durante descarga revoca isBackedUp y sanea el buffer`() = runTest {
        activateWithDocumentBackup()
        lifecycle.backedUp = true
        val remoteBytes = byteArrayOf(4, 3, 2, 1)
        val downloadStarted = CompletableDeferred<Unit>()
        val resumeDownload = CompletableDeferred<Unit>()
        archive.nextResult = RemoteDocumentDownloadResult.Downloaded(remoteBytes)
        archive.beforeResult = {
            downloadStarted.complete(Unit)
            resumeDownload.await()
        }

        val pending = async { useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID) }
        downloadStarted.await()
        lifecycle.backedUp = false
        resumeDownload.complete(Unit)

        assertEquals(RemotePurchaseDocumentLoadResult.NotEligible, pending.await())
        assertTrue(remoteBytes.all { it == 0.toByte() })
        assertEquals(3, lifecycle.backedUpChecks)
    }

    @Test
    fun `foreign active business and unavailable adapter fail before eligibility check`() = runTest {
        activateWithDocumentBackup(OTHER_BUSINESS_ID)
        lifecycle.backedUp = true

        assertEquals(
            RemotePurchaseDocumentLoadResult.NotEligible,
            useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID),
        )
        archive.isConfigured = false
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        assertEquals(
            RemotePurchaseDocumentLoadResult.NotEligible,
            useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID),
        )
        assertEquals(0, lifecycle.backedUpChecks)
        assertTrue(archive.references.isEmpty())
    }

    @Test
    fun `remote integrity rejection remains a closed explicit result`() = runTest {
        activateWithDocumentBackup()
        lifecycle.backedUp = true
        archive.nextResult = RemoteDocumentDownloadResult.IntegrityRejected

        assertEquals(
            RemotePurchaseDocumentLoadResult.IntegrityRejected,
            useCase(BUSINESS_ID, PURCHASE_ID, IMAGE_ID),
        )
    }

    private suspend fun activateWithDocumentBackup(businessId: BusinessId = BUSINESS_ID) {
        activate(businessId)
        configuration.updateBackupEnabled(true)
        configuration.updateDocumentBackupEnabled(true)
    }

    private suspend fun activate(businessId: BusinessId = BUSINESS_ID) {
        configuration.completeOnboarding(
            businessId,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private class FakeDocumentLifecycle : DocumentBackupLifecycleRepository {
        var backedUp = false
        var backedUpChecks = 0
        var onBackedUpCheck: suspend (Int) -> Unit = {}

        override suspend fun isBackedUp(
            businessId: BusinessId,
            purchaseId: PurchaseId,
            imageId: ImageId,
        ): Boolean {
            backedUpChecks += 1
            onBackedUpCheck(backedUpChecks)
            return backedUp
        }

        override suspend fun ensurePurge(
            businessId: BusinessId,
            image: RetainedImageRef,
            requestedAt: Instant,
        ): DocumentPurgeIntentResult = DocumentPurgeIntentResult.NOT_REQUIRED

        override suspend fun ensureRetainedUploads(
            businessId: BusinessId,
            postedAfterExclusive: Instant?,
            requestedAt: Instant,
        ): Int = 0

        override suspend fun withdrawOpenUploads(
            businessId: BusinessId,
            requestedAt: Instant,
        ): Int = 0
    }

    private class FakeRemoteDocumentArchive : RemoteDocumentArchive {
        var isConfigured = true
        var nextResult: RemoteDocumentDownloadResult = RemoteDocumentDownloadResult.NotFound
        var beforeResult: suspend () -> Unit = {}
        val references = mutableListOf<RemoteDocumentReference>()

        override val configured: Boolean
            get() = isConfigured

        override suspend fun download(
            reference: RemoteDocumentReference,
        ): RemoteDocumentDownloadResult {
            references += reference
            beforeResult()
            return nextResult
        }
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 1L))
        val OTHER_BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 2L))
        val PURCHASE_ID: PurchaseId = PurchaseId.from(UUID(0L, 3L))
        val IMAGE_ID: ImageId = ImageId.from(UUID(0L, 4L))
    }
}
