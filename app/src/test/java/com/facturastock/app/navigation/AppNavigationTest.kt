package com.facturastock.app.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.action_back
import com.facturastock.app.resources.action_close_purchase_flow
import com.facturastock.app.resources.action_discard_draft
import com.facturastock.app.resources.action_finish_processing
import com.facturastock.app.resources.action_keep_editing
import com.facturastock.app.resources.action_take_photo
import com.facturastock.app.resources.discard_dialog_title
import com.facturastock.app.resources.navigation_home
import com.facturastock.app.resources.navigation_inventory
import com.facturastock.app.resources.navigation_invoices
import com.facturastock.app.resources.navigation_reports
import com.facturastock.app.resources.navigation_sales
import com.facturastock.app.ui.navigation.BackPressedDispatcher
import com.facturastock.app.ui.navigation.LocalBackPressedDispatcher
import com.facturastock.app.ui.theme.FacturaStockTheme
import com.facturastock.app.testing.SceneSystemBack
import com.facturastock.app.testing.performClickOnUiThread
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun salesEntryHasNoDebtorsShortcutAndReturnsFromReportsWithoutDiscardingDrafts() {
        val discarded = mutableListOf<DraftId>()
        lateinit var navController: NavHostController
        var salesEntryId: String? = null
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                    onDiscardDraft = discarded::add,
                )
            }
        }

        assertRoute(navController, AppRoutes.SALES)
        composeRule.runOnIdle { salesEntryId = navController.currentBackStackEntry?.id }
        composeRule.onNodeWithTag(SalesTestTags.OPEN_DEBTORS).assertDoesNotExist()
        clickNavigation(Res.string.navigation_reports)
        assertRoute(navController, AppRoutes.REPORTS)
        composeRule.runOnIdle {
            assertEquals(salesEntryId, navController.previousBackStackEntry?.id)
            assertTrue(discarded.isEmpty())
        }

        clickNavigation(Res.string.navigation_sales)
        assertRoute(navController, AppRoutes.SALES)
        composeRule.onNodeWithTag(SalesTestTags.OPEN_DEBTORS).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(salesEntryId, navController.currentBackStackEntry?.id)
            assertEquals(null, navController.previousBackStackEntry)
            assertTrue(discarded.isEmpty())
        }
    }

    @Test
    fun invoiceProductScanNeedsOnlyCameraAndShutterBeforeOpeningProducts() {
        val draftUuid = uuid("10000000-0000-4000-8000-000000000001")
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                    uuidGenerator = SequenceUuidGenerator(draftUuid),
                )
            }
        }

        assertRoute(navController, AppRoutes.SALES)
        composeRule.runOnIdle {
            navController.navigate(AppRoutes.camera(DraftId.from(draftUuid)))
        }
        composeRule.waitForIdle()
        assertRouteWithArguments(
            navController,
            AppRoutes.CAMERA,
            AppRoutes.DRAFT_ID to draftUuid.toString(),
        )
        click(Res.string.action_take_photo)
        assertRouteWithArguments(
            navController,
            AppRoutes.PROCESSING,
            AppRoutes.DRAFT_ID to draftUuid.toString(),
        )
        click(Res.string.action_finish_processing)
        assertRoute(navController, AppRoutes.PRODUCTS_PATTERN)
        composeRule.runOnIdle {
            assertEquals(
                AppRoutes.SALES,
                navController.previousBackStackEntry?.destination?.route,
            )
        }
    }

    @Test
    fun legacyDraftBackRequiresExplicitDiscardOnceAndReturnsToSales() {
        val draftUuid = uuid("50000000-0000-4000-8000-000000000005")
        val discarded = mutableListOf<DraftId>()
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                    uuidGenerator = SequenceUuidGenerator(draftUuid),
                    onDiscardDraft = discarded::add,
                )
            }
        }

        composeRule.runOnIdle {
            navController.navigate(AppRoutes.camera(DraftId.from(draftUuid)))
        }
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.CAMERA)

        composeRule
            .onNodeWithContentDescription(
                str(Res.string.action_close_purchase_flow),
            )
            .performClickOnUiThread(composeRule)
        waitUntilDisplayed(Res.string.discard_dialog_title)
        assertRoute(navController, AppRoutes.CAMERA)
        composeRule.runOnIdle { assertTrue(discarded.isEmpty()) }

        composeRule
            .onNodeWithText(str(Res.string.action_keep_editing))
            .performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.CAMERA)
        composeRule.runOnIdle { assertTrue(discarded.isEmpty()) }

        composeRule
            .onNodeWithContentDescription(
                str(Res.string.action_close_purchase_flow),
            )
            .performClickOnUiThread(composeRule)
        waitUntilDisplayed(Res.string.action_discard_draft)
        composeRule
            .onNodeWithText(str(Res.string.action_discard_draft))
            .performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.SALES)
        composeRule.runOnIdle {
            assertEquals(listOf(DraftId.from(draftUuid)), discarded)
        }

        clickNavigation(Res.string.navigation_sales)
        assertRoute(navController, AppRoutes.SALES)
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, navController.previousBackStackEntry)
        }
    }

    @Test
    fun validatedInternalDeepLinkOpensReadOnlyPurchaseDetail() {
        val purchaseUuid = uuid("70000000-0000-4000-8000-000000000007")
        val purchaseId = PurchaseId.from(purchaseUuid)
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                    initialInternalDeepLink = InternalDeepLinks.purchaseDetail(purchaseId),
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            navController.currentDestination?.route == AppRoutes.PURCHASE_DETAIL
        }
        assertRouteWithArguments(
            navController,
            AppRoutes.PURCHASE_DETAIL,
            AppRoutes.PURCHASE_ID to purchaseUuid.toString(),
        )
    }

    @Test
    fun identicalInternalDeepLinkWithANewRequestIdIsHandledAgain() {
        val purchaseUuid = uuid("71000000-0000-4000-8000-000000000007")
        val purchaseId = PurchaseId.from(purchaseUuid)
        val requestId = mutableLongStateOf(1L)
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                    initialInternalDeepLink = InternalDeepLinks.purchaseDetail(purchaseId),
                    initialInternalDeepLinkRequestId = requestId.longValue,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            navController.currentDestination?.route == AppRoutes.PURCHASE_DETAIL
        }
        composeRule.runOnIdle {
            navController.navigate(AppRoutes.SALES)
        }
        assertRoute(navController, AppRoutes.SALES)

        composeRule.runOnIdle { requestId.longValue = 2L }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            navController.currentDestination?.route == AppRoutes.PURCHASE_DETAIL
        }
        assertRouteWithArguments(
            navController,
            AppRoutes.PURCHASE_DETAIL,
            AppRoutes.PURCHASE_ID to purchaseUuid.toString(),
        )
    }

    @Test
    fun graphMatchesContractAndEveryTopLevelDestinationNavigates() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                )
            }
        }

        composeRule.runOnIdle {
            val graphRoutes = navController.graph.mapNotNull { it.route }
            assertEquals(AppRoutes.all.map { it.pattern }.toSet(), graphRoutes.toSet())
            assertEquals(graphRoutes.size, graphRoutes.distinct().size)
        }

        listOf(
            TopLevelDestination.INVENTORY,
            TopLevelDestination.SALES,
            TopLevelDestination.REPORTS,
            TopLevelDestination.SALES,
        ).forEach { destination ->
            composeRule
                .onNode(
                    hasText(str(destination.labelRes)) and
                        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
                )
                .performClickOnUiThread(composeRule)
            composeRule.waitForIdle()
            assertRoute(navController, destination.route)
        }
        clickNavigation(Res.string.navigation_inventory)
        composeRule.onNodeWithTag(InventoryTestTags.REGISTER_PRODUCTS).performScrollTo().performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.INVENTORY_REGISTER)
        composeRule.onNodeWithTag(InventoryTestTags.REGISTRATION_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(str(Res.string.action_back)).performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.INVENTORY)
    }

    @Test
    fun salesIsInitialDestinationAndRemovedSectionsRedirectWithoutVisibleTabs() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                )
            }
        }

        assertRoute(navController, AppRoutes.SALES)
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.CASH_ENTRY).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertDoesNotExist()

        listOf(AppRoutes.HOME, AppRoutes.INVOICES).forEach { legacyRoute ->
            composeRule.runOnIdle { navController.navigate(legacyRoute) }
            composeRule.waitForIdle()
            assertRoute(navController, AppRoutes.SALES)
            composeRule.onNodeWithTag(SalesTestTags.SCREEN).assertIsDisplayed()
            composeRule.onNodeWithText(str(Res.string.navigation_home)).assertDoesNotExist()
            composeRule.onNodeWithText(str(Res.string.navigation_invoices)).assertDoesNotExist()
        }
    }

    @Test
    fun demoScenarioCallbacksOpenTypedPreviewOrCompletedPurchase() {
        val draftId = DraftId.from(uuid("a1000000-0000-4000-8000-000000000001"))
        val captureId = CaptureId.from(uuid("a2000000-0000-4000-8000-000000000002"))
        val purchaseId = PurchaseId.from(uuid("a3000000-0000-4000-8000-000000000003"))
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                )
            }
        }

        composeRule.runOnIdle {
            navController.navigate(AppRoutes.imagePreview(draftId, captureId))
        }
        composeRule.waitForIdle()
        assertRouteWithArguments(
            navController,
            AppRoutes.IMAGE_PREVIEW,
            AppRoutes.DRAFT_ID to draftId.value,
            AppRoutes.CAPTURE_ID to captureId.value,
        )

        composeRule.runOnIdle {
            check(navController.popBackStack())
            navController.navigate(AppRoutes.purchaseDetail(purchaseId))
        }
        composeRule.waitForIdle()
        assertRouteWithArguments(
            navController,
            AppRoutes.PURCHASE_DETAIL,
            AppRoutes.PURCHASE_ID to purchaseId.value,
        )
    }

    @Test
    fun escapeOnAProtectedDraftAsksToDiscardInsteadOfLeaving() {
        val draftUuid = uuid("91000000-0000-4000-8000-000000000009")
        val discarded = mutableListOf<DraftId>()
        val dispatcher = BackPressedDispatcher()
        // Esc de la ventana = Atrás de la escena (ComposeSceneMediator -> NavigationEventDispatcher).
        val sceneBack = SceneSystemBack()
        lateinit var navController: NavHostController

        composeRule.setContent {
            sceneBack.capture()
            val controller = rememberNavController()
            SideEffect { navController = controller }
            CompositionLocalProvider(LocalBackPressedDispatcher provides dispatcher) {
                FacturaStockTheme {
                    FacturaStockApp(
                        navController = controller,
                        useInjectedViewModels = false,
                        onDiscardDraft = discarded::add,
                    )
                }
            }
        }

        composeRule.runOnIdle {
            navController.navigate(AppRoutes.camera(DraftId.from(draftUuid)))
        }
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.CAMERA)

        // Esc (Atrás del sistema) sobre un borrador protegido abre la confirmación, no retrocede.
        sceneBack.pressBack(composeRule)
        waitUntilDisplayed(Res.string.discard_dialog_title)
        assertRoute(navController, AppRoutes.CAMERA)
        composeRule.runOnIdle { assertTrue(discarded.isEmpty()) }

        composeRule.onNodeWithText(str(Res.string.action_keep_editing)).performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.CAMERA)

        sceneBack.pressBack(composeRule)
        waitUntilDisplayed(Res.string.action_discard_draft)
        composeRule.onNodeWithText(str(Res.string.action_discard_draft)).performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.SALES)
        composeRule.runOnIdle {
            assertEquals(listOf(DraftId.from(draftUuid)), discarded)
            assertEquals(null, navController.previousBackStackEntry)
        }
    }

    @Test
    fun preparedSummaryAndReopenReplaceEditableNavigationHistory() {
        val draftId = DraftId.from(uuid("a0000000-0000-4000-8000-000000000010"))
        lateinit var navController: NavHostController

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                )
            }
        }

        composeRule.runOnIdle {
            navController.navigate(AppRoutes.source(draftId))
            navController.navigate(AppRoutes.invoiceHeader(draftId))
            navController.navigate(AppRoutes.invoiceLines(draftId))
            navController.navigate(AppRoutes.purchaseSummary(draftId))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(AppRoutes.INVOICE_LINES, navController.previousBackStackEntry?.destination?.route)
            navController.ensurePreparedSummaryIsFlowRoot(draftId)
        }
        composeRule.waitForIdle()

        assertRoute(navController, AppRoutes.PURCHASE_SUMMARY)
        composeRule.runOnIdle {
            assertEquals(AppRoutes.SALES, navController.previousBackStackEntry?.destination?.route)
            navController.openLineReviewFromPrepared(draftId)
        }
        composeRule.waitForIdle()

        assertRoute(navController, AppRoutes.INVOICE_LINES)
        composeRule.runOnIdle {
            assertEquals(AppRoutes.SALES, navController.previousBackStackEntry?.destination?.route)
        }
    }

    private fun clickNavigation(labelRes: StringResource) {
        composeRule
            .onNode(
                hasText(str(labelRes)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
            .performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
    }

    private fun click(labelRes: StringResource) {
        composeRule
            .onNodeWithText(str(labelRes))
            .performScrollTo()
            .performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
    }

    private fun waitUntilDisplayed(labelRes: StringResource) {
        val label = str(labelRes)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun assertRoute(
        navController: NavHostController,
        expectedPattern: String,
    ) {
        composeRule.runOnIdle {
            assertEquals(expectedPattern, navController.currentDestination?.route)
        }
    }

    private fun assertRouteWithArguments(
        navController: NavHostController,
        expectedPattern: String,
        vararg expectedArguments: Pair<String, String>,
    ) {
        composeRule.runOnIdle {
            assertEquals(expectedPattern, navController.currentDestination?.route)
            val arguments = navController.currentBackStackEntry?.arguments
            val applicationArguments = arguments
                ?.read { toMap() }
                ?.filterKeys { it in ROUTE_ARGUMENT_NAMES }
            assertEquals(expectedArguments.map { it.first }.toSet(), applicationArguments?.keys)
            expectedArguments.forEach { (key, value) ->
                assertEquals(value, applicationArguments?.get(key))
            }
        }
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private fun str(resource: StringResource): String = runBlocking { getString(resource) }

    private companion object {
        val ROUTE_ARGUMENT_NAMES = setOf(
            AppRoutes.DRAFT_ID,
            AppRoutes.CAPTURE_ID,
            AppRoutes.LINE_ID,
            AppRoutes.PURCHASE_ID,
            AppRoutes.EXPECTED_PREPARED_HASH,
        )
    }
}

private class SequenceUuidGenerator(vararg values: UUID) : UuidGenerator {
    private val iterator = values.iterator()

    override fun newUuid(): UUID {
        check(iterator.hasNext())
        return iterator.next()
    }
}
