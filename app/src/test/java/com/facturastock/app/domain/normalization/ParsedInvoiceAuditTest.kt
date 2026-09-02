package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato determinista del audit trail versionado: mismo parseo, mismo orden de campos,
 * advertencias y bloqueos. Y la regla que impide que una confianza limitada se autoconfirme.
 */
class ParsedInvoiceAuditTest {
    @Test
    fun `the audit is versioned and fingerprinted`() {
        assertThrows(IllegalArgumentException::class.java) { audit(parserVersion = 0) }
        assertThrows(IllegalArgumentException::class.java) { audit(fingerprint = "a".repeat(63)) }
        assertThrows(IllegalArgumentException::class.java) { audit(fingerprint = "A".repeat(64)) }
    }

    @Test
    fun `header fields must precede positioned line fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            audit(
                fields = listOf(lineDescriptionField(position = 0), issuerRucField()),
            )
        }
    }

    @Test
    fun `two traces cannot describe the same field and position`() {
        assertThrows(IllegalArgumentException::class.java) {
            audit(fields = listOf(issuerRucField(), issuerRucField()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            audit(
                fields = listOf(
                    issuerRucField(),
                    issuerRucField().copy(
                        candidates = listOf(ocrCandidate(rawText = "20512345678")),
                    ),
                ),
            )
        }
    }

    @Test
    fun `warnings must be unique and deterministically ordered`() {
        val issuer = warning(ParsedInvoiceFieldKind.ISSUER_RUC)
        val total = warning(ParsedInvoiceFieldKind.DOCUMENT_TOTAL)

        assertThrows(IllegalArgumentException::class.java) {
            audit(warnings = listOf(total, issuer))
        }
        assertThrows(IllegalArgumentException::class.java) {
            audit(warnings = listOf(issuer, issuer))
        }
        assertTrue(audit(warnings = listOf(issuer, total)).warnings.size == 2)
    }

    @Test
    fun `blockers must be unique and ordered by line then code`() {
        val missingQuantity = blocker(
            code = ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_QUANTITY,
            linePosition = 0,
        )
        val missingDescription = blocker(
            code = ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_DESCRIPTION,
            linePosition = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            audit(blockers = listOf(missingDescription, missingQuantity))
        }
        assertThrows(IllegalArgumentException::class.java) {
            audit(blockers = listOf(missingQuantity, missingQuantity))
        }
        assertTrue(audit(blockers = listOf(missingQuantity, missingDescription)).blockers.size == 2)
    }

    @Test
    fun `only a clean high confidence audit may confirm itself`() {
        assertTrue(audit(confidence = ParsedInvoiceConfidence.HIGH).eligibleForAutomaticConfirmation)
        for (limited in listOf(
            ParsedInvoiceConfidence.MEDIUM,
            ParsedInvoiceConfidence.LOW,
            ParsedInvoiceConfidence.UNKNOWN,
        )) {
            assertFalse(audit(confidence = limited).eligibleForAutomaticConfirmation)
        }
    }

    @Test
    fun `a pending warning or a line blocker withdraws automatic confirmation`() {
        val withWarning = audit(
            confidence = ParsedInvoiceConfidence.HIGH,
            warnings = listOf(warning(ParsedInvoiceFieldKind.ISSUER_RUC)),
        )
        val withInformativeWarning = audit(
            confidence = ParsedInvoiceConfidence.HIGH,
            warnings = listOf(
                warning(ParsedInvoiceFieldKind.ISSUER_RUC, requiresReview = false),
            ),
        )
        val withBlocker = audit(
            confidence = ParsedInvoiceConfidence.HIGH,
            blockers = listOf(
                blocker(ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_QUANTITY, linePosition = 0),
            ),
        )

        assertFalse(withWarning.eligibleForAutomaticConfirmation)
        assertTrue(withInformativeWarning.eligibleForAutomaticConfirmation)
        assertFalse(withBlocker.eligibleForAutomaticConfirmation)
    }

    @Test
    fun `a field without a selection stays unknown and under review`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParsedInvoiceFieldTrace(
                kind = ParsedInvoiceFieldKind.ISSUE_DATE,
                confidence = ParsedInvoiceConfidence.MEDIUM,
                requiresReview = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParsedInvoiceFieldTrace(
                kind = ParsedInvoiceFieldKind.ISSUE_DATE,
                confidence = ParsedInvoiceConfidence.UNKNOWN,
                requiresReview = false,
            )
        }
        val absent = ParsedInvoiceFieldTrace(
            kind = ParsedInvoiceFieldKind.ISSUE_DATE,
            confidence = ParsedInvoiceConfidence.UNKNOWN,
            requiresReview = true,
        )
        assertTrue(absent.candidates.isEmpty())
    }

    @Test
    fun `an ocr candidate keeps its raw text and its evidence`() {
        assertThrows(IllegalArgumentException::class.java) { ocrCandidate(rawText = null) }
        assertThrows(IllegalArgumentException::class.java) { ocrCandidate(evidence = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            ocrCandidate(warnings = listOf("LOW_CONFIDENCE", "FIELD_REQUIRES_REVIEW"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ocrCandidate(reasons = listOf("LABEL_MATCH", "LABEL_MATCH"))
        }
    }

    @Test
    fun `a line blocker always keeps the evidence of its row`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParsedInvoiceBlocker(
                code = ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_QUANTITY,
                linePosition = 0,
                evidence = emptyList(),
            )
        }
    }

    private fun audit(
        parserVersion: Int = 1,
        fingerprint: String = "a".repeat(64),
        confidence: ParsedInvoiceConfidence = ParsedInvoiceConfidence.HIGH,
        fields: List<ParsedInvoiceFieldTrace> = listOf(issuerRucField()),
        warnings: List<ParsedInvoiceWarning> = emptyList(),
        blockers: List<ParsedInvoiceBlocker> = emptyList(),
    ): ParsedInvoiceAudit = ParsedInvoiceAudit(
        draftId = DraftId.from(UUID(0L, 51L)),
        runId = OcrRunId.from(UUID(0L, 52L)),
        parserVersion = parserVersion,
        contextFingerprint = fingerprint,
        confidence = confidence,
        fields = fields,
        warnings = warnings,
        blockers = blockers,
    )

    private fun issuerRucField(): ParsedInvoiceFieldTrace = ParsedInvoiceFieldTrace(
        kind = ParsedInvoiceFieldKind.ISSUER_RUC,
        selectedCandidateIndex = 0,
        candidates = listOf(ocrCandidate()),
        confidence = ParsedInvoiceConfidence.HIGH,
        requiresReview = false,
    )

    private fun lineDescriptionField(position: Int): ParsedInvoiceFieldTrace =
        ParsedInvoiceFieldTrace(
            kind = ParsedInvoiceFieldKind.LINE_DESCRIPTION,
            position = position,
            selectedCandidateIndex = 0,
            candidates = listOf(ocrCandidate()),
            confidence = ParsedInvoiceConfidence.HIGH,
            requiresReview = false,
        )

    private fun ocrCandidate(
        rawText: String? = "205I2345678",
        warnings: List<String> = emptyList(),
        reasons: List<String> = listOf("LABEL_MATCH"),
        evidence: List<CandidateEvidence> = listOf(evidence()),
    ): ParsedInvoiceCandidateTrace = ParsedInvoiceCandidateTrace(
        canonicalValue = "20512345678",
        rawText = rawText,
        origin = ParsedInvoiceValueOrigin.OCR,
        confidencePermille = 960,
        confidence = ParsedInvoiceConfidence.HIGH,
        requiresReview = false,
        warnings = warnings,
        reasons = reasons,
        evidence = evidence,
    )

    private fun warning(
        field: ParsedInvoiceFieldKind,
        requiresReview: Boolean = true,
    ): ParsedInvoiceWarning = ParsedInvoiceWarning(
        code = ParsedInvoiceWarningCode.FIELD_REQUIRES_REVIEW,
        field = field,
        requiresReview = requiresReview,
        evidence = listOf(evidence()),
    )

    private fun blocker(
        code: ParsedInvoiceBlockerCode,
        linePosition: Int,
    ): ParsedInvoiceBlocker = ParsedInvoiceBlocker(
        code = code,
        linePosition = linePosition,
        evidence = listOf(evidence()),
    )

    private fun evidence(): CandidateEvidence = CandidateEvidence(
        rawText = "R.U.C. 205I2345678",
        unicodeText = "R.U.C. 205I2345678",
        normalizedText = "R.U.C. 205I2345678",
        comparisonText = "R.U.C. 205I2345678",
        sourceImageId = ImageId.from(UUID(0L, 53L)),
        pageIndex = 0,
    )
}
