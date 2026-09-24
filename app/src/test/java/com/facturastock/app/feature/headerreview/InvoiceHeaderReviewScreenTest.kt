package com.facturastock.app.feature.headerreview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.facturastock.app.resources.*
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Action
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Confidence
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Evidence
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Field
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldError
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldId
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Page
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.State
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Test

class InvoiceHeaderReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun str(resource: StringResource, vararg args: Any): String =
        runBlocking { getString(resource, *args) }

    @Test
    fun lowConfidenceUsesVisibleTextAndTalkBackStateDescription() {
        val lowText = str(Res.string.header_review_low_confidence)
        val state = validState(
            Field(
                id = FieldId.RUC,
                value = "20123456786",
                confidence = Confidence.LOW,
                evidence = RUC_EVIDENCE,
            ),
        )

        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHeaderReviewScreen(state = state, onAction = {})
            }
        }

        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.field(FieldId.RUC))
            .performScrollTo()
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    lowText,
                ),
            )
        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.confidence(FieldId.RUC))
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    lowText,
                ),
            )
        composeRule.onNodeWithText(lowText).assertIsDisplayed()
        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.evidence(FieldId.RUC))
            .assertHasClickAction()
    }

    @Test
    fun evidenceActionShowsExactOcrTextPageAndAlternatives() {
        var state by mutableStateOf(
            validState(
                Field(
                    id = FieldId.RUC,
                    value = "20123456786",
                    confidence = Confidence.LOW,
                    evidence = RUC_EVIDENCE,
                ),
            ),
        )
        val received = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHeaderReviewScreen(
                    state = state,
                    onAction = { action ->
                        received += action
                        state = when (action) {
                            is Action.ShowEvidence -> state.copy(evidenceField = action.field)
                            Action.DismissEvidence -> state.copy(evidenceField = null)
                            else -> state
                        }
                    },
                )
            }
        }

        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.evidence(FieldId.RUC))
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.EVIDENCE_DIALOG)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.IsTraversalGroup,
                    true,
                ),
            )
        composeRule.onNodeWithText(RUC_EVIDENCE.rawText).assertIsDisplayed()
        composeRule.onNodeWithText(
            str(Res.string.header_review_evidence_page, 1),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            str(
                Res.string.header_review_alternative_value,
                RUC_EVIDENCE.alternatives.single(),
            ),
        ).assertIsDisplayed()

        composeRule.onNodeWithText(str(Res.string.header_review_close)).performClick()
        composeRule.onNodeWithTag(InvoiceHeaderReviewTestTags.EVIDENCE_DIALOG).assertDoesNotExist()
        assertEquals(
            listOf(
                Action.ShowEvidence(FieldId.RUC),
                Action.DismissEvidence,
            ),
            received.filter { action ->
                action is Action.ShowEvidence || action == Action.DismissEvidence
            },
        )
    }

    @Test
    fun documentThumbnailIsNamedClickableAndOpensTheExpandableDocument() {
        var state by mutableStateOf(validState())
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHeaderReviewScreen(
                    state = state,
                    onAction = { action ->
                        state = when (action) {
                            is Action.ShowExpandedPage -> state.copy(
                                expandedPageIndex = action.index,
                            )
                            Action.DismissExpandedPage -> state.copy(expandedPageIndex = null)
                            else -> state
                        }
                    },
                )
            }
        }

        val description = str(Res.string.header_review_expand_page_description, 1)
        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.DOCUMENT_THUMBNAIL)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(description),
                ),
            )
            .performClick()

        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.EXPANDED_DOCUMENT)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    str(Res.string.header_review_expanded_page_title, 1),
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.IsTraversalGroup,
                    true,
                ),
            )
        composeRule.onNodeWithText(str(Res.string.header_review_close)).performClick()
        composeRule.onNodeWithTag(InvoiceHeaderReviewTestTags.EXPANDED_DOCUMENT).assertDoesNotExist()
    }

    @Test
    fun requiredErrorIsDeferredUntilContinueAndCtaExplainsTheCorrection() {
        var state by mutableStateOf(
            validState(
                Field(
                    id = FieldId.RUC,
                    value = "",
                    confidence = Confidence.HIGH,
                    error = FieldError.REQUIRED,
                ),
            ),
        )
        val error = str(Res.string.header_review_error_required)
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHeaderReviewScreen(
                    state = state,
                    onAction = { action ->
                        if (action == Action.ReviewProducts) {
                            state = state.copy(continueAttempted = true)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText(error).assertDoesNotExist()
        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.REVIEW_PRODUCTS)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.BLOCKING_EXPLANATION)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(error)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            str(
                Res.string.header_review_blocking_message,
                str(Res.string.header_review_ruc_label),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun reviewProductsActionRemainsVisibleWhenTheDecimalKeyboardIsRequested() {
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHeaderReviewScreen(state = validState(), onAction = {})
            }
        }

        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.LIST)
            .performScrollToNode(
                hasTestTag(InvoiceHeaderReviewTestTags.field(FieldId.TOTAL)),
            )
        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.field(FieldId.TOTAL))
            .performClick()
        composeRule.waitForIdle()

        // La barra está fuera del LazyColumn: al enfocar un campo decimal para escribirlo la
        // acción sigue dentro del área visible (en escritorio no hay teclado en pantalla).
        composeRule
            .onNodeWithTag(InvoiceHeaderReviewTestTags.REVIEW_PRODUCTS)
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    private fun validState(vararg overrides: Field): State {
        val replacements = overrides.associateBy(Field::id)
        val values = mapOf(
            FieldId.RUC to "20123456786",
            FieldId.SUPPLIER to "Proveedor Andino SAC",
            FieldId.DOCUMENT_TYPE to PurchaseDocumentType.INVOICE.name,
            FieldId.SERIES to "F001",
            FieldId.NUMBER to "42",
            FieldId.ISSUE_DATE to "10/08/2026",
            FieldId.CURRENCY to "PEN",
            FieldId.SUBTOTAL to "100.00",
            FieldId.IGV to "18.00",
            FieldId.OTHER_CHARGES to "0.00",
            FieldId.TOTAL to "118.00",
        )
        return State(
            draftId = DRAFT_ID,
            isLoading = false,
            pages = listOf(PAGE),
            fields = FieldId.entries.map { id ->
                replacements[id] ?: Field(
                    id = id,
                    value = values.getValue(id),
                    confidence = Confidence.HIGH,
                )
            },
        )
    }

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000023"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000024"),
        )
        val PAGE = Page(
            imageId = IMAGE_ID,
            relativePath = "draft_images/review/page-0.jpg",
            pageIndex = 0,
            rotationDegrees = 0,
        )
        val RUC_EVIDENCE = Evidence(
            rawText = "RUC OCR: 20123456786",
            pageIndex = 0,
            sourceImageId = IMAGE_ID,
            alternatives = listOf("20123456788"),
        )
    }
}
