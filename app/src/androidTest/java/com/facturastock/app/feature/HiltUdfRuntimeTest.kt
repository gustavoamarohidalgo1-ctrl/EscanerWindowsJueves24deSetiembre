package com.facturastock.app.feature

import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.MainActivity
import com.facturastock.app.R
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.feature.catalogs.CatalogsTestTags
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica Hilt, acceso manual al catálogo y restauración sin la sección Facturas. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltUdfRuntimeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Inject
    lateinit var businessRepository: BusinessRepository

    @Inject
    lateinit var database: FacturaStockDatabase

    @Inject
    lateinit var unitRepository: UnitRepository

    @Inject
    lateinit var inventoryLocationRepository: InventoryLocationRepository

    @Inject
    lateinit var productRepository: ProductRepository

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val configuration = completedGateConfiguration()
        TestAppConfigurationState.current.value = configuration
        // El módulo productivo usa una base en disco. Borrarla antes de crear el componente
        // mantiene el E2E repetible incluso si una ejecución anterior publicó una compra.
        context.deleteDatabase(FacturaStockDatabase.NAME)
        hiltRule.inject()
        runBlocking {
            val businessId = requireNotNull(configuration.businessId)
            if (businessRepository.findById(businessId) == null) {
                businessRepository.create(
                    Business(
                        businessId = businessId,
                        legalName = "Negocio Hilt OCR SAC",
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
    fun hiltStartsAndManualCatalogSurvivesRecreationWithoutInvoiceTab() {
        scenario.onActivity { activity ->
            assertTrue(activity.application is HiltTestApplication)
        }

        waitUntilTagDisplayed(SalesTestTags.SCREEN)
        listOf(R.string.navigation_home, R.string.navigation_invoices).forEach { labelRes ->
            val matcher = hasText(context.getString(labelRes)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            composeRule.onNode(matcher).assertDoesNotExist()
        }
        clickNavigation(R.string.navigation_inventory)
        waitUntilDisplayedInList(
            listTag = InventoryTestTags.LIST_SCREEN,
            labelRes = R.string.inventory_empty_title,
        )
        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).performScrollToNode(
            hasText(context.getString(R.string.inventory_register_manual)),
        )
        clickTag(InventoryTestTags.REGISTER_MANUAL)
        waitUntilTagDisplayed(CatalogsTestTags.FORM)
        composeRule.onNodeWithText(context.getString(R.string.action_cancel))
            .performScrollTo()
            .performClick()
        waitUntilDisplayed(R.string.products_empty_title)

        runBlocking {
            seedCatalogProduct(
                requireNotNull(TestAppConfigurationState.current.value.businessId),
                TestAppConfigurationState.current.value.currency,
            )
        }

        waitUntilTextDisplayed(TEST_PRODUCT_NAME, timeoutMillis = 30_000L)
        waitUntilTagDisplayed(CatalogsTestTags.LIST)

        runBlocking {
            val businessId = requireNotNull(
                TestAppConfigurationState.current.value.businessId,
            ).value
            val products = database.productDao().listForBusiness(businessId)
            assertEquals(1, products.size)
            assertEquals(TEST_PRODUCT_ID.toString(), products.single().productId)
            assertEquals(TEST_PRODUCT_NAME, products.single().name)
            assertTrue(
                database.purchaseDao().listForBusiness(
                    businessId = businessId,
                    limit = 10,
                    offset = 0,
                ).isEmpty(),
            )
            assertTrue(database.inventoryDao().listDiagnosticMovements(businessId).isEmpty())
            assertTrue(database.inventoryDao().listDiagnosticBalances(businessId).isEmpty())
            assertEquals(0, database.invoiceDraftDao().countForBusiness(businessId))
        }

        scenario.recreate()
        waitUntilTextDisplayed(TEST_PRODUCT_NAME, timeoutMillis = 30_000L)
        waitUntilTagDisplayed(CatalogsTestTags.LIST)
        runBlocking {
            val businessId = requireNotNull(
                TestAppConfigurationState.current.value.businessId,
            ).value
            assertEquals(1, database.productDao().countForBusiness(businessId))
            assertTrue(
                database.purchaseDao().listForBusiness(
                    businessId = businessId,
                    limit = 10,
                    offset = 0,
                ).isEmpty(),
            )
            assertTrue(database.inventoryDao().listDiagnosticMovements(businessId).isEmpty())
            assertEquals(0, database.invoiceDraftDao().countForBusiness(businessId))
        }
    }

    @Test
    fun mainActivityProtectsSensitiveScreensFromScreenshots() {
        scenario.onActivity { activity ->
            assertTrue(
                activity.window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    private suspend fun seedCatalogProduct(
        businessId: BusinessId,
        currency: CurrencyCode,
    ) {
        val unitId = UnitId.from(TEST_UNIT_ID)
        val locationId = LocationId.from(TEST_LOCATION_ID)
        if (unitRepository.findById(unitId) == null) {
            unitRepository.create(
                UnitOfMeasure(
                    unitId = unitId,
                    businessId = businessId,
                    code = "NIU",
                    name = "Unidad",
                    symbol = "un",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        if (inventoryLocationRepository.findById(locationId) == null) {
            inventoryLocationRepository.create(
                InventoryLocation(
                    locationId = locationId,
                    businessId = businessId,
                    name = "Almacén E2E",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        val productId = ProductId.from(TEST_PRODUCT_ID)
        if (productRepository.findById(productId) == null) {
            productRepository.create(
                Product(
                    productId = productId,
                    businessId = businessId,
                    unitId = unitId,
                    name = TEST_PRODUCT_NAME,
                    locationId = locationId,
                    salePrice = Money.fromMajor("8.50", currency),
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
    }

    private fun clickTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                composeRule.onNodeWithTag(tag)
                    .assertIsDisplayed()
                    .assertIsEnabled()
            }.isSuccess
        }
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
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

    private fun waitUntilTextDisplayed(text: String, timeoutMillis: Long = 5_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching { composeRule.onNodeWithText(text).assertIsDisplayed() }.isSuccess
        }
    }

    private fun waitUntilTagDisplayed(tag: String, timeoutMillis: Long = 5_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitUntilDisplayedInList(
        listTag: String,
        @StringRes labelRes: Int,
        timeoutMillis: Long = 5_000L,
    ) {
        val label = context.getString(labelRes)
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag(listTag)
                    .performScrollToNode(hasText(label))
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
    }

    private companion object {
        const val TEST_PRODUCT_NAME = "ARROZ EXTRA 5 KG"
        val TEST_UNIT_ID = java.util.UUID.fromString("423e4567-e89b-42d3-a456-426614174000")
        val TEST_LOCATION_ID = java.util.UUID.fromString("523e4567-e89b-42d3-a456-426614174000")
        val TEST_PRODUCT_ID = java.util.UUID.fromString("623e4567-e89b-42d3-a456-426614174000")
    }
}
