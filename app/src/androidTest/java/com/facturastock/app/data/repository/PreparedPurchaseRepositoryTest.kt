package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceHeaderEditCodec
import com.facturastock.app.data.local.codec.InvoiceLinesEditCodec
import com.facturastock.app.data.local.dao.updateCas
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceHeaderEditEntity
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.PublishPreparedPurchaseResult
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparedPurchaseRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock
    private lateinit var repository: RoomPreparedPurchaseRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java).build()
        clock = TestClock(PUBLISH_TIME)
        repository = RoomPreparedPurchaseRepository(
            database = database,
            preparedPurchaseDao = database.preparedPurchaseDao(),
            dispatchers = testDispatchers,
            clock = clock,
        )
        database.businessDao().insert(
            BusinessEntity(
                businessId = BUSINESS_ID.value,
                legalName = "Negocio de prueba",
                createdAt = BASE_TIME.toEpochMilli(),
                updatedAt = BASE_TIME.toEpochMilli(),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun publishCommitsSnapshotAndReadyStatusTogether() = runBlocking {
        seedReviewingDraft()
        val purchase = purchase()

        val result = publish(purchase)

        assertEquals(PublishPreparedPurchaseResult.PREPARED, result)
        assertEquals(purchase, repository.find(DRAFT_ID))
        val draft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        assertEquals(DraftStatus.READY_TO_POST.name, draft.status)
        assertEquals(PUBLISH_TIME.toEpochMilli(), draft.updatedAt)
    }

    @Test
    fun exactRetryIsAlreadyPreparedAndDoesNotRewriteSnapshotOrDraft() = runBlocking {
        seedReviewingDraft()
        val first = purchase(preparedAt = PUBLISH_TIME)
        assertEquals(PublishPreparedPurchaseResult.PREPARED, publish(first))
        val storedBefore = requireNotNull(database.preparedPurchaseDao().find(DRAFT_ID.value))
        val draftBefore = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        clock.advanceSeconds(90)
        val retry = purchase(preparedAt = PUBLISH_TIME.plusSeconds(90))
        assertEquals(first.logicalHash, retry.logicalHash)

        val result = publish(retry)

        assertEquals(PublishPreparedPurchaseResult.ALREADY_PREPARED, result)
        assertEquals(first, repository.find(DRAFT_ID))
        assertEquals(storedBefore, database.preparedPurchaseDao().find(DRAFT_ID.value))
        assertEquals(draftBefore, database.invoiceDraftDao().findById(DRAFT_ID.value))
    }

    @Test
    fun concurrentExactPublishHasOneWriterAndOneIdempotentReader() = runBlocking {
        seedReviewingDraft()
        val purchase = purchase()

        val results = coroutineScope {
            List(2) {
                async(Dispatchers.Default) { publish(purchase) }
            }.awaitAll()
        }

        assertEquals(1, results.count { it == PublishPreparedPurchaseResult.PREPARED })
        assertEquals(1, results.count { it == PublishPreparedPurchaseResult.ALREADY_PREPARED })
        assertEquals(purchase, repository.find(DRAFT_ID))
        assertEquals(
            DraftStatus.READY_TO_POST.name,
            database.invoiceDraftDao().findById(DRAFT_ID.value)?.status,
        )
    }

    @Test
    fun conflictingPayloadCannotOverwritePreparedSnapshot() = runBlocking {
        seedReviewingDraft()
        val first = purchase()
        assertEquals(PublishPreparedPurchaseResult.PREPARED, publish(first))
        val conflicting = purchase(totalMinor = 1_200, preparedAt = PUBLISH_TIME.plusSeconds(30))
        assertTrue(first.logicalHash != conflicting.logicalHash)

        val result = publish(conflicting)

        assertEquals(PublishPreparedPurchaseResult.CONFLICT, result)
        assertEquals(first, repository.find(DRAFT_ID))
        assertEquals(
            PUBLISH_TIME.toEpochMilli(),
            database.invoiceDraftDao().findById(DRAFT_ID.value)?.updatedAt,
        )
    }

    @Test
    fun nonReviewingDraftRejectsPublishWithoutLeavingSnapshot() = runBlocking {
        seedDraft(status = DraftStatus.CAPTURED)

        val result = publish(purchase())

        assertEquals(PublishPreparedPurchaseResult.CONFLICT, result)
        assertNull(repository.find(DRAFT_ID))
        assertEquals(
            DraftStatus.CAPTURED.name,
            database.invoiceDraftDao().findById(DRAFT_ID.value)?.status,
        )
    }

    @Test
    fun changedReviewRevisionRejectsPublishWithoutReadyOrSnapshot() = runBlocking {
        seedReviewingDraft()
        assertEquals(
            1,
            database.invoiceHeaderEditDao().update(headerEditEntity(HEADER_REVISION + 1)),
        )

        val result = publish(purchase())

        assertEquals(PublishPreparedPurchaseResult.CONFLICT, result)
        assertNull(repository.find(DRAFT_ID))
        val draft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        assertEquals(DraftStatus.NEEDS_REVIEW.name, draft.status)
        assertEquals(BASE_TIME.toEpochMilli(), draft.updatedAt)
    }

    @Test
    fun changedDraftRejectsPublishWithoutReadyOrSnapshot() = runBlocking {
        seedReviewingDraft()
        val current = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        database.invoiceDraftDao().update(current.copy(updatedAt = BASE_TIME.plusSeconds(1).toEpochMilli()))

        val result = publish(purchase())

        assertEquals(PublishPreparedPurchaseResult.CONFLICT, result)
        assertNull(repository.find(DRAFT_ID))
        assertEquals(
            DraftStatus.NEEDS_REVIEW.name,
            database.invoiceDraftDao().findById(DRAFT_ID.value)?.status,
        )
    }

    @Test
    fun archivedProductRejectsPublishWithoutReadyOrSnapshot() = runBlocking {
        seedReviewingDraft()
        val product = requireNotNull(database.productDao().findById(PRODUCT_ID.value))
        database.productDao().updateCas(product.copy(status = CatalogStatus.ARCHIVED.name))

        val result = publish(purchase())

        assertEquals(PublishPreparedPurchaseResult.CONFLICT, result)
        assertNull(repository.find(DRAFT_ID))
        assertEquals(
            DraftStatus.NEEDS_REVIEW.name,
            database.invoiceDraftDao().findById(DRAFT_ID.value)?.status,
        )
    }

    @Test
    fun publishDoesNotMutateUnitOrProductCatalog() = runBlocking {
        seedReviewingDraft()
        val unit = requireNotNull(database.unitDao().findById(UNIT_ID.value))
        val product = requireNotNull(database.productDao().findById(PRODUCT_ID.value))

        assertEquals(PublishPreparedPurchaseResult.PREPARED, publish(purchase()))

        assertEquals(unit, database.unitDao().findById(UNIT_ID.value))
        assertEquals(product, database.productDao().findById(PRODUCT_ID.value))
        assertEquals(1, database.unitDao().countForBusiness(BUSINESS_ID.value))
        assertEquals(1, database.productDao().countForBusiness(BUSINESS_ID.value))
    }

    @Test
    fun publishAcceptsStagedProductWithoutInsertingItIntoCatalog() = runBlocking {
        seedReviewingDraft()
        val stagedId = ProductId.from(uuid(40))
        val staged = StagedPurchaseProduct(
            productId = stagedId,
            businessId = BUSINESS_ID,
            unitId = UNIT_ID,
            name = "Producto staged",
            sku = "STAGED-40",
        )
        val stagedLine = PreparedPurchaseLine(
            lineId = LINE_ID,
            position = 0,
            productId = stagedId,
            unitId = UNIT_ID,
            description = "Producto staged",
            quantity = Quantity.of("2"),
            unitCost = UnitCost.of("5.00", PEN),
            lineTotal = Money.ofMinor(1_000, PEN),
            linkConfidence = 1_000,
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )
        val purchase = purchase(line = stagedLine)

        assertEquals(PublishPreparedPurchaseResult.PREPARED, publish(purchase))

        assertNull(database.productDao().findById(stagedId.value))
        assertEquals(1, database.productDao().countForBusiness(BUSINESS_ID.value))
        assertEquals(staged, repository.find(DRAFT_ID)?.lines?.single()?.stagedProduct)
        assertEquals(
            DraftStatus.READY_TO_POST.name,
            database.invoiceDraftDao().findById(DRAFT_ID.value)?.status,
        )
    }

    @Test
    fun reopenDeletesSnapshotAndReturnsDraftToReviewInOneCommit() = runBlocking {
        seedReviewingDraft()
        assertEquals(PublishPreparedPurchaseResult.PREPARED, publish(purchase()))
        clock.advanceSeconds(60)

        assertTrue(repository.reopenForEdit(DRAFT_ID))

        assertNull(repository.find(DRAFT_ID))
        val reopened = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        assertEquals(DraftStatus.NEEDS_REVIEW.name, reopened.status)
        assertEquals(PUBLISH_TIME.plusSeconds(60).toEpochMilli(), reopened.updatedAt)
        assertFalse(repository.reopenForEdit(DRAFT_ID))
    }

    @Test
    fun insertFailureRollsBackReadyTransition() = runBlocking {
        seedReviewingDraft()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_prepared_insert " +
                "BEFORE INSERT ON prepared_purchases " +
                "BEGIN SELECT RAISE(ABORT, 'forced prepared insert failure'); END",
        )

        assertThrows(StorageException::class.java) {
            runBlocking { publish(purchase()) }
        }

        assertNull(repository.find(DRAFT_ID))
        val draft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        assertEquals(DraftStatus.NEEDS_REVIEW.name, draft.status)
        assertEquals(BASE_TIME.toEpochMilli(), draft.updatedAt)
    }

    @Test
    fun deleteFailureRollsBackReopenTransition() = runBlocking {
        seedReviewingDraft()
        val purchase = purchase()
        assertEquals(PublishPreparedPurchaseResult.PREPARED, publish(purchase))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_prepared_delete " +
                "BEFORE DELETE ON prepared_purchases " +
                "BEGIN SELECT RAISE(ABORT, 'forced prepared delete failure'); END",
        )
        clock.advanceSeconds(60)

        assertThrows(StorageException::class.java) {
            runBlocking { repository.reopenForEdit(DRAFT_ID) }
        }

        assertEquals(purchase, repository.find(DRAFT_ID))
        val draft = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        assertEquals(DraftStatus.READY_TO_POST.name, draft.status)
        assertEquals(PUBLISH_TIME.toEpochMilli(), draft.updatedAt)
    }

    private suspend fun seedReviewingDraft() = seedDraft(DraftStatus.NEEDS_REVIEW)

    private suspend fun seedDraft(status: DraftStatus) {
        database.unitDao().insert(unitEntity())
        database.productDao().insert(productEntity())
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = DRAFT_ID.value,
                businessId = BUSINESS_ID.value,
                createdAt = BASE_TIME.toEpochMilli(),
                updatedAt = BASE_TIME.toEpochMilli(),
                status = status.name,
            ),
        )
        database.invoiceHeaderEditDao().insert(headerEditEntity(HEADER_REVISION))
        database.invoiceLinesEditDao().insert(linesEditEntity(LINES_REVISION))
    }

    private suspend fun publish(purchase: PreparedPurchase): PublishPreparedPurchaseResult =
        repository.publish(
            purchase = purchase,
            expectedDraftUpdatedAt = BASE_TIME,
            expectedHeaderRevision = HEADER_REVISION,
            expectedLinesRevision = LINES_REVISION,
        )

    private fun unitEntity(): UnitEntity = UnitEntity(
        unitId = UNIT_ID.value,
        businessId = BUSINESS_ID.value,
        code = "NIU",
        name = "Unidad",
        createdAt = BASE_TIME.toEpochMilli(),
        updatedAt = BASE_TIME.toEpochMilli(),
    )

    private fun productEntity(): ProductEntity = ProductEntity(
        productId = PRODUCT_ID.value,
        businessId = BUSINESS_ID.value,
        unitId = UNIT_ID.value,
        name = "Arroz",
        createdAt = BASE_TIME.toEpochMilli(),
        updatedAt = BASE_TIME.toEpochMilli(),
        sku = "ARR-01",
    )

    private fun headerEditEntity(revision: Long): InvoiceHeaderEditEntity {
        val edit = InvoiceHeaderEdit(
            draftId = DRAFT_ID,
            revision = revision,
            updatedAt = BASE_TIME,
        )
        val payload = InvoiceHeaderEditCodec.encode(edit)
        return InvoiceHeaderEditEntity(
            draftId = DRAFT_ID.value,
            revision = revision,
            payloadCodecVersion = InvoiceHeaderEditCodec.VERSION,
            payloadSha256 = InvoiceHeaderEditCodec.sha256(payload),
            payload = payload,
            updatedAt = BASE_TIME.toEpochMilli(),
        )
    }

    private fun linesEditEntity(revision: Long): InvoiceLinesEditEntity {
        val edit = InvoiceLinesEdit(
            draftId = DRAFT_ID,
            lines = emptyList(),
            revision = revision,
            updatedAt = BASE_TIME,
        )
        val payload = InvoiceLinesEditCodec.encode(edit)
        return InvoiceLinesEditEntity(
            draftId = DRAFT_ID.value,
            revision = revision,
            payloadCodecVersion = InvoiceLinesEditCodec.VERSION,
            payloadSha256 = InvoiceLinesEditCodec.sha256(payload),
            payload = payload,
            updatedAt = BASE_TIME.toEpochMilli(),
        )
    }

    private fun purchase(
        totalMinor: Long = 1_000,
        preparedAt: Instant = PUBLISH_TIME,
        line: PreparedPurchaseLine? = null,
    ): PreparedPurchase {
        val total = Money.ofMinor(totalMinor, PEN)
        val lines = listOf(
            line ?: PreparedPurchaseLine(
                lineId = LINE_ID,
                position = 0,
                productId = PRODUCT_ID,
                unitId = UNIT_ID,
                description = "Arroz",
                quantity = Quantity.of("2"),
                unitCost = UnitCost.of("5.00", PEN),
                lineTotal = total,
                linkConfidence = 1_000,
            ),
        )
        val logicalHash = PreparedPurchase.logicalHash(
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            supplierId = null,
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor SA",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = "F001-123",
            issueDate = LocalDate.of(2026, 8, 8),
            currency = PEN,
            lines = lines,
            subtotal = null,
            tax = null,
            otherCharges = null,
            total = total,
            acceptedWarnings = emptyList(),
        )
        return PreparedPurchase(
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            supplierId = null,
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor SA",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = "F001-123",
            issueDate = LocalDate.of(2026, 8, 8),
            currency = PEN,
            lines = lines,
            subtotal = null,
            tax = null,
            otherCharges = null,
            total = total,
            acceptedWarnings = emptyList(),
            logicalHash = logicalHash,
            preparedAt = preparedAt,
        )
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-08-08T12:00:00Z")
        val PUBLISH_TIME: Instant = BASE_TIME.plusSeconds(30)
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DRAFT_ID: DraftId = DraftId.from(uuid(2))
        val UNIT_ID: UnitId = UnitId.from(uuid(3))
        val PRODUCT_ID: ProductId = ProductId.from(uuid(4))
        val LINE_ID: LineId = LineId.from(uuid(5))
        const val HEADER_REVISION: Long = 3L
        const val LINES_REVISION: Long = 5L

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
