package com.facturastock.app.data.ocr

import com.facturastock.app.data.demo.DemoInvoiceFixture
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Usa OCR fake solo para el borrador sintético estable; todo archivo normal sigue por ML Kit. */
class DemoAwareInvoiceTextRecognizer @Inject constructor(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val mlKit: MlKitInvoiceTextRecognizer,
) : InvoiceTextRecognizer {
    override suspend fun recognize(pages: List<OcrImageFile>) =
        if (isExactDemoPage(pages)) {
            DemoInvoiceFixture.recognize(pages)
        } else {
            mlKit.recognize(pages)
        }

    internal suspend fun isExactDemoPage(pages: List<OcrImageFile>): Boolean {
        if (pages.size != 1) return false
        val demoBusinessId = try {
            appConfigurationRepository.current().demoBusinessId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return false
        val expectedDraftId = DemoPurchaseScenario.draftIdFor(demoBusinessId).value
        val expectedImageId = DemoPurchaseScenario.imageIdFor(demoBusinessId)
        val page = pages.single()
        val path = page.relativePath.split('/')
        return page.sourceImageId == expectedImageId &&
            path.size >= 3 && path[0] == "draft_images" && path[1] == expectedDraftId
    }
}
