package com.facturastock.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.repository.RoomInventoryReadRepository
import com.facturastock.app.data.repository.RoomPurchaseBackupOutboxRepository
import com.facturastock.app.data.repository.RoomPurchasePostingRepository
import com.facturastock.app.data.repository.RoomPurchaseReadRepository
import com.facturastock.app.data.repository.RoomPurchaseVoidRepository
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.dao.InventoryBalanceMutation
import com.facturastock.app.data.local.dao.PurchasePostingBatch
import com.facturastock.app.data.local.dao.updateCas
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.InventoryCostingRequest
import com.facturastock.app.domain.model.InventoryCostingResult
import com.facturastock.app.domain.model.InventoryDiagnosticIssue
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.PurchaseDuplicateReason
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseReconciliationAdjustment
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.InventoryCostingService
import com.facturastock.app.domain.repository.ConfirmPurchaseCommand
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseConfirmationBlocker
import com.facturastock.app.domain.repository.PurchaseConfirmationContext
import com.facturastock.app.domain.repository.PurchaseVoidCommand
import com.facturastock.app.domain.repository.PurchaseVoidResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PurchasePostingDaoTest {
    private lateinit var database: FacturaStockDatabase

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java)
            .addCallback(postingPersistenceCallback)
            .build()

        seedCatalog(
            businessId = BUSINESS_ID,
            supplierId = SUPPLIER_ID,
            unitId = UNIT_ID,
            locationId = LOCATION_ID,
            productId = PRODUCT_ID,
            label = "principal",
            supplierRuc = SUPPLIER_RUC,
        )
        seedCatalog(
            businessId = OTHER_BUSINESS_ID,
            supplierId = OTHER_SUPPLIER_ID,
            unitId = OTHER_UNIT_ID,
            locationId = OTHER_LOCATION_ID,
            productId = OTHER_PRODUCT_ID,
            label = "ajeno",
            supplierRuc = OTHER_SUPPLIER_RUC,
        )
        seedPreparedDraft(DRAFT_ID)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun validBatchCommitsCompletePostedGraphAndLinksPreparedDraft() = runBlocking {
        val batch = postingBatch()
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))

        val posted = database.purchasePostingDao().postAtomically(batch)

        assertEquals(batch.purchase, posted)
        assertCommittedGraph(batch)
        val draft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(DraftStatus.COMMITTED.name, draft.status)
        assertEquals(batch.purchase.purchaseId, draft.confirmedPurchaseId)
        assertEquals(batch.purchase.updatedAt, draft.updatedAt)
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun postingRejectsPurchaseLineWithoutFrozenCloudCatalogSnapshot() = runBlocking {
        val batch = postingBatch(offset = 44)
        val invalid = batch.copy(
            lines = batch.lines.map {
                it.copy(productNameSnapshot = null, unitCodeSnapshot = null)
            },
        )

        val failure = runCatching {
            database.purchasePostingDao().postAtomically(invalid)
        }.exceptionOrNull()

        assertTrue(failure is SQLiteConstraintException)
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    @Test
    fun postingRejectsUnknownLegacyProductProvenanceWithoutArtifacts() = runBlocking {
        val valid = postingBatch(offset = 45)
        val unknown = valid.copy(
            lines = valid.lines.map { line ->
                line.copy(productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY.name)
            },
        )

        assertPostingFails(IllegalArgumentException::class.java, unknown)

        assertNoPostingArtifacts(unknown, expectedBalanceCount = 0)
        assertEquals(
            DraftStatus.READY_TO_POST.name,
            database.invoiceDraftDao().findById(DRAFT_ID)?.status,
        )
    }

    @Test
    fun repositoryRejectsPreparedLegacyProvenanceBeforeBuildingPostingGraph() = runBlocking {
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
        )

        val result = productionRepository().confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        )

        assertEquals(ConfirmPurchaseResult.PreparedChanged, result)
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    @Test
    fun postedPurchaseVoidCommitsCompleteGraphPreservesHistoryAndIsIdempotent() = runBlocking {
        val batch = postingBatch(offset = 600)
        val purchaseId = requireNotNull(PurchaseId.parse(batch.purchase.purchaseId))
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val retainedImage = InvoiceImageEntity(
            imageId = uuid(9_600),
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            pageIndex = 0,
            filePath = "invoices/$DRAFT_ID/page-0.jpg",
            sha256 = "a".repeat(64),
            mimeType = "image/jpeg",
            widthPx = 1_200,
            heightPx = 1_600,
            fileSizeBytes = 42_000L,
            createdAt = DRAFT_CREATED_AT,
        )
        database.invoiceImageDao().insert(retainedImage)
        database.purchasePostingDao().postAtomically(batch)

        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val readRepository = purchaseReadRepository()
        val detailBefore = requireNotNull(
            readRepository.observePurchase(businessId, purchaseId).first { it != null },
        )
        val actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER)
        val voidRepository = purchaseVoidRepository(POSTED_AT + 50_000L)
        val preview = voidRepository.preview(businessId, purchaseId, actor)
            as PreviewPurchaseVoidResult.Ready
        val reason = "Recepción confirmó \"cantidad duplicada\""
        val command = PurchaseVoidCommand(
            businessId = businessId,
            purchaseId = purchaseId,
            reason = reason,
            actor = actor,
            expectedImpactHash = preview.preview.expectedImpactHash,
        )
        val start = CompletableDeferred<Unit>()

        val concurrentResults = coroutineScope {
            val first = async {
                start.await()
                voidRepository.void(command)
            }
            val second = async {
                start.await()
                voidRepository.void(command)
            }
            start.complete(Unit)
            awaitAll(first, second)
        }

        assertEquals(1, concurrentResults.count { it is PurchaseVoidResult.Voided })
        assertEquals(1, concurrentResults.count { it is PurchaseVoidResult.AlreadyVoided })
        val committedResult = concurrentResults.filterIsInstance<PurchaseVoidResult.Voided>().single()
        assertTrue(committedResult.negativeImpacts.isEmpty())
        assertTrue(voidRepository.void(command) is PurchaseVoidResult.AlreadyVoided)

        val voidedPurchase = requireNotNull(database.purchaseDao().findById(batch.purchase.purchaseId))
        assertEquals(PurchaseStatus.VOIDED.name, voidedPurchase.status)
        assertEquals(batch.purchase.postedAt, voidedPurchase.postedAt)
        assertTrue(requireNotNull(voidedPurchase.voidedAt) > requireNotNull(batch.purchase.postedAt))
        assertEquals(voidedPurchase.voidedAt, voidedPurchase.updatedAt)

        val storedMovements = database.inventoryDao().listMovementsForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        )
        val original = storedMovements.single { it.type == StockMovementType.PURCHASE.name }
        val reversal = storedMovements.single { it.type == StockMovementType.VOID.name }
        assertEquals(batch.movements.single(), original)
        assertEquals(original.purchaseId, reversal.purchaseId)
        assertEquals(original.purchaseLineId, reversal.purchaseLineId)
        assertEquals(original.productId, reversal.productId)
        assertEquals(original.locationId, reversal.locationId)
        assertEquals(original.currencyCode, reversal.currencyCode)
        assertEquals(original.unitCost, reversal.unitCost)
        assertEquals(
            0,
            reversal.quantityDelta.toBigDecimal().compareTo(
                original.quantityDelta.toBigDecimal().negate(),
            ),
        )

        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals("0", balance.quantityOnHand)
        assertEquals(batch.balanceMutations.single().balance.averageUnitCost, balance.averageUnitCost)
        assertEquals(1L, balance.version)
        assertEquals(voidedPurchase.voidedAt, balance.updatedAt)

        val voidAudit = database.auditEventDao().listForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        ).single { it.eventType == AuditEventType.PURCHASE_VOIDED.name }
        val voidOutbox = requireNotNull(
            database.outboxOperationDao().findByIdempotencyKey(
                PurchaseVoidIdentity.outboxKey(batch.purchase.purchaseId),
            ),
        )
        assertNotEquals(voidAudit.payload, voidOutbox.payload)
        assertEquals("SYNC_PURCHASE_VOID", voidOutbox.operationType)
        assertTrue(voidAudit.payload.contains("\"actorRole\":\"OWNER\""))
        assertTrue(voidAudit.payload.contains("\"negativeStockPolicy\":\"ALLOW_WITH_VISIBLE_WARNING\""))
        assertTrue(voidAudit.payload.contains("\"averageUnitCostPolicy\":\"PRESERVE_CURRENT\""))
        assertTrue(voidAudit.payload.contains("\"negativeImpactCount\":\"0\""))
        listOf(reason, "\"reason\"", "\"actorId\"", "\"impactHash\"", "\"impacts\"")
            .forEach { sensitive -> assertFalse(voidAudit.payload.contains(sensitive)) }
        assertTrue(voidOutbox.payload.contains("\"impactHash\":\"${preview.preview.expectedImpactHash}\""))
        assertTrue(voidOutbox.payload.contains("\"actorId\":\"owner-local\""))
        assertTrue(voidOutbox.payload.contains("\"role\":\"OWNER\""))
        assertTrue(voidOutbox.payload.contains("\"reason\":\"Recepción confirmó \\\"cantidad duplicada\\\"\""))
        assertTrue(voidOutbox.payload.contains("\"negativeStockPolicy\":\"ALLOW_WITH_VISIBLE_WARNING\""))
        assertTrue(voidOutbox.payload.contains("\"averageUnitCostPolicy\":\"PRESERVE_CURRENT\""))
        assertTrue(voidOutbox.payload.contains("\"negativeImpactCount\":0"))

        val detailAfter = requireNotNull(
            readRepository.observePurchase(businessId, purchaseId).first {
                it?.summary?.status == PurchaseStatus.VOIDED &&
                    it.movements.count { movement -> movement.type == StockMovementType.VOID } == 1 &&
                    it.auditEvents.any { event ->
                        event.eventType == AuditEventType.PURCHASE_VOIDED
                    }
            },
        )
        assertEquals(detailBefore.lines, detailAfter.lines)
        assertEquals(detailBefore.preparedLogicalHash, detailAfter.preparedLogicalHash)
        assertEquals(detailBefore.images, detailAfter.images)
        assertEquals(
            detailBefore.movements.single(),
            detailAfter.movements.single { it.type == StockMovementType.PURCHASE },
        )
        assertEquals(1, detailAfter.movements.count { it.type == StockMovementType.VOID })
        assertEquals(PurchaseStatus.VOIDED, detailAfter.summary.status)
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(retainedImage, database.invoiceImageDao().findById(retainedImage.imageId))
        assertPostingTableCounts(
            purchases = 1,
            lines = 1,
            balances = 1,
            movements = 2,
            audits = 2,
            outbox = 2,
        )
    }

    @Test
    fun latestOutboxFlowMovesFromPostingToVoidAndPreservesBothPendingKeys() = runBlocking {
        val batch = postingBatch(offset = 603)
        database.purchasePostingDao().postAtomically(batch)
        val outbox = database.outboxOperationDao()
        val emissions = Channel<OutboxOperationEntity>(Channel.UNLIMITED)
        val collector = launch {
            outbox.observeLatestForPurchase(BUSINESS_ID, batch.purchase.purchaseId)
                .collect { operation -> operation?.let { emissions.send(it) } }
        }

        try {
            val postedOperation = withTimeout(5_000L) { emissions.receive() }
            assertEquals(batch.outboxOperations.single(), postedOperation)
            assertEquals(OutboxOperationStatus.PENDING.name, postedOperation.status)

            val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
            val purchaseId = requireNotNull(PurchaseId.parse(batch.purchase.purchaseId))
            val actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER)
            val repository = purchaseVoidRepository(POSTED_AT + 50_000L)
            val preview = repository.preview(businessId, purchaseId, actor)
                as PreviewPurchaseVoidResult.Ready
            assertTrue(
                repository.void(
                    PurchaseVoidCommand(
                        businessId = businessId,
                        purchaseId = purchaseId,
                        reason = "Anulación para validar la secuencia observable del outbox",
                        actor = actor,
                        expectedImpactHash = preview.preview.expectedImpactHash,
                    ),
                ) is PurchaseVoidResult.Voided,
            )

            val voidOperation = withTimeout(5_000L) { emissions.receive() }
            assertEquals(
                PurchaseVoidIdentity.outboxKey(batch.purchase.purchaseId),
                voidOperation.idempotencyKey,
            )
            assertEquals("SYNC_PURCHASE_VOID", voidOperation.operationType)
            assertEquals(OutboxOperationStatus.PENDING.name, voidOperation.status)
            assertEquals(
                postedOperation,
                outbox.findByIdempotencyKey(postedOperation.idempotencyKey),
            )
            assertEquals(voidOperation, outbox.findLatestForPurchase(BUSINESS_ID, purchaseId.value))
            assertPostingTableCounts(
                purchases = 1,
                lines = 1,
                balances = 1,
                movements = 2,
                audits = 2,
                outbox = 2,
            )
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun voidAfterConsumptionAllowsVisibleNegativeStockAndAuditsImpact() = runBlocking {
        val batch = postingBatch(offset = 601)
        database.purchasePostingDao().postAtomically(batch)
        val consumedAt = requireNotNull(batch.purchase.postedAt) + 1L
        assertEquals(
            1,
            database.inventoryDao().updateBalanceIfVersion(
                businessId = BUSINESS_ID,
                productId = PRODUCT_ID,
                locationId = LOCATION_ID,
                expectedVersion = 0L,
                quantityOnHand = "1",
                averageUnitCost = "5.00",
                currencyCode = CURRENCY,
                updatedAt = consumedAt,
            ),
        )
        database.inventoryDao().insertMovements(
            listOf(
                StockMovementEntity(
                    movementId = uuid(9_610),
                    businessId = BUSINESS_ID,
                    productId = PRODUCT_ID,
                    locationId = LOCATION_ID,
                    type = StockMovementType.ADJUSTMENT.name,
                    quantityDelta = "-1",
                    currencyCode = CURRENCY,
                    idempotencyKey = "adjustment-before-void-601",
                    occurredAt = consumedAt,
                    createdAt = consumedAt,
                ),
            ),
        )
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val purchaseId = requireNotNull(PurchaseId.parse(batch.purchase.purchaseId))
        val actor = PurchaseOverrideActor("manager-local", PurchaseOverrideRole.MANAGER)
        val repository = purchaseVoidRepository(consumedAt + 10L)
        val preview = repository.preview(businessId, purchaseId, actor)
            as PreviewPurchaseVoidResult.Ready
        val impact = preview.preview.impacts.single()
        assertEquals(0, impact.currentQuantity.compareTo(BigDecimal.ONE))
        assertEquals(0, impact.reversalQuantity.compareTo(BigDecimal("-2")))
        assertEquals(0, impact.resultingQuantity.compareTo(BigDecimal("-1")))
        assertTrue(preview.preview.hasNegativeImpact)

        val result = repository.void(
            PurchaseVoidCommand(
                businessId = businessId,
                purchaseId = purchaseId,
                reason = "Mercadería consumida antes de detectar el duplicado",
                actor = actor,
                expectedImpactHash = preview.preview.expectedImpactHash,
            ),
        ) as PurchaseVoidResult.Voided

        assertEquals(1, result.negativeImpacts.size)
        assertEquals(
            0,
            result.negativeImpacts.single().resultingQuantity.compareTo(BigDecimal("-1")),
        )
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals("-1", balance.quantityOnHand)
        assertEquals("5.00", balance.averageUnitCost)
        assertEquals(2L, balance.version)
        val voidAudit = database.auditEventDao().listForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        ).single { it.eventType == AuditEventType.PURCHASE_VOIDED.name }
        val voidOutbox = requireNotNull(
            database.outboxOperationDao().findByIdempotencyKey(
                PurchaseVoidIdentity.outboxKey(batch.purchase.purchaseId),
            ),
        )
        assertTrue(voidAudit.payload.contains("\"actorRole\":\"MANAGER\""))
        assertTrue(voidAudit.payload.contains("\"negativeImpactCount\":\"1\""))
        assertFalse(voidAudit.payload.contains("\"resultingQuantity\""))
        assertFalse(voidAudit.payload.contains("\"currentQuantity\""))
        assertFalse(voidAudit.payload.contains("\"reversalQuantity\""))
        assertFalse(voidAudit.payload.contains("Mercadería consumida antes de detectar el duplicado"))
        assertTrue(voidOutbox.payload.contains("\"resultingQuantity\":\"-1\""))
        assertTrue(voidOutbox.payload.contains("\"negative\":true"))
        assertTrue(
            voidOutbox.payload.contains(
                "\"reason\":\"Mercadería consumida antes de detectar el duplicado\"",
            ),
        )

        val position = inventoryReadRepository().observeInventory(businessId).first()
            .single().positions.single()
        assertEquals(0, position.quantityOnHand.compareTo(BigDecimal("-1")))
        assertTrue(InventoryDataAlert.NEGATIVE_STOCK in position.alerts)
    }

    @Test
    fun failureAtFinalStatusUpdateRollsBackVoidAndKeepsPendingOutboxRetryable() = runBlocking {
        val batch = postingBatch(offset = 602)
        database.purchasePostingDao().postAtomically(batch)
        val purchaseId = requireNotNull(PurchaseId.parse(batch.purchase.purchaseId))
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER)
        val repository = purchaseVoidRepository(POSTED_AT + 50_000L)
        val preview = repository.preview(businessId, purchaseId, actor)
            as PreviewPurchaseVoidResult.Ready
        val command = PurchaseVoidCommand(
            businessId = businessId,
            purchaseId = purchaseId,
            reason = "Fallo tardío inducido para verificar rollback total",
            actor = actor,
            expectedImpactHash = preview.preview.expectedImpactHash,
        )
        val purchaseBefore = requireNotNull(database.purchaseDao().findById(batch.purchase.purchaseId))
        val balanceBefore = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        val movementsBefore = database.inventoryDao().listMovementsForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        )
        val auditsBefore = database.auditEventDao().listForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        )
        val postingOutboxBefore = requireNotNull(
            database.outboxOperationDao().findById(batch.outboxOperations.single().operationId),
        )
        assertEquals(OutboxOperationStatus.PENDING.name, postingOutboxBefore.status)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER `test_force_void_final_failure` " +
                "BEFORE UPDATE OF `status` ON `purchases` " +
                "WHEN OLD.`status` = 'POSTED' AND NEW.`status` = 'VOIDED' " +
                "BEGIN SELECT RAISE(ABORT, 'forced late void failure'); END",
        )

        try {
            assertTrue(repository.void(command) is PurchaseVoidResult.RetryableConflict)
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS `test_force_void_final_failure`")
        }

        assertEquals(purchaseBefore, database.purchaseDao().findById(batch.purchase.purchaseId))
        assertEquals(
            balanceBefore,
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals(
            movementsBefore,
            database.inventoryDao().listMovementsForPurchase(
                BUSINESS_ID,
                batch.purchase.purchaseId,
            ),
        )
        assertEquals(
            auditsBefore,
            database.auditEventDao().listForPurchase(BUSINESS_ID, batch.purchase.purchaseId),
        )
        assertEquals(
            postingOutboxBefore,
            database.outboxOperationDao().findById(postingOutboxBefore.operationId),
        )
        assertNull(
            database.outboxOperationDao().findByIdempotencyKey(
                PurchaseVoidIdentity.outboxKey(batch.purchase.purchaseId),
            ),
        )
        assertPostingTableCounts(expected = 1)

        assertTrue(repository.void(command) is PurchaseVoidResult.Voided)

        assertEquals(
            postingOutboxBefore,
            database.outboxOperationDao().findById(postingOutboxBefore.operationId),
        )
        val voidOutbox = requireNotNull(
            database.outboxOperationDao().findByIdempotencyKey(
                PurchaseVoidIdentity.outboxKey(batch.purchase.purchaseId),
            ),
        )
        assertEquals(OutboxOperationStatus.PENDING.name, voidOutbox.status)
        assertPostingTableCounts(
            purchases = 1,
            lines = 1,
            balances = 1,
            movements = 2,
            audits = 2,
            outbox = 2,
        )
    }

    @Test
    fun conflictResolutionAuditFailureRollsBackRepositoryTransactionAndCanRetry() = runBlocking {
        val batch = postingBatch(offset = 604)
        database.purchasePostingDao().postAtomically(batch)
        val outbox = database.outboxOperationDao()
        val operationId = batch.outboxOperations.single().operationId
        val claimToken = uuid(9_604)
        val postedAt = requireNotNull(batch.purchase.postedAt)
        assertEquals(
            1,
            outbox.claim(
                operationId = operationId,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = postedAt + 1L,
                claimToken = claimToken,
                claimLeaseUntil = postedAt + 1_000L,
            ),
        )
        assertEquals(
            1,
            outbox.fail(
                operationId = operationId,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                failedStatus = OutboxOperationStatus.CONFLICT.name,
                lastError = "REMOTE_PURCHASE_ALREADY_EXISTS",
                nextAttemptAt = null,
                failedAt = postedAt + 2L,
                claimToken = claimToken,
                conflictRemotePurchaseId = uuid(9_605),
                conflictReceiptId = "remote-receipt-604",
            ),
        )
        val conflictedBefore = requireNotNull(outbox.findById(operationId))
        val auditsBefore = database.auditEventDao().listForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        )
        val auditId = uuid(9_606)
        val repository = RoomPurchaseBackupOutboxRepository(
            database = database,
            outbox = outbox,
            auditEvents = database.auditEventDao(),
            uuids = UuidGenerator { UUID.fromString(auditId) },
            dispatchers = DefaultDispatcherProvider(),
        )
        val purchaseId = requireNotNull(PurchaseId.parse(batch.purchase.purchaseId))
        val resolvedAt = Instant.ofEpochMilli(postedAt + 3L)
        assertFalse(
            repository.resolveConflictKeepRemote(
                activeBusinessId = BusinessId.from(UUID.fromString(uuid(9_999))),
                operationId = operationId,
                purchaseId = purchaseId,
                remotePurchaseId = conflictedBefore.conflictRemotePurchaseId,
                remoteReceiptId = conflictedBefore.conflictReceiptId,
                actorId = "otro-tenant",
                resolvedAt = resolvedAt,
            ),
        )
        assertEquals(conflictedBefore, outbox.findById(operationId))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER `test_force_conflict_audit_failure` " +
                "BEFORE INSERT ON `audit_events` " +
                "WHEN NEW.`eventType` = 'SYNC_CONFLICT_RESOLVED' " +
                "BEGIN SELECT RAISE(ABORT, 'forced conflict audit failure'); END",
        )

        val failure = try {
            repository.resolveConflictKeepRemote(
                activeBusinessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
                operationId = operationId,
                purchaseId = purchaseId,
                remotePurchaseId = conflictedBefore.conflictRemotePurchaseId,
                remoteReceiptId = conflictedBefore.conflictReceiptId,
                actorId = "owner-local",
                resolvedAt = resolvedAt,
            )
            null
        } catch (throwable: Throwable) {
            throwable
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS `test_force_conflict_audit_failure`")
        }

        assertTrue(
            "Expected StorageException, got ${failure?.javaClass?.name}: ${failure?.message}",
            failure is StorageException,
        )
        assertEquals(conflictedBefore, outbox.findById(operationId))
        assertEquals(
            auditsBefore,
            database.auditEventDao().listForPurchase(BUSINESS_ID, batch.purchase.purchaseId),
        )

        assertTrue(
            repository.resolveConflictKeepRemote(
                activeBusinessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
                operationId = operationId,
                purchaseId = purchaseId,
                remotePurchaseId = conflictedBefore.conflictRemotePurchaseId,
                remoteReceiptId = conflictedBefore.conflictReceiptId,
                actorId = "owner-local",
                resolvedAt = resolvedAt,
            ),
        )

        val resolved = requireNotNull(outbox.findById(operationId))
        assertEquals(OutboxOperationStatus.RESOLVED.name, resolved.status)
        assertEquals(conflictedBefore.conflictRemotePurchaseId, resolved.conflictRemotePurchaseId)
        assertEquals(conflictedBefore.conflictReceiptId, resolved.conflictReceiptId)
        val resolutionAudit = database.auditEventDao().listForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        ).single { it.eventType == AuditEventType.SYNC_CONFLICT_RESOLVED.name }
        assertEquals(auditId, resolutionAudit.auditEventId)
        assertEquals(resolvedAt.toEpochMilli(), resolutionAudit.occurredAt)
        assertPostingTableCounts(
            purchases = 1,
            lines = 1,
            balances = 1,
            movements = 1,
            audits = 2,
            outbox = 1,
        )
    }

    @Test
    fun creditNoteVoidUsesPositiveCompensationAndRestoresQuantity() = runBlocking {
        val opening = InventoryBalanceEntity(
            businessId = BUSINESS_ID,
            productId = PRODUCT_ID,
            locationId = LOCATION_ID,
            quantityOnHand = "10",
            averageUnitCost = "5.00",
            currencyCode = CURRENCY,
            version = 0L,
            updatedAt = 500L,
        )
        assertTrue(database.inventoryDao().insertBalanceIfAbsent(opening) != -1L)
        val draft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        database.invoiceDraftDao().update(
            draft.copy(documentType = PurchaseDocumentType.CREDIT_NOTE.name),
        )
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            documentType = PurchaseDocumentType.CREDIT_NOTE,
        )
        val batch = postingBatch(
            offset = 603,
            documentType = PurchaseDocumentType.CREDIT_NOTE,
            movementQuantity = "-2",
            expectedBalanceVersion = 0L,
            finalBalanceVersion = 1L,
            finalQuantity = "8",
            finalAverageUnitCost = "5.00",
        )
        database.purchasePostingDao().postAtomically(batch)
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val purchaseId = requireNotNull(PurchaseId.parse(batch.purchase.purchaseId))
        val actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER)
        val repository = purchaseVoidRepository(POSTED_AT + 50_000L)
        val preview = repository.preview(businessId, purchaseId, actor)
            as PreviewPurchaseVoidResult.Ready
        assertEquals(
            0,
            preview.preview.impacts.single().reversalQuantity.compareTo(BigDecimal("2")),
        )

        assertTrue(
            repository.void(
                PurchaseVoidCommand(
                    businessId = businessId,
                    purchaseId = purchaseId,
                    reason = "Nota de crédito registrada en el documento equivocado",
                    actor = actor,
                    expectedImpactHash = preview.preview.expectedImpactHash,
                ),
            ) is PurchaseVoidResult.Voided,
        )

        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals("10", balance.quantityOnHand)
        assertEquals("5.00", balance.averageUnitCost)
        val movements = database.inventoryDao().listMovementsForPurchase(
            BUSINESS_ID,
            batch.purchase.purchaseId,
        )
        assertEquals("-2", movements.single { it.type == StockMovementType.PURCHASE.name }.quantityDelta)
        assertEquals("2", movements.single { it.type == StockMovementType.VOID.name }.quantityDelta)
    }

    @Test
    fun healthyPostingIsExposedByInventoryFlowAndReconcilesExactly() = runBlocking {
        val batch = postingBatch(offset = 500)
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val inventory = inventoryReadRepository()
        val initialObserved = CompletableDeferred<Unit>()
        val postedObserved = CompletableDeferred<List<com.facturastock.app.domain.model.InventoryReadItem>>()
        val observation = launch {
            inventory.observeInventory(businessId).collect { items ->
                if (items.isEmpty()) initialObserved.complete(Unit)
                else postedObserved.complete(items)
            }
        }
        withTimeout(5_000) { initialObserved.await() }

        database.purchasePostingDao().postAtomically(batch)
        val emittedItems = withTimeout(5_000) { postedObserved.await() }
        observation.cancelAndJoin()

        val item = emittedItems.single()
        val position = item.positions.single()

        assertEquals(PRODUCT_ID, item.productId.value)
        assertEquals(LOCATION_ID, position.locationId.value)
        assertEquals(0, position.quantityOnHand.compareTo(BigDecimal("2")))
        assertEquals(0, position.averageUnitCost.amount.compareTo(BigDecimal("5")))
        assertEquals(0, position.estimatedValue.compareTo(BigDecimal("10")))
        assertTrue(item.allAlerts.isEmpty())

        val report = inventory.diagnose(businessId)
        val diagnostic = report.positions.single()
        assertTrue(report.isConsistent)
        assertEquals(1, report.movementCount)
        assertEquals(0, requireNotNull(diagnostic.cachedQuantity).compareTo(BigDecimal("2")))
        assertEquals(0, requireNotNull(diagnostic.ledgerQuantity).compareTo(BigDecimal("2")))
        assertEquals(0, requireNotNull(diagnostic.cachedAverageUnitCost).compareTo(BigDecimal("5")))
        assertEquals(0, requireNotNull(diagnostic.ledgerAverageUnitCost).compareTo(BigDecimal("5")))
        assertTrue(diagnostic.issues.isEmpty())
    }

    @Test
    fun inventoryUnitAndReferencedUnitCodeAreImmutableAfterStockHistory() = runBlocking {
        database.purchasePostingDao().postAtomically(postingBatch(offset = 499))
        database.unitDao().insert(
            UnitEntity(
                unitId = PURCHASE_UNIT_ID,
                businessId = BUSINESS_ID,
                code = "CJA",
                name = "Caja",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )

        val product = requireNotNull(database.productDao().findById(PRODUCT_ID))
        val productFailure = runCatching {
            database.productDao().updateCas(product.copy(unitId = PURCHASE_UNIT_ID))
        }.exceptionOrNull()
        assertTrue(productFailure is SQLiteConstraintException)

        val unit = requireNotNull(database.unitDao().findById(UNIT_ID))
        val unitFailure = runCatching {
            database.unitDao().update(unit.copy(code = "KGM"))
        }.exceptionOrNull()
        assertTrue(unitFailure is SQLiteConstraintException)
        assertEquals(UNIT_ID, requireNotNull(database.productDao().findById(PRODUCT_ID)).unitId)
        assertEquals("NIU", requireNotNull(database.unitDao().findById(UNIT_ID)).code)
    }

    @Test
    fun cloudLineSnapshotDoesNotChangeWhenProductCatalogIsRenamed() = runBlocking {
        val posted = productionRepository().confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val before = requireNotNull(
            purchaseReadRepository().observePurchase(businessId, posted.purchaseId).first(),
        )
        val originalName = before.lines.single().productName
        val frozen = database.purchaseLineDao().listForPurchase(posted.purchaseId.value).single()
        assertEquals(originalName, frozen.productNameSnapshot)
        assertEquals("NIU", frozen.unitCodeSnapshot)

        val product = requireNotNull(database.productDao().findById(PRODUCT_ID))
        assertEquals(
            1,
            database.productDao().updateCas(
                product.copy(
                    name = "Nombre nuevo que no altera la compra",
                    normalizedName = "nombre nuevo que no altera la compra",
                    updatedAt = product.updatedAt + 1,
                ),
            ),
        )

        val after = requireNotNull(
            purchaseReadRepository().observePurchase(businessId, posted.purchaseId).first(),
        )
        assertEquals(originalName, after.lines.single().productName)
        assertEquals("NIU", after.lines.single().unitCode)
    }

    @Test
    fun diagnosticReportsExactLedgerAndCacheQuantitiesAfterProjectionDiverges() = runBlocking {
        val batch = postingBatch(offset = 501)
        database.purchasePostingDao().postAtomically(batch)
        val updated = database.inventoryDao().updateBalanceIfVersion(
            businessId = BUSINESS_ID,
            productId = PRODUCT_ID,
            locationId = LOCATION_ID,
            expectedVersion = 0L,
            quantityOnHand = "9.125",
            averageUnitCost = "5.00",
            currencyCode = CURRENCY,
            updatedAt = requireNotNull(batch.purchase.postedAt) + 1L,
        )
        assertEquals(1, updated)
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val inventory = inventoryReadRepository()

        val cachedPosition = inventory.observeInventory(businessId).first()
            .single().positions.single()
        assertEquals(0, cachedPosition.quantityOnHand.compareTo(BigDecimal("9.125")))

        val report = inventory.diagnose(businessId)
        val diagnostic = report.positions.single()
        assertFalse(report.isConsistent)
        assertTrue(InventoryDiagnosticIssue.QUANTITY_DIVERGENCE in diagnostic.issues)
        assertEquals(0, requireNotNull(diagnostic.cachedQuantity).compareTo(BigDecimal("9.125")))
        assertEquals(0, requireNotNull(diagnostic.ledgerQuantity).compareTo(BigDecimal("2")))
        assertNull(diagnostic.ledgerAverageUnitCost)
        assertFalse(InventoryDiagnosticIssue.AVERAGE_COST_DIVERGENCE in diagnostic.issues)
    }

    @Test
    fun diagnosticDetectsAverageCostDivergenceWhenQuantityStillMatchesLedger() = runBlocking {
        val batch = postingBatch(offset = 502)
        database.purchasePostingDao().postAtomically(batch)
        assertEquals(
            1,
            database.inventoryDao().updateBalanceIfVersion(
                businessId = BUSINESS_ID,
                productId = PRODUCT_ID,
                locationId = LOCATION_ID,
                expectedVersion = 0L,
                quantityOnHand = "2",
                averageUnitCost = "6.125",
                currencyCode = CURRENCY,
                updatedAt = requireNotNull(batch.purchase.postedAt) + 1L,
            ),
        )

        val report = inventoryReadRepository().diagnose(requireNotNull(BusinessId.parse(BUSINESS_ID)))
        val diagnostic = report.positions.single()

        assertFalse(report.isConsistent)
        assertTrue(InventoryDiagnosticIssue.AVERAGE_COST_DIVERGENCE in diagnostic.issues)
        assertFalse(InventoryDiagnosticIssue.QUANTITY_DIVERGENCE in diagnostic.issues)
        assertEquals(
            0,
            requireNotNull(diagnostic.ledgerAverageUnitCost).compareTo(BigDecimal("5")),
        )
    }

    @Test
    fun multipleWarehousesAndFactorKeepExactAppliedTotalsInDiagnosticReplay() = runBlocking {
        val secondLocationId = uuid(9_001)
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = secondLocationId,
                businessId = BUSINESS_ID,
                name = "Almacén secundario",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )
        database.unitDao().insert(
            UnitEntity(
                unitId = PURCHASE_UNIT_ID,
                businessId = BUSINESS_ID,
                code = "CJA",
                name = "Caja",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )
        val product = requireNotNull(database.productDao().findById(PRODUCT_ID))
        assertEquals(
            1,
            database.productDao().updateCas(
                product.copy(
                    purchaseUnitId = PURCHASE_UNIT_ID,
                    purchaseFactor = "3",
                ),
            ),
        )

        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            lineQuantity = "1",
            lineUnitCost = "0.01",
        )
        val openingBatch = postingBatch(
            offset = 510,
            lineQuantity = "1",
            lineUnitCost = "0.01",
            movementQuantity = "1",
            movementUnitCost = "0.010000000000000000",
            finalQuantity = "1",
            finalAverageUnitCost = "0.010000000000000000",
        )
        database.purchasePostingDao().postAtomically(openingBatch)

        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "43",
            unitId = PURCHASE_UNIT_ID,
            lineQuantity = "2",
            lineUnitCost = "0.01",
        )
        val factorBatch = postingBatch(
            offset = 511,
            draftId = SECOND_DRAFT_ID,
            documentNumber = "43",
            unitId = PURCHASE_UNIT_ID,
            lineQuantity = "2",
            lineUnitCost = "0.01",
            movementQuantity = "6",
            movementUnitCost = "0.003333333333333333",
            expectedBalanceVersion = 0L,
            finalBalanceVersion = 1L,
            finalQuantity = "7",
            finalAverageUnitCost = "0.004285714285714286",
        )
        database.purchasePostingDao().postAtomically(factorBatch)

        seedPreparedDraft(
            draftId = THIRD_DRAFT_ID,
            documentNumber = "44",
            unitId = PURCHASE_UNIT_ID,
            lineQuantity = "2",
            lineUnitCost = "0.01",
        )
        // postAtomically recibe el destino ya resuelto. Este escenario prueba que el DAO y las
        // lecturas conservan dos stock keys; la política de ubicación default se cubre en el
        // repositorio de confirmación, no se redefine aquí.
        val secondWarehouseBatch = postingBatch(
            offset = 512,
            draftId = THIRD_DRAFT_ID,
            documentNumber = "44",
            unitId = PURCHASE_UNIT_ID,
            locationId = secondLocationId,
            lineQuantity = "2",
            lineUnitCost = "0.01",
            movementQuantity = "6",
            movementUnitCost = "0.003333333333333333",
            finalQuantity = "6",
            finalAverageUnitCost = "0.003333333333333333",
        )
        database.purchasePostingDao().postAtomically(secondWarehouseBatch)

        val exactAppliedTotal = requireNotNull(factorBatch.lines.single().appliedCostTotal)
            .toBigDecimal()
        val reconstructedRoundedTotal = requireNotNull(factorBatch.movements.single().unitCost)
            .toBigDecimal().multiply(factorBatch.movements.single().quantityDelta.toBigDecimal())
        assertNotEquals(0, exactAppliedTotal.compareTo(reconstructedRoundedTotal))

        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val inventory = inventoryReadRepository()
        val item = inventory.observeInventory(businessId).first { items ->
            items.singleOrNull()?.positions?.size == 2
        }.single()
        val primary = item.positions.single { it.locationId.value == LOCATION_ID }
        val secondary = item.positions.single { it.locationId.value == secondLocationId }

        assertEquals(0, primary.quantityOnHand.compareTo(BigDecimal("7")))
        assertEquals(
            0,
            primary.averageUnitCost.amount.compareTo(BigDecimal("0.004285714285714286")),
        )
        assertEquals(0, secondary.quantityOnHand.compareTo(BigDecimal("6")))
        assertEquals(
            0,
            secondary.averageUnitCost.amount.compareTo(BigDecimal("0.003333333333333333")),
        )

        val report = inventory.diagnose(businessId)
        assertTrue(report.isConsistent)
        assertEquals(2, report.positions.size)
        val primaryDiagnostic = report.positions.single { it.locationId.value == LOCATION_ID }
        val secondaryDiagnostic = report.positions.single {
            it.locationId.value == secondLocationId
        }
        assertEquals(
            0,
            requireNotNull(primaryDiagnostic.ledgerAverageUnitCost)
                .compareTo(BigDecimal("0.004285714285714286")),
        )
        assertEquals(2, primaryDiagnostic.movementCount)
        assertEquals(1, secondaryDiagnostic.movementCount)
        assertEquals(
            secondLocationId,
            database.inventoryDao().listMovementsForPurchase(
                BUSINESS_ID,
                secondWarehouseBatch.purchase.purchaseId,
            ).single().locationId,
        )
    }

    @Test
    fun multipleMovementsForOneLineAreRejectedWithoutPartialState() = runBlocking {
        val batch = postingBatch()
        val movement = batch.movements.single()
        val splitBatch = batch.copy(
            movements = listOf(
                movement.copy(quantityDelta = "1"),
                movement.copy(
                    movementId = uuid(1_408),
                    quantityDelta = "1",
                    idempotencyKey = "movement-post-split-second",
                ),
            ),
        )

        assertPostingFails(IllegalArgumentException::class.java, splitBatch)
        assertNoPostingArtifacts(splitBatch, expectedBalanceCount = 0)
        assertEquals(
            DraftStatus.READY_TO_POST.name,
            database.invoiceDraftDao().findById(DRAFT_ID)?.status,
        )
    }

    @Test
    fun freshSchemaKeepsPostedPurchaseLinesAndMovementsImmutable() = runBlocking {
        val batch = postingBatch()
        database.purchasePostingDao().postAtomically(batch)
        val sqlite = database.openHelper.writableDatabase

        assertSqlConstraint {
            sqlite.execSQL(
                "UPDATE `purchases` SET `totalMinorUnits` = `totalMinorUnits` + 1 " +
                    "WHERE `purchaseId` = '${batch.purchase.purchaseId}'",
            )
        }
        assertSqlConstraint {
            sqlite.execSQL(
                "DELETE FROM `purchase_lines` " +
                    "WHERE `purchaseLineId` = '${batch.lines.single().purchaseLineId}'",
            )
        }
        assertSqlConstraint {
            sqlite.execSQL(
                "UPDATE `stock_movements` SET `quantityDelta` = '999' " +
                    "WHERE `movementId` = '${batch.movements.single().movementId}'",
            )
        }

        assertCommittedGraph(batch)
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun productionConfirmationCommitsOnceAndRetryReturnsTheSamePurchase() = runBlocking {
        val repository = productionRepository()
        val command = confirmationCommand(DRAFT_ID)
        val context = confirmationContext()

        val first = repository.confirm(command, context)
        val postedId = (first as ConfirmPurchaseResult.Posted).purchaseId
        val retry = repository.confirm(command, context)

        assertEquals(ConfirmPurchaseResult.AlreadyPosted(postedId), retry)
        assertPostingTableCounts(expected = 1)
        val purchase = requireNotNull(database.purchaseDao().findById(postedId.value))
        assertEquals(PurchaseStatus.POSTED.name, purchase.status)
        val draft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(DraftStatus.COMMITTED.name, draft.status)
        assertEquals(postedId.value, draft.confirmedPurchaseId)
        val lines = database.purchaseLineDao().listForPurchase(postedId.value)
        val movements = database.inventoryDao().listMovementsForPurchase(BUSINESS_ID, postedId.value)
        assertEquals(1, lines.size)
        assertEquals(1, movements.size)
        assertEquals(postedId.value, movements.single().purchaseId)
        assertEquals(lines.single().purchaseLineId, movements.single().purchaseLineId)
    }

    @Test
    fun roomReadFlowEmitsPublishedPurchaseImmediatelyAndEnforcesBusinessIsolation() = runBlocking {
        val reader = purchaseReadRepository()
        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val otherBusinessId = requireNotNull(BusinessId.parse(OTHER_BUSINESS_ID))
        val emissions = Channel<List<com.facturastock.app.domain.model.PurchaseReadSummary>>(
            Channel.UNLIMITED,
        )
        val collector = launch {
            reader.observePurchases(businessId).collect(emissions::send)
        }
        assertTrue(emissions.receive().isEmpty())

        val command = confirmationCommand(DRAFT_ID)
        val posting = productionRepository()
        val posted = posting.confirm(command, confirmationContext()) as ConfirmPurchaseResult.Posted
        val visible = emissions.receive()

        assertEquals(1, visible.size)
        assertEquals(posted.purchaseId, visible.single().purchaseId)
        assertEquals(1, visible.single().lineCount)
        assertEquals(1, visible.single().productCount)
        assertEquals(PurchaseSyncState.PENDING_SYNC, visible.single().syncState)
        assertTrue(reader.observePurchases(otherBusinessId).first().isEmpty())
        assertNull(reader.observePurchase(otherBusinessId, posted.purchaseId).first())

        val detail = requireNotNull(reader.observePurchase(businessId, posted.purchaseId).first())
        assertEquals(visible.single(), detail.summary)
        assertEquals(1, detail.lines.size)
        assertEquals(1, detail.movements.size)
        assertEquals(detail.lines.single().purchaseLineId, detail.movements.single().purchaseLineId)
        assertEquals(1, detail.auditEvents.size)
        assertEquals(command.expectedPreparedLogicalHash, detail.preparedLogicalHash)
        assertNull(detail.duplicateOverride)

        assertEquals(ConfirmPurchaseResult.AlreadyPosted(posted.purchaseId), posting.confirm(command, confirmationContext()))
        assertEquals(1, reader.observePurchases(businessId).first().size)
        collector.cancelAndJoin()
        emissions.close()
        Unit
    }

    @Test
    fun purchaseSyncBadgeIgnoresNewerDocumentTransferOperations() = runBlocking {
        val batch = postingBatch()
        database.purchasePostingDao().postAtomically(batch)
        database.outboxOperationDao().insert(
            OutboxOperationEntity(
                operationId = uuid(87),
                businessId = BUSINESS_ID,
                purchaseId = batch.purchase.purchaseId,
                idempotencyKey = "document-upload-badge-isolation",
                operationType = "SYNC_DOCUMENT_UPLOAD",
                payload = "{\"document\":true}",
                status = OutboxOperationStatus.FAILED.name,
                createdAt = batch.outboxOperations.single().createdAt + 1L,
                updatedAt = batch.outboxOperations.single().updatedAt + 1L,
                lastError = "document-only failure",
                entityType = "DOCUMENT",
                entityId = uuid(88),
                entityVersion = 1L,
            ),
        )

        val businessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val purchaseId = requireNotNull(PurchaseId.parse(batch.purchase.purchaseId))
        val summary = requireNotNull(
            purchaseReadRepository().observePurchase(businessId, purchaseId).first(),
        ).summary

        assertEquals(PurchaseSyncState.PENDING_SYNC, summary.syncState)
        assertNull(summary.lastSyncError)
    }

    @Test
    fun roomReadDetailKeepsAcceptedThreeCentReconciliationVisible() = runBlocking {
        val basePrepared = preparedPurchase(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
        )
        val adjustedLines = basePrepared.lines.map { line ->
            line.copy(lineTotal = Money.ofMinor(1_183L, PEN))
        }
        val warnings = listOf("LINES_TOTAL_DIFFERENCE")
        val logicalHash = PreparedPurchase.logicalHash(
            draftId = basePrepared.draftId,
            businessId = basePrepared.businessId,
            supplierId = basePrepared.supplierId,
            supplierRuc = basePrepared.supplierRuc,
            supplierLegalName = basePrepared.supplierLegalName,
            documentType = basePrepared.documentType,
            documentNumber = basePrepared.documentNumber,
            issueDate = basePrepared.issueDate,
            currency = basePrepared.currency,
            lines = adjustedLines,
            subtotal = basePrepared.subtotal,
            tax = basePrepared.tax,
            otherCharges = basePrepared.otherCharges,
            total = basePrepared.total,
            acceptedWarnings = warnings,
        )
        val adjustedPrepared = basePrepared.copy(
            lines = adjustedLines,
            acceptedWarnings = warnings,
            logicalHash = logicalHash,
        )
        val payload = PreparedPurchaseCodec.encode(adjustedPrepared)
        database.preparedPurchaseDao().upsert(
            PreparedPurchaseEntity(
                draftId = DRAFT_ID,
                logicalHash = logicalHash,
                payloadCodecVersion = PreparedPurchaseCodec.VERSION,
                payloadSha256 = PreparedPurchaseCodec.sha256(payload),
                payload = payload,
                preparedAt = adjustedPrepared.preparedAt.toEpochMilli(),
            ),
        )
        val baseBatch = postingBatch()
        val adjustedBatch = baseBatch.copy(
            expectedPreparedLogicalHash = logicalHash,
            lines = baseBatch.lines.map { line -> line.copy(totalMinorUnits = 1_183L) },
        )

        database.purchasePostingDao().postAtomically(adjustedBatch)

        val detail = requireNotNull(
            purchaseReadRepository().observePurchase(
                requireNotNull(BusinessId.parse(BUSINESS_ID)),
                requireNotNull(PurchaseId.parse(adjustedBatch.purchase.purchaseId)),
            ).first(),
        )
        assertEquals(1_183L, detail.lines.single().total.minorUnits)
        assertEquals(1_180L, detail.summary.total.minorUnits)
        assertEquals(-3L, detail.adjustment?.minorUnits)
        assertEquals(warnings, detail.acceptedWarnings)
        assertEquals(1, detail.lines.size)
        assertEquals(1, detail.movements.size)
    }

    @Test
    fun postedAuditRedactsAdjustmentWhileOutboxKeepsTheFunctionalPayload() = runBlocking {
        val adjustment = PurchaseReconciliationAdjustment(
            amount = Money.ofMinor(731L, PEN),
            reason = POSTED_ADJUSTMENT_REASON_SENTINEL,
        )
        val base = preparedPurchase(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
        )
        val adjusted = base.copy(
            reconciliationAdjustment = adjustment,
            logicalHash = PreparedPurchase.logicalHash(
                draftId = base.draftId,
                businessId = base.businessId,
                supplierId = base.supplierId,
                supplierRuc = base.supplierRuc,
                supplierLegalName = base.supplierLegalName,
                documentType = base.documentType,
                documentNumber = base.documentNumber,
                issueDate = base.issueDate,
                currency = base.currency,
                lines = base.lines,
                subtotal = base.subtotal,
                tax = base.tax,
                otherCharges = base.otherCharges,
                total = base.total,
                acceptedWarnings = base.acceptedWarnings,
                reconciliationAdjustment = adjustment,
            ),
        )
        val encoded = PreparedPurchaseCodec.encode(adjusted)
        database.preparedPurchaseDao().upsert(
            PreparedPurchaseEntity(
                draftId = DRAFT_ID,
                logicalHash = adjusted.logicalHash,
                payloadCodecVersion = PreparedPurchaseCodec.VERSION,
                payloadSha256 = PreparedPurchaseCodec.sha256(encoded),
                payload = encoded,
                preparedAt = adjusted.preparedAt.toEpochMilli(),
            ),
        )

        val posted = productionRepository().confirm(
            ConfirmPurchaseCommand(adjusted.draftId, adjusted.logicalHash),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val audit = database.auditEventDao().listForPurchase(BUSINESS_ID, posted.purchaseId.value)
            .single { it.eventType == AuditEventType.PURCHASE_POSTED.name }
        val outbox = requireNotNull(
            database.outboxOperationDao().findLatestForPurchase(BUSINESS_ID, posted.purchaseId.value),
        )
        assertTrue(audit.payload.contains("\"adjustmentApplied\":\"true\""))
        assertFalse(audit.payload.contains(POSTED_ADJUSTMENT_REASON_SENTINEL))
        assertFalse(audit.payload.contains("\"reconciliationAdjustment\""))
        assertFalse(audit.payload.contains("\"minorUnits\":731"))
        assertTrue(outbox.payload.contains(POSTED_ADJUSTMENT_REASON_SENTINEL))
        assertTrue(outbox.payload.contains("\"reconciliationAdjustment\""))
        assertTrue(outbox.payload.contains("\"minorUnits\":731"))
        assertEquals(OutboxOperationStatus.PENDING.name, outbox.status)
        assertEquals("SYNC_PURCHASE", outbox.operationType)
        assertEquals(4, outbox.payloadVersion)
        assertTrue(outbox.payload.startsWith("{\"version\":4"))
    }

    @Test
    fun productionConfirmationRejectsAStaleReviewedHashWithoutWriting() = runBlocking {
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val result = productionRepository().confirm(
            ConfirmPurchaseCommand(
                draftId = requireNotNull(DraftId.parse(DRAFT_ID)),
                expectedPreparedLogicalHash = "f".repeat(64),
            ),
            confirmationContext(),
        )

        assertEquals(ConfirmPurchaseResult.PreparedChanged, result)
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    @Test
    fun staleRetryAfterCommitCannotClaimAnotherPreparedHashAsAlreadyPosted() = runBlocking {
        val repository = productionRepository()
        val posted = repository.confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val staleRetry = repository.confirm(
            ConfirmPurchaseCommand(
                draftId = requireNotNull(DraftId.parse(DRAFT_ID)),
                expectedPreparedLogicalHash = "f".repeat(64),
            ),
            confirmationContext(),
        )

        assertEquals(ConfirmPurchaseResult.PreparedChanged, staleRetry)
        assertEquals(
            posted.purchaseId.value,
            database.invoiceDraftDao().findById(DRAFT_ID)?.confirmedPurchaseId,
        )
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun productionConfirmationUsesPurchaseFactorWhenBothCatalogUnitsCoincide() = runBlocking {
        val product = requireNotNull(database.productDao().findById(PRODUCT_ID))
        assertEquals(
            1,
            database.productDao().updateCas(
                product.copy(
                    purchaseUnitId = UNIT_ID,
                    purchaseFactor = "3",
                    updatedAt = product.updatedAt + 1,
                ),
            ),
        )

        val result = productionRepository().confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        )

        val purchaseId = (result as ConfirmPurchaseResult.Posted).purchaseId.value
        val movement = database.inventoryDao()
            .listMovementsForPurchase(BUSINESS_ID, purchaseId)
            .single()
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals(0, movement.quantityDelta.toBigDecimal().compareTo(BigDecimal("6")))
        assertEquals(0, balance.quantityOnHand.toBigDecimal().compareTo(BigDecimal("6")))
    }

    @Test
    fun productionConfirmationBlocksAnExistingBalanceInAnotherCurrencyWithoutWriting() =
        runBlocking {
            assertTrue(
                database.inventoryDao().insertBalanceIfAbsent(
                    InventoryBalanceEntity(
                        businessId = BUSINESS_ID,
                        productId = PRODUCT_ID,
                        locationId = LOCATION_ID,
                        quantityOnHand = "4",
                        averageUnitCost = "2",
                        currencyCode = "USD",
                        version = 0,
                        updatedAt = DRAFT_UPDATED_AT,
                    ),
                ) != -1L,
            )

            val result = productionRepository().confirm(
                confirmationCommand(DRAFT_ID),
                confirmationContext(),
            )

            assertEquals(
                ConfirmPurchaseResult.Blocked(
                    setOf(
                        PurchaseConfirmationBlocker.BalanceCurrencyMismatch(
                            lineId = requireNotNull(LineId.parse(PREPARED_LINE_ID)),
                            expected = PEN,
                            actual = CurrencyCode.of("USD"),
                        ),
                    ),
                ),
                result,
            )
            assertPostingTableCounts(
                purchases = 0,
                lines = 0,
                balances = 1,
                movements = 0,
                audits = 0,
                outbox = 0,
            )
            assertEquals(DraftStatus.READY_TO_POST.name, database.invoiceDraftDao().findById(DRAFT_ID)?.status)
        }

    @Test
    fun authorizedExactDuplicateIsPostedAndAuditedInsideTheSameCommit() = runBlocking {
        val repository = productionRepository()
        val first = repository.confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted
        seedPreparedDraft(SECOND_DRAFT_ID, documentNumber = "42")
        val secondDraftId = requireNotNull(DraftId.parse(SECOND_DRAFT_ID))
        val sensitiveOverrideReason =
            "PRIVATE_DUPLICATE_OVERRIDE_user@example.pe_RUC_20123456789"
        val override = PurchaseDuplicateOverride(
            draftId = secondDraftId,
            preparedLogicalHash = requireNotNull(
                database.preparedPurchaseDao().find(SECOND_DRAFT_ID),
            ).logicalHash,
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            existingPurchaseId = first.purchaseId,
            duplicateKind = PurchaseDuplicateKind.EXACT,
            reasons = setOf(
                PurchaseDuplicateReason.SAME_BUSINESS,
                PurchaseDuplicateReason.SAME_SUPPLIER,
                PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
                PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER,
            ),
            reason = sensitiveOverrideReason,
            actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER),
        )

        val second = repository.confirm(
            confirmationCommand(secondDraftId.value, duplicateOverride = override),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val stored = requireNotNull(database.purchaseDao().findById(second.purchaseId.value))
        assertEquals(SECOND_DRAFT_ID, stored.documentIdentitySlot)
        assertEquals(first.purchaseId.value, stored.duplicateOverrideOfPurchaseId)
        assertEquals(override.reason, stored.duplicateOverrideReason)
        assertEquals(override.actor.actorId, stored.duplicateOverrideActorId)
        assertEquals(PurchaseOverrideRole.OWNER.name, stored.duplicateOverrideRole)
        val readDetail = requireNotNull(
            purchaseReadRepository().observePurchase(
                requireNotNull(BusinessId.parse(BUSINESS_ID)),
                second.purchaseId,
            ).first(),
        )
        val readOverride = requireNotNull(readDetail.duplicateOverride)
        assertEquals(first.purchaseId, readOverride.existingPurchaseId)
        assertEquals(override.reason, readOverride.reason)
        assertEquals(override.actor.actorId, readOverride.actorId)
        assertEquals(PurchaseOverrideRole.OWNER, readOverride.actorRole)
        val events = database.auditEventDao().listForPurchase(BUSINESS_ID, second.purchaseId.value)
        assertEquals(
            setOf(
                AuditEventType.PURCHASE_DUPLICATE_OVERRIDE.name,
                AuditEventType.PURCHASE_POSTED.name,
            ),
            events.mapTo(linkedSetOf(), AuditEventEntity::eventType),
        )
        val overrideAudit = events.single {
            it.eventType == AuditEventType.PURCHASE_DUPLICATE_OVERRIDE.name
        }
        assertFalse(overrideAudit.payload.contains(sensitiveOverrideReason))
        assertFalse(overrideAudit.payload.contains(override.actor.actorId))
        assertFalse(overrideAudit.payload.contains(override.preparedLogicalHash))
        assertTrue(overrideAudit.payload.contains("\"existingPurchaseId\":\"${first.purchaseId.value}\""))
        assertTrue(overrideAudit.payload.contains("\"duplicateKind\":\"EXACT\""))
        assertTrue(overrideAudit.payload.contains("\"actorRole\":\"OWNER\""))
        assertEquals(DraftStatus.COMMITTED.name, database.invoiceDraftDao().findById(SECOND_DRAFT_ID)?.status)
        assertPostingTableCounts(
            purchases = 2,
            lines = 2,
            balances = 1,
            movements = 2,
            audits = 3,
            outbox = 2,
        )
    }

    @Test
    fun duplicateOverrideAuthorizedForAnotherHashCannotPublishOrWriteAudit() = runBlocking {
        val repository = productionRepository()
        val first = repository.confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted
        seedPreparedDraft(SECOND_DRAFT_ID, documentNumber = "42")
        val secondDraftId = requireNotNull(DraftId.parse(SECOND_DRAFT_ID))
        val staleOverride = PurchaseDuplicateOverride(
            draftId = secondDraftId,
            preparedLogicalHash = "f".repeat(64),
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            existingPurchaseId = first.purchaseId,
            duplicateKind = PurchaseDuplicateKind.EXACT,
            reasons = setOf(
                PurchaseDuplicateReason.SAME_BUSINESS,
                PurchaseDuplicateReason.SAME_SUPPLIER,
                PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
                PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER,
            ),
            reason = "Autorización perteneciente a otra preparación",
            actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER),
        )

        val result = repository.confirm(
            confirmationCommand(SECOND_DRAFT_ID, duplicateOverride = staleOverride),
            confirmationContext(),
        )

        assertEquals(ConfirmPurchaseResult.ExactDuplicate(first.purchaseId), result)
        assertEquals(
            DraftStatus.READY_TO_POST.name,
            database.invoiceDraftDao().findById(SECOND_DRAFT_ID)?.status,
        )
        assertTrue(
            database.auditEventDao().listForEntity(
                businessId = BUSINESS_ID,
                entityType = "INVOICE_DRAFT",
                entityId = SECOND_DRAFT_ID,
            ).isEmpty(),
        )
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun authorizedExactDuplicateByFrozenRucCanUseAnotherSupplierRow() = runBlocking {
        val repository = productionRepository()
        val first = repository.confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted
        val historicalSupplier = requireNotNull(database.supplierDao().findById(SUPPLIER_ID))
        assertEquals(
            1,
            database.supplierDao().updateCas(
                historicalSupplier.copy(
                    ruc = UPDATED_SUPPLIER_RUC,
                    updatedAt = historicalSupplier.updatedAt + 1,
                ),
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = REASSIGNED_SUPPLIER_ID,
                businessId = BUSINESS_ID,
                legalName = "Proveedor actual del RUC",
                ruc = SUPPLIER_RUC,
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "42",
            supplierId = REASSIGNED_SUPPLIER_ID,
            supplierRuc = SUPPLIER_RUC,
            supplierLegalName = "Proveedor actual del RUC",
        )
        val secondDraftId = requireNotNull(DraftId.parse(SECOND_DRAFT_ID))
        assertEquals(
            ConfirmPurchaseResult.ExactDuplicate(first.purchaseId),
            repository.confirm(confirmationCommand(secondDraftId.value), confirmationContext()),
        )
        val override = PurchaseDuplicateOverride(
            draftId = secondDraftId,
            preparedLogicalHash = requireNotNull(
                database.preparedPurchaseDao().find(SECOND_DRAFT_ID),
            ).logicalHash,
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            existingPurchaseId = first.purchaseId,
            duplicateKind = PurchaseDuplicateKind.EXACT,
            reasons = setOf(
                PurchaseDuplicateReason.SAME_BUSINESS,
                PurchaseDuplicateReason.SAME_SUPPLIER,
                PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
                PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER,
            ),
            reason = "RUC histórico verificado por contabilidad",
            actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER),
        )

        val result = repository.confirm(
            confirmationCommand(secondDraftId.value, duplicateOverride = override),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val stored = requireNotNull(database.purchaseDao().findById(result.purchaseId.value))
        assertEquals(REASSIGNED_SUPPLIER_ID, stored.supplierId)
        assertEquals(first.purchaseId.value, stored.duplicateOverrideOfPurchaseId)
        assertEquals(DraftStatus.COMMITTED.name, database.invoiceDraftDao().findById(SECOND_DRAFT_ID)?.status)
    }

    @Test
    fun exactDuplicateWithoutAtomicAuthorizationOpensExistingAndWritesNothing() = runBlocking {
        val repository = productionRepository()
        val first = repository.confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted
        seedPreparedDraft(SECOND_DRAFT_ID, documentNumber = "42")
        val secondDraftBefore = requireNotNull(database.invoiceDraftDao().findById(SECOND_DRAFT_ID))

        val result = repository.confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        )

        assertEquals(ConfirmPurchaseResult.ExactDuplicate(first.purchaseId), result)
        assertEquals(secondDraftBefore, database.invoiceDraftDao().findById(SECOND_DRAFT_ID))
        assertPostingTableCounts(
            purchases = 1,
            lines = 1,
            balances = 1,
            movements = 1,
            audits = 1,
            outbox = 1,
        )
    }

    @Test
    fun concurrentProductionConfirmationCreatesOneGraphAndBothCallersResolveOnePurchase() =
        runBlocking {
            val repository = productionRepository()
            val command = confirmationCommand(DRAFT_ID)
            val context = confirmationContext()

            val results = coroutineScope {
                listOf(
                    async { repository.confirm(command, context) },
                    async { repository.confirm(command, context) },
                ).awaitAll()
            }

            val ids = results.map { result ->
                when (result) {
                    is ConfirmPurchaseResult.Posted -> result.purchaseId
                    is ConfirmPurchaseResult.AlreadyPosted -> result.purchaseId
                    else -> error("Resultado inesperado: $result")
                }
            }
            assertEquals(1, ids.distinct().size)
            assertEquals(1, results.count { it is ConfirmPurchaseResult.Posted })
            assertEquals(1, results.count { it is ConfirmPurchaseResult.AlreadyPosted })
            assertPostingTableCounts(expected = 1)
        }

    @Test
    fun productionConfirmationFailureAtFinalSessionStepLeavesNoPartialState() = runBlocking {
        val repository = productionRepository()
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER `test_block_production_commit` " +
                "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
                "WHEN NEW.`status` = 'COMMITTED' " +
                "BEGIN SELECT RAISE(ABORT, 'blocked production commit'); END",
        )

        val result = try {
            repository.confirm(
                confirmationCommand(DRAFT_ID),
                confirmationContext(),
            )
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS `test_block_production_commit`")
        }

        assertEquals(ConfirmPurchaseResult.RetryableConflict, result)
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
        assertEquals(0, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))

        // Al liberar el fallo (p. ej. tras recuperar espacio), el mismo comando publica una vez.
        val retry = repository.confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        )
        assertTrue(retry is ConfirmPurchaseResult.Posted)
        assertPostingTableCounts(expected = 1)
        assertEquals(
            ConfirmPurchaseResult.AlreadyPosted((retry as ConfirmPurchaseResult.Posted).purchaseId),
            repository.confirm(confirmationCommand(DRAFT_ID), confirmationContext()),
        )
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun productionConfirmationCreatesStagedProductAndAliasOnlyInsideTheCommit() = runBlocking {
        val stagedId = uuid(1_710)
        val staged = StagedPurchaseProduct(
            productId = requireNotNull(ProductId.parse(stagedId)),
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            unitId = requireNotNull(UnitId.parse(UNIT_ID)),
            name = "Producto staged unico",
            sku = "SKU-STAGED-1710",
            salePrice = Money.ofMinor(650L, CurrencyCode.of("PEN")),
        )
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "71",
            productId = stagedId,
            lineDescription = "Alias OCR del producto staged",
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )

        assertNull(database.productDao().findById(stagedId))
        assertEquals(0, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))

        val posted = productionRepository().confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val product = requireNotNull(database.productDao().findById(stagedId))
        assertEquals(staged.name, product.name)
        assertEquals(staged.sku, product.sku)
        assertEquals(650L, product.salePriceMinorUnits)
        assertEquals("PEN", product.salePriceCurrencyCode)
        val line = database.purchaseLineDao().listForPurchase(posted.purchaseId.value).single()
        assertEquals(stagedId, line.productId)
        assertEquals(PurchaseProductProvenance.CREATED_IN_DRAFT.name, line.productProvenance)
        val alias = database.supplierProductAliasDao()
            .findByNormalizedAlias(BUSINESS_ID, "alias ocr del producto staged")
            .single()
        assertEquals(stagedId, alias.productId)
        val productOutbox = requireNotNull(
            database.outboxOperationDao().findByIdempotencyKey(
                "sync-product:v2:$stagedId:1",
            ),
        )
        assertEquals("SYNC_PRODUCT", productOutbox.operationType)
        assertEquals("PRODUCT", productOutbox.entityType)
        assertEquals(stagedId, productOutbox.entityId)
        assertEquals(1L, productOutbox.entityVersion)
        assertEquals(2, productOutbox.payloadVersion)
        assertTrue(productOutbox.payload.startsWith("{\"version\":2"))
        assertTrue(productOutbox.payload.contains("\"salePriceMinorUnits\":650"))
        assertTrue(productOutbox.payload.contains("\"salePriceCurrencyCode\":\"PEN\""))
    }

    @Test
    fun stagedProductWithoutSalePriceIsBlockedBeforeAnyPostingArtifact() = runBlocking {
        val stagedId = uuid(1_714)
        val staged = StagedPurchaseProduct(
            productId = requireNotNull(ProductId.parse(stagedId)),
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            unitId = requireNotNull(UnitId.parse(UNIT_ID)),
            name = "Producto staged legacy sin precio",
            sku = "SKU-STAGED-1714",
            salePrice = null,
        )
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "74",
            productId = stagedId,
            lineDescription = "Alias staged legacy sin precio",
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )

        val result = productionRepository().confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        )

        assertEquals(
            ConfirmPurchaseResult.Blocked(
                setOf(
                    PurchaseConfirmationBlocker.SalePriceRequired(
                        lineId = requireNotNull(LineId.parse(PREPARED_LINE_ID)),
                        productId = requireNotNull(ProductId.parse(stagedId)),
                    ),
                ),
            ),
            result,
        )
        assertNull(database.productDao().findById(stagedId))
        assertEquals(0, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    @Test
    fun finalFailureRollsBackStagedProductAndItsAlias() = runBlocking {
        val stagedId = uuid(1_711)
        val staged = StagedPurchaseProduct(
            productId = requireNotNull(ProductId.parse(stagedId)),
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            unitId = requireNotNull(UnitId.parse(UNIT_ID)),
            name = "Producto staged rollback",
            barcode = "7750000017110",
            salePrice = Money.ofMinor(700L, CurrencyCode.of("PEN")),
        )
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "72",
            productId = stagedId,
            lineDescription = "Alias OCR que debe revertirse",
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(SECOND_DRAFT_ID))
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(SECOND_DRAFT_ID))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER `test_block_staged_commit` " +
                "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
                "WHEN NEW.`status` = 'COMMITTED' " +
                "BEGIN SELECT RAISE(ABORT, 'blocked staged commit'); END",
        )

        val result = try {
            productionRepository().confirm(
                confirmationCommand(SECOND_DRAFT_ID),
                confirmationContext(),
            )
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS `test_block_staged_commit`")
        }

        assertEquals(ConfirmPurchaseResult.RetryableConflict, result)
        assertNull(database.productDao().findById(stagedId))
        assertEquals(0, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))
        assertEquals(draftBefore, database.invoiceDraftDao().findById(SECOND_DRAFT_ID))
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(SECOND_DRAFT_ID))
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    @Test
    fun stagedProductNaturalKeyClaimedBeforeConfirmBlocksWithoutArtifacts() = runBlocking {
        val stagedId = uuid(1_712)
        val staged = StagedPurchaseProduct(
            productId = requireNotNull(ProductId.parse(stagedId)),
            businessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
            unitId = requireNotNull(UnitId.parse(UNIT_ID)),
            name = "Producto staged en carrera",
            sku = "SKU-STAGED-RACE",
            salePrice = Money.ofMinor(750L, CurrencyCode.of("PEN")),
        )
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "73",
            productId = stagedId,
            lineDescription = "Alias OCR en carrera",
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )
        val competingId = uuid(1_713)
        database.productDao().insert(
            ProductEntity(
                productId = competingId,
                businessId = BUSINESS_ID,
                unitId = UNIT_ID,
                locationId = LOCATION_ID,
                name = "Producto ganador de la carrera",
                sku = staged.sku,
                createdAt = DRAFT_UPDATED_AT + 1,
                updatedAt = DRAFT_UPDATED_AT + 1,
            ),
        )

        val result = productionRepository().confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        )

        assertEquals(
            ConfirmPurchaseResult.Blocked(
                setOf(
                    PurchaseConfirmationBlocker.ProductUnavailable(
                        requireNotNull(LineId.parse(PREPARED_LINE_ID)),
                        requireNotNull(ProductId.parse(stagedId)),
                    ),
                ),
            ),
            result,
        )
        assertNull(database.productDao().findById(stagedId))
        assertNotNull(database.productDao().findById(competingId))
        assertEquals(0, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    @Test
    fun productionConfirmationCreatesMissingSupplierAndAliasInTheCommit() = runBlocking {
        val newRuc = "20987654321"
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "77",
            supplierId = null,
            supplierRuc = newRuc,
            supplierLegalName = "Proveedor nuevo SAC",
        )

        val result = productionRepository().confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val supplier = requireNotNull(database.supplierDao().findByRuc(BUSINESS_ID, newRuc))
        assertEquals("Proveedor nuevo SAC", supplier.legalName)
        assertEquals(supplier.supplierId, database.purchaseDao().findById(result.purchaseId.value)?.supplierId)
        assertEquals(1, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))
        val supplierOutbox = requireNotNull(
            database.outboxOperationDao().findByIdempotencyKey(
                "sync-supplier:v1:${supplier.supplierId}:1",
            ),
        )
        assertEquals("SYNC_SUPPLIER", supplierOutbox.operationType)
        assertEquals("SUPPLIER", supplierOutbox.entityType)
        assertEquals(supplier.supplierId, supplierOutbox.entityId)
        assertEquals(1L, supplierOutbox.entityVersion)
    }

    @Test
    fun releasedRucCreatesANewStableSupplierInsteadOfReusingAnOccupiedId() = runBlocking {
        val originalRuc = OTHER_SUPPLIER_RUC
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "77",
            supplierId = null,
            supplierRuc = originalRuc,
            supplierLegalName = "Proveedor original SAC",
        )
        val repository = productionRepository()
        repository.confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted
        val original = requireNotNull(database.supplierDao().findByRuc(BUSINESS_ID, originalRuc))
        assertEquals(
            1,
            database.supplierDao().updateCas(
                original.copy(
                    ruc = RELEASED_SUPPLIER_RUC,
                    updatedAt = original.updatedAt + 1,
                ),
            ),
        )
        seedPreparedDraft(
            draftId = THIRD_DRAFT_ID,
            documentNumber = "78",
            supplierId = null,
            supplierRuc = originalRuc,
            supplierLegalName = "Proveedor reemplazo SAC",
        )

        val second = repository.confirm(
            confirmationCommand(THIRD_DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val replacement = requireNotNull(database.supplierDao().findByRuc(BUSINESS_ID, originalRuc))
        assertNotEquals(original.supplierId, replacement.supplierId)
        assertEquals(replacement.supplierId, database.purchaseDao().findById(second.purchaseId.value)?.supplierId)
    }

    @Test
    fun releasedAliasCreatesANewStableRowInsteadOfReusingAnOccupiedId() = runBlocking {
        val repository = productionRepository()
        repository.confirm(
            confirmationCommand(DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted
        val original = database.supplierProductAliasDao()
            .findByNormalizedAlias(BUSINESS_ID, "producto de prueba")
            .single()
        assertEquals(
            1,
            database.supplierProductAliasDao().update(
                original.copy(
                    alias = "Alias renombrado",
                    aliasNormalized = "alias renombrado",
                    updatedAt = original.updatedAt + 1,
                ),
            ),
        )
        seedPreparedDraft(SECOND_DRAFT_ID, documentNumber = "43")

        repository.confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        ) as ConfirmPurchaseResult.Posted

        val replacement = database.supplierProductAliasDao()
            .findByNormalizedAlias(BUSINESS_ID, "producto de prueba")
            .single()
        assertNotEquals(original.aliasId, replacement.aliasId)
        assertEquals(2, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))
    }

    @Test
    fun finalFailureAlsoRollsBackNewSupplierAndAlias() = runBlocking {
        val newRuc = "20987654321"
        seedPreparedDraft(
            draftId = SECOND_DRAFT_ID,
            documentNumber = "78",
            supplierId = null,
            supplierRuc = newRuc,
            supplierLegalName = "Proveedor transitorio SAC",
        )
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(SECOND_DRAFT_ID))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER `test_block_supplier_commit` " +
                "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
                "WHEN NEW.`status` = 'COMMITTED' " +
                "BEGIN SELECT RAISE(ABORT, 'blocked supplier commit'); END",
        )

        val result = productionRepository().confirm(
            confirmationCommand(SECOND_DRAFT_ID),
            confirmationContext(),
        )

        assertEquals(ConfirmPurchaseResult.RetryableConflict, result)
        assertNull(database.supplierDao().findByRuc(BUSINESS_ID, newRuc))
        assertEquals(0, database.supplierProductAliasDao().countForBusiness(BUSINESS_ID))
        assertEquals(draftBefore, database.invoiceDraftDao().findById(SECOND_DRAFT_ID))
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = 0,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    @Test
    fun draftCommitFailureRollsBackTheCompletePostingGraph() = runBlocking {
        val batch = postingBatch()
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER `test_block_draft_commit` " +
                "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
                "WHEN NEW.`status` = 'COMMITTED' " +
                "BEGIN SELECT RAISE(ABORT, 'blocked draft commit'); END",
        )

        assertPostingFails(SQLiteConstraintException::class.java, batch)

        assertNoPostingArtifacts(batch, expectedBalanceCount = 0)
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
    }

    @Test
    fun duplicateCandidateKeepsFrozenDraftRucAfterSupplierEdit() = runBlocking {
        database.purchasePostingDao().postAtomically(postingBatch())
        val supplier = requireNotNull(database.supplierDao().findById(SUPPLIER_ID))
        assertEquals(
            1,
            database.supplierDao().updateCas(
                supplier.copy(
                    legalName = "Proveedor renombrado",
                    ruc = "20111111111",
                    updatedAt = supplier.updatedAt + 1,
                ),
            ),
        )

        val rows = database.purchaseDao().listDuplicateCandidateRows(
            businessId = BUSINESS_ID,
            supplierId = null,
            supplierRuc = SUPPLIER_RUC,
            documentType = PurchaseDocumentType.INVOICE.name,
        )

        assertEquals(1, rows.map { it.purchaseId }.distinct().size)
        assertTrue(rows.all { it.supplierRuc == SUPPLIER_RUC })
        assertTrue(rows.all { it.supplierLegalName == "proveedor principal" })
    }

    @Test
    fun orphanDuplicateOverrideAuditIsRejected() = runBlocking {
        seedPreparedDraft(SECOND_DRAFT_ID, documentNumber = "43")
        val posted = database.purchasePostingDao().postAtomically(postingBatch())
        val event = AuditEventEntity(
            auditEventId = uuid(990),
            businessId = BUSINESS_ID,
            purchaseId = null,
            eventType = AuditEventType.PURCHASE_DUPLICATE_OVERRIDE.name,
            entityType = "INVOICE_DRAFT",
            entityId = SECOND_DRAFT_ID,
            payload = "{\"existingPurchaseId\":\"${posted.purchaseId}\",\"reason\":\"autorizado\"}",
            occurredAt = POSTED_AT + 1,
        )

        val failure = runCatching { database.auditEventDao().insert(event) }.exceptionOrNull()
        assertTrue(failure is SQLiteConstraintException)
        assertTrue(
            database.auditEventDao().listForEntity(
                businessId = BUSINESS_ID,
                entityType = "INVOICE_DRAFT",
                entityId = SECOND_DRAFT_ID,
            ).isEmpty(),
        )
    }

    @Test
    fun existingBalanceUsesWeightedAverageRoundedToScale18() = runBlocking {
        val balanceBefore = balance(version = 0L, quantity = "10", updatedAt = 500L)
        assertTrue(database.inventoryDao().insertBalanceIfAbsent(balanceBefore) != -1L)
        val batch = postingBatch(
            offset = 400,
            expectedBalanceVersion = 0L,
            finalBalanceVersion = 1L,
            finalQuantity = "12",
            finalAverageUnitCost = "4.166666666666666667",
        )

        val posted = database.purchasePostingDao().postAtomically(batch)

        assertEquals(batch.purchase, posted)
        assertCommittedGraph(batch)
        val stored = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals("12", stored.quantityOnHand)
        assertEquals("4.166666666666666667", stored.averageUnitCost)
        assertEquals(1L, stored.version)
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun purchaseUnitFactorConvertsQuantityAndCostAtScale18() = runBlocking {
        database.unitDao().insert(
            UnitEntity(
                unitId = PURCHASE_UNIT_ID,
                businessId = BUSINESS_ID,
                code = "CJA",
                name = "Caja",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )
        val product = requireNotNull(database.productDao().findById(PRODUCT_ID))
        assertEquals(
            1,
            database.productDao().updateCas(
                product.copy(
                    purchaseUnitId = PURCHASE_UNIT_ID,
                    purchaseFactor = "3",
                ),
            ),
        )
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = PURCHASE_UNIT_ID,
            lineQuantity = "2",
            lineUnitCost = "10",
        )
        val batch = postingBatch(
            offset = 401,
            unitId = PURCHASE_UNIT_ID,
            lineQuantity = "2",
            lineUnitCost = "10",
            movementQuantity = "6",
            movementUnitCost = "3.333333333333333333",
            finalQuantity = "6",
            finalAverageUnitCost = "3.333333333333333333",
        )

        val posted = database.purchasePostingDao().postAtomically(batch)

        assertEquals(batch.purchase, posted)
        assertCommittedGraph(batch)
        val movement = requireNotNull(
            database.inventoryDao().findMovementById(batch.movements.single().movementId),
        )
        assertEquals("6", movement.quantityDelta)
        assertEquals("3.333333333333333333", movement.unitCost)
        val storedLine = requireNotNull(
            database.purchaseLineDao().findById(batch.lines.single().purchaseLineId),
        )
        assertEquals("10", storedLine.readUnitCost)
        assertEquals("3.333333333333333333", storedLine.appliedUnitCost)
        assertEquals("20", storedLine.appliedCostTotal)
        assertEquals("3", storedLine.purchaseUnitFactor)
        assertEquals("6", storedLine.inventoryQuantity)
        val stored = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals("6", stored.quantityOnHand)
        assertEquals("3.333333333333333333", stored.averageUnitCost)
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun negativeOpeningBalanceIsPreservedAndAverageRebasesToAppliedCost() = runBlocking {
        val balanceBefore = balance(version = 0L, quantity = "-5", updatedAt = 500L)
        assertTrue(database.inventoryDao().insertBalanceIfAbsent(balanceBefore) != -1L)
        val batch = postingBatch(
            offset = 402,
            expectedBalanceVersion = 0L,
            finalBalanceVersion = 1L,
            finalQuantity = "-3",
            finalAverageUnitCost = "5.000000000000000000",
        )

        database.purchasePostingDao().postAtomically(batch)

        val stored = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals("-3", stored.quantityOnHand)
        assertEquals(0, stored.averageUnitCost.toBigDecimal().compareTo(BigDecimal("5")))
        assertTrue(
            requireNotNull(batch.lines.single().costingWarnings)
                .contains("PREVIOUS_NON_POSITIVE_BALANCE_REBASED"),
        )
        assertPostingTableCounts(expected = 1)
    }

    @Test
    fun includedTaxWithGrossPolicyDoesNotAddIgvTwice() = runBlocking {
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            taxTreatment = InventoryTaxTreatment.INCLUDED,
        )
        val preparedHash = requireNotNull(
            database.preparedPurchaseDao().find(DRAFT_ID),
        ).logicalHash
        val original = postingBatch(offset = 403)
        val includedGross = original.copy(
            expectedPreparedLogicalHash = preparedHash,
            lines = original.lines.map { line ->
                line.copy(
                    taxTreatment = InventoryTaxTreatment.INCLUDED.name,
                    costPolicy = CostPolicy.GROSS.name,
                    appliedCostTotal = "10.00",
                    appliedUnitCost = "5.000000000000000000",
                )
            },
            movements = original.movements.map { movement ->
                movement.copy(unitCost = "5.000000000000000000")
            },
            balanceMutations = original.balanceMutations.map { mutation ->
                mutation.copy(
                    balance = mutation.balance.copy(
                        averageUnitCost = "5.000000000000000000",
                    ),
                )
            },
        )

        database.purchasePostingDao().postAtomically(includedGross)

        val storedLine = requireNotNull(
            database.purchaseLineDao().findById(includedGross.lines.single().purchaseLineId),
        )
        assertEquals("5.00", storedLine.readUnitCost)
        assertEquals("10.00", storedLine.appliedCostTotal)
        assertEquals("5.000000000000000000", storedLine.appliedUnitCost)
    }

    @Test
    fun explicitRateKeepsReadTaxSeparateFromExactSubCentAppliedTax() = runBlocking {
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            lineQuantity = "1",
            lineUnitCost = "0.03",
            taxEvidence = InventoryTaxEvidence.ExplicitRate(BigDecimal("18")),
        )
        val preparedHash = requireNotNull(
            database.preparedPurchaseDao().find(DRAFT_ID),
        ).logicalHash
        val original = postingBatch(
            offset = 406,
            lineQuantity = "1",
            lineUnitCost = "0.03",
            movementQuantity = "1",
            movementUnitCost = "0.030000",
            finalQuantity = "1",
            finalAverageUnitCost = "0.030000",
        )
        val rate = original.copy(
            expectedPreparedLogicalHash = preparedHash,
            lines = original.lines.map { line ->
                line.copy(
                    appliedUnitCost = "0.030000",
                    appliedCostTotal = "0.03",
                    taxEvidenceType = "EXPLICIT_RATE",
                    taxEvidenceValue = "18",
                    roundingScale = 6,
                    costingWarnings = "PREVIOUS_NON_POSITIVE_BALANCE_REBASED",
                )
            },
            movements = original.movements.map { it.copy(unitCost = "0.030000") },
            balanceMutations = original.balanceMutations.map { mutation ->
                mutation.copy(balance = mutation.balance.copy(averageUnitCost = "0.030000"))
            },
        )

        database.purchasePostingDao().postAtomically(rate)

        val storedLine = requireNotNull(
            database.purchaseLineDao().findById(rate.lines.single().purchaseLineId),
        )
        // La lectura congelada (1.80) se conserva aunque la tasa explícita aplicada derive otro
        // impuesto; no se obliga a que un Money en céntimos represente el resultado sub-centavo.
        assertEquals(180L, storedLine.taxMinorUnits)
        assertEquals("EXPLICIT_RATE", storedLine.taxEvidenceType)
        assertEquals("18", storedLine.taxEvidenceValue)
        assertEquals("0.03", storedLine.appliedCostTotal)
    }

    @Test
    fun unknownTaxTreatmentRollsBackWithoutInferringDecision() = runBlocking {
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            taxTreatment = InventoryTaxTreatment.UNKNOWN,
            taxEvidence = InventoryTaxEvidence.None,
        )
        val preparedHash = requireNotNull(
            database.preparedPurchaseDao().find(DRAFT_ID),
        ).logicalHash
        val original = postingBatch(offset = 404)
        val unknown = original.copy(
            expectedPreparedLogicalHash = preparedHash,
            lines = original.lines.map { line ->
                line.copy(
                    taxTreatment = InventoryTaxTreatment.UNKNOWN.name,
                    taxEvidenceType = "NONE",
                    taxEvidenceValue = null,
                )
            },
        )

        assertPostingFails(IllegalArgumentException::class.java, unknown)

        assertNoPostingArtifacts(unknown, expectedBalanceCount = 0)
    }

    @Test
    fun aggregateAverageUsesExactTotalsAcrossLinesAndDoesNotAccumulateRounding() = runBlocking {
        val opening = balance(version = 0L, quantity = "1", updatedAt = 500L).copy(
            averageUnitCost = "0",
        )
        assertTrue(database.inventoryDao().insertBalanceIfAbsent(opening) != -1L)
        val original = postingBatch(
            offset = 405,
            lineQuantity = "2",
            lineUnitCost = "0.50",
            movementQuantity = "2",
            movementUnitCost = "0.50",
            expectedBalanceVersion = 0L,
            finalBalanceVersion = 1L,
            finalQuantity = "4",
            finalAverageUnitCost = "0.26",
        )
        val secondLineId = uuid(1_405)
        val secondLine = original.lines.single().copy(
            purchaseLineId = secondLineId,
            position = 1,
            rawText = "1 PRODUCTO 0.02",
            quantity = "1",
            readUnitCost = "0.02",
            taxMinorUnits = 0,
            totalMinorUnits = 2,
            appliedUnitCost = "0.02",
            inventoryQuantity = "1",
            appliedCostTotal = "0.02",
            taxEvidenceValue = "0",
            roundingScale = 2,
            costingWarnings = "CALCULATION_ROUNDED",
        )
        val firstLine = original.lines.single().copy(
            roundingScale = 2,
            costingWarnings = "CALCULATION_ROUNDED",
        )
        val firstMovement = original.movements.single().copy(unitCost = "0.50")
        val secondMovement = firstMovement.copy(
            movementId = uuid(1_406),
            purchaseLineId = secondLineId,
            quantityDelta = "1",
            unitCost = "0.02",
            idempotencyKey = "movement-post-405-second",
        )
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            lineQuantity = "2",
            lineUnitCost = "0.50",
            extraLine = PreparedPurchaseLine(
                lineId = requireNotNull(LineId.parse(uuid(1_407))),
                position = 1,
                productId = requireNotNull(ProductId.parse(PRODUCT_ID)),
                unitId = requireNotNull(UnitId.parse(UNIT_ID)),
                description = "Producto de prueba",
                rawText = "1 PRODUCTO 0.02",
                quantity = Quantity.of("1"),
                unitCost = UnitCost.of("0.02", PEN),
                discount = Money.zero(PEN),
                tax = Money.zero(PEN),
                lineTotal = Money.fromMajor("0.02", PEN),
                linkConfidence = 950,
                taxTreatment = InventoryTaxTreatment.EXCLUDED,
                taxEvidence = InventoryTaxEvidence.ExplicitAmount(BigDecimal.ZERO),
                productProvenance = PurchaseProductProvenance.EXISTING,
            ),
        )
        val preparedHash = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID)).logicalHash
        val batch = original.copy(
            expectedPreparedLogicalHash = preparedHash,
            lines = listOf(firstLine, secondLine),
            movements = listOf(firstMovement, secondMovement),
        )

        database.purchasePostingDao().postAtomically(batch)

        val stored = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals("4", stored.quantityOnHand)
        assertEquals("0.26", stored.averageUnitCost)
        val summaries = purchaseReadRepository().observePurchases(
            requireNotNull(BusinessId.parse(BUSINESS_ID)),
        ).first()
        assertEquals(1, summaries.size)
        assertEquals(2, summaries.single().lineCount)
        assertEquals(1, summaries.single().productCount)
        val readDetail = requireNotNull(
            purchaseReadRepository().observePurchase(
                requireNotNull(BusinessId.parse(BUSINESS_ID)),
                requireNotNull(PurchaseId.parse(batch.purchase.purchaseId)),
            ).first(),
        )
        assertEquals(2, readDetail.lines.size)
    }

    @Test
    fun staleBalanceVersionRollsBackEverythingAndKeepsPreparedDraftReady() = runBlocking {
        val balanceBefore = balance(version = 5L, quantity = "10", updatedAt = 500L)
        assertTrue(database.inventoryDao().insertBalanceIfAbsent(balanceBefore) != -1L)
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val staleBatch = postingBatch(
            expectedBalanceVersion = 4L,
            finalBalanceVersion = 5L,
            finalQuantity = "12",
        )

        assertPostingFails(IllegalArgumentException::class.java, staleBatch)

        assertNoPostingArtifacts(staleBatch, expectedBalanceCount = 1)
        assertEquals(
            balanceBefore,
            database.inventoryDao().findBalance(BUSINESS_ID, PRODUCT_ID, LOCATION_ID),
        )
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(DraftStatus.READY_TO_POST.name, draftBefore.status)
        assertNull(draftBefore.confirmedPurchaseId)
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
    }

    @Test
    fun incorrectPreparedHashRejectsPostingWithoutWritingAnything() = runBlocking {
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val batch = postingBatch().copy(
            expectedPreparedLogicalHash = "f".repeat(64),
        )

        assertPostingFails(IllegalArgumentException::class.java, batch)

        assertNoPostingArtifacts(batch, expectedBalanceCount = 0)
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
    }

    @Test
    fun rawTextDifferentFromFrozenReviewRejectsPostingWithoutArtifacts() = runBlocking {
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val original = postingBatch(offset = 302)
        val batch = original.copy(
            lines = original.lines.map { line -> line.copy(rawText = "TEXTO INVENTADO") },
        )

        assertPostingFails(IllegalArgumentException::class.java, batch)

        assertNoPostingArtifacts(batch, expectedBalanceCount = 0)
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
    }

    @Test
    fun arbitraryBalanceQuantityAndAverageCostEachRollbackCompleteGraph() = runBlocking {
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        val arbitraryQuantity = postingBatch(
            offset = 300,
            finalQuantity = "3",
        )

        assertPostingFails(IllegalArgumentException::class.java, arbitraryQuantity)
        assertNoPostingArtifacts(arbitraryQuantity, expectedBalanceCount = 0)
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))

        val arbitraryAverageCost = postingBatch(
            offset = 301,
            finalAverageUnitCost = "6.00",
        )

        assertPostingFails(IllegalArgumentException::class.java, arbitraryAverageCost)
        assertNoPostingArtifacts(arbitraryAverageCost, expectedBalanceCount = 0)
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
    }

    @Test
    fun duplicateDraftPurchaseIdempotencyAndMovementRollbackWithoutChangingPriorGraph() =
        runBlocking {
            val committed = postingBatch()
            database.purchasePostingDao().postAtomically(committed)
            seedPreparedDraft(SECOND_DRAFT_ID, documentNumber = "43")
            val secondPrepared = requireNotNull(
                database.preparedPurchaseDao().find(SECOND_DRAFT_ID),
            )
            val secondDraft = requireNotNull(database.invoiceDraftDao().findById(SECOND_DRAFT_ID))

            val duplicateSource = postingBatch(offset = 100, draftId = DRAFT_ID)
            assertPostingFails(IllegalArgumentException::class.java, duplicateSource)
            assertPriorGraphUnchanged(committed, secondDraft, secondPrepared)

            val idempotencyCandidate = postingBatch(
                offset = 101,
                draftId = SECOND_DRAFT_ID,
                documentNumber = "43",
            )
            val duplicatePurchaseIdempotency = idempotencyCandidate.copy(
                purchase = idempotencyCandidate.purchase.copy(
                    idempotencyKey = committed.purchase.idempotencyKey,
                ),
            )
            assertPostingFails(
                SQLiteConstraintException::class.java,
                duplicatePurchaseIdempotency,
            )
            assertPriorGraphUnchanged(committed, secondDraft, secondPrepared)

            val movementCandidate = postingBatch(
                offset = 102,
                draftId = SECOND_DRAFT_ID,
                documentNumber = "43",
                expectedBalanceVersion = 0L,
                finalBalanceVersion = 1L,
                finalQuantity = "4",
            )
            val duplicateMovement = movementCandidate.copy(
                movements = movementCandidate.movements.map {
                    it.copy(idempotencyKey = committed.movements.single().idempotencyKey)
                },
            )
            assertPostingFails(SQLiteConstraintException::class.java, duplicateMovement)
            assertPriorGraphUnchanged(committed, secondDraft, secondPrepared)
        }

    @Test
    fun duplicateOutboxKeyRollsBackLatePostingAndKeepsPriorPendingOperation() = runBlocking {
        val committed = postingBatch(offset = 700)
        database.purchasePostingDao().postAtomically(committed)
        val pendingBefore = requireNotNull(
            database.outboxOperationDao().findById(committed.outboxOperations.single().operationId),
        )
        assertEquals(OutboxOperationStatus.PENDING.name, pendingBefore.status)
        seedPreparedDraft(SECOND_DRAFT_ID, documentNumber = "43")
        val secondPrepared = requireNotNull(database.preparedPurchaseDao().find(SECOND_DRAFT_ID))
        val secondDraft = requireNotNull(database.invoiceDraftDao().findById(SECOND_DRAFT_ID))
        val retryableBatch = postingBatch(
            offset = 701,
            draftId = SECOND_DRAFT_ID,
            documentNumber = "43",
            expectedBalanceVersion = 0L,
            finalBalanceVersion = 1L,
            finalQuantity = "4",
        )
        val duplicateOutboxKey = retryableBatch.copy(
            outboxOperations = retryableBatch.outboxOperations.map { operation ->
                operation.copy(idempotencyKey = pendingBefore.idempotencyKey)
            },
        )

        assertPostingFails(SQLiteConstraintException::class.java, duplicateOutboxKey)

        // La colisión ocurre tras insertar compra, líneas, saldo, movimiento y auditoría. La
        // transacción debe revertirlos todos sin consumir ni alterar la operación previa.
        assertPriorGraphUnchanged(committed, secondDraft, secondPrepared)
        assertEquals(
            pendingBefore,
            database.outboxOperationDao().findById(pendingBefore.operationId),
        )
        assertNull(
            database.outboxOperationDao().findById(retryableBatch.outboxOperations.single().operationId),
        )

        database.purchasePostingDao().postAtomically(retryableBatch)

        assertCommittedGraph(retryableBatch)
        assertEquals(
            pendingBefore,
            database.outboxOperationDao().findById(pendingBefore.operationId),
        )
        assertEquals(
            OutboxOperationStatus.PENDING.name,
            database.outboxOperationDao()
                .findById(retryableBatch.outboxOperations.single().operationId)
                ?.status,
        )
        assertPostingTableCounts(
            purchases = 2,
            lines = 2,
            balances = 1,
            movements = 2,
            audits = 2,
            outbox = 2,
        )
    }

    @Test
    fun catalogFromAnotherBusinessIsRejectedAndRollsBack() = runBlocking {
        val crossBusinessBatch = postingBatch(
            offset = 200,
            productId = OTHER_PRODUCT_ID,
            unitId = OTHER_UNIT_ID,
            locationId = OTHER_LOCATION_ID,
        )
        storePreparedSnapshot(
            draftId = DRAFT_ID,
            documentNumber = "42",
            productId = OTHER_PRODUCT_ID,
            unitId = OTHER_UNIT_ID,
        )
        val preparedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID))
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))

        assertPostingFails(IllegalArgumentException::class.java, crossBusinessBatch)

        assertNoPostingArtifacts(crossBusinessBatch, expectedBalanceCount = 0)
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(preparedBefore, database.preparedPurchaseDao().find(DRAFT_ID))
        assertNotNull(database.productDao().findById(OTHER_PRODUCT_ID))
        assertNotNull(database.unitDao().findById(OTHER_UNIT_ID))
        assertNotNull(database.inventoryLocationDao().findById(OTHER_LOCATION_ID))
    }

    private suspend fun seedCatalog(
        businessId: String,
        supplierId: String,
        unitId: String,
        locationId: String,
        productId: String,
        label: String,
        supplierRuc: String,
    ) {
        database.businessDao().insert(
            BusinessEntity(
                businessId = businessId,
                legalName = "Negocio $label",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = supplierId,
                businessId = businessId,
                legalName = "Proveedor $label",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
                ruc = supplierRuc,
            ),
        )
        database.unitDao().insert(
            UnitEntity(
                unitId = unitId,
                businessId = businessId,
                code = "NIU",
                name = "Unidad $label",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = locationId,
                businessId = businessId,
                name = "Almacén $label",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
            ),
        )
        database.productDao().insert(
            ProductEntity(
                productId = productId,
                businessId = businessId,
                unitId = unitId,
                name = "Producto $label",
                createdAt = CATALOG_TIME,
                updatedAt = CATALOG_TIME,
                locationId = locationId,
                sku = "SKU-${label.uppercase(Locale.ROOT)}",
            ),
        )
    }

    private suspend fun seedPreparedDraft(
        draftId: String,
        documentNumber: String = "42",
        productId: String = PRODUCT_ID,
        unitId: String = UNIT_ID,
        documentType: PurchaseDocumentType = PurchaseDocumentType.INVOICE,
        lineQuantity: String = "2",
        lineUnitCost: String = "5.00",
        supplierId: String? = SUPPLIER_ID,
        supplierRuc: String = SUPPLIER_RUC,
        supplierLegalName: String = "Proveedor principal",
        lineDescription: String = "Producto de prueba",
        taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.EXCLUDED,
        taxEvidence: InventoryTaxEvidence = InventoryTaxEvidence.ExplicitAmount(
            BigDecimal("1.80"),
        ),
        productProvenance: PurchaseProductProvenance = PurchaseProductProvenance.EXISTING,
        stagedProduct: StagedPurchaseProduct? = null,
    ) {
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = draftId,
                businessId = BUSINESS_ID,
                createdAt = DRAFT_CREATED_AT,
                updatedAt = DRAFT_UPDATED_AT,
                status = DraftStatus.READY_TO_POST.name,
                supplierId = supplierId,
                supplierRucNormalized = supplierRuc,
                supplierLegalNameNormalized = supplierLegalName.lowercase(Locale.ROOT),
                documentType = documentType.name,
                documentNumberNormalized = "F001-$documentNumber",
                issueDateNormalized = "2026-08-12",
                currencyCode = CURRENCY,
                subtotalMinorUnits = 1_000L,
                taxMinorUnits = 180L,
                otherChargesMinorUnits = 0L,
                totalMinorUnits = 1_180L,
                headerConfidence = 950,
            ),
        )
        storePreparedSnapshot(
            draftId = draftId,
            documentNumber = documentNumber,
            productId = productId,
            unitId = unitId,
            documentType = documentType,
            lineQuantity = lineQuantity,
            lineUnitCost = lineUnitCost,
            supplierId = supplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = supplierLegalName,
            lineDescription = lineDescription,
            taxTreatment = taxTreatment,
            taxEvidence = taxEvidence,
            productProvenance = productProvenance,
            stagedProduct = stagedProduct,
        )
    }

    private suspend fun storePreparedSnapshot(
        draftId: String,
        documentNumber: String,
        productId: String,
        unitId: String,
        documentType: PurchaseDocumentType = PurchaseDocumentType.INVOICE,
        lineQuantity: String = "2",
        lineUnitCost: String = "5.00",
        extraLine: PreparedPurchaseLine? = null,
        supplierId: String? = SUPPLIER_ID,
        supplierRuc: String = SUPPLIER_RUC,
        supplierLegalName: String = "Proveedor principal",
        lineDescription: String = "Producto de prueba",
        taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.EXCLUDED,
        taxEvidence: InventoryTaxEvidence = InventoryTaxEvidence.ExplicitAmount(
            BigDecimal("1.80"),
        ),
        productProvenance: PurchaseProductProvenance = PurchaseProductProvenance.EXISTING,
        stagedProduct: StagedPurchaseProduct? = null,
    ) {
        val prepared = preparedPurchase(
            draftId = draftId,
            documentNumber = documentNumber,
            productId = productId,
            unitId = unitId,
            documentType = documentType,
            lineQuantity = lineQuantity,
            lineUnitCost = lineUnitCost,
            extraLine = extraLine,
            supplierId = supplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = supplierLegalName,
            lineDescription = lineDescription,
            taxTreatment = taxTreatment,
            taxEvidence = taxEvidence,
            productProvenance = productProvenance,
            stagedProduct = stagedProduct,
        )
        val payload = PreparedPurchaseCodec.encode(prepared)
        database.preparedPurchaseDao().upsert(
            PreparedPurchaseEntity(
                draftId = draftId,
                logicalHash = prepared.logicalHash,
                payloadCodecVersion = PreparedPurchaseCodec.VERSION,
                payloadSha256 = PreparedPurchaseCodec.sha256(payload),
                payload = payload,
                preparedAt = prepared.preparedAt.toEpochMilli(),
            ),
        )
    }

    private fun preparedPurchase(
        draftId: String,
        documentNumber: String,
        productId: String,
        unitId: String,
        documentType: PurchaseDocumentType = PurchaseDocumentType.INVOICE,
        lineQuantity: String = "2",
        lineUnitCost: String = "5.00",
        extraLine: PreparedPurchaseLine? = null,
        supplierId: String? = SUPPLIER_ID,
        supplierRuc: String = SUPPLIER_RUC,
        supplierLegalName: String = "Proveedor principal",
        lineDescription: String = "Producto de prueba",
        taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.EXCLUDED,
        taxEvidence: InventoryTaxEvidence = InventoryTaxEvidence.ExplicitAmount(
            BigDecimal("1.80"),
        ),
        productProvenance: PurchaseProductProvenance = PurchaseProductProvenance.EXISTING,
        stagedProduct: StagedPurchaseProduct? = null,
    ): PreparedPurchase {
        val parsedDraftId = requireNotNull(DraftId.parse(draftId))
        val parsedBusinessId = requireNotNull(BusinessId.parse(BUSINESS_ID))
        val parsedSupplierId = supplierId?.let { requireNotNull(SupplierId.parse(it)) }
        val lines = buildList {
            add(
            PreparedPurchaseLine(
                lineId = requireNotNull(LineId.parse(PREPARED_LINE_ID)),
                position = 0,
                productId = requireNotNull(ProductId.parse(productId)),
                unitId = requireNotNull(UnitId.parse(unitId)),
                description = lineDescription,
                rawText = "$lineQuantity PRODUCTO $lineUnitCost",
                quantity = Quantity.of(lineQuantity),
                unitCost = UnitCost.of(lineUnitCost, PEN),
                tax = Money.ofMinor(180L, PEN),
                lineTotal = Money.ofMinor(1_180L, PEN),
                linkConfidence = 950,
                taxTreatment = taxTreatment,
                taxEvidence = taxEvidence,
                productProvenance = productProvenance,
                stagedProduct = stagedProduct,
            ),
            )
            extraLine?.let(::add)
        }
        val subtotal = Money.ofMinor(1_000L, PEN)
        val tax = Money.ofMinor(180L, PEN)
        val otherCharges = Money.ofMinor(0L, PEN)
        val total = Money.ofMinor(1_180L, PEN)
        val logicalHash = PreparedPurchase.logicalHash(
            draftId = parsedDraftId,
            businessId = parsedBusinessId,
            supplierId = parsedSupplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = supplierLegalName,
            documentType = documentType,
            documentNumber = "F001-$documentNumber",
            issueDate = ISSUE_DATE,
            currency = PEN,
            lines = lines,
            subtotal = subtotal,
            tax = tax,
            otherCharges = otherCharges,
            total = total,
            acceptedWarnings = emptyList(),
        )
        return PreparedPurchase(
            draftId = parsedDraftId,
            businessId = parsedBusinessId,
            supplierId = parsedSupplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = supplierLegalName,
            documentType = documentType,
            documentNumber = "F001-$documentNumber",
            issueDate = ISSUE_DATE,
            currency = PEN,
            lines = lines,
            subtotal = subtotal,
            tax = tax,
            otherCharges = otherCharges,
            total = total,
            acceptedWarnings = emptyList(),
            logicalHash = logicalHash,
            preparedAt = Instant.ofEpochMilli(PREPARED_AT),
        )
    }

    private fun postingBatch(
        offset: Int = 0,
        draftId: String = DRAFT_ID,
        documentNumber: String = "42",
        productId: String = PRODUCT_ID,
        unitId: String = UNIT_ID,
        locationId: String = LOCATION_ID,
        documentType: PurchaseDocumentType = PurchaseDocumentType.INVOICE,
        lineQuantity: String = "2",
        lineUnitCost: String = "5.00",
        movementQuantity: String = "2",
        movementUnitCost: String = "5.00",
        expectedBalanceVersion: Long? = null,
        finalBalanceVersion: Long = 0L,
        finalQuantity: String = "2",
        finalAverageUnitCost: String = "5.00",
    ): PurchasePostingBatch {
        val purchaseId = uuid(20 + offset)
        val lineId = uuid(21 + offset)
        val postedAt = POSTED_AT + offset
        val purchase = PurchaseEntity(
            purchaseId = purchaseId,
            businessId = BUSINESS_ID,
            sourceDraftId = draftId,
            supplierId = SUPPLIER_ID,
            documentType = documentType.name,
            documentSeries = "F001",
            documentNumber = documentNumber,
            issueDate = "2026-08-12",
            currencyCode = CURRENCY,
            subtotalMinorUnits = 1_000L,
            taxMinorUnits = 180L,
            otherChargesMinorUnits = 0L,
            totalMinorUnits = 1_180L,
            status = PurchaseStatus.POSTED.name,
            idempotencyKey = "purchase-post-$offset",
            createdAt = PURCHASE_CREATED_AT + offset,
            updatedAt = postedAt,
            postedAt = postedAt,
        )
        val movementQuantityDecimal = movementQuantity.toBigDecimal()
        val lineQuantityDecimal = lineQuantity.toBigDecimal()
        val factor = movementQuantityDecimal.abs().divide(lineQuantityDecimal)
        val inferredPreviousQuantity = finalQuantity.toBigDecimal().subtract(movementQuantityDecimal)
        val inferredPreviousAverage = when {
            expectedBalanceVersion == null -> BigDecimal.ZERO
            inferredPreviousQuantity.compareTo(BigDecimal.TEN) == 0 -> BigDecimal("4")
            else -> BigDecimal("5")
        }
        val costing = requireNotNull(
            (InventoryCostingService().calculate(
                InventoryCostingRequest(
                    currency = PEN,
                    purchaseQuantity = lineQuantityDecimal,
                    purchaseUnitFactor = factor,
                    readPurchaseUnitCost = lineUnitCost.toBigDecimal(),
                    lineDiscount = BigDecimal.ZERO,
                    taxTreatment = InventoryTaxTreatment.EXCLUDED,
                    taxEvidence = InventoryTaxEvidence.ExplicitAmount(BigDecimal("1.80")),
                    costPolicy = CostPolicy.NET,
                    previousQuantity = inferredPreviousQuantity,
                    previousAverageUnitCost = inferredPreviousAverage,
                    roundingPolicy = InventoryCostRoundingPolicy(18, RoundingMode.HALF_EVEN),
                ),
            ) as? InventoryCostingResult.Calculated)?.calculation,
        )
        val line = PurchaseLineEntity(
            purchaseLineId = lineId,
            purchaseId = purchaseId,
            productId = productId,
            unitId = unitId,
            productNameSnapshot = "Producto principal",
            unitCodeSnapshot = "NIU",
            position = 0,
            rawText = "$lineQuantity PRODUCTO $lineUnitCost",
            description = "Producto de prueba",
            quantity = lineQuantity,
            readUnitCost = lineUnitCost,
            currencyCode = CURRENCY,
            taxMinorUnits = 180L,
            totalMinorUnits = 1_180L,
            confidence = 950,
            productProvenance = PurchaseProductProvenance.EXISTING.name,
            appliedUnitCost = costing.appliedInventoryUnitCost.toPlainString(),
            purchaseUnitFactor = factor.toPlainString(),
            inventoryQuantity = costing.inventoryQuantity.toPlainString(),
            discount = "0",
            taxTreatment = InventoryTaxTreatment.EXCLUDED.name,
            costPolicy = CostPolicy.NET.name,
            appliedCostTotal = costing.appliedCostTotal.toPlainString(),
            taxEvidenceType = "EXPLICIT_AMOUNT",
            taxEvidenceValue = "1.80",
            roundingScale = 18,
            roundingMode = "HALF_EVEN",
            costingWarnings = costing.warnings.map { it.name }.sorted().joinToString(","),
        )
        val balance = InventoryBalanceEntity(
            businessId = BUSINESS_ID,
            productId = productId,
            locationId = locationId,
            quantityOnHand = finalQuantity,
            averageUnitCost = finalAverageUnitCost,
            currencyCode = CURRENCY,
            version = finalBalanceVersion,
            updatedAt = postedAt,
        )
        val movement = StockMovementEntity(
            movementId = uuid(22 + offset),
            businessId = BUSINESS_ID,
            purchaseId = purchaseId,
            purchaseLineId = lineId,
            productId = productId,
            locationId = locationId,
            type = StockMovementType.PURCHASE.name,
            quantityDelta = movementQuantity,
            unitCost = movementUnitCost,
            currencyCode = CURRENCY,
            idempotencyKey = "movement-post-$offset",
            occurredAt = postedAt,
            createdAt = postedAt,
        )
        val audit = AuditEventEntity(
            auditEventId = uuid(23 + offset),
            businessId = BUSINESS_ID,
            purchaseId = purchaseId,
            eventType = AuditEventType.PURCHASE_POSTED.name,
            entityType = "PURCHASE",
            entityId = purchaseId,
            payload = "{\"purchaseId\":\"$purchaseId\"}",
            occurredAt = postedAt,
        )
        val outbox = OutboxOperationEntity(
            operationId = uuid(24 + offset),
            businessId = BUSINESS_ID,
            purchaseId = purchaseId,
            idempotencyKey = "outbox-post-$offset",
            operationType = "SYNC_PURCHASE",
            payload = "{\"purchaseId\":\"$purchaseId\"}",
            status = OutboxOperationStatus.PENDING.name,
            createdAt = postedAt,
            updatedAt = postedAt,
        )
        return PurchasePostingBatch(
            purchase = purchase,
            expectedPreparedLogicalHash = preparedPurchase(
                draftId = draftId,
                documentNumber = documentNumber,
                productId = productId,
                unitId = unitId,
                documentType = documentType,
                lineQuantity = lineQuantity,
                lineUnitCost = lineUnitCost,
            ).logicalHash,
            lines = listOf(line),
            balanceMutations = listOf(
                InventoryBalanceMutation(
                    expectedVersion = expectedBalanceVersion,
                    balance = balance,
                ),
            ),
            movements = listOf(movement),
            auditEvents = listOf(audit),
            outboxOperations = listOf(outbox),
        )
    }

    private fun balance(
        version: Long,
        quantity: String,
        updatedAt: Long,
    ): InventoryBalanceEntity = InventoryBalanceEntity(
        businessId = BUSINESS_ID,
        productId = PRODUCT_ID,
        locationId = LOCATION_ID,
        quantityOnHand = quantity,
        averageUnitCost = "4.00",
        currencyCode = CURRENCY,
        version = version,
        updatedAt = updatedAt,
    )

    private suspend fun assertCommittedGraph(batch: PurchasePostingBatch) {
        assertEquals(
            batch.purchase,
            database.purchaseDao().findById(batch.purchase.purchaseId),
        )
        assertEquals(
            batch.lines,
            database.purchaseLineDao().listForPurchase(batch.purchase.purchaseId),
        )
        val balance = batch.balanceMutations.single().balance
        assertEquals(
            balance,
            database.inventoryDao().findBalance(
                balance.businessId,
                balance.productId,
                balance.locationId,
            ),
        )
        assertEquals(
            batch.movements,
            database.inventoryDao().listMovementsForPurchase(
                batch.purchase.businessId,
                batch.purchase.purchaseId,
            ),
        )
        assertEquals(
            batch.auditEvents,
            database.auditEventDao().listForPurchase(
                batch.purchase.businessId,
                batch.purchase.purchaseId,
            ),
        )
        assertEquals(
            batch.outboxOperations.single(),
            database.outboxOperationDao().findById(batch.outboxOperations.single().operationId),
        )
    }

    private suspend fun assertNoPostingArtifacts(
        batch: PurchasePostingBatch,
        expectedBalanceCount: Int,
    ) {
        assertNull(database.purchaseDao().findById(batch.purchase.purchaseId))
        assertTrue(database.purchaseLineDao().listForPurchase(batch.purchase.purchaseId).isEmpty())
        assertTrue(
            database.inventoryDao().listMovementsForPurchase(
                batch.purchase.businessId,
                batch.purchase.purchaseId,
            ).isEmpty(),
        )
        assertTrue(
            database.auditEventDao().listForPurchase(
                batch.purchase.businessId,
                batch.purchase.purchaseId,
            ).isEmpty(),
        )
        batch.outboxOperations.forEach { operation ->
            assertNull(database.outboxOperationDao().findById(operation.operationId))
        }
        assertPostingTableCounts(
            purchases = 0,
            lines = 0,
            balances = expectedBalanceCount,
            movements = 0,
            audits = 0,
            outbox = 0,
        )
    }

    private suspend fun assertPriorGraphUnchanged(
        committed: PurchasePostingBatch,
        secondDraftBefore: InvoiceDraftEntity,
        secondPreparedBefore: PreparedPurchaseEntity,
    ) {
        assertCommittedGraph(committed)
        assertPostingTableCounts(expected = 1)
        assertEquals(secondDraftBefore, database.invoiceDraftDao().findById(SECOND_DRAFT_ID))
        assertEquals(
            secondPreparedBefore,
            database.preparedPurchaseDao().find(SECOND_DRAFT_ID),
        )
        val firstDraft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID))
        assertEquals(committed.purchase.purchaseId, firstDraft.confirmedPurchaseId)
    }

    private fun productionRepository(): RoomPurchasePostingRepository =
        RoomPurchasePostingRepository(
            database = database,
            appClock = AppClock { Instant.ofEpochMilli(POSTED_AT) },
            dispatchers = DefaultDispatcherProvider(),
        )

    private fun purchaseReadRepository(): RoomPurchaseReadRepository =
        RoomPurchaseReadRepository(
            database = database,
            dispatchers = DefaultDispatcherProvider(),
        )

    private fun purchaseVoidRepository(now: Long): RoomPurchaseVoidRepository =
        RoomPurchaseVoidRepository(
            database = database,
            appClock = AppClock { Instant.ofEpochMilli(now) },
            dispatchers = DefaultDispatcherProvider(),
        )

    private fun inventoryReadRepository(): RoomInventoryReadRepository =
        RoomInventoryReadRepository(
            database = database,
            appClock = AppClock { Instant.ofEpochMilli(POSTED_AT + 10_000L) },
            dispatchers = DefaultDispatcherProvider(),
        )

    private fun confirmationContext(): PurchaseConfirmationContext = PurchaseConfirmationContext(
        activeBusinessId = requireNotNull(BusinessId.parse(BUSINESS_ID)),
        costPolicy = CostPolicy.NET,
    )

    private suspend fun confirmationCommand(
        draftId: String,
        duplicateOverride: PurchaseDuplicateOverride? = null,
    ): ConfirmPurchaseCommand = ConfirmPurchaseCommand(
        draftId = requireNotNull(DraftId.parse(draftId)),
        expectedPreparedLogicalHash = requireNotNull(
            database.preparedPurchaseDao().find(draftId),
        ).logicalHash,
        duplicateOverride = duplicateOverride,
    )

    private suspend fun assertPostingFails(
        expected: Class<out Throwable>,
        batch: PurchasePostingBatch,
    ) {
        val failure = try {
            database.purchasePostingDao().postAtomically(batch)
            null
        } catch (throwable: Throwable) {
            throwable
        }
        assertNotNull("Expected ${expected.simpleName}", failure)
        assertTrue(
            "Expected ${expected.simpleName}, got ${failure?.javaClass?.name}: ${failure?.message}",
            expected.isInstance(failure),
        )
    }

    private fun assertSqlConstraint(block: () -> Unit) {
        val failure = try {
            block()
            null
        } catch (throwable: Throwable) {
            throwable
        }
        assertNotNull("Expected SQLiteConstraintException", failure)
        assertTrue(
            "Expected SQLiteConstraintException, got ${failure?.javaClass?.name}: ${failure?.message}",
            failure is SQLiteConstraintException,
        )
    }

    private fun assertPostingTableCounts(expected: Int) {
        assertPostingTableCounts(
            purchases = expected,
            lines = expected,
            balances = expected,
            movements = expected,
            audits = expected,
            outbox = expected,
        )
    }

    private fun assertPostingTableCounts(
        purchases: Int,
        lines: Int,
        balances: Int,
        movements: Int,
        audits: Int,
        outbox: Int,
    ) {
        assertEquals(purchases, rowCount("purchases"))
        assertEquals(lines, rowCount("purchase_lines"))
        assertEquals(balances, rowCount("inventory_balances"))
        assertEquals(movements, rowCount("stock_movements"))
        assertEquals(audits, rowCount("audit_events"))
        assertEquals(outbox, rowCount("outbox_operations"))
    }

    private fun rowCount(table: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM `$table`")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun uuid(seed: Int): String = "00000000-0000-0000-0000-%012d".format(seed)

    private companion object {
        const val POSTED_ADJUSTMENT_REASON_SENTINEL =
            "PRIVATE_ADJUSTMENT_user@example.pe_RUC_20123456789"
        const val BUSINESS_ID = "00000000-0000-0000-0000-000000000001"
        const val SUPPLIER_ID = "00000000-0000-0000-0000-000000000002"
        const val UNIT_ID = "00000000-0000-0000-0000-000000000003"
        const val LOCATION_ID = "00000000-0000-0000-0000-000000000004"
        const val PRODUCT_ID = "00000000-0000-0000-0000-000000000005"
        const val DRAFT_ID = "00000000-0000-0000-0000-000000000006"
        const val SECOND_DRAFT_ID = "00000000-0000-0000-0000-000000000007"
        const val PREPARED_LINE_ID = "00000000-0000-0000-0000-000000000008"
        const val PURCHASE_UNIT_ID = "00000000-0000-0000-0000-000000000009"
        const val THIRD_DRAFT_ID = "00000000-0000-0000-0000-000000000010"
        const val REASSIGNED_SUPPLIER_ID = "00000000-0000-0000-0000-000000000011"
        const val SUPPLIER_RUC = "20123456789"
        const val UPDATED_SUPPLIER_RUC = "20123456780"
        const val RELEASED_SUPPLIER_RUC = "20987654320"

        const val OTHER_BUSINESS_ID = "00000000-0000-0000-0000-000000000101"
        const val OTHER_SUPPLIER_ID = "00000000-0000-0000-0000-000000000102"
        const val OTHER_UNIT_ID = "00000000-0000-0000-0000-000000000103"
        const val OTHER_LOCATION_ID = "00000000-0000-0000-0000-000000000104"
        const val OTHER_PRODUCT_ID = "00000000-0000-0000-0000-000000000105"
        const val OTHER_SUPPLIER_RUC = "20987654321"

        const val CURRENCY = "PEN"
        const val CATALOG_TIME = 1L
        const val DRAFT_CREATED_AT = 10L
        const val DRAFT_UPDATED_AT = 20L
        const val PREPARED_AT = 30L
        const val PURCHASE_CREATED_AT = 1_000L
        const val POSTED_AT = 2_000L
        val PEN: CurrencyCode = CurrencyCode.of(CURRENCY)
        val ISSUE_DATE: LocalDate = LocalDate.of(2026, 8, 12)
    }
}
