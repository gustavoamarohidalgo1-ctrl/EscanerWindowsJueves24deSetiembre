package com.facturastock.app.domain.usecase

import com.facturastock.app.data.reporting.ConsentAwareProductionObservability
import com.facturastock.app.data.reporting.ObservabilitySink
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.PurchaseDuplicateReason
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.repository.ConfirmPurchaseCommand
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.PurchaseConfirmationBlocker
import com.facturastock.app.domain.repository.PurchaseConfirmationContext
import com.facturastock.app.domain.repository.PurchasePostingRepository
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDocumentBackupLifecycleRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakeRetainedImageStore
import com.facturastock.app.testing.FakeRetentionFileSweep
import com.facturastock.app.testing.RecordingProductionObservability
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmPurchaseUseCaseTest {
    private val configuration = FakeAppConfigurationRepository()
    private val posting = RecordingPurchasePostingRepository()
    private val scheduler = FakePurchaseBackupScheduler()
    private val draftFiles = FakeDraftFileStore()
    private val drafts = FakeInvoiceDraftRepository { NOW }
    private val purchaseReads = FakePurchaseReadRepository()
    private val retentionSweep = FakeRetentionFileSweep()
    private val retainedStore = FakeRetainedImageStore()
    private val documentLifecycle = FakeDocumentBackupLifecycleRepository()
    private val privacyScheduler = RecordingPrivacyMaintenanceScheduler()
    private val privacyMaintenance = RunPrivacyMaintenanceUseCase(
        appConfigurationRepository = configuration,
        invoiceDraftRepository = drafts,
        purchaseReadRepository = purchaseReads,
        retentionFileSweep = retentionSweep,
        draftFileStore = draftFiles,
        retainedImageStore = retainedStore,
        documentLifecycle = documentLifecycle,
        purchaseBackupScheduler = scheduler,
        appClock = { NOW },
    )
    private val observability = RecordingProductionObservability()
    private val useCase = ConfirmPurchaseUseCase(
        configuration,
        posting,
        scheduler,
        ApplyImageRetentionAfterConfirmUseCase(
            configuration,
            draftFiles,
            privacyMaintenance,
            purchaseReads,
            retainedStore,
            privacyScheduler,
        ),
        observability,
    )

    @Test
    fun `sin negocio activo bloquea sin llamar al puerto`() = runTest {
        val result = useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(
            ConfirmPurchaseResult.Blocked(
                setOf(PurchaseConfirmationBlocker.NoActiveBusiness),
            ),
            result,
        )
        assertEquals(emptyList<PostingCall>(), posting.calls)
    }

    @Test
    fun `delega draft contexto y autorizacion exacta intacta al puerto`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.GROSS,
        )
        posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)

        val result = useCase(DRAFT_ID, PREPARED_HASH, DUPLICATE_OVERRIDE)

        assertEquals(ConfirmPurchaseResult.Posted(PURCHASE_ID), result)
        assertEquals(
            listOf(
                PostingCall(
                    command = ConfirmPurchaseCommand(
                        draftId = DRAFT_ID,
                        expectedPreparedLogicalHash = PREPARED_HASH,
                        duplicateOverride = DUPLICATE_OVERRIDE,
                    ),
                    context = PurchaseConfirmationContext(
                        activeBusinessId = BUSINESS_ID,
                        costPolicy = CostPolicy.GROSS,
                    ),
                ),
            ),
            posting.calls,
        )
        val delegated = posting.calls.single().command.duplicateOverride
        assertEquals("owner-31", delegated?.actor?.actorId)
        assertEquals(PurchaseOverrideRole.OWNER, delegated?.actor?.role)
        assertEquals("Duplicado validado contra el original", delegated?.reason)
        val audit = observability.records.single().event
        assertEquals(OperationalAction.PURCHASE_CONFIRMATION, audit.action)
        assertEquals(OperationalOutcome.SUCCEEDED, audit.outcome)
        assertEquals(BUSINESS_ID, audit.identifiers.businessId)
        assertEquals(DRAFT_ID, audit.identifiers.draftId)
        assertEquals(PURCHASE_ID, audit.identifiers.purchaseId)
        assertEquals(false, audit.toString().contains("Duplicado validado contra el original"))
    }

    @Test
    fun `conserva AlreadyPosted como exito idempotente con el mismo purchase id`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.NET,
        )
        posting.nextResult = ConfirmPurchaseResult.AlreadyPosted(PURCHASE_ID)

        val result = useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(ConfirmPurchaseResult.AlreadyPosted(PURCHASE_ID), result)
        assertEquals(1, posting.calls.size)
        assertEquals(
            ConfirmPurchaseCommand(DRAFT_ID, PREPARED_HASH),
            posting.calls.single().command,
        )
    }

    @Test
    fun `publicar encola el drenado de la outbox y un bloqueo no`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.NET,
        )
        posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)

        val result = useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(ConfirmPurchaseResult.Posted(PURCHASE_ID), result)
        assertEquals(1, scheduler.enqueueCount)
    }

    @Test
    fun `fallo de WorkManager tras el commit conserva el resultado publicado`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.NET,
        )
        posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)
        scheduler.enqueueFailure = IllegalStateException("workmanager storage unavailable")

        val result = useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(ConfirmPurchaseResult.Posted(PURCHASE_ID), result)
        assertEquals(1, posting.calls.size)
    }

    @Test
    fun `un bloqueo del dominio no encola ningun drenado`() = runTest {
        val result = useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(
            ConfirmPurchaseResult.Blocked(setOf(PurchaseConfirmationBlocker.NoActiveBusiness)),
            result,
        )
        assertEquals(0, scheduler.enqueueCount)
    }

    @Test
    fun `tras publicar con politica por defecto solo se purgan las versiones OCR`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.NET,
        )
        posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)

        useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(listOf(DRAFT_ID), draftFiles.ocrVersionDeletions)
        assertEquals(emptyList<DraftId>(), draftFiles.draftTreeDeletions)
    }

    @Test
    fun `KEEP cifra inmediatamente solo las paginas de la compra confirmada`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.NET,
        )
        val current = RetainedImageRef(
            businessId = BUSINESS_ID,
            purchaseId = PURCHASE_ID,
            draftId = DRAFT_ID,
            imageId = IMAGE_ID,
            relativeFilePath = "draft_images/${DRAFT_ID.value}/page.jpg",
            postedAt = NOW,
        )
        val unrelatedDraft = DraftId.from(UUID(0L, 91L))
        val unrelated = current.copy(
            purchaseId = PurchaseId.from(UUID(0L, 92L)),
            draftId = unrelatedDraft,
            imageId = ImageId.from(UUID(0L, 93L)),
            relativeFilePath = "draft_images/${unrelatedDraft.value}/page.jpg",
        )
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(current, unrelated))
        retainedStore.putFile(current.relativeFilePath)
        retainedStore.putFile(unrelated.relativeFilePath)
        posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)

        useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(listOf(current.relativeFilePath), retainedStore.encryptedInPlacePaths)
        assertEquals(listOf(DRAFT_ID), draftFiles.ocrVersionDeletions)
    }

    @Test
    fun `cambio KEEP a AFTER_CONFIRM crea purga durable antes de borrar la fuente`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.NET,
        )
        val retained = RetainedImageRef(
            businessId = BUSINESS_ID,
            purchaseId = PURCHASE_ID,
            draftId = DRAFT_ID,
            imageId = IMAGE_ID,
            relativeFilePath = "draft_images/${DRAFT_ID.value}/page.jpg",
            postedAt = NOW,
        )
        val unrelated = retained.copy(
            draftId = OTHER_DRAFT_ID,
            imageId = OTHER_IMAGE_ID,
            relativeFilePath = "draft_images/${OTHER_DRAFT_ID.value}/unrelated.jpg",
        )
        purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained, unrelated))
        retainedStore.putFile(retained.relativeFilePath)
        retainedStore.putFile(unrelated.relativeFilePath)
        documentLifecycle.purgeResult = DocumentPurgeIntentResult.DURABLE
        val order = mutableListOf<String>()
        documentLifecycle.onEnsurePurge = { order += "purge" }
        retainedStore.beforeDelete = { order += "delete" }
        posting.afterConfirm = {
            configuration.updateImageRetentionPolicy(ImageRetentionPolicy.AFTER_CONFIRM)
        }
        posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)

        useCase(DRAFT_ID, PREPARED_HASH)

        assertEquals(ImageRetentionPolicy.KEEP, posting.calls.single().context.imageRetentionPolicy)
        assertEquals(ImageRetentionPolicy.AFTER_CONFIRM, configuration.current().imageRetentionPolicy)
        assertEquals(listOf("purge", "delete"), order)
        assertEquals(1, documentLifecycle.purgeCalls.size)
        assertEquals(false, retainedStore.exists(retained.relativeFilePath))
        assertEquals(true, retainedStore.exists(unrelated.relativeFilePath))
        assertEquals(1, privacyScheduler.immediateCount)
        assertEquals(emptyList<DraftId>(), draftFiles.draftTreeDeletions)
    }

    @Test
    fun `fallo de purga post commit conserva fuente y mantenimiento posterior reintenta`() =
        runTest {
            configuration.completeOnboarding(
                businessId = BUSINESS_ID,
                taxRate = AppConfiguration.DEFAULT_TAX_RATE,
                costPolicy = CostPolicy.NET,
            )
            val retained = RetainedImageRef(
                businessId = BUSINESS_ID,
                purchaseId = PURCHASE_ID,
                draftId = DRAFT_ID,
                imageId = IMAGE_ID,
                relativeFilePath = "draft_images/${DRAFT_ID.value}/retry.jpg",
                postedAt = NOW,
            )
            purchaseReads.setRetainedImages(BUSINESS_ID, listOf(retained))
            retainedStore.putFile(retained.relativeFilePath)
            documentLifecycle.purgeFailure = IllegalStateException("Room no disponible")
            posting.afterConfirm = {
                configuration.updateImageRetentionPolicy(ImageRetentionPolicy.AFTER_CONFIRM)
            }
            posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)

            val posted = useCase(DRAFT_ID, PREPARED_HASH)

            assertEquals(ConfirmPurchaseResult.Posted(PURCHASE_ID), posted)
            assertEquals(true, retainedStore.exists(retained.relativeFilePath))

            documentLifecycle.purgeFailure = null
            documentLifecycle.purgeResult = DocumentPurgeIntentResult.DURABLE
            val retry = privacyMaintenance()

            assertEquals(1, retry.purgeIntentDurable)
            assertEquals(1, retry.retentionDeleted)
            assertEquals(false, retainedStore.exists(retained.relativeFilePath))
        }

    @Test
    fun `un fallo del hook de archivos nunca rompe la confirmacion`() = runTest {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = CostPolicy.NET,
        )
        posting.nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)
        draftFiles.nextFailure = StorageError.Unavailable

        val result = useCase(DRAFT_ID, PREPARED_HASH)

        // El commit ya era durable: el hook mejor esfuerzo traga el fallo y el drenado sigue.
        assertEquals(ConfirmPurchaseResult.Posted(PURCHASE_ID), result)
        assertEquals(1, scheduler.enqueueCount)
    }

    @Test
    fun `confirmacion conserva resultado y efectos con telemetria normal off o sink defectuoso`() =
        runTest {
            val normalSink = CountingSink()
            val offSink = CountingSink()
            val throwingSink = CountingSink(throwOnEmit = true)

            val normal = runConfirmation(diagnosticsEnabled = true, sink = normalSink)
            val off = runConfirmation(diagnosticsEnabled = false, sink = offSink)
            val throwing = runConfirmation(diagnosticsEnabled = true, sink = throwingSink)

            assertEquals(normal, off)
            assertEquals(normal, throwing)
            assertEquals(1, normalSink.emissionAttempts)
            assertEquals(0, offSink.emissionAttempts)
            assertEquals(1, throwingSink.emissionAttempts)
        }

    private suspend fun runConfirmation(
        diagnosticsEnabled: Boolean,
        sink: CountingSink,
    ): ConfirmationSnapshot {
        val configuration = FakeAppConfigurationRepository().apply {
            completeOnboarding(
                businessId = BUSINESS_ID,
                taxRate = AppConfiguration.DEFAULT_TAX_RATE,
                costPolicy = CostPolicy.NET,
            )
            updateDiagnosticsEnabled(diagnosticsEnabled)
        }
        val posting = RecordingPurchasePostingRepository().apply {
            nextResult = ConfirmPurchaseResult.Posted(PURCHASE_ID)
        }
        val scheduler = FakePurchaseBackupScheduler()
        val files = FakeDraftFileStore()
        val result = ConfirmPurchaseUseCase(
            appConfigurationRepository = configuration,
            purchasePostingRepository = posting,
            purchaseBackupScheduler = scheduler,
            applyImageRetentionAfterConfirm = ApplyImageRetentionAfterConfirmUseCase(
                configuration,
                files,
            ),
            observability = ConsentAwareProductionObservability(configuration, sink),
        )(DRAFT_ID, PREPARED_HASH)
        return ConfirmationSnapshot(
            result = result,
            postingCalls = posting.calls.toList(),
            schedulerEnqueueCount = scheduler.enqueueCount,
            ocrVersionDeletions = files.ocrVersionDeletions,
            draftTreeDeletions = files.draftTreeDeletions,
        )
    }

    private data class ConfirmationSnapshot(
        val result: ConfirmPurchaseResult,
        val postingCalls: List<PostingCall>,
        val schedulerEnqueueCount: Int,
        val ocrVersionDeletions: List<DraftId>,
        val draftTreeDeletions: List<DraftId>,
    )

    private class CountingSink(
        private val throwOnEmit: Boolean = false,
    ) : ObservabilitySink {
        var emissionAttempts: Int = 0
            private set

        override fun setCollectionEnabled(enabled: Boolean) = Unit

        override fun emit(
            eventName: String,
            attributes: Map<String, String>,
            isFailure: Boolean,
        ) {
            emissionAttempts++
            if (throwOnEmit) error("observability sink unavailable")
        }
    }

    private class RecordingPrivacyMaintenanceScheduler : PrivacyMaintenanceScheduler {
        var immediateCount: Int = 0
            private set

        override suspend fun enqueue() = Unit

        override suspend fun enqueueImmediate() {
            immediateCount++
        }
    }

    private data class PostingCall(
        val command: ConfirmPurchaseCommand,
        val context: PurchaseConfirmationContext,
    )

    private class RecordingPurchasePostingRepository : PurchasePostingRepository {
        val calls = mutableListOf<PostingCall>()
        var nextResult: ConfirmPurchaseResult = ConfirmPurchaseResult.RetryableConflict
        var afterConfirm: (suspend () -> Unit)? = null

        override suspend fun confirm(
            command: ConfirmPurchaseCommand,
            context: PurchaseConfirmationContext,
        ): ConfirmPurchaseResult {
            calls += PostingCall(command, context)
            afterConfirm?.invoke()
            return nextResult
        }
    }

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000031"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000039"),
        )
        val OTHER_DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000041"),
        )
        val OTHER_IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000049"),
        )
        val NOW: Instant = Instant.parse("2026-08-22T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000032"),
        )
        val PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000033"),
        )
        val EXISTING_PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000034"),
        )
        const val PREPARED_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val DUPLICATE_OVERRIDE = PurchaseDuplicateOverride(
            draftId = DRAFT_ID,
            preparedLogicalHash = PREPARED_HASH,
            businessId = BUSINESS_ID,
            existingPurchaseId = EXISTING_PURCHASE_ID,
            duplicateKind = PurchaseDuplicateKind.EXACT,
            reasons = setOf(
                PurchaseDuplicateReason.SAME_BUSINESS,
                PurchaseDuplicateReason.SAME_SUPPLIER,
                PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
                PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER,
            ),
            reason = "Duplicado validado contra el original",
            actor = PurchaseOverrideActor(
                actorId = "owner-31",
                role = PurchaseOverrideRole.OWNER,
            ),
        )
    }
}
