package com.facturastock.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotPageEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.data.repository.DocumentBackupPayloadCodec
import com.facturastock.app.data.repository.RoomAuditTrailRepository
import com.facturastock.app.data.repository.RoomCloudBusinessBindingRepository
import com.facturastock.app.data.repository.RoomDocumentBackupLifecycleRepository
import com.facturastock.app.data.repository.RoomPurchasePostingRepository
import com.facturastock.app.data.repository.RoomPurchaseReadRepository
import com.facturastock.app.data.repository.RoomPurchaseVoidRepository
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.AuditEventWrite
import com.facturastock.app.domain.repository.AuditPayloadKey
import com.facturastock.app.domain.repository.ConfirmPurchaseCommand
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.DocumentUploadArtifactSweepReport
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.DocumentUploadSource
import com.facturastock.app.domain.repository.PreparedDocumentUpload
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseConfirmationContext
import com.facturastock.app.domain.repository.PurchaseVoidCommand
import com.facturastock.app.domain.repository.PurchaseVoidResult
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Consulta de imágenes retenidas para el mantenimiento de privacidad contra Room real:
 * solo compras terminales (POSTED/VOIDED) del negocio, con una fila por imagen del borrador
 * origen y el `postedAt` de la compra.
 */
@RunWith(AndroidJUnit4::class)
class PurchaseRetentionQueryTest {
    private lateinit var database: FacturaStockDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FacturaStockDatabase::class.java,
        )
            .addCallback(postingPersistenceCallback)
            .build()
        database.businessDao().insert(
            BusinessEntity(
                businessId = BUSINESS_ID,
                legalName = "Negocio retención de prueba",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = SUPPLIER_ID,
                businessId = BUSINESS_ID,
                legalName = SUPPLIER_LEGAL_NAME,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
                ruc = SUPPLIER_RUC,
            ),
        )
        database.unitDao().insert(
            UnitEntity(
                unitId = UNIT_ID,
                businessId = BUSINESS_ID,
                code = "NIU",
                name = "Unidad",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = LOCATION_ID,
                businessId = BUSINESS_ID,
                name = "Almacén retención",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        database.productDao().insert(
            ProductEntity(
                productId = PRODUCT_ID,
                businessId = BUSINESS_ID,
                unitId = UNIT_ID,
                name = "Producto de retención",
                locationId = LOCATION_ID,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun onlyTerminalPurchasesOfTheBusinessContributeRetainedImages() = runBlocking {
        val postedPrepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val voidedPrepared = seedPreparedDraft(DRAFT_VOIDED, documentSuffix = 2)
        val postedDraft = postedPrepared.draftId.value
        val voidedDraft = voidedPrepared.draftId.value
        val openDraft = seedDraft(DRAFT_OPEN)
        seedImage(postedDraft, pageIndex = 0)
        seedImage(postedDraft, pageIndex = 1)
        seedImage(voidedDraft, pageIndex = 0)
        seedImage(openDraft, pageIndex = 0)
        val postedPurchaseId = postPreparedPurchase(postedPrepared)
        val voidedPurchaseId = postPreparedPurchase(voidedPrepared)
        voidPurchase(voidedPurchaseId)
        // Una compra todavía DRAFT no es terminal: sus imágenes no son retenidas.
        database.purchaseDao().insert(openPurchase(PURCHASE_OPEN, openDraft, documentSuffix = 3))

        val rows = database.purchaseDao().listRetainedImagesForRetention(BUSINESS_ID)

        assertEquals(3, rows.size)
        assertEquals(
            mapOf(postedPurchaseId to 2, voidedPurchaseId to 1),
            rows.groupingBy { it.purchaseId }.eachCount(),
        )
        assertTrue(rows.none { it.purchaseId == PURCHASE_OPEN })
        // Cada fila trae la ruta relativa de la imagen y el postedAt de su compra.
        val postedRow = rows.first { it.purchaseId == postedPurchaseId }
        assertEquals("draft_images/$postedDraft/page-0.jpg", postedRow.filePath)
        assertEquals(POSTED_AT, postedRow.postedAt)
        assertEquals(postedDraft, postedRow.sourceDraftId)

        val postCommitRows = database.purchaseDao().listRetainedImagesForDraft(
            BUSINESS_ID,
            postedDraft,
        )
        assertEquals(2, postCommitRows.size)
        assertTrue(postCommitRows.all { it.purchaseId == postedPurchaseId })
        assertTrue(
            database.purchaseDao().listRetainedImagesForDraft(BUSINESS_ID, openDraft).isEmpty(),
        )
    }

    @Test
    fun aPurchaseWithoutImagesContributesNoRows() = runBlocking {
        postPreparedPurchase(seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1))

        assertTrue(database.purchaseDao().listRetainedImagesForRetention(BUSINESS_ID).isEmpty())
    }

    @Test
    fun retainedDocumentBootstrapIsCutoffAwareAndIdempotent() = runBlocking {
        val prepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val imageId = seedImage(prepared.draftId.value, pageIndex = 0)
        val purchaseId = postPreparedPurchase(prepared)
        val repository = documentLifecycleRepository()
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val requestedAt = Instant.ofEpochMilli(BOOTSTRAP_AT)

        assertEquals(
            0,
            repository.ensureRetainedUploads(
                businessId = businessId,
                postedAfterExclusive = Instant.ofEpochMilli(POSTED_AT),
                requestedAt = requestedAt,
            ),
        )
        assertEquals(
            1,
            repository.ensureRetainedUploads(
                businessId = businessId,
                postedAfterExclusive = Instant.ofEpochMilli(POSTED_AT - 1L),
                requestedAt = requestedAt,
            ),
        )
        assertEquals(
            0,
            repository.ensureRetainedUploads(
                businessId = businessId,
                postedAfterExclusive = null,
                requestedAt = requestedAt.plusSeconds(1L),
            ),
        )

        val upload = database.outboxOperationDao().findForEntityVersion(
            businessId = BUSINESS_ID,
            entityType = "DOCUMENT",
            entityId = imageId,
            entityVersion = 1L,
        )
        assertEquals(purchaseId, upload?.purchaseId)
        assertEquals("SYNC_DOCUMENT_UPLOAD", upload?.operationType)
        assertEquals(OutboxOperationStatus.PENDING.name, upload?.status)
        assertEquals(
            DocumentBackupPayloadCodec.candidateOperationId(purchaseId, imageId),
            upload?.operationId,
        )
        assertTrue(DocumentBackupPayloadCodec.decodeUpload(requireNotNull(upload).payload) != null)
    }

    @Test
    fun remoteReadEligibilityRequiresCompletedPinnedUploadAndRejectsAnyPurge() = runBlocking {
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(
                localBusinessId = BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                createdAt = CREATED_AT,
                boundLegacyOperationCount = 0,
            ),
        )
        val prepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val imageId = seedImage(prepared.draftId.value, pageIndex = 0)
        val purchaseId = postPreparedPurchase(
            prepared = prepared,
            context = PurchaseConfirmationContext(
                activeBusinessId = prepared.businessId,
                costPolicy = CostPolicy.NET,
                backupEnabled = true,
                documentBackupEnabled = true,
                imageRetentionPolicy = ImageRetentionPolicy.KEEP,
            ),
        )
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val parsedPurchaseId = requireNotNull(PurchaseId.parse(purchaseId))
        val parsedImageId = requireNotNull(ImageId.parse(imageId))
        val repository = documentLifecycleRepository()
        val outbox = database.outboxOperationDao()
        val upload = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 1L),
        )
        assertEquals(CLOUD_BUSINESS_ID, upload.targetCloudBusinessId)
        assertFalse(repository.isBackedUp(businessId, parsedPurchaseId, parsedImageId))

        val claimToken = UUID(0L, 998L).toString()
        assertEquals(
            1,
            outbox.claim(
                operationId = upload.operationId,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = BOOTSTRAP_AT,
                claimToken = claimToken,
                claimLeaseUntil = BOOTSTRAP_AT + 1_000L,
                targetCloudBusinessId = CLOUD_BUSINESS_ID,
            ),
        )
        assertEquals(
            1,
            outbox.complete(
                operationId = upload.operationId,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                completedStatus = OutboxOperationStatus.COMPLETED.name,
                completedAt = BOOTSTRAP_AT + 1L,
                claimToken = claimToken,
            ),
        )
        assertTrue(repository.isBackedUp(businessId, parsedPurchaseId, parsedImageId))

        assertEquals(
            DocumentPurgeIntentResult.DURABLE,
            repository.ensurePurge(
                businessId = businessId,
                image = RetainedImageRef(
                    businessId = businessId,
                    purchaseId = parsedPurchaseId,
                    draftId = prepared.draftId,
                    imageId = parsedImageId,
                    relativeFilePath = "draft_images/${prepared.draftId.value}/page-0.jpg",
                    postedAt = Instant.ofEpochMilli(POSTED_AT),
                ),
                requestedAt = Instant.ofEpochMilli(PURGE_AT),
            ),
        )
        val purge = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 2L),
        )
        assertEquals(CLOUD_BUSINESS_ID, purge.targetCloudBusinessId)
        assertFalse(repository.isBackedUp(businessId, parsedPurchaseId, parsedImageId))
    }

    @Test
    fun neverAttemptedLegacyUploadIsResolvedWithoutInventingTenantOrPurge() = runBlocking {
        val prepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val imageId = seedImage(prepared.draftId.value, pageIndex = 0)
        val purchaseId = postPreparedPurchase(prepared)
        val repository = documentLifecycleRepository()
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        assertEquals(
            1,
            repository.ensureRetainedUploads(
                businessId,
                postedAfterExclusive = null,
                requestedAt = Instant.ofEpochMilli(BOOTSTRAP_AT),
            ),
        )

        assertEquals(
            DocumentPurgeIntentResult.NOT_REQUIRED,
            repository.ensurePurge(
                businessId,
                retainedImage(prepared, purchaseId, imageId),
                Instant.ofEpochMilli(PURGE_AT),
            ),
        )

        val upload = requireNotNull(
            database.outboxOperationDao().findForEntityVersion(
                BUSINESS_ID,
                "DOCUMENT",
                imageId,
                1L,
            ),
        )
        assertEquals(OutboxOperationStatus.RESOLVED.name, upload.status)
        assertEquals(null, upload.targetCloudBusinessId)
        assertEquals(
            null,
            database.outboxOperationDao().findForEntityVersion(
                BUSINESS_ID,
                "DOCUMENT",
                imageId,
                2L,
            ),
        )
        assertEquals(
            DocumentPurgeIntentResult.NOT_REQUIRED,
            repository.ensurePurge(
                businessId,
                retainedImage(prepared, purchaseId, imageId),
                Instant.ofEpochMilli(PURGE_AT + 1L),
            ),
        )

        val bindingRepository = RoomCloudBusinessBindingRepository(
            database = database,
            bindings = database.cloudBusinessBindingDao(),
            outbox = database.outboxOperationDao(),
            catalogLinks = database.catalogSyncLinkDao(),
            dispatchers = DefaultDispatcherProvider(),
        )
        assertEquals(
            CloudBusinessBindingResult.Bound,
            bindingRepository.bindOnce(
                businessId,
                requireNotNull(BusinessId.parse(CLOUD_BUSINESS_ID)),
                Instant.ofEpochMilli(PURGE_AT + 1L),
            ),
        )
        val suppressedUpload = database.outboxOperationDao().findForEntityVersion(
            BUSINESS_ID,
            "DOCUMENT",
            imageId,
            1L,
        )
        assertEquals(OutboxOperationStatus.RESOLVED.name, suppressedUpload?.status)
        assertEquals(null, suppressedUpload?.targetCloudBusinessId)
        assertEquals(
            DocumentPurgeIntentResult.NOT_REQUIRED,
            repository.ensurePurge(
                businessId,
                retainedImage(prepared, purchaseId, imageId),
                Instant.ofEpochMilli(PURGE_AT + 2L),
            ),
        )
        assertEquals(
            null,
            database.outboxOperationDao().findForEntityVersion(
                BUSINESS_ID,
                "DOCUMENT",
                imageId,
                2L,
            ),
        )
    }

    @Test
    fun documentOptOutReconcilesAJustSuppressedAttemptWithADurablePurge() = runBlocking {
        insertCloudBinding()
        val prepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val imageId = seedImage(prepared.draftId.value, pageIndex = 0)
        postPreparedPurchase(
            prepared = prepared,
            context = PurchaseConfirmationContext(
                activeBusinessId = prepared.businessId,
                costPolicy = CostPolicy.NET,
                backupEnabled = true,
                documentBackupEnabled = true,
                imageRetentionPolicy = ImageRetentionPolicy.KEEP,
            ),
        )
        val outbox = database.outboxOperationDao()
        val upload = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 1L),
        )
        val claimToken = UUID(0L, 995L).toString()
        assertEquals(
            1,
            outbox.claim(
                operationId = upload.operationId,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = BOOTSTRAP_AT + 1L,
                claimToken = claimToken,
                claimLeaseUntil = BOOTSTRAP_AT + 2_000L,
                targetCloudBusinessId = CLOUD_BUSINESS_ID,
            ),
        )
        // Reproduce el orden peligroso: un intento anterior pudo llegar a Storage, el ACK se
        // perdió y la compuerta fresca de consentimiento resuelve el retry antes del withdraw.
        assertEquals(
            1,
            outbox.resolveSuppressed(
                operationId = upload.operationId,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                resolvedStatus = OutboxOperationStatus.RESOLVED.name,
                resolvedAt = BOOTSTRAP_AT + 2L,
                claimToken = claimToken,
            ),
        )
        assertEquals(
            null,
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 2L),
        )

        assertEquals(
            1,
            documentLifecycleRepository().withdrawOpenUploads(
                requireNotNull(BusinessId.parse(BUSINESS_ID)),
                Instant.ofEpochMilli(PURGE_AT),
            ),
        )

        val purge = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 2L),
        )
        assertEquals("SYNC_DOCUMENT_PURGE", purge.operationType)
        assertEquals(OutboxOperationStatus.PENDING.name, purge.status)
        assertEquals(CLOUD_BUSINESS_ID, purge.targetCloudBusinessId)
        assertEquals(OutboxOperationStatus.RESOLVED.name, outbox.findById(upload.operationId)?.status)
    }

    @Test
    fun globalDocumentOptOutTombstonesCompletedAndInflightUploadsAcrossBusinesses() = runBlocking {
        insertCloudBinding()
        insertSecondBusinessGraph()
        insertCloudBinding(SECOND_BUSINESS_ID, SECOND_CLOUD_BUSINESS_ID)

        val firstPrepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val firstImageId = seedImage(firstPrepared.draftId.value, pageIndex = 0)
        postPreparedPurchase(
            prepared = firstPrepared,
            context = documentBackupContext(firstPrepared.businessId),
        )
        val secondPrepared = seedPreparedDraft(
            draftId = SECOND_DRAFT_POSTED,
            documentSuffix = 7,
            businessIdRaw = SECOND_BUSINESS_ID,
            supplierIdRaw = SECOND_SUPPLIER_ID,
            unitIdRaw = SECOND_UNIT_ID,
            productIdRaw = SECOND_PRODUCT_ID,
            supplierRuc = SECOND_SUPPLIER_RUC,
            supplierLegalName = SECOND_SUPPLIER_LEGAL_NAME,
        )
        val secondImageId = seedImage(
            draftId = secondPrepared.draftId.value,
            pageIndex = 0,
            businessId = SECOND_BUSINESS_ID,
        )
        postPreparedPurchase(
            prepared = secondPrepared,
            context = documentBackupContext(secondPrepared.businessId),
        )

        val outbox = database.outboxOperationDao()
        val completedUpload = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", firstImageId, 1L),
        )
        val completedClaim = UUID(0L, 993L).toString()
        assertEquals(
            1,
            outbox.claim(
                operationId = completedUpload.operationId,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = BOOTSTRAP_AT,
                claimToken = completedClaim,
                claimLeaseUntil = BOOTSTRAP_AT + 2_000L,
                targetCloudBusinessId = CLOUD_BUSINESS_ID,
            ),
        )
        assertEquals(
            1,
            outbox.complete(
                operationId = completedUpload.operationId,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                completedStatus = OutboxOperationStatus.COMPLETED.name,
                completedAt = BOOTSTRAP_AT + 1L,
                claimToken = completedClaim,
            ),
        )

        val inflightUpload = requireNotNull(
            outbox.findForEntityVersion(SECOND_BUSINESS_ID, "DOCUMENT", secondImageId, 1L),
        )
        val inflightClaim = UUID(0L, 994L).toString()
        assertEquals(
            1,
            outbox.claim(
                operationId = inflightUpload.operationId,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = BOOTSTRAP_AT,
                claimToken = inflightClaim,
                claimLeaseUntil = BOOTSTRAP_AT + 2_000L,
                targetCloudBusinessId = SECOND_CLOUD_BUSINESS_ID,
            ),
        )
        val artifacts = FakeDocumentUploadPreparer().apply {
            this.artifacts += setOf(firstImageId, secondImageId)
            failSweep = true
        }

        val changed = documentLifecycleRepository(artifacts).withdrawAllOpenUploads(
            Instant.ofEpochMilli(PURGE_AT),
        )

        assertEquals(2, changed)
        val firstPurge = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", firstImageId, 2L),
        )
        val secondPurge = requireNotNull(
            outbox.findForEntityVersion(SECOND_BUSINESS_ID, "DOCUMENT", secondImageId, 2L),
        )
        assertEquals(CLOUD_BUSINESS_ID, firstPurge.targetCloudBusinessId)
        assertEquals(SECOND_CLOUD_BUSINESS_ID, secondPurge.targetCloudBusinessId)
        assertEquals(OutboxOperationStatus.PENDING.name, firstPurge.status)
        assertEquals(OutboxOperationStatus.PENDING.name, secondPurge.status)
        // El ACK histórico sigue siendo evidencia; el intento en vuelo pierde su claim. Los
        // tombstones ya son durables antes de cualquiera de estas transiciones locales.
        assertEquals(
            OutboxOperationStatus.COMPLETED.name,
            outbox.findById(completedUpload.operationId)?.status,
        )
        val withdrawnInflight = requireNotNull(outbox.findById(inflightUpload.operationId))
        assertEquals(OutboxOperationStatus.RESOLVED.name, withdrawnInflight.status)
        assertEquals(null, withdrawnInflight.claimToken)
        // Un cleanup local fallido no transforma un commit remoto pendiente en error: ambos
        // purges ya son drenables. El replay posterior solo limpia artefactos y no duplica filas.
        assertEquals(setOf(firstImageId, secondImageId), artifacts.artifacts)
        artifacts.failSweep = false
        assertEquals(
            0,
            documentLifecycleRepository(artifacts).withdrawAllOpenUploads(
                Instant.ofEpochMilli(PURGE_AT + 1L),
            ),
        )
        assertTrue(artifacts.artifacts.isEmpty())
    }

    @Test
    fun attemptedLegacyUploadWithUnknownTenantBlocksPurgeAndPreservesUpload() = runBlocking {
        val prepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val imageId = seedImage(prepared.draftId.value, pageIndex = 0)
        val purchaseId = postPreparedPurchase(prepared)
        val repository = documentLifecycleRepository()
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        repository.ensureRetainedUploads(
            businessId,
            postedAfterExclusive = null,
            requestedAt = Instant.ofEpochMilli(BOOTSTRAP_AT),
        )
        val outbox = database.outboxOperationDao()
        val uploadBeforeClaim = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 1L),
        )
        val claimToken = UUID(0L, 996L).toString()
        assertEquals(
            1,
            outbox.claim(
                operationId = uploadBeforeClaim.operationId,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = BOOTSTRAP_AT + 1L,
                claimToken = claimToken,
                claimLeaseUntil = BOOTSTRAP_AT + 2_000L,
                targetCloudBusinessId = null,
            ),
        )

        assertEquals(
            DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN,
            repository.ensurePurge(
                businessId,
                retainedImage(prepared, purchaseId, imageId),
                Instant.ofEpochMilli(PURGE_AT),
            ),
        )

        val uploadAfter = requireNotNull(
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 1L),
        )
        assertEquals(OutboxOperationStatus.PROCESSING.name, uploadAfter.status)
        assertEquals(claimToken, uploadAfter.claimToken)
        assertEquals(null, uploadAfter.targetCloudBusinessId)
        assertEquals(
            null,
            outbox.findForEntityVersion(BUSINESS_ID, "DOCUMENT", imageId, 2L),
        )
    }

    @Test
    fun purgeWithoutPriorUploadDurablySuppressesConcurrentAndFutureBootstrap() = runBlocking {
        insertCloudBinding()
        val prepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val imageId = seedImage(prepared.draftId.value, pageIndex = 0)
        val purchaseId = postPreparedPurchase(prepared)
        val repository = documentLifecycleRepository()
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val image = RetainedImageRef(
            businessId = businessId,
            purchaseId = requireNotNull(PurchaseId.parse(purchaseId)),
            draftId = prepared.draftId,
            imageId = requireNotNull(ImageId.parse(imageId)),
            relativeFilePath = "draft_images/${prepared.draftId.value}/page-0.jpg",
            postedAt = Instant.ofEpochMilli(POSTED_AT),
        )

        assertEquals(
            DocumentPurgeIntentResult.DURABLE,
            repository.ensurePurge(businessId, image, Instant.ofEpochMilli(BOOTSTRAP_AT)),
        )
        val firstPurge = requireNotNull(
            database.outboxOperationDao().findForEntityVersion(
                BUSINESS_ID,
                "DOCUMENT",
                imageId,
                2L,
            ),
        )
        assertEquals(CLOUD_BUSINESS_ID, firstPurge.targetCloudBusinessId)

        // Simula tanto el bootstrap concurrente inmediatamente posterior al commit del
        // tombstone como una activación futura: ninguno puede resucitar la versión upload.
        assertEquals(
            0,
            repository.ensureRetainedUploads(
                businessId = businessId,
                postedAfterExclusive = null,
                requestedAt = Instant.ofEpochMilli(BOOTSTRAP_AT),
            ),
        )
        assertEquals(
            0,
            repository.ensureRetainedUploads(
                businessId = businessId,
                postedAfterExclusive = null,
                requestedAt = Instant.ofEpochMilli(PURGE_AT),
            ),
        )
        assertEquals(
            null,
            database.outboxOperationDao().findForEntityVersion(
                BUSINESS_ID,
                "DOCUMENT",
                imageId,
                1L,
            ),
        )

        assertEquals(
            DocumentPurgeIntentResult.DURABLE,
            repository.ensurePurge(
                businessId,
                image,
                Instant.ofEpochMilli(PURGE_AT).plusSeconds(1L),
            ),
        )
        val replayedPurge = database.outboxOperationDao().findForEntityVersion(
            BUSINESS_ID,
            "DOCUMENT",
            imageId,
            2L,
        )
        assertEquals(firstPurge.operationId, replayedPurge?.operationId)
        assertEquals(firstPurge.idempotencyKey, replayedPurge?.idempotencyKey)
    }

    @Test
    fun durablePurgeRetriesEncryptedArtifactDeletionAfterOfflineRestart() = runBlocking {
        insertCloudBinding()
        val prepared = seedPreparedDraft(DRAFT_POSTED, documentSuffix = 1)
        val imageId = seedImage(prepared.draftId.value, pageIndex = 0)
        val purchaseId = postPreparedPurchase(
            prepared = prepared,
            context = PurchaseConfirmationContext(
                activeBusinessId = prepared.businessId,
                costPolicy = CostPolicy.NET,
                backupEnabled = true,
                documentBackupEnabled = true,
                imageRetentionPolicy = ImageRetentionPolicy.KEEP,
            ),
        )
        val artifactStore = FakeDocumentUploadPreparer().apply {
            artifacts += imageId
            failDiscard = true
        }
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val image = RetainedImageRef(
            businessId = businessId,
            purchaseId = requireNotNull(PurchaseId.parse(purchaseId)),
            draftId = prepared.draftId,
            imageId = requireNotNull(ImageId.parse(imageId)),
            relativeFilePath = "draft_images/${prepared.draftId.value}/page-0.jpg",
            postedAt = Instant.ofEpochMilli(POSTED_AT),
        )

        val firstResult = documentLifecycleRepository(artifactStore).ensurePurge(
            businessId,
            image,
            Instant.ofEpochMilli(PURGE_AT),
        )

        assertEquals(
            DocumentPurgeIntentResult.DURABLE_ARTIFACT_RETRY_REQUIRED,
            firstResult,
        )
        assertTrue(imageId in artifactStore.artifacts)
        val durableBeforeRestart = requireNotNull(
            database.outboxOperationDao().findForEntityVersion(
                BUSINESS_ID,
                "DOCUMENT",
                imageId,
                2L,
            ),
        )
        assertEquals(OutboxOperationStatus.PENDING.name, durableBeforeRestart.status)

        artifactStore.failDiscard = false
        val replay = documentLifecycleRepository(artifactStore).ensurePurge(
            businessId,
            image,
            Instant.ofEpochMilli(PURGE_AT + 1L),
        )

        assertEquals(DocumentPurgeIntentResult.DURABLE, replay)
        assertFalse(imageId in artifactStore.artifacts)
        val durableAfterRestart = requireNotNull(
            database.outboxOperationDao().findForEntityVersion(
                BUSINESS_ID,
                "DOCUMENT",
                imageId,
                2L,
            ),
        )
        assertEquals(durableBeforeRestart.operationId, durableAfterRestart.operationId)
        assertEquals(2, artifactStore.discardCalls)
    }

    @Test
    fun postingCreatesDocumentCandidateOnlyWithBothOptInsAndRetainedPolicy() = runBlocking {
        val cases = listOf(
            Triple(false, ImageRetentionPolicy.KEEP, false),
            Triple(true, ImageRetentionPolicy.KEEP, true),
            Triple(true, ImageRetentionPolicy.DAYS_30, true),
            Triple(true, ImageRetentionPolicy.DAYS_90, true),
            Triple(true, ImageRetentionPolicy.AFTER_OCR, false),
            Triple(true, ImageRetentionPolicy.AFTER_CONFIRM, false),
        )

        cases.forEachIndexed { index, (documentsEnabled, policy, expectedCandidate) ->
            val draftId = UUID(0L, 50L + index).toString()
            val prepared = seedPreparedDraft(draftId, documentSuffix = index + 1)
            val imageId = seedImage(draftId, pageIndex = 0)
            postPreparedPurchase(
                prepared = prepared,
                context = PurchaseConfirmationContext(
                    activeBusinessId = prepared.businessId,
                    costPolicy = CostPolicy.NET,
                    backupEnabled = true,
                    documentBackupEnabled = documentsEnabled,
                    imageRetentionPolicy = policy,
                ),
            )

            val candidate = database.outboxOperationDao().findForEntityVersion(
                businessId = BUSINESS_ID,
                entityType = "DOCUMENT",
                entityId = imageId,
                entityVersion = 1L,
            )
            assertEquals(
                "documentBackup=$documentsEnabled policy=$policy",
                expectedCandidate,
                candidate != null,
            )
        }
    }

    @Test
    fun publishedOcrQueryIncludesOnlyOpenDraftsPastTheOcrBoundary() = runBlocking {
        val eligible = listOf(
            DraftStatus.OCR_READY,
            DraftStatus.NEEDS_REVIEW,
            DraftStatus.READY_TO_POST,
        )
        // COMMITTED solo se alcanza por el posting atómico; el trigger impide fabricar ese
        // estado en INSERT. Esta consulta cubre todos los estados que pueden iniciar abiertos.
        val allStatuses = DraftStatus.entries.filterNot { it == DraftStatus.COMMITTED }
        allStatuses.forEachIndexed { index, status ->
            val draftId = UUID(0L, 700L + index).toString()
            database.invoiceDraftDao().insert(
                InvoiceDraftEntity(
                    draftId = draftId,
                    businessId = BUSINESS_ID,
                    status = status.name,
                    createdAt = CREATED_AT,
                    updatedAt = CREATED_AT,
                ),
            )
            val imageId = seedImage(draftId, pageIndex = 0)
            if (status in eligible) seedPublishedOcrSnapshot(draftId, imageId, index)
        }
        val manualFallbackDraftId = UUID(0L, 799L).toString()
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = manualFallbackDraftId,
                businessId = BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW.name,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        seedImage(manualFallbackDraftId, pageIndex = 0)
        val mismatchedSnapshotDraftId = UUID(0L, 798L).toString()
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = mismatchedSnapshotDraftId,
                businessId = BUSINESS_ID,
                status = DraftStatus.OCR_READY.name,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        seedImage(mismatchedSnapshotDraftId, pageIndex = 0)
        seedPublishedOcrSnapshot(
            draftId = mismatchedSnapshotDraftId,
            imageId = UUID(0L, 797L).toString(),
            sequence = 99,
        )

        val rows = database.invoiceDraftDao().listImagesForDraftStatuses(
            eligible.map { it.name },
        )

        assertEquals(eligible.size, rows.size)
        assertTrue(rows.none { it.draftId == manualFallbackDraftId })
        assertTrue(rows.none { it.draftId == mismatchedSnapshotDraftId })
        assertEquals(
            eligible.mapTo(mutableSetOf()) { status ->
                val index = allStatuses.indexOf(status)
                "draft_images/${UUID(0L, 700L + index)}/page-0.jpg"
            },
            rows.mapTo(mutableSetOf()) { it.filePath },
        )
    }

    @Test
    fun businessAuditExportIncludesEventsWithoutPurchase() = runBlocking {
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        RoomAuditTrailRepository(
            auditEvents = database.auditEventDao(),
            dispatchers = DefaultDispatcherProvider(),
        ).record(
            AuditEventWrite(
                auditEventId = AUDIT_RECONCILED,
                businessId = businessId,
                purchaseId = null,
                eventType = AuditEventType.SYNC_RECONCILED,
                entityType = "business",
                entityId = businessId.value,
                payload = mapOf(
                    AuditPayloadKey.VERSION to "1",
                    AuditPayloadKey.LATEST_SEQ to "7",
                    AuditPayloadKey.MATCHED_COUNT to "4",
                    AuditPayloadKey.AMBIGUOUS_COUNT to "2",
                    AuditPayloadKey.REMOTE_ONLY_COUNT to "1",
                    AuditPayloadKey.BALANCE_DIFFERENCES_COUNT to "2",
                    AuditPayloadKey.UNLINKED_PRODUCTS_COUNT to "0",
                    AuditPayloadKey.AMBIGUOUS_PRODUCTS_COUNT to "1",
                ),
                occurredAt = Instant.ofEpochMilli(BOOTSTRAP_AT),
            ),
        )

        val events = RoomPurchaseReadRepository(
            database = database,
            dispatchers = DefaultDispatcherProvider(),
        ).listAuditEvents(businessId)

        assertEquals(1, events.size)
        assertEquals(AUDIT_RECONCILED, events.single().auditEventId)
        assertEquals(null, events.single().purchaseId)
        assertEquals(AuditEventType.SYNC_RECONCILED, events.single().eventType)
    }

    private suspend fun seedPublishedOcrSnapshot(
        draftId: String,
        imageId: String,
        sequence: Int,
    ) {
        database.invoiceOcrSnapshotDao().insertHeader(
            InvoiceOcrSnapshotEntity(
                draftId = draftId,
                runId = UUID(0L, 800L + sequence).toString(),
                completedAt = CREATED_AT,
                codecVersion = 1,
                pageCount = 1,
            ),
        )
        database.invoiceOcrSnapshotDao().insertPages(
            listOf(
                InvoiceOcrSnapshotPageEntity(
                    draftId = draftId,
                    pageIndex = 0,
                    sourceImageId = imageId,
                    widthPx = 1_200,
                    heightPx = 1_600,
                    payloadSha256 = "%064x".format(900L + sequence),
                    payload = byteArrayOf(1),
                ),
            ),
        )
    }

    private suspend fun seedDraft(draftId: String): String {
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = draftId,
                businessId = BUSINESS_ID,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        return draftId
    }

    private var imageSequence = 0L

    private suspend fun seedImage(
        draftId: String,
        pageIndex: Int,
        businessId: String = BUSINESS_ID,
    ): String {
        val imageId = "00000000-0000-4000-8000-${"%012d".format(100L + (++imageSequence))}"
        database.invoiceImageDao().insert(
            InvoiceImageEntity(
                imageId = imageId,
                draftId = draftId,
                businessId = businessId,
                pageIndex = pageIndex,
                filePath = "draft_images/$draftId/page-$pageIndex.jpg",
                sha256 = "%064x".format(imageSequence),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = 4_000L,
                createdAt = CREATED_AT,
            ),
        )
        return imageId
    }

    private suspend fun seedPreparedDraft(
        draftId: String,
        documentSuffix: Int,
        businessIdRaw: String = BUSINESS_ID,
        supplierIdRaw: String = SUPPLIER_ID,
        unitIdRaw: String = UNIT_ID,
        productIdRaw: String = PRODUCT_ID,
        supplierRuc: String = SUPPLIER_RUC,
        supplierLegalName: String = SUPPLIER_LEGAL_NAME,
    ): PreparedPurchase {
        val currency = CurrencyCode.of("PEN")
        val parsedDraftId = requireNotNull(DraftId.parse(draftId))
        val businessId = requireNotNull(BusinessId.parse(businessIdRaw))
        val supplierId = requireNotNull(SupplierId.parse(supplierIdRaw))
        val documentNumber = "F001-00012$documentSuffix"
        val issueDate = LocalDate.parse("2026-08-10")
        val lines = listOf(
            PreparedPurchaseLine(
                lineId = requireNotNull(
                    LineId.parse(UUID(0L, 30L + documentSuffix).toString()),
                ),
                position = 0,
                productId = requireNotNull(ProductId.parse(productIdRaw)),
                unitId = requireNotNull(UnitId.parse(unitIdRaw)),
                description = "Producto de retención",
                rawText = "1 PRODUCTO RETENCION 10.00",
                quantity = Quantity.of("1"),
                unitCost = UnitCost.of("10.00", currency),
                tax = Money.ofMinor(180L, currency),
                lineTotal = Money.ofMinor(1_180L, currency),
                linkConfidence = 1_000,
                taxTreatment = InventoryTaxTreatment.EXCLUDED,
                taxEvidence = InventoryTaxEvidence.ExplicitAmount(
                    Money.ofMinor(180L, currency).toMajor(),
                ),
                productProvenance = PurchaseProductProvenance.EXISTING,
            ),
        )
        val subtotal = Money.ofMinor(1_000L, currency)
        val tax = Money.ofMinor(180L, currency)
        val otherCharges = Money.zero(currency)
        val total = Money.ofMinor(1_180L, currency)
        val logicalHash = PreparedPurchase.logicalHash(
            draftId = parsedDraftId,
            businessId = businessId,
            supplierId = supplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = supplierLegalName,
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = documentNumber,
            issueDate = issueDate,
            currency = currency,
            lines = lines,
            subtotal = subtotal,
            tax = tax,
            otherCharges = otherCharges,
            total = total,
            acceptedWarnings = emptyList(),
        )
        val prepared = PreparedPurchase(
            draftId = parsedDraftId,
            businessId = businessId,
            supplierId = supplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = supplierLegalName,
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = documentNumber,
            issueDate = issueDate,
            currency = currency,
            lines = lines,
            subtotal = subtotal,
            tax = tax,
            otherCharges = otherCharges,
            total = total,
            acceptedWarnings = emptyList(),
            logicalHash = logicalHash,
            preparedAt = Instant.ofEpochMilli(PREPARED_AT),
        )
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = draftId,
                businessId = businessIdRaw,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
                status = DraftStatus.READY_TO_POST.name,
                supplierId = supplierIdRaw,
                supplierRucNormalized = supplierRuc,
                supplierLegalNameNormalized = supplierLegalName.lowercase(),
                documentType = PurchaseDocumentType.INVOICE.name,
                documentNumberNormalized = documentNumber,
                issueDateNormalized = issueDate.toString(),
                currencyCode = currency.value,
                subtotalMinorUnits = subtotal.minorUnits,
                taxMinorUnits = tax.minorUnits,
                otherChargesMinorUnits = otherCharges.minorUnits,
                totalMinorUnits = total.minorUnits,
                headerConfidence = 1_000,
            ),
        )
        val payload = PreparedPurchaseCodec.encode(prepared)
        database.preparedPurchaseDao().upsert(
            PreparedPurchaseEntity(
                draftId = draftId,
                logicalHash = logicalHash,
                payloadCodecVersion = PreparedPurchaseCodec.VERSION,
                payloadSha256 = PreparedPurchaseCodec.sha256(payload),
                payload = payload,
                preparedAt = PREPARED_AT,
            ),
        )
        return prepared
    }

    private suspend fun postPreparedPurchase(
        prepared: PreparedPurchase,
        context: PurchaseConfirmationContext = PurchaseConfirmationContext(
            activeBusinessId = prepared.businessId,
            costPolicy = CostPolicy.NET,
        ),
    ): String {
        val result = RoomPurchasePostingRepository(
            database = database,
            appClock = AppClock { Instant.ofEpochMilli(POSTED_AT) },
            dispatchers = DefaultDispatcherProvider(),
        ).confirm(
            command = ConfirmPurchaseCommand(
                draftId = prepared.draftId,
                expectedPreparedLogicalHash = prepared.logicalHash,
            ),
            context = context,
        )
        check(result is ConfirmPurchaseResult.Posted) {
            "El fixture no pudo publicar la compra: $result"
        }
        return result.purchaseId.value
    }

    private suspend fun voidPurchase(purchaseId: String) {
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val parsedPurchaseId = requireNotNull(PurchaseId.parse(purchaseId))
        val actor = PurchaseOverrideActor("retention-owner", PurchaseOverrideRole.OWNER)
        val repository = RoomPurchaseVoidRepository(
            database = database,
            appClock = AppClock { Instant.ofEpochMilli(VOIDED_AT) },
            dispatchers = DefaultDispatcherProvider(),
        )
        val preview = repository.preview(businessId, parsedPurchaseId, actor)
        check(preview is PreviewPurchaseVoidResult.Ready) {
            "El fixture no pudo previsualizar la anulación: $preview"
        }
        val result = repository.void(
            PurchaseVoidCommand(
                businessId = businessId,
                purchaseId = parsedPurchaseId,
                reason = "Compra anulada para probar la retención",
                actor = actor,
                expectedImpactHash = preview.preview.expectedImpactHash,
            ),
        )
        check(result is PurchaseVoidResult.Voided) {
            "El fixture no pudo anular la compra: $result"
        }
    }

    private fun openPurchase(
        purchaseId: String,
        draftId: String,
        documentSuffix: Int,
    ): PurchaseEntity = PurchaseEntity(
        purchaseId = purchaseId,
        businessId = BUSINESS_ID,
        sourceDraftId = draftId,
        supplierId = SUPPLIER_ID,
        documentType = "INVOICE",
        documentSeries = "F001",
        documentNumber = "00012$documentSuffix",
        issueDate = "2026-08-10",
        currencyCode = "PEN",
        subtotalMinorUnits = 1_000L,
        taxMinorUnits = 180L,
        otherChargesMinorUnits = 0L,
        totalMinorUnits = 1_180L,
        status = PurchaseStatus.DRAFT.name,
        idempotencyKey = "idem-$purchaseId",
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

    private suspend fun insertCloudBinding(
        localBusinessId: String = BUSINESS_ID,
        cloudBusinessId: String = CLOUD_BUSINESS_ID,
    ) {
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(
                localBusinessId = localBusinessId,
                cloudBusinessId = cloudBusinessId,
                createdAt = CREATED_AT,
                boundLegacyOperationCount = 0,
            ),
        )
    }

    private suspend fun insertSecondBusinessGraph() {
        database.businessDao().insert(
            BusinessEntity(
                businessId = SECOND_BUSINESS_ID,
                legalName = "Segundo negocio retención",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = SECOND_SUPPLIER_ID,
                businessId = SECOND_BUSINESS_ID,
                legalName = SECOND_SUPPLIER_LEGAL_NAME,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
                ruc = SECOND_SUPPLIER_RUC,
            ),
        )
        database.unitDao().insert(
            UnitEntity(
                unitId = SECOND_UNIT_ID,
                businessId = SECOND_BUSINESS_ID,
                code = "BX2",
                name = "Unidad B",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = SECOND_LOCATION_ID,
                businessId = SECOND_BUSINESS_ID,
                name = "Almacén B",
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
        database.productDao().insert(
            ProductEntity(
                productId = SECOND_PRODUCT_ID,
                businessId = SECOND_BUSINESS_ID,
                unitId = SECOND_UNIT_ID,
                name = "Producto B",
                locationId = SECOND_LOCATION_ID,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )
    }

    private fun documentBackupContext(businessId: BusinessId): PurchaseConfirmationContext =
        PurchaseConfirmationContext(
            activeBusinessId = businessId,
            costPolicy = CostPolicy.NET,
            backupEnabled = true,
            documentBackupEnabled = true,
            imageRetentionPolicy = ImageRetentionPolicy.KEEP,
        )

    private fun retainedImage(
        prepared: PreparedPurchase,
        purchaseId: String,
        imageId: String,
    ): RetainedImageRef = RetainedImageRef(
        businessId = prepared.businessId,
        purchaseId = requireNotNull(PurchaseId.parse(purchaseId)),
        draftId = prepared.draftId,
        imageId = requireNotNull(ImageId.parse(imageId)),
        relativeFilePath = "draft_images/${prepared.draftId.value}/page-0.jpg",
        postedAt = Instant.ofEpochMilli(POSTED_AT),
    )

    private fun documentLifecycleRepository(
        documentUploadPreparer: DocumentUploadPreparer? = null,
    ) = RoomDocumentBackupLifecycleRepository(
        database = database,
        dispatchers = DefaultDispatcherProvider(),
        documentUploadPreparer = documentUploadPreparer
            ?: com.facturastock.app.domain.repository.DisabledDocumentUploadPreparer,
    )

    private class FakeDocumentUploadPreparer : DocumentUploadPreparer {
        val artifacts = mutableSetOf<String>()
        var failDiscard: Boolean = false
        var failSweep: Boolean = false
        var discardCalls: Int = 0

        override suspend fun prepare(source: DocumentUploadSource): PreparedDocumentUpload? = null

        override suspend fun discard(imageId: String): PrivateImageDeletionResult {
            discardCalls++
            if (failDiscard) return PrivateImageDeletionResult.FAILED
            return if (artifacts.remove(imageId)) {
                PrivateImageDeletionResult.DELETED
            } else {
                PrivateImageDeletionResult.ALREADY_ABSENT
            }
        }

        override suspend fun sweepOrphans(
            retainedImageIds: Set<ImageId>,
        ): DocumentUploadArtifactSweepReport {
            val orphans = artifacts.filter { raw ->
                retainedImageIds.none { retained -> retained.value == raw }
            }
            if (failSweep) {
                return DocumentUploadArtifactSweepReport(
                    attempted = orphans.size,
                    failed = orphans.size,
                )
            }
            artifacts.removeAll(orphans.toSet())
            return DocumentUploadArtifactSweepReport(
                attempted = orphans.size,
                deleted = orphans.size,
            )
        }
    }

    private companion object {
        const val CREATED_AT = 1_000L
        const val PREPARED_AT = 1_500L
        const val POSTED_AT = 2_000L
        const val VOIDED_AT = 3_000L
        const val BOOTSTRAP_AT = 4_000L
        const val PURGE_AT = 5_000L
        const val SUPPLIER_RUC = "20123456789"
        const val SUPPLIER_LEGAL_NAME = "Proveedor Retención SA"
        const val SECOND_SUPPLIER_RUC = "20987654321"
        const val SECOND_SUPPLIER_LEGAL_NAME = "Proveedor Retención B SA"
        val BUSINESS_ID: String = UUID(0L, 1L).toString()
        val CLOUD_BUSINESS_ID: String = UUID(0L, 901L).toString()
        val SECOND_BUSINESS_ID: String = UUID(0L, 1_001L).toString()
        val SECOND_CLOUD_BUSINESS_ID: String = UUID(0L, 1_901L).toString()
        val SUPPLIER_ID: String = UUID(0L, 2L).toString()
        val UNIT_ID: String = UUID(0L, 3L).toString()
        val LOCATION_ID: String = UUID(0L, 4L).toString()
        val PRODUCT_ID: String = UUID(0L, 5L).toString()
        val SECOND_SUPPLIER_ID: String = UUID(0L, 1_002L).toString()
        val SECOND_UNIT_ID: String = UUID(0L, 1_003L).toString()
        val SECOND_LOCATION_ID: String = UUID(0L, 1_004L).toString()
        val SECOND_PRODUCT_ID: String = UUID(0L, 1_005L).toString()
        val DRAFT_POSTED: String = UUID(0L, 11L).toString()
        val DRAFT_VOIDED: String = UUID(0L, 12L).toString()
        val DRAFT_OPEN: String = UUID(0L, 13L).toString()
        val SECOND_DRAFT_POSTED: String = UUID(0L, 1_011L).toString()
        val PURCHASE_OPEN: String = UUID(0L, 23L).toString()
        val AUDIT_RECONCILED: String = UUID(0L, 31L).toString()
    }
}
