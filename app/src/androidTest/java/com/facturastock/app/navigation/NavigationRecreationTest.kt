package com.facturastock.app.navigation

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.MainActivity
import com.facturastock.app.R
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.feature.catalogs.CatalogsTestTags
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.common.ScannerCodeInputTestTags
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.util.UUID
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica recreación, navegación superior y deep links con la configuración completa. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationRecreationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Inject
    lateinit var businessRepository: BusinessRepository

    @Inject
    lateinit var invoiceDraftRepository: InvoiceDraftRepository

    @Inject lateinit var locationRepository: InventoryLocationRepository
    @Inject lateinit var unitRepository: UnitRepository
    @Inject lateinit var productRepository: ProductRepository
    @Inject lateinit var inventoryRepository: ProductInventoryRepository

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val configuration = completedGateConfiguration()
        TestAppConfigurationState.current.value = configuration
        context.deleteDatabase(FacturaStockDatabase.NAME)
        hiltRule.inject()
        runBlocking {
            val businessId = requireNotNull(configuration.businessId)
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
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun specialRegistrationKeepsDraftAcrossRecreationAndSavesKilosWithoutCode() {
        val businessId = requireNotNull(TestAppConfigurationState.current.value.activeBusinessId)
        val locationId = LocationId.from(UUID.fromString("10000000-0000-4000-8000-000000000089"))
        runBlocking {
            locationRepository.create(InventoryLocation(locationId, businessId, "Almacén kilos",
                createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        }
        clickNavigation(R.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(InventoryTestTags.REGISTER_SPECIAL_PRODUCT).performClick()
        waitUntilTagDisplayed(CatalogsTestTags.PRODUCT_NAME)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertDoesNotExist()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.FIELD).assertDoesNotExist()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performTextReplacement("Comida prueba por kilo")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).performScrollTo().performTextReplacement("2,5")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE).performScrollTo().performTextReplacement("4,25")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SALE_PRICE).performScrollTo().performTextReplacement("10")

        scenario.recreate()
        waitUntilTagDisplayed(CatalogsTestTags.PRODUCT_NAME)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).assertTextContains("Comida prueba por kilo")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).performScrollTo().assertTextContains("2,5")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SALE_PRICE).performScrollTo().assertTextContains("10")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertDoesNotExist()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).performScrollTo().performClick()
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        runBlocking {
            val product = productRepository.search(businessId, "Comida prueba por kilo").single()
            assertNull(product.barcode)
            assertEquals("KGM", unitRepository.findById(product.unitId)?.code)
            assertEquals(locationId, product.locationId)
            val stock = inventoryRepository.summaryForProduct(businessId, product.productId).positions.single()
            assertEquals(0, BigDecimal("2.5").compareTo(stock.quantityOnHand))
            assertEquals(0, BigDecimal("4.25").compareTo(requireNotNull(stock.averageUnitCost).amount))
        }
        scenario.recreate()
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
    }

    @Test
    fun activityRecreationKeepsInventoryDestination() {
        clickNavigation(R.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)

        scenario.recreate()
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
    }

    @Test
    fun cashSaleOpensDirectlySurvivesRecreationAndSystemBackLeavesSales() {
        assertDirectCashSale()

        scenario.recreate()
        // Vender restaurado sigue en la venta al contado, sin volver a un selector.
        assertDirectCashSale()

        // Sin selector al que regresar, Atrás del sistema sale de Vender (raíz de la pila).
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(50L)
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun creditSaleOpensFromSalesTopBarAndBackReturnsToCashSale() {
        assertDirectCashSale()

        openCreditSaleFromTopBar()
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertDirectCashSale()

        // "Volver" dentro de la venta a crédito también regresa a Vender al contado.
        openCreditSaleFromTopBar()
        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).assertIsDisplayed().performClick()
        assertDirectCashSale()

        // Una venta a crédito restaurada conserva su tipo y Atrás sigue volviendo al contado.
        openCreditSaleFromTopBar()
        scenario.recreate()
        waitUntilTagDisplayed(SalesTestTags.DEBTOR_NAME)
        composeRule.onNodeWithTag(SalesTestTags.OPEN_CREDIT_SALE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertDirectCashSale()

        // El icono de venta a crédito pertenece sólo a Vender.
        clickNavigation(R.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        waitUntilTagGone(SalesTestTags.OPEN_CREDIT_SALE)
        clickNavigation(R.string.navigation_reports)
        waitUntilTagDisplayed(ReportsTestTags.SCREEN)
        waitUntilTagGone(SalesTestTags.OPEN_CREDIT_SALE)
        clickNavigation(R.string.navigation_sales)
        assertDirectCashSale()
    }

    @Test
    fun systemBackFromReportsReturnsToSales() {
        clickNavigation(R.string.navigation_reports)
        waitUntilTagDisplayed(ReportsTestTags.SCREEN)

        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntilTagDisplayed(SalesTestTags.SCREEN)
    }

    @Test
    fun remainingSectionsNavigateWithoutCreatingInvoiceDrafts() {
        val businessId = requireNotNull(TestAppConfigurationState.current.value.activeBusinessId)
        val before = runBlocking {
            invoiceDraftRepository.observeDrafts(businessId, status = null)
                .first().map { draft -> draft.draftId }.toSet()
        }

        clickNavigation(R.string.navigation_inventory)
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        clickNavigation(R.string.navigation_reports)
        waitUntilTagDisplayed(ReportsTestTags.SCREEN)
        clickNavigation(R.string.navigation_sales)
        waitUntilTagDisplayed(SalesTestTags.SCREEN)

        listOf(R.string.navigation_home, R.string.navigation_invoices).forEach { labelRes ->
            val matcher = hasText(context.getString(labelRes)) and
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
        val deepLink = InternalDeepLinks.purchaseDetail(purchaseId)

        scenario.onActivity { activity ->
            activity.onNewIntent(
                Intent(activity, MainActivity::class.java).apply {
                    data = Uri.parse(deepLink)
                },
            )
        }

        waitUntilDisplayed(R.string.purchase_not_found_title)

        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntilTagDisplayed(SalesTestTags.SCREEN)

        // El URI no cambia: el identificador de evento debe forzar una segunda entrega.
        scenario.onActivity { activity ->
            activity.onNewIntent(
                Intent(activity, MainActivity::class.java).apply {
                    data = Uri.parse(deepLink)
                },
            )
        }
        waitUntilDisplayed(R.string.purchase_not_found_title)
    }

    private fun clickNavigation(@StringRes labelRes: Int) {
        val matcher = hasText(context.getString(labelRes)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            runCatching { composeRule.onNode(matcher).assertIsDisplayed() }.isSuccess
        }
        composeRule.onNode(matcher).performClick()
        composeRule.waitForIdle()
    }

    /** Vender abre directamente la venta al contado con el lector unificado. */
    private fun assertDirectCashSale() {
        waitUntilTagDisplayed(SalesTestTags.OPEN_CREDIT_SALE)
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
            .onNodeWithContentDescription(context.getString(R.string.sales_open_credit_sale))
            .assertIsDisplayed()
    }

    private fun openCreditSaleFromTopBar() {
        composeRule.onNodeWithTag(SalesTestTags.OPEN_CREDIT_SALE).assertIsEnabled().performClick()
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
        @StringRes labelRes: Int,
        timeoutMillis: Long = 5_000L,
    ) {
        val label = context.getString(labelRes)
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
    }
}
