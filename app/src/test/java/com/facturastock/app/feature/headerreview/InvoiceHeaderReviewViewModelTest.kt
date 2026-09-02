package com.facturastock.app.feature.headerreview

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.normalization.CandidateEvidence
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.normalization.ParsedInvoiceCandidateTrace
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldKind
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldTrace
import com.facturastock.app.domain.normalization.ParsedInvoiceValueOrigin
import com.facturastock.app.domain.repository.InvoiceHeaderEditPublication
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.ParsedInvoicePublication
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.PersistedParsedInvoice
import com.facturastock.app.domain.repository.PublishParsedInvoiceResult
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import com.facturastock.app.domain.usecase.InvoiceHeaderReviewValidator
import com.facturastock.app.domain.usecase.LoadInvoiceHeaderReviewUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceHeaderEditUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Action
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.AdvanceBlockReason
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Confidence
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Effect
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldError
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldId
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.ReviewWarning
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.ReviewWarningCode
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.RecordingProductionObservability
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceHeaderReviewViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `autosaved edits load from durable storage in a fresh ViewModel`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val first = fixture.viewModel()
            runCurrent()

            first.onAction(Action.FieldChanged(FieldId.SUPPLIER, "Proveedor persistido SAC"))
            advanceUntilIdle()

            assertEquals(
                "Proveedor persistido SAC",
                fixture.reviews.find(DRAFT_ID)?.supplierLegalName,
            )
            assertEquals(
                "Proveedor persistido SAC",
                fixture.drafts.findDraft(DRAFT_ID)?.supplierLegalNameNormalized,
            )
            val audit = fixture.observability.records.single().event
            assertEquals(OperationalAction.INVOICE_HEADER_EDIT, audit.action)
            assertEquals(OperationalOutcome.SUCCEEDED, audit.outcome)
            assertEquals(DRAFT_ID, audit.identifiers.draftId)
            assertFalse(audit.toString().contains("Proveedor persistido SAC"))

            // Simula recreación/proceso nuevo: el handle solo conserva el argumento de ruta.
            val restored = fixture.viewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value)),
            )
            runCurrent()

            assertEquals(
                "Proveedor persistido SAC",
                restored.uiState.value.field(FieldId.SUPPLIER).value,
            )
            assertEquals(1L, fixture.reviews.find(DRAFT_ID)?.revision)
        }

    @Test
    fun `idle editor follows the durable Room flow without an explicit reload`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(initialEdit = validEdit())
            val viewModel = fixture.viewModel()
            runCurrent()

            fixture.reviews.publishExternal(
                validEdit().copy(
                    supplierLegalName = "Proveedor actualizado desde Room",
                    revision = 4L,
                    updatedAt = NOW.plusSeconds(4),
                ),
            )
            runCurrent()

            assertEquals(
                "Proveedor actualizado desde Room",
                viewModel.uiState.value.field(FieldId.SUPPLIER).value,
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `validation stays hidden until blur and Continue reveals the first correction`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                initialEdit = validEdit().copy(supplierRuc = "", revision = 4L),
            )
            val viewModel = fixture.viewModel()
            runCurrent()

            val initialRuc = viewModel.uiState.value.field(FieldId.RUC)
            assertEquals(FieldError.REQUIRED, initialRuc.error)
            assertFalse(initialRuc.touched)
            assertFalse(initialRuc.shouldShowError(viewModel.uiState.value.continueAttempted))

            viewModel.onAction(Action.FieldFocusChanged(FieldId.RUC, focused = true))
            viewModel.onAction(Action.FieldFocusChanged(FieldId.RUC, focused = false))
            runCurrent()

            val blurredRuc = viewModel.uiState.value.field(FieldId.RUC)
            assertTrue(blurredRuc.touched)
            assertTrue(blurredRuc.shouldShowError(viewModel.uiState.value.continueAttempted))

            viewModel.effects.test {
                viewModel.onAction(Action.ReviewProducts)
                runCurrent()

                assertEquals(Effect.FocusField(FieldId.RUC), awaitItem())
                assertTrue(viewModel.uiState.value.continueAttempted)
                assertEquals(FieldId.RUC, viewModel.uiState.value.blockingFields.first())
                assertEquals(
                    AdvanceBlockReason.MISSING_OR_INVALID,
                    viewModel.uiState.value.blockingReason(FieldId.RUC),
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `LOW essential OCR value blocks until explicit confirmation is persisted`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(lowField = ParsedInvoiceFieldKind.ISSUER_RUC)
            val viewModel = fixture.viewModel()
            runCurrent()

            val ruc = viewModel.uiState.value.field(FieldId.RUC)
            assertEquals(Confidence.LOW, ruc.confidence)
            assertTrue(ruc.requiresExplicitConfirmation)

            viewModel.effects.test {
                viewModel.onAction(Action.ReviewProducts)
                runCurrent()
                assertEquals(Effect.FocusField(FieldId.RUC), awaitItem())
                assertEquals(
                    AdvanceBlockReason.UNCONFIRMED_UNCERTAIN_VALUE,
                    viewModel.uiState.value.blockingReason(FieldId.RUC),
                )

                viewModel.onAction(Action.FieldConfirmed(FieldId.RUC))
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.field(FieldId.RUC).confirmedByUser)
                assertTrue(
                    com.facturastock.app.domain.model.InvoiceHeaderEditField.SUPPLIER_RUC in
                        requireNotNull(fixture.reviews.find(DRAFT_ID)).touchedFields,
                )

                viewModel.onAction(Action.ReviewProducts)
                advanceUntilIdle()
                assertEquals(Effect.OpenProductsReview(DRAFT_ID), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `rapid r1 r2 r3 edits are serialized and the latest revision wins`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.viewModel()
            runCurrent()

            viewModel.onAction(Action.FieldChanged(FieldId.SUPPLIER, "A"))
            viewModel.onAction(Action.FieldChanged(FieldId.SUPPLIER, "AB"))
            viewModel.onAction(Action.FieldChanged(FieldId.SUPPLIER, "ABC"))
            advanceUntilIdle()

            val persistedRevisions = fixture.reviews.attempts.map { it.edit.revision }
            assertTrue(persistedRevisions.isNotEmpty())
            assertEquals(3L, persistedRevisions.last())
            assertEquals(persistedRevisions.sorted().distinct(), persistedRevisions)
            assertEquals("ABC", fixture.reviews.find(DRAFT_ID)?.supplierLegalName)
            assertEquals(3L, fixture.reviews.find(DRAFT_ID)?.revision)
            assertEquals("ABC", viewModel.uiState.value.field(FieldId.SUPPLIER).value)
            assertFalse(viewModel.uiState.value.field(FieldId.SUPPLIER).isPersisting)
        }

    @Test
    fun `failed autosave keeps text and Retry persists the same latest revision`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            fixture.reviews.nextResult = SaveInvoiceHeaderEditResult.CONFLICT
            val viewModel = fixture.viewModel()
            runCurrent()

            viewModel.onAction(Action.FieldChanged(FieldId.TOTAL, "118.03"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.saveFailure)
            assertEquals("118.03", viewModel.uiState.value.field(FieldId.TOTAL).value)
            assertNull(fixture.reviews.find(DRAFT_ID))

            viewModel.onAction(Action.RetrySave)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.saveFailure)
            assertNull(viewModel.uiState.value.failure)
            assertEquals("118.03", fixture.reviews.find(DRAFT_ID)?.total)
            assertEquals(2L, fixture.reviews.find(DRAFT_ID)?.revision)
            assertEquals(listOf(1L, 2L), fixture.reviews.attempts.map { it.edit.revision })
        }

    @Test
    fun `insufficient space during review keeps durable baseline and retries local text`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val baseline = validEdit().copy(revision = 4L)
            val fixture = fixture(initialEdit = baseline)
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            fixture.reviews.nextException = StorageException(StorageError.InsufficientSpace)

            viewModel.onAction(Action.FieldChanged(FieldId.TOTAL, "118.03"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.saveFailure)
            assertEquals(
                InvoiceHeaderReviewContract.Failure.STORAGE_FULL,
                viewModel.uiState.value.failure,
            )
            assertEquals("118.03", viewModel.uiState.value.field(FieldId.TOTAL).value)
            assertEquals(baseline, fixture.reviews.find(DRAFT_ID))
            assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
            assertEquals(1, fixture.drafts.observeImages(DRAFT_ID).first().size)

            viewModel.onAction(Action.RetrySave)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.saveFailure)
            assertNull(viewModel.uiState.value.failure)
            assertEquals("118.03", fixture.reviews.find(DRAFT_ID)?.total)
            assertEquals(5L, fixture.reviews.find(DRAFT_ID)?.revision)
            assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
        }

    @Test
    fun `reactive editors merge remote and local fields without a false conflict`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val remoteEditor = fixture.viewModel()
            val localEditor = fixture.viewModel()
            runCurrent()

            remoteEditor.onAction(
                Action.FieldChanged(FieldId.SUPPLIER, "Proveedor remoto SAC"),
            )
            advanceUntilIdle()
            assertEquals(
                "Proveedor remoto SAC",
                localEditor.uiState.value.field(FieldId.SUPPLIER).value,
            )

            // Room Flow adelantó la base local a r1; los cambios propios continúan desde allí.
            localEditor.onAction(Action.FieldChanged(FieldId.TOTAL, "118.01"))
            localEditor.onAction(Action.FieldChanged(FieldId.TOTAL, "118.03"))
            advanceUntilIdle()

            val merged = requireNotNull(fixture.reviews.find(DRAFT_ID))
            assertEquals(3L, merged.revision)
            assertEquals("Proveedor remoto SAC", merged.supplierLegalName)
            assertEquals("118.03", merged.total)
            assertEquals(
                "Proveedor remoto SAC",
                localEditor.uiState.value.field(FieldId.SUPPLIER).value,
            )
            assertEquals("118.03", localEditor.uiState.value.field(FieldId.TOTAL).value)
            assertFalse(localEditor.uiState.value.saveFailure)
        }

    @Test
    fun `recreation rebases saved local delta after a concurrent CAS race`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val remoteHandle = routeHandle()
            val localHandle = routeHandle()
            val remoteEditor = fixture.viewModel(remoteHandle)
            val localEditor = fixture.viewModel(localHandle)
            runCurrent()

            remoteEditor.onAction(
                Action.FieldChanged(FieldId.SUPPLIER, "Proveedor remoto r1"),
            )
            advanceUntilIdle()

            // Una escritura externa gana exactamente entre la edición local y su CAS.
            fixture.reviews.nextConcurrentEdit = requireNotNull(fixture.reviews.find(DRAFT_ID)).copy(
                supplierLegalName = "Proveedor remoto r2",
                revision = 2L,
                updatedAt = NOW.plusSeconds(2),
            )
            localEditor.onAction(Action.FieldChanged(FieldId.TOTAL, "118.03"))
            advanceUntilIdle()
            assertTrue(localEditor.uiState.value.saveFailure)
            assertEquals("118.03", localEditor.uiState.value.field(FieldId.TOTAL).value)
            assertEquals(2L, fixture.reviews.find(DRAFT_ID)?.revision)

            // Room avanza otra vez mientras el cambio local sigue en el SavedStateHandle.
            remoteEditor.onAction(
                Action.FieldChanged(FieldId.SUPPLIER, "Proveedor remoto r3"),
            )
            advanceUntilIdle()
            assertEquals(3L, fixture.reviews.find(DRAFT_ID)?.revision)

            val recreated = fixture.viewModel(localHandle)
            advanceUntilIdle()

            val rebased = requireNotNull(fixture.reviews.find(DRAFT_ID))
            assertEquals(4L, rebased.revision)
            assertEquals("Proveedor remoto r3", rebased.supplierLegalName)
            assertEquals("118.03", rebased.total)
            assertEquals(
                "Proveedor remoto r3",
                recreated.uiState.value.field(FieldId.SUPPLIER).value,
            )
            assertEquals("118.03", recreated.uiState.value.field(FieldId.TOTAL).value)
            assertFalse(recreated.uiState.value.saveFailure)
        }

    @Test
    fun `checksum and exact three cent difference reach warnings without blocking`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                initialEdit = validEdit().copy(
                    supplierRuc = "12345678901",
                    otherCharges = null,
                    total = "118.03",
                    revision = 7L,
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    ReviewWarning(ReviewWarningCode.RUC_CHECKSUM_MISMATCH),
                    ReviewWarning(
                        code = ReviewWarningCode.TOTAL_DIFFERENCE,
                        difference = "0.03",
                        currency = "PEN",
                    ),
                ),
                viewModel.uiState.value.warnings,
            )
            assertTrue(viewModel.uiState.value.blockingFields.isEmpty())
            assertTrue(viewModel.uiState.value.canReviewProducts)

            viewModel.effects.test {
                viewModel.onAction(Action.ReviewProducts)
                advanceUntilIdle()
                assertEquals(Effect.OpenProductsReview(DRAFT_ID), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `Back flushes the latest edit before emitting navigation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.viewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(Action.FieldChanged(FieldId.SUPPLIER, "Guardado antes de volver"))
                viewModel.onAction(Action.BackSelected)
                advanceUntilIdle()

                assertEquals(Effect.Back, awaitItem())
                assertEquals(
                    "Guardado antes de volver",
                    fixture.reviews.find(DRAFT_ID)?.supplierLegalName,
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `double Continue flushes the current edit and emits navigation once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.viewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(Action.ReviewProducts)
                viewModel.onAction(Action.ReviewProducts)
                advanceUntilIdle()

                assertEquals(Effect.OpenProductsReview(DRAFT_ID), awaitItem())
                expectNoEvents()
                assertEquals(0L, fixture.reviews.find(DRAFT_ID)?.revision)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun fixture(
        initialEdit: InvoiceHeaderEdit? = null,
        lowField: ParsedInvoiceFieldKind? = null,
    ): Fixture {
        val clock = AppClock { NOW }
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(validDraft())
        drafts.seedImage(
            InvoiceImage(
                imageId = IMAGE_ID,
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                pageIndex = 0,
                filePath = "draft_images/review/page-0.jpg",
                sha256 = "a".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = 2_000L,
                createdAt = NOW,
            ),
        )
        val reviews = RevisionedHeaderReviewRepository(drafts)
        if (initialEdit != null) {
            reviews.seed(initialEdit)
        }
        val parsed = FixedParsedInvoiceRepository(audit(lowField))
        val observability = RecordingProductionObservability()
        return Fixture(
            clock = clock,
            drafts = drafts,
            reviews = reviews,
            parsed = parsed,
            observability = observability,
            dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        )
    }

    private data class Fixture(
        val clock: AppClock,
        val drafts: FakeInvoiceDraftRepository,
        val reviews: RevisionedHeaderReviewRepository,
        val parsed: ParsedInvoiceRepository,
        val observability: RecordingProductionObservability,
        val dispatchers: TestDispatcherProvider,
    ) {
        fun viewModel(
            savedStateHandle: SavedStateHandle = SavedStateHandle(
                mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value),
            ),
        ): InvoiceHeaderReviewViewModel {
            val validator = InvoiceHeaderReviewValidator()
            return InvoiceHeaderReviewViewModel(
                savedStateHandle = savedStateHandle,
                loadInvoiceHeaderReviewUseCase = LoadInvoiceHeaderReviewUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceHeaderReviewRepository = reviews,
                    parsedInvoiceRepository = parsed,
                ),
                saveInvoiceHeaderEditUseCase = SaveInvoiceHeaderEditUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceHeaderReviewRepository = reviews,
                    validator = validator,
                    observability = observability,
                ),
                validator = validator,
                appClock = clock,
                dispatcherProvider = dispatchers,
            )
        }
    }

    private fun routeHandle(): SavedStateHandle = SavedStateHandle(
        mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value),
    )

    private class RevisionedHeaderReviewRepository(
        private val drafts: FakeInvoiceDraftRepository,
    ) : InvoiceHeaderReviewRepository {
        private val lock = Mutex()
        private var stored: InvoiceHeaderEdit? = null
        private val observed = MutableStateFlow<InvoiceHeaderEdit?>(null)
        private val recordedAttempts = mutableListOf<InvoiceHeaderEditPublication>()

        var nextResult: SaveInvoiceHeaderEditResult? = null
        var nextException: Exception? = null
        var nextConcurrentEdit: InvoiceHeaderEdit? = null

        val attempts: List<InvoiceHeaderEditPublication>
            get() = recordedAttempts.toList()

        suspend fun seed(edit: InvoiceHeaderEdit) {
            lock.withLock {
                stored = edit
                observed.value = edit
            }
        }

        suspend fun publishExternal(edit: InvoiceHeaderEdit) {
            lock.withLock {
                stored = edit
                observed.value = edit
            }
        }

        override suspend fun find(draftId: DraftId): InvoiceHeaderEdit? = lock.withLock {
            stored?.takeIf { it.draftId == draftId }
        }

        override fun observe(draftId: DraftId): Flow<InvoiceHeaderEdit?> = observed

        override suspend fun saveIfNewer(
            publication: InvoiceHeaderEditPublication,
        ): SaveInvoiceHeaderEditResult = lock.withLock {
            recordedAttempts += publication
            nextException?.let { forced ->
                nextException = null
                throw forced
            }
            nextResult?.let { forced ->
                nextResult = null
                return@withLock forced
            }
            nextConcurrentEdit?.let { concurrent ->
                nextConcurrentEdit = null
                stored = concurrent
                observed.value = concurrent
            }
            val current = stored
            if (current != null) {
                when {
                    current.revision == publication.edit.revision && current == publication.edit ->
                        return@withLock SaveInvoiceHeaderEditResult.ALREADY_SAVED
                    current.revision > publication.edit.revision ->
                        return@withLock SaveInvoiceHeaderEditResult.STALE_REVISION
                    current.revision == publication.edit.revision ->
                        return@withLock SaveInvoiceHeaderEditResult.CONFLICT
                    publication.expectedRevision != current.revision ->
                        return@withLock SaveInvoiceHeaderEditResult.STALE_REVISION
                }
            } else if (publication.expectedRevision != null) {
                return@withLock SaveInvoiceHeaderEditResult.STALE_REVISION
            }
            if (!drafts.updateDraft(publication.projectedDraft)) {
                return@withLock SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE
            }
            stored = publication.edit
            observed.value = publication.edit
            SaveInvoiceHeaderEditResult.SAVED
        }
    }

    private class FixedParsedInvoiceRepository(
        private val audit: ParsedInvoiceAudit,
    ) : ParsedInvoiceRepository {
        override suspend fun publish(
            publication: ParsedInvoicePublication,
        ): PublishParsedInvoiceResult = error("La prueba de revisión no publica un parser")

        override suspend fun find(draftId: DraftId): PersistedParsedInvoice? =
            audit.takeIf { it.draftId == draftId }?.let {
                PersistedParsedInvoice(
                    audit = it,
                    parsedAt = NOW,
                    payloadSha256 = "b".repeat(64),
                )
            }
    }

    private fun audit(lowField: ParsedInvoiceFieldKind?): ParsedInvoiceAudit {
        val values = listOf(
            ParsedInvoiceFieldKind.ISSUER_RUC to "20123456786",
            ParsedInvoiceFieldKind.DOCUMENT_NUMBER to "F001-42",
            ParsedInvoiceFieldKind.ISSUE_DATE to "10/08/2026",
            ParsedInvoiceFieldKind.CURRENCY to "PEN",
            ParsedInvoiceFieldKind.DOCUMENT_TOTAL to "118.00",
        )
        val fields = values.map { (kind, value) ->
            val score = if (kind == lowField) 699 else 950
            val confidence = ParsedInvoiceConfidence.fromPermille(score)
            ParsedInvoiceFieldTrace(
                kind = kind,
                selectedCandidateIndex = 0,
                candidates = listOf(
                    ParsedInvoiceCandidateTrace(
                        canonicalValue = value,
                        rawText = value,
                        origin = ParsedInvoiceValueOrigin.OCR,
                        confidencePermille = score,
                        confidence = confidence,
                        requiresReview = false,
                        warnings = emptyList(),
                        reasons = listOf("TEST_FIXTURE"),
                        evidence = listOf(evidence(value)),
                    ),
                ),
                confidence = confidence,
                requiresReview = false,
            )
        }.sortedWith(ParsedInvoiceAudit.FIELD_ORDER)
        return ParsedInvoiceAudit(
            draftId = DRAFT_ID,
            runId = OCR_RUN_ID,
            parserVersion = 1,
            contextFingerprint = "c".repeat(64),
            confidence = fields.minBy { it.confidence.reliabilityRank }.confidence,
            fields = fields,
            warnings = emptyList(),
            blockers = emptyList(),
        )
    }

    private fun evidence(rawText: String): CandidateEvidence = CandidateEvidence(
        rawText = rawText,
        unicodeText = rawText,
        normalizedText = rawText,
        comparisonText = rawText,
        sourceImageId = IMAGE_ID,
        pageIndex = 0,
    )

    private fun validDraft(): InvoiceDraft {
        val currency = CurrencyCode.of("PEN")
        return InvoiceDraft(
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            status = DraftStatus.NEEDS_REVIEW,
            supplierRucRaw = "20123456786",
            supplierRucNormalized = "20123456786",
            supplierLegalNameRaw = "Proveedor Andino SAC",
            supplierLegalNameNormalized = "Proveedor Andino SAC",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumberRaw = "F001-42",
            documentNumberNormalized = "F001-42",
            issueDateRaw = "10/08/2026",
            issueDate = LocalDate.of(2026, 8, 10),
            currency = currency,
            subtotal = Money.ofMinor(10_000L, currency),
            tax = Money.ofMinor(1_800L, currency),
            otherCharges = Money.zero(currency),
            total = Money.ofMinor(11_800L, currency),
            createdAt = NOW,
            updatedAt = NOW,
        )
    }

    private fun validEdit(): InvoiceHeaderEdit = InvoiceHeaderEdit(
        draftId = DRAFT_ID,
        supplierRuc = "20123456786",
        supplierLegalName = "Proveedor Andino SAC",
        documentType = PurchaseDocumentType.INVOICE.name,
        documentSeries = "F001",
        documentNumber = "42",
        issueDate = "10/08/2026",
        currency = "PEN",
        subtotal = "100.00",
        igv = "18.00",
        otherCharges = "0.00",
        total = "118.00",
        revision = 0L,
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000023"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000024"),
        )
        val OCR_RUN_ID: OcrRunId = OcrRunId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000025"),
        )
    }
}
