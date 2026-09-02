package com.facturastock.app.feature.preparation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseDuplicateAssessment
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateMatch
import com.facturastock.app.domain.model.PurchaseDuplicateProbe
import com.facturastock.app.domain.model.PurchaseDuplicateReason
import com.facturastock.app.domain.model.PurchaseReconciliationAdjustment
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.RecordedPurchase
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PreparationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exactDuplicateShowsExistingPurchaseAndHasNoNormalConfirmationAction() {
        val actions = mutableListOf<PreparationContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreparationScreen(
                    state = PreparationContract.State(
                        draftId = DRAFT_ID,
                        duplicateAssessment = exactAssessment(),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Este comprobante ya está registrado").assertIsDisplayed()
        composeRule.onNodeWithText("Comprobante: F001-000123").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar compra").assertDoesNotExist()
        composeRule.onNodeWithText("Ver compra existente").performClick()

        assertEquals(listOf(PreparationContract.Action.OpenExistingSelected), actions)
    }

    @Test
    fun exactDuplicateOverrideRequiresAnAuditableReasonBeforeConfirmation() {
        var state by mutableStateOf(
            PreparationContract.State(
                draftId = DRAFT_ID,
                duplicateAssessment = exactAssessment(),
            ),
        )
        val actions = mutableListOf<PreparationContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreparationScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        state = when (action) {
                            PreparationContract.Action.RequestOverride ->
                                state.copy(showOverrideDialog = true)
                            is PreparationContract.Action.OverrideReasonChanged ->
                                state.copy(overrideReason = action.value)
                            PreparationContract.Action.ConfirmOverride ->
                                state.copy(showOverrideDialog = false)
                            else -> state
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(PreparationTestTags.OVERRIDE_ACTION).performClick()
        composeRule.onNodeWithTag(PreparationTestTags.OVERRIDE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(PreparationTestTags.OVERRIDE_CONFIRM).assertIsNotEnabled()

        val reason = "Documento físico distinto verificado por gerencia"
        composeRule.onNodeWithTag(PreparationTestTags.OVERRIDE_REASON)
            .performTextInput(reason)
        composeRule.onNodeWithTag(PreparationTestTags.OVERRIDE_CONFIRM)
            .assertIsEnabled()
            .performClick()

        composeRule.onNodeWithTag(PreparationTestTags.OVERRIDE_DIALOG).assertDoesNotExist()
        assertEquals(reason, state.overrideReason)
        assertEquals(PreparationContract.Action.RequestOverride, actions.first())
        assertEquals(PreparationContract.Action.ConfirmOverride, actions.last())
    }

    @Test
    fun probableDuplicateShowsWarningAndAllowsNormalConfirmation() {
        val actions = mutableListOf<PreparationContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreparationScreen(
                    state = PreparationContract.State(
                        draftId = DRAFT_ID,
                        duplicateAssessment = probableAssessment(),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Encontramos una compra similar").assertIsDisplayed()
        composeRule.onNodeWithText("Registrar de todos modos").assertDoesNotExist()
        composeRule.onNodeWithText("Confirmar compra").performClick()

        assertEquals(listOf(PreparationContract.Action.Confirm), actions)
    }

    @Test
    fun canonicalDemoAmountsAndAdjustmentRemainVisibleAtIrreversibleConfirmation() {
        val original = exactAssessment()
        val assessment = original.copy(
            probe = original.probe.copy(
                total = Money.ofMinor(9_783, PEN),
                reconciliationAdjustment = PurchaseReconciliationAdjustment(
                    amount = Money.ofMinor(3, PEN),
                    reason = DEMO_REASON,
                ),
            ),
            match = null,
        )
        val adjustment = requireNotNull(assessment.probe.reconciliationAdjustment)
        composeRule.setContent {
            FacturaStockTheme {
                PreparationScreen(
                    state = PreparationContract.State(
                        draftId = DRAFT_ID,
                        duplicateAssessment = assessment,
                    ),
                    onAction = {},
                )
            }
        }

        listOf(
            "Total objetivo: ${assessment.probe.total.formatForDisplay()}",
            "Ajuste aceptado: ${adjustment.amount.formatSignedForDisplay()}",
            "Motivo: $DEMO_REASON",
        ).forEach { expectedText ->
            composeRule.onNodeWithText(expectedText).assertIsDisplayed()
        }
    }

    @Test
    fun storageFullExplainsRecoveryAndOffersRetryWithoutDiscardingTheSnapshot() {
        val actions = mutableListOf<PreparationContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreparationScreen(
                    state = PreparationContract.State(
                        draftId = DRAFT_ID,
                        expectedPreparedLogicalHash = "a".repeat(64),
                        duplicateAssessment = exactAssessment().copy(match = null),
                        failure = PreparationContract.Failure.STORAGE_FULL,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(
            "No hay espacio suficiente. Libera espacio y reintenta; " +
                "tus datos guardados y el cambio en pantalla se conservan.",
        ).assertIsDisplayed()
        composeRule
            .onNodeWithTag(PreparationTestTags.LIST)
            .performScrollToNode(hasTestTag(PreparationTestTags.RETRY))
        composeRule.onNodeWithTag(PreparationTestTags.RETRY).performClick()
        assertEquals(listOf(PreparationContract.Action.Retry), actions)
    }

    private fun exactAssessment(): PurchaseDuplicateAssessment {
        val purchase = RecordedPurchase(
            purchaseId = PURCHASE_ID,
            businessId = BUSINESS_ID,
            sourceDraftId = OLD_DRAFT_ID,
            supplierId = SUPPLIER_ID,
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor SAC",
            documentType = PurchaseDocumentType.INVOICE,
            documentSeries = "F001",
            documentNumber = "000123",
            issueDate = DATE,
            currency = PEN,
            total = Money.ofMinor(1_000, PEN),
            status = PurchaseStatus.POSTED,
        )
        val probe = PurchaseDuplicateProbe(
            draftId = DRAFT_ID,
            preparedLogicalHash = "a".repeat(64),
            businessId = BUSINESS_ID,
            supplierId = SUPPLIER_ID,
            supplierRuc = "20123456789",
            documentType = PurchaseDocumentType.INVOICE,
            documentSeries = "F001",
            documentNumber = "000123",
            issueDate = DATE,
            currency = PEN,
            total = Money.ofMinor(1_000, PEN),
            imageHashes = emptySet(),
        )
        return PurchaseDuplicateAssessment(
            probe = probe,
            match = PurchaseDuplicateMatch(
                kind = PurchaseDuplicateKind.EXACT,
                purchase = purchase,
                reasons = setOf(
                    PurchaseDuplicateReason.SAME_BUSINESS,
                    PurchaseDuplicateReason.SAME_SUPPLIER,
                    PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
                    PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER,
                ),
            ),
        )
    }

    private fun probableAssessment(): PurchaseDuplicateAssessment {
        val exact = exactAssessment()
        val exactMatch = requireNotNull(exact.match)
        return exact.copy(
            match = exactMatch.copy(
                kind = PurchaseDuplicateKind.PROBABLE,
                purchase = exactMatch.purchase.copy(documentNumber = "999"),
                reasons = setOf(
                    PurchaseDuplicateReason.SAME_BUSINESS,
                    PurchaseDuplicateReason.SAME_SUPPLIER,
                    PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
                    PurchaseDuplicateReason.SAME_SERIES,
                    PurchaseDuplicateReason.SAME_ISSUE_DATE,
                    PurchaseDuplicateReason.SAME_TOTAL,
                ),
            ),
        )
    }

    private companion object {
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val DATE: LocalDate = LocalDate.of(2026, 8, 12)
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DRAFT_ID: DraftId = DraftId.from(uuid(2))
        val OLD_DRAFT_ID: DraftId = DraftId.from(uuid(3))
        val SUPPLIER_ID: SupplierId = SupplierId.from(uuid(4))
        val PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(5))
        const val DEMO_REASON =
            "[DEMO] Diferencia controlada entre suma calculada y total del comprobante"

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
