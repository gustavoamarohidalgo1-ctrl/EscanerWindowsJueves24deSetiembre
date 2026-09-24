package com.facturastock.app.feature.linking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.facturastock.app.resources.*
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.ProductMatchCandidate
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.feature.linking.ProductLinkingContract.Action
import com.facturastock.app.feature.linking.ProductLinkingContract.CreateForm
import com.facturastock.app.feature.linking.ProductLinkingContract.LineLinking
import com.facturastock.app.feature.linking.ProductLinkingContract.LinkStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.SearchStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.State
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Test

class ProductLinkingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun str(resource: StringResource, vararg args: Any): String =
        runBlocking { getString(resource, *args) }

    @Test
    fun candidatesShowTheirReasonAndLinkingEmitsTheConfirmation() {
        val pending = lineLinking(seed = 1, position = 0, status = LinkStatus.NEEDS_CHOICE)
        val candidate = candidate(seed = 10, name = "Azúcar Rubia")
        var state by mutableStateOf(
            State(
                draftId = DRAFT_ID,
                initialLineId = pending.lineId,
                isLoading = false,
                lines = listOf(pending),
                currentLineId = pending.lineId,
                candidates = listOf(candidate),
                searchQuery = pending.description,
                units = listOf(unit(seed = 60)),
            ),
        )
        val received = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                ProductLinkingScreen(
                    state = state,
                    onAction = { action ->
                        received += action
                        state = reduce(state, action)
                    },
                )
            }
        }

        composeRule
            .onNodeWithTag(ProductLinkingTestTags.candidate(candidate.product.productId))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Azúcar Rubia").assertIsDisplayed()
        composeRule.onNodeWithText(
            str(Res.string.linking_reason_similar_name),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(ProductLinkingTestTags.SKIP).assertIsDisplayed()
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE).assertIsDisplayed()
        composeRule.onNodeWithTag(ProductLinkingTestTags.CONTINUE).assertIsNotEnabled()

        composeRule
            .onNodeWithTag(ProductLinkingTestTags.candidateLink(candidate.product.productId))
            .performClick()

        assertEquals(
            Action.CandidateConfirmed(candidate.product.productId),
            received.last(),
        )
        composeRule.onNodeWithText(
            str(Res.string.linking_status_confirmed),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(ProductLinkingTestTags.CONTINUE).assertIsEnabled()
    }

    @Test
    fun failedSearchHidesPreviousCandidatesAndOffersRetry() {
        val pending = lineLinking(seed = 1, position = 0, status = LinkStatus.NEEDS_CHOICE)
        val previousCandidate = candidate(seed = 10, name = "Azúcar Rubia")
        val received = mutableListOf<Action>()

        composeRule.setContent {
            FacturaStockTheme {
                ProductLinkingScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        initialLineId = pending.lineId,
                        isLoading = false,
                        lines = listOf(pending),
                        currentLineId = pending.lineId,
                        candidates = emptyList(),
                        searchQuery = "Leche Fresca",
                        searchStatus = SearchStatus.FAILED,
                        units = listOf(unit(seed = 60)),
                    ),
                    onAction = received::add,
                )
            }
        }

        composeRule.onNodeWithTag(ProductLinkingTestTags.SEARCH_FAILURE).assertIsDisplayed()
        composeRule.onNodeWithText(
            str(Res.string.linking_search_error_message),
        ).assertIsDisplayed()
        composeRule
            .onNodeWithTag(ProductLinkingTestTags.candidate(previousCandidate.product.productId))
            .assertDoesNotExist()
        composeRule.onNodeWithText(str(Res.string.action_retry)).performClick()

        assertEquals(Action.RetrySearch, received.last())
    }

    @Test
    fun skipLeavesTheLinePendingWithoutContinuing() {
        val pending = lineLinking(seed = 1, position = 0, status = LinkStatus.NO_MATCH)
        var state by mutableStateOf(
            State(
                draftId = DRAFT_ID,
                initialLineId = pending.lineId,
                isLoading = false,
                lines = listOf(pending),
                currentLineId = pending.lineId,
                searchQuery = pending.description,
                units = listOf(unit(seed = 60)),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                ProductLinkingScreen(
                    state = state,
                    onAction = { action -> state = reduce(state, action) },
                )
            }
        }

        composeRule.onNodeWithText(
            str(Res.string.linking_no_candidates),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(ProductLinkingTestTags.SKIP).performClick()
        composeRule.onNodeWithText(
            str(Res.string.linking_status_skipped),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(ProductLinkingTestTags.CONTINUE).assertIsEnabled()
    }

    @Test
    fun createDialogValidatesTheFormAndReportsChanges() {
        val pending = lineLinking(seed = 1, position = 0, status = LinkStatus.NO_MATCH)
        var state by mutableStateOf(
            State(
                draftId = DRAFT_ID,
                initialLineId = pending.lineId,
                isLoading = false,
                lines = listOf(pending),
                currentLineId = pending.lineId,
                searchQuery = pending.description,
                units = listOf(unit(seed = 60)),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                ProductLinkingScreen(
                    state = state,
                    onAction = { action -> state = reduce(state, action) },
                )
            }
        }

        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE).performClick()
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_DIALOG)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.IsTraversalGroup,
                    true,
                ),
            )
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_NAME).assertIsDisplayed()

        // Sin unidad seleccionada el envío muestra el error y no cierra el diálogo.
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_SUBMIT).performClick()
        composeRule.onNodeWithText(
            str(Res.string.linking_create_error_unit),
        ).assertIsDisplayed()

        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_NAME)
            .performTextReplacement("Galleta Sorpresa")
        assertEquals(
            "Galleta Sorpresa",
            state.createForm?.name,
        )

        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_DISMISS).performClick()
        composeRule
            .onNodeWithTag(ProductLinkingTestTags.CREATE_DIALOG)
            .assertDoesNotExist()
        assertTrue(state.createForm == null)
    }

    @Test
    fun validNewProductSelectionCreatesAndLinksWithoutLeavingTheReview() {
        val pending = lineLinking(seed = 2, position = 0, status = LinkStatus.NO_MATCH)
        val inventoryUnit = unit(seed = 60)
        var state by mutableStateOf(
            State(
                draftId = DRAFT_ID,
                initialLineId = pending.lineId,
                isLoading = false,
                lines = listOf(pending),
                currentLineId = pending.lineId,
                searchQuery = pending.description,
                units = listOf(inventoryUnit),
            ),
        )
        val received = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                ProductLinkingScreen(
                    state = state,
                    onAction = { action ->
                        received += action
                        state = reduce(state, action)
                    },
                )
            }
        }

        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE).performClick()
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_NAME)
            .performTextReplacement("Galleta Sorpresa")
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_UNIT).performClick()
        composeRule.onNodeWithText(
            str(
                Res.string.linking_unit_option,
                inventoryUnit.name,
                inventoryUnit.code,
            ),
        ).performClick()
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_SALE_PRICE)
            .performTextReplacement("8.50")

        assertTrue(requireNotNull(state.createForm).isValid)
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_SUBMIT).performClick()

        assertEquals(Action.CreateSubmitted, received.last())
        composeRule.onNodeWithTag(ProductLinkingTestTags.CREATE_DIALOG).assertDoesNotExist()
        // El producto enlazado aparece tanto en la tarjeta activa como en el resumen de la línea.
        composeRule.onAllNodesWithText(
            str(Res.string.linking_linked_to, "Galleta Sorpresa"),
        ).assertCountEquals(2)
        composeRule.onNodeWithTag(ProductLinkingTestTags.CONTINUE).assertIsEnabled()
    }

    @Test
    fun missingLegacyPriceUsesAnAccessibleRequiredPriceDialog() {
        val pending = lineLinking(seed = 3, position = 0, status = LinkStatus.NEEDS_CHOICE)
            .copy(requiresSalePrice = true)
        val product = candidate(seed = 12, name = "Café molido").product.copy(salePrice = null)
        val form = ProductLinkingContract.SalePriceForm(
            product = product,
            lineId = pending.lineId,
            confidencePermille = 1_000,
            reason = null,
            value = "",
            submitAttempted = true,
        )
        val received = mutableListOf<Action>()

        composeRule.setContent {
            FacturaStockTheme {
                ProductLinkingScreen(
                    state = State(
                        draftId = DRAFT_ID,
                        isLoading = false,
                        lines = listOf(pending),
                        currentLineId = pending.lineId,
                        salePriceForm = form,
                        salePriceFailure = ProductLinkingContract.SalePriceFailure.INVALID_PRICE,
                    ),
                    onAction = received::add,
                )
            }
        }

        composeRule.onNodeWithTag(ProductLinkingTestTags.SALE_PRICE_DIALOG)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true),
            )
        composeRule.onNodeWithTag(ProductLinkingTestTags.SALE_PRICE_FIELD)
            .assertIsDisplayed()
            .performTextReplacement("12.50")
        assertEquals(Action.SalePriceChanged("12.50"), received.last())
        composeRule.onNodeWithTag(ProductLinkingTestTags.SKIP).assertIsNotEnabled()
    }

    /** Reductor mínimo del test: replica las transiciones visibles sin tocar repositorios. */
    private fun reduce(state: State, action: Action): State = when (action) {
        is Action.CandidateConfirmed -> {
            val productName = state.candidates
                .firstOrNull { it.product.productId == action.productId }
                ?.product
                ?.name
            state.copy(
                lines = state.lines.map { line ->
                    if (line.lineId == state.currentLineId) {
                        line.copy(
                            status = LinkStatus.CONFIRMED,
                            linkedProductName = productName,
                        )
                    } else {
                        line
                    }
                },
                candidates = emptyList(),
            )
        }

        Action.SkipLine -> state.copy(
            lines = state.lines.map { line ->
                if (line.lineId == state.currentLineId) {
                    line.copy(status = LinkStatus.SKIPPED)
                } else {
                    line
                }
            },
        )

        Action.OpenCreateForm -> state.copy(
            createForm = CreateForm(name = state.currentLine?.description.orEmpty()),
        )

        is Action.CreateFormChanged -> state.copy(createForm = action.form)

        Action.CreateSubmitted -> {
            val form = state.createForm
            if (form?.isValid == true) {
                state.copy(
                    lines = state.lines.map { line ->
                        if (line.lineId == state.currentLineId) {
                            line.copy(
                                status = LinkStatus.CONFIRMED,
                                linkedProductName = form.name.trim(),
                            )
                        } else {
                            line
                        }
                    },
                    createForm = null,
                )
            } else {
                state.copy(createForm = form?.copy(submitAttempted = true))
            }
        }

        Action.CreateFormDismissed -> state.copy(createForm = null)

        else -> state
    }

    private fun lineLinking(
        seed: Int,
        position: Int,
        status: LinkStatus,
    ): LineLinking = LineLinking(
        lineId = LineId.from(uuid(seed)),
        position = position,
        description = "AZUCAR RUBIA X KG",
        code = "COD-77",
        status = status,
        linkedProductName = null,
        linkReason = null,
    )

    private fun candidate(seed: Int, name: String): ProductMatchCandidate = ProductMatchCandidate(
        product = Product(
            productId = ProductId.from(uuid(seed)),
            businessId = BUSINESS_ID,
            unitId = UnitId.from(uuid(60)),
            name = name,
            status = CatalogStatus.ACTIVE,
            createdAt = NOW,
            updatedAt = NOW,
            salePrice = Money.fromMajor("8.50", CurrencyCode.of("PEN")),
        ),
        reason = ProductMatchReason.SIMILAR_NAME,
        confidencePermille = 853,
    )

    private fun unit(seed: Int): UnitOfMeasure = UnitOfMeasure(
        unitId = UnitId.from(uuid(seed)),
        businessId = BUSINESS_ID,
        code = "NIU",
        name = "Unidad",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
        )
    }
}
