package com.facturastock.app.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.facturastock.app.core.platform.LocalAppDirectories
import com.facturastock.app.di.AppViewModelFactory
import com.facturastock.app.di.LocalAppViewModelFactory
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.catalogs.CatalogsTestTags
import com.facturastock.app.feature.common.ScannerCodeInputTestTags
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.navigation_home
import com.facturastock.app.resources.navigation_inventory
import com.facturastock.app.resources.navigation_invoices
import com.facturastock.app.resources.navigation_reports
import com.facturastock.app.resources.navigation_sales
import com.facturastock.app.resources.purchase_not_found_title
import com.facturastock.app.resources.sales_open_credit_sale
import com.facturastock.app.testing.SceneSystemBack
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.ui.navigation.LocalBackPressedDispatcher
import com.facturastock.app.ui.theme.FacturaStockTheme
import com.facturastock.app.testing.performClickOnUiThread
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Navegación superior, Atrás (Esc) y deep links internos con la configuración completa sobre el
 * grafo real. La recreación de Activity de Android no existe en escritorio: donde el test
 * original la usaba para comprobar persistencia se cierra y reabre la ventana
 * ([DesktopAppHarness.restartApp]).
 */
class NavigationRecreationTest {
    @get:Rule(order = 0)
    val harness = DesktopAppHarness()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val keyboard get() = harness.keyboard(composeRule)

    @Before
    fun setUp() {
        runBlocking {
            val businessRepository = harness.component.businesses()
            val businessId = requireNotNull(TestAppConfigurationState.current.value.businessId)
            if (businessRepository.findById(businessId) == null) {
                businessRepository.create(
                    Business(
                        businessId = businessId,
                        legalName = "Negocio Navegación OCR SAC",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                )
            }
        }
    }

    @Test
    fun specialRegistrationSavesKilosWithoutCodeAndSurvivesWindowRestart() {
        harness.setAppContent(composeRule)
        val businessId = requireNotNull(TestAppConfigurationState.current.value.activeBusinessId)
        val locationId = LocationId.from(UUID.fromString("10000000-0000-4000-8000-000000000089"))
        runBlocking {
            harness.component.locations().create(
                InventoryLocation(
                    locationId, businessId, "Almacén kilos",
                    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                ),
            )
        }
        clickNavigation(Res.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(InventoryTestTags.REGISTER_SPECIAL_PRODUCT).performClickOnUiThread(composeRule)
        waitUntilTagDisplayed(CatalogsTestTags.PRODUCT_NAME)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertDoesNotExist()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.FIELD).assertDoesNotExist()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performTextReplacement("Comida prueba por kilo")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).performScrollTo().performTextReplacement("2,5")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE).performScrollTo().performTextReplacement("4,25")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SALE_PRICE).performScrollTo().performTextReplacement("10")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertDoesNotExist()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).performScrollTo().performClickOnUiThread(composeRule)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        assertSavedKilosProduct(businessId, locationId)

        // Cerrar y reabrir la ventana: el producto sigue guardado y no reaparece el formulario.
        harness.restartApp(composeRule)
        waitUntilTagDisplayed(SalesTestTags.SCREEN, timeoutMillis = 15_000L)
        clickNavigation(Res.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        assertSavedKilosProduct(businessId, locationId)
    }

    @Test
    fun cashSaleOpensDirectlyAndEscapeAtTheRootKeepsTheWindowOnSales() {
        harness.setAppContent(composeRule)
        assertDirectCashSale()

        // Android cerraba la Activity con Atrás en la raíz; en Windows la ventana sigue en Vender.
        keyboard.escape()
        composeRule.waitForIdle()
        assertDirectCashSale()
    }

    @Test
    fun creditSaleOpensFromSalesTopBarAndBackReturnsToCashSale() {
        harness.setAppContent(composeRule)
        assertDirectCashSale()

        openCreditSaleFromTopBar()
        keyboard.escape()
        assertDirectCashSale()

        // "Volver" dentro de la venta a crédito también regresa a Vender al contado.
        openCreditSaleFromTopBar()
        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).assertIsDisplayed().performClickOnUiThread(composeRule)
        assertDirectCashSale()

        // Una segunda venta a crédito conserva su tipo y Atrás sigue volviendo al contado.
        openCreditSaleFromTopBar()
        composeRule.onNodeWithTag(SalesTestTags.OPEN_CREDIT_SALE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
        keyboard.escape()
        assertDirectCashSale()

        // El icono de venta a crédito pertenece sólo a Vender.
        clickNavigation(Res.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        waitUntilTagGone(SalesTestTags.OPEN_CREDIT_SALE)
        clickNavigation(Res.string.navigation_reports)
        waitUntilTagDisplayed(ReportsTestTags.SCREEN)
        waitUntilTagGone(SalesTestTags.OPEN_CREDIT_SALE)
        clickNavigation(Res.string.navigation_sales)
        assertDirectCashSale()
    }

    @Test
    fun escapeFromReportsReturnsToSales() {
        harness.setAppContent(composeRule)
        clickNavigation(Res.string.navigation_reports)
        waitUntilTagDisplayed(ReportsTestTags.SCREEN)

        keyboard.escape()
        waitUntilTagDisplayed(SalesTestTags.SCREEN)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun remainingSectionsNavigateWithoutCreatingInvoiceDrafts() {
        harness.setAppContent(composeRule)
        val businessId = requireNotNull(TestAppConfigurationState.current.value.activeBusinessId)
        val invoiceDraftRepository = harness.component.invoiceDrafts()
        val before = runBlocking {
            invoiceDraftRepository.observeDrafts(businessId, status = null)
                .first().map { draft -> draft.draftId }.toSet()
        }

        clickNavigation(Res.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        clickNavigation(Res.string.navigation_reports)
        waitUntilTagDisplayed(ReportsTestTags.SCREEN)
        clickNavigation(Res.string.navigation_sales)
        waitUntilTagDisplayed(SalesTestTags.SCREEN)

        listOf(Res.string.navigation_home, Res.string.navigation_invoices).forEach { labelRes ->
            val matcher = hasText(str(labelRes)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            composeRule.onNode(matcher).assertDoesNotExist()
        }
        val after = runBlocking {
            invoiceDraftRepository.observeDrafts(businessId, status = null)
                .first().map { draft -> draft.draftId }.toSet()
        }
        assertEquals(before, after)
    }

    @Test
    fun repeatedIdenticalInternalDeepLinkIsHandledWithoutExposingAnUnknownPurchase() {
        val purchaseId = PurchaseId.parse(
            "80000000-0000-4000-8000-000000000008",
        ) ?: error("UUID de prueba inválido")
        val deepLink = mutableStateOf<String?>(null)
        val requestId = mutableLongStateOf(0L)
        setAppWithInternalDeepLink(deepLink = { deepLink.value }, requestId = { requestId.longValue })
        waitUntilTagDisplayed(SalesTestTags.SCREEN, timeoutMillis = 15_000L)

        // Equivalente a entregar el enlace a la ventana ya abierta (onNewIntent en Android).
        composeRule.runOnIdle {
            deepLink.value = InternalDeepLinks.purchaseDetail(purchaseId)
            requestId.longValue = 1L
        }
        waitUntilDisplayed(Res.string.purchase_not_found_title)

        deepLinkSceneBack.pressBack(composeRule)
        waitUntilTagDisplayed(SalesTestTags.SCREEN)

        // El URI no cambia: el identificador de evento debe forzar una segunda entrega.
        composeRule.runOnIdle { requestId.longValue = 2L }
        waitUntilDisplayed(Res.string.purchase_not_found_title)
    }

    /**
     * La ventana de producción no recibe URIs externos; los deep links internos entran por los
     * parámetros de [FacturaStockApp]. Se monta con el mismo grafo y CompositionLocals que
     * [DesktopAppHarness.setAppContent], sin la compuerta de acceso de `AppRoot`.
     */
    private val deepLinkSceneBack = SceneSystemBack()

    private fun setAppWithInternalDeepLink(deepLink: () -> String?, requestId: () -> Long) {
        val factory = AppViewModelFactory(harness.component.viewModelComponentFactory())
        val store = ViewModelStore()
        composeRule.setContent {
            deepLinkSceneBack.capture()
            val storeOwner = remember {
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = store
                }
            }
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides storeOwner,
                LocalAppViewModelFactory provides factory,
                LocalBackPressedDispatcher provides harness.backPressedDispatcher,
                LocalAppDirectories provides harness.directories,
            ) {
                FacturaStockTheme {
                    FacturaStockApp(
                        initialInternalDeepLink = deepLink(),
                        initialInternalDeepLinkRequestId = requestId(),
                    )
                }
            }
        }
    }

    private fun assertSavedKilosProduct(
        businessId: com.facturastock.app.domain.model.id.BusinessId,
        locationId: LocationId,
    ) = runBlocking {
        val product = harness.component.products().search(businessId, "Comida prueba por kilo").single()
        assertNull(product.barcode)
        assertEquals("KGM", harness.component.units().findById(product.unitId)?.code)
        assertEquals(locationId, product.locationId)
        val stock = harness.component.productInventory()
            .summaryForProduct(businessId, product.productId).positions.single()
        assertEquals(0, BigDecimal("2.5").compareTo(stock.quantityOnHand))
        assertEquals(0, BigDecimal("4.25").compareTo(requireNotNull(stock.averageUnitCost).amount))
    }

    private fun clickNavigation(labelRes: StringResource) {
        val matcher = hasText(str(labelRes)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            runCatching { composeRule.onNode(matcher).assertIsDisplayed() }.isSuccess
        }
        composeRule.onNode(matcher).performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
    }

    /** Vender abre directamente la venta al contado con el lector unificado. */
    private fun assertDirectCashSale() {
        waitUntilTagDisplayed(SalesTestTags.OPEN_CREDIT_SALE, timeoutMillis = 15_000L)
        waitUntilTagDisplayed(ScannerCodeInputTestTags.FIELD)
        waitUntilTagGone(SalesTestTags.DEBTOR_NAME)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(ScannerCodeInputTestTags.FIELD).assertCountEquals(1)
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.CASH_ENTRY).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.CREDIT_ENTRY).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(str(Res.string.sales_open_credit_sale))
            .assertIsDisplayed()
    }

    private fun openCreditSaleFromTopBar() {
        composeRule.onNodeWithTag(SalesTestTags.OPEN_CREDIT_SALE).assertIsEnabled().performClickOnUiThread(composeRule)
        waitUntilTagDisplayed(SalesTestTags.DEBTOR_NAME)
        waitUntilTagGone(SalesTestTags.OPEN_CREDIT_SALE)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(ScannerCodeInputTestTags.FIELD).assertCountEquals(1)
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
    }

    private fun waitUntilTagGone(tag: String, timeoutMillis: Long = 10_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching { composeRule.onNodeWithTag(tag).assertDoesNotExist() }.isSuccess
        }
    }

    private fun waitUntilTagDisplayed(tag: String, timeoutMillis: Long = 10_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitUntilDisplayed(
        labelRes: StringResource,
        timeoutMillis: Long = 10_000L,
    ) {
        val label = str(labelRes)
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun str(resource: StringResource): String = runBlocking { getString(resource) }
}
