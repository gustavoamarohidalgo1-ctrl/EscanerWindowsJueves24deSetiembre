package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.normalization.AppliedOcrCorrection
import com.facturastock.app.domain.normalization.CandidateEvidence
import com.facturastock.app.domain.normalization.OcrCorrectionReason
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.normalization.ParsedInvoiceBlocker
import com.facturastock.app.domain.normalization.ParsedInvoiceBlockerCode
import com.facturastock.app.domain.normalization.ParsedInvoiceCandidateTrace
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldKind
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldTrace
import com.facturastock.app.domain.normalization.ParsedInvoiceValueOrigin
import com.facturastock.app.domain.normalization.ParsedInvoiceWarning
import com.facturastock.app.domain.normalization.ParsedInvoiceWarningCode
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParsedInvoiceAuditCodecTest {
    @Test
    fun `full audit round trip preserves fields candidates reasons evidence warnings and blockers`() {
        val audit = fullAudit()

        val restored = ParsedInvoiceAuditCodec.decode(ParsedInvoiceAuditCodec.encode(audit))

        assertEquals(audit, restored)
        val candidate = restored.fields.last().candidates.single()
        assertEquals(listOf("GEOMETRY", "LABEL_MATCH"), candidate.reasons)
        assertEquals(listOf("LOW_CONFIDENCE", "OCR_CORRECTION"), candidate.warnings)
        assertEquals(false, restored.warnings.first().requiresReview)
        assertEquals(4, candidate.evidence.single().cornerPoints.size)
        assertEquals(3, candidate.evidence.single().corrections.size)
    }

    @Test
    fun `same audit always produces the same bytes and hash`() {
        val audit = fullAudit()

        val first = ParsedInvoiceAuditCodec.encode(audit)
        val second = ParsedInvoiceAuditCodec.encode(audit)

        assertArrayEquals(first, second)
        assertEquals(ParsedInvoiceAuditCodec.sha256(first), ParsedInvoiceAuditCodec.sha256(second))
        assertEquals(64, ParsedInvoiceAuditCodec.sha256(first).length)
    }

    @Test
    fun `reasons participate in payload and hash`() {
        val audit = fullAudit()
        val changedField = audit.fields.last().let { field ->
            field.copy(
                candidates = listOf(
                    field.candidates.single().copy(reasons = listOf("LABEL_MATCH")),
                ),
            )
        }
        val changed = audit.copy(fields = audit.fields.dropLast(1) + changedField)

        val originalPayload = ParsedInvoiceAuditCodec.encode(audit)
        val changedPayload = ParsedInvoiceAuditCodec.encode(changed)

        assertNotEquals(
            ParsedInvoiceAuditCodec.sha256(originalPayload),
            ParsedInvoiceAuditCodec.sha256(changedPayload),
        )
    }

    @Test
    fun `decoder rejects unsupported versions trailing bytes and oversized payloads`() {
        val payload = ParsedInvoiceAuditCodec.encode(fullAudit())
        val unsupportedVersion = payload.copyOf().also { bytes -> bytes[7] = 2 }
        val trailing = payload + byteArrayOf(1)

        assertThrows(IOException::class.java) {
            ParsedInvoiceAuditCodec.decode(unsupportedVersion)
        }
        assertThrows(IOException::class.java) {
            ParsedInvoiceAuditCodec.decode(trailing)
        }
        assertThrows(IOException::class.java) {
            ParsedInvoiceAuditCodec.decode(
                ByteArray(ParsedInvoiceAuditCodec.MAX_PAYLOAD_BYTES + 1),
            )
        }
    }

    private fun fullAudit(): ParsedInvoiceAudit {
        val evidence = evidence()
        val calculatedCandidate = ParsedInvoiceCandidateTrace(
            canonicalValue = "12.34 PEN",
            rawText = null,
            origin = ParsedInvoiceValueOrigin.CALCULATED,
            confidencePermille = 950,
            confidence = ParsedInvoiceConfidence.MEDIUM,
            requiresReview = true,
            warnings = listOf("DERIVED_VALUE"),
            reasons = listOf("ARITHMETIC_RECONCILIATION"),
            evidence = emptyList(),
        )
        val ocrCandidate = ParsedInvoiceCandidateTrace(
            canonicalValue = "CAFÉ MOLIDO",
            rawText = "CAFÉ M0LIDO",
            origin = ParsedInvoiceValueOrigin.OCR,
            confidencePermille = 650,
            confidence = ParsedInvoiceConfidence.LOW,
            requiresReview = true,
            warnings = listOf("LOW_CONFIDENCE", "OCR_CORRECTION"),
            reasons = listOf("GEOMETRY", "LABEL_MATCH"),
            evidence = listOf(evidence),
        )
        val fields = listOf(
            ParsedInvoiceFieldTrace(
                kind = ParsedInvoiceFieldKind.CALCULATED_TOTAL,
                selectedCandidateIndex = 0,
                candidates = listOf(calculatedCandidate),
                confidence = ParsedInvoiceConfidence.MEDIUM,
                requiresReview = true,
            ),
            ParsedInvoiceFieldTrace(
                kind = ParsedInvoiceFieldKind.LINE_DESCRIPTION,
                position = 0,
                selectedCandidateIndex = 0,
                candidates = listOf(ocrCandidate),
                confidence = ParsedInvoiceConfidence.LOW,
                requiresReview = true,
            ),
        )
        val warnings = listOf(
            ParsedInvoiceWarning(
                code = ParsedInvoiceWarningCode.FINANCIAL_DIFFERENCE,
                field = ParsedInvoiceFieldKind.DOCUMENT_TOTAL,
                detail = "Diferencia de S/ 0.01",
                requiresReview = false,
                evidence = listOf(evidence),
            ),
            ParsedInvoiceWarning(
                code = ParsedInvoiceWarningCode.LOW_CONFIDENCE_REQUIRES_CONFIRMATION,
                field = ParsedInvoiceFieldKind.LINE_DESCRIPTION,
                position = 0,
                detail = "Confirmar descripción",
                evidence = listOf(evidence),
            ),
        )
        return ParsedInvoiceAudit(
            draftId = DraftId.from(UUID.fromString(DRAFT_ID)),
            runId = OcrRunId.from(UUID.fromString(RUN_ID)),
            parserVersion = 7,
            contextFingerprint = "a".repeat(64),
            confidence = ParsedInvoiceConfidence.LOW,
            fields = fields,
            warnings = warnings,
            blockers = listOf(
                ParsedInvoiceBlocker(
                    code = ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_QUANTITY,
                    linePosition = 0,
                    evidence = listOf(evidence),
                ),
            ),
        )
    }

    private fun evidence(): CandidateEvidence = CandidateEvidence(
        rawText = "CAFÉ M0LIDO",
        unicodeText = "CAFÉ M0LIDO",
        normalizedText = "CAFÉ MOLIDO",
        comparisonText = "CAFE MOLIDO",
        corrections = listOf(
            AppliedOcrCorrection(
                originalFragment = "0",
                correctedFragment = "O",
                reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
            ),
            AppliedOcrCorrection(
                originalFragment = "U0",
                correctedFragment = "UN",
                reason = OcrCorrectionReason.UNIT_CONFUSABLE_IN_UNIT_CONTEXT,
            ),
            AppliedOcrCorrection(
                originalFragment = "T0TAL",
                correctedFragment = "TOTAL",
                reason = OcrCorrectionReason.FIXED_VOCABULARY_CONFUSABLE,
            ),
        ),
        sourceImageId = ImageId.from(UUID.fromString(IMAGE_ID)),
        pageIndex = 1,
        boundingBox = InvoiceTextBoundingBox(10, 20, 210, 80),
        cornerPoints = listOf(
            InvoiceTextPoint(10, 20),
            InvoiceTextPoint(210, 20),
            InvoiceTextPoint(210, 80),
            InvoiceTextPoint(10, 80),
        ),
        blockPosition = 2,
        linePosition = 3,
        elementPosition = 4,
        clockwiseAngleTenths = -15,
    )

    private companion object {
        const val DRAFT_ID = "11111111-1111-1111-1111-111111111111"
        const val RUN_ID = "22222222-2222-2222-2222-222222222222"
        const val IMAGE_ID = "33333333-3333-3333-3333-333333333333"
    }
}
