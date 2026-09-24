package com.facturastock.app.demo

import java.io.File
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.demo.DemoInvoiceFixture
import com.facturastock.app.data.demo.DemoInvoiceImageGenerator
import com.facturastock.app.data.files.LocalDraftFileStore
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.repository.RoomBusinessRepository
import com.facturastock.app.data.repository.RoomInventoryReadRepository
import com.facturastock.app.data.repository.RoomInventoryLocationRepository
import com.facturastock.app.data.repository.RoomInvoiceDraftRepository
import com.facturastock.app.data.repository.RoomInvoiceHeaderReviewRepository
import com.facturastock.app.data.repository.RoomInvoiceLinesReviewRepository
import com.facturastock.app.data.repository.RoomInvoiceOcrSnapshotRepository
import com.facturastock.app.data.repository.RoomParsedInvoiceRepository
import com.facturastock.app.data.repository.RoomPreparedPurchaseRepository
import com.facturastock.app.data.repository.RoomProductRepository
import com.facturastock.app.data.repository.RoomPurchasePostingRepository
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.data.sync.NoOpPurchaseBackupScheduler
import com.facturastock.app.data.repository.RoomPurchaseReadRepository
import com.facturastock.app.data.repository.RoomSupplierProductAliasRepository
import com.facturastock.app.data.repository.RoomSupplierRepository
import com.facturastock.app.data.repository.RoomUnitRepository
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.DemoInvoiceSource
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.DraftImageImporter
import com.facturastock.app.domain.repository.ImportedImageFile
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile
import com.facturastock.app.domain.repository.PurchaseConfirmationBlocker
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import com.facturastock.app.domain.usecase.ConfirmPurchaseUseCase
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterConfirmUseCase
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterOcrUseCase
import com.facturastock.app.domain.usecase.CreateLinkedProductResult
import com.facturastock.app.domain.usecase.CreateLinkedProductUseCase
import com.facturastock.app.domain.usecase.EnterDemoModeUseCase
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.LoadInvoiceHeaderReviewUseCase
import com.facturastock.app.domain.usecase.LoadInvoiceLinesReviewUseCase
import com.facturastock.app.domain.usecase.NewLinkedProduct
import com.facturastock.app.domain.usecase.ObserveInventoryUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseDetailUseCase
import com.facturastock.app.domain.usecase.ObservePurchasesUseCase
import com.facturastock.app.domain.usecase.ParseInvoiceUseCase
import com.facturastock.app.domain.usecase.PreparePurchaseResult
import com.facturastock.app.domain.usecase.PreparePurchaseUseCase
import com.facturastock.app.domain.usecase.ProductMatchOutcome
import com.facturastock.app.domain.usecase.ProductMatchQuery
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.domain.usecase.RunInvoiceOcrUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceHeaderEditUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceLinesEditUseCase
import com.facturastock.app.domain.usecase.StartDemoInvoiceScenarioResult
import com.facturastock.app.domain.usecase.StartDemoInvoiceScenarioUseCase
import com.facturastock.app.domain.usecase.WARNING_LINES_TOTAL_DIFFERENCE
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Recorrido de demostración sin datos reales y con las mismas fronteras que usa la aplicación.
 * Solo la captura y el motor OCR son dobles deterministas: todo lo que sigue, incluido el
 * parser, los autosaves de revisión, la preparación y el posting, usa producción y Room real.
 * La base vive en un archivo de test para poder cerrar Room a mitad de la revisión y continuar
 * el mismo journey con repositorios recién construidos, como después de una muerte de proceso.
 */
class DemoInvoiceEndToEndTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private val directories by lazy { AppDirectories(tempFolder.newFolder("app")) }
    private lateinit var configuration: DemoConfigurationRepository

    @Before
    fun setUp() = runBlocking {
        database = openDatabase()
        configuration = DemoConfigurationRepository()
        val clock = AppClock { NOW }
        val seededBusinessId = EnterDemoModeUseCase(
            businessRepository = RoomBusinessRepository(
                database.businessDao(),
                TEST_DISPATCHERS,
                clock,
            ),
            supplierRepository = RoomSupplierRepository(
                database,
                database.supplierDao(),
                TEST_DISPATCHERS,
                clock,
            ),
            unitRepository = RoomUnitRepository(database.unitDao(), TEST_DISPATCHERS, clock),
            inventoryLocationRepository = RoomInventoryLocationRepository(
                database,
                TEST_DISPATCHERS,
                clock,
            ),
            productRepository = RoomProductRepository(
                database,
                database.productDao(),
                TEST_DISPATCHERS,
                clock,
            ),
            appConfigurationRepository = configuration,
            uuidGenerator = UuidGenerator { uuid(1) },
            appClock = clock,
        )()
        assertEquals(BUSINESS_ID, seededBusinessId)
        assertEquals(BUSINESS_ID, configuration.current().activeBusinessId)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun openDatabase(): FacturaStockDatabase =
        FacturaStockDatabase.buildAt(File(tempFolder.root, DATABASE_NAME))

    @Test
    fun synthetic38LineInvoiceSurvivesReviewRestartAndConcurrentDoubleTapPostsStockOnce() =
        runBlocking {
            val clock = AppClock { NOW }
            val uuidGenerator = SequentialUuidGenerator(1_000L)
            val drafts = RoomInvoiceDraftRepository(
                database = database,
                invoiceDraftDao = database.invoiceDraftDao(),
                invoiceImageDao = database.invoiceImageDao(),
                invoiceLineDao = database.invoiceLineDao(),
                dispatchers = TEST_DISPATCHERS,
                clock = clock,
            )
            val demoInvoiceJpeg = DemoInvoiceImageGenerator.jpegBytes()
            val importer = RecordingImageImporter(demoInvoiceJpeg)
            val importDraftImage = ImportDraftImageUseCase(
                appConfigurationRepository = configuration,
                invoiceDraftRepository = drafts,
                draftImageImporter = importer,
                draftFileStore = NoOpDraftFileStore,
                uuidGenerator = uuidGenerator,
            )
            val started = StartDemoInvoiceScenarioUseCase(
                appConfigurationRepository = configuration,
                invoiceDraftRepository = drafts,
                demoInvoiceSource = DemoInvoiceSource { demoInvoiceJpeg },
                importDraftImageUseCase = importDraftImage,
                appClock = clock,
            )()
            assertTrue(started is StartDemoInvoiceScenarioResult.Ready)
            val ready = started as StartDemoInvoiceScenarioResult.Ready
            assertEquals(DRAFT_ID, ready.draftId)
            assertEquals(DemoPurchaseScenario.imageIdFor(BUSINESS_ID).value, ready.captureId.value)
            val imported = requireNotNull(
                drafts.findImage(DemoPurchaseScenario.imageIdFor(BUSINESS_ID)),
            )
            assertEquals(1, importer.captureCount)
            assertEquals(0, imported.pageIndex)
            assertEquals(1, drafts.observeImages(DRAFT_ID).first().size)

            val preprocessor = RecordingPreprocessor()
            val recognizer = DemoInvoiceRecognizer()
            val ocrSnapshots = RoomInvoiceOcrSnapshotRepository(
                database,
                database.invoiceOcrSnapshotDao(),
                TEST_DISPATCHERS,
            )
            val draftFiles = LocalDraftFileStore(directories)
            val ocrResult = RunInvoiceOcrUseCase(
                invoiceDraftRepository = drafts,
                invoiceImagePreprocessor = preprocessor,
                invoiceTextRecognizer = recognizer,
                invoiceOcrSnapshotRepository = ocrSnapshots,
                uuidGenerator = uuidGenerator,
                appClock = clock,
                applyImageRetentionAfterOcr = ApplyImageRetentionAfterOcrUseCase(
                    configuration,
                    draftFiles,
                    drafts,
                ),
            )(DRAFT_ID)
            assertEquals(1, ocrResult.pageCount)
            assertEquals(1, preprocessor.calls)
            assertEquals(1, recognizer.calls)

            val parsedInvoices = RoomParsedInvoiceRepository(
                database = database,
                parsedInvoiceDao = database.parsedInvoiceDao(),
                invoiceDraftDao = database.invoiceDraftDao(),
                invoiceLineDao = database.invoiceLineDao(),
                invoiceOcrSnapshotDao = database.invoiceOcrSnapshotDao(),
                dispatchers = TEST_DISPATCHERS,
            )
            val parsed = ParseInvoiceUseCase(
                invoiceDraftRepository = drafts,
                invoiceOcrSnapshotRepository = ocrSnapshots,
                parsedInvoiceRepository = parsedInvoices,
                appConfigurationRepository = configuration,
                businessRepository = RoomBusinessRepository(
                    database.businessDao(),
                    TEST_DISPATCHERS,
                    clock,
                ),
                appClock = clock,
                dispatcherProvider = TEST_DISPATCHERS,
            )(DRAFT_ID)
            assertEquals(LINE_COUNT, parsed.lineItems.items.size)
            assertEquals(SUPPLIER_RUC, parsed.header.issuerRuc.selected?.value)
            assertEquals(
                DemoInvoiceFixture.SUPPLIER_LEGAL_NAME,
                parsed.header.issuerLegalName.selected?.value,
            )
            assertEquals(LINE_COUNT, drafts.observeLines(DRAFT_ID).first().size)

            val headerReviews = RoomInvoiceHeaderReviewRepository(
                database,
                database.invoiceHeaderEditDao(),
                database.invoiceDraftDao(),
                TEST_DISPATCHERS,
            )
            val headerSnapshot = requireNotNull(
                LoadInvoiceHeaderReviewUseCase(drafts, headerReviews, parsedInvoices)(DRAFT_ID),
            )
            val reviewedHeader = headerSnapshot.edit.copy(
                revision = 1L,
                touchedFields = InvoiceHeaderEditField.entries.toSet(),
                updatedAt = NOW,
            )
            assertEquals(
                SaveInvoiceHeaderEditResult.SAVED,
                SaveInvoiceHeaderEditUseCase(drafts, headerReviews)(reviewedHeader),
            )

            val lineReviews = RoomInvoiceLinesReviewRepository(
                database = database,
                linesEditDao = database.invoiceLinesEditDao(),
                invoiceDraftDao = database.invoiceDraftDao(),
                invoiceLineDao = database.invoiceLineDao(),
                productDao = database.productDao(),
                unitDao = database.unitDao(),
                dispatchers = TEST_DISPATCHERS,
            )
            val loadLines = LoadInvoiceLinesReviewUseCase(
                drafts,
                lineReviews,
                parsedInvoices,
                clock,
            )
            val baselineReview = requireNotNull(loadLines(DRAFT_ID))
            assertEquals(LINE_COUNT, baselineReview.edit.activeLines.size)

            val products = RoomProductRepository(
                database,
                database.productDao(),
                TEST_DISPATCHERS,
                clock,
            )
            val suppliers = RoomSupplierRepository(
                database,
                database.supplierDao(),
                TEST_DISPATCHERS,
                clock,
            )
            val supplierId = requireNotNull(suppliers.findByRuc(BUSINESS_ID, SUPPLIER_RUC))
                .supplierId
            val aliases = RoomSupplierProductAliasRepository(
                database.supplierProductAliasDao(),
                TEST_DISPATCHERS,
                clock,
            )
            val units = RoomUnitRepository(database.unitDao(), TEST_DISPATCHERS, clock)
            val preparedPurchases = RoomPreparedPurchaseRepository(
                database,
                database.preparedPurchaseDao(),
                TEST_DISPATCHERS,
                clock,
            )
            val prepare = PreparePurchaseUseCase(
                invoiceDraftRepository = drafts,
                invoiceHeaderReviewRepository = headerReviews,
                invoiceLinesReviewRepository = lineReviews,
                preparedPurchaseRepository = preparedPurchases,
                productRepository = products,
                unitRepository = units,
                appClock = clock,
            )
            val posting = RoomPurchasePostingRepository(database, clock, TEST_DISPATCHERS)
            // Sin nube, el programador productivo de escritorio es un no-op: el E2E sigue offline.
            val backupScheduler = NoOpPurchaseBackupScheduler()
            val confirm = ConfirmPurchaseUseCase(
                configuration,
                posting,
                backupScheduler,
                ApplyImageRetentionAfterConfirmUseCase(configuration, draftFiles),
            )

            // Aun con una cabecera válida, no hay instantánea publicable mientras falten los
            // 38 enlaces y no se haya explicado la diferencia exacta de tres céntimos.
            val inspection = requireNotNull(prepare.inspect(DRAFT_ID))
            assertEquals(
                CALCULATED_LINES_MINOR,
                inspection.summary?.lineSum?.minorUnits,
            )
            assertEquals(TARGET_TOTAL_MINOR, inspection.summary?.invoiceTotal?.minorUnits)
            assertEquals(ADJUSTMENT_MINOR, inspection.roundingDifference?.minorUnits)
            val beforeMatching = prepare(DRAFT_ID, roundingAccepted = false)
                as PreparePurchaseResult.Blocked
            assertEquals(
                LINE_COUNT,
                beforeMatching.blockers.count {
                    it.code == PrepareBlockerCode.LINE_PRODUCT_MISSING
                },
            )
            assertTrue(
                beforeMatching.blockers.any {
                    it.code == PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED
                },
            )
            val prematureConfirmation = confirm(DRAFT_ID, UNPREPARED_HASH)
                as ConfirmPurchaseResult.Blocked
            assertTrue(PurchaseConfirmationBlocker.DraftNotReady in prematureConfirmation.reasons)
            assertTrue(database.purchaseDao().listForBusiness(BUSINESS_ID.value, 100, 0).isEmpty())

            val matching = ProductMatchingUseCase(products, aliases)
            val productForLine = linkedMapOf<Int, Product>()
            baselineReview.edit.activeLines.take(EXISTING_LINE_COUNT).forEach { line ->
                val expected = DemoInvoiceFixture.lines[line.position]
                assertEquals(expected.description, line.description.selectedValue)
                assertEquals(expected.supplierCode, line.code.selectedValue)
                val outcome = matching(
                    ProductMatchQuery(
                        businessId = BUSINESS_ID,
                        supplierId = supplierId,
                        supplierCode = line.code.selectedValue,
                        description = line.description.selectedValue,
                    ),
                ) as ProductMatchOutcome.AutoLinked
                assertEquals(ProductMatchReason.SKU, outcome.candidate.reason)
                assertEquals(
                    expected.supplierCode,
                    outcome.candidate.product.sku,
                )
                productForLine[line.position] = outcome.candidate.product
            }

            val ambiguousLine = baselineReview.edit.activeLines[AMBIGUOUS_POSITION]
            val ambiguous = matching(
                ProductMatchQuery(
                    businessId = BUSINESS_ID,
                    supplierId = supplierId,
                    supplierCode = ambiguousLine.code.selectedValue,
                    description = ambiguousLine.description.selectedValue,
                ),
            ) as ProductMatchOutcome.Ambiguous
            assertEquals(
                2,
                ambiguous.candidates.size,
            )
            assertTrue(
                ambiguous.candidates.all { it.reason == ProductMatchReason.EXACT_NAME },
            )
            assertTrue(ambiguous.candidates.all { it.product.name == AMBIGUOUS_PRODUCT_NAME })
            productForLine[AMBIGUOUS_POSITION] = ambiguous.candidates.first().product

            val newLine = baselineReview.edit.activeLines[NEW_POSITION]
            assertEquals(
                ProductMatchOutcome.NoMatch,
                matching(
                    ProductMatchQuery(
                        businessId = BUSINESS_ID,
                        supplierId = supplierId,
                        supplierCode = newLine.code.selectedValue,
                        description = newLine.description.selectedValue,
                    ),
                ),
            )
            val createProduct = CreateLinkedProductUseCase(
                appConfigurationRepository = configuration,
                productRepository = products,
                uuidGenerator = uuidGenerator,
            )
            val stagedNewProduct = (
                createProduct(
                NewLinkedProduct(
                    businessId = BUSINESS_ID,
                    name = NEW_PRODUCT_NAME,
                    unitId = requireNotNull(units.findByCode(BUSINESS_ID, "NIU")).unitId,
                    salePrice = Money.ofMinor(650L, CurrencyCode.of("PEN")),
                ),
            ) as CreateLinkedProductResult.Staged
                ).product
            productForLine[NEW_POSITION] = Product(
                productId = stagedNewProduct.productId,
                businessId = stagedNewProduct.businessId,
                unitId = stagedNewProduct.unitId,
                name = stagedNewProduct.name,
                sku = stagedNewProduct.sku,
                barcode = stagedNewProduct.barcode,
                purchaseUnitId = stagedNewProduct.purchaseUnitId,
                purchaseFactor = stagedNewProduct.purchaseFactor,
                salePrice = stagedNewProduct.salePrice,
                createdAt = NOW,
                updatedAt = NOW,
            )
            assertEquals(LINE_COUNT, productForLine.size)

            // La resolución de catálogo declara una intención autoritativa: Room valida las
            // referencias y publica snapshot + proyección en el mismo CAS, sin una escritura
            // previa y separada sobre invoice_lines.
            val linkedReview = baselineReview.edit.copy(
                lines = baselineReview.edit.lines.map { line ->
                    val fixtureLine = DemoInvoiceFixture.lines[line.position]
                    val product = requireNotNull(productForLine[line.position])
                    val explicitLineTax = BigDecimal.valueOf(fixtureLine.taxMinorUnits, 2)
                        .toPlainString()
                    line.copy(
                        linkedProductId = product.productId,
                        linkedUnitId = product.unitId,
                        linkConfidence = 900,
                        taxTreatment = InventoryTaxTreatment.INCLUDED,
                        igv = line.igv.copy(
                            written = explicitLineTax,
                            selectedSource = InvoiceLineValueSource.WRITTEN,
                        ),
                        productProvenance = if (line.position == NEW_POSITION) {
                            PurchaseProductProvenance.CREATED_IN_DRAFT
                        } else {
                            PurchaseProductProvenance.EXISTING
                        },
                        stagedProduct = stagedNewProduct.takeIf { line.position == NEW_POSITION },
                        reviewConfirmedByUser = true,
                        touchedFields = line.touchedFields + InvoiceLineEditField.IGV,
                        updatedAt = NOW,
                    )
                },
                revision = baselineReview.persistedEditRevision + 1L,
                updatedAt = NOW,
            )
            assertEquals(
                SaveInvoiceLinesEditResult.SAVED,
                SaveInvoiceLinesEditUseCase(drafts, lineReviews)(
                    linkedReview,
                    expectedRevision = baselineReview.persistedEditRevision,
                    catalogLinkLineIds = linkedReview.activeLines
                        .mapTo(linkedSetOf()) { it.lineId },
                ),
            )
            val expectedHeaderReview = requireNotNull(headerReviews.find(DRAFT_ID))
            val expectedLinesReview = requireNotNull(lineReviews.find(DRAFT_ID))
            assertEquals(LINE_COUNT, expectedLinesReview.activeLines.size)
            assertTrue(
                expectedLinesReview.activeLines.all {
                    it.taxTreatment == InventoryTaxTreatment.INCLUDED &&
                        it.igv.selectedSource == InvoiceLineValueSource.WRITTEN
                },
            )
            assertEquals(
                IGV_MINOR,
                expectedLinesReview.activeLines.sumOf { line ->
                    requireNotNull(line.igv.selectedValue).toBigDecimal()
                        .movePointRight(2).longValueExact()
                },
            )

            // Rotación: se descartan las instancias que alimentaban ambas pantallas y se crean
            // otras sobre la misma conexión. Ningún valor debe reconstruirse desde el OCR.
            val rotatedDrafts = draftRepository(database, clock)
            val rotatedParsedInvoices = parsedInvoiceRepository(database)
            val rotatedHeaderReviews = headerReviewRepository(database)
            val rotatedLineReviews = linesReviewRepository(database)
            val rotatedHeader = requireNotNull(
                LoadInvoiceHeaderReviewUseCase(
                    rotatedDrafts,
                    rotatedHeaderReviews,
                    rotatedParsedInvoices,
                )(DRAFT_ID),
            )
            val rotatedLines = requireNotNull(
                LoadInvoiceLinesReviewUseCase(
                    rotatedDrafts,
                    rotatedLineReviews,
                    rotatedParsedInvoices,
                    clock,
                )(DRAFT_ID),
            )
            assertEquals(expectedHeaderReview, rotatedHeader.edit)
            assertEquals(expectedLinesReview, rotatedLines.edit)
            assertEquals(LINE_COUNT, rotatedLines.edit.activeLines.size)
            assertEquals(1, rotatedHeader.pages.size)

            // Muerte de proceso de la capa local: se cierra Room y se pierden repositorios,
            // codecs y Flows. La reapertura usa el mismo archivo y construye todo desde cero.
            database.close()
            database = openDatabase()
            val restartedDrafts = draftRepository(database, clock)
            val restartedParsedInvoices = parsedInvoiceRepository(database)
            val restartedHeaderReviews = headerReviewRepository(database)
            val restartedLineReviews = linesReviewRepository(database)
            val restartedHeader = requireNotNull(
                LoadInvoiceHeaderReviewUseCase(
                    restartedDrafts,
                    restartedHeaderReviews,
                    restartedParsedInvoices,
                )(DRAFT_ID),
            )
            val restartedLines = requireNotNull(
                LoadInvoiceLinesReviewUseCase(
                    restartedDrafts,
                    restartedLineReviews,
                    restartedParsedInvoices,
                    clock,
                )(DRAFT_ID),
            )
            assertEquals(expectedHeaderReview, restartedHeader.edit)
            assertEquals(expectedLinesReview, restartedLines.edit)
            assertEquals(DraftStatus.NEEDS_REVIEW, restartedHeader.draft.status)
            assertEquals(1L, restartedHeader.persistedEditRevision)
            assertEquals(expectedLinesReview.revision, restartedLines.persistedEditRevision)
            assertEquals(LINE_COUNT, restartedDrafts.observeLines(DRAFT_ID).first().size)
            assertTrue(restartedParsedInvoices.find(DRAFT_ID) != null)
            assertEquals(
                productForLine.mapValues { (_, product) -> product.productId },
                restartedLines.edit.activeLines.associate { line ->
                    line.position to requireNotNull(line.linkedProductId)
                },
            )

            val restartedProducts = RoomProductRepository(
                database,
                database.productDao(),
                TEST_DISPATCHERS,
                clock,
            )
            val restartedUnits = RoomUnitRepository(database.unitDao(), TEST_DISPATCHERS, clock)
            val restartedPreparedPurchases = RoomPreparedPurchaseRepository(
                database,
                database.preparedPurchaseDao(),
                TEST_DISPATCHERS,
                clock,
            )
            val restartedPrepare = PreparePurchaseUseCase(
                invoiceDraftRepository = restartedDrafts,
                invoiceHeaderReviewRepository = restartedHeaderReviews,
                invoiceLinesReviewRepository = restartedLineReviews,
                preparedPurchaseRepository = restartedPreparedPurchases,
                productRepository = restartedProducts,
                unitRepository = restartedUnits,
                appClock = clock,
            )
            val restartedConfirm = ConfirmPurchaseUseCase(
                configuration,
                RoomPurchasePostingRepository(database, clock, TEST_DISPATCHERS),
                NoOpPurchaseBackupScheduler(),
                ApplyImageRetentionAfterConfirmUseCase(configuration, draftFiles),
            )

            val withoutAdjustment = restartedPrepare(DRAFT_ID, roundingAccepted = false)
                as PreparePurchaseResult.Blocked
            assertEquals(
                listOf(PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED),
                withoutAdjustment.blockers.map { it.code }.distinct(),
            )
            val confirmWithoutAdjustment = restartedConfirm(DRAFT_ID, UNPREPARED_HASH)
                as ConfirmPurchaseResult.Blocked
            assertTrue(
                PurchaseConfirmationBlocker.DraftNotReady in confirmWithoutAdjustment.reasons,
            )
            assertTrue(database.purchaseDao().listForBusiness(BUSINESS_ID.value, 100, 0).isEmpty())

            val withoutReason = restartedPrepare(DRAFT_ID, roundingAccepted = true)
                as PreparePurchaseResult.Blocked
            assertEquals(
                listOf(PrepareBlockerCode.ADJUSTMENT_REASON_REQUIRED),
                withoutReason.blockers.map { it.code }.distinct(),
            )
            val confirmWithoutReason = restartedConfirm(DRAFT_ID, UNPREPARED_HASH)
                as ConfirmPurchaseResult.Blocked
            assertTrue(
                PurchaseConfirmationBlocker.DraftNotReady in confirmWithoutReason.reasons,
            )
            assertTrue(database.purchaseDao().listForBusiness(BUSINESS_ID.value, 100, 0).isEmpty())

            val prepared = restartedPrepare(
                DRAFT_ID,
                roundingAccepted = true,
                adjustmentReason = ADJUSTMENT_REASON,
            )
                as PreparePurchaseResult.Prepared
            assertEquals(LINE_COUNT, prepared.purchase.lines.size)
            assertEquals(TARGET_TOTAL_MINOR, prepared.purchase.total.minorUnits)
            assertTrue(
                prepared.purchase.lines.all {
                    it.taxTreatment == InventoryTaxTreatment.INCLUDED
                },
            )
            assertEquals(
                IGV_MINOR,
                prepared.purchase.lines.sumOf { requireNotNull(it.tax).minorUnits },
            )
            assertTrue(WARNING_LINES_TOTAL_DIFFERENCE in prepared.purchase.acceptedWarnings)
            assertEquals(
                ADJUSTMENT_MINOR,
                prepared.purchase.reconciliationAdjustment?.amount?.minorUnits,
            )
            assertEquals(
                ADJUSTMENT_REASON,
                prepared.purchase.reconciliationAdjustment?.reason,
            )

            val doubleTapResults = coroutineScope {
                List(2) {
                    async(Dispatchers.Default) {
                        restartedConfirm(DRAFT_ID, prepared.purchase.logicalHash)
                    }
                }.awaitAll()
            }
            val posted = doubleTapResults.filterIsInstance<ConfirmPurchaseResult.Posted>().single()
            assertEquals(
                posted.purchaseId,
                doubleTapResults.filterIsInstance<ConfirmPurchaseResult.AlreadyPosted>()
                    .single()
                    .purchaseId,
            )
            val purchaseId = posted.purchaseId
            assertEquals(
                stagedNewProduct.salePrice,
                requireNotNull(restartedProducts.findById(stagedNewProduct.productId)).salePrice,
            )
            val storedPrepared = requireNotNull(restartedPreparedPurchases.find(DRAFT_ID))
            assertEquals(
                ADJUSTMENT_MINOR,
                storedPrepared.reconciliationAdjustment?.amount?.minorUnits,
            )
            assertEquals(ADJUSTMENT_REASON, storedPrepared.reconciliationAdjustment?.reason)

            val postingAudit = database.auditEventDao().listForPurchase(
                BUSINESS_ID.value,
                purchaseId.value,
            ).single { it.eventType == AuditEventType.PURCHASE_POSTED.name }
            val outbox = requireNotNull(
                database.outboxOperationDao().findLatestForPurchase(
                    BUSINESS_ID.value,
                    purchaseId.value,
                ),
            )
            assertEquals("SYNC_PURCHASE", outbox.operationType)
            assertEquals(OutboxOperationStatus.PENDING.name, outbox.status)
            assertFalse(postingAudit.payload == outbox.payload)
            assertTrue(postingAudit.payload.contains("\"adjustmentApplied\":\"true\""))
            assertFalse(postingAudit.payload.contains("\"reconciliationAdjustment\""))
            assertFalse(postingAudit.payload.contains("\"minorUnits\""))
            assertFalse(postingAudit.payload.contains("\"currency\""))
            assertFalse(postingAudit.payload.contains(ADJUSTMENT_REASON))
            assertTrue(outbox.payload.contains("\"minorUnits\":$ADJUSTMENT_MINOR"))
            assertTrue(outbox.payload.contains("\"currency\":\"PEN\""))
            assertTrue(outbox.payload.contains("\"reason\":\"$ADJUSTMENT_REASON\""))

            val purchaseReads = RoomPurchaseReadRepository(database, TEST_DISPATCHERS)
            val purchaseList = withTimeout(5_000) {
                ObservePurchasesUseCase(configuration, purchaseReads)().first { it.size == 1 }
            }
            val summary = purchaseList.single()
            assertEquals(purchaseId, summary.purchaseId)
            assertEquals(DemoInvoiceFixture.SUPPLIER_LEGAL_NAME, summary.supplierLegalName)
            assertEquals(LINE_COUNT, summary.lineCount)
            assertEquals(7, summary.productCount)
            assertEquals(1, summary.createdProductCount)
            assertEquals(6, summary.existingProductCount)
            assertEquals(0, summary.unknownProductCount)
            assertEquals(TARGET_TOTAL_MINOR, summary.total.minorUnits)

            val detail = requireNotNull(withTimeout(5_000) {
                ObservePurchaseDetailUseCase(configuration, purchaseReads)(purchaseId)
                    .first { it != null }
            })
            assertEquals(LINE_COUNT, detail.lines.size)
            assertEquals(7, detail.productCount)
            assertEquals(
                1,
                detail.lines.distinctBy { it.productId }.count {
                    it.productProvenance == PurchaseProductProvenance.CREATED_IN_DRAFT
                },
            )
            assertEquals(
                6,
                detail.lines.distinctBy { it.productId }.count {
                    it.productProvenance == PurchaseProductProvenance.EXISTING
                },
            )
            assertEquals(SUBTOTAL_MINOR, detail.subtotal.minorUnits)
            assertEquals(IGV_MINOR, detail.tax.minorUnits)
            assertEquals(TARGET_TOTAL_MINOR, detail.summary.total.minorUnits)
            assertEquals(CALCULATED_LINES_MINOR, detail.lines.sumOf { it.total.minorUnits })
            assertEquals(ADJUSTMENT_MINOR, detail.adjustment?.minorUnits)
            assertEquals(ADJUSTMENT_REASON, detail.adjustmentReason)
            assertEquals(LINE_COUNT, detail.movements.size)
            assertTrue(detail.movements.all { it.type == StockMovementType.PURCHASE })

            val storedMovements = database.inventoryDao().listMovementsForPurchase(
                BUSINESS_ID.value,
                purchaseId.value,
            )
            assertEquals(LINE_COUNT, storedMovements.size)
            assertTrue(storedMovements.all { it.type == StockMovementType.PURCHASE.name })
            assertEquals(
                LINE_COUNT,
                database.purchaseLineDao().listForPurchase(purchaseId.value).size,
            )

            val inventory = withTimeout(5_000) {
                ObserveInventoryUseCase(
                    configuration,
                    RoomInventoryReadRepository(database, clock, TEST_DISPATCHERS),
                )().first { items -> items.sumOf { it.positions.size } == 7 }
            }
            assertEquals(
                BigDecimal(LINE_COUNT),
                inventory.fold(BigDecimal.ZERO) { total, item ->
                    total.add(item.totalQuantityOnHand)
                },
            )
            val expectedQuantityByProduct = productForLine.values
                .groupingBy(Product::productId)
                .eachCount()
                .mapValues { (_, count) -> BigDecimal(count) }
            val inventoryByProduct = inventory.associate { item ->
                item.productId to item.totalQuantityOnHand
            }
            assertEquals(expectedQuantityByProduct.keys, inventoryByProduct.keys)
            expectedQuantityByProduct.forEach { (productId, expectedQuantity) ->
                assertEquals(
                    0,
                    expectedQuantity.compareTo(requireNotNull(inventoryByProduct[productId])),
                )
            }
            val movementQuantityByProduct = storedMovements
                .groupBy { movement -> requireNotNull(movement.productId) }
                .mapValues { (_, movements) ->
                    movements.fold(BigDecimal.ZERO) { quantity, movement ->
                        quantity.add(BigDecimal(movement.quantityDelta))
                    }
                }
            val expectedMovementQuantityByProduct = expectedQuantityByProduct
                .mapKeys { (productId, _) -> productId.value }
            assertEquals(expectedMovementQuantityByProduct.keys, movementQuantityByProduct.keys)
            expectedMovementQuantityByProduct.forEach { (productId, expectedQuantity) ->
                assertEquals(
                    0,
                    expectedQuantity.compareTo(requireNotNull(movementQuantityByProduct[productId])),
                )
            }

            val retry = restartedConfirm(DRAFT_ID, prepared.purchase.logicalHash)
            assertEquals(ConfirmPurchaseResult.AlreadyPosted(purchaseId), retry)
            assertEquals(
                1,
                database.purchaseDao().listForBusiness(BUSINESS_ID.value, 100, 0).size,
            )
            assertEquals(
                LINE_COUNT,
                database.purchaseLineDao().listForPurchase(purchaseId.value).size,
            )
            assertEquals(
                LINE_COUNT,
                database.inventoryDao().listMovementsForPurchase(
                    BUSINESS_ID.value,
                    purchaseId.value,
                ).size,
            )
            assertEquals(
                1,
                database.auditEventDao().listForPurchase(
                    BUSINESS_ID.value,
                    purchaseId.value,
                ).count { it.eventType == AuditEventType.PURCHASE_POSTED.name },
            )
            val outboxAfterRetry = requireNotNull(
                database.outboxOperationDao().findLatestForPurchase(
                    BUSINESS_ID.value,
                    purchaseId.value,
                ),
            )
            assertEquals(outbox.operationId, outboxAfterRetry.operationId)
            assertEquals(OutboxOperationStatus.PENDING.name, outboxAfterRetry.status)
        }

    private fun draftRepository(
        source: FacturaStockDatabase,
        clock: AppClock,
    ): RoomInvoiceDraftRepository = RoomInvoiceDraftRepository(
        database = source,
        invoiceDraftDao = source.invoiceDraftDao(),
        invoiceImageDao = source.invoiceImageDao(),
        invoiceLineDao = source.invoiceLineDao(),
        dispatchers = TEST_DISPATCHERS,
        clock = clock,
    )

    private fun parsedInvoiceRepository(
        source: FacturaStockDatabase,
    ): RoomParsedInvoiceRepository = RoomParsedInvoiceRepository(
        database = source,
        parsedInvoiceDao = source.parsedInvoiceDao(),
        invoiceDraftDao = source.invoiceDraftDao(),
        invoiceLineDao = source.invoiceLineDao(),
        invoiceOcrSnapshotDao = source.invoiceOcrSnapshotDao(),
        dispatchers = TEST_DISPATCHERS,
    )

    private fun headerReviewRepository(
        source: FacturaStockDatabase,
    ): RoomInvoiceHeaderReviewRepository = RoomInvoiceHeaderReviewRepository(
        source,
        source.invoiceHeaderEditDao(),
        source.invoiceDraftDao(),
        TEST_DISPATCHERS,
    )

    private fun linesReviewRepository(
        source: FacturaStockDatabase,
    ): RoomInvoiceLinesReviewRepository = RoomInvoiceLinesReviewRepository(
        database = source,
        linesEditDao = source.invoiceLinesEditDao(),
        invoiceDraftDao = source.invoiceDraftDao(),
        invoiceLineDao = source.invoiceLineDao(),
        productDao = source.productDao(),
        unitDao = source.unitDao(),
        dispatchers = TEST_DISPATCHERS,
    )

    private class RecordingImageImporter(
        private val expectedJpeg: ByteArray,
    ) : DraftImageImporter {
        var captureCount: Int = 0
            private set

        override suspend fun import(
            draftId: DraftId,
            imageId: ImageId,
            sourceUri: String,
        ): ImportedImageFile = importedFile(draftId, imageId)

        override suspend fun importBytes(
            draftId: DraftId,
            imageId: ImageId,
            jpegBytes: ByteArray,
            rotationDegrees: Int,
        ): ImportedImageFile {
            assertTrue(jpegBytes.contentEquals(expectedJpeg))
            assertEquals(0, rotationDegrees)
            captureCount += 1
            return importedFile(draftId, imageId)
        }

        private fun importedFile(draftId: DraftId, imageId: ImageId) = ImportedImageFile(
            relativePath = "draft_images/${draftId.value}/${imageId.value}.jpg",
            sha256 = "d".repeat(64),
            mimeType = "image/jpeg",
            widthPx = DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
            heightPx = DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
            fileSizeBytes = expectedJpeg.size.toLong(),
        )
    }

    private data object NoOpDraftFileStore : DraftFileStore {
        override suspend fun deleteFiles(
            relativePaths: List<String>,
        ): List<PrivateImageDeletionResult> =
            List(relativePaths.size) { PrivateImageDeletionResult.DELETED }
        override suspend fun deleteDraftTree(draftId: DraftId) = Unit
        override suspend fun deleteDraftTreeIf(
            draftId: DraftId,
            pathIsReferencedAnywhere: suspend (String) -> Boolean,
            shouldDelete: suspend () -> Boolean,
        ): Boolean = shouldDelete()
        override suspend fun deleteOcrVersions(draftId: DraftId) = Unit
    }

    private class RecordingPreprocessor : InvoiceImagePreprocessor {
        var calls: Int = 0
            private set
        private var prepared: List<OcrImageFile> = emptyList()

        override suspend fun preprocess(
            draftId: DraftId,
            images: List<com.facturastock.app.domain.model.InvoiceImage>,
        ): List<OcrImageFile> {
            calls += 1
            prepared = images.map { image ->
                OcrImageFile(
                    sourceImageId = image.imageId,
                    relativePath = "demo/${draftId.value}/ocr/${image.imageId.value}.jpg",
                    mimeType = image.mimeType,
                    widthPx = image.widthPx,
                    heightPx = image.heightPx,
                    fileSizeBytes = image.fileSizeBytes,
                )
            }
            return prepared
        }

        override suspend fun findPrepared(draftId: DraftId): List<OcrImageFile> = prepared

        override suspend fun clearOcrVersions(draftId: DraftId) {
            prepared = emptyList()
        }
    }

    private class DemoInvoiceRecognizer : InvoiceTextRecognizer {
        var calls: Int = 0
            private set

        override suspend fun recognize(pages: List<OcrImageFile>) =
            DemoInvoiceFixture.recognize(pages).also {
                calls += 1
            }
    }

    private class SequentialUuidGenerator(start: Long) : UuidGenerator {
        private var next = start
        override fun newUuid(): UUID = uuid(next++)
    }

    private class DemoConfigurationRepository : AppConfigurationRepository {
        private val state = MutableStateFlow(
            AppConfiguration(
                onboardingCompleted = false,
                businessId = null,
                demoBusinessId = null,
                taxRate = TaxRate(BigDecimal("18")),
                costPolicy = CostPolicy.NET,
                currency = CurrencyCode.of("PEN"),
                zoneId = ZoneId.of("America/Lima"),
            ),
        )

        override fun observe(): Flow<AppConfiguration> = state
        override suspend fun current(): AppConfiguration = state.value

        override suspend fun completeOnboarding(
            businessId: BusinessId,
            taxRate: TaxRate,
            costPolicy: CostPolicy,
        ) {
            state.value = state.value.copy(
                onboardingCompleted = true,
                businessId = businessId,
                taxRate = taxRate,
                costPolicy = costPolicy,
            )
        }

        override suspend fun updateTaxRate(rate: TaxRate) {
            state.value = state.value.copy(taxRate = rate)
        }

        override suspend fun updateCostPolicy(policy: CostPolicy) {
            state.value = state.value.copy(costPolicy = policy)
        }

        override suspend fun updateImageRetentionPolicy(policy: ImageRetentionPolicy) {
            state.value = state.value.copy(imageRetentionPolicy = policy)
        }

        override suspend fun updateBackupEnabled(enabled: Boolean) {
            state.value = state.value.copy(backupEnabled = enabled)
        }

        override suspend fun updateDocumentBackupEnabled(enabled: Boolean) {
            state.value = state.value.copy(documentBackupEnabled = enabled)
        }

        override suspend fun updateDiagnosticsEnabled(enabled: Boolean) {
            state.value = state.value.copy(diagnosticsEnabled = enabled)
        }

        override suspend fun updateBiometricLockEnabled(enabled: Boolean) {
            state.value = state.value.copy(biometricLockEnabled = enabled)
        }

        override suspend fun enterDemoMode(demoBusinessId: BusinessId) {
            state.value = state.value.copy(demoBusinessId = demoBusinessId)
        }

        override suspend fun exitDemoMode() {
            state.value = state.value.copy(demoBusinessId = null)
        }
    }

    private companion object {
        const val DATABASE_NAME = "demo-invoice-end-to-end-test.db"
        const val LINE_COUNT = DemoInvoiceFixture.EXPECTED_LINE_COUNT
        const val EXISTING_LINE_COUNT = DemoPurchaseScenario.EXISTING_LINE_COUNT
        const val AMBIGUOUS_POSITION = 36
        const val NEW_POSITION = 37
        const val SUBTOTAL_MINOR = DemoInvoiceFixture.SUBTOTAL_MINOR_UNITS
        const val IGV_MINOR = DemoInvoiceFixture.IGV_MINOR_UNITS
        const val CALCULATED_LINES_MINOR = DemoInvoiceFixture.CALCULATED_TOTAL_MINOR_UNITS
        const val TARGET_TOTAL_MINOR = DemoInvoiceFixture.TARGET_TOTAL_MINOR_UNITS
        const val ADJUSTMENT_MINOR = DemoInvoiceFixture.REQUIRED_ADJUSTMENT_MINOR_UNITS
        const val ADJUSTMENT_REASON = DemoInvoiceFixture.REQUIRED_ADJUSTMENT_REASON
        const val SUPPLIER_RUC = DemoPurchaseScenario.PRIMARY_SUPPLIER_RUC
        const val AMBIGUOUS_PRODUCT_NAME = DemoPurchaseScenario.AMBIGUOUS_PRODUCT_DESCRIPTION
        const val NEW_PRODUCT_NAME = DemoPurchaseScenario.NEW_PRODUCT_DESCRIPTION

        val NOW: Instant = Instant.parse("2026-08-14T15:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DRAFT_ID: DraftId = DemoPurchaseScenario.draftIdFor(BUSINESS_ID)
        val UNPREPARED_HASH: String = "0".repeat(64)

        val TEST_DISPATCHERS = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

        fun uuid(seed: Long): UUID = UUID(0L, seed)

    }
}
