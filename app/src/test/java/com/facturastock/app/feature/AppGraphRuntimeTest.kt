package com.facturastock.app.feature

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.facturastock.app.feature.catalogs.CatalogsTestTags
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.action_cancel
import com.facturastock.app.resources.inventory_empty_title
import com.facturastock.app.resources.navigation_home
import com.facturastock.app.resources.navigation_inventory
import com.facturastock.app.resources.navigation_invoices
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.performClickOnUiThread
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Verifica el grafo Dagger real de escritorio, el acceso manual al catálogo y que lo guardado
 * sobrevive a cerrar y reabrir la ventana, sin la sección Facturas.
 */
class AppGraphRuntimeTest {
    @get:Rule(order = 0)
    val harness = DesktopAppHarness()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val businessRepository get() = harness.component.businesses()
    private val database get() = harness.component.database()
    private val unitRepository get() = harness.component.units()
    private val inventoryLocationRepository get() = harness.component.locations()
    private val productRepository get() = harness.component.products()

    @Before
    fun setUp() {
        runBlocking {
            val businessId = requireNotNull(TestAppConfigurationState.current.value.businessId)
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
        harness.setAppContent(composeRule)
    }

    @Test
    fun appGraphStartsAndManualCatalogSurvivesRestartWithoutInvoiceTab() {
        waitUntilTagDisplayed(SalesTestTags.SCREEN, timeoutMillis = 15_000L)
        listOf(Res.string.navigation_home, Res.string.navigation_invoices).forEach { labelRes ->
            val matcher = hasText(str(labelRes)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            composeRule.onNode(matcher).assertDoesNotExist()
        }
        openManualCatalogAndCancel()

        runBlocking {
            seedCatalogProduct(
                requireNotNull(TestAppConfigurationState.current.value.businessId),
                TestAppConfigurationState.current.value.currency,
            )
        }

        waitUntilTextDisplayedInInventory(TEST_PRODUCT_NAME)

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

        // Cerrar y reabrir la ventana: el catálogo persistido vuelve a mostrarse sin duplicados.
        harness.restartApp(composeRule)
        waitUntilTagDisplayed(SalesTestTags.SCREEN, timeoutMillis = 15_000L)
        openManualCatalogAndCancel(inventoryEmpty = false)
        waitUntilTextDisplayedInInventory(TEST_PRODUCT_NAME)
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

    private fun openManualCatalogAndCancel(inventoryEmpty: Boolean = true) {
        clickNavigation(Res.string.navigation_inventory)
        if (inventoryEmpty) {
            waitUntilDisplayedInList(
                listTag = InventoryTestTags.LIST_SCREEN,
                labelRes = Res.string.inventory_empty_title,
            )
        } else {
            waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        }
        clickTag(InventoryTestTags.REGISTER_MANUAL)
        waitUntilTagDisplayed(CatalogsTestTags.FORM)
        composeRule.onNodeWithText(str(Res.string.action_cancel))
            .performScrollTo()
            .performClickOnUiThread(composeRule)
        // El alta manual directa cancela de vuelta a Inventario (CatalogsViewModel.emitRegistrationBack).
        waitUntilTagDisplayed(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
    }

    private fun waitUntilTextDisplayedInInventory(text: String) {
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            runCatching {
                composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).performScrollToNode(hasText(text))
                composeRule.onNodeWithText(text).assertIsDisplayed()
            }.isSuccess
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
        composeRule.onNodeWithTag(tag).performClickOnUiThread(composeRule)
        composeRule.waitForIdle()
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

    private fun waitUntilTagDisplayed(tag: String, timeoutMillis: Long = 10_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitUntilDisplayedInList(
        listTag: String,
        labelRes: StringResource,
        timeoutMillis: Long = 10_000L,
    ) {
        val label = str(labelRes)
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag(listTag)
                    .performScrollToNode(hasText(label))
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun str(resource: StringResource): String = runBlocking { getString(resource) }

    private companion object {
        const val TEST_PRODUCT_NAME = "ARROZ EXTRA 5 KG"
        val TEST_UNIT_ID = java.util.UUID.fromString("423e4567-e89b-42d3-a456-426614174000")
        val TEST_LOCATION_ID = java.util.UUID.fromString("523e4567-e89b-42d3-a456-426614174000")
        val TEST_PRODUCT_ID = java.util.UUID.fromString("623e4567-e89b-42d3-a456-426614174000")
    }
}
