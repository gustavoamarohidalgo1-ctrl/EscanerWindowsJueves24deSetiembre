package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.ImageId

/**
 * Resultado explicable del análisis previo al OCR. Todas las magnitudes son enteras para
 * mantener el dominio libre de punto flotante: porcentajes en milésimas (0..1000), ángulos
 * en décimas de grado y luminancia en 0..255.
 *
 * Las advertencias son heurísticas deliberadamente no bloqueantes. `Possible*` significa
 * exactamente eso: la app detectó una señal que puede reducir el OCR, no certifica que la
 * captura sea defectuosa.
 */
data class ImageQualityReport(
    val imageId: ImageId,
    val effectiveWidthPx: Int,
    val effectiveHeightPx: Int,
    val meanLuminance: Int,
    val darkPixelsPermille: Int,
    val brightPixelsPermille: Int,
    val sharpnessScore: Int,
    val estimatedSkewTenths: Int,
    val borderContentPermille: Int,
    val warnings: List<ImageQualityWarning>,
) {
    val hasWarnings: Boolean
        get() = warnings.isNotEmpty()
}

sealed interface ImageQualityWarning {
    data class LowResolution(
        val widthPx: Int,
        val heightPx: Int,
        val minimumShortSidePx: Int,
        val minimumLongSidePx: Int,
    ) : ImageQualityWarning

    data class PossibleBlur(
        val sharpnessScore: Int,
        val recommendedMinimum: Int,
    ) : ImageQualityWarning

    data class PossibleUnderexposure(
        val meanLuminance: Int,
        val darkPixelsPermille: Int,
    ) : ImageQualityWarning

    data class PossibleOverexposure(
        val meanLuminance: Int,
        val brightPixelsPermille: Int,
    ) : ImageQualityWarning

    data class PossibleSkew(
        val estimatedDegreesTenths: Int,
        val confidencePermille: Int,
    ) : ImageQualityWarning

    data class PossibleIncompleteCrop(
        val borderContentPermille: Int,
        val warningThresholdPermille: Int,
    ) : ImageQualityWarning
}
