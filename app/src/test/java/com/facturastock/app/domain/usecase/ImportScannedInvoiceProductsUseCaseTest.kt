package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.normalization.CalculatedInvoiceTotals
import com.facturastock.app.domain.normalization.Candidate
import com.facturastock.app.domain.normalization.CandidateEvidence
import com.facturastock.app.domain.normalization.HeaderField
import com.facturastock.app.domain.normalization.InvoiceHeaderParseResult
import com.facturastock.app.domain.normalization.InvoiceLineItemsParseResult
import com.facturastock.app.domain.normalization.InvoiceTotalsParseResult
import com.facturastock.app.domain.normalization.InvoiceUnitCode
import com.facturastock.app.domain.normalization.ParsedInvoice
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ParsedInvoiceLineItem
import com.facturastock.app.domain.normalization.ReadInvoiceTotals
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeUnitRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportScannedInvoiceProductsUseCaseTest {
    @Test
    fun `imports only safe rows, deduplicates names, and falls back to NIU`() = runTest {
        val fixture = Fixture()
        fixture.seedBusinessAndDraft()
        val niu = fixture.seedUnit("NIU")
        val kgm = fixture.seedUnit("KGM")
        val parsed = parsedInvoice(
            line(name = "Arroz premium", position = 0, unit = InvoiceUnitCode.KGM),
            line(name = " arroz PREMIUM ", position = 1, signal = Signal.CODE),
            line(name = "Aceite vegetal", position = 2, signal = Signal.CODE, unit = InvoiceUnitCode.LTR),
            line(name = "Proveedor Ejemplo", position = 3, signal = Signal.NONE),
            line(name = "Azúcar", position = 4, descriptionConfidence = 899),
        )

        val result = fixture.useCase(DRAFT_ID, parsed)

        val success = result as ImportScannedInvoiceProductsResult.Success
        assertEquals(
            ScannedInvoiceProductImportCounts(
                scannedLineCount = 5,
                eligibleProductCount = 3,
                importedCount = 2,
                alreadyExistingCount = 0,
                duplicateLineCount = 1,
                skippedLineCount = 2,
            ),
            success.counts,
        )
        val stored = fixture.products.observeForBusiness(BUSINESS_ID).first().associateBy { it.name }
        assertEquals(kgm.unitId, stored.getValue("Arroz premium").unitId)
        // LTR era OCR seguro, pero no está configurada: la política cerrada usa NIU activa.
        assertEquals(niu.unitId, stored.getValue("Aceite vegetal").unitId)
        assertTrue(stored.values.all { product ->
            product.sku == null && product.barcode == null && product.locationId == null &&
                product.purchaseUnitId == null && product.salePrice == null
        })
        assertNull(fixture.drafts.findDraft(DRAFT_ID))
        assertEquals(listOf(DRAFT_ID), fixture.files.draftTreeDeletions)
    }

    @Test
    fun `counts an existing normalized name without changing it and imports the remainder`() = runTest {
        val fixture = Fixture()
        fixture.seedBusinessAndDraft()
        val niu = fixture.seedUnit("NIU")
        val existing = fixture.products.create(
            product(name = "ARROZ", unitId = niu.unitId, productId = productId(90)),
        )

        val result = fixture.useCase(
            DRAFT_ID,
            parsedInvoice(
                line(name = " arroz ", position = 0),
                line(name = "Leche", position = 1),
            ),
        )

        val success = result as ImportScannedInvoiceProductsResult.Success
        assertEquals(1, success.counts.importedCount)
        assertEquals(1, success.counts.alreadyExistingCount)
        val stored = fixture.products.observeForBusiness(BUSINESS_ID).first()
        assertEquals(2, stored.size)
        assertEquals(existing.productId, stored.single { it.name == "ARROZ" }.productId)
        assertNull(fixture.drafts.findDraft(DRAFT_ID))
    }

    @Test
    fun `all names already existing is an idempotent success that cleans up the draft`() = runTest {
        val fixture = Fixture()
        fixture.seedBusinessAndDraft()
        val niu = fixture.seedUnit("NIU")
        fixture.products.create(
            product(name = "Café", unitId = niu.unitId, productId = productId(91)),
        )
        fixture.products.create(
            product(name = "Pan", unitId = niu.unitId, productId = productId(92)),
        )

        val result = fixture.useCase(
            DRAFT_ID,
            parsedInvoice(
                line(name = " café ", position = 0),
                line(name = "PAN", position = 1),
            ),
        )

        val success = result as ImportScannedInvoiceProductsResult.Success
        assertEquals(0, success.counts.importedCount)
        assertEquals(2, success.counts.alreadyExistingCount)
        assertEquals(2, fixture.products.observeForBusiness(BUSINESS_ID).first().size)
        assertNull(fixture.drafts.findDraft(DRAFT_ID))
        assertEquals(listOf(DRAFT_ID), fixture.files.draftTreeDeletions)
    }

    @Test
    fun `no safe product is a closed failure and preserves the draft`() = runTest {
        val fixture = Fixture()
        fixture.seedBusinessAndDraft()
        fixture.seedUnit("NIU")

        val result = fixture.useCase(
            DRAFT_ID,
            parsedInvoice(line(name = "Texto suelto", position = 0, signal = Signal.NONE)),
        )

        val failure = result as ImportScannedInvoiceProductsResult.Failure
        assertEquals(ScannedInvoiceProductImportError.NO_ELIGIBLE_PRODUCTS, failure.error)
        assertEquals(0, failure.counts.eligibleProductCount)
        assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
        assertTrue(fixture.products.observeForBusiness(BUSINESS_ID).first().isEmpty())
    }

    @Test
    fun `fails closed when neither the OCR unit nor NIU is active`() = runTest {
        val fixture = Fixture()
        fixture.seedBusinessAndDraft()
        fixture.seedUnit("KGM", status = CatalogStatus.ARCHIVED)

        val result = fixture.useCase(
            DRAFT_ID,
            parsedInvoice(line(name = "Harina", position = 0, unit = InvoiceUnitCode.KGM)),
        )

        val failure = result as ImportScannedInvoiceProductsResult.Failure
        assertEquals(ScannedInvoiceProductImportError.UNIT_UNAVAILABLE, failure.error)
        assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
        assertTrue(fixture.products.observeForBusiness(BUSINESS_ID).first().isEmpty())
    }

    @Test
    fun `maps catalog storage conflicts and does not discard a retryable draft`() = runTest {
        val fixture = Fixture()
        fixture.seedBusinessAndDraft()
        fixture.seedUnit("NIU")
        fixture.products.nextFailure = StorageError.ConstraintConflict("fixture")

        val result = fixture.useCase(
            DRAFT_ID,
            parsedInvoice(line(name = "Conserva", position = 0)),
        )

        val failure = result as ImportScannedInvoiceProductsResult.Failure
        assertEquals(ScannedInvoiceProductImportError.CATALOG_CONFLICT, failure.error)
        assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
        assertTrue(fixture.products.observeForBusiness(BUSINESS_ID).first().isEmpty())
    }

    @Test
    fun `rejects a draft that does not belong to the configured active business`() = runTest {
        val fixture = Fixture()
        fixture.seedBusinessAndDraft(draftBusinessId = OTHER_BUSINESS_ID)
        fixture.seedUnit("NIU")

        val result = fixture.useCase(
            DRAFT_ID,
            parsedInvoice(line(name = "Producto", position = 0)),
        )

        val failure = result as ImportScannedInvoiceProductsResult.Failure
        assertEquals(ScannedInvoiceProductImportError.BUSINESS_MISMATCH, failure.error)
        assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
    }

    private class Fixture {
        private val clock = AppClock { NOW }
        private val configuration = FakeAppConfigurationRepository()
        private val businesses = FakeBusinessRepository(clock)
        val products = FakeProductRepository(clock)
        private val units = FakeUnitRepository(clock, products)
        val drafts = FakeInvoiceDraftRepository(clock)
        val files = FakeDraftFileStore()
        private var generatedId = 100L
        private val uuidGenerator = UuidGenerator { UUID(0L, generatedId++) }
        val useCase = ImportScannedInvoiceProductsUseCase(
            appConfigurationRepository = configuration,
            businessRepository = businesses,
            invoiceDraftRepository = drafts,
            productRepository = products,
            unitRepository = units,
            uuidGenerator = uuidGenerator,
            appClock = clock,
            deleteDraftUseCase = DeleteDraftUseCase(drafts, files),
        )

        suspend fun seedBusinessAndDraft(draftBusinessId: BusinessId = BUSINESS_ID) {
            businesses.create(
                Business(
                    businessId = BUSINESS_ID,
                    legalName = "Negocio activo",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            configuration.completeOnboarding(
                BUSINESS_ID,
                AppConfiguration.DEFAULT_TAX_RATE,
                CostPolicy.NET,
            )
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = draftBusinessId,
                    status = DraftStatus.NEEDS_REVIEW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
        }

        suspend fun seedUnit(
            code: String,
            status: CatalogStatus = CatalogStatus.ACTIVE,
        ): UnitOfMeasure = units.create(
            UnitOfMeasure(
                unitId = unitId(code),
                businessId = BUSINESS_ID,
                code = code,
                name = code,
                status = status,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
    }

    private enum class Signal {
        QUANTITY,
        CODE,
        NONE,
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-02T12:00:00Z")
        val BUSINESS_ID: BusinessId = businessId(1)
        val OTHER_BUSINESS_ID: BusinessId = businessId(2)
        val DRAFT_ID: DraftId = draftId(1)
        val RUN_ID: OcrRunId = ocrRunId(1)

        fun parsedInvoice(vararg lines: ParsedInvoiceLineItem): ParsedInvoice = ParsedInvoice(
            header = InvoiceHeaderParseResult(
                draftId = DRAFT_ID,
                runId = RUN_ID,
                documentType = HeaderField(),
                issuerRuc = HeaderField(),
                issuerLegalName = HeaderField(),
                documentNumber = HeaderField(),
                issueDate = HeaderField(),
                currency = HeaderField(),
            ),
            lineItems = InvoiceLineItemsParseResult(
                draftId = DRAFT_ID,
                runId = RUN_ID,
                currency = null,
                items = lines.toList(),
            ),
            totals = InvoiceTotalsParseResult(
                draftId = DRAFT_ID,
                runId = RUN_ID,
                currency = null,
                referenceIgvRate = AppConfiguration.DEFAULT_TAX_RATE,
                taxRoundingMode = null,
                read = ReadInvoiceTotals(),
                calculated = CalculatedInvoiceTotals(),
                reconciliation = null,
            ),
            audit = ParsedInvoiceAudit(
                draftId = DRAFT_ID,
                runId = RUN_ID,
                parserVersion = 1,
                contextFingerprint = "0".repeat(64),
                confidence = ParsedInvoiceConfidence.HIGH,
                fields = emptyList(),
                warnings = emptyList(),
                blockers = emptyList(),
            ),
        )

        fun line(
            name: String,
            position: Int,
            signal: Signal = Signal.QUANTITY,
            descriptionConfidence: Int = 950,
            unit: InvoiceUnitCode? = null,
        ): ParsedInvoiceLineItem {
            val rowEvidence = evidence("$name 1")
            return ParsedInvoiceLineItem(
                position = position,
                pageIndex = 0,
                rawText = "$name 1",
                evidence = listOf(rowEvidence),
                boundingBox = null,
                code = if (signal == Signal.CODE) candidate("COD-$position", 950) else null,
                description = candidate(name, descriptionConfidence),
                quantity = if (signal == Signal.QUANTITY) candidate(Quantity.of("1"), 950) else null,
                unit = unit?.let { candidate(it, 950) },
            )
        }

        fun <T : Any> candidate(value: T, confidence: Int): Candidate<T> = Candidate(
            value = value,
            evidence = evidence(value.toString()),
            boundingBox = null,
            confidencePermille = confidence,
        )

        fun evidence(rawText: String): CandidateEvidence = CandidateEvidence(
            rawText = rawText,
            unicodeText = rawText,
            normalizedText = rawText,
            comparisonText = rawText.lowercase(),
        )

        fun product(name: String, unitId: UnitId, productId: ProductId) =
            com.facturastock.app.domain.model.Product(
                productId = productId,
                businessId = BUSINESS_ID,
                unitId = unitId,
                name = name,
                createdAt = NOW,
                updatedAt = NOW,
            )

        fun businessId(value: Long): BusinessId = BusinessId.from(UUID(0L, value))
        fun draftId(value: Long): DraftId = DraftId.from(UUID(0L, value))
        fun ocrRunId(value: Long): OcrRunId = OcrRunId.from(UUID(0L, value))
        fun productId(value: Long): ProductId = ProductId.from(UUID(0L, value))
        fun unitId(code: String): UnitId = UnitId.from(
            UUID.nameUUIDFromBytes("unit-$code".toByteArray()),
        )
    }
}
