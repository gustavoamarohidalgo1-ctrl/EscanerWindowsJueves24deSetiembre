package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.InvoiceHeaderEditPublication
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceHeaderReviewRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var businesses: RoomBusinessRepository
    private lateinit var drafts: RoomInvoiceDraftRepository
    private lateinit var reviews: RoomInvoiceHeaderReviewRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java).build()
        val clock = TestClock(BASE_TIME)
        businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
        drafts = RoomInvoiceDraftRepository(
            database = database,
            invoiceDraftDao = database.invoiceDraftDao(),
            invoiceImageDao = database.invoiceImageDao(),
            invoiceLineDao = database.invoiceLineDao(),
            dispatchers = testDispatchers,
            clock = clock,
        )
        reviews = newRepository()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun partialTextAndTypedProjectionSurviveRepositoryRecreation() = runBlocking {
        val draft = seedDraft()
        val edit = edit(revision = 1, updatedAt = BASE_TIME.plusSeconds(1))
        val projection = projection(draft, edit.updatedAt)

        val result = reviews.saveIfNewer(InvoiceHeaderEditPublication(edit, projection))

        assertEquals(SaveInvoiceHeaderEditResult.SAVED, result)
        assertEquals(edit, newRepository().find(DRAFT_ID))
        val stored = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertEquals(BASE_TIME, stored.createdAt)
        assertEquals(edit.updatedAt, stored.updatedAt)
        assertEquals("Distribuidora Pacífico S.A.C.", stored.supplierLegalNameNormalized)
        assertEquals(PurchaseDocumentType.INVOICE, stored.documentType)
        assertEquals("F001-00000042", stored.documentNumberNormalized)
        assertEquals(20L, stored.otherCharges?.minorUnits)
        // La fecha parcial inválida quedó en el edit, sin borrar el último valor tipado.
        assertEquals("20/", reviews.find(DRAFT_ID)?.issueDate)
        assertEquals(draft.issueDate, stored.issueDate)
    }

    @Test
    fun equalRetryIsIdempotentAndOlderOrConflictingRevisionCannotOverwrite() = runBlocking {
        val draft = seedDraft()
        val first = edit(revision = 4, updatedAt = BASE_TIME.plusSeconds(4))
        val firstProjection = projection(draft, first.updatedAt)
        assertEquals(
            SaveInvoiceHeaderEditResult.SAVED,
            reviews.saveIfNewer(InvoiceHeaderEditPublication(first, firstProjection)),
        )

        val changedProjection = firstProjection.copy(
            supplierLegalNameNormalized = "NO DEBE GANAR",
            updatedAt = BASE_TIME.plusSeconds(40),
        )
        assertEquals(
            SaveInvoiceHeaderEditResult.ALREADY_SAVED,
            reviews.saveIfNewer(InvoiceHeaderEditPublication(first, changedProjection)),
        )
        assertEquals(first.updatedAt, drafts.findDraft(DRAFT_ID)?.updatedAt)
        assertEquals(
            "Distribuidora Pacífico S.A.C.",
            drafts.findDraft(DRAFT_ID)?.supplierLegalNameNormalized,
        )

        val stale = first.copy(revision = 3, updatedAt = BASE_TIME.plusSeconds(5))
        assertEquals(
            SaveInvoiceHeaderEditResult.STALE_REVISION,
            reviews.saveIfNewer(InvoiceHeaderEditPublication(stale, firstProjection)),
        )
        val conflict = first.copy(total = "99.99")
        assertEquals(
            SaveInvoiceHeaderEditResult.CONFLICT,
            reviews.saveIfNewer(InvoiceHeaderEditPublication(conflict, firstProjection)),
        )
        assertEquals(first, reviews.find(DRAFT_ID))
    }

    @Test
    fun identicalRetryWithNanosecondTimestampIsIdempotentAndDoesNotRewriteRows() = runBlocking {
        val draft = seedDraft()
        val preciseUpdatedAt = BASE_TIME.plusSeconds(6).plusNanos(456_789L)
        assertTrue(preciseUpdatedAt.nano % 1_000_000 != 0)
        val edit = edit(revision = 6, updatedAt = preciseUpdatedAt)
        val publication = InvoiceHeaderEditPublication(
            edit = edit,
            projectedDraft = projection(draft, preciseUpdatedAt),
        )

        assertEquals(
            SaveInvoiceHeaderEditResult.SAVED,
            reviews.saveIfNewer(publication),
        )
        val editRowAfterFirstSave = requireNotNull(
            database.invoiceHeaderEditDao().findByDraftId(DRAFT_ID.value),
        )
        val draftRowAfterFirstSave = requireNotNull(
            database.invoiceDraftDao().findById(DRAFT_ID.value),
        )

        assertEquals(
            SaveInvoiceHeaderEditResult.ALREADY_SAVED,
            reviews.saveIfNewer(publication),
        )

        assertEquals(
            editRowAfterFirstSave,
            database.invoiceHeaderEditDao().findByDraftId(DRAFT_ID.value),
        )
        assertEquals(
            draftRowAfterFirstSave,
            database.invoiceDraftDao().findById(DRAFT_ID.value),
        )
    }

    @Test
    fun identicalRetryIsRejectedAfterDraftLeavesHeaderReview() = runBlocking {
        val draft = seedDraft()
        val edit = edit(revision = 2, updatedAt = BASE_TIME.plusSeconds(2))
        val publication = InvoiceHeaderEditPublication(
            edit = edit,
            projectedDraft = projection(draft, edit.updatedAt),
        )
        assertEquals(SaveInvoiceHeaderEditResult.SAVED, reviews.saveIfNewer(publication))

        val advanced = requireNotNull(drafts.findDraft(DRAFT_ID)).copy(
            status = DraftStatus.READY_TO_POST,
            updatedAt = BASE_TIME.plusSeconds(3),
        )
        assertTrue(drafts.updateDraft(advanced))

        assertEquals(
            SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE,
            reviews.saveIfNewer(publication),
        )
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(DRAFT_ID)?.status)
        assertEquals(
            edit.copy(updatedAt = Instant.ofEpochMilli(edit.updatedAt.toEpochMilli())),
            reviews.find(DRAFT_ID),
        )
    }

    @Test
    fun concurrentWritersFromTheSameAbsentBaseAllowExactlyOneWinner() = runBlocking {
        val draft = seedDraft()
        val publications = (1L..8L).map { revision ->
            val edit = edit(revision, BASE_TIME.plusSeconds(revision))
            InvoiceHeaderEditPublication(edit, projection(draft, edit.updatedAt))
        }

        val results = coroutineScope {
            publications.map { publication ->
                async(Dispatchers.Default) { reviews.saveIfNewer(publication) }
            }.awaitAll()
        }

        assertEquals(1, results.count { it == SaveInvoiceHeaderEditResult.SAVED })
        val winner = requireNotNull(reviews.find(DRAFT_ID))
        assertTrue(winner.revision in 1L..8L)
        assertEquals(winner.updatedAt, drafts.findDraft(DRAFT_ID)?.updatedAt)
    }

    @Test
    fun higherRevisionStillRequiresTheExactPersistedBase() = runBlocking {
        val draft = seedDraft()
        val first = edit(1L, BASE_TIME.plusSeconds(1))
        assertEquals(
            SaveInvoiceHeaderEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceHeaderEditPublication(first, projection(draft, first.updatedAt)),
            ),
        )

        val skippedLocalRevision = edit(3L, BASE_TIME.plusSeconds(3))
        val skippedProjection = projection(draft, skippedLocalRevision.updatedAt)
        assertEquals(
            SaveInvoiceHeaderEditResult.STALE_REVISION,
            reviews.saveIfNewer(
                InvoiceHeaderEditPublication(skippedLocalRevision, skippedProjection),
            ),
        )
        assertEquals(
            SaveInvoiceHeaderEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceHeaderEditPublication(
                    edit = skippedLocalRevision,
                    projectedDraft = skippedProjection,
                    expectedRevision = 1L,
                ),
            ),
        )
        assertEquals(3L, reviews.find(DRAFT_ID)?.revision)
    }

    @Test
    fun foreignKeyFailureRollsBackEditAndProjection() = runBlocking {
        val draft = seedDraft()
        val edit = edit(1, BASE_TIME.plusSeconds(1))
        val missingSupplier = SupplierId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000099"),
        )

        assertThrows(StorageException::class.java) {
            runBlocking {
                reviews.saveIfNewer(
                    InvoiceHeaderEditPublication(
                        edit,
                        projection(draft, edit.updatedAt).copy(supplierId = missingSupplier),
                    ),
                )
            }
        }

        assertNull(reviews.find(DRAFT_ID))
        assertNull(drafts.findDraft(DRAFT_ID)?.supplierLegalNameNormalized)
        assertEquals(BASE_TIME, drafts.findDraft(DRAFT_ID)?.updatedAt)
    }

    @Test
    fun nonReviewDraftRejectsEditWithoutWritingEitherSide() = runBlocking {
        val draft = seedDraft(status = DraftStatus.READY_TO_POST)
        val edit = edit(1, BASE_TIME.plusSeconds(1))

        val result = reviews.saveIfNewer(
            InvoiceHeaderEditPublication(
                edit,
                projection(draft.copy(status = DraftStatus.NEEDS_REVIEW), edit.updatedAt),
            ),
        )

        assertEquals(SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE, result)
        assertNull(reviews.find(DRAFT_ID))
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(DRAFT_ID)?.status)
    }

    private fun newRepository() = RoomInvoiceHeaderReviewRepository(
        database = database,
        headerEditDao = database.invoiceHeaderEditDao(),
        invoiceDraftDao = database.invoiceDraftDao(),
        dispatchers = testDispatchers,
    )

    private suspend fun seedDraft(
        status: DraftStatus = DraftStatus.NEEDS_REVIEW,
    ): InvoiceDraft {
        businesses.create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio Revisión SAC",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        return drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = status,
                issueDateRaw = "2026-08-10",
                issueDate = java.time.LocalDate.parse("2026-08-10"),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
    }

    private fun edit(revision: Long, updatedAt: Instant) = InvoiceHeaderEdit(
        draftId = DRAFT_ID,
        supplierRuc = "20123456789",
        supplierLegalName = "Distribuidora Pacífico S.A.C.",
        documentType = "FACTURA",
        documentSeries = "F001",
        documentNumber = "00000042",
        issueDate = "20/",
        currency = "PEN",
        subtotal = "10.00",
        igv = "1.80",
        otherCharges = "0.20",
        total = "12.00",
        revision = revision,
        touchedFields = setOf(
            InvoiceHeaderEditField.SUPPLIER_LEGAL_NAME,
            InvoiceHeaderEditField.ISSUE_DATE,
            InvoiceHeaderEditField.OTHER_CHARGES,
        ),
        updatedAt = updatedAt,
    )

    private fun projection(draft: InvoiceDraft, updatedAt: Instant): InvoiceDraft {
        val currency = CurrencyCode.of("PEN")
        return draft.copy(
            status = DraftStatus.NEEDS_REVIEW,
            supplierRucRaw = "20123456789",
            supplierRucNormalized = "20123456789",
            supplierLegalNameRaw = "Distribuidora Pacífico S.A.C.",
            supplierLegalNameNormalized = "Distribuidora Pacífico S.A.C.",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumberRaw = "F001-00000042",
            documentNumberNormalized = "F001-00000042",
            currency = currency,
            subtotal = Money.ofMinor(1_000L, currency),
            tax = Money.ofMinor(180L, currency),
            otherCharges = Money.ofMinor(20L, currency),
            total = Money.ofMinor(1_200L, currency),
            updatedAt = updatedAt,
        )
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000023"),
        )
    }
}
