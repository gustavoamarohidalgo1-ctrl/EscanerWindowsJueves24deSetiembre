package com.facturastock.app.data.repository

import com.facturastock.app.data.local.newFacturaStockDatabase
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InvoiceLinesEditPublication
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InvoiceLinesReviewRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var reviews: RoomInvoiceLinesReviewRepository

    @Before
    fun setUp() = runBlocking {
        database = tempFolder.newFacturaStockDatabase()
        reviews = newRepository()
        seedGraph()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun snapshotAndTypedProjectionSurviveRecreationAndPreserveCatalogLinks() = runBlocking {
        val edit = edit(revision = 1, updatedAt = BASE_TIME.plusSeconds(1))
        val projection = listOf(
            projectedLine(LINE_1, 0, "Café premium", totalMinor = -1_180L),
            projectedLine(LINE_2, 1, "Azúcar", totalMinor = 500L),
        )

        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(InvoiceLinesEditPublication(edit, projection)),
        )

        assertEquals(edit, newRepository().find(DRAFT_ID))
        assertEquals(edit, newRepository().observe(DRAFT_ID).awaitMatching { it?.revision == 1L })
        val stored = database.invoiceLineDao().listForDraft(DRAFT_ID.value)
        assertEquals(listOf(LINE_1.value, LINE_2.value), stored.map(InvoiceLineEntity::lineId))
        assertEquals("P001", stored[0].codeNormalized)
        assertEquals("NIU", stored[0].unitCodeNormalized)
        assertEquals(100L, stored[0].discountMinorUnits)
        assertEquals(180L, stored[0].taxMinorUnits)
        assertEquals(-1_180L, stored[0].lineTotalMinorUnits)
        // La proyección enviada no llevaba enlaces: el autosave no deshace el link concurrente.
        assertEquals(PRODUCT_ID, stored[0].productId)
        assertEquals(UNIT_ID, stored[0].unitId)
        assertEquals(940, stored[0].linkConfidence)
    }

    @Test
    fun deleteRestoreAndReorderUseSameStableIdAndSurviveRestart() = runBlocking {
        val first = edit(1, BASE_TIME.plusSeconds(1))
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceLinesEditPublication(
                    first,
                    listOf(
                        projectedLine(LINE_1, 0, "Café", 1_180L),
                        projectedLine(LINE_2, 1, "Azúcar", 500L),
                    ),
                ),
            ),
        )

        val deleteTime = BASE_TIME.plusSeconds(2)
        val deletedSecond = first.lines[1].copy(
            deletedAt = deleteTime,
            updatedAt = deleteTime,
        )
        val deleted = InvoiceLinesEdit(
            draftId = DRAFT_ID,
            lines = listOf(first.lines[0], deletedSecond),
            revision = 2,
            updatedAt = deleteTime,
        )
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceLinesEditPublication(
                    deleted,
                    listOf(projectedLine(LINE_1, 0, "Café", 1_180L)),
                    expectedRevision = 1,
                ),
            ),
        )
        assertNull(database.invoiceLineDao().findById(LINE_2.value))
        assertEquals(LINE_2, newRepository().find(DRAFT_ID)?.lines?.single { it.isDeleted }?.lineId)

        val restoreTime = BASE_TIME.plusSeconds(3)
        val restored = InvoiceLinesEdit(
            draftId = DRAFT_ID,
            lines = listOf(
                deletedSecond.copy(position = 0, deletedAt = null, updatedAt = restoreTime),
                first.lines[0].copy(position = 1, updatedAt = restoreTime),
            ),
            revision = 3,
            updatedAt = restoreTime,
        )
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceLinesEditPublication(
                    restored,
                    listOf(
                        projectedLine(LINE_2, 0, "Azúcar", 500L),
                        projectedLine(LINE_1, 1, "Café", 1_180L),
                    ),
                    expectedRevision = 2,
                ),
            ),
        )

        assertEquals(restored, newRepository().find(DRAFT_ID))
        assertEquals(
            listOf(LINE_2.value, LINE_1.value),
            database.invoiceLineDao().listForDraft(DRAFT_ID.value).map(InvoiceLineEntity::lineId),
        )
        val restoredProjection = requireNotNull(database.invoiceLineDao().findById(LINE_2.value))
        assertEquals(PRODUCT_ID, restoredProjection.productId)
        assertEquals(UNIT_ID, restoredProjection.unitId)
        assertEquals(940, restoredProjection.linkConfidence)
        assertEquals(1, newRepository().find(DRAFT_ID)?.lines?.first()?.sourcePosition)
    }

    @Test
    fun equalRetryIsIdempotentAndStaleOrConflictingWriterCannotOverwrite() = runBlocking {
        val first = edit(4, BASE_TIME.plusSeconds(4))
        val publication = InvoiceLinesEditPublication(
            first,
            listOf(
                projectedLine(LINE_1, 0, "Café", 1_180L),
                projectedLine(LINE_2, 1, "Azúcar", 500L),
            ),
        )
        assertEquals(SaveInvoiceLinesEditResult.SAVED, reviews.saveIfNewer(publication))
        assertEquals(SaveInvoiceLinesEditResult.ALREADY_SAVED, reviews.saveIfNewer(publication))

        val stale = edit(3, BASE_TIME.plusSeconds(5))
        assertEquals(
            SaveInvoiceLinesEditResult.STALE_REVISION,
            reviews.saveIfNewer(publication.copy(edit = stale)),
        )
        val conflict = first.copy(lines = first.lines.mapIndexed { index, line ->
            if (index == 0) line.copy(reviewConfirmedByUser = true) else line
        })
        assertEquals(
            SaveInvoiceLinesEditResult.CONFLICT,
            reviews.saveIfNewer(publication.copy(edit = conflict)),
        )
        val future = edit(6, BASE_TIME.plusSeconds(6))
        assertEquals(
            SaveInvoiceLinesEditResult.STALE_REVISION,
            reviews.saveIfNewer(publication.copy(edit = future)),
        )
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(publication.copy(edit = future, expectedRevision = 4)),
        )
    }

    @Test
    fun concurrentSnapshotsFromAbsentBaseAllowExactlyOneWinner() = runBlocking {
        val publications = (1L..8L).map { revision ->
            InvoiceLinesEditPublication(
                edit(revision, BASE_TIME.plusSeconds(revision)),
                listOf(
                    projectedLine(LINE_1, 0, "Café $revision", 1_180L),
                    projectedLine(LINE_2, 1, "Azúcar", 500L),
                ),
            )
        }

        val results = coroutineScope {
            publications.map { publication ->
                async(Dispatchers.Default) { reviews.saveIfNewer(publication) }
            }.awaitAll()
        }

        assertEquals(1, results.count { it == SaveInvoiceLinesEditResult.SAVED })
        assertTrue(requireNotNull(reviews.find(DRAFT_ID)).revision in 1L..8L)
    }

    @Test
    fun catalogLinkIntentPersistsNullToLinkAndChangeInSnapshotAndProjection() = runBlocking {
        database.invoiceLineDao().listForDraft(DRAFT_ID.value).forEach { current ->
            database.invoiceLineDao().update(
                current.copy(productId = null, unitId = null, linkConfidence = null),
            )
        }
        val baseline = edit(1, BASE_TIME.plusSeconds(1)).withoutCatalogLinks()
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceLinesEditPublication(
                    edit = baseline,
                    projectedLines = listOf(
                        projectedLine(LINE_1, 0, "Café", 1_180L),
                        projectedLine(LINE_2, 1, "Azúcar", 500L),
                    ),
                ),
            ),
        )

        val linked = baseline.withCatalogLink(
            lineId = LINE_1,
            productId = PRODUCT_ID,
            unitId = UNIT_ID,
            confidence = 940,
            revision = 2,
            updatedAt = BASE_TIME.plusSeconds(2),
        )
        val publication = InvoiceLinesEditPublication(
            edit = linked,
            projectedLines = listOf(
                projectedLine(LINE_1, 0, "Café", 1_180L).copy(
                    productId = requireNotNull(ProductId.parse(PRODUCT_ID)),
                    unitId = requireNotNull(UnitId.parse(UNIT_ID)),
                    linkConfidence = 940,
                ),
                projectedLine(LINE_2, 1, "Azúcar", 500L),
            ),
            expectedRevision = 1,
            catalogLinkLineIds = setOf(LINE_1),
        )
        assertEquals(SaveInvoiceLinesEditResult.SAVED, reviews.saveIfNewer(publication))
        assertCatalogLink(PRODUCT_ID, UNIT_ID, 940)
        assertEquals(SaveInvoiceLinesEditResult.ALREADY_SAVED, reviews.saveIfNewer(publication))

        database.productDao().insert(
            ProductEntity(PRODUCT_2_ID, BUSINESS_ID.value, UNIT_ID, "Café alternativo", 1_000, 1_000),
        )
        val changed = linked.withCatalogLink(
            lineId = LINE_1,
            productId = PRODUCT_2_ID,
            unitId = UNIT_ID,
            confidence = 1_000,
            revision = 3,
            updatedAt = BASE_TIME.plusSeconds(3),
        )
        val changedPublication = publication.copy(
            edit = changed,
            projectedLines = publication.projectedLines.map { line ->
                if (line.lineId == LINE_1) {
                    line.copy(
                        productId = requireNotNull(ProductId.parse(PRODUCT_2_ID)),
                        linkConfidence = 1_000,
                        updatedAt = BASE_TIME.plusSeconds(3),
                    )
                } else {
                    line.copy(updatedAt = BASE_TIME.plusSeconds(3))
                }
            },
        )
        assertEquals(
            SaveInvoiceLinesEditResult.STALE_REVISION,
            reviews.saveIfNewer(changedPublication.copy(expectedRevision = 1)),
        )
        assertCatalogLink(PRODUCT_ID, UNIT_ID, 940)
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(changedPublication.copy(expectedRevision = 2)),
        )
        assertCatalogLink(PRODUCT_2_ID, UNIT_ID, 1_000)
    }

    @Test
    fun catalogLinkIntentValidatesMultipleProductsWithOneBulkCatalogSnapshot() = runBlocking {
        database.invoiceLineDao().listForDraft(DRAFT_ID.value).forEach { current ->
            database.invoiceLineDao().update(
                current.copy(productId = null, unitId = null, linkConfidence = null),
            )
        }
        database.productDao().insert(
            ProductEntity(PRODUCT_2_ID, BUSINESS_ID.value, UNIT_ID, "Azúcar", 1_000, 1_000),
        )
        val baseline = edit(1, BASE_TIME.plusSeconds(1)).withoutCatalogLinks()
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceLinesEditPublication(
                    edit = baseline,
                    projectedLines = listOf(
                        projectedLine(LINE_1, 0, "Café", 1_180L),
                        projectedLine(LINE_2, 1, "Azúcar", 500L),
                    ),
                ),
            ),
        )

        val updatedAt = BASE_TIME.plusSeconds(2)
        val linked = baseline
            .withCatalogLink(LINE_1, PRODUCT_ID, UNIT_ID, 900, 2, updatedAt)
            .withCatalogLink(LINE_2, PRODUCT_2_ID, UNIT_ID, 910, 2, updatedAt)
        val result = reviews.saveIfNewer(
            InvoiceLinesEditPublication(
                edit = linked,
                projectedLines = listOf(
                    projectedLine(LINE_1, 0, "Café", 1_180L).copy(
                        productId = requireNotNull(ProductId.parse(PRODUCT_ID)),
                        unitId = requireNotNull(UnitId.parse(UNIT_ID)),
                        linkConfidence = 900,
                        updatedAt = updatedAt,
                    ),
                    projectedLine(LINE_2, 1, "Azúcar", 500L).copy(
                        productId = requireNotNull(ProductId.parse(PRODUCT_2_ID)),
                        unitId = requireNotNull(UnitId.parse(UNIT_ID)),
                        linkConfidence = 910,
                        updatedAt = updatedAt,
                    ),
                ),
                expectedRevision = 1,
                catalogLinkLineIds = setOf(LINE_1, LINE_2),
            ),
        )

        assertEquals(SaveInvoiceLinesEditResult.SAVED, result)
        val stored = database.invoiceLineDao().listForDraft(DRAFT_ID.value)
        assertEquals(listOf(PRODUCT_ID, PRODUCT_2_ID), stored.map { line -> line.productId })
        assertEquals(listOf(UNIT_ID, UNIT_ID), stored.map { line -> line.unitId })
        assertEquals(listOf(900, 910), stored.map { line -> line.linkConfidence })
    }

    @Test
    fun catalogLinkIntentRejectsMissingAndCrossBusinessReferencesWithoutMutation() = runBlocking {
        database.invoiceLineDao().listForDraft(DRAFT_ID.value).forEach { current ->
            database.invoiceLineDao().update(
                current.copy(productId = null, unitId = null, linkConfidence = null),
            )
        }
        val baseline = edit(1, BASE_TIME.plusSeconds(1)).withoutCatalogLinks()
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceLinesEditPublication(
                    edit = baseline,
                    projectedLines = listOf(
                        projectedLine(LINE_1, 0, "Café", 1_180L),
                        projectedLine(LINE_2, 1, "Azúcar", 500L),
                    ),
                ),
            ),
        )

        assertEquals(
            SaveInvoiceLinesEditResult.CONFLICT,
            reviews.saveIfNewer(
                catalogLinkPublication(
                    baseline = baseline,
                    productId = MISSING_PRODUCT_ID,
                    unitId = UNIT_ID,
                ),
            ),
        )
        assertCatalogLink(null, null, null)

        database.unitDao().insert(
            UnitEntity(SECOND_UNIT_ID, BUSINESS_ID.value, "LTR", "Litro", 1_000, 1_000),
        )
        assertEquals(
            SaveInvoiceLinesEditResult.CONFLICT,
            reviews.saveIfNewer(
                catalogLinkPublication(
                    baseline = baseline,
                    productId = PRODUCT_ID,
                    unitId = SECOND_UNIT_ID,
                ),
            ),
        )
        assertCatalogLink(null, null, null)

        database.businessDao().insert(
            BusinessEntity(OTHER_BUSINESS_ID, "Otro negocio", 1_000, 1_000),
        )
        database.unitDao().insert(
            UnitEntity(OTHER_UNIT_ID, OTHER_BUSINESS_ID, "KGM", "Kilogramo", 1_000, 1_000),
        )
        database.productDao().insert(
            ProductEntity(
                OTHER_PRODUCT_ID,
                OTHER_BUSINESS_ID,
                OTHER_UNIT_ID,
                "Producto ajeno",
                1_000,
                1_000,
            ),
        )
        assertEquals(
            SaveInvoiceLinesEditResult.CONFLICT,
            reviews.saveIfNewer(
                catalogLinkPublication(
                    baseline = baseline,
                    productId = OTHER_PRODUCT_ID,
                    unitId = OTHER_UNIT_ID,
                ),
            ),
        )
        assertCatalogLink(null, null, null)
        assertEquals(1L, requireNotNull(reviews.find(DRAFT_ID)).revision)
    }

    @Test
    fun stalePublicationCannotResurrectAnExactUnlinkInSnapshotOrProjection() = runBlocking {
        // El caso de uso ya leyó ambos links, pero otro flujo los desvincula antes de que Room
        // abra la transacción de autosave.
        database.invoiceLineDao().listForDraft(DRAFT_ID.value).forEach { current ->
            database.invoiceLineDao().update(
                current.copy(productId = null, unitId = null, linkConfidence = null),
            )
        }
        val saveTime = BASE_TIME.plusSeconds(1)
        val stale = edit(revision = 1, updatedAt = saveTime)
        val staleTombstone = stale.lines[0].copy(
            deletedAt = saveTime,
            updatedAt = saveTime,
        )
        val staleActive = stale.lines[1].copy(position = 0, updatedAt = saveTime)
        val staleEdit = stale.copy(lines = listOf(staleTombstone, staleActive))
        val staleProjection = projectedLine(LINE_2, 0, "Azúcar", 500L).copy(
            productId = requireNotNull(ProductId.parse(PRODUCT_ID)),
            unitId = requireNotNull(UnitId.parse(UNIT_ID)),
            linkConfidence = 940,
        )

        val stalePublication = InvoiceLinesEditPublication(staleEdit, listOf(staleProjection))
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(stalePublication),
        )
        // El retry llega con el mismo link stale, pero la fila tombstone ya no existe en
        // invoice_lines: debe usar el existingEdit canónico y seguir siendo idempotente.
        assertEquals(
            SaveInvoiceLinesEditResult.ALREADY_SAVED,
            reviews.saveIfNewer(stalePublication),
        )

        val persisted = requireNotNull(newRepository().find(DRAFT_ID))
        assertTrue(persisted.lines.all { it.linkedProductId == null })
        assertTrue(persisted.lines.all { it.linkedUnitId == null })
        assertTrue(persisted.lines.all { it.linkConfidence == null })
        assertNull(database.invoiceLineDao().findById(LINE_1.value))
        val projected = requireNotNull(database.invoiceLineDao().findById(LINE_2.value))
        assertNull(projected.productId)
        assertNull(projected.unitId)
        assertNull(projected.linkConfidence)

        // Incluso si el restore se construye desde la copia stale anterior al unlink, el
        // tombstone persistido prevalece para el ID ausente y no resucita el enlace.
        val restoreTime = BASE_TIME.plusSeconds(2)
        val restoredEdit = staleEdit.copy(
            lines = listOf(
                staleTombstone.copy(position = 0, deletedAt = null, updatedAt = restoreTime),
                staleActive.copy(position = 1, updatedAt = restoreTime),
            ),
            revision = 2,
            updatedAt = restoreTime,
        )
        val staleRestoredProjections = listOf(
            projectedLine(LINE_1, 0, "Café", 1_180L).copy(
                productId = requireNotNull(ProductId.parse(PRODUCT_ID)),
                unitId = requireNotNull(UnitId.parse(UNIT_ID)),
                linkConfidence = 940,
            ),
            staleProjection.copy(position = 1, updatedAt = restoreTime),
        )
        assertEquals(
            SaveInvoiceLinesEditResult.SAVED,
            reviews.saveIfNewer(
                InvoiceLinesEditPublication(
                    edit = restoredEdit,
                    projectedLines = staleRestoredProjections,
                    expectedRevision = 1,
                ),
            ),
        )
        val restoredSnapshot = requireNotNull(newRepository().find(DRAFT_ID))
        assertTrue(restoredSnapshot.lines.all { it.linkedProductId == null })
        assertTrue(restoredSnapshot.lines.all { it.linkedUnitId == null })
        assertTrue(restoredSnapshot.lines.all { it.linkConfidence == null })
        database.invoiceLineDao().listForDraft(DRAFT_ID.value).forEach { line ->
            assertNull(line.productId)
            assertNull(line.unitId)
            assertNull(line.linkConfidence)
        }
    }

    @Test
    fun draftOutsideReviewRejectsSnapshotAndProjection() = runBlocking {
        val current = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        database.invoiceDraftDao().update(current.copy(status = DraftStatus.READY_TO_POST.name))

        val result = reviews.saveIfNewer(
            InvoiceLinesEditPublication(
                edit(1, BASE_TIME.plusSeconds(1)),
                listOf(projectedLine(LINE_1, 0, "Café", 1_180L)),
            ),
        )

        assertEquals(SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE, result)
        assertNull(reviews.find(DRAFT_ID))
        assertEquals(2, database.invoiceLineDao().countForDraft(DRAFT_ID.value))
    }

    private fun newRepository() = RoomInvoiceLinesReviewRepository(
        database = database,
        linesEditDao = database.invoiceLinesEditDao(),
        invoiceDraftDao = database.invoiceDraftDao(),
        invoiceLineDao = database.invoiceLineDao(),
        productDao = database.productDao(),
        unitDao = database.unitDao(),
        dispatchers = testDispatchers,
    )

    private suspend fun seedGraph() {
        database.businessDao().insert(
            BusinessEntity(BUSINESS_ID.value, "Negocio Líneas SAC", 1_000, 1_000),
        )
        database.unitDao().insert(
            UnitEntity(UNIT_ID, BUSINESS_ID.value, "NIU", "Unidad", 1_000, 1_000),
        )
        database.productDao().insert(
            ProductEntity(PRODUCT_ID, BUSINESS_ID.value, UNIT_ID, "Café", 1_000, 1_000),
        )
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = DRAFT_ID.value,
                businessId = BUSINESS_ID.value,
                createdAt = BASE_TIME.toEpochMilli(),
                updatedAt = BASE_TIME.toEpochMilli(),
                status = DraftStatus.NEEDS_REVIEW.name,
                currencyCode = "PEN",
            ),
        )
        database.invoiceLineDao().insertAll(
            listOf(
                InvoiceLineEntity(
                    lineId = LINE_1.value,
                    draftId = DRAFT_ID.value,
                    businessId = BUSINESS_ID.value,
                    position = 0,
                    descriptionRaw = "Café OCR",
                    createdAt = BASE_TIME.toEpochMilli(),
                    updatedAt = BASE_TIME.toEpochMilli(),
                    quantity = "2",
                    unitCost = "5.00",
                    unitCostCurrency = "PEN",
                    unitId = UNIT_ID,
                    productId = PRODUCT_ID,
                    lineTotalMinorUnits = 1_000,
                    ocrConfidence = 800,
                    linkConfidence = 940,
                ),
                InvoiceLineEntity(
                    lineId = LINE_2.value,
                    draftId = DRAFT_ID.value,
                    businessId = BUSINESS_ID.value,
                    position = 1,
                    descriptionRaw = "Azúcar OCR",
                    createdAt = BASE_TIME.toEpochMilli(),
                    updatedAt = BASE_TIME.toEpochMilli(),
                    quantity = "1",
                    unitCost = "5.00",
                    unitCostCurrency = "PEN",
                    unitId = UNIT_ID,
                    productId = PRODUCT_ID,
                    lineTotalMinorUnits = 500,
                    ocrConfidence = 900,
                    linkConfidence = 940,
                ),
            ),
        )
    }

    private fun edit(revision: Long, updatedAt: Instant): InvoiceLinesEdit = InvoiceLinesEdit(
        draftId = DRAFT_ID,
        lines = listOf(
            editLine(LINE_1, position = 0, sourcePosition = 0, "Café"),
            editLine(LINE_2, position = 1, sourcePosition = 1, "Azúcar"),
        ).map { it.copy(updatedAt = updatedAt) },
        revision = revision,
        updatedAt = updatedAt,
    )

    private fun editLine(
        id: LineId,
        position: Int,
        sourcePosition: Int,
        description: String,
    ) = InvoiceLineEdit(
        lineId = id,
        position = position,
        origin = InvoiceLineEditOrigin.OCR,
        sourcePosition = sourcePosition,
        ocrRawText = "$description 1 NIU 5.00",
        linkedProductId = com.facturastock.app.domain.model.id.ProductId.parse(PRODUCT_ID),
        linkedUnitId = com.facturastock.app.domain.model.id.UnitId.parse(UNIT_ID),
        linkConfidence = 940,
        description = InvoiceLineEditValue(
            ocr = description,
            selectedSource = InvoiceLineValueSource.OCR,
        ),
        code = InvoiceLineEditValue(
            ocr = "P00${sourcePosition + 1}",
            selectedSource = InvoiceLineValueSource.OCR,
        ),
        quantity = InvoiceLineEditValue(ocr = "1", selectedSource = InvoiceLineValueSource.OCR),
        unit = InvoiceLineEditValue(ocr = "NIU", selectedSource = InvoiceLineValueSource.OCR),
        unitCost = InvoiceLineEditValue(ocr = "5.00", selectedSource = InvoiceLineValueSource.OCR),
        discount = InvoiceLineEditValue(calculated = "1.00", selectedSource = InvoiceLineValueSource.CALCULATED),
        igv = InvoiceLineEditValue(calculated = "1.80", selectedSource = InvoiceLineValueSource.CALCULATED),
        total = InvoiceLineEditValue(ocr = "5.00", selectedSource = InvoiceLineValueSource.OCR),
        confidencePermille = 650,
        requiresReview = true,
        reviewConfirmedByUser = false,
        reviewRequiredFields = setOf(InvoiceLineEditField.TOTAL),
        createdAt = BASE_TIME,
        updatedAt = BASE_TIME,
    )

    private fun projectedLine(
        id: LineId,
        position: Int,
        description: String,
        totalMinor: Long,
    ): InvoiceLine {
        val currency = CurrencyCode.of("PEN")
        return InvoiceLine(
            lineId = id,
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            position = position,
            descriptionRaw = description,
            descriptionNormalized = description,
            codeRaw = "P001",
            codeNormalized = "P001",
            quantity = Quantity.of("2"),
            unitRaw = "UND",
            unitCodeNormalized = "NIU",
            unitCost = UnitCost.of("5.00", currency),
            discount = Money.ofMinor(100, currency),
            tax = Money.ofMinor(180, currency),
            lineTotal = Money.ofMinor(totalMinor, currency),
            createdAt = BASE_TIME,
            updatedAt = BASE_TIME,
        )
    }

    private fun InvoiceLinesEdit.withoutCatalogLinks(): InvoiceLinesEdit = copy(
        lines = lines.map { line ->
            line.copy(linkedProductId = null, linkedUnitId = null, linkConfidence = null)
        },
    )

    private fun InvoiceLinesEdit.withCatalogLink(
        lineId: LineId,
        productId: String,
        unitId: String,
        confidence: Int,
        revision: Long,
        updatedAt: Instant,
    ): InvoiceLinesEdit = copy(
        lines = lines.map { line ->
            if (line.lineId == lineId) {
                line.copy(
                    linkedProductId = requireNotNull(ProductId.parse(productId)),
                    linkedUnitId = requireNotNull(UnitId.parse(unitId)),
                    linkConfidence = confidence,
                    updatedAt = updatedAt,
                )
            } else {
                line.copy(updatedAt = updatedAt)
            }
        },
        revision = revision,
        updatedAt = updatedAt,
    )

    private fun catalogLinkPublication(
        baseline: InvoiceLinesEdit,
        productId: String,
        unitId: String,
    ): InvoiceLinesEditPublication {
        val updatedAt = BASE_TIME.plusSeconds(2)
        val linked = baseline.withCatalogLink(
            lineId = LINE_1,
            productId = productId,
            unitId = unitId,
            confidence = 900,
            revision = 2,
            updatedAt = updatedAt,
        )
        return InvoiceLinesEditPublication(
            edit = linked,
            projectedLines = listOf(
                projectedLine(LINE_1, 0, "Café", 1_180L).copy(
                    productId = requireNotNull(ProductId.parse(productId)),
                    unitId = requireNotNull(UnitId.parse(unitId)),
                    linkConfidence = 900,
                    updatedAt = updatedAt,
                ),
                projectedLine(LINE_2, 1, "Azúcar", 500L).copy(updatedAt = updatedAt),
            ),
            expectedRevision = 1,
            catalogLinkLineIds = setOf(LINE_1),
        )
    }

    private suspend fun assertCatalogLink(
        productId: String?,
        unitId: String?,
        confidence: Int?,
    ) {
        val storedEdit = requireNotNull(reviews.find(DRAFT_ID)).activeLines
            .first { it.lineId == LINE_1 }
        assertEquals(productId, storedEdit.linkedProductId?.value)
        assertEquals(unitId, storedEdit.linkedUnitId?.value)
        assertEquals(confidence, storedEdit.linkConfidence)
        val projected = requireNotNull(database.invoiceLineDao().findById(LINE_1.value))
        assertEquals(productId, projected.productId)
        assertEquals(unitId, projected.unitId)
        assertEquals(confidence, projected.linkConfidence)
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000023"),
        )
        val LINE_1: LineId = LineId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
        )
        val LINE_2: LineId = LineId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000102"),
        )
        const val UNIT_ID: String = "00000000-0000-0000-0000-000000000201"
        const val PRODUCT_ID: String = "00000000-0000-0000-0000-000000000202"
        const val PRODUCT_2_ID: String = "00000000-0000-0000-0000-000000000203"
        const val MISSING_PRODUCT_ID: String = "00000000-0000-0000-0000-000000000204"
        const val SECOND_UNIT_ID: String = "00000000-0000-0000-0000-000000000205"
        const val OTHER_BUSINESS_ID: String = "00000000-0000-0000-0000-000000000301"
        const val OTHER_UNIT_ID: String = "00000000-0000-0000-0000-000000000302"
        const val OTHER_PRODUCT_ID: String = "00000000-0000-0000-0000-000000000303"
    }
}
