package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.ImageId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariantes de la capa pura de normalización. Un candidato solo queda resuelto cuando no existe
 * ninguna lectura rival ni advertencia pendiente: es el mecanismo que impide una suposición
 * silenciosa. Toda evidencia conserva además su procedencia OCR completa.
 */
class CandidateTest {
    @Test
    fun `an unresolved candidate must explain why it needs review`() {
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = null,
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = 900,
                warnings = emptyList(),
                alternatives = listOf("1234.56", "1.23456"),
            )
        }
    }

    @Test
    fun `an informative warning alone cannot justify an unresolved candidate`() {
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = null,
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = 900,
                warnings = listOf(CandidateWarning.UNIT_ALIAS_NORMALIZED),
                alternatives = listOf("NIU", "KGM"),
            )
        }
    }

    @Test
    fun `an ambiguous separator keeps both readings and demands review`() {
        val doubtful = Candidate(
            value = null,
            evidence = evidence(),
            boundingBox = InvoiceTextBoundingBox(10, 20, 210, 80),
            confidencePermille = 880,
            warnings = listOf(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR),
            alternatives = listOf("1234.56", "1.23456"),
        )

        assertTrue(doubtful.requiresReview)
        assertEquals(listOf("1234.56", "1.23456"), doubtful.alternatives)
    }

    @Test
    fun `a resolved candidate must not keep alternatives`() {
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = "1234.56",
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = 900,
                alternatives = listOf("1.23456"),
            )
        }
    }

    @Test
    fun `a resolved candidate must not carry a pending warning`() {
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = "1234.56",
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = 900,
                warnings = listOf(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR),
            )
        }
    }

    @Test
    fun `the selected value must not repeat as an alternative`() {
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = "NIU",
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = null,
                alternatives = listOf("NIU"),
            )
        }
    }

    @Test
    fun `warnings and alternatives are rejected when duplicated`() {
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = null,
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = null,
                warnings = listOf(
                    CandidateWarning.AMBIGUOUS_DATE_ORDER,
                    CandidateWarning.AMBIGUOUS_DATE_ORDER,
                ),
                alternatives = listOf("2026-01-02"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = null,
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = null,
                warnings = listOf(CandidateWarning.AMBIGUOUS_DATE_ORDER),
                alternatives = listOf("2026-01-02", "2026-01-02"),
            )
        }
    }

    @Test
    fun `candidate confidence stays inside the permille range`() {
        assertThrows(IllegalArgumentException::class.java) {
            Candidate(
                value = "NIU",
                evidence = evidence(),
                boundingBox = null,
                confidencePermille = 1_001,
            )
        }
    }

    @Test
    fun `a unit alias is normalized without demanding review`() {
        val resolved = Candidate(
            value = "NIU",
            evidence = evidence(),
            boundingBox = null,
            confidencePermille = 1_000,
            warnings = listOf(CandidateWarning.UNIT_ALIAS_NORMALIZED),
        )

        assertFalse(resolved.requiresReview)
    }

    @Test
    fun `evidence keeps image and page together`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandidateSource(rawText = "TOTAL", sourceImageId = ImageId.from(UUID(0L, 41L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CandidateEvidence(
                rawText = "TOTAL",
                unicodeText = "TOTAL",
                normalizedText = "TOTAL",
                comparisonText = "TOTAL",
                pageIndex = 0,
            )
        }
    }

    @Test
    fun `a quadrilateral needs zero or four corners`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandidateSource(
                rawText = "TOTAL",
                cornerPoints = listOf(InvoiceTextPoint(10, 20), InvoiceTextPoint(210, 20)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CandidateEvidence(
                rawText = "TOTAL",
                unicodeText = "TOTAL",
                normalizedText = "TOTAL",
                comparisonText = "TOTAL",
                cornerPoints = listOf(InvoiceTextPoint(10, 20), InvoiceTextPoint(210, 20)),
            )
        }
    }

    @Test
    fun `geometric provenance rejects negative positions and impossible angles`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandidateSource(rawText = "TOTAL", linePosition = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CandidateSource(rawText = "TOTAL", clockwiseAngleTenths = 1_801)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CandidateSource(rawText = "TOTAL", confidencePermille = -1)
        }
    }

    @Test
    fun `an ocr correction must change a non empty fragment`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppliedOcrCorrection(
                originalFragment = "",
                correctedFragment = "O",
                reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppliedOcrCorrection(
                originalFragment = "TOTAL",
                correctedFragment = "TOTAL",
                reason = OcrCorrectionReason.FIXED_VOCABULARY_CONFUSABLE,
            )
        }
    }

    @Test
    fun `normalized evidence never rewrites the original ocr text`() {
        val source = CandidateSource(
            rawText = "  CAFE M0LIDO  ",
            sourceImageId = ImageId.from(UUID(0L, 42L)),
            pageIndex = 0,
        )

        val normalized = PeruvianTextNormalizer.normalize(source)

        assertEquals("  CAFE M0LIDO  ", normalized.rawText)
        assertEquals("CAFE M0LIDO", normalized.comparisonText)
    }

    private fun evidence(): CandidateEvidence = CandidateEvidence(
        rawText = "1,234.56",
        unicodeText = "1,234.56",
        normalizedText = "1,234.56",
        comparisonText = "1,234.56",
        sourceImageId = ImageId.from(UUID(0L, 40L)),
        pageIndex = 0,
        boundingBox = InvoiceTextBoundingBox(10, 20, 210, 80),
    )
}
