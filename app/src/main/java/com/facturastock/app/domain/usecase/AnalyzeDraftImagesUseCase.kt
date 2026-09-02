package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.ImageQualityReport
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.ImageQualityAnalyzer
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import kotlinx.coroutines.flow.first

/** Analiza las páginas secuencialmente para mantener una cota de memoria predecible. */
class AnalyzeDraftImagesUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val imageQualityAnalyzer: ImageQualityAnalyzer,
) {
    suspend operator fun invoke(draftId: DraftId): List<ImageQualityReport> =
        invoiceDraftRepository.observeImages(draftId).first().map { image ->
            imageQualityAnalyzer.analyze(image)
        }
}
