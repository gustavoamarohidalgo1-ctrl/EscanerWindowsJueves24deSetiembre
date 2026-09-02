package com.facturastock.app.feature.linereview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Action
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Confidence
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.DeletedLine
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Editor
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Field
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Line
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.State
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Summary
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.ValueOrigin
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceLineReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emptyStateAddActionRespectsLoadingGate() {
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = true,
                        lines = emptyList(),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.EMPTY_ADD_LINE)
            .assertIsNotEnabled()
    }

    @Test
    fun oneHundredStableCardsScrollLazilyWhileTheExactSummaryStaysFixed() {
        val lines = (0 until InvoiceLineReviewContract.MAX_LINES).map { index ->
            line(seed = index + 1, position = index, description = "Producto ${index + 1}")
        }
        val last = lines.last()
        val summary = Summary(
            lineSum = "S/ 118.03",
            invoiceTotal = "S/ 118.00",
            exactDifference = "+S/ 0.03",
            hasDifference = true,
        )

        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = false,
                        lines = lines,
                        summary = summary,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText(summary.exactDifference).assertIsDisplayed()
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.ADD_LINE)
            .assertIsNotEnabled()
        composeRule.onNodeWithText(
            context.getString(R.string.line_review_limit_reached),
        ).assertIsDisplayed()

        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.LIST)
            .performScrollToNode(hasTestTag(InvoiceLineReviewTestTags.card(last.lineId)))
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.card(last.lineId))
            .assertIsDisplayed()
        // The summary is not a LazyColumn item: scrolling a hundred products cannot move it away.
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText(summary.exactDifference).assertIsDisplayed()
    }

    @Test
    fun requestedLastLineUsesHybridJumpAndStillCompletesFocusRequest() {
        val lines = (0 until InvoiceLineReviewContract.MAX_LINES).map { index ->
            line(seed = index + 1, position = index, description = "Producto ${index + 1}")
        }
        val last = lines.last()
        var handled = false

        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = false,
                        lines = lines,
                    ),
                    onAction = {},
                    requestedScrollLineId = last.lineId,
                    onRequestedScrollHandled = { handled = true },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { handled }
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.card(last.lineId))
            .assertIsDisplayed()
            .assertIsFocused()
            .assert(
                SemanticsMatcher("clears the one-shot live region after focus") { node ->
                    runCatching { node.config[SemanticsProperties.LiveRegion] }.isFailure
                },
            )
    }

    @Test
    fun deepRecycledCardAtTwoHundredPercentFontKeepsMetricsAndDeleteAction() {
        val lines = (0 until InvoiceLineReviewContract.MAX_LINES).map { index ->
            line(seed = index + 1, position = index, description = "Producto ${index + 1}")
        }
        val last = lines.last()
        var received: Action? = null
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    InvoiceLineReviewScreen(
                        state = State(
                            draftId = DRAFT_ID,
                            isLoading = false,
                            currencyLabel = "S/",
                            lines = lines,
                        ),
                        onAction = { action -> received = action },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.LIST)
            .performScrollToNode(hasTestTag(InvoiceLineReviewTestTags.card(last.lineId)))
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.card(last.lineId))
            .assert(
                SemanticsMatcher("keeps metrics after lazy reuse") { node ->
                    val spoken = node.config[SemanticsProperties.ContentDescription]
                        .joinToString(separator = " ")
                    "${context.getString(R.string.line_review_metric_cost)}: S/ 10.00" in spoken &&
                        "${context.getString(R.string.line_review_metric_total)}: S/ 11.80" in spoken
                },
            )
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.delete(last.lineId))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(Action.RequestDelete(last.lineId), received)
    }

    @Test
    fun reorderMenuAtTwoHundredPercentKeepsTouchTargetsTagsAndTalkBackCopy() {
        val lines = (1..3).map { seed ->
            line(seed = seed, position = seed - 1, description = "Producto $seed")
        }
        val middle = lines[1]
        var received: Action? = null
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    InvoiceLineReviewScreen(
                        state = State(
                            draftId = DRAFT_ID,
                            isLoading = false,
                            lines = lines,
                        ),
                        onAction = { action -> received = action },
                    )
                }
            }
        }

        val lineNumber = context.getString(R.string.line_review_line_number, 2)
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.LIST)
            .performScrollToNode(hasTestTag(InvoiceLineReviewTestTags.card(middle.lineId)))
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.moreActions(middle.lineId))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.line_review_more_actions_accessibility,
                lineNumber,
                "Producto 2",
            ),
        ).performClick()

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.moveUp(middle.lineId))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.line_review_move_up_accessibility,
                lineNumber,
                "Producto 2",
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.moveDown(middle.lineId))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(Action.MoveLineDown(middle.lineId), received)
    }

    @Test
    fun pendingFilterAndAccentInsensitiveSearchUpdateTheVisibleCards() {
        // Una fila no es revisada mientras su decisión fiscal siga UNKNOWN, aunque el OCR sea
        // HIGH. Este fixture fija EXCLUDED con IGV explícito; la segunda fila conserva blockers.
        val reviewed = line(
            seed = 1,
            position = 0,
            description = "Arroz extra",
            taxTreatment = InventoryTaxTreatment.EXCLUDED,
        )
        val pending = line(
            seed = 2,
            position = 1,
            description = "AZÚCAR RUBIA",
            confidence = Confidence.LOW,
            requiresReview = true,
        )
        var state by mutableStateOf(
            State(
                draftId = DRAFT_ID,
                isLoading = false,
                lines = listOf(reviewed, pending),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = state,
                    onAction = { action ->
                        state = when (action) {
                            Action.PendingFilterToggled -> state.copy(
                                pendingOnly = !state.pendingOnly,
                            )

                            is Action.SearchChanged -> state.copy(searchQuery = action.query)
                            else -> state
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.PENDING_FILTER)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.line_review_total_count, 2),
                ),
            )
            .performClick()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.line_review_pending_count, 1, 2),
                ),
            )
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.card(reviewed.lineId))
            .assertDoesNotExist()
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.card(pending.lineId))
            .assertIsDisplayed()

        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.SEARCH)
            .performTextInput("azucar")
        // El IME puede sacar la tarjeta del viewport de la LazyColumn. Buscarla desde el
        // contenedor valida que el filtro la conservó incluso si aún no está compuesta.
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.LIST)
            .performScrollToNode(
                hasTestTag(InvoiceLineReviewTestTags.card(pending.lineId)),
            )
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.card(pending.lineId))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.SEARCH)
            .performTextReplacement("aceite")
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.EMPTY_FILTER).assertIsDisplayed()
    }

    @Test
    fun deletionRequiresConfirmationAndTheSameStableLineCanBeRestored() {
        val original = line(seed = 1, position = 0, description = "Leche evaporada")
        var state by mutableStateOf(
            State(
                draftId = DRAFT_ID,
                isLoading = false,
                lines = listOf(original),
            ),
        )
        val received = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = state,
                    onAction = { action ->
                        received += action
                        state = when (action) {
                            is Action.RequestDelete -> state.copy(
                                pendingDeletionLineId = action.lineId,
                            )

                            Action.ConfirmDelete -> state.copy(
                                lines = emptyList(),
                                pendingDeletionLineId = null,
                                restorableDeletion = DeletedLine(
                                    lineId = original.lineId,
                                    description = original.field(FieldId.DESCRIPTION).value,
                                    formerPosition = original.position,
                                ),
                            )

                            Action.RestoreDeletedLine -> state.copy(
                                lines = listOf(original),
                                restorableDeletion = null,
                            )

                            else -> state
                        }
                    },
                )
            }
        }

        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.delete(original.lineId))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.DELETE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.line_review_delete_confirm),
        ).performClick()

        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.card(original.lineId))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.RESTORE_BANNER).assertIsDisplayed()
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.RESTORE).performClick()
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.card(original.lineId))
            .assertIsDisplayed()
        assertEquals(
            listOf(
                Action.RequestDelete(original.lineId),
                Action.ConfirmDelete,
                Action.RestoreDeletedLine,
            ),
            received,
        )
    }

    @Test
    fun editorExposesEveryFieldAndSeparatesOcrCalculatedAndWrittenValues() {
        val editable = line(
            seed = 1,
            position = 0,
            description = "Café molido",
            totalOrigin = ValueOrigin.WRITTEN,
        )
        var state by mutableStateOf(
            State(
                draftId = DRAFT_ID,
                isLoading = false,
                lines = listOf(editable),
                editor = Editor(editable),
                summary = Summary(
                    lineSum = "S/ 11.83",
                    invoiceTotal = "S/ 11.80",
                    exactDifference = "+S/ 0.03",
                    hasDifference = true,
                ),
            ),
        )
        val changes = mutableListOf<Action.EditorFieldChanged>()
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = state,
                    onAction = { action ->
                        if (action is Action.EditorFieldChanged) {
                            changes += action
                            val changedFields = state.editor!!.line.fields.map { field ->
                                if (field.id == action.field) {
                                    field.copy(value = action.value, origin = ValueOrigin.WRITTEN)
                                } else {
                                    field
                                }
                            }
                            val changedLine = state.editor!!.line.copy(fields = changedFields)
                            state = state.copy(
                                lines = listOf(changedLine),
                                editor = state.editor!!.copy(line = changedLine),
                            )
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.EDITOR)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.IsTraversalGroup,
                    true,
                ),
            )
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_SUMMARY)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(
                        context.getString(R.string.line_review_summary_sum_label) +
                            ": S/ 11.83. " +
                            context.getString(R.string.line_review_summary_difference_label) +
                            ": +S/ 0.03. " +
                            context.getString(
                                R.string.line_review_summary_differs,
                                "+S/ 0.03",
                            ),
                    ),
                ),
            )
        FieldId.entries.forEach { field ->
            composeRule
                .onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_LIST)
                .performScrollToNode(
                    hasTestTag(InvoiceLineReviewTestTags.editorField(field)),
                )
            composeRule
                .onNodeWithTag(InvoiceLineReviewTestTags.editorField(field))
                .assertIsDisplayed()
        }
        val ocrReference = context.getString(R.string.line_review_ocr_reference, "11.80")
        val calculatedReference = context.getString(
            R.string.line_review_calculated_reference,
            "11.70",
        )
        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.editorFieldOrigin(FieldId.TOTAL))
            .assert(
                SemanticsMatcher("exposes OCR and calculated references") { node ->
                    val spoken = node.config[SemanticsProperties.StateDescription]
                    ocrReference in spoken && calculatedReference in spoken
                },
            )
        composeRule.onNodeWithText(
            context.getString(R.string.line_review_source_written),
        ).assertIsDisplayed()

        composeRule
            .onNodeWithTag(InvoiceLineReviewTestTags.editorField(FieldId.TOTAL))
            .performTextReplacement("11.83")
        assertEquals(
            Action.EditorFieldChanged(FieldId.TOTAL, "11.83"),
            changes.last(),
        )
    }

    @Test
    fun editorFieldsSummaryAndDoneRemainReachableInACompactLargeTextWindow() {
        val editable = line(seed = 1, position = 0, description = "Café molido")
        composeRule.setContent {
            val density = LocalDensity.current
            val containerSize = LocalWindowInfo.current.containerSize
            val compactScale = maxOf(
                containerSize.width / (360f * density.density),
                containerSize.height / (240f * density.density),
                1f,
            )
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density * compactScale,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    Box(Modifier.width(360.dp).height(240.dp)) {
                        InvoiceLineReviewScreen(
                            state = State(
                                draftId = DRAFT_ID,
                                isLoading = false,
                                lines = listOf(editable),
                                editor = Editor(editable),
                                summary = Summary(
                                    lineSum = "S/ 11.80",
                                    invoiceTotal = "S/ 11.80",
                                    exactDifference = "S/ 0.00",
                                    hasDifference = false,
                                ),
                            ),
                            onAction = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_LIST)
            .performScrollToNode(
                hasTestTag(InvoiceLineReviewTestTags.editorField(FieldId.TOTAL)),
            )
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.editorField(FieldId.TOTAL))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_SUMMARY)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_DONE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun incompleteSummaryNeverAnnouncesThatAmountsMatch() {
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = false,
                        lines = listOf(line(seed = 1, position = 0)),
                        summary = Summary(),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.line_review_summary_incomplete),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.line_review_summary_matches),
        ).assertDoesNotExist()
    }

    @Test
    fun exemptLineShowsTaxMeaningInsteadOfCurrencyPending() {
        val exempt = line(
            seed = 1,
            position = 0,
            taxTreatment = InventoryTaxTreatment.EXEMPT,
            igvValue = "",
        )
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = false,
                        currencyLabel = "PEN",
                        lines = listOf(exempt),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.line_review_tax_exempt_value),
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("PEN Pendiente").assertDoesNotExist()
    }

    @Test
    fun compactMetricTextNodesKeepVisibleValuesAndCompleteAccessibleDescription() {
        val reviewed = line(seed = 1, position = 0)
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = false,
                        currencyLabel = "S/",
                        lines = listOf(reviewed),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceLineReviewTestTags.card(reviewed.lineId))
            .assert(
                SemanticsMatcher("keeps all spoken card metrics") { node ->
                    val spoken = node.config[SemanticsProperties.ContentDescription]
                        .joinToString(separator = " ")
                    "Producto 1" in spoken &&
                        "Cantidad y unidad: 1 NIU" in spoken &&
                        "Costo: S/ 10.00" in spoken &&
                        "IGV: S/ 1.80" in spoken &&
                        "Total: S/ 11.80" in spoken
                },
            )
        composeRule.onNodeWithText(
            context.getString(
                R.string.line_review_metric_value,
                context.getString(R.string.line_review_metric_cost),
                "S/ 10.00",
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.line_review_metric_value,
                context.getString(R.string.line_review_metric_igv),
                "S/ 1.80",
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.line_review_metric_value,
                context.getString(R.string.line_review_metric_total),
                "S/ 11.80",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun repeatedLineActionsExposeTheLineAndProductToTalkBack() {
        val reviewed = line(seed = 1, position = 0, description = "Café molido")
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceLineReviewScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = false,
                        lines = listOf(reviewed),
                    ),
                    onAction = {},
                )
            }
        }

        val lineNumber = context.getString(R.string.line_review_line_number, 1)
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.line_review_edit_accessibility,
                lineNumber,
                "Café molido",
            ),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.line_review_delete_accessibility,
                lineNumber,
                "Café molido",
            ),
        ).performScrollTo().assertIsDisplayed()
    }

    private fun line(
        seed: Int,
        position: Int,
        description: String = "Producto $seed",
        confidence: Confidence = Confidence.HIGH,
        requiresReview: Boolean = false,
        totalOrigin: ValueOrigin = ValueOrigin.OCR,
        taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.UNKNOWN,
        igvValue: String = "1.80",
    ): Line {
        val values = mapOf(
            FieldId.DESCRIPTION to description,
            FieldId.CODE to "SKU-$seed",
            FieldId.QUANTITY to "1",
            FieldId.UNIT to "NIU",
            FieldId.UNIT_COST to "10.00",
            FieldId.DISCOUNT to "0.00",
            FieldId.IGV to igvValue,
            FieldId.TOTAL to "11.80",
        )
        return Line(
            lineId = lineId(seed),
            position = position,
            fields = FieldId.entries.map { field ->
                Field(
                    id = field,
                    value = values.getValue(field),
                    origin = if (field == FieldId.TOTAL) totalOrigin else ValueOrigin.OCR,
                    ocrValue = values.getValue(field),
                    calculatedValue = if (field == FieldId.TOTAL) "11.70" else null,
                    confidence = confidence,
                )
            },
            confidence = confidence,
            confidencePercent = when (confidence) {
                Confidence.HIGH -> 96
                Confidence.MEDIUM -> 80
                Confidence.LOW -> 55
                Confidence.UNKNOWN -> null
            },
            requiresReview = requiresReview,
            taxTreatment = taxTreatment,
        )
    }

    private fun lineId(seed: Int): LineId = LineId.from(
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed)),
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
    }
}
