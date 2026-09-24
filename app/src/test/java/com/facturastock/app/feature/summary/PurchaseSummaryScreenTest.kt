package com.facturastock.app.feature.summary

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Action
import com.facturastock.app.feature.summary.PurchaseSummaryContract.BlockerItem
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Mode
import com.facturastock.app.feature.summary.PurchaseSummaryContract.State
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PurchaseSummaryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

    @Test
    fun preparedSummaryShowsFrozenSnapshotAndBothExplicitActions() {
        val purchase = preparedPurchase()
        val received = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseSummaryScreen(
                    state = State(
                        draftId = purchase.draftId,
                        isLoading = false,
                        mode = Mode.PREPARED,
                        prepared = purchase,
                    ),
                    onAction = received::add,
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseSummaryTestTags.PREPARED_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("Proveedor Preparado SAC").assertIsDisplayed()
        composeRule.onNodeWithText("F001-42", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.preparedLine(0))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Arroz preparado").assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.HASH)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("abcdef01").assertIsDisplayed()

        composeRule.onNodeWithTag(PurchaseSummaryTestTags.REGISTER)
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.REOPEN)
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf(Action.RegisterSelected, Action.ReopenSelected), received)
        composeRule.onNodeWithText(str(Res.string.summary_action_register))
            .assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.summary_action_reopen))
            .assertIsDisplayed()
    }

    @Test
    fun preparedActionsAreDisabledWhileInvalidationIsRunning() {
        val purchase = preparedPurchase()
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseSummaryScreen(
                    state = State(
                        draftId = purchase.draftId,
                        isLoading = false,
                        mode = Mode.PREPARED,
                        prepared = purchase,
                        isBusy = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseSummaryTestTags.REGISTER).assertIsNotEnabled()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.REOPEN).assertIsNotEnabled()
    }

    @Test
    fun missingSalePriceBlockerExplainsAffectedLine() {
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseSummaryScreen(
                    state = State(
                        draftId = DraftId.from(uuid(19)),
                        isLoading = false,
                        blockers = listOf(
                            BlockerItem(
                                code = PrepareBlockerCode.LINE_SALE_PRICE_REQUIRED,
                                linePosition = 3,
                            ),
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(
            str(Res.string.summary_blocker_line_sale_price_required, 3),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.PREPARE).assertIsNotEnabled()
    }

    @Test
    fun threeCentAdjustmentRequiresExplicitConfirmationAndReasonBeforePrepare() {
        val received = mutableListOf<Action>()
        val reason = "Diferencia controlada indicada por el comprobante demo"
        var state by mutableStateOf(
            State(
                draftId = DraftId.from(uuid(20)),
                isLoading = false,
                blockers = listOf(BlockerItem(PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED)),
                roundingDifference = "+S/ 0.03",
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseSummaryScreen(
                    state = state,
                    onAction = { action ->
                        received += action
                        state = when (action) {
                            is Action.RoundingAcceptanceChanged ->
                                state.copy(roundingAccepted = action.accepted)
                            is Action.AdjustmentReasonChanged ->
                                state.copy(adjustmentReason = action.reason)
                            else -> state
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("+S/ 0.03", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.PREPARE).assertIsNotEnabled()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.ADJUSTMENT_CONFIRMATION)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
            )
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.ADJUSTMENT_REASON)
            .performScrollTo()
            .assertIsDisplayed()
            .performTextInput(reason)
        composeRule.onNodeWithTag(PurchaseSummaryTestTags.PREPARE)
            .assertIsEnabled()
            // Invoca la acción semántica directamente: el IME puede cubrir físicamente el botón
            // durante una suite completa aunque Compose ya lo reporte visible y habilitado.
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    Action.RoundingAcceptanceChanged(true),
                    Action.AdjustmentReasonChanged(reason),
                    Action.PrepareSelected,
                ),
                received,
            )
        }
    }

    private fun preparedPurchase(): PreparedPurchase {
        val pen = CurrencyCode.of("PEN")
        return PreparedPurchase(
            draftId = DraftId.from(uuid(1)),
            businessId = BusinessId.from(uuid(2)),
            supplierId = null,
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor Preparado SAC",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = "F001-42",
            issueDate = LocalDate.of(2026, 8, 12),
            currency = pen,
            lines = listOf(
                PreparedPurchaseLine(
                    lineId = LineId.from(uuid(3)),
                    position = 0,
                    productId = ProductId.from(uuid(4)),
                    unitId = UnitId.from(uuid(5)),
                    description = "Arroz preparado",
                    quantity = Quantity.of("2"),
                    lineTotal = Money.ofMinor(1_000, pen),
                ),
            ),
            subtotal = Money.ofMinor(847, pen),
            tax = Money.ofMinor(153, pen),
            otherCharges = null,
            total = Money.ofMinor(1_000, pen),
            acceptedWarnings = emptyList(),
            logicalHash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            preparedAt = Instant.parse("2026-08-12T12:00:00Z"),
        )
    }

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
