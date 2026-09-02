package com.facturastock.app.feature.linereview

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.repository.InvoiceLinesEditPublication
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import com.facturastock.app.domain.usecase.InvoiceLineReviewCalculator
import com.facturastock.app.domain.usecase.InvoiceLineReviewValidator
import com.facturastock.app.domain.usecase.LoadInvoiceLinesReviewUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceLinesEditUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Action
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Effect
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.ValueOrigin
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeParsedInvoiceRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.RecordingProductionObservability
import com.facturastock.app.testing.TestDispatcherProvider
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceLineReviewViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial projection observes cancellation before processing the ninth row`() {
        val projectionJob = Job()
        var processedRows = 0

        assertThrows(CancellationException::class.java) {
            repeat(InvoiceLineReviewContract.MAX_LINES) { index ->
                projectionJob.ensureInvoiceLineProjectionActive(index)
                processedRows += 1
                if (processedRows == 8) projectionJob.cancel()
            }
        }

        assertEquals(8, processedRows)
    }

    @Test
    fun `editing one of one hundred lines reuses the other ninety nine UI projections`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = (1..InvoiceLineReviewContract.MAX_LINES).map { seed ->
                    line(
                        seed = seed,
                        position = seed - 1,
                        description = "Producto $seed",
                        totalMinor = 1_180L,
                    )
                },
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }
            val before = viewModel.uiState.value.lines
            val editedIndex = 49

            viewModel.onAction(Action.EditLine(before[editedIndex].lineId))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()

            val after = viewModel.uiState.value.lines
            assertEquals(InvoiceLineReviewContract.MAX_LINES, after.size)
            before.indices.forEach { index ->
                if (index == editedIndex) {
                    assertNotSame(before[index], after[index])
                } else {
                    assertSame(before[index], after[index])
                }
            }
            assertEquals(
                Money.ofMinor(1_183L, PEN).formatForDisplay(),
                after[editedIndex].cardProjection?.total,
            )

            releaseSave.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `rapid edits stay controlled on Main and merge pending row projections`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "line-projection",
            )
            val fixture = fixture(
                lines = (1..InvoiceLineReviewContract.MAX_LINES).map { seed ->
                    line(
                        seed = seed,
                        position = seed - 1,
                        description = "Producto $seed",
                        totalMinor = 1_180L,
                    )
                },
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }
            val firstEditedIndex = 48
            val secondEditedIndex = 49
            val before = viewModel.uiState.value.lines

            viewModel.onAction(Action.EditLine(before[firstEditedIndex].lineId))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()

            // El render global está detenido en Default; Main conserva capacidad de responder.
            assertSame(before[firstEditedIndex], viewModel.uiState.value.lines[firstEditedIndex])
            assertEquals(
                "11.83",
                viewModel.uiState.value.editor?.line?.field(FieldId.TOTAL)?.value,
            )
            viewModel.onAction(Action.EditLine(before[secondEditedIndex].lineId))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "22.22"))
            runCurrent()
            assertEquals(
                "22.22",
                viewModel.uiState.value.editor?.line?.field(FieldId.TOTAL)?.value,
            )
            viewModel.onAction(Action.SearchChanged("Producto 5"))
            runCurrent()
            assertEquals("Producto 5", viewModel.uiState.value.searchQuery)

            isolatedDefault.scheduler.advanceUntilIdle()
            runCurrent()
            val after = viewModel.uiState.value.lines
            assertNotSame(before[firstEditedIndex], after[firstEditedIndex])
            assertNotSame(before[secondEditedIndex], after[secondEditedIndex])
            before.indices
                .filterNot { it == firstEditedIndex || it == secondEditedIndex }
                .forEach { index ->
                    assertSame(before[index], after[index])
                }
            assertEquals(
                Money.ofMinor(1_183L, PEN).formatForDisplay(),
                after[firstEditedIndex].cardProjection?.total,
            )
            assertEquals(
                Money.ofMinor(2_222L, PEN).formatForDisplay(),
                after[secondEditedIndex].cardProjection?.total,
            )

            releaseSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }

    @Test
    fun `tax selection stays controlled while global projection waits on Default`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "tax-projection",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Primera", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(
                Action.EditorTaxTreatmentChanged(InventoryTaxTreatment.EXEMPT),
            )
            runCurrent()

            assertEquals(
                InventoryTaxTreatment.EXEMPT,
                viewModel.uiState.value.editor?.line?.taxTreatment,
            )
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            assertEquals(
                InventoryTaxTreatment.EXEMPT,
                viewModel.uiState.value.lines.single().taxTreatment,
            )

            releaseSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }

    @Test
    fun `new editor flag survives autosave superseding its pending projection`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "new-line-projection",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Existente", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            viewModel.onAction(Action.AddLine)
            viewModel.onAction(Action.AddLine)
            viewModel.onAction(Action.EditLine(lineId(1)))
            viewModel.onAction(Action.RequestDelete(lineId(1)))
            viewModel.onAction(Action.CloseEditor)
            // IO/autosave termina mientras la proyección que abre el editor sigue bloqueada.
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.editor)

            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            val editor = requireNotNull(viewModel.uiState.value.editor)
            assertTrue(editor.isNew)
            assertTrue(editor.line.lineId != lineId(1))
            assertNull(viewModel.uiState.value.pendingDeletionLineId)
            assertEquals(2, viewModel.uiState.value.lines.size)
        }

    @Test
    fun `single line replacement preserves the other ninety nine domain instances`() {
        val before = (1..InvoiceLineReviewContract.MAX_LINES).map { seed ->
            editableLine(
                seed = seed,
                position = seed - 1,
                description = "Producto $seed",
                deletedAt = null,
            )
        }
        val replacementIndex = 49
        val replacement = before[replacementIndex].copy(
            description = InvoiceLineEditValue(
                written = "Producto corregido",
                selectedSource = InvoiceLineValueSource.WRITTEN,
            ),
        )

        val after = before.replaceLine(replacement)

        before.indices.forEach { index ->
            if (index == replacementIndex) {
                assertSame(replacement, after[index])
            } else {
                assertSame(before[index], after[index])
            }
        }
    }

    @Test
    fun `card caps sixty four thousand chars while search and editor keep the full value`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val searchableTail = "NEEDLE64K"
            val fullDescription =
                "a".repeat(InvoiceLineReviewValidator.MAX_DESCRIPTION_LENGTH - searchableTail.length) +
                    searchableTail
            val fixture = fixture(
                lines = listOf(
                    line(
                        seed = 1,
                        position = 0,
                        description = fullDescription,
                        totalMinor = 11_800L,
                    ),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()

            val line = viewModel.uiState.value.lines.single()
            val cardDescription = requireNotNull(line.cardProjection).description
            assertEquals(fullDescription, line.field(FieldId.DESCRIPTION).value)
            assertTrue(cardDescription.endsWith("…"))
            assertEquals(
                InvoiceLineReviewContract.MAX_CARD_DESCRIPTION_LENGTH,
                cardDescription.codePointCount(0, cardDescription.length),
            )

            viewModel.onAction(Action.SearchChanged(searchableTail.lowercase()))
            runCurrent()
            assertEquals(listOf(line.lineId), viewModel.uiState.value.visibleLines.map { it.lineId })

            viewModel.onAction(Action.EditLine(line.lineId))
            runCurrent()
            assertEquals(
                fullDescription,
                viewModel.uiState.value.editor?.line?.field(FieldId.DESCRIPTION)?.value,
            )
        }

    @Test
    fun `same revision global totals and currency invalidate only the required projections`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Uno", totalMinor = 5_000L),
                    line(seed = 2, position = 1, description = "Dos", totalMinor = 6_800L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val persistedRevision = requireNotNull(fixture.reviews.find(DRAFT_ID)).revision
            val beforeTotalChange = viewModel.uiState.value.lines
            val draft = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
            val changedTotal = Money.ofMinor(12_345L, PEN)

            assertTrue(fixture.drafts.updateDraft(draft.copy(total = changedTotal)))
            advanceUntilIdle()

            assertEquals(persistedRevision, requireNotNull(fixture.reviews.find(DRAFT_ID)).revision)
            assertEquals(changedTotal.formatForDisplay(), viewModel.uiState.value.summary.invoiceTotal)
            beforeTotalChange.indices.forEach { index ->
                assertSame(beforeTotalChange[index], viewModel.uiState.value.lines[index])
            }

            val usd = CurrencyCode.of("USD")
            val usdTotal = Money.ofMinor(12_345L, usd)
            val totalOnlyLines = viewModel.uiState.value.lines
            assertTrue(
                fixture.drafts.updateDraft(
                    requireNotNull(fixture.drafts.findDraft(DRAFT_ID)).copy(
                        currency = usd,
                        total = usdTotal,
                    ),
                ),
            )
            advanceUntilIdle()

            val afterCurrencyChange = viewModel.uiState.value.lines
            assertEquals(persistedRevision, requireNotNull(fixture.reviews.find(DRAFT_ID)).revision)
            assertEquals(usd.value, viewModel.uiState.value.currencyLabel)
            assertEquals(usdTotal.formatForDisplay(), viewModel.uiState.value.summary.invoiceTotal)
            afterCurrencyChange.forEach { line ->
                assertEquals(usd.value, requireNotNull(line.cardProjection).currencyLabel)
            }
            totalOnlyLines.indices.forEach { index ->
                assertNotSame(totalOnlyLines[index], afterCurrencyChange[index])
            }

            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }
            viewModel.onAction(Action.EditLine(afterCurrencyChange.first().lineId))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.DESCRIPTION, "Uno editado"))
            runCurrent()

            val afterPartialEdit = viewModel.uiState.value.lines
            assertNotSame(afterCurrencyChange.first(), afterPartialEdit.first())
            assertSame(afterCurrencyChange[1], afterPartialEdit[1])
            afterPartialEdit.forEach { line ->
                assertEquals(usd.value, requireNotNull(line.cardProjection).currencyLabel)
            }

            releaseSave.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `link intent survives autosave completion without a second tap`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Pendiente", totalMinor = 11_800L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val saveEntered = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = {
                saveEntered.complete(Unit)
                releaseSave.await()
            }
            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.QUANTITY, ""))
            runCurrent()
            assertTrue(saveEntered.isCompleted)
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.LinkProducts)
            releaseSave.complete(Unit)
            advanceUntilIdle()

            assertEquals(Effect.FocusLine(lineId(1)), effect.await())
        }

    @Test
    fun `link validation dominates a pending partial render`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "link-arbitration",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Pendiente", totalMinor = 11_800L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.QUANTITY, ""))
            runCurrent()
            assertEquals(
                "",
                viewModel.uiState.value.editor?.line?.field(FieldId.QUANTITY)?.value,
            )

            viewModel.onAction(Action.LinkProducts)
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(Effect.FocusLine(lineId(1)), effect.await())
            assertTrue(viewModel.uiState.value.continueAttempted)
            assertNotNull(viewModel.uiState.value.lines.single().field(FieldId.QUANTITY).error)
            assertFalse(viewModel.uiState.value.isSaving)

            releaseSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }

    @Test
    fun `cancelled link validation leaves the pending row render as fallback`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "link-cancel-fallback",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Primera", totalMinor = 1_180L),
                    line(seed = 2, position = 1, description = "Segunda", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()
            viewModel.onAction(Action.LinkProducts)
            runCurrent()
            viewModel.onAction(Action.RequestDelete(lineId(2)))
            runCurrent()

            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(
                Money.ofMinor(1_183L, PEN).formatForDisplay(),
                viewModel.uiState.value.lines.first().cardProjection?.total,
            )
            assertEquals(lineId(2), viewModel.uiState.value.pendingDeletionLineId)
            assertFalse(viewModel.uiState.value.continueAttempted)

            releaseSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }

    @Test
    fun `late row projection cannot hide a newer load failure`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "load-failure-arbitration",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Primera", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()
            fixture.reviews.failObservation(IOException("lectura Room interrumpida"))
            runCurrent()
            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )

            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )
            assertEquals(
                Money.ofMinor(1_183L, PEN).formatForDisplay(),
                viewModel.uiState.value.lines.single().cardProjection?.total,
            )

            releaseSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )
            assertFalse(viewModel.uiState.value.isSaving)
        }

    @Test
    fun `late link validation cannot hide load failure or navigate`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "link-load-failure",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Lista", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            fixture.reviews.seed(
                InvoiceLinesEdit(
                    draftId = DRAFT_ID,
                    lines = listOf(
                        editableLine(
                            seed = 1,
                            position = 0,
                            description = "Lista",
                            deletedAt = null,
                        ).copy(taxTreatment = InventoryTaxTreatment.EXEMPT),
                    ),
                    revision = 1L,
                    updatedAt = NOW,
                ),
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.LinkProducts)
            runCurrent()
            fixture.reviews.failObservation(IOException("lectura Room interrumpida"))
            runCurrent()
            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )

            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )
            assertFalse(viewModel.uiState.value.isSaving)
            assertFalse(effect.isCompleted)
            effect.cancel()
        }

    @Test
    fun `valid link save cannot hide load failure or navigate`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "link-save-load-failure",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Lista", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            fixture.reviews.seed(
                InvoiceLinesEdit(
                    draftId = DRAFT_ID,
                    lines = listOf(
                        editableLine(
                            seed = 1,
                            position = 0,
                            description = "Lista",
                            deletedAt = null,
                        ).copy(taxTreatment = InventoryTaxTreatment.EXEMPT),
                    ),
                    revision = 1L,
                    updatedAt = NOW,
                ),
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val saveEntered = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = {
                saveEntered.complete(Unit)
                releaseSave.await()
            }
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.LinkProducts)
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            runCurrent()
            saveEntered.await()

            fixture.reviews.failObservation(IOException("lectura Room interrumpida"))
            runCurrent()
            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )
            assertFalse(viewModel.uiState.value.isSaving)

            releaseSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )
            assertFalse(viewModel.uiState.value.isSaving)
            assertFalse(effect.isCompleted)
            effect.cancel()
        }

    @Test
    fun `newer durable snapshot cancels stale link and a second link can proceed`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "link-newer-snapshot",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Lista", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            fixture.reviews.seed(
                InvoiceLinesEdit(
                    draftId = DRAFT_ID,
                    lines = listOf(
                        editableLine(
                            seed = 1,
                            position = 0,
                            description = "Lista",
                            deletedAt = null,
                        ).copy(taxTreatment = InventoryTaxTreatment.EXEMPT),
                    ),
                    revision = 1L,
                    updatedAt = NOW,
                ),
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val durable = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val saveEntered = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = {
                saveEntered.complete(Unit)
                releaseSave.await()
            }
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.LinkProducts)
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            runCurrent()
            saveEntered.await()
            fixture.reviews.publishExternal(
                durable.copy(
                    revision = durable.revision + 1L,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            runCurrent()

            assertFalse(viewModel.uiState.value.isSaving)
            assertFalse(effect.isCompleted)

            releaseSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSaving)
            assertFalse(effect.isCompleted)

            fixture.reviews.beforeSave = {}
            viewModel.onAction(Action.LinkProducts)
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(
                Effect.OpenProductLinking(DRAFT_ID, lineId(1)),
                effect.await(),
            )
        }

    @Test
    fun `stale back result cannot consume the ownership of a newer back request`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Lista", totalMinor = 1_180L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.81"))
            advanceUntilIdle()

            val firstBackEntered = CompletableDeferred<Unit>()
            val releaseFirstBack = CompletableDeferred<Unit>()
            var saveCall = 0
            fixture.reviews.beforeSave = {
                saveCall += 1
                if (saveCall == 1) {
                    firstBackEntered.complete(Unit)
                    releaseFirstBack.await()
                }
            }
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.BackSelected)
            runCurrent()
            firstBackEntered.await()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()
            viewModel.onAction(Action.BackSelected)
            runCurrent()

            releaseFirstBack.complete(Unit)
            advanceUntilIdle()

            assertEquals(Effect.Back, effect.await())
            assertFalse(viewModel.uiState.value.isSaving)
            assertEquals(
                "11.83",
                fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.total?.selectedValue,
            )
        }

    @Test
    fun `failed rebase does not strand a pending row projection`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "rebase-render-fallback",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Primera", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val initial = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val releaseConflictingSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseConflictingSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.81"))
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            runCurrent()
            fixture.reviews.publishExternal(
                initial.copy(
                    revision = 1L,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            runCurrent()
            releaseConflictingSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailure)

            val releaseSecondSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSecondSave.await() }
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()
            fixture.reviews.findFailure = IOException("rebase interrumpido")
            viewModel.onAction(Action.RetrySave)
            advanceUntilIdle()

            assertEquals(
                "11.83",
                viewModel.uiState.value.editor?.line?.field(FieldId.TOTAL)?.value,
            )
            assertEquals(
                Money.ofMinor(1_181L, PEN).formatForDisplay(),
                viewModel.uiState.value.lines.single().cardProjection?.total,
            )

            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.saveFailure)
            assertEquals(
                Money.ofMinor(1_183L, PEN).formatForDisplay(),
                viewModel.uiState.value.lines.single().cardProjection?.total,
            )

            releaseSecondSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }

    @Test
    fun `rebase merges edits made after retry started and ignores stale completion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "rebase-latest-local",
            )
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Primera", totalMinor = 1_180L),
                ),
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val initial = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val releaseConflictingSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseConflictingSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.81"))
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            runCurrent()
            fixture.reviews.publishExternal(
                initial.copy(revision = 1L, updatedAt = NOW.plusSeconds(1)),
            )
            runCurrent()
            releaseConflictingSave.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailure)

            val rebaseFindEntered = CompletableDeferred<Unit>()
            val releaseRebaseFind = CompletableDeferred<Unit>()
            val releaseSaveFind = CompletableDeferred<Unit>()
            var findCall = 0
            fixture.reviews.beforeFind = {
                findCall += 1
                if (findCall == 1) {
                    rebaseFindEntered.complete(Unit)
                    releaseRebaseFind.await()
                } else {
                    releaseSaveFind.await()
                }
            }
            fixture.reviews.beforeSave = {}

            viewModel.onAction(Action.RetrySave)
            runCurrent()
            rebaseFindEntered.await()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()
            releaseRebaseFind.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(
                "11.83",
                viewModel.uiState.value.editor?.line?.field(FieldId.TOTAL)?.value,
            )
            assertEquals(
                Money.ofMinor(1_183L, PEN).formatForDisplay(),
                viewModel.uiState.value.lines.single().cardProjection?.total,
            )

            releaseSaveFind.complete(Unit)
            advanceUntilIdle()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            assertEquals(
                "11.83",
                fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.total?.selectedValue,
            )
            assertFalse(viewModel.uiState.value.saveFailure)
        }

    @Test
    fun `observation failure cancels rebase ownership and retry load stays recoverable`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Primera", totalMinor = 1_180L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val initial = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val releaseConflictingSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseConflictingSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.81"))
            runCurrent()
            fixture.reviews.publishExternal(
                initial.copy(revision = 1L, updatedAt = NOW.plusSeconds(1)),
            )
            runCurrent()
            releaseConflictingSave.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailure)

            val rebaseEntered = CompletableDeferred<Unit>()
            val releaseRebase = CompletableDeferred<Unit>()
            fixture.reviews.beforeFind = {
                rebaseEntered.complete(Unit)
                releaseRebase.await()
            }
            fixture.reviews.beforeSave = {}
            viewModel.onAction(Action.RetrySave)
            runCurrent()
            rebaseEntered.await()
            assertTrue(viewModel.uiState.value.isSaving)

            fixture.reviews.failObservation(IOException("collector Room interrumpido"))
            runCurrent()

            assertEquals(
                InvoiceLineReviewContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )
            assertFalse(viewModel.uiState.value.isSaving)
            assertTrue(viewModel.uiState.value.saveFailure)

            fixture.reviews.beforeFind = {}
            fixture.reviews.recoverObservation()
            viewModel.onAction(Action.RetryLoad)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.failure)
            assertFalse(viewModel.uiState.value.isSaving)
            assertTrue(viewModel.uiState.value.saveFailure)
            releaseRebase.complete(Unit)
        }

    @Test
    fun `link cannot steal ownership from retry and works after rebase completes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Lista", totalMinor = 1_180L),
                ),
            )
            fixture.reviews.seed(
                InvoiceLinesEdit(
                    draftId = DRAFT_ID,
                    lines = listOf(
                        editableLine(
                            seed = 1,
                            position = 0,
                            description = "Lista",
                            deletedAt = null,
                        ).copy(taxTreatment = InventoryTaxTreatment.EXEMPT),
                    ),
                    revision = 1L,
                    updatedAt = NOW,
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val initial = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val releaseConflictingSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseConflictingSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.81"))
            runCurrent()
            fixture.reviews.publishExternal(
                initial.copy(revision = 2L, updatedAt = NOW.plusSeconds(1)),
            )
            runCurrent()
            releaseConflictingSave.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailure)

            val rebaseEntered = CompletableDeferred<Unit>()
            val releaseRebase = CompletableDeferred<Unit>()
            fixture.reviews.beforeFind = {
                rebaseEntered.complete(Unit)
                releaseRebase.await()
            }
            fixture.reviews.beforeSave = {}
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.RetrySave)
            runCurrent()
            rebaseEntered.await()
            viewModel.onAction(Action.LinkProducts)
            runCurrent()
            assertFalse(effect.isCompleted)

            fixture.reviews.beforeFind = {}
            releaseRebase.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSaving)
            assertFalse(viewModel.uiState.value.saveFailure)
            assertFalse(effect.isCompleted)

            viewModel.onAction(Action.LinkProducts)
            advanceUntilIdle()

            assertEquals(
                Effect.OpenProductLinking(DRAFT_ID, lineId(1)),
                effect.await(),
            )
        }

    @Test
    fun `newer snapshot removes an orphan editor id so add line works again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Única", totalMinor = 1_180L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            viewModel.onAction(Action.EditLine(lineId(1)))
            advanceUntilIdle()
            assertEquals(lineId(1), viewModel.uiState.value.editor?.line?.lineId)

            val durable = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val removed = durable.activeLines.single().copy(
                deletedAt = NOW.plusSeconds(1),
                updatedAt = NOW.plusSeconds(1),
            )
            fixture.reviews.publishExternal(
                durable.copy(
                    lines = listOf(removed),
                    revision = durable.revision + 1L,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.editor)
            assertTrue(viewModel.uiState.value.lines.isEmpty())
            assertTrue(viewModel.uiState.value.canAddLine)

            viewModel.onAction(Action.AddLine)
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.lines.size)
            assertNotNull(viewModel.uiState.value.editor)
        }

    @Test
    fun `opening editor cancels queued projection of one hundred long rows`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val isolatedDefault = StandardTestDispatcher(
                scheduler = TestCoroutineScheduler(),
                name = "link-validation",
            )
            val longDescription = "x".repeat(InvoiceLineReviewValidator.MAX_DESCRIPTION_LENGTH)
            val fixture = fixture(
                lines = (1..InvoiceLineReviewContract.MAX_LINES).map { seed ->
                    line(
                        seed = seed,
                        position = seed - 1,
                        description = longDescription,
                        totalMinor = 118L,
                    )
                },
                defaultDispatcher = isolatedDefault,
            )
            val viewModel = fixture.viewModel()
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.LinkProducts)
            // Main ya dejó la proyección esperando en Default; EditLine debe cancelar ese Job
            // antes de que el scheduler aislado ejecute las cien normalizaciones de 64k.
            runCurrent()
            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            isolatedDefault.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(lineId(1), viewModel.uiState.value.editor?.line?.lineId)
            assertFalse(effect.isCompleted)
            effect.cancel()
        }

    @Test
    fun `requesting deletion supersedes link validation and emits no stale navigation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Pendiente", totalMinor = 11_800L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onAction(Action.LinkProducts)
            viewModel.onAction(Action.RequestDelete(lineId(1)))
            advanceUntilIdle()

            assertEquals(lineId(1), viewModel.uiState.value.pendingDeletionLineId)
            assertFalse(effect.isCompleted)
            effect.cancel()
        }

    @Test
    fun `editing updates exact three-cent difference before the durable save finishes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Base", totalMinor = 10_000L),
                    line(seed = 2, position = 1, description = "IGV", totalMinor = 1_800L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()

            val releaseSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseSave.await() }
            viewModel.onAction(Action.EditLine(lineId(2)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "18.03"))
            runCurrent()

            val totalField = viewModel.uiState.value.editor!!.line.field(FieldId.TOTAL)
            assertEquals("18.03", totalField.value)
            assertEquals(ValueOrigin.WRITTEN, totalField.origin)
            assertEquals("18.00", totalField.ocrValue)
            assertEquals(
                Money.ofMinor(3L, PEN).formatSignedForDisplay(),
                viewModel.uiState.value.summary.exactDifference,
            )
            // The writer is deliberately suspended: the value above is an optimistic exact
            // recomputation, not a delayed Room echo.
            assertEquals("18.00", fixture.reviews.find(DRAFT_ID)!!.activeLines[1].total.selectedValue)

            releaseSave.complete(Unit)
            advanceUntilIdle()
            assertEquals("18.03", fixture.reviews.find(DRAFT_ID)!!.activeLines[1].total.selectedValue)
            val audit = fixture.observability.records.single().event
            assertEquals(OperationalAction.INVOICE_LINES_EDIT, audit.action)
            assertEquals(OperationalOutcome.SUCCEEDED, audit.outcome)
            assertEquals(DRAFT_ID, audit.identifiers.draftId)
            assertFalse(audit.toString().contains("18.03"))
        }

    @Test
    fun `older save success cannot regress a newer observed revision`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Inicial", totalMinor = 1_180L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val oldSaveCommitted = CompletableDeferred<Unit>()
            val releaseOldSaveResult = CompletableDeferred<Unit>()
            var interceptNextSave = true
            fixture.reviews.afterSave = { result ->
                if (interceptNextSave && result == SaveInvoiceLinesEditResult.SAVED) {
                    interceptNextSave = false
                    val committed = requireNotNull(fixture.reviews.find(DRAFT_ID))
                    val externalLine = committed.activeLines.single().copy(
                        description = InvoiceLineEditValue(
                            written = "Servidor",
                            selectedSource = InvoiceLineValueSource.WRITTEN,
                        ),
                        updatedAt = NOW.plusSeconds(2),
                    )
                    fixture.reviews.publishExternal(
                        committed.copy(
                            lines = listOf(externalLine),
                            revision = committed.revision + 1L,
                            updatedAt = NOW.plusSeconds(2),
                        ),
                    )
                    oldSaveCommitted.complete(Unit)
                    releaseOldSaveResult.await()
                }
            }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.81"))
            runCurrent()
            oldSaveCommitted.await()
            runCurrent()
            releaseOldSaveResult.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.saveFailure)
            assertEquals(
                "11.81",
                viewModel.uiState.value.editor?.line?.field(FieldId.TOTAL)?.value,
            )

            fixture.reviews.afterSave = {}
            viewModel.onAction(Action.RetrySave)
            advanceUntilIdle()

            val durable = requireNotNull(fixture.reviews.find(DRAFT_ID))
            assertFalse(viewModel.uiState.value.saveFailure)
            assertEquals("Servidor", durable.activeLines.single().description.selectedValue)
            assertEquals("11.81", durable.activeLines.single().total.selectedValue)
            assertTrue(durable.revision >= 3L)
        }

    @Test
    fun `conflated local save keeps its original base and cannot overwrite remote changes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Inicial", totalMinor = 1_180L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val initial = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val firstSaveEntered = CompletableDeferred<Unit>()
            val releaseFirstSave = CompletableDeferred<Unit>()
            var saveCall = 0
            fixture.reviews.beforeSave = {
                saveCall += 1
                if (saveCall == 1) {
                    firstSaveEntered.complete(Unit)
                    releaseFirstSave.await()
                }
            }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.81"))
            runCurrent()
            firstSaveEntered.await()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.82"))
            runCurrent()

            val remoteLine = initial.activeLines.single().copy(
                description = InvoiceLineEditValue(
                    written = "Servidor",
                    selectedSource = InvoiceLineValueSource.WRITTEN,
                ),
                updatedAt = NOW.plusSeconds(1),
            )
            fixture.reviews.publishExternal(
                initial.copy(
                    lines = listOf(remoteLine),
                    revision = initial.revision + 1L,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()

            releaseFirstSave.complete(Unit)
            advanceUntilIdle()

            val stillRemote = requireNotNull(fixture.reviews.find(DRAFT_ID))
            assertEquals("Servidor", stillRemote.activeLines.single().description.selectedValue)
            assertEquals("11.80", stillRemote.activeLines.single().total.selectedValue)
            assertTrue(viewModel.uiState.value.saveFailure)

            fixture.reviews.beforeSave = {}
            viewModel.onAction(Action.RetrySave)
            advanceUntilIdle()

            val merged = requireNotNull(fixture.reviews.find(DRAFT_ID))
            assertEquals("Servidor", merged.activeLines.single().description.selectedValue)
            assertEquals("11.83", merged.activeLines.single().total.selectedValue)
            assertFalse(viewModel.uiState.value.saveFailure)
        }

    @Test
    fun `rebase snapshot is rejected when a newer durable revision arrives before publish`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Inicial", totalMinor = 1_180L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val initial = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val releaseConflictingSave = CompletableDeferred<Unit>()
            fixture.reviews.beforeSave = { releaseConflictingSave.await() }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "11.83"))
            runCurrent()
            val firstRemoteLine = initial.activeLines.single().copy(
                description = InvoiceLineEditValue(
                    written = "Servidor 1",
                    selectedSource = InvoiceLineValueSource.WRITTEN,
                ),
            )
            fixture.reviews.publishExternal(
                initial.copy(
                    lines = listOf(firstRemoteLine),
                    revision = initial.revision + 1L,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            runCurrent()
            releaseConflictingSave.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailure)

            val staleRebaseRead = CompletableDeferred<InvoiceLinesEdit>()
            val releaseStaleRebase = CompletableDeferred<Unit>()
            var interceptFind = true
            fixture.reviews.afterFind = { edit ->
                if (interceptFind && edit != null) {
                    interceptFind = false
                    staleRebaseRead.complete(edit)
                    releaseStaleRebase.await()
                }
            }
            fixture.reviews.beforeSave = {}
            viewModel.onAction(Action.RetrySave)
            runCurrent()
            val stale = staleRebaseRead.await()

            val secondRemoteLine = stale.activeLines.single().copy(
                description = InvoiceLineEditValue(
                    written = "Servidor 2",
                    selectedSource = InvoiceLineValueSource.WRITTEN,
                ),
                updatedAt = NOW.plusSeconds(2),
            )
            fixture.reviews.publishExternal(
                stale.copy(
                    lines = listOf(secondRemoteLine),
                    revision = stale.revision + 1L,
                    updatedAt = NOW.plusSeconds(2),
                ),
            )
            runCurrent()
            releaseStaleRebase.complete(Unit)
            advanceUntilIdle()

            val untouched = requireNotNull(fixture.reviews.find(DRAFT_ID))
            assertEquals("Servidor 2", untouched.activeLines.single().description.selectedValue)
            assertTrue(viewModel.uiState.value.saveFailure)
            assertFalse(viewModel.uiState.value.isSaving)

            fixture.reviews.afterFind = {}
            viewModel.onAction(Action.RetrySave)
            advanceUntilIdle()

            val merged = requireNotNull(fixture.reviews.find(DRAFT_ID))
            assertEquals("Servidor 2", merged.activeLines.single().description.selectedValue)
            assertEquals("11.83", merged.activeLines.single().total.selectedValue)
            assertFalse(viewModel.uiState.value.saveFailure)
        }

    @Test
    fun `idle editor follows a newer durable lines snapshot without reload`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Versión inicial", totalMinor = 11_800L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val current = requireNotNull(fixture.reviews.find(DRAFT_ID))
            val changedLine = current.activeLines.single().copy(
                description = InvoiceLineEditValue(
                    written = "Actualizada desde Room",
                    selectedSource = InvoiceLineValueSource.WRITTEN,
                ),
                updatedAt = NOW.plusSeconds(1),
            )

            fixture.reviews.publishExternal(
                current.copy(
                    lines = listOf(changedLine),
                    revision = current.revision + 1,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            runCurrent()

            assertEquals(
                "Actualizada desde Room",
                viewModel.uiState.value.lines.single().field(FieldId.DESCRIPTION).value,
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `poco espacio conserva la linea local y permite reintentar el autosave`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Versión durable", totalMinor = 11_800L),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            fixture.reviews.beforeSave = {
                throw StorageException(StorageError.InsufficientSpace)
            }

            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()
            viewModel.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "118.03"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.saveFailure)
            assertEquals(
                InvoiceLineReviewContract.Failure.STORAGE_FULL,
                viewModel.uiState.value.failure,
            )
            assertEquals(
                "118.03",
                viewModel.uiState.value.editor?.line?.field(FieldId.TOTAL)?.value,
            )
            assertEquals(
                "118.00",
                fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.total?.selectedValue,
            )

            // Una validación que solo enfoca la primera fila pendiente no debe ocultar el
            // fallo durable ni eliminar la acción de reintento.
            viewModel.onAction(Action.LinkProducts)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailure)
            assertEquals(
                InvoiceLineReviewContract.Failure.STORAGE_FULL,
                viewModel.uiState.value.failure,
            )

            fixture.reviews.beforeSave = {}
            viewModel.onAction(Action.RetrySave)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.saveFailure)
            assertEquals(null, viewModel.uiState.value.failure)
            assertEquals(
                "118.03",
                fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.total?.selectedValue,
            )
        }

    @Test
    fun `search pending filter and open editor survive recreation through SavedStateHandle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Arroz extra", totalMinor = 5_000L),
                    line(
                        seed = 2,
                        position = 1,
                        description = "AZÚCAR RUBIA",
                        totalMinor = 6_800L,
                        quantity = null,
                    ),
                ),
            )
            val handle = routeHandle()
            val first = fixture.viewModel(handle)
            advanceUntilIdle()

            first.onAction(Action.SearchChanged("azucar"))
            runCurrent()
            first.onAction(Action.PendingFilterToggled)
            runCurrent()
            first.onAction(Action.EditLine(lineId(2)))
            runCurrent()

            assertEquals(listOf(lineId(2)), first.uiState.value.visibleLines.map { it.lineId })
            assertEquals(lineId(2), first.uiState.value.editor?.line?.lineId)

            val recreated = fixture.viewModel(handle)
            advanceUntilIdle()

            assertEquals("azucar", recreated.uiState.value.searchQuery)
            assertTrue(recreated.uiState.value.pendingOnly)
            assertEquals(listOf(lineId(2)), recreated.uiState.value.visibleLines.map { it.lineId })
            assertEquals(lineId(2), recreated.uiState.value.editor?.line?.lineId)
        }

    @Test
    fun `initial false focus callback does not reveal errors until a real focus interaction`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(
                        seed = 1,
                        position = 0,
                        description = "Cantidad pendiente",
                        totalMinor = 11_800L,
                        quantity = null,
                    ),
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            viewModel.onAction(Action.EditLine(lineId(1)))
            runCurrent()

            viewModel.onAction(Action.EditorFieldFocusChanged(FieldId.QUANTITY, focused = false))
            runCurrent()
            assertFalse(viewModel.uiState.value.editor!!.line.field(FieldId.QUANTITY).touched)

            viewModel.onAction(Action.EditorFieldFocusChanged(FieldId.QUANTITY, focused = true))
            viewModel.onAction(Action.EditorFieldFocusChanged(FieldId.QUANTITY, focused = false))
            runCurrent()
            assertTrue(viewModel.uiState.value.editor!!.line.field(FieldId.QUANTITY).touched)
            assertNotNull(viewModel.uiState.value.editor!!.line.field(FieldId.QUANTITY).error)
        }

    @Test
    fun `delete is confirmed then its durable tombstone restores the same stable id after restart`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Primero", totalMinor = 5_000L),
                    line(seed = 2, position = 1, description = "Segundo", totalMinor = 6_800L),
                ),
            )
            val first = fixture.viewModel()
            advanceUntilIdle()

            first.onAction(Action.RequestDelete(lineId(1)))
            runCurrent()
            assertEquals(2, first.uiState.value.lines.size)
            assertEquals(lineId(1), first.uiState.value.pendingDeletionLineId)

            first.onAction(Action.ConfirmDelete)
            advanceUntilIdle()
            assertEquals(listOf(lineId(2)), first.uiState.value.lines.map { it.lineId })
            assertEquals(lineId(1), first.uiState.value.restorableDeletion?.lineId)
            assertTrue(fixture.reviews.find(DRAFT_ID)!!.lines.single { it.lineId == lineId(1) }.isDeleted)

            val afterDeleteRestart = fixture.viewModel()
            advanceUntilIdle()
            assertEquals(listOf(lineId(2)), afterDeleteRestart.uiState.value.lines.map { it.lineId })
            assertEquals(lineId(1), afterDeleteRestart.uiState.value.restorableDeletion?.lineId)

            afterDeleteRestart.onAction(Action.RestoreDeletedLine)
            advanceUntilIdle()
            assertEquals(
                listOf(lineId(1), lineId(2)),
                afterDeleteRestart.uiState.value.orderedLines.map { it.lineId },
            )

            val afterRestoreRestart = fixture.viewModel()
            advanceUntilIdle()
            assertEquals(
                listOf(lineId(1), lineId(2)),
                afterRestoreRestart.uiState.value.orderedLines.map { it.lineId },
            )
            assertFalse(fixture.reviews.find(DRAFT_ID)!!.lines.single { it.lineId == lineId(1) }.isDeleted)
        }

    @Test
    fun `reorder is persisted and a fresh ViewModel reads the same stable order`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Uno", totalMinor = 4_000L),
                    line(seed = 2, position = 1, description = "Dos", totalMinor = 4_000L),
                    line(seed = 3, position = 2, description = "Tres", totalMinor = 3_800L),
                ),
            )
            val first = fixture.viewModel()
            advanceUntilIdle()

            first.onAction(Action.MoveLineUp(lineId(3)))
            advanceUntilIdle()
            assertEquals(
                listOf(lineId(1), lineId(3), lineId(2)),
                first.uiState.value.orderedLines.map { it.lineId },
            )

            val recreated = fixture.viewModel()
            advanceUntilIdle()
            assertEquals(
                listOf(lineId(1), lineId(3), lineId(2)),
                recreated.uiState.value.orderedLines.map { it.lineId },
            )
            assertEquals(
                listOf(lineId(1), lineId(3), lineId(2)),
                fixture.reviews.lastProjection.map { it.lineId },
            )
        }

    @Test
    fun `added and edited line survives a new ViewModel and repository read`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "OCR", totalMinor = 11_800L),
                ),
            )
            val first = fixture.viewModel()
            advanceUntilIdle()

            first.onAction(Action.AddLine)
            runCurrent()
            val addedId = first.uiState.value.editor!!.line.lineId
            first.onAction(Action.EditorFieldChanged(FieldId.DESCRIPTION, "Producto manual"))
            first.onAction(Action.EditorFieldChanged(FieldId.QUANTITY, "2"))
            first.onAction(Action.EditorFieldChanged(FieldId.UNIT, "NIU"))
            first.onAction(Action.EditorFieldChanged(FieldId.UNIT_COST, "5.00"))
            first.onAction(Action.EditorFieldChanged(FieldId.TOTAL, "10.00"))
            advanceUntilIdle()

            val storedAdded = fixture.reviews.find(DRAFT_ID)!!.activeLines.single {
                it.lineId == addedId
            }
            assertEquals("Producto manual", storedAdded.description.selectedValue)
            assertEquals("2", storedAdded.quantity.selectedValue)
            assertEquals("10.00", storedAdded.total.selectedValue)

            val recreated = fixture.viewModel()
            advanceUntilIdle()
            val restoredAdded = recreated.uiState.value.lines.single { it.lineId == addedId }
            assertEquals("Producto manual", restoredAdded.field(FieldId.DESCRIPTION).value)
            assertEquals("2", restoredAdded.field(FieldId.QUANTITY).value)
            assertEquals("10.00", restoredAdded.field(FieldId.TOTAL).value)
        }

    @Test
    fun `one hundred active lines disable add without changing the durable revision`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = (0 until 100).map { index ->
                    line(
                        seed = index + 1,
                        position = index,
                        description = "Producto ${index + 1}",
                        totalMinor = 118L,
                    )
                },
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()
            val revision = fixture.reviews.find(DRAFT_ID)!!.revision

            assertEquals(100, viewModel.uiState.value.lines.size)
            assertFalse(viewModel.uiState.value.canAddLine)
            viewModel.onAction(Action.AddLine)
            advanceUntilIdle()

            assertEquals(100, viewModel.uiState.value.lines.size)
            assertEquals(revision, fixture.reviews.find(DRAFT_ID)!!.revision)
            assertNull(viewModel.uiState.value.editor)
        }

    @Test
    fun `add at retained capacity purges the oldest tombstone and persists five hundred rows`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Activa", totalMinor = 11_800L),
                ),
            )
            val active = editableLine(
                seed = 1,
                position = 0,
                description = "Activa",
                deletedAt = null,
            )
            val tombstones = (2..500).map { seed ->
                editableLine(
                    seed = seed,
                    position = 0,
                    description = "Eliminada $seed",
                    deletedAt = NOW.minusSeconds((502 - seed).toLong()),
                )
            }
            val oldestTombstoneId = lineId(2)
            fixture.reviews.seed(
                InvoiceLinesEdit(
                    draftId = DRAFT_ID,
                    lines = listOf(active) + tombstones,
                    revision = 7L,
                    updatedAt = NOW,
                ),
            )
            val viewModel = fixture.viewModel()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.canAddLine)
            viewModel.onAction(Action.AddLine)
            advanceUntilIdle()

            val addedId = viewModel.uiState.value.editor!!.line.lineId
            val persisted = fixture.reviews.find(DRAFT_ID)!!
            assertEquals(500, persisted.lines.size)
            assertEquals(2, persisted.activeLines.size)
            assertFalse(persisted.lines.any { it.lineId == oldestTombstoneId })
            assertTrue(persisted.lines.any { it.lineId == lineId(3) && it.isDeleted })
            assertTrue(persisted.activeLines.any { it.lineId == addedId })

            val recreated = fixture.viewModel()
            advanceUntilIdle()
            assertEquals(2, recreated.uiState.value.lines.size)
            assertEquals(500, fixture.reviews.find(DRAFT_ID)!!.lines.size)
            assertTrue(recreated.uiState.value.lines.any { it.lineId == addedId })
        }

    private suspend fun fixture(
        lines: List<InvoiceLine>,
        defaultDispatcher: TestDispatcher? = null,
    ): Fixture {
        val clock = MutableClock(NOW)
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW,
                currency = PEN,
                total = Money.ofMinor(11_800L, PEN),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        drafts.replaceLines(DRAFT_ID, lines)
        val reviews = RevisionedLinesReviewRepository(drafts)
        val parsed = FakeParsedInvoiceRepository(drafts)
        val validator = InvoiceLineReviewValidator()
        val calculator = InvoiceLineReviewCalculator()
        val observability = RecordingProductionObservability()
        val dispatchers = if (defaultDispatcher == null) {
            TestDispatcherProvider(mainDispatcherRule.dispatcher)
        } else {
            TestDispatcherProvider(
                main = mainDispatcherRule.dispatcher,
                default = defaultDispatcher,
            )
        }
        return Fixture(
            clock = clock,
            drafts = drafts,
            reviews = reviews,
            parsed = parsed,
            validator = validator,
            calculator = calculator,
            observability = observability,
            dispatchers = dispatchers,
        )
    }

    private inner class Fixture(
        val clock: MutableClock,
        val drafts: FakeInvoiceDraftRepository,
        val reviews: RevisionedLinesReviewRepository,
        val parsed: FakeParsedInvoiceRepository,
        val validator: InvoiceLineReviewValidator,
        val calculator: InvoiceLineReviewCalculator,
        val observability: RecordingProductionObservability,
        val dispatchers: TestDispatcherProvider,
    ) {
        private var nextUuidSeed = 10_000

        fun viewModel(handle: SavedStateHandle = routeHandle()): InvoiceLineReviewViewModel =
            InvoiceLineReviewViewModel(
                savedStateHandle = handle,
                loadInvoiceLinesReviewUseCase = LoadInvoiceLinesReviewUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceLinesReviewRepository = reviews,
                    parsedInvoiceRepository = parsed,
                    appClock = clock,
                ),
                saveInvoiceLinesEditUseCase = SaveInvoiceLinesEditUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceLinesReviewRepository = reviews,
                    calculator = calculator,
                    validator = validator,
                    observability = observability,
                ),
                validator = validator,
                calculator = calculator,
                uuidGenerator = UuidGenerator { uuid(nextUuidSeed++) },
                appClock = clock,
                dispatcherProvider = dispatchers,
            )
    }

    private class RevisionedLinesReviewRepository(
        private val drafts: FakeInvoiceDraftRepository,
    ) : InvoiceLinesReviewRepository {
        private val lock = Mutex()
        private val flow = MutableStateFlow<InvoiceLinesEdit?>(null)
        private val observationFailure = MutableStateFlow<Throwable?>(null)
        private var stored: InvoiceLinesEdit? = null

        var beforeSave: suspend () -> Unit = {}
        var afterSave: suspend (SaveInvoiceLinesEditResult) -> Unit = {}
        var beforeFind: suspend () -> Unit = {}
        var afterFind: suspend (InvoiceLinesEdit?) -> Unit = {}
        var findFailure: Throwable? = null
        var lastProjection: List<InvoiceLine> = emptyList()
            private set

        suspend fun seed(edit: InvoiceLinesEdit) {
            lock.withLock {
                stored = edit
                flow.value = edit
            }
        }

        suspend fun publishExternal(edit: InvoiceLinesEdit) {
            lock.withLock {
                stored = edit
                flow.value = edit
            }
        }

        fun failObservation(failure: Throwable) {
            observationFailure.value = failure
        }

        fun recoverObservation() {
            observationFailure.value = null
        }

        override suspend fun find(draftId: DraftId): InvoiceLinesEdit? {
            beforeFind()
            findFailure?.let { failure -> throw failure }
            val result = lock.withLock { stored?.takeIf { it.draftId == draftId } }
            afterFind(result)
            return result
        }

        override fun observe(draftId: DraftId): Flow<InvoiceLinesEdit?> =
            combine(flow, observationFailure) { edit, failure ->
                if (failure != null) throw failure
                edit
            }

        override suspend fun saveIfNewer(
            publication: InvoiceLinesEditPublication,
        ): SaveInvoiceLinesEditResult {
            beforeSave()
            val result = lock.withLock {
                val draft = drafts.findDraft(publication.edit.draftId)
                    ?: return@withLock SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE
                if (
                    draft.status != DraftStatus.NEEDS_REVIEW ||
                    draft.activeOcrRunId != null ||
                    draft.confirmedPurchaseId != null
                ) {
                    return@withLock SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE
                }
                val current = stored
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
                lastProjection = publication.projectedLines
                stored = publication.edit
                flow.value = publication.edit
                SaveInvoiceLinesEditResult.SAVED
            }
            afterSave(result)
            return result
        }
    }

    private class MutableClock(private var value: Instant) : AppClock {
        override fun now(): Instant = value
    }

    private fun routeHandle(): SavedStateHandle = SavedStateHandle(
        mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value),
    )

    private fun line(
        seed: Int,
        position: Int,
        description: String,
        totalMinor: Long,
        quantity: String? = "1",
    ): InvoiceLine = InvoiceLine(
        lineId = lineId(seed),
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        position = position,
        descriptionRaw = description,
        descriptionNormalized = description,
        quantity = quantity?.let(Quantity::of),
        unitCost = UnitCost.of(Money.ofMinor(totalMinor, PEN).toMajor(), PEN),
        lineTotal = Money.ofMinor(totalMinor, PEN),
        ocrConfidence = 950,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun editableLine(
        seed: Int,
        position: Int,
        description: String,
        deletedAt: Instant?,
    ): InvoiceLineEdit {
        val createdAt = NOW.minusSeconds(1_000L)
        val updatedAt = deletedAt ?: NOW
        fun written(value: String): InvoiceLineEditValue = InvoiceLineEditValue(
            written = value,
            selectedSource = InvoiceLineValueSource.WRITTEN,
        )
        return InvoiceLineEdit(
            lineId = lineId(seed),
            position = position,
            origin = InvoiceLineEditOrigin.USER,
            description = written(description),
            quantity = written("1"),
            total = written("118.00"),
            requiresReview = false,
            reviewConfirmedByUser = true,
            deletedAt = deletedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun lineId(seed: Int): LineId = LineId.from(uuid(seed))

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
        )
    }
}
