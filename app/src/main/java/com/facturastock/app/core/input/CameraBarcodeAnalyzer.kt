package com.facturastock.app.core.input

import androidx.camera.core.ImageAnalysis

/** Resultado neutral del lector de cámara; ningún tipo de ML Kit cruza esta frontera. */
data class CameraBarcode(
    val value: String,
    val formatName: String,
)

/** Analizador CameraX liberable que puede implementarse con un motor local. */
interface CameraBarcodeAnalyzer : ImageAnalysis.Analyzer {
    fun release()
}

/** Fábrica inyectable para que la UI no dependa directamente de la capa `data`. */
fun interface CameraBarcodeAnalyzerFactory {
    fun create(onBarcode: (CameraBarcode) -> Unit): CameraBarcodeAnalyzer
}
