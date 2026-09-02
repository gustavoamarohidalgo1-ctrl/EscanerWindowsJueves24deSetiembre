package com.facturastock.app.navigation

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.feature.capture.CaptureTestTags
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewTestTags
import com.facturastock.app.feature.home.HomeTestTags
import com.facturastock.app.feature.invoices.InvoiceHubTestTags
import com.facturastock.app.feature.linereview.InvoiceLineReviewTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Con compuerta (prompt 9) la app real solo arranca en HOME si el onboarding está completo:
 * el módulo [com.facturastock.app.di.AppConfigurationModule] se reemplaza por un fake de test
 * cuyo estado se fija completo ANTES de lanzar la actividad manualmente.
 *
 * El permiso de cámara se concede por adelantado ([GrantPermissionRule]): el paso de origen
 * comprueba el permiso al pulsar "Tomar foto" y, al tenerlo ya, navega directo a la cámara
 * sin diálogo del sistema.
 *
 * REQUISITO DE ENTORNO: el paso de cámara usa CameraX real (prompt 12), por lo que el test de
 * recreación necesita un dispositivo/emulador con cámara funcional —en el emulador con cámara
 * virtual el obturador captura un JPEG real y el flujo sigue a la vista previa—. Si el enlace
 * de la cámara falla, la pantalla ofrece la salida "Elegir imagen" hacia la galería.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationRecreationTest {
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
    lateinit var invoiceDraftRepository: InvoiceDraftRepository

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val configuration = completedGateConfiguration()
        TestAppConfigurationState.current.value = configuration
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
    fun activityRecreationKeepsCurrentFlowDestination() {
        click(R.string.action_scan_invoice)
        // Con el permiso concedido, "Tomar foto" en origen abre la cámara directamente.
        click(R.string.action_take_photo)
        // Obturador real de CameraX: la captura produce un JPEG que se importa como página 0.
        clickTag(CaptureTestTags.SHUTTER)
        processPreviewIntoOcr()
        confirmUncertainEssentialHeaderFields()
        click(R.string.header_review_review_products)

        waitUntilTagDisplayed(InvoiceLineReviewTestTags.LINK_PRODUCTS)

        scenario.recreate()
        waitUntilTagDisplayed(InvoiceLineReviewTestTags.LINK_PRODUCTS)
    }

    @Test
    fun systemBackOnDraftShowsDiscardConfirmation() {
        click(R.string.action_scan_invoice)

        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntilDisplayed(R.string.discard_dialog_title)
        composeRule
            .onNodeWithText(context.getString(R.string.action_keep_editing))
            .assertIsDisplayed()
    }

    @Test
    fun invoiceHubCreatesDurableDraftAndDiscardDeletesBeforeReturning() {
        val businessId = requireNotNull(TestAppConfigurationState.current.value.activeBusinessId)
        val before = runBlocking {
            invoiceDraftRepository.observeDrafts(businessId, status = null)
                .first()
                .map { draft -> draft.draftId }
                .toSet()
        }

        clickNavigation(R.string.navigation_invoices)
        composeRule.onNodeWithTag(InvoiceHubTestTags.SCREEN).performScrollToNode(
            hasTestTag(InvoiceHubTestTags.REGISTER_ACTION),
        )
        clickTag(InvoiceHubTestTags.REGISTER_ACTION)
        waitUntilDisplayed(R.string.action_take_photo)

        var createdDraftId: DraftId? = null
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            createdDraftId = runBlocking {
                invoiceDraftRepository.observeDrafts(businessId, status = null)
                    .first()
                    .singleOrNull { draft -> draft.draftId !in before }
                    ?.draftId
            }
            createdDraftId != null
        }
        val durableDraftId = requireNotNull(createdDraftId)
        assertEquals(
            DraftStatus.CREATED,
            runBlocking { invoiceDraftRepository.findDraft(durableDraftId)?.status },
        )

        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntilDisplayed(R.string.discard_dialog_title)
        click(R.string.action_discard_draft)

        // El efecto de navegación solo se publica después de que Room confirma la ausencia.
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            runBlocking { invoiceDraftRepository.findDraft(durableDraftId) == null }
        }
        assertNull(runBlocking { invoiceDraftRepository.findDraft(durableDraftId) })
        waitUntilTagDisplayed(InvoiceHubTestTags.SCREEN)
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
        waitUntilTagDisplayed(HomeTestTags.DRAFTS_LIST)

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

    private fun clickNavigation(@StringRes labelRes: Int) {
        val matcher = hasText(context.getString(labelRes)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            runCatching { composeRule.onNode(matcher).assertIsDisplayed() }.isSuccess
        }
        composeRule.onNode(matcher).performClick()
        composeRule.waitForIdle()
    }

    private fun waitUntilTagDisplayed(tag: String, timeoutMillis: Long = 10_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun processPreviewIntoOcr() {
        // CameraX y la publicación Room concluyen fuera del reloj de idleness de Compose.
        // Esperar el CTA de Preview demuestra que la página ya se publicó de forma atómica.
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

    private companion object {
        const val MAX_HEADER_FIELDS = 11
    }
}
