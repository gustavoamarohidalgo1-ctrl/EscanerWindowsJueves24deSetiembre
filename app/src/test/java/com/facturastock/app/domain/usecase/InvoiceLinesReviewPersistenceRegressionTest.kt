package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InvoiceLinesEditPublication
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeParsedInvoiceRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceLinesReviewPersistenceRegressionTest {
    @Test
    fun `external catalog links survive delete retry restart and restore`() = runTest {
        val fixture = fixture(
            baseline = invoiceLine(quantity = "1", unitCost = "10.00", total = "10.00"),
        )
        val initial = fixture.load(DRAFT_ID)!!
        val initialLine = initial.edit.activeLines.single()
        assertNull(initialLine.linkedProductId)
        assertNull(initialLine.linkedUnitId)

        val externallyLinked = fixture.drafts.observeLines(DRAFT_ID).first().single().copy(
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            linkConfidence = 930,
        )
        assertEquals(true, fixture.drafts.updateLine(externallyLinked))

        val deleteTime = NOW.plusSeconds(1L)
        val deleted = initial.edit.copy(
            lines = listOf(
                initialLine.copy(
                    deletedAt = deleteTime,
                    updatedAt = deleteTime,
                ),
            ),
            revision = initial.edit.revision + 1L,
            updatedAt = deleteTime,
        )
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            fixture.save(deleted, expectedRevision = initial.persistedEditRevision),
        )
        assertEquals(emptyList<InvoiceLine>(), fixture.drafts.observeLines(DRAFT_ID).first())

        val afterDeleteRestart = fixture.load(DRAFT_ID)!!
        assertEquals(PRODUCT_ID, afterDeleteRestart.edit.lines.single().linkedProductId)
        assertEquals(UNIT_ID, afterDeleteRestart.edit.lines.single().linkedUnitId)
        assertEquals(930, afterDeleteRestart.edit.lines.single().linkConfidence)
        val retryTime = NOW.plusSeconds(2L)
        val retry = afterDeleteRestart.edit.copy(
            revision = afterDeleteRestart.edit.revision + 1L,
            updatedAt = retryTime,
        )
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            fixture.save(retry, expectedRevision = afterDeleteRestart.persistedEditRevision),
        )

        val beforeRestoreRestart = fixture.load(DRAFT_ID)!!
        val restoreTime = NOW.plusSeconds(3L)
        val tombstone = beforeRestoreRestart.edit.lines.single()
        val restored = beforeRestoreRestart.edit.copy(
            lines = listOf(
                tombstone.copy(
                    position = 0,
                    deletedAt = null,
                    updatedAt = restoreTime,
                ),
            ),
            revision = beforeRestoreRestart.edit.revision + 1L,
            updatedAt = restoreTime,
        )
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            fixture.save(restored, expectedRevision = beforeRestoreRestart.persistedEditRevision),
        )

        val restoredProjection = fixture.drafts.observeLines(DRAFT_ID).first().single()
        assertEquals(LINE_ID, restoredProjection.lineId)
        assertEquals(PRODUCT_ID, restoredProjection.productId)
        assertEquals(UNIT_ID, restoredProjection.unitId)
        assertEquals(930, restoredProjection.linkConfidence)
    }

    @Test
    fun `authoritative catalog intent replaces the previous link in edit and projection`() =
        runTest {
            val fixture = fixture(
                baseline = invoiceLine(quantity = "1", unitCost = "10.00", total = "10.00")
                    .copy(
                        productId = PRODUCT_ID,
                        unitId = UNIT_ID,
                        linkConfidence = 850,
                    ),
            )
            val initial = requireNotNull(fixture.load(DRAFT_ID))
            val changedAt = NOW.plusSeconds(1L)
            val changed = initial.edit.copy(
                lines = initial.edit.lines.map { line ->
                    line.copy(
                        linkedProductId = PRODUCT_2_ID,
                        linkedUnitId = UNIT_ID,
                        linkConfidence = 990,
                        updatedAt = changedAt,
                    )
                },
                revision = initial.edit.revision + 1L,
                updatedAt = changedAt,
            )

            assertEquals(
                SaveInvoiceLinesEditResult.SAVED,
                fixture.save(
                    edit = changed,
                    expectedRevision = initial.persistedEditRevision,
                    catalogLinkLineIds = setOf(LINE_ID),
                ),
            )

            val persisted = requireNotNull(fixture.reviews.find(DRAFT_ID)).activeLines.single()
            assertEquals(PRODUCT_2_ID, persisted.linkedProductId)
            assertEquals(UNIT_ID, persisted.linkedUnitId)
            assertEquals(990, persisted.linkConfidence)
            val projected = fixture.drafts.observeLines(DRAFT_ID).first().single()
            assertEquals(PRODUCT_2_ID, projected.productId)
            assertEquals(UNIT_ID, projected.unitId)
            assertEquals(990, projected.linkConfidence)
        }

    @Test
    fun `calculated total after explicit tax decision remains convergent on retry and advance`() =
        runTest {
            val fixture = fixture(
                baseline = invoiceLine(quantity = "2", unitCost = "5.00", total = null),
            )

            val initial = fixture.load(DRAFT_ID)!!
            val initialTotal = initial.edit.activeLines.single().total
            assertNull(initialTotal.calculated)
            assertNull(initialTotal.selectedValue)
            assertNull(fixture.drafts.observeLines(DRAFT_ID).first().single().lineTotal)

            val decisionTime = NOW.plusSeconds(1L)
            val decided = InvoiceLineReviewCalculator().recalculate(
                initial.edit.copy(
                    lines = initial.edit.lines.map { line ->
                        line.copy(
                            taxTreatment = InventoryTaxTreatment.EXEMPT,
                            updatedAt = decisionTime,
                        )
                    },
                    revision = initial.edit.revision + 1L,
                    updatedAt = decisionTime,
                ),
                PEN,
            )
            assertEquals("10.00", decided.activeLines.single().total.calculated)
            assertEquals("10.00", decided.activeLines.single().total.selectedValue)
            assertEquals(
                SaveInvoiceLinesEditResult.SAVED,
                fixture.save(decided, expectedRevision = initial.persistedEditRevision),
            )
            assertEquals(1_000L, fixture.drafts.observeLines(DRAFT_ID).first().single().lineTotal?.minorUnits)

            assertEquals(
                SaveInvoiceLinesEditResult.ALREADY_SAVED,
                fixture.save(decided, expectedRevision = decided.revision),
            )
            assertEquals(1_000L, fixture.drafts.observeLines(DRAFT_ID).first().single().lineTotal?.minorUnits)

            val advanceTime = NOW.plusSeconds(2L)
            val advanced = decided.copy(
                revision = decided.revision + 1L,
                updatedAt = advanceTime,
            )
            assertEquals(
                SaveInvoiceLinesEditResult.SAVED,
                fixture.save(advanced, expectedRevision = decided.revision),
            )

            val afterRestart = fixture.load(DRAFT_ID)!!
            assertEquals("10.00", afterRestart.edit.activeLines.single().total.calculated)
            assertEquals("10.00", afterRestart.edit.activeLines.single().total.selectedValue)
            assertEquals(1_000L, fixture.drafts.observeLines(DRAFT_ID).first().single().lineTotal?.minorUnits)
        }

    private suspend fun fixture(baseline: InvoiceLine): Fixture {
        val clock = AppClock { NOW }
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW,
                currency = PEN,
                total = Money.ofMinor(1_000L, PEN),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        drafts.replaceLines(DRAFT_ID, listOf(baseline))
        val reviews = ExactPublicationLinesReviewRepository(drafts)
        val parsed = FakeParsedInvoiceRepository(drafts)
        return Fixture(
            drafts = drafts,
            reviews = reviews,
            load = LoadInvoiceLinesReviewUseCase(drafts, reviews, parsed, clock),
            save = SaveInvoiceLinesEditUseCase(drafts, reviews),
        )
    }

    private data class Fixture(
        val drafts: FakeInvoiceDraftRepository,
        val reviews: ExactPublicationLinesReviewRepository,
        val load: LoadInvoiceLinesReviewUseCase,
        val save: SaveInvoiceLinesEditUseCase,
    )

    /**
     * Fake agregado intencionalmente transparente: no reconcilia enlaces ni importes por su
     * cuenta, por lo que las pruebas observan exactamente lo que publican los casos de uso.
     */
    private class ExactPublicationLinesReviewRepository(
        private val drafts: FakeInvoiceDraftRepository,
    ) : InvoiceLinesReviewRepository {
        private val mutex = Mutex()
        private val stored = MutableStateFlow<InvoiceLinesEdit?>(null)

        override suspend fun find(draftId: DraftId): InvoiceLinesEdit? = mutex.withLock {
            stored.value?.takeIf { it.draftId == draftId }
        }

        override fun observe(draftId: DraftId): Flow<InvoiceLinesEdit?> = stored

        override suspend fun saveIfNewer(
            publication: InvoiceLinesEditPublication,
        ): SaveInvoiceLinesEditResult = mutex.withLock {
            val current = stored.value
            if (current != null) {
                when {
                    current.revision == publication.edit.revision &&
                        current == publication.edit ->
                        return@withLock SaveInvoiceLinesEditResult.ALREADY_SAVED

                    current.revision > publication.edit.revision ->
                        return@withLock SaveInvoiceLinesEditResult.STALE_REVISION

                    current.revision == publication.edit.revision ->
                        return@withLock SaveInvoiceLinesEditResult.CONFLICT

                    publication.expectedRevision != current.revision ->
                        return@withLock SaveInvoiceLinesEditResult.STALE_REVISION
                }
            } else if (publication.expectedRevision != null) {
                return@withLock SaveInvoiceLinesEditResult.STALE_REVISION
            }
            drafts.replaceLines(publication.edit.draftId, publication.projectedLines)
            stored.value = publication.edit
            SaveInvoiceLinesEditResult.SAVED
        }
    }

    private fun invoiceLine(
        quantity: String,
        unitCost: String,
        total: String?,
    ): InvoiceLine = InvoiceLine(
        lineId = LINE_ID,
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        position = 0,
        descriptionRaw = "Producto",
        descriptionNormalized = "Producto",
        quantity = Quantity.of(quantity),
        unitCost = UnitCost.of(unitCost, PEN),
        lineTotal = total?.let { Money.fromMajor(it, PEN) },
        ocrConfidence = 950,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val DRAFT_ID: DraftId = DraftId.from(uuid("10000000-0000-0000-0000-000000000001"))
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid("20000000-0000-0000-0000-000000000001"))
        val LINE_ID: LineId = LineId.from(uuid("30000000-0000-0000-0000-000000000001"))
        val PRODUCT_ID: ProductId = ProductId.from(uuid("40000000-0000-0000-0000-000000000001"))
        val PRODUCT_2_ID: ProductId = ProductId.from(uuid("40000000-0000-0000-0000-000000000002"))
        val UNIT_ID: UnitId = UnitId.from(uuid("50000000-0000-0000-0000-000000000001"))

        fun uuid(value: String): UUID = UUID.fromString(value)
    }
}
