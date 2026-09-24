package com.facturastock.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.feature.common.ScannerCodeInputTestTags
import com.facturastock.app.feature.debtors.DebtorsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.feature.sales.WeightSaleTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.action_cancel
import com.facturastock.app.resources.weight_sale_add
import com.facturastock.app.resources.weight_sale_calculated_quantity
import com.facturastock.app.resources.weight_sale_save
import com.facturastock.app.resources.weight_sale_total
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.performClickOnUiThread
import com.facturastock.app.testing.snapshotTable
import com.facturastock.app.ui.format.formatForDisplay
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

/** Venta por importe con la ventana, los repositorios y SQLite reales sobre un directorio temporal. */
class WeightSaleJourneyTest {
    @get:Rule(order = 0)
    val harness = DesktopAppHarness()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var product: Product
    private val pen = CurrencyCode.of("PEN")
    private val locationId = LocationId.from(UUID.randomUUID())
    private val database get() = harness.component.database()
    private val businessId get() = requireNotNull(TestAppConfigurationState.current.value.businessId)

    @Before
    fun setUp() {
        val component = harness.component
        runBlocking {
            component.businesses().create(
                Business(businessId, "Negocio por kilo QA", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            )
            val unitId = UnitId.from(UUID.randomUUID())
            component.units().create(
                UnitOfMeasure(unitId, businessId, "KGM", "Kilogramo", symbol = "kg", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            )
            component.locations().create(
                InventoryLocation(locationId, businessId, "Almacén Principal", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            )
            val result =
                component.productRegistration().register(
                    product =
                        Product(
                            productId = ProductId.from(UUID.randomUUID()),
                            businessId = businessId,
                            unitId = unitId,
                            locationId = locationId,
                            name = "Huevo por kilo",
                            barcode = null,
                            salePrice = Money.ofMinor(800L, pen),
                            createdAt = Instant.EPOCH,
                            updatedAt = Instant.EPOCH,
                        ),
                    quantity = "10".toBigDecimal(),
                    unitCost = UnitCost.of("4", pen),
                )
            product =
                when (result) {
                    is CatalogMutationResult.Saved -> result.value
                    else -> error("No se pudo preparar el producto por kilo: $result")
                }
        }
        harness.setAppContent(composeRule)
    }

    @Test
    fun cashAmountRejectsExcessAndCancelThenEditsTheSameLineBeforeDeductingKilos() {
        completeWeightSale(credit = false)
    }

    @Test
    fun creditAmountAlsoAcceptsKilosAndPostsTheExactStockAndDebtTogether() {
        completeWeightSale(credit = true)
    }

    private fun completeWeightSale(credit: Boolean) {
        // Vender abre directamente la venta al contado; la venta a crédito se abre desde su
        // icono de la barra superior.
        waitForTag(SalesTestTags.OPEN_CREDIT_SALE)
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).assertDoesNotExist()
        if (credit) {
            clickTag(SalesTestTags.OPEN_CREDIT_SALE)
            waitForTag(SalesTestTags.DEBTOR_NAME)
            composeRule.waitUntil(15_000L) {
                runCatching { composeRule.onNodeWithTag(SalesTestTags.OPEN_CREDIT_SALE).assertDoesNotExist() }.isSuccess
            }
            composeRule.waitForIdle()
        }
        waitForReader()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking { database.saleDao().findActiveDraft(businessId.value, "PEN") != null }
        }
        val stockBefore = snapshot("stock_movements")
        if (!credit) {
            val unchangedTables =
                listOf("sales", "sale_lines", "stock_movements", "inventory_balances", "debts", "audit_events")
                    .associateWith(::snapshot)
            searchAndOpenWeightProduct()
            composeRule.onNodeWithTag(WeightSaleTestTags.INPUT).performTextReplacement("100")
            composeRule.onNodeWithTag(WeightSaleTestTags.ERROR).assertIsDisplayed()
            weightDialogButton(Res.string.weight_sale_add).assertIsNotEnabled()
            weightDialogButton(Res.string.action_cancel).performClickOnUiThread(composeRule)
            waitForReader()
            unchangedTables.forEach { (table, rows) -> assertEquals("Cancelar alteró $table", rows, snapshot(table)) }
        }

        searchAndOpenWeightProduct()
        composeRule.onNodeWithTag(WeightSaleTestTags.AMOUNT_MODE).assertIsSelected()
        composeRule.onNodeWithTag(WeightSaleTestTags.INPUT).performTextReplacement("7")
        assertSevenSolesPreview()
        if (credit) {
            clickTag(WeightSaleTestTags.QUANTITY_MODE)
            composeRule.onNodeWithTag(WeightSaleTestTags.INPUT).performTextReplacement("0,875")
            assertSevenSolesPreview()
        }
        weightDialogButton(Res.string.weight_sale_add).assertIsEnabled().performClickOnUiThread(composeRule)
        val originalLine = awaitCartLine("0.875", 700L)
        assertStock("10")
        assertEquals(stockBefore, snapshot("stock_movements"))

        if (!credit) {
            editAmount(originalLine.saleLineId, "8", "1", 800L)
            editAmount(originalLine.saleLineId, "7", "0.875", 700L)
            assertStock("10")
            assertEquals(stockBefore, snapshot("stock_movements"))
        } else {
            scrollTo(SalesTestTags.DEBTOR_NAME)
            composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME).performClick().performTextReplacement("Cliente peso QA")
        }

        scrollTo(SalesTestTags.CHECKOUT)
        // Preparar la venta conserva existencias y deuda hasta el único toque que la registra.
        assertStock("10")
        assertEquals(stockBefore, snapshot("stock_movements"))
        runBlocking { assertNull(database.debtDao().findDebtForSale(originalLine.saleId)) }
        clickTag(SalesTestTags.CHECKOUT)
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .size == 1
            }
        }
        assertPostedSale(originalLine, credit)
        if (credit) {
            // Abierta desde Vender, la venta a crédito registrada termina en la lista de deudores.
            waitForTag(DebtorsTestTags.LIST_SCREEN)
        } else {
            waitForTag(SalesTestTags.OPEN_CREDIT_SALE)
        }
    }

    private fun searchAndOpenWeightProduct() {
        waitForReader()
        // Seleccionar todo y escribir «Hu» en la entrada unificada: busca por nombre.
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.FIELD).performTextReplacement("Hu")
        val tag = SalesTestTags.option(product.productId.value, locationId.value)
        scrollTo(tag)
        clickTag(tag)
        waitForTag(WeightSaleTestTags.DIALOG)
    }

    private fun assertSevenSolesPreview() {
        composeRule
            .onNodeWithTag(WeightSaleTestTags.QUANTITY, useUnmergedTree = true)
            .assertTextEquals(str(Res.string.weight_sale_calculated_quantity, "0,875"))
        composeRule
            .onNodeWithTag(WeightSaleTestTags.TOTAL, useUnmergedTree = true)
            .assertTextEquals(str(Res.string.weight_sale_total, Money.ofMinor(700L, pen).formatForDisplay()))
    }

    private fun editAmount(
        lineId: String,
        amount: String,
        quantity: String,
        total: Long,
    ) {
        scrollTo(WeightSaleTestTags.edit(lineId))
        clickTag(WeightSaleTestTags.edit(lineId))
        waitForTag(WeightSaleTestTags.DIALOG)
        composeRule.onNodeWithTag(WeightSaleTestTags.INPUT).performTextReplacement(amount)
        weightDialogButton(Res.string.weight_sale_save).assertIsEnabled().performClickOnUiThread(composeRule)
        assertEquals(lineId, awaitCartLine(quantity, total).saleLineId)
    }

    private fun awaitCartLine(
        quantity: String,
        total: Long,
    ): SaleLineEntity {
        composeRule.waitUntil(15_000L) {
            runBlocking {
                val draft = database.saleDao().findActiveDraft(businessId.value, "PEN")
                val line = draft?.lines?.singleOrNull()
                if (draft == null || line == null) return@runBlocking false
                line.productId == product.productId.value && line.quantity.toBigDecimal().compareTo(quantity.toBigDecimal()) == 0 &&
                    line.lineTotalMinorUnits == total && draft.sale.totalMinorUnits == total
            }
        }
        composeRule.waitForIdle()
        return runBlocking { requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines.single() }
    }

    private fun assertPostedSale(
        originalLine: SaleLineEntity,
        credit: Boolean,
    ) = runBlocking {
        val posted = requireNotNull(database.saleDao().findWithLines(originalLine.saleId))
        assertEquals("POSTED", posted.sale.status)
        assertEquals(700L, posted.sale.totalMinorUnits)
        val line = posted.lines.single()
        assertEquals(originalLine.saleLineId, line.saleLineId)
        assertEquals(product.productId.value, line.productId)
        assertEquals("KGM", line.unitCodeSnapshot)
        assertNull(line.barcodeSnapshot)
        assertEquals(0, "0.875".toBigDecimal().compareTo(line.quantity.toBigDecimal()))
        assertEquals(800L, requireNotNull(line.unitPriceMinorUnits))
        assertEquals(700L, requireNotNull(line.lineTotalMinorUnits))
        val movement = database.inventoryDao().listMovementsForSale(businessId.value, posted.sale.saleId).single()
        assertEquals("SALE", movement.type)
        assertEquals(line.saleLineId, movement.saleLineId)
        assertEquals(0, "-0.875".toBigDecimal().compareTo(movement.quantityDelta.toBigDecimal()))
        assertEquals(0, "4".toBigDecimal().compareTo(requireNotNull(movement.unitCost).toBigDecimal()))
        val debt = database.debtDao().findDebtForSale(posted.sale.saleId)
        if (credit) {
            assertEquals("Cliente peso QA", requireNotNull(debt).debtorName)
            assertEquals(700L, debt.balanceMinorUnits)
        } else {
            assertNull(debt)
        }
        assertStock("9.125")
        val persistedProduct = requireNotNull(database.productDao().findById(product.productId.value))
        assertNull(persistedProduct.barcode)
        assertEquals(800L, requireNotNull(persistedProduct.salePriceMinorUnits))
    }

    private fun assertStock(expected: String) =
        runBlocking {
            val balance = database.inventoryDao().listDiagnosticBalances(businessId.value).single()
            assertEquals(product.productId.value, balance.productId)
            assertEquals(0, expected.toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
        }

    private fun weightDialogButton(label: StringResource) =
        composeRule.onNode(
            hasText(str(label)) and hasAnyAncestor(hasTestTag(WeightSaleTestTags.DIALOG)),
        )

    /** El campo del lector está habilitado y con el foco del teclado. */
    private fun waitForReader() {
        composeRule.waitForFocusedScannerField()
    }

    private fun scrollTo(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(tag))
                composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            }.isSuccess
        }
    }

    private fun clickTag(tag: String) {
        waitForTag(tag)
        composeRule.onNodeWithTag(tag).assertIsEnabled().performClickOnUiThread(composeRule)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }

    private fun snapshot(table: String): List<List<String?>> = database.snapshotTable(table)

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }
}
