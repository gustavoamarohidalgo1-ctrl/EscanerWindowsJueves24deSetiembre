package com.facturastock.app.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.feature.home.HomeTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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

        assertRoute(navController, AppRoutes.HOME)
        click(R.string.action_scan_invoice)
        assertRouteWithArguments(
            navController,
            AppRoutes.CAMERA,
            AppRoutes.DRAFT_ID to draftUuid.toString(),
        )
        click(R.string.action_take_photo)
        assertRouteWithArguments(
            navController,
            AppRoutes.PROCESSING,
            AppRoutes.DRAFT_ID to draftUuid.toString(),
        )
        click(R.string.action_finish_processing)
        assertRoute(navController, AppRoutes.PRODUCTS)
        composeRule.runOnIdle {
            assertEquals(
                AppRoutes.HOME,
                navController.previousBackStackEntry?.destination?.route,
            )
        }
    }

    @Test
    fun backRequiresExplicitDiscardAndInvokesItOnce() {
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

        click(R.string.action_scan_invoice)
        assertRoute(navController, AppRoutes.CAMERA)

        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.action_close_purchase_flow),
            )
            .performClick()
        waitUntilDisplayed(R.string.discard_dialog_title)
        assertRoute(navController, AppRoutes.CAMERA)
        composeRule.runOnIdle { assertTrue(discarded.isEmpty()) }

        composeRule
            .onNodeWithText(context.getString(R.string.action_keep_editing))
            .performClick()
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.CAMERA)
        composeRule.runOnIdle { assertTrue(discarded.isEmpty()) }

        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.action_close_purchase_flow),
            )
            .performClick()
        waitUntilDisplayed(R.string.action_discard_draft)
        composeRule
            .onNodeWithText(context.getString(R.string.action_discard_draft))
            .performClick()
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.INVOICES)
        composeRule.runOnIdle {
            assertEquals(listOf(DraftId.from(draftUuid)), discarded)
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
            navController.navigate(AppRoutes.HOME)
        }
        assertRoute(navController, AppRoutes.HOME)

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
            TopLevelDestination.SALES,
            TopLevelDestination.INVOICES,
            TopLevelDestination.INVENTORY,
            TopLevelDestination.REPORTS,
            TopLevelDestination.HOME,
        ).forEach { destination ->
            composeRule
                .onNode(
                    hasText(context.getString(destination.labelRes)) and
                        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
                )
                .performClick()
            composeRule.waitForIdle()
            assertRoute(navController, destination.route)
        }
    }

    @Test
    fun salesEntryIsReachableFromHomeAndRemainsAVisibleTopLevelArea() {
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

        composeRule.onNodeWithTag(HomeTestTags.SALES_CTA).performClick()
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.SALES)
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.navigation_home)).performClick()
        composeRule.waitForIdle()
        assertRoute(navController, AppRoutes.HOME)
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
            navController.navigate(AppRoutes.SETTINGS)
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
    fun savedStateRestoresDestinationAndSameDraftId() {
        val draftUuid = uuid("90000000-0000-4000-8000-000000000009")
        val uuidGenerator = SequenceUuidGenerator(draftUuid)
        val restorationTester = StateRestorationTester(composeRule)
        lateinit var navController: NavHostController

        restorationTester.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            FacturaStockTheme {
                FacturaStockApp(
                    navController = controller,
                    useInjectedViewModels = false,
                    uuidGenerator = uuidGenerator,
                )
            }
        }

        click(R.string.action_scan_invoice)
        assertRouteWithArguments(
            navController,
            AppRoutes.CAMERA,
            AppRoutes.DRAFT_ID to draftUuid.toString(),
        )

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertRouteWithArguments(
            navController,
            AppRoutes.CAMERA,
            AppRoutes.DRAFT_ID to draftUuid.toString(),
        )
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
            assertEquals(AppRoutes.HOME, navController.previousBackStackEntry?.destination?.route)
            navController.openLineReviewFromPrepared(draftId)
        }
        composeRule.waitForIdle()

        assertRoute(navController, AppRoutes.INVOICE_LINES)
        composeRule.runOnIdle {
            assertEquals(AppRoutes.HOME, navController.previousBackStackEntry?.destination?.route)
        }
    }

    private fun click(@StringRes labelRes: Int) {
        composeRule
            .onNodeWithText(context.getString(labelRes))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
    }

    private fun waitUntilDisplayed(@StringRes labelRes: Int) {
        val label = context.getString(labelRes)
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
            val applicationArgumentKeys = arguments
                ?.keySet()
                ?.filter { it in ROUTE_ARGUMENT_NAMES }
                ?.toSet()
            assertEquals(expectedArguments.map { it.first }.toSet(), applicationArgumentKeys)
            expectedArguments.forEach { (key, value) ->
                assertEquals(value, arguments?.getString(key))
            }
        }
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)

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
