package com.facturastock.app.feature

import android.Manifest
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.facturastock.app.MainActivity
import com.facturastock.app.R
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
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
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.feature.capture.CaptureTestTags
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewTestTags
import com.facturastock.app.feature.home.HomeTestTags
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.linereview.InvoiceLineReviewTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica el arranque Hilt de extremo a extremo. Con la compuerta del prompt 9, el estado de
 * configuración se fija completo vía [TestAppConfigurationState] ANTES de lanzar la
 * actividad; el módulo real se reemplaza por [com.facturastock.app.di.AppConfigurationModule]
 * con `@TestInstallIn` en el módulo de test.
 *
 * El permiso de cámara se concede por adelantado ([GrantPermissionRule]): el paso de origen
 * comprueba el permiso al pulsar "Tomar foto" y, al tenerlo ya, navega directo a la cámara
 * sin diálogo del sistema.
 *
 * REQUISITO DE ENTORNO: el paso de cámara usa CameraX real (prompt 12), por lo que este test
 * necesita un dispositivo/emulador con cámara funcional —en el emulador con cámara virtual el
 * obturador captura un JPEG real y el flujo sigue a la vista previa—. Si el enlace de la
 * cámara falla en un entorno concreto, la pantalla muestra el error recuperable con la salida
 * "Elegir imagen" hacia la galería del paso de origen.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltUdfRuntimeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
    )

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
    fun hiltStartsAndInjectedUdfRoutesCompleteThePurchaseFlow() {
        scenario.onActivity { activity ->
            assertTrue(activity.application is HiltTestApplication)
        }

        click(R.string.home_shortcut_products)
        waitUntilDisplayed(R.string.products_empty_title)
        clickContentDescription(R.string.action_back)
        clickNavigation(R.string.navigation_inventory)
        waitUntilDisplayedInList(
            listTag = InventoryTestTags.LIST_SCREEN,
            labelRes = R.string.inventory_empty_title,
        )
        clickNavigation(R.string.navigation_home)
        click(R.string.action_view_purchases)
        waitUntilDisplayed(R.string.purchases_empty_title)
        clickContentDescription(R.string.action_back)

        runBlocking {
            seedCatalogForRecognizedInvoice(
                requireNotNull(TestAppConfigurationState.current.value.businessId),
                TestAppConfigurationState.current.value.currency,
            )
        }

        click(R.string.action_scan_invoice)
        // Con el permiso concedido, "Tomar foto" en origen abre la cámara directamente.
        click(R.string.action_take_photo)
        // Obturador real de CameraX: la captura produce un JPEG que se importa como página 0.
        clickTag(CaptureTestTags.SHUTTER)
        processPreviewIntoOcr()
        confirmUncertainEssentialHeaderFields()
        click(R.string.header_review_review_products)
        resolveExplicitTaxDecisionAndOpenProductLinking()
        click(R.string.action_continue)
        click(R.string.summary_action_prepare)
        click(R.string.summary_action_register)
        click(R.string.action_confirm_purchase)

        waitUntilDisplayed(R.string.action_view_purchase_detail)
        runBlocking {
            val committed = database.purchaseDao().listForBusiness(
                businessId = requireNotNull(TestAppConfigurationState.current.value.businessId).value,
                limit = 10,
                offset = 0,
            ).single()
            assertTrue(committed.status == "POSTED")
            val draft = requireNotNull(database.invoiceDraftDao().findById(committed.sourceDraftId))
            assertTrue(draft.status == DraftStatus.COMMITTED.name)
            assertTrue(draft.confirmedPurchaseId == committed.purchaseId)
            val lines = database.purchaseLineDao().listForPurchase(committed.purchaseId)
            val movements = database.inventoryDao().listMovementsForPurchase(
                committed.businessId,
                committed.purchaseId,
            )
            assertTrue(lines.isNotEmpty())
            assertTrue(movements.size == lines.size)
            assertTrue(movements.all { movement ->
                movement.purchaseId == committed.purchaseId &&
                    movement.purchaseLineId in lines.map { it.purchaseLineId }
            })
            assertTrue(database.auditEventDao()
                .listForPurchase(committed.businessId, committed.purchaseId).isNotEmpty())
            assertTrue(
                database.outboxOperationDao().listReady(
                    pendingStatus = "PENDING",
                    now = Long.MAX_VALUE,
                    limit = 100,
                ).any { operation -> operation.purchaseId == committed.purchaseId },
            )
        }
        scenario.recreate()
        waitUntilDisplayed(R.string.action_view_purchase_detail)

        click(R.string.action_view_purchase_detail)
        waitUntilDisplayed(R.string.action_back)
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntilTagDisplayed(HomeTestTags.DRAFTS_LIST)
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

    private suspend fun seedCatalogForRecognizedInvoice(
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
                    name = "ARROZ EXTRA 5 KG",
                    locationId = locationId,
                    salePrice = Money.fromMajor("8.50", currency),
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
    }

    private fun click(@StringRes labelRes: Int) {
        val label = context.getString(labelRes)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            runCatching {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
        val node = composeRule.onNodeWithText(label)
        runCatching { node.performScrollTo() }
        node.performClick()
        composeRule.waitForIdle()
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

    private fun clickContentDescription(@StringRes labelRes: Int) {
        val label = context.getString(labelRes)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            runCatching {
                composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithContentDescription(label).performClick()
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

    private fun processPreviewIntoOcr() {
        click(R.string.preview_action_process)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            isDisplayed(R.string.preview_quality_continue) ||
                isDisplayed(R.string.header_review_review_products)
        }
        if (isDisplayed(R.string.preview_quality_continue)) {
            click(R.string.preview_quality_continue)
        }
        // OcrViewModel arranca el pipeline al entrar en Procesamiento y navega al completar.
        waitUntilDisplayed(R.string.header_review_review_products, timeoutMillis = 30_000L)
    }

    /**
     * El parser puede exigir confirmación humana aunque el valor sea válido. El E2E debe
     * respetar esa compuerta en vez de asumir que todo OCR atraviesa la revisión en automático.
     * Los campos inválidos no exponen este control y siguen haciendo fallar el recorrido.
     */
    private fun confirmUncertainEssentialHeaderFields() {
        val list = composeRule.onNodeWithTag(InvoiceHeaderReviewTestTags.LIST)
        val label = context.getString(R.string.header_review_confirm_verified_value)
        repeat(MAX_HEADER_FIELDS) {
            val present = runCatching {
                list.performScrollToNode(hasText(label))
            }.isSuccess
            if (!present) return
            composeRule.onAllNodesWithText(label)[0].performClick()
            composeRule.waitForIdle()
        }
    }

    /**
     * El comprobante fake contiene IGV explícito, pero la aplicación no debe inferir si ese
     * impuesto está incluido o excluido. El primer intento de vincular enfoca la única línea
     * pendiente; el test reproduce entonces la decisión humana coherente con 100 + 18 = 118.
     */
    private fun resolveExplicitTaxDecisionAndOpenProductLinking() {
        clickTag(InvoiceLineReviewTestTags.LINK_PRODUCTS)
        // El efecto FocusLine desplaza a la fila bloqueante; la decisión se toma dentro de su
        // editor, no desde el resumen inferior.
        click(R.string.line_review_edit)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag(InvoiceLineReviewTestTags.EDITOR).assertIsDisplayed()
            }.isSuccess
        }
        click(R.string.line_review_tax_excluded)
        waitUntilTaxDecisionPersisted()
        if (isDisplayed(R.string.line_review_confirm_reviewed)) {
            click(R.string.line_review_confirm_reviewed)
            waitUntilTaxDecisionPersisted()
        }
        clickTag(InvoiceLineReviewTestTags.CLOSE_EDITOR)
        clickTag(InvoiceLineReviewTestTags.LINK_PRODUCTS)
    }

    private fun waitUntilTaxDecisionPersisted() {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                composeRule.onNodeWithText(
                    context.getString(R.string.line_review_tax_excluded),
                ).assertIsEnabled()
            }.isSuccess
        }
    }

    private fun isDisplayed(@StringRes labelRes: Int): Boolean = runCatching {
        composeRule.onNodeWithText(context.getString(labelRes)).assertIsDisplayed()
    }.isSuccess

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
        const val MAX_HEADER_FIELDS = 11
        val TEST_UNIT_ID = java.util.UUID.fromString("423e4567-e89b-42d3-a456-426614174000")
        val TEST_LOCATION_ID = java.util.UUID.fromString("523e4567-e89b-42d3-a456-426614174000")
        val TEST_PRODUCT_ID = java.util.UUID.fromString("623e4567-e89b-42d3-a456-426614174000")
    }
}
