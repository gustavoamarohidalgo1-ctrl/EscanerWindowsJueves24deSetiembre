package com.facturastock.app.data.ocr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.data.demo.DemoInvoiceFixture
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.OcrImageFile
import java.math.BigDecimal
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoAwareInvoiceTextRecognizerTest {
    @Test
    fun exactDemoDraftUsesDeterministicFakeWithoutReadingAFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = DemoConfigurationRepository(DEMO_BUSINESS_ID)
        val draftId = DemoPurchaseScenario.draftIdFor(DEMO_BUSINESS_ID)
        val recognizer = DemoAwareInvoiceTextRecognizer(
            appConfigurationRepository = configuration,
            mlKit = MlKitInvoiceTextRecognizer(context, DefaultDispatcherProvider()),
        )

        val result = recognizer.recognize(
            listOf(
                OcrImageFile(
                    sourceImageId = DemoPurchaseScenario.imageIdFor(DEMO_BUSINESS_ID),
                    relativePath = "draft_images/${draftId.value}/ocr/runs/fake/demo.jpg",
                    mimeType = "image/jpeg",
                    widthPx = DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
                    heightPx = DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
                    fileSizeBytes = 1L,
                ),
            ),
        )

        assertEquals(1, result.pages.size)
        assertTrue(result.text.contains(DemoInvoiceFixture.DOCUMENT_NUMBER))
        assertTrue(result.text.contains("38 líneas"))
    }

    @Test
    fun replacingTheDemoPageDisablesTheFakeEvenWhenTheDraftPathIsUnchanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val draftId = DemoPurchaseScenario.draftIdFor(DEMO_BUSINESS_ID)
        val recognizer = DemoAwareInvoiceTextRecognizer(
            appConfigurationRepository = DemoConfigurationRepository(DEMO_BUSINESS_ID),
            mlKit = MlKitInvoiceTextRecognizer(context, DefaultDispatcherProvider()),
        )

        assertFalse(
            recognizer.isExactDemoPage(
                listOf(
                    OcrImageFile(
                        sourceImageId = REPLACEMENT_IMAGE_ID,
                        relativePath = "draft_images/${draftId.value}/ocr/runs/replaced/page.jpg",
                        mimeType = "image/jpeg",
                        widthPx = DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
                        heightPx = DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
                        fileSizeBytes = 1L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun configurationCancellationIsNeverConvertedIntoNormalMlKitFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cancellation = CancellationException("cancelacion simulada")
        val recognizer = DemoAwareInvoiceTextRecognizer(
            appConfigurationRepository = DemoConfigurationRepository(
                businessId = DEMO_BUSINESS_ID,
                currentFailure = cancellation,
            ),
            mlKit = MlKitInvoiceTextRecognizer(context, DefaultDispatcherProvider()),
        )

        val failure = runCatching {
            recognizer.isExactDemoPage(
                listOf(
                    OcrImageFile(
                        sourceImageId = DemoPurchaseScenario.imageIdFor(DEMO_BUSINESS_ID),
                        relativePath = "draft_images/${DemoPurchaseScenario.draftIdFor(DEMO_BUSINESS_ID).value}/page.jpg",
                        mimeType = "image/jpeg",
                        widthPx = 1,
                        heightPx = 1,
                        fileSizeBytes = 1L,
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure === cancellation)
    }

    private class DemoConfigurationRepository(
        businessId: BusinessId,
        private val currentFailure: Throwable? = null,
    ) :
        AppConfigurationRepository {
        private val state = MutableStateFlow(
            AppConfiguration(
                onboardingCompleted = true,
                businessId = businessId,
                demoBusinessId = businessId,
                taxRate = TaxRate(BigDecimal("18")),
                costPolicy = CostPolicy.NET,
                currency = CurrencyCode.of("PEN"),
                zoneId = ZoneId.of("America/Lima"),
            ),
        )

        override fun observe(): Flow<AppConfiguration> = state
        override suspend fun current(): AppConfiguration {
            currentFailure?.let { throw it }
            return state.value
        }
        override suspend fun completeOnboarding(
            businessId: BusinessId,
            taxRate: TaxRate,
            costPolicy: CostPolicy,
        ) = Unit
        override suspend fun updateTaxRate(rate: TaxRate) = Unit
        override suspend fun updateCostPolicy(policy: CostPolicy) = Unit
        override suspend fun updateImageRetentionPolicy(policy: ImageRetentionPolicy) = Unit
        override suspend fun updateBackupEnabled(enabled: Boolean) = Unit
        override suspend fun updateDocumentBackupEnabled(enabled: Boolean) = Unit
        override suspend fun updateDiagnosticsEnabled(enabled: Boolean) = Unit
        override suspend fun updateBiometricLockEnabled(enabled: Boolean) = Unit
        override suspend fun enterDemoMode(demoBusinessId: BusinessId) = Unit
        override suspend fun exitDemoMode() = Unit
    }

    private companion object {
        val DEMO_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("35000000-0000-0000-0000-000000000035"),
        )
        val REPLACEMENT_IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("35000000-0000-0000-0000-000000000036"),
        )
    }
}
