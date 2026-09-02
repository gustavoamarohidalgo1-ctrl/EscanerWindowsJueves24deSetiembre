package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.ImageQualityReport
import com.facturastock.app.domain.model.InvoiceImage

/**
 * Puerto de análisis heurístico de una página antes del OCR. La implementación debe usar
 * una decodificación muestreada, respetar giro/recorte y cooperar con la cancelación.
 * Ninguna advertencia bloquea por sí sola el procesamiento.
 */
interface ImageQualityAnalyzer {
    suspend fun analyze(image: InvoiceImage): ImageQualityReport
}
