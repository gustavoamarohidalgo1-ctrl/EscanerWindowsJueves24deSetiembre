package com.facturastock.app.feature.purchases

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseVoidImpact
import com.facturastock.app.domain.model.PurchaseVoidPreview
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PurchaseVoidScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewShowsAuthorizedRoleEveryImpactAndNegativeBalanceBeforeConfirmation() {
        val preview = preview()
        val actions = mutableListOf<PurchaseVoidContract.Action>()
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    PurchaseVoidContract.State(
                        purchaseId = preview.purchaseId,
                        preview = preview,
                    ),
                )
            }
            FacturaStockTheme {
                PurchaseVoidScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        state = when (action) {
                            is PurchaseVoidContract.Action.ReasonChanged -> state.copy(
                                reason = action.reason,
                            )
                            is PurchaseVoidContract.Action.ConfirmationChanged -> state.copy(
                                confirmed = action.confirmed,
                            )
                            else -> state
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasTestTag(PurchaseVoidTestTags.AUTHORIZED_ROLE))
        composeRule.onNodeWithTag(PurchaseVoidTestTags.AUTHORIZED_ROLE)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Rol autorizado: Propietario").assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasTestTag(PurchaseVoidTestTags.NEGATIVE_WARNING))
        composeRule.onNodeWithTag(PurchaseVoidTestTags.NEGATIVE_WARNING)
            .assertIsDisplayed()
        composeRule.onNodeWithText("La anulación dejará existencias negativas")
            .assertIsDisplayed()

        preview.impacts.forEach { impact ->
            val impactTag = PurchaseVoidTestTags.impact(
                impact.productId.value,
                impact.locationId.value,
            )
            composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
                .performScrollToNode(hasTestTag(impactTag))
            composeRule.onNodeWithTag(impactTag).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasText("Existencia resultante: -2 NIU"))
        composeRule.onNodeWithText("Existencia resultante: -2 NIU")
            .assertIsDisplayed()

        composeRule.onNodeWithTag(PurchaseVoidTestTags.SUBMIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasTestTag(PurchaseVoidTestTags.REASON))
        composeRule.onNodeWithTag(PurchaseVoidTestTags.REASON)
            .performTextInput("Documento registrado por error")
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasTestTag(PurchaseVoidTestTags.CONFIRMATION))
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONFIRMATION)
            .performClick()
        composeRule.onNodeWithTag(PurchaseVoidTestTags.SUBMIT)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(PurchaseVoidContract.Action.Submit, actions.last())
            assertEquals(
                "Documento registrado por error",
                actions.filterIsInstance<PurchaseVoidContract.Action.ReasonChanged>()
                    .last()
                    .reason,
            )
            assertEquals(
                true,
                actions.filterIsInstance<PurchaseVoidContract.Action.ConfirmationChanged>()
                    .last()
                    .confirmed,
            )
        }
    }

    @Test
    fun changedImpactIsVisibleAndRequiresASecondExplicitConfirmation() {
        val preview = preview()
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseVoidScreen(
                    state = PurchaseVoidContract.State(
                        purchaseId = preview.purchaseId,
                        preview = preview,
                        reason = "Documento registrado por error",
                        confirmed = false,
                        failure = PurchaseVoidContract.Failure.IMPACT_CHANGED,
                    ),
                    onAction = {},
                )
            }
        }

        val changedMessage =
            "Las existencias cambiaron. Actualizamos el impacto; revísalo y confirma nuevamente."
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasText(changedMessage))
        composeRule.onNodeWithText(changedMessage).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseVoidTestTags.SUBMIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasTestTag(PurchaseVoidTestTags.NEGATIVE_WARNING))
        composeRule.onNodeWithTag(PurchaseVoidTestTags.NEGATIVE_WARNING)
            .assertIsDisplayed()
    }

    @Test
    fun storageFullKeepsVoidInputsVisibleAndSubmitReadyForRetry() {
        val preview = preview()
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseVoidScreen(
                    state = PurchaseVoidContract.State(
                        purchaseId = preview.purchaseId,
                        preview = preview,
                        reason = "Documento registrado por error",
                        confirmed = true,
                        failure = PurchaseVoidContract.Failure.STORAGE_FULL,
                    ),
                    onAction = {},
                )
            }
        }

        val message = "No hay espacio suficiente. Libera espacio y reintenta; " +
            "tus datos guardados y el cambio en pantalla se conservan."
        composeRule.onNodeWithTag(PurchaseVoidTestTags.CONTENT)
            .performScrollToNode(hasText(message))
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseVoidTestTags.SUBMIT).assertIsEnabled()
    }

    private fun preview(): PurchaseVoidPreview = PurchaseVoidPreview(
        purchaseId = PurchaseId.from(uuid(1)),
        documentNumber = "F001-42",
        actor = PurchaseOverrideActor("owner-local", PurchaseOverrideRole.OWNER),
        impacts = listOf(
            PurchaseVoidImpact(
                productId = ProductId.from(uuid(2)),
                productName = "Café molido",
                locationId = LocationId.from(uuid(3)),
                locationName = "Almacén principal",
                unitCode = "NIU",
                currentQuantity = BigDecimal("8"),
                reversalQuantity = BigDecimal("-10"),
                resultingQuantity = BigDecimal("-2"),
                currency = CurrencyCode.of("PEN"),
            ),
            PurchaseVoidImpact(
                productId = ProductId.from(uuid(4)),
                productName = "Azúcar rubia",
                locationId = LocationId.from(uuid(5)),
                locationName = "Tienda Miraflores",
                unitCode = "KGM",
                currentQuantity = BigDecimal("20"),
                reversalQuantity = BigDecimal("-5"),
                resultingQuantity = BigDecimal("15"),
                currency = CurrencyCode.of("PEN"),
            ),
        ),
        expectedImpactHash = "a".repeat(64),
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
