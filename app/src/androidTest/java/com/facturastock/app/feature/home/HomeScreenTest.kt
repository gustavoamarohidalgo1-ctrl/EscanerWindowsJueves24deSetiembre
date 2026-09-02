package com.facturastock.app.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.HomeDashboardRead
import com.facturastock.app.domain.model.HomeDashboardSnapshot
import com.facturastock.app.domain.model.HomeDraftOverview
import com.facturastock.app.domain.model.HomeInventoryOverview
import com.facturastock.app.domain.model.HomePurchaseOverview
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun scanCtaIsVisibleAndFiresItsCallback() {
        var clicked = false
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(onScanInvoice = { clicked = true })
            }
        }

        scrollHomeTo(hasTestTag(HomeTestTags.SCAN_CTA))
        composeRule
            .onNodeWithTag(HomeTestTags.SCAN_CTA)
            .assertIsDisplayed()
            .performClick()
        assertTrue(clicked)
    }

    @Test
    fun salesCtaIsVisibleAndFiresItsCallback() {
        var clicked = false
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(onOpenSales = { clicked = true })
            }
        }

        scrollHomeTo(hasTestTag(HomeTestTags.SALES_CTA))
        composeRule
            .onNodeWithTag(HomeTestTags.SALES_CTA)
            .assertIsDisplayed()
            .performClick()
        assertTrue(clicked)
    }

    @Test
    fun homeKeepsOnlyUsefulOperationalContent() {
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen()
            }
        }

        listOf(HomeTestTags.SALES_CTA, HomeTestTags.SCAN_CTA).forEach { tag ->
            scrollHomeTo(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun primaryActionsShareARowWhenTabletWidthIsAvailable() {
        composeRule.setContent {
            FacturaStockTheme {
                Box(
                    modifier = Modifier
                        .width(900.dp)
                        .height(600.dp),
                ) {
                    HomeScreen()
                }
            }
        }

        val purchaseBounds = composeRule
            .onNodeWithTag(HomeTestTags.SCAN_CTA)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val saleBounds = composeRule
            .onNodeWithTag(HomeTestTags.SALES_CTA)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(abs(purchaseBounds.top - saleBounds.top) < 1f)
        assertTrue(saleBounds.right <= purchaseBounds.left)
    }

    @Test
    fun shortcutButtonsFireTheirCallbacks() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(
                    onOpenProducts = { opened += "products" },
                    onOpenPurchases = { opened += "purchases" },
                    onOpenInventory = { opened += "inventory" },
                )
            }
        }

        listOf(
            HomeTestTags.SHORTCUT_PRODUCTS,
            HomeTestTags.SHORTCUT_PURCHASES,
        ).forEach { tag ->
            scrollHomeTo(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).performClick()
        }
        assertEquals(listOf("products", "purchases"), opened)
    }

    @Test
    fun realDashboardShowsCompactInventoryState() {
        val draft = draftItem(DRAFT_ID)
        var inventoryOpened = false
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(
                    dashboard = dashboardSnapshot(draft),
                    drafts = listOf(draft),
                    onOpenInventory = { inventoryOpened = true },
                )
            }
        }

        composeRule.onNodeWithText("Bodega Mayda").assertIsDisplayed()
        composeRule.onNodeWithText("10 / 12").assertIsDisplayed()
        val stockSummary = hasText(
            context.getString(R.string.home_summary_stock),
        ) and hasAnyAncestor(
            hasTestTag(HomeTestTags.PULSE_GRID),
        )
        scrollHomeTo(stockSummary)
        composeRule.onNode(stockSummary).assertIsDisplayed().performClick()

        assertTrue(inventoryOpened)
    }

    @Test
    fun emptyBusinessShowsStateAndTwoActionsWithoutTutorialCards() {
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(
                    dashboard = emptyDashboardSnapshot(),
                    drafts = emptyList(),
                )
            }
        }

        scrollHomeTo(hasTestTag(HomeTestTags.PULSE_GRID))
        composeRule.onNodeWithTag(HomeTestTags.PULSE_GRID).assertIsDisplayed()
        scrollHomeTo(hasTestTag(HomeTestTags.GETTING_STARTED))
        composeRule.onNodeWithTag(HomeTestTags.GETTING_STARTED).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.ATTENTION_PANEL).assertDoesNotExist()
    }

    @Test
    fun draftCardShowsSupplierDocumentTotalAndStatus() {
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(drafts = listOf(draftItem(DRAFT_ID)))
            }
        }

        scrollHomeTo(hasTestTag(HomeTestTags.draftCard(DRAFT_ID)))
        composeRule.onNodeWithTag(HomeTestTags.draftCard(DRAFT_ID)).assertIsDisplayed()
        composeRule.onNodeWithText("Distribuidora Andina S.A.C.").assertIsDisplayed()
        composeRule.onNodeWithText("F001-001234").assertIsDisplayed()
        composeRule.onNodeWithText("248.50", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.home_status_needs_review))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(
                    R.string.home_draft_continue,
                    context.getString(R.string.home_next_review_products),
                ),
            )
            .assertIsDisplayed()
    }

    @Test
    fun tappingADraftCardFiresTheCallbackWithItsDraftId() {
        var selected: DraftId? = null
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(
                    drafts = listOf(draftItem(DRAFT_ID)),
                    onDraftSelected = { selected = it },
                )
            }
        }

        scrollHomeTo(hasTestTag(HomeTestTags.draftCard(DRAFT_ID)))
        composeRule.onNodeWithTag(HomeTestTags.draftCard(DRAFT_ID)).performClick()
        assertEquals(DRAFT_ID, selected)
    }

    @Test
    fun deleteAffordanceOpensTheDialogAndCancelOrConfirmResolveIt() {
        var confirmed = false
        composeRule.setContent {
            FacturaStockTheme {
                var pendingDeletion by remember { mutableStateOf<DraftId?>(null) }
                HomeScreen(
                    drafts = listOf(draftItem(DRAFT_ID)),
                    draftIdPendingDeletion = pendingDeletion,
                    onDeleteRequested = { pendingDeletion = it },
                    onDeleteConfirmed = {
                        confirmed = true
                        pendingDeletion = null
                    },
                    onDeleteDismissed = { pendingDeletion = null },
                )
            }
        }

        scrollHomeTo(hasTestTag(HomeTestTags.draftDelete(DRAFT_ID)))
        composeRule.onNodeWithTag(HomeTestTags.draftDelete(DRAFT_ID)).performClick()
        composeRule.onNodeWithTag(HomeTestTags.DELETE_DIALOG).assertIsDisplayed()

        composeRule.onNodeWithTag(HomeTestTags.DELETE_DIALOG_CANCEL).performClick()
        composeRule.onNodeWithTag(HomeTestTags.DELETE_DIALOG).assertDoesNotExist()
        assertFalse(confirmed)

        scrollHomeTo(hasTestTag(HomeTestTags.draftDelete(DRAFT_ID)))
        composeRule.onNodeWithTag(HomeTestTags.draftDelete(DRAFT_ID)).performClick()
        composeRule.onNodeWithTag(HomeTestTags.DELETE_DIALOG_CONFIRM).performClick()
        assertTrue(confirmed)
        composeRule.onNodeWithTag(HomeTestTags.DELETE_DIALOG).assertDoesNotExist()
    }

    @Test
    fun interruptedOcrDraftOpensTheChoiceDialogWithResumeAndRetry() {
        var resumed = false
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(
                    drafts = listOf(
                        draftItem(DRAFT_ID, status = DraftStatus.OCR_PROCESSING),
                    ),
                    draftIdPendingOcrChoice = DRAFT_ID,
                    onOcrResumeSelected = { resumed = true },
                )
            }
        }

        composeRule.onNodeWithTag(HomeTestTags.OCR_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.OCR_DIALOG_RETRY).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.OCR_DIALOG_RESUME).performClick()
        assertTrue(resumed)
    }

    @Test
    fun ocrChoiceReflowsAtTwoHundredPercentAndAnnouncesRecovery() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    HomeScreen(
                        draftIdPendingOcrChoice = DRAFT_ID,
                        isRecoveringOcr = true,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.ocr_recovering))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.OCR_DIALOG_CANCEL)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun snackbarShowsAfterADeleteResultEffect() {
        val snackbarHostState = SnackbarHostState()
        val message = context.getString(R.string.home_draft_deleted)
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen(snackbarHostState = snackbarHostState)
            }
        }

        composeRule.runOnIdle {
            CoroutineScope(Dispatchers.Main).launch {
                snackbarHostState.showSnackbar(message)
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag(HomeTestTags.SNACKBAR).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    private fun draftItem(
        draftId: DraftId,
        status: DraftStatus = DraftStatus.NEEDS_REVIEW,
        supplierName: String? = "Distribuidora Andina S.A.C.",
    ) = HomeContract.HomeDraftItem(
        draft = InvoiceDraft(
            draftId = draftId,
            businessId = BUSINESS_ID,
            status = status,
            documentNumberNormalized = "F001-001234",
            issueDate = LocalDate.of(2026, 8, 1),
            currency = CurrencyCode.of("PEN"),
            total = Money.ofMinor(24_850, CurrencyCode.of("PEN")),
            createdAt = Instant.parse("2026-08-01T10:15:00Z"),
            updatedAt = Instant.parse("2026-08-03T18:40:00Z"),
        ),
        supplierName = supplierName,
    )

    private fun scrollHomeTo(matcher: SemanticsMatcher) {
        composeRule.onNodeWithTag(HomeTestTags.DRAFTS_LIST).performScrollToNode(matcher)
    }

    private fun dashboardSnapshot(
        draft: HomeContract.HomeDraftItem,
    ): HomeDashboardSnapshot {
        return HomeDashboardSnapshot(
            business = Business(
                businessId = BUSINESS_ID,
                legalName = "Bodega Mayda E.I.R.L.",
                tradeName = "Bodega Mayda",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
            isDemoMode = false,
            zoneId = ZoneId.of("America/Lima"),
            overview = HomeDashboardRead(
                drafts = HomeDraftOverview(
                    openCount = 1,
                    recent = listOf(RecentDraft(draft.draft, draft.supplierName)),
                ),
                inventory = HomeInventoryOverview(
                    productCount = 12,
                    availableProductCount = 10,
                    attentionProductCount = 3,
                    withoutStockCount = 2,
                    withoutSalePriceCount = 2,
                    negativeStockCount = 1,
                ),
                purchases = HomePurchaseOverview(
                    postedCount = 8,
                    syncProblemCount = 1,
                ),
            ),
            observedAt = Instant.parse("2026-08-28T15:30:00Z"),
        )
    }

    private fun emptyDashboardSnapshot(): HomeDashboardSnapshot {
        return HomeDashboardSnapshot(
            business = Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio Demo",
                tradeName = "Negocio Demo",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
            isDemoMode = false,
            zoneId = ZoneId.of("America/Lima"),
            overview = HomeDashboardRead(
                drafts = HomeDraftOverview(openCount = 0, recent = emptyList()),
                inventory = HomeInventoryOverview(
                    productCount = 0,
                    availableProductCount = 0,
                    attentionProductCount = 0,
                    withoutStockCount = 0,
                    withoutSalePriceCount = 0,
                    negativeStockCount = 0,
                ),
                purchases = HomePurchaseOverview(
                    postedCount = 0,
                    syncProblemCount = 0,
                ),
            ),
            observedAt = Instant.parse("2026-08-28T15:30:00Z"),
        )
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000d1"),
        )
    }
}
