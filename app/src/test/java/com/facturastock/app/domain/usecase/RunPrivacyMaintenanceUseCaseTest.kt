package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.OcrVersionSweepReport
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.PrivacyMaintenanceStep
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.RetentionSweepReport
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.DocumentUploadArtifactSweepReport
import com.facturastock.app.domain.repository.OcrVersionSweepDecision
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDocumentBackupLifecycleRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakeRetainedImageStore
import com.facturastock.app.testing.FakeRetentionFileSweep
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orquestación del mantenimiento de privacidad con puertos falsos: contadores reales, mejor
 * esfuerzo por archivo, retención por política con reloj fijo y migración de cifrado solo
 * sobre las supervivientes en claro.
 */
class RunPrivacyMaintenanceUseCaseTest {
    private var now = NOW
    private val configuration = FakeAppConfigurationRepository()
    private val drafts = FakeInvoiceDraftRepository(AppClock { now })
    private val purchaseReads = FakePurchaseReadRepository()
    private val sweep = FakeRetentionFileSweep()
    private val draftFiles = FakeDraftFileStore()
    private val retainedStore = FakeRetainedImageStore()
    private val documentLifecycle = FakeDocumentBackupLifecycleRepository()
    private val backupScheduler = FakePurchaseBackupScheduler()
    private val privacyScheduler = RecordingPrivacyMaintenanceScheduler()
    private val ocrActivity = OcrRunActivityRegistry()
    private val useCase = RunPrivacyMaintenanceUseCase(
        appConfigurationRepository = configuration,
        invoiceDraftRepository = drafts,
        purchaseReadRepository = purchaseReads,
        retentionFileSweep = sweep,
        draftFileStore = draftFiles,
        retainedImageStore = retainedStore,
        documentLifecycle = documentLifecycle,
        purchaseBackupScheduler = backupScheduler,
        privacyMaintenanceScheduler = privacyScheduler,
        ocrRunActivityRegistry = ocrActivity,
        appClock = AppClock { now },
    )

    @Test
    fun `sin negocio activo solo corren los barridos de archivos`() = runTest {
        sweep.staleImportsResult = 2
        sweep.staleCacheEntriesResult = 5
        sweep.orphanDraftDirsResult = 1
        sweep.committedOcrRunsResult = 3

        val report = useCase()

        assertEquals(
            RetentionSweepReport(
                staleImports = 2,
                staleCacheEntries = 5,
                stalePrivateTemps = 0,
                orphanDraftDirs = 1,
                committedOcrRuns = 3,
                afterOcrDeleteAttempted = 0,
                afterOcrDeleted = 0,
                afterOcrAlreadyAbsent = 0,
                afterOcrDeleteFailed = 0,
                retentionDeleteAttempted = 0,
                purgeIntentAttempted = 0,
                purgeIntentDurable = 0,
                purgeIntentNotRequired = 0,
                purgeIntentFailed = 0,
                retentionDeleted = 0,
                retentionAlreadyAbsent = 0,
                retentionDeleteFailed = 0,
                encryptedMigrated = 0,
                encryptionAttempted = 0,
                encryptionAlreadySatisfied = 0,
                encryptionFailed = 0,
                failedSteps = emptySet(),
            ),
            report,
        )
        assertFalse(report.hasUnconfirmedLocalWork)
    }

    @Test
    fun `con KEEP las retenidas se conservan y se cifran las que estan en claro`() = runTest {
        activateBusiness()
        val plain = retainedRef("draft-images/a.jpg", postedDaysAgo = 400)
        val alreadyEncrypted = retainedRef("draft-images/b.jpg", postedDaysAgo = 10)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(plain, alreadyEncrypted))
        retainedStore.putFile(plain.relativeFilePath)
        retainedStore.putEncryptedFile(alreadyEncrypted.relativeFilePath)

        val report = useCase()

        assertEquals(0, report.retentionDeleteAttempted)
        assertEquals(0, report.retentionDeleted)
        assertEquals(0, report.retentionAlreadyAbsent)
        assertEquals(0, report.retentionDeleteFailed)
        assertEquals(1, report.encryptedMigrated)
        assertEquals(2, report.encryptionAttempted)
        assertEquals(1, report.encryptionAlreadySatisfied)
        assertEquals(0, report.encryptionFailed)
        assertEquals(listOf(plain.relativeFilePath), retainedStore.encryptedInPlacePaths)
        assertTrue(retainedStore.deletedPaths.isEmpty())
    }

    @Test
    fun `un envelope se autentica y reintenta hasta confirmar publicacion durable`() = runTest {
        activateBusiness()
        val encrypted = retainedRef("draft-images/enveloped-retry.fse", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(encrypted))
        retainedStore.putEncryptedFile(encrypted.relativeFilePath)
        retainedStore.failingPaths += encrypted.relativeFilePath

        val failedReport = useCase()

        assertEquals(listOf(encrypted.relativeFilePath), retainedStore.encryptInPlaceAttempts)
        assertEquals(0, failedReport.encryptionAlreadySatisfied)
        assertEquals(1, failedReport.encryptionFailed)
        assertEquals(1, failedReport.encryptionRetryableFailed)
        assertTrue(failedReport.hasRetryableLocalWork)

        retainedStore.failingPaths -= encrypted.relativeFilePath

        val recoveredReport = useCase()

        assertEquals(
            listOf(encrypted.relativeFilePath, encrypted.relativeFilePath),
            retainedStore.encryptInPlaceAttempts,
        )
        assertEquals(1, recoveredReport.encryptionAlreadySatisfied)
        assertEquals(0, recoveredReport.encryptionFailed)
        assertEquals(0, recoveredReport.encryptionRetryableFailed)
        assertFalse(recoveredReport.hasRetryableLocalWork)
    }

    @Test
    fun `DAYS_30 borra justo en el limite y conserva las recientes`() = runTest {
        activateBusiness()
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.DAYS_30)
        val expired = retainedRef("draft-images/old.jpg", postedDaysAgo = 30)
        val fresh = retainedRef("draft-images/fresh.jpg", postedDaysAgo = 29)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(expired, fresh))
        retainedStore.putFile(expired.relativeFilePath)
        retainedStore.putFile(fresh.relativeFilePath)

        val report = useCase()

        assertEquals(1, report.retentionDeleteAttempted)
        assertEquals(1, report.retentionDeleted)
        assertEquals(0, report.retentionAlreadyAbsent)
        assertEquals(0, report.retentionDeleteFailed)
        assertEquals(listOf(expired.relativeFilePath), retainedStore.deletedPaths)
        // La superviviente se cifra en la misma pasada.
        assertEquals(1, report.encryptedMigrated)
        assertEquals(listOf(fresh.relativeFilePath), retainedStore.encryptedInPlacePaths)
    }

    @Test
    fun `aliases retenidos elegibles autorizan cada purga y borran el archivo una sola vez`() =
        runTest {
            activateBusiness()
            configuration.updateImageRetentionPolicy(ImageRetentionPolicy.DAYS_30)
            val sharedPath = "draft-images/legacy-shared-expired.jpg"
            val first = retainedRef(sharedPath, postedDaysAgo = 31)
            val second = retainedRef(sharedPath, postedDaysAgo = 40)
            purchaseReads.setRetainedImages(BUSINESS_ID, listOf(first, second))
            retainedStore.putFile(sharedPath)
            documentLifecycle.purgeResult = DocumentPurgeIntentResult.DURABLE

            val report = useCase()

            assertEquals(2, report.purgeIntentAttempted)
            assertEquals(2, report.purgeIntentDurable)
            assertEquals(2, report.retentionDeleted)
            assertEquals(listOf(sharedPath), retainedStore.deletedPaths)
            assertEquals(setOf(first.imageId, second.imageId), documentLifecycle.purgeCalls
                .mapTo(mutableSetOf()) { call -> call.image.imageId })
        }

    @Test
    fun `un alias retenido reciente protege el archivo sin crear retry infinito`() = runTest {
        activateBusiness()
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.DAYS_30)
        val sharedPath = "draft-images/legacy-shared-mixed-age.jpg"
        val expired = retainedRef(sharedPath, postedDaysAgo = 31)
        val fresh = retainedRef(sharedPath, postedDaysAgo = 2)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(expired, fresh))
        retainedStore.putFile(sharedPath)

        val report = useCase()

        assertEquals(1, report.retentionDeleteAttempted)
        assertEquals(1, report.retentionDeleteFailed)
        assertEquals(0, report.retentionDeleteRetryableFailed)
        assertFalse(report.hasRetryableLocalWork)
        assertTrue(retainedStore.exists(sharedPath))
        assertEquals(listOf(sharedPath), retainedStore.encryptedInPlacePaths)
        assertTrue(retainedStore.deletedPaths.isEmpty())
    }

    @Test
    fun `una fila Room ajena protege el path retenido aunque coincida el cardinal`() = runTest {
        activateBusiness()
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.DAYS_30)
        val sharedPath = "draft_images/${DRAFT_ID.value}/legacy-cross-owner.jpg"
        val expired = retainedRef(sharedPath, postedDaysAgo = 31)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(expired))
        retainedStore.putFile(sharedPath)
        createOpenDraftImage(
            draftId = DraftId.from(UUID(0L, 81L)),
            businessId = BUSINESS_ID,
            imageId = ImageId.from(UUID(0L, 82L)),
            path = sharedPath,
        )

        val report = useCase()

        assertEquals(1, report.retentionDeleteFailed)
        assertEquals(0, report.retentionDeleteRetryableFailed)
        assertTrue(retainedStore.exists(sharedPath))
        assertTrue(retainedStore.encryptedInPlacePaths.isEmpty())
        assertTrue(retainedStore.deletedPaths.isEmpty())
    }

    @Test
    fun `hook confirmado conserva path compartido por una fila Room ajena`() = runTest {
        val sharedPath = "draft_images/${DRAFT_ID.value}/legacy-confirm-alias.jpg"
        val retained = retainedRef(sharedPath, postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained))
        retainedStore.putFile(sharedPath)
        createOpenDraftImage(
            draftId = DraftId.from(UUID(0L, 83L)),
            businessId = BUSINESS_ID,
            imageId = ImageId.from(UUID(0L, 84L)),
            path = sharedPath,
        )

        useCase.applyToConfirmedDraft(
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            policy = ImageRetentionPolicy.AFTER_CONFIRM,
        )

        assertEquals(1, documentLifecycle.purgeCalls.size)
        assertTrue(retainedStore.exists(sharedPath))
        assertTrue(retainedStore.encryptedInPlacePaths.isEmpty())
        assertTrue(retainedStore.deletedPaths.isEmpty())
    }

    @Test
    fun `la politica global procesa tambien compras de un negocio no seleccionado`() = runTest {
        activateBusiness()
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.DAYS_30)
        val otherBusiness = BusinessId.from(UUID(0L, 21L))
        val expired = retainedRef(
            "draft-images/other-business-expired.jpg",
            postedDaysAgo = 30,
            businessId = otherBusiness,
        )
        purchaseReads.setRetainedImages(otherBusiness, listOf(expired))
        retainedStore.putFile(expired.relativeFilePath)
        documentLifecycle.purgeResult = DocumentPurgeIntentResult.DURABLE

        val report = useCase()

        assertEquals(1, report.retentionDeleted)
        assertEquals(otherBusiness, documentLifecycle.purgeCalls.single().businessId)
    }

    @Test
    fun `forceImageDeletion borra todas las retenidas sea cual sea la politica`() = runTest {
        activateBusiness()
        val first = retainedRef("draft-images/one.jpg", postedDaysAgo = 1)
        val second = retainedRef("draft-images/two.jpg", postedDaysAgo = 2)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(first, second))
        retainedStore.putFile(first.relativeFilePath)
        retainedStore.putFile(second.relativeFilePath)

        val report = useCase(forceImageDeletion = true)

        assertEquals(2, report.retentionDeleteAttempted)
        assertEquals(2, report.retentionDeleted)
        assertEquals(0, report.retentionAlreadyAbsent)
        assertEquals(0, report.retentionDeleteFailed)
        // Nada sobrevive: no hay migración de cifrado.
        assertEquals(0, report.encryptedMigrated)
    }

    @Test
    fun `force confirma wake durable antes de una falla posterior y el retry consume cutoff`() =
        runTest {
            privacyScheduler.onImmediate = {
                configuration.nextFailure =
                    com.facturastock.app.domain.error.StorageError.Unavailable
            }

            val failure = runCatching { useCase(forceImageDeletion = true) }.exceptionOrNull()

            assertTrue(failure != null)
            assertEquals(1, privacyScheduler.immediateCount)
            assertEquals(NOW, configuration.forceImageDeletionRequestedAt)

            privacyScheduler.onImmediate = {}
            val recovered = useCase()

            assertFalse(recovered.forceDeletionStillPending)
            assertEquals(null, configuration.forceImageDeletionRequestedAt)
        }

    @Test
    fun `force conserva checkpoint y propaga si WorkManager no confirma el wake`() = runTest {
        privacyScheduler.onImmediate = { error("WorkManager no disponible") }

        val failure = runCatching { useCase(forceImageDeletion = true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, privacyScheduler.immediateCount)
        assertEquals(NOW, configuration.forceImageDeletionRequestedAt)
    }

    @Test
    fun `force excluye imagenes creadas despues de su cutoff`() = runTest {
        configuration.requestForceImageDeletion(NOW)
        now = NOW.plusSeconds(1)
        val futureDraftPath = "draft_images/${DRAFT_ID.value}/future.jpg"
        createOpenDraftImage(
            DRAFT_ID,
            BUSINESS_ID,
            ImageId.from(UUID(0L, 101L)),
            futureDraftPath,
            createdAt = NOW.plusSeconds(1),
        )
        val futureRetained = retainedRef("draft-images/future.jpg", postedDaysAgo = 0).copy(
            sourceImageCreatedAt = NOW.plusSeconds(1),
        )
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(futureRetained))
        retainedStore.putFile(futureRetained.relativeFilePath)

        val report = useCase()

        assertEquals(0, report.draftDeleteAttempted)
        assertEquals(0, report.retentionDeleteAttempted)
        assertTrue(draftFiles.deletions.isEmpty())
        assertTrue(retainedStore.exists(futureRetained.relativeFilePath))
        assertFalse(report.forceDeletionStillPending)
        assertEquals(null, configuration.forceImageDeletionRequestedAt)
    }

    @Test
    fun `retry conserva identidad temporal al pasar de imagen fuente a compra confirmada`() =
        runTest {
            val path = "draft-images/transitioned.jpg"
            val transitioned = retainedRef(path, postedDaysAgo = -1).copy(
                sourceImageCreatedAt = NOW.minusSeconds(1),
            )
            purchaseReads.setRetainedImages(BUSINESS_ID, listOf(transitioned))
            retainedStore.putFile(path)
            retainedStore.failingPaths += path

            val first = useCase(forceImageDeletion = true)
            assertTrue(first.forceDeletionStillPending)
            assertEquals(NOW, configuration.forceImageDeletionRequestedAt)

            retainedStore.failingPaths -= path
            val retriedAfterRestart = useCase()

            assertEquals(1, retriedAfterRestart.retentionDeleted)
            assertFalse(retainedStore.exists(path))
            assertFalse(retriedAfterRestart.forceDeletionStillPending)
            assertEquals(null, configuration.forceImageDeletionRequestedAt)
        }

    @Test
    fun `borrado manual incluye borradores KEEP aunque no exista negocio activo`() = runTest {
        val path = "draft_images/${DRAFT_ID.value}/open.jpg"
        createOpenDraftImage(DRAFT_ID, BUSINESS_ID, ImageId.from(UUID(0L, 30L)), path)

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.draftDeleteAttempted)
        assertEquals(1, report.draftDeleted)
        assertEquals(0, report.draftAlreadyAbsent)
        assertEquals(0, report.draftDeleteFailed)
        assertEquals(listOf(listOf(path)), draftFiles.deletions)
        assertTrue(documentLifecycle.purgeCalls.isEmpty())
    }

    @Test
    fun `borrado manual incluye borradores abiertos de varios negocios sin purga cloud`() =
        runTest {
            val otherBusiness = BusinessId.from(UUID(0L, 31L))
            val otherDraft = DraftId.from(UUID(0L, 32L))
            val firstPath = "draft_images/${DRAFT_ID.value}/first.jpg"
            val secondPath = "draft_images/${otherDraft.value}/second.jpg"
            createOpenDraftImage(
                DRAFT_ID,
                BUSINESS_ID,
                ImageId.from(UUID(0L, 33L)),
                firstPath,
            )
            createOpenDraftImage(
                otherDraft,
                otherBusiness,
                ImageId.from(UUID(0L, 34L)),
                secondPath,
            )

            val report = useCase(forceImageDeletion = true)

            assertEquals(2, report.draftDeleteAttempted)
            assertEquals(2, report.draftDeleted)
            assertEquals(listOf(listOf(firstPath, secondPath)), draftFiles.deletions)
            assertTrue(documentLifecycle.purgeCalls.isEmpty())
        }

    @Test
    fun `borrado manual selecciona OCR abierto y confirmado pero difiere el que esta leyendo`() =
        runTest {
            val processingId = DraftId.from(UUID(0L, 71L))
            val committedId = DraftId.from(UUID(0L, 72L))
            drafts.createDraft(
                InvoiceDraft(
                    draftId = processingId,
                    businessId = BUSINESS_ID,
                    status = DraftStatus.CAPTURED,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            val processingRunId = OcrRunId.from(UUID(0L, 73L))
            assertTrue(drafts.beginOcrRun(processingId, processingRunId))
            ocrActivity.markActive(processingId, processingRunId)
            drafts.createDraft(
                InvoiceDraft(
                    draftId = committedId,
                    businessId = BUSINESS_ID,
                    status = DraftStatus.COMMITTED,
                    confirmedPurchaseId = PurchaseId.from(UUID(0L, 74L)),
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            sweep.allOcrCandidateIds = setOf(processingId, committedId)
            sweep.allDraftOcrResult = OcrVersionSweepReport(
                attempted = 2,
                deleted = 1,
                failed = 1,
                retryableFailed = 1,
            )

            val report = useCase(forceImageDeletion = true)

            assertEquals(setOf(processingId, committedId), sweep.lastAllOcrDraftIds)
            assertEquals(OcrVersionSweepDecision.RETRY_LATER, sweep.allOcrDecisions[processingId])
            assertEquals(OcrVersionSweepDecision.DELETE, sweep.allOcrDecisions[committedId])
            assertEquals(null, sweep.lastCommittedDraftIds)
            assertEquals(2, report.forcedOcrDeleteAttempted)
            assertEquals(1, report.forcedOcrDeleted)
            assertEquals(1, report.forcedOcrDeleteFailed)
            assertTrue(report.hasUnconfirmedLocalWork)
            assertTrue(report.hasRetryableLocalWork)
            assertTrue(report.forceDeletionStillPending)
            assertEquals(NOW, configuration.forceImageDeletionRequestedAt)
            ocrActivity.markFinished(processingId, processingRunId)
        }

    @Test
    fun `OCR_PROCESSING residual no bloquea force y el cutoff se consume`() = runTest {
        val processingId = DraftId.from(UUID(0L, 76L))
        val residualRunId = OcrRunId.from(UUID(0L, 77L))
        drafts.createDraft(
            InvoiceDraft(
                draftId = processingId,
                businessId = BUSINESS_ID,
                status = DraftStatus.CAPTURED,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        assertTrue(drafts.beginOcrRun(processingId, residualRunId))
        sweep.allOcrCandidateIds = setOf(processingId)
        sweep.allDraftOcrResult = OcrVersionSweepReport(attempted = 1, deleted = 1)

        val report = useCase(forceImageDeletion = true)

        assertEquals(OcrVersionSweepDecision.DELETE, sweep.allOcrDecisions[processingId])
        assertFalse(report.forceDeletionStillPending)
        assertEquals(null, configuration.forceImageDeletionRequestedAt)
    }

    @Test
    fun `fallo total del OCR forzado no impide borrar originales y queda visible`() = runTest {
        val path = "draft_images/${DRAFT_ID.value}/open-with-ocr.jpg"
        createOpenDraftImage(DRAFT_ID, BUSINESS_ID, ImageId.from(UUID(0L, 75L)), path)
        sweep.allDraftOcrFailure = IllegalStateException("fallo sintético")

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.draftDeleted)
        assertEquals(listOf(listOf(path)), draftFiles.deletions)
        assertTrue(PrivacyMaintenanceStep.FORCED_OCR_RUNS in report.failedSteps)
        assertTrue(report.hasUnconfirmedLocalWork)
    }

    @Test
    fun `borrado manual cubre drafts y compras terminales de dos negocios con tenant correcto`() =
        runTest {
            activateBusiness()
            val otherBusiness = BusinessId.from(UUID(0L, 41L))
            val otherDraft = DraftId.from(UUID(0L, 42L))
            val openPath = "draft_images/${otherDraft.value}/open.jpg"
            createOpenDraftImage(
                otherDraft,
                otherBusiness,
                ImageId.from(UUID(0L, 43L)),
                openPath,
            )
            val activeRetained = retainedRef(
                "draft_images/active-terminal.jpg",
                postedDaysAgo = 1,
            )
            val otherRetained = retainedRef(
                "draft_images/other-terminal.jpg",
                postedDaysAgo = 1,
                businessId = otherBusiness,
            )
            purchaseReads.setRetainedImages(BUSINESS_ID, listOf(activeRetained))
            purchaseReads.setRetainedImages(otherBusiness, listOf(otherRetained))
            retainedStore.putFile(activeRetained.relativeFilePath)
            retainedStore.putFile(otherRetained.relativeFilePath)
            documentLifecycle.purgeResult = DocumentPurgeIntentResult.DURABLE

            val report = useCase(forceImageDeletion = true)

            assertEquals(1, report.draftDeleted)
            assertEquals(2, report.retentionDeleted)
            assertEquals(
                listOf(BUSINESS_ID, otherBusiness),
                documentLifecycle.purgeCalls.map { it.businessId },
            )
            assertEquals(listOf(listOf(openPath)), draftFiles.deletions)
        }

    @Test
    fun `fallo al borrar una imagen de borrador queda visible y no falsea exito`() = runTest {
        val path = "draft_images/${DRAFT_ID.value}/stuck-open.jpg"
        createOpenDraftImage(DRAFT_ID, BUSINESS_ID, ImageId.from(UUID(0L, 35L)), path)
        draftFiles.deletionResults = mapOf(path to PrivateImageDeletionResult.FAILED)

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.draftDeleteAttempted)
        assertEquals(0, report.draftDeleted)
        assertEquals(1, report.draftDeleteFailed)
        assertTrue(report.hasUnconfirmedLocalWork)
    }

    @Test
    fun `purga durable se registra antes del borrado local y el ACK queda pendiente`() = runTest {
        activateBusiness()
        val retained = retainedRef("draft-images/remote.jpg", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained))
        retainedStore.putFile(retained.relativeFilePath)
        documentLifecycle.purgeResult = DocumentPurgeIntentResult.DURABLE
        val sequence = mutableListOf<String>()
        documentLifecycle.onEnsurePurge = { sequence += "purge" }
        retainedStore.beforeDelete = { sequence += "delete" }

        val report = useCase(forceImageDeletion = true)

        assertEquals(listOf("purge", "delete"), sequence)
        assertEquals(1, documentLifecycle.purgeCalls.size)
        assertEquals(1, report.purgeIntentAttempted)
        assertEquals(1, report.purgeIntentDurable)
        assertEquals(0, report.purgeIntentNotRequired)
        assertEquals(0, report.purgeIntentFailed)
        assertEquals(1, report.retentionDeleted)
        assertEquals(1, backupScheduler.privacyEnqueueCount)
    }

    @Test
    fun `excepcion local no reclasifica una purga durable ni rompe el reporte`() = runTest {
        activateBusiness()
        val retained = retainedRef("draft-images/delete-throws.jpg", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained))
        retainedStore.putFile(retained.relativeFilePath)
        retainedStore.throwingDeletePaths += retained.relativeFilePath
        documentLifecycle.purgeResult = DocumentPurgeIntentResult.DURABLE

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.purgeIntentDurable)
        assertEquals(0, report.purgeIntentFailed)
        assertEquals(1, report.retentionDeleteFailed)
        assertEquals(1, report.retentionDeleteRetryableFailed)
        assertTrue(report.hasRetryableLocalWork)
    }

    @Test
    fun `fallo al hacer durable la purga impide borrar local y queda separado`() = runTest {
        activateBusiness()
        val retained = retainedRef("draft-images/purge-failed.jpg", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained))
        retainedStore.putFile(retained.relativeFilePath)
        documentLifecycle.purgeFailure = IllegalStateException("sin espacio")

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.purgeIntentFailed)
        assertEquals(1, report.retentionDeleteFailed)
        assertTrue(retainedStore.exists(retained.relativeFilePath))
        assertTrue(retainedStore.deletedPaths.isEmpty())
        assertEquals(1, report.encryptionAttempted)
        assertEquals(1, report.encryptedMigrated)
        assertEquals(listOf(retained.relativeFilePath), retainedStore.encryptedInPlacePaths)
        assertEquals(0, backupScheduler.privacyEnqueueCount)
    }

    @Test
    fun `destino legacy desconocido conserva y cifra fuente sin inventar tenant`() = runTest {
        activateBusiness()
        val retained = retainedRef("draft-images/legacy-unknown.jpg", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained))
        retainedStore.putFile(retained.relativeFilePath)
        documentLifecycle.purgeResult = DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.purgeIntentLegacyDestinationUnknown)
        assertEquals(0, report.purgeIntentDurable)
        assertEquals(1, report.retentionDeleteFailed)
        assertTrue(retainedStore.exists(retained.relativeFilePath))
        assertEquals(listOf(retained.relativeFilePath), retainedStore.encryptedInPlacePaths)
        assertEquals(0, backupScheduler.privacyEnqueueCount)
        assertTrue(report.hasUnconfirmedLocalWork)
        assertFalse(report.hasRetryableLocalWork)
    }

    @Test
    fun `purga durable con derivado atascado borra original y conserva senal de retry`() = runTest {
        activateBusiness()
        val retained = retainedRef("draft-images/artifact-retry.jpg", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained))
        retainedStore.putFile(retained.relativeFilePath)
        documentLifecycle.purgeResult =
            DocumentPurgeIntentResult.DURABLE_ARTIFACT_RETRY_REQUIRED

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.purgeIntentDurable)
        assertEquals(1, report.retentionDeleted)
        assertEquals(1, report.orphanDocumentArtifactsFailed)
        assertFalse(retainedStore.exists(retained.relativeFilePath))
        assertEquals(1, backupScheduler.privacyEnqueueCount)
        assertTrue(report.hasUnconfirmedLocalWork)
    }

    @Test
    fun `barrido documental reporta derivados eliminados y fallos sin ocultarlos`() = runTest {
        documentLifecycle.artifactSweepResult = DocumentUploadArtifactSweepReport(
            attempted = 2,
            deleted = 1,
            failed = 1,
        )

        val report = useCase()

        assertEquals(1, report.orphanDocumentArtifactsDeleted)
        assertEquals(1, report.orphanDocumentArtifactsFailed)
        assertTrue(
            PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS in report.failedSteps,
        )
        assertTrue(report.hasUnconfirmedLocalWork)
    }

    @Test
    fun `excepcion de barrido documental deja senal y permite continuar otras etapas`() = runTest {
        documentLifecycle.artifactSweepFailure = IllegalStateException("disco no disponible")
        sweep.staleCacheEntriesResult = 2

        val report = useCase()

        assertEquals(2, report.staleCacheEntries)
        assertEquals(0, report.orphanDocumentArtifactsDeleted)
        assertEquals(0, report.orphanDocumentArtifactsFailed)
        assertTrue(
            PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS in report.failedSteps,
        )
    }

    @Test
    fun `un archivo no eliminable no detiene el resto del lote`() = runTest {
        activateBusiness()
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.DAYS_30)
        val stuck = retainedRef("draft-images/stuck.jpg", postedDaysAgo = 60)
        val normal = retainedRef("draft-images/normal.jpg", postedDaysAgo = 60)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(stuck, normal))
        retainedStore.putFile(stuck.relativeFilePath)
        retainedStore.putFile(normal.relativeFilePath)
        retainedStore.failingPaths += stuck.relativeFilePath

        val report = useCase()

        // El archivo atascado no cuenta como borrado y su cifrado tampoco pudo confirmarse.
        assertEquals(2, report.retentionDeleteAttempted)
        assertEquals(1, report.retentionDeleted)
        assertEquals(0, report.retentionAlreadyAbsent)
        assertEquals(1, report.retentionDeleteFailed)
        assertEquals(listOf(normal.relativeFilePath), retainedStore.deletedPaths)
        assertEquals(0, report.encryptedMigrated)
        assertEquals(1, report.encryptionAttempted)
        assertEquals(1, report.encryptionFailed)
        assertTrue(retainedStore.exists(stuck.relativeFilePath))
    }

    @Test
    fun `un archivo ya ausente se distingue de un fallo y confirma el objetivo local`() = runTest {
        activateBusiness()
        val absent = retainedRef("draft-images/already-absent.jpg", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(absent))

        val report = useCase(forceImageDeletion = true)

        assertEquals(1, report.retentionDeleteAttempted)
        assertEquals(0, report.retentionDeleted)
        assertEquals(1, report.retentionAlreadyAbsent)
        assertEquals(0, report.retentionDeleteFailed)
    }

    @Test
    fun `los barridos reciben los IDs de borradores de la base y su fallo cuenta cero`() = runTest {
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        sweep.orphanDraftDirsResult = -1
        sweep.committedOcrRunsResult = 4

        val report = useCase()

        assertEquals(setOf(DRAFT_ID), sweep.lastOrphanExistingDraftIds)
        assertEquals(0, report.orphanDraftDirs)
        assertEquals(4, report.committedOcrRuns)
        assertEquals(
            setOf(PrivacyMaintenanceStep.ORPHAN_DRAFT_DIRECTORIES),
            report.failedSteps,
        )
    }

    @Test
    fun `orphan sweep revalida un draft que estaba presente en el snapshot y desaparecio`() =
        runTest {
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            sweep.orphanCandidateIds = setOf(DRAFT_ID)
            sweep.beforeOrphanRevalidation = { check(drafts.deleteDraft(DRAFT_ID)) }

            useCase()

            assertEquals(setOf(DRAFT_ID), sweep.lastOrphanExistingDraftIds)
            assertEquals(false, sweep.orphanExistenceDecisions[DRAFT_ID])
        }

    @Test
    fun `unreferenced sweep enumera un directorio cuyo draft desaparecio tras el snapshot`() =
        runTest {
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            sweep.unreferencedCandidateIds = setOf(DRAFT_ID)
            sweep.beforeUnreferencedRevalidation = { check(drafts.deleteDraft(DRAFT_ID)) }

            useCase()

            assertEquals(setOf(DRAFT_ID), sweep.lastUnreferencedDraftIds)
            assertEquals(emptySet<String>(), sweep.unreferencedPathsByDraft[DRAFT_ID])
        }

    @Test
    fun `un sweep fallido se reporta y las otras etapas continuan`() = runTest {
        sweep.staleImportsResult = -1
        sweep.staleCacheEntriesResult = 2
        sweep.orphanDraftDirsResult = 3
        sweep.committedOcrRunsResult = 4

        val report = useCase()

        assertEquals(0, report.staleImports)
        assertEquals(2, report.staleCacheEntries)
        assertEquals(3, report.orphanDraftDirs)
        assertEquals(4, report.committedOcrRuns)
        assertEquals(setOf(PrivacyMaintenanceStep.STALE_IMPORTS), report.failedSteps)
        assertTrue(report.hasUnconfirmedLocalWork)
    }

    @Test
    fun `archivo ya ausente no crea un retry perpetuo de cifrado`() = runTest {
        activateBusiness()
        val missing = retainedRef("draft-images/missing.jpg", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(missing))

        val report = useCase()

        assertEquals(1, report.encryptionAttempted)
        assertEquals(0, report.encryptedMigrated)
        assertEquals(1, report.encryptionAlreadySatisfied)
        assertEquals(0, report.encryptionFailed)
        assertFalse(report.hasUnconfirmedLocalWork)
        assertFalse(report.hasRetryableLocalWork)
    }

    @Test
    fun `envelope cifrado corrupto se reporta y nunca se sobrescribe`() = runTest {
        activateBusiness()
        val corrupt = retainedRef("draft-images/corrupt.fse", postedDaysAgo = 1)
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(corrupt))
        retainedStore.putCorruptFile(corrupt.relativeFilePath)

        val report = useCase()

        assertEquals(1, report.encryptionAttempted)
        assertEquals(0, report.encryptedMigrated)
        assertEquals(0, report.encryptionAlreadySatisfied)
        assertEquals(1, report.encryptionFailed)
        assertEquals(0, report.encryptionRetryableFailed)
        assertTrue(retainedStore.encryptedInPlacePaths.isEmpty())
        assertTrue(retainedStore.exists(corrupt.relativeFilePath))
        assertTrue(report.hasUnconfirmedLocalWork)
        assertFalse(report.hasRetryableLocalWork)
    }

    @Test
    fun `cambio tardio a AFTER_OCR borra originales de un draft cuyo OCR ya fue publicado`() =
        runTest {
            createPublishedOcrDraft("draft_images/${DRAFT_ID.value}/page.jpg")

            // KEEP no toca el original del flujo todavía abierto.
            useCase()
            assertTrue(draftFiles.deletions.isEmpty())

            configuration.updateImageRetentionPolicy(ImageRetentionPolicy.AFTER_OCR)
            val report = useCase()

            assertEquals(1, report.afterOcrDeleteAttempted)
            assertEquals(1, report.afterOcrDeleted)
            assertEquals(0, report.afterOcrAlreadyAbsent)
            assertEquals(0, report.afterOcrDeleteFailed)
            assertEquals(
                listOf(listOf("draft_images/${DRAFT_ID.value}/page.jpg")),
                draftFiles.deletions,
            )
        }

    @Test
    fun `fallback manual sin snapshot OCR no habilita el borrado AFTER_OCR`() = runTest {
        val path = "draft_images/${DRAFT_ID.value}/manual.jpg"
        createPublishedOcrDraft(
            path = path,
            status = DraftStatus.NEEDS_REVIEW,
            snapshotPublished = false,
        )
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.AFTER_OCR)

        val report = useCase()

        assertEquals(0, report.afterOcrDeleteAttempted)
        assertTrue(draftFiles.deletions.isEmpty())
    }

    @Test
    fun `mantenimiento repara el fallo del hook AFTER_OCR`() = runTest {
        val path = "draft_images/${DRAFT_ID.value}/hook-failed.jpg"
        createPublishedOcrDraft(path)
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.AFTER_OCR)
        draftFiles.nextFailure = com.facturastock.app.domain.error.StorageError.Unavailable

        ApplyImageRetentionAfterOcrUseCase(configuration, draftFiles, drafts)(
            listOf(requireNotNull(drafts.observeImages(DRAFT_ID).first().single())),
        )
        val report = useCase()

        assertEquals(1, report.afterOcrDeleted)
        assertEquals(listOf(listOf(path)), draftFiles.deletions)
    }

    @Test
    fun `reinicio vuelve a intentar un original AFTER_OCR cuyo borrado no se confirmo`() = runTest {
        val path = "draft_images/${DRAFT_ID.value}/retry.jpg"
        createPublishedOcrDraft(path)
        configuration.updateImageRetentionPolicy(ImageRetentionPolicy.AFTER_OCR)
        draftFiles.deletionResults = mapOf(path to PrivateImageDeletionResult.FAILED)

        val failed = useCase()
        assertEquals(1, failed.afterOcrDeleteAttempted)
        assertEquals(1, failed.afterOcrDeleteFailed)

        draftFiles.deletionResults = mapOf(path to PrivateImageDeletionResult.DELETED)
        val retried = useCase()
        assertEquals(1, retried.afterOcrDeleteAttempted)
        assertEquals(1, retried.afterOcrDeleted)
        assertEquals(2, draftFiles.deletions.size)
    }

    private suspend fun createPublishedOcrDraft(
        path: String,
        status: DraftStatus = DraftStatus.OCR_READY,
        snapshotPublished: Boolean = true,
    ) {
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                // Las imágenes solo pueden publicarse mientras el borrador sigue abierto.
                // Después simulamos el snapshot OCR ya publicado.
                status = DraftStatus.CAPTURED,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        drafts.seedImage(
            InvoiceImage(
                imageId = ImageId.from(UUID(0L, 3L)),
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                pageIndex = 0,
                filePath = path,
                sha256 = "a".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 100,
                heightPx = 100,
                fileSizeBytes = 100,
                createdAt = NOW,
            ),
        )
        check(drafts.updateDraft(requireNotNull(drafts.findDraft(DRAFT_ID)).copy(status = status)))
        if (snapshotPublished) drafts.markOcrSnapshotPublished(DRAFT_ID)
    }

    private suspend fun createOpenDraftImage(
        draftId: DraftId,
        businessId: BusinessId,
        imageId: ImageId,
        path: String,
        createdAt: Instant = NOW,
    ) {
        drafts.createDraft(
            InvoiceDraft(
                draftId = draftId,
                businessId = businessId,
                createdAt = createdAt,
                updatedAt = NOW,
            ),
        )
        drafts.seedImage(
            InvoiceImage(
                imageId = imageId,
                draftId = draftId,
                businessId = businessId,
                pageIndex = 0,
                filePath = path,
                sha256 = "d".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 100,
                heightPx = 100,
                fileSizeBytes = 100,
                createdAt = createdAt,
            ),
        )
    }

    private suspend fun activateBusiness() {
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            CostPolicy.NET,
        )
    }

    private var refSequence = 0L

    private fun retainedRef(
        relativePath: String,
        postedDaysAgo: Long,
        businessId: BusinessId = BUSINESS_ID,
    ): RetainedImageRef {
        val sequence = ++refSequence
        return RetainedImageRef(
            businessId = businessId,
            purchaseId = PurchaseId.from(UUID(0L, sequence)),
            draftId = DRAFT_ID,
            imageId = ImageId.from(UUID(1L, sequence)),
            relativeFilePath = relativePath,
            postedAt = NOW.minus(Duration.ofDays(postedDaysAgo)),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-17T00:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 1L))
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 2L))
    }

    private class RecordingPrivacyMaintenanceScheduler : PrivacyMaintenanceScheduler {
        var immediateCount: Int = 0
            private set
        var onImmediate: suspend () -> Unit = {}

        override suspend fun enqueue() = Unit

        override suspend fun enqueueImmediate() {
            immediateCount++
            onImmediate()
        }
    }
}
