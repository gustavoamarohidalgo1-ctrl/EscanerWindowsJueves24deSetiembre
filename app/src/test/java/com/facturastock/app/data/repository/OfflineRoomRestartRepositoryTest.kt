package com.facturastock.app.data.repository

import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.RetryPurchaseBackupResult
import com.facturastock.app.domain.usecase.RetryDraftOcrUseCase
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Prueba el límite que equivale a una muerte de proceso para la capa de datos: se cierra la
 * conexión Room, se pierden todos los objetos de repositorio y se construyen otros contra el
 * mismo archivo. No se instala ningún cliente de red ni fake remoto.
 */
class OfflineRoomRestartRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val filesDir: File
        get() = File(tempFolder.root, "files")

    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock

    @Before
    fun setUp() {
        clock = TestClock(NOW)
        draftDirectory(CAPTURED_DRAFT_ID).deleteRecursively()
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        draftDirectory(CAPTURED_DRAFT_ID).deleteRecursively()
    }

    @Test
    fun capturedFilesInterruptedOcrAndReviewSurviveDatabaseReopenWithoutNetwork() = runBlocking {
        val firstDrafts = draftRepository(database)
        RoomBusinessRepository(database.businessDao(), testDispatchers, clock).create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio offline de prueba",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

        firstDrafts.createDraft(draft(CAPTURED_DRAFT_ID, DraftStatus.CAPTURED))
        val originalFile = File(filesDir, ORIGINAL_RELATIVE_PATH).apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeBytes(ORIGINAL_BYTES)
        }
        database.seedImageFixture(
            InvoiceImage(
                imageId = IMAGE_ID,
                draftId = CAPTURED_DRAFT_ID,
                businessId = BUSINESS_ID,
                pageIndex = 0,
                filePath = ORIGINAL_RELATIVE_PATH,
                sha256 = "a".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = originalFile.length(),
                createdAt = Instant.EPOCH,
            ),
            capturedAt = clock.now(),
        )
        assertTrue(firstDrafts.beginOcrRun(CAPTURED_DRAFT_ID, INTERRUPTED_RUN_ID))

        firstDrafts.createDraft(draft(REVIEW_DRAFT_ID, DraftStatus.NEEDS_REVIEW))
        firstDrafts.addLine(
            InvoiceLine(
                lineId = LINE_ID,
                draftId = REVIEW_DRAFT_ID,
                businessId = BUSINESS_ID,
                position = 0,
                descriptionRaw = "ARROZ EXTRA 5 KG",
                descriptionNormalized = "ARROZ EXTRA 5 KG",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

        // Simula cierre forzado/reinicio: ninguna instancia de repositorio ni Flow se reutiliza.
        database.close()
        database = openDatabase()
        val reopenedDrafts = draftRepository(database)

        val interrupted = reopenedDrafts.observeDraft(CAPTURED_DRAFT_ID)
            .awaitMatching { it != null }
        assertEquals(DraftStatus.OCR_PROCESSING, interrupted?.status)
        assertEquals(INTERRUPTED_RUN_ID, interrupted?.activeOcrRunId)
        assertEquals(
            listOf(IMAGE_ID),
            reopenedDrafts.observeImages(CAPTURED_DRAFT_ID)
                .awaitMatching { it.size == 1 }
                .map(InvoiceImage::imageId),
        )
        assertTrue(originalFile.readBytes().contentEquals(ORIGINAL_BYTES))

        val reviewed = reopenedDrafts.observeDraft(REVIEW_DRAFT_ID).awaitMatching { it != null }
        assertEquals(DraftStatus.NEEDS_REVIEW, reviewed?.status)
        assertEquals(
            "ARROZ EXTRA 5 KG",
            reopenedDrafts.observeLines(REVIEW_DRAFT_ID)
                .awaitMatching { it.size == 1 }
                .single()
                .descriptionNormalized,
        )

        // La recuperación usa el token durable; no vuelve a capturar ni borra la revisión.
        assertTrue(RetryDraftOcrUseCase(reopenedDrafts)(CAPTURED_DRAFT_ID, INTERRUPTED_RUN_ID))
        assertEquals(DraftStatus.CAPTURED, reopenedDrafts.findDraft(CAPTURED_DRAFT_ID)?.status)
        assertEquals(1, reopenedDrafts.observeImages(CAPTURED_DRAFT_ID).awaitMatching { it.size == 1 }.size)
        assertEquals(1, reopenedDrafts.observeLines(REVIEW_DRAFT_ID).awaitMatching { it.size == 1 }.size)
    }

    @Test
    fun interruptedAndFailedBackupsRecoverAfterReopenWithoutChangingPurchaseOrIdentity() =
        runBlocking {
            seedBackingPurchase()
            val firstOutbox = database.outboxOperationDao()
            firstOutbox.insert(outbox(status = OutboxOperationStatus.PENDING))
            clock.advanceSeconds(1)
            assertEquals(
                1,
                firstOutbox.claim(
                    operationId = OPERATION_ID,
                    pendingStatus = OutboxOperationStatus.PENDING.name,
                    claimedStatus = OutboxOperationStatus.PROCESSING.name,
                    claimedAt = clock.now().toEpochMilli(),
                    claimToken = CLAIM_TOKEN,
                    claimLeaseUntil = clock.now().plusSeconds(300).toEpochMilli(),
                ),
            )

            // El proceso muere después del claim y antes de cualquier respuesta remota; para
            // cuando el siguiente arranque lo observa, el lease del claim ya venció.
            clock.advanceSeconds(301)
            database.close()
            database = openDatabase()
            val backups = RoomPurchaseBackupRepository(
                outbox = database.outboxOperationDao(),
                clock = clock,
                dispatchers = testDispatchers,
            )

            assertEquals(1, backups.recoverInterrupted())
            assertEquals(0, backups.recoverInterrupted())
            val recovered = requireNotNull(backups.observe(BUSINESS_ID, PURCHASE_ID).first())
            assertEquals(PurchaseSyncState.PENDING_SYNC, recovered.state)
            assertEquals(1, recovered.attemptCount)
            assertEquals(IDEMPOTENCY_KEY, database.outboxOperationDao().findById(OPERATION_ID)?.idempotencyKey)
            assertEquals(PURCHASE_ID.value, database.purchaseDao().findById(PURCHASE_ID.value)?.purchaseId)

            // Un nuevo intento falla: el error queda en Room y el retry es un CAS idempotente.
            clock.advanceSeconds(1)
            assertEquals(
                1,
                database.outboxOperationDao().claim(
                    operationId = OPERATION_ID,
                    pendingStatus = OutboxOperationStatus.PENDING.name,
                    claimedStatus = OutboxOperationStatus.PROCESSING.name,
                    claimedAt = clock.now().toEpochMilli(),
                    claimToken = CLAIM_TOKEN_2,
                    claimLeaseUntil = clock.now().plusSeconds(300).toEpochMilli(),
                ),
            )
            clock.advanceSeconds(1)
            assertEquals(
                1,
                database.outboxOperationDao().fail(
                    operationId = OPERATION_ID,
                    claimedStatus = OutboxOperationStatus.PROCESSING.name,
                    failedStatus = OutboxOperationStatus.FAILED.name,
                    lastError = "NETWORK_UNAVAILABLE",
                    nextAttemptAt = null,
                    failedAt = clock.now().toEpochMilli(),
                    claimToken = CLAIM_TOKEN_2,
                ),
            )
            val failed = requireNotNull(backups.observe(BUSINESS_ID, PURCHASE_ID).first())
            assertEquals(PurchaseSyncState.ERROR, failed.state)
            assertEquals(2, failed.attemptCount)
            assertEquals("NETWORK_UNAVAILABLE", failed.lastError)

            assertEquals(
                RetryPurchaseBackupResult.Requeued,
                backups.retry(BUSINESS_ID, PURCHASE_ID),
            )
            assertEquals(
                RetryPurchaseBackupResult.AlreadyPending,
                backups.retry(BUSINESS_ID, PURCHASE_ID),
            )
            assertEquals(2, database.outboxOperationDao().findById(OPERATION_ID)?.attemptCount)
            assertEquals(IDEMPOTENCY_KEY, database.outboxOperationDao().findById(OPERATION_ID)?.idempotencyKey)

            // CONFLICT sigue la misma reencolación explícita y nunca crea otra operación.
            clock.advanceSeconds(1)
            assertEquals(
                1,
                database.outboxOperationDao().claim(
                    operationId = OPERATION_ID,
                    pendingStatus = OutboxOperationStatus.PENDING.name,
                    claimedStatus = OutboxOperationStatus.PROCESSING.name,
                    claimedAt = clock.now().toEpochMilli(),
                    claimToken = CLAIM_TOKEN_3,
                    claimLeaseUntil = clock.now().plusSeconds(300).toEpochMilli(),
                ),
            )
            clock.advanceSeconds(1)
            assertEquals(
                1,
                database.outboxOperationDao().fail(
                    operationId = OPERATION_ID,
                    claimedStatus = OutboxOperationStatus.PROCESSING.name,
                    failedStatus = OutboxOperationStatus.CONFLICT.name,
                    lastError = "REMOTE_VERSION_CONFLICT",
                    nextAttemptAt = null,
                    failedAt = clock.now().toEpochMilli(),
                    claimToken = CLAIM_TOKEN_3,
                ),
            )
            assertEquals(
                PurchaseSyncState.CONFLICT,
                backups.observe(BUSINESS_ID, PURCHASE_ID).first()?.state,
            )
            assertEquals(RetryPurchaseBackupResult.Requeued, backups.retry(BUSINESS_ID, PURCHASE_ID))
            assertEquals(PurchaseSyncState.PENDING_SYNC, backups.observe(BUSINESS_ID, PURCHASE_ID).first()?.state)
            assertEquals(OPERATION_ID, database.outboxOperationDao().findLatestForPurchase(
                BUSINESS_ID.value,
                PURCHASE_ID.value,
            )?.operationId)
        }

    @Test
    fun manualRetryPrioritizesFailedPurchasePostHiddenByPendingVoid() = runBlocking {
        seedBackingPurchase()
        val outbox = database.outboxOperationDao()
        outbox.insert(outbox(status = OutboxOperationStatus.PENDING))
        clock.advanceSeconds(1)
        assertEquals(
            1,
            outbox.claim(
                operationId = OPERATION_ID,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN,
                claimLeaseUntil = clock.now().plusSeconds(300).toEpochMilli(),
            ),
        )
        clock.advanceSeconds(1)
        assertEquals(
            1,
            outbox.fail(
                operationId = OPERATION_ID,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                failedStatus = OutboxOperationStatus.FAILED.name,
                lastError = "HTTP_503_EXHAUSTED",
                nextAttemptAt = null,
                failedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN,
            ),
        )
        clock.advanceSeconds(1)
        outbox.insert(voidOutbox(createdAt = clock.now()))
        val backups = RoomPurchaseBackupRepository(
            outbox = outbox,
            clock = clock,
            dispatchers = testDispatchers,
        )

        // La vista por compra sigue mostrando la operación más nueva, pero retry debe reparar
        // primero el predecessor que hace causalmente posible enviar esa anulación.
        assertEquals(PurchaseSyncState.PENDING_SYNC, backups.observe(BUSINESS_ID, PURCHASE_ID).first()?.state)
        assertEquals(RetryPurchaseBackupResult.Requeued, backups.retry(BUSINESS_ID, PURCHASE_ID))
        assertEquals(OutboxOperationStatus.PENDING.name, outbox.findById(OPERATION_ID)?.status)
        assertEquals(OutboxOperationStatus.PENDING.name, outbox.findById(VOID_OPERATION_ID)?.status)
        assertEquals(0, outbox.findById(VOID_OPERATION_ID)?.attemptCount)
        assertEquals(RetryPurchaseBackupResult.AlreadyPending, backups.retry(BUSINESS_ID, PURCHASE_ID))

        // CONFLICT usa la misma prioridad aunque el VOID siga siendo la fila más reciente.
        clock.advanceSeconds(1)
        assertEquals(
            1,
            outbox.claim(
                operationId = OPERATION_ID,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN_2,
                claimLeaseUntil = clock.now().plusSeconds(300).toEpochMilli(),
            ),
        )
        clock.advanceSeconds(1)
        assertEquals(
            1,
            outbox.fail(
                operationId = OPERATION_ID,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                failedStatus = OutboxOperationStatus.CONFLICT.name,
                lastError = "REMOTE_VERSION_CONFLICT",
                nextAttemptAt = null,
                failedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN_2,
            ),
        )

        assertEquals(RetryPurchaseBackupResult.Requeued, backups.retry(BUSINESS_ID, PURCHASE_ID))
        assertEquals(OutboxOperationStatus.PENDING.name, outbox.findById(OPERATION_ID)?.status)
        assertEquals(OutboxOperationStatus.PENDING.name, outbox.findById(VOID_OPERATION_ID)?.status)
        assertEquals(0, outbox.findById(VOID_OPERATION_ID)?.attemptCount)
    }

    @Test
    fun purchaseRetryIgnoresNewerDocumentAndRequeuesFailedVoid() = runBlocking {
        seedBackingPurchase()
        val outbox = database.outboxOperationDao()
        outbox.insert(outbox(status = OutboxOperationStatus.PENDING))
        assertEquals(
            1,
            outbox.claim(
                operationId = OPERATION_ID,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN,
                claimLeaseUntil = clock.now().plusSeconds(300).toEpochMilli(),
            ),
        )
        clock.advanceSeconds(1)
        assertEquals(
            1,
            outbox.complete(
                operationId = OPERATION_ID,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                completedStatus = OutboxOperationStatus.COMPLETED.name,
                completedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN,
            ),
        )

        clock.advanceSeconds(1)
        outbox.insert(voidOutbox(createdAt = clock.now()))
        assertEquals(
            1,
            outbox.claim(
                operationId = VOID_OPERATION_ID,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN_2,
                claimLeaseUntil = clock.now().plusSeconds(300).toEpochMilli(),
            ),
        )
        clock.advanceSeconds(1)
        assertEquals(
            1,
            outbox.fail(
                operationId = VOID_OPERATION_ID,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                failedStatus = OutboxOperationStatus.FAILED.name,
                lastError = "VOID_REMOTE_FAILURE",
                nextAttemptAt = null,
                failedAt = clock.now().toEpochMilli(),
                claimToken = CLAIM_TOKEN_2,
            ),
        )

        clock.advanceSeconds(1)
        outbox.insert(documentOutbox(createdAt = clock.now()))
        val backups = RoomPurchaseBackupRepository(
            outbox = outbox,
            clock = clock,
            dispatchers = testDispatchers,
        )

        val visible = requireNotNull(backups.observe(BUSINESS_ID, PURCHASE_ID).first())
        assertEquals(PurchaseSyncState.ERROR, visible.state)
        assertEquals("VOID_REMOTE_FAILURE", visible.lastError)
        assertEquals(
            VOID_OPERATION_ID,
            outbox.findLatestForPurchase(BUSINESS_ID.value, PURCHASE_ID.value)?.operationId,
        )

        // Incluso con todos los IDs coincidentes, el CAS específico de compra no puede tocar
        // una transferencia documental que comparta purchaseId.
        assertEquals(
            0,
            outbox.retryFailedOrConflicted(
                operationId = DOCUMENT_OPERATION_ID,
                businessId = BUSINESS_ID.value,
                purchaseId = PURCHASE_ID.value,
                failedStatus = OutboxOperationStatus.FAILED.name,
                conflictStatus = OutboxOperationStatus.CONFLICT.name,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                retriedAt = clock.now().toEpochMilli(),
            ),
        )

        assertEquals(RetryPurchaseBackupResult.Requeued, backups.retry(BUSINESS_ID, PURCHASE_ID))
        assertEquals(OutboxOperationStatus.COMPLETED.name, outbox.findById(OPERATION_ID)?.status)
        assertEquals(OutboxOperationStatus.PENDING.name, outbox.findById(VOID_OPERATION_ID)?.status)
        assertEquals(OutboxOperationStatus.FAILED.name, outbox.findById(DOCUMENT_OPERATION_ID)?.status)
        assertEquals("document-only failure", outbox.findById(DOCUMENT_OPERATION_ID)?.lastError)
    }

    @Test
    fun productSearchIsAReactiveBoundedRoomPageAndSurvivesReopenWithoutNetwork() =
        runBlocking {
            RoomBusinessRepository(database.businessDao(), testDispatchers, clock).create(
                Business(
                    businessId = BUSINESS_ID,
                    legalName = "Negocio catálogo offline",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
            val units = RoomUnitRepository(database.unitDao(), testDispatchers, clock)
            units.create(
                UnitOfMeasure(
                    unitId = UNIT_ID,
                    businessId = BUSINESS_ID,
                    code = "NIU",
                    name = "Unidad",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
            val products = RoomProductRepository(
                database,
                database.productDao(),
                testDispatchers,
                clock,
            )
            val search = CatalogSearch(query = "arroz", limit = 1)
            val emissions = Channel<CatalogPage<Product>>(Channel.UNLIMITED)
            val collector = launch {
                products.observeSearch(BUSINESS_ID, search).collect(emissions::send)
            }
            try {
                assertEquals(0, emissions.receiveMatching { it.total == 0 }.items.size)

                val first = products.create(product(PRODUCT_ID_1, "Arroz extra"))
                val one = emissions.receiveMatching { page ->
                    page.total == 1 && page.items.singleOrNull()?.productId == PRODUCT_ID_1
                }
                assertEquals(1, one.limit)
                assertFalse(one.hasMore)

                clock.advanceSeconds(1)
                products.create(product(PRODUCT_ID_2, "Arroz superior"))
                val bounded = emissions.receiveMatching { page ->
                    page.total == 2 && page.items.size == 1
                }
                assertEquals(1, bounded.items.size)
                assertTrue(bounded.hasMore)

                clock.advanceSeconds(1)
                assertTrue(products.update(first.copy(name = "Café molido")))
                val filtered = emissions.receiveMatching { page ->
                    page.total == 1 && page.items.singleOrNull()?.productId == PRODUCT_ID_2
                }
                assertEquals(listOf(PRODUCT_ID_2), filtered.items.map(Product::productId))
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }

            database.close()
            database = openDatabase()
            val afterRestart = RoomProductRepository(
                database,
                database.productDao(),
                testDispatchers,
                clock,
            ).observeSearch(BUSINESS_ID, search).first()
            assertEquals(1, afterRestart.total)
            assertEquals(listOf(PRODUCT_ID_2), afterRestart.items.map(Product::productId))
        }

    private fun openDatabase(): FacturaStockDatabase =
        FacturaStockDatabase.buildAt(File(tempFolder.root, DATABASE_NAME))

    private fun draftRepository(database: FacturaStockDatabase): RoomInvoiceDraftRepository =
        RoomInvoiceDraftRepository(
            database = database,
            invoiceDraftDao = database.invoiceDraftDao(),
            invoiceImageDao = database.invoiceImageDao(),
            invoiceLineDao = database.invoiceLineDao(),
            dispatchers = testDispatchers,
            clock = clock,
        )

    private fun draft(draftId: DraftId, status: DraftStatus): InvoiceDraft = InvoiceDraft(
        draftId = draftId,
        businessId = BUSINESS_ID,
        status = status,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private suspend fun seedBackingPurchase() {
        val now = NOW.toEpochMilli()
        database.businessDao().insert(
            BusinessEntity(
                businessId = BUSINESS_ID.value,
                legalName = "Negocio offline de prueba",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = SUPPLIER_ID,
                businessId = BUSINESS_ID.value,
                legalName = "Proveedor offline de prueba",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = BACKUP_DRAFT_ID,
                businessId = BUSINESS_ID.value,
                createdAt = now,
                updatedAt = now,
                status = DraftStatus.CREATED.name,
            ),
        )
        database.purchaseDao().insert(
            PurchaseEntity(
                purchaseId = PURCHASE_ID.value,
                businessId = BUSINESS_ID.value,
                sourceDraftId = BACKUP_DRAFT_ID,
                supplierId = SUPPLIER_ID,
                documentType = PurchaseDocumentType.INVOICE.name,
                documentSeries = "F036",
                documentNumber = "1",
                issueDate = "2026-08-14",
                currencyCode = "PEN",
                subtotalMinorUnits = 100L,
                taxMinorUnits = 18L,
                otherChargesMinorUnits = 0L,
                totalMinorUnits = 118L,
                status = PurchaseStatus.DRAFT.name,
                idempotencyKey = "purchase-offline-36",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun outbox(status: OutboxOperationStatus): OutboxOperationEntity =
        OutboxOperationEntity(
            operationId = OPERATION_ID,
            businessId = BUSINESS_ID.value,
            purchaseId = PURCHASE_ID.value,
            idempotencyKey = IDEMPOTENCY_KEY,
            operationType = "SYNC_PURCHASE",
            payload = "{\"purchaseId\":\"${PURCHASE_ID.value}\"}",
            status = status.name,
            createdAt = NOW.toEpochMilli(),
            updatedAt = NOW.toEpochMilli(),
        )

    private fun voidOutbox(createdAt: Instant): OutboxOperationEntity =
        OutboxOperationEntity(
            operationId = VOID_OPERATION_ID,
            businessId = BUSINESS_ID.value,
            purchaseId = PURCHASE_ID.value,
            idempotencyKey = "sync-purchase-void:v1:${PURCHASE_ID.value}",
            operationType = "SYNC_PURCHASE_VOID",
            payload = "{\"version\":1,\"purchaseId\":\"${PURCHASE_ID.value}\"}",
            status = OutboxOperationStatus.PENDING.name,
            createdAt = createdAt.toEpochMilli(),
            updatedAt = createdAt.toEpochMilli(),
            payloadVersion = 1,
            entityType = "PURCHASE",
            entityId = PURCHASE_ID.value,
            entityVersion = 2L,
        )

    private fun documentOutbox(createdAt: Instant): OutboxOperationEntity =
        OutboxOperationEntity(
            operationId = DOCUMENT_OPERATION_ID,
            businessId = BUSINESS_ID.value,
            purchaseId = PURCHASE_ID.value,
            idempotencyKey = "document-upload-retry-isolation",
            operationType = "SYNC_DOCUMENT_UPLOAD",
            payload = "{\"document\":true}",
            status = OutboxOperationStatus.FAILED.name,
            attemptCount = 1,
            createdAt = createdAt.toEpochMilli(),
            updatedAt = createdAt.toEpochMilli(),
            lastError = "document-only failure",
            entityType = "DOCUMENT",
            entityId = DOCUMENT_IMAGE_ID,
            entityVersion = 1L,
        )

    private fun product(productId: ProductId, name: String): Product = Product(
        productId = productId,
        businessId = BUSINESS_ID,
        unitId = UNIT_ID,
        name = name,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private suspend fun <T> Channel<T>.receiveMatching(predicate: (T) -> Boolean): T =
        withTimeout(5_000) {
            while (true) {
                val value = receive()
                if (predicate(value)) return@withTimeout value
            }
            error("inalcanzable")
        }

    private fun draftDirectory(draftId: DraftId): File =
        File(filesDir, "draft_images/${draftId.value}")

    private companion object {
        const val DATABASE_NAME = "offline-room-restart-test.db"
        val NOW: Instant = Instant.parse("2026-08-14T18:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003601"),
        )
        val CAPTURED_DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003602"),
        )
        val REVIEW_DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003603"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003604"),
        )
        val LINE_ID: LineId = LineId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003605"),
        )
        val INTERRUPTED_RUN_ID: OcrRunId = OcrRunId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003606"),
        )
        val PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003607"),
        )
        val UNIT_ID: UnitId = UnitId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003611"),
        )
        val PRODUCT_ID_1: ProductId = ProductId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003612"),
        )
        val PRODUCT_ID_2: ProductId = ProductId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003613"),
        )
        const val BACKUP_DRAFT_ID = "00000000-0000-4000-8000-000000003608"
        const val SUPPLIER_ID = "00000000-0000-4000-8000-000000003609"
        const val OPERATION_ID = "00000000-0000-4000-8000-000000003610"
        const val VOID_OPERATION_ID = "00000000-0000-4000-8000-000000003614"
        const val DOCUMENT_OPERATION_ID = "00000000-0000-4000-8000-000000003615"
        const val DOCUMENT_IMAGE_ID = "00000000-0000-4000-8000-000000003616"
        const val IDEMPOTENCY_KEY = "outbox-offline-36"
        const val CLAIM_TOKEN = "00000000-0000-4000-8000-0000000036c1"
        const val CLAIM_TOKEN_2 = "00000000-0000-4000-8000-0000000036c2"
        const val CLAIM_TOKEN_3 = "00000000-0000-4000-8000-0000000036c3"
        val ORIGINAL_BYTES: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val ORIGINAL_RELATIVE_PATH: String =
            "draft_images/${CAPTURED_DRAFT_ID.value}/${IMAGE_ID.value}.jpg"
    }
}
