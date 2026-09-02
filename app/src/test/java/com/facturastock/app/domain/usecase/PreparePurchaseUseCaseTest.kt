package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.repository.InvoiceHeaderEditPublication
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeInvoiceLinesReviewRepository
import com.facturastock.app.testing.FakePreparedPurchaseRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.RecordingProductionObservability
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreparePurchaseUseCaseTest {
    private val clock = AppClock { Instant.parse("2026-08-08T12:00:00Z") }
    private val businessId = BusinessId.from(uuid(1))
    private val draftId = DraftId.from(uuid(2))
    private val unitId = UnitId.from(uuid(3))
    private val productId = ProductId.from(uuid(4))
    private val pen = CurrencyCode.of("PEN")

    private lateinit var drafts: FakeInvoiceDraftRepository
    private lateinit var headers: FakeHeaderReviewRepository
    private lateinit var lines: FakeInvoiceLinesReviewRepository
    private lateinit var prepared: FakePreparedPurchaseRepository
    private lateinit var products: FakeProductRepository
    private lateinit var units: FakeUnitRepository
    private lateinit var observability: RecordingProductionObservability
    private lateinit var useCase: PreparePurchaseUseCase

    @Before
    fun setUp() {
        drafts = FakeInvoiceDraftRepository(clock)
        headers = FakeHeaderReviewRepository()
        lines = FakeInvoiceLinesReviewRepository(drafts)
        prepared = FakePreparedPurchaseRepository(drafts, clock)
        products = FakeProductRepository(clock)
        units = FakeUnitRepository(clock, products)
        observability = RecordingProductionObservability()
        useCase = PreparePurchaseUseCase(
            invoiceDraftRepository = drafts,
            invoiceHeaderReviewRepository = headers,
            invoiceLinesReviewRepository = lines,
            preparedPurchaseRepository = prepared,
            productRepository = products,
            unitRepository = units,
            appClock = clock,
            observability = observability,
        )
    }

    @Test
    fun `compra valida llega a READY_TO_POST con instantanea congelada`() = runTest {
        seedValidDraft()

        val result = useCase(draftId, roundingAccepted = false)

        val purchase = (result as PreparePurchaseResult.Prepared).purchase
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId)?.status)
        assertEquals(purchase, prepared.find(draftId))
        assertEquals(64, purchase.logicalHash.length)
        assertEquals("20123456789", purchase.supplierRuc)
        assertEquals("F001-123", purchase.documentNumber)
        assertEquals(LocalDate.of(2026, 8, 8), purchase.issueDate)
        assertEquals(1, purchase.lines.size)
        assertEquals(productId, purchase.lines.single().productId)
        assertEquals(Money.ofMinor(1_000, pen), purchase.total)
        assertTrue(purchase.acceptedWarnings.isEmpty())
    }

    @Test
    fun `invalida enumera todos los bloqueos a la vez`() = runTest {
        seedDraft(
            header = validHeader().copy(supplierRuc = null, documentSeries = null),
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(linkedProductId = null),
                    validLine(seed = 11, position = 1).copy(
                        quantity = InvoiceLineEditValue(
                            written = "0",
                            selectedSource = InvoiceLineValueSource.WRITTEN,
                        ),
                    ),
                ),
            ),
        )

        val result = useCase(draftId, roundingAccepted = false)

        val blockers = (result as PreparePurchaseResult.Blocked).blockers
        assertEquals(
            2,
            blockers.count { it.code == PrepareBlockerCode.HEADER_INVALID },
        )
        assertTrue(blockers.any { it.code == PrepareBlockerCode.LINE_PRODUCT_MISSING })
        assertTrue(blockers.any { it.code == PrepareBlockerCode.LINE_QUANTITY_INVALID })
        // El estado no se mueve y no queda instantánea.
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)
        assertNull(prepared.find(draftId))
    }

    @Test
    fun `nota de credito se bloquea antes de congelar la compra`() = runTest {
        seedDraft(
            documentType = PurchaseDocumentType.CREDIT_NOTE,
            header = validHeader().copy(documentType = PurchaseDocumentType.CREDIT_NOTE.name),
            seedCatalog = true,
        )

        val result = useCase(draftId, roundingAccepted = false)

        val blockers = (result as PreparePurchaseResult.Blocked).blockers
        assertTrue(blockers.any { it.code == PrepareBlockerCode.CREDIT_NOTE_UNSUPPORTED })
        assertNull(prepared.find(draftId))
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)
    }

    @Test
    fun `preparar dos veces sin cambios produce la misma logica`() = runTest {
        seedValidDraft()
        val first = useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Prepared

        val second = useCase(draftId, roundingAccepted = false)

        val again = second as PreparePurchaseResult.AlreadyPrepared
        assertEquals(first.purchase.logicalHash, again.purchase.logicalHash)
        assertEquals(first.purchase.preparedAt, again.purchase.preparedAt)
        assertEquals(first.purchase, again.purchase)
    }

    @Test
    fun `redondeo sin aceptacion bloquea y con aceptacion registra la advertencia`() = runTest {
        // Suma de líneas 10.00 frente a total del documento 10.01: un céntimo de redondeo.
        seedDraft(
            draftTotal = Money.ofMinor(1_001, pen),
            header = validHeader().copy(total = "10.01"),
            seedCatalog = true,
        )

        val sinAceptar = useCase(draftId, roundingAccepted = false)
        val bloqueos = (sinAceptar as PreparePurchaseResult.Blocked).blockers
        assertEquals(
            listOf(PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED),
            bloqueos.map { it.code },
        )
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)

        val sinMotivo = useCase(draftId, roundingAccepted = true)
        assertEquals(
            listOf(PrepareBlockerCode.ADJUSTMENT_REASON_REQUIRED),
            (sinMotivo as PreparePurchaseResult.Blocked).blockers.map { it.code },
        )

        val motivo = "Diferencia de redondeo indicada por el comprobante"
        val conAceptacion = useCase(
            draftId = draftId,
            roundingAccepted = true,
            adjustmentReason = motivo,
        )
        val purchase = (conAceptacion as PreparePurchaseResult.Prepared).purchase
        assertEquals(
            listOf("TOTAL_DIFFERENCE", WARNING_LINES_TOTAL_DIFFERENCE),
            purchase.acceptedWarnings,
        )
        assertEquals(1L, purchase.reconciliationAdjustment?.amount?.minorUnits)
        assertEquals(motivo, purchase.reconciliationAdjustment?.reason)
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId)?.status)
        assertEquals(2, observability.records.size)
        assertEquals(OperationalOutcome.BLOCKED, observability.records.first().event.outcome)
        val audit = observability.records.last().event
        assertEquals(OperationalAction.PURCHASE_ADJUSTMENT, audit.action)
        assertEquals(OperationalOutcome.SUCCEEDED, audit.outcome)
        assertEquals(businessId, audit.identifiers.businessId)
        assertEquals(draftId, audit.identifiers.draftId)
        assertTrue(!audit.toString().contains(motivo))
        assertTrue(!audit.toString().contains("10.01"))
    }

    @Test
    fun `volver a editar invalida la preparacion y la nueva preparacion cambia el hash`() = runTest {
        seedValidDraft()
        val first = useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Prepared

        assertEquals(ReopenPreparedPurchaseResult.REOPENED, useCase.reopen(draftId))
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)
        assertNull(prepared.find(draftId))

        // Edición posterior: la línea y la proyección cambian a 12.00.
        lines.seed(
            validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        total = InvoiceLineEditValue(
                            written = "12.00",
                            selectedSource = InvoiceLineValueSource.WRITTEN,
                        ),
                    ),
                ),
            ),
        )
        val draft = drafts.findDraft(draftId)!!
        drafts.updateDraft(draft.copy(total = Money.ofMinor(1_200, pen)))
        headers.stored = validHeader().copy(subtotal = "10.17", igv = "1.83", total = "12.00")

        val second = useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Prepared
        assertNotEquals(first.purchase.logicalHash, second.purchase.logicalHash)
    }

    @Test
    fun `borrador no editable devuelve DraftNotReady`() = runTest {
        seedValidDraft(status = DraftStatus.CAPTURED)

        assertEquals(PreparePurchaseResult.DraftNotReady, useCase(draftId, roundingAccepted = true))
    }

    @Test
    fun `sin lineas activas bloquea con NO_LINES`() = runTest {
        seedDraft(linesEdit = validLines().copy(lines = emptyList()))

        val result = useCase(draftId, roundingAccepted = false)

        val blockers = (result as PreparePurchaseResult.Blocked).blockers
        assertTrue(blockers.any { it.code == PrepareBlockerCode.NO_LINES })
    }

    @Test
    fun `linea sin importe total bloquea la preparacion`() = runTest {
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        total = InvoiceLineEditValue(),
                    ),
                ),
            ),
        )

        val result = useCase(draftId, roundingAccepted = false)

        val blockers = (result as PreparePurchaseResult.Blocked).blockers
        assertTrue(
            blockers.any {
                it.code == PrepareBlockerCode.LINE_AMOUNT_INVALID && it.detail == "TOTAL"
            },
        )
    }

    @Test
    fun `linea sin costo unitario no puede congelarse para publicacion`() = runTest {
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        unitCost = InvoiceLineEditValue(),
                    ),
                ),
            ),
        )

        val result = useCase(draftId, roundingAccepted = false)

        val blockers = (result as PreparePurchaseResult.Blocked).blockers
        assertTrue(
            blockers.any {
                it.code == PrepareBlockerCode.LINE_AMOUNT_INVALID && it.detail == "UNIT_COST"
            },
        )
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)
        assertNull(prepared.find(draftId))
    }

    @Test
    fun `cabecera sin tipo documental no puede congelar identidad de comprobante`() = runTest {
        seedDraft(
            seedCatalog = true,
            header = validHeader().copy(documentType = null),
        )

        val result = useCase(draftId, roundingAccepted = false)

        val blockers = (result as PreparePurchaseResult.Blocked).blockers
        assertTrue(
            blockers.any {
                it.code == PrepareBlockerCode.HEADER_INVALID && it.detail == "DOCUMENT_TYPE"
            },
        )
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)
        assertNull(prepared.find(draftId))
    }

    @Test
    fun `tipo documental revisado completa una proyeccion de borrador aun vacia`() = runTest {
        seedValidDraft()
        val current = requireNotNull(drafts.findDraft(draftId))
        drafts.updateDraft(current.copy(documentType = null))

        val result = useCase(draftId, roundingAccepted = false)

        assertEquals(
            PurchaseDocumentType.INVOICE,
            (result as PreparePurchaseResult.Prepared).purchase.documentType,
        )
    }

    @Test
    fun `linea pendiente de confirmacion bloquea aunque sus campos sean validos`() = runTest {
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        reviewConfirmedByUser = false,
                        requiresReview = true,
                    ),
                ),
            ),
        )

        val result = useCase(draftId, roundingAccepted = false)

        val blockers = (result as PreparePurchaseResult.Blocked).blockers
        assertTrue(blockers.any { it.code == PrepareBlockerCode.LINE_REVIEW_PENDING })
    }

    @Test
    fun `producto inexistente o unidad ajena no se consideran resueltos`() = runTest {
        seedDraft(seedCatalog = false)

        val missing = useCase(draftId, roundingAccepted = false)

        assertTrue(
            (missing as PreparePurchaseResult.Blocked).blockers.any {
                it.code == PrepareBlockerCode.LINE_PRODUCT_INVALID
            },
        )

        seedCatalog()
        products.update(requireNotNull(products.findById(productId)).copy(status = CatalogStatus.ARCHIVED))
        val archived = useCase(draftId, roundingAccepted = false)

        assertTrue(
            (archived as PreparePurchaseResult.Blocked).blockers.any {
                it.code == PrepareBlockerCode.LINE_PRODUCT_INVALID
            },
        )
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)
        assertNull(prepared.find(draftId))
    }

    @Test
    fun `borrador legacy exige volver a vincular procedencia antes de preparar`() = runTest {
        val legacyLine = validLine(seed = 10, position = 0).copy(
            productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
        )
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(lines = listOf(legacyLine)),
        )

        assertTrue(legacyLine.isPending)
        val blocked = useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Blocked
        assertTrue(blocked.blockers.any { blocker ->
            blocker.code == PrepareBlockerCode.LINE_PRODUCT_PROVENANCE_REQUIRED &&
                blocker.lineId == legacyLine.lineId
        })
        assertNull(prepared.find(draftId))

        lines.seed(
            validLines().copy(
                lines = listOf(
                    legacyLine.copy(productProvenance = PurchaseProductProvenance.EXISTING),
                ),
                revision = 2L,
            ),
        )

        val relinked = useCase(draftId, roundingAccepted = false)
        assertTrue(relinked is PreparePurchaseResult.Prepared)
        assertEquals(
            PurchaseProductProvenance.EXISTING,
            (relinked as PreparePurchaseResult.Prepared).purchase.lines.single().productProvenance,
        )
    }

    @Test
    fun `producto staged sin precio confirmado no puede prepararse`() = runTest {
        units.create(
            UnitOfMeasure(
                unitId = unitId,
                businessId = businessId,
                code = "NIU",
                name = "Unidad",
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )
        val staged = StagedPurchaseProduct(
            productId = productId,
            businessId = businessId,
            unitId = unitId,
            name = "Arroz staged legacy",
            salePrice = null,
        )
        val line = validLine(seed = 10, position = 0).copy(
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )
        seedDraft(linesEdit = validLines().copy(lines = listOf(line)))

        val result = useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Blocked

        assertTrue(result.blockers.any { blocker ->
            blocker.code == PrepareBlockerCode.LINE_SALE_PRICE_REQUIRED &&
                blocker.lineId == line.lineId
        })
        assertTrue(result.blockers.none { it.code == PrepareBlockerCode.LINE_PRODUCT_INVALID })
        assertNull(prepared.find(draftId))
    }

    @Test
    fun `preparar no toca el catalogo ni mueve stock`() = runTest {
        seedValidDraft()
        val catalogBefore = products.observeForBusiness(businessId).first()

        useCase(draftId, roundingAccepted = false)

        assertEquals(catalogBefore, products.observeForBusiness(businessId).first())
    }

    @Test
    fun `preparacion congela tratamiento incluido y evidencia exacta`() = runTest {
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        taxTreatment = InventoryTaxTreatment.INCLUDED,
                        igv = written("1.53"),
                    ),
                ),
            ),
        )

        val purchase = (
            useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Prepared
            ).purchase
        val line = purchase.lines.single()

        assertEquals(InventoryTaxTreatment.INCLUDED, line.taxTreatment)
        assertEquals(
            InventoryTaxEvidence.ExplicitAmount(java.math.BigDecimal("1.53")),
            line.taxEvidence,
        )
    }

    @Test
    fun `preparacion congela exonerado sin inventar evidencia`() = runTest {
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        taxTreatment = InventoryTaxTreatment.EXEMPT,
                        igv = written("0"),
                    ),
                ),
            ),
        )

        val line = (
            useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Prepared
            ).purchase.lines.single()

        assertEquals(InventoryTaxTreatment.EXEMPT, line.taxTreatment)
        assertEquals(InventoryTaxEvidence.None, line.taxEvidence)
    }

    @Test
    fun `tratamiento desconocido bloquea la preparacion y exige revision`() = runTest {
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        taxTreatment = InventoryTaxTreatment.UNKNOWN,
                    ),
                ),
            ),
        )

        val result = useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Blocked

        assertTrue(result.blockers.any { blocker ->
            blocker.code == PrepareBlockerCode.LINE_TAX_DECISION_REQUIRED
        })
        assertNull(prepared.find(draftId))
    }

    @Test
    fun `incluido o excluido sin IGV no fabrican evidencia cero`() = runTest {
        seedDraft(
            seedCatalog = true,
            linesEdit = validLines().copy(
                lines = listOf(
                    validLine(seed = 10, position = 0).copy(
                        taxTreatment = InventoryTaxTreatment.INCLUDED,
                        igv = InvoiceLineEditValue(),
                    ),
                ),
            ),
        )

        val missing = useCase(draftId, roundingAccepted = false) as PreparePurchaseResult.Blocked

        assertTrue(missing.blockers.any { blocker ->
            blocker.code == PrepareBlockerCode.LINE_TAX_DECISION_REQUIRED &&
                blocker.detail == "TAX_EVIDENCE_REQUIRED"
        })
        assertNull(prepared.find(draftId))
    }

    // --- Fixtures ---

    private suspend fun seedValidDraft(status: DraftStatus = DraftStatus.NEEDS_REVIEW) =
        seedDraft(status = status, seedCatalog = true)

    private suspend fun seedDraft(
        status: DraftStatus = DraftStatus.NEEDS_REVIEW,
        draftTotal: Money = Money.ofMinor(1_000, pen),
        documentType: PurchaseDocumentType = PurchaseDocumentType.INVOICE,
        header: InvoiceHeaderEdit = validHeader(),
        linesEdit: InvoiceLinesEdit = validLines(),
        seedCatalog: Boolean = false,
    ) {
        if (seedCatalog) seedCatalog()
        drafts.createDraft(
            InvoiceDraft(
                draftId = draftId,
                businessId = businessId,
                status = status,
                supplierRucNormalized = "20123456789",
                supplierLegalNameNormalized = "PROVEEDOR SA",
                documentType = documentType,
                documentNumberNormalized = "F001-123",
                issueDate = LocalDate.of(2026, 8, 8),
                currency = pen,
                subtotal = Money.ofMinor(847, pen),
                tax = Money.ofMinor(153, pen),
                total = draftTotal,
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )
        headers.stored = header
        lines.seed(linesEdit)
    }

    private suspend fun seedCatalog() {
        if (units.findById(unitId) == null) {
            units.create(
                UnitOfMeasure(
                    unitId = unitId,
                    businessId = businessId,
                    code = "NIU",
                    name = "Unidad",
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
        }
        if (products.findById(productId) == null) {
            products.create(
                Product(
                    productId = productId,
                    businessId = businessId,
                    unitId = unitId,
                    name = "Arroz",
                    status = CatalogStatus.ACTIVE,
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
        }
    }

    private fun validHeader(): InvoiceHeaderEdit = InvoiceHeaderEdit(
        draftId = draftId,
        supplierRuc = "20123456789",
        supplierLegalName = "Proveedor SA",
        documentType = PurchaseDocumentType.INVOICE.name,
        documentSeries = "F001",
        documentNumber = "123",
        issueDate = "08/08/2026",
        currency = "PEN",
        subtotal = "8.47",
        igv = "1.53",
        total = "10.00",
        revision = 1L,
        updatedAt = clock.now(),
    )

    private fun validLines(): InvoiceLinesEdit = InvoiceLinesEdit(
        draftId = draftId,
        lines = listOf(validLine(seed = 10, position = 0)),
        revision = 1L,
        updatedAt = clock.now(),
    )

    private fun validLine(seed: Int, position: Int): InvoiceLineEdit = InvoiceLineEdit(
        lineId = LineId.from(uuid(seed)),
        position = position,
        origin = InvoiceLineEditOrigin.USER,
        linkedProductId = productId,
        linkedUnitId = unitId,
        linkConfidence = 1_000,
        taxTreatment = InventoryTaxTreatment.EXCLUDED,
        productProvenance = PurchaseProductProvenance.EXISTING,
        description = written("Arroz"),
        quantity = written("2"),
        unitCost = written("5.00"),
        igv = written("0"),
        total = written("10.00"),
        reviewConfirmedByUser = true,
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    private fun written(value: String) = InvoiceLineEditValue(
        written = value,
        selectedSource = InvoiceLineValueSource.WRITTEN,
    )

    private class FakeHeaderReviewRepository : InvoiceHeaderReviewRepository {
        var stored: InvoiceHeaderEdit? = null

        override suspend fun find(draftId: DraftId): InvoiceHeaderEdit? =
            stored?.takeIf { it.draftId == draftId }

        override suspend fun saveIfNewer(
            publication: InvoiceHeaderEditPublication,
        ): SaveInvoiceHeaderEditResult {
            stored = publication.edit
            return SaveInvoiceHeaderEditResult.SAVED
        }
    }

    private companion object {
        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
