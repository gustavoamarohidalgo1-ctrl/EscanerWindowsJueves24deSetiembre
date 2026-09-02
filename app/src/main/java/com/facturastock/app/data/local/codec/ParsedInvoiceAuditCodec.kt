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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Codec binario determinista del audit trail. Enums y IDs se guardan por nombre/valor estable. */
internal object ParsedInvoiceAuditCodec {
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 4_000_000

    fun encode(audit: ParsedInvoiceAudit): ByteArray {
        val buffer = BoundedAuditOutputStream(MAX_PAYLOAD_BYTES)
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeBoundedString(audit.draftId.value, MAX_IDENTIFIER_BYTES)
            output.writeBoundedString(audit.runId.value, MAX_IDENTIFIER_BYTES)
            output.writeInt(audit.parserVersion)
            output.writeBoundedString(audit.contextFingerprint, MAX_HASH_BYTES)
            output.writeEnum(audit.confidence)
            output.writeCount(audit.fields.size, "fields")
            audit.fields.forEach { field -> output.writeField(field) }
            output.writeCount(audit.warnings.size, "warnings")
            audit.warnings.forEach { warning -> output.writeWarning(warning) }
            output.writeCount(audit.blockers.size, "blockers")
            audit.blockers.forEach { blocker -> output.writeBlocker(blocker) }
        }
        return buffer.toByteArray().also { payload ->
            require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
                "El audit trail excede $MAX_PAYLOAD_BYTES bytes serializados"
            }
        }
    }

    @Throws(IOException::class)
    fun decode(payload: ByteArray): ParsedInvoiceAudit {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) {
            throw IOException("Tamaño de audit trail inválido: ${payload.size}")
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Cabecera de audit trail inválida")
                val version = input.readInt()
                if (version != VERSION) {
                    throw IOException("Versión de audit trail no soportada: $version")
                }
                val draftId = DraftId.parse(input.readBoundedString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("draftId del audit trail inválido")
                val runId = OcrRunId.parse(input.readBoundedString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("runId del audit trail inválido")
                val audit = ParsedInvoiceAudit(
                    draftId = draftId,
                    runId = runId,
                    parserVersion = input.readInt(),
                    contextFingerprint = input.readBoundedString(MAX_HASH_BYTES),
                    confidence = input.readEnum(),
                    fields = List(input.readCount("fields")) { input.readField() },
                    warnings = List(input.readCount("warnings")) { input.readWarning() },
                    blockers = List(input.readCount("blockers")) { input.readBlocker() },
                )
                if (input.available() != 0) throw IOException("Datos sobrantes en el audit trail")
                return audit
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw IOException("Audit trail corrupto", failure)
        }
    }

    fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun DataOutputStream.writeField(field: ParsedInvoiceFieldTrace) {
        writeEnum(field.kind)
        writeNullableInt(field.position)
        writeNullableInt(field.selectedCandidateIndex)
        writeCount(field.candidates.size, "field candidates")
        field.candidates.forEach { candidate -> writeCandidate(candidate) }
        writeEnum(field.confidence)
        writeBoolean(field.requiresReview)
    }

    private fun DataInputStream.readField(): ParsedInvoiceFieldTrace = ParsedInvoiceFieldTrace(
        kind = readEnum(),
        position = readNullableInt(),
        selectedCandidateIndex = readNullableInt(),
        candidates = List(readCount("field candidates")) { readCandidate() },
        confidence = readEnum(),
        requiresReview = readStrictBoolean(),
    )

    private fun DataOutputStream.writeCandidate(candidate: ParsedInvoiceCandidateTrace) {
        writeNullableString(candidate.canonicalValue, MAX_CANONICAL_VALUE_BYTES)
        writeNullableString(candidate.rawText, MAX_TEXT_BYTES)
        writeEnum(candidate.origin)
        writeNullableInt(candidate.confidencePermille)
        writeEnum(candidate.confidence)
        writeBoolean(candidate.requiresReview)
        writeStringList(candidate.warnings, "candidate warnings")
        writeStringList(candidate.reasons, "candidate reasons")
        writeCount(candidate.evidence.size, "candidate evidence")
        candidate.evidence.forEach { evidence -> writeEvidence(evidence) }
    }

    private fun DataInputStream.readCandidate(): ParsedInvoiceCandidateTrace =
        ParsedInvoiceCandidateTrace(
            canonicalValue = readNullableString(MAX_CANONICAL_VALUE_BYTES),
            rawText = readNullableString(MAX_TEXT_BYTES),
            origin = readEnum(),
            confidencePermille = readNullableInt(),
            confidence = readEnum(),
            requiresReview = readStrictBoolean(),
            warnings = readStringList("candidate warnings"),
            reasons = readStringList("candidate reasons"),
            evidence = List(readCount("candidate evidence")) { readEvidence() },
        )

    private fun DataOutputStream.writeWarning(warning: ParsedInvoiceWarning) {
        writeEnum(warning.code)
        writeNullableEnum(warning.field)
        writeNullableInt(warning.position)
        writeNullableString(warning.detail, MAX_DETAIL_BYTES)
        writeBoolean(warning.requiresReview)
        writeCount(warning.evidence.size, "warning evidence")
        warning.evidence.forEach { evidence -> writeEvidence(evidence) }
    }

    private fun DataInputStream.readWarning(): ParsedInvoiceWarning = ParsedInvoiceWarning(
        code = readEnum(),
        field = readNullableEnum<ParsedInvoiceFieldKind>(),
        position = readNullableInt(),
        detail = readNullableString(MAX_DETAIL_BYTES),
        requiresReview = readStrictBoolean(),
        evidence = List(readCount("warning evidence")) { readEvidence() },
    )

    private fun DataOutputStream.writeBlocker(blocker: ParsedInvoiceBlocker) {
        writeEnum(blocker.code)
        writeInt(blocker.linePosition)
        writeCount(blocker.evidence.size, "blocker evidence")
        blocker.evidence.forEach { evidence -> writeEvidence(evidence) }
    }

    private fun DataInputStream.readBlocker(): ParsedInvoiceBlocker = ParsedInvoiceBlocker(
        code = readEnum(),
        linePosition = readInt(),
        evidence = List(readCount("blocker evidence")) { readEvidence() },
    )

    private fun DataOutputStream.writeEvidence(evidence: CandidateEvidence) {
        writeBoundedString(evidence.rawText, MAX_TEXT_BYTES)
        writeBoundedString(evidence.unicodeText, MAX_TEXT_BYTES)
        writeBoundedString(evidence.normalizedText, MAX_TEXT_BYTES)
        writeBoundedString(evidence.comparisonText, MAX_TEXT_BYTES)
        writeCount(evidence.corrections.size, "evidence corrections")
        evidence.corrections.forEach { correction -> writeCorrection(correction) }
        writeBoolean(evidence.sourceImageId != null)
        evidence.sourceImageId?.let { sourceImageId ->
            writeBoundedString(sourceImageId.value, MAX_IDENTIFIER_BYTES)
            writeInt(requireNotNull(evidence.pageIndex))
        }
        writeBoundingBox(evidence.boundingBox)
        writeCount(evidence.cornerPoints.size, "evidence corners")
        evidence.cornerPoints.forEach { point -> writePoint(point) }
        writeNullableInt(evidence.blockPosition)
        writeNullableInt(evidence.linePosition)
        writeNullableInt(evidence.elementPosition)
        writeNullableInt(evidence.clockwiseAngleTenths)
    }

    private fun DataInputStream.readEvidence(): CandidateEvidence {
        val rawText = readBoundedString(MAX_TEXT_BYTES)
        val unicodeText = readBoundedString(MAX_TEXT_BYTES)
        val normalizedText = readBoundedString(MAX_TEXT_BYTES)
        val comparisonText = readBoundedString(MAX_TEXT_BYTES)
        val corrections = List(readCount("evidence corrections")) { readCorrection() }
        val hasSource = readStrictBoolean()
        val sourceImageId = if (hasSource) {
            ImageId.parse(readBoundedString(MAX_IDENTIFIER_BYTES))
                ?: throw IOException("sourceImageId de evidencia inválido")
        } else {
            null
        }
        val pageIndex = if (hasSource) readInt() else null
        return CandidateEvidence(
            rawText = rawText,
            unicodeText = unicodeText,
            normalizedText = normalizedText,
            comparisonText = comparisonText,
            corrections = corrections,
            sourceImageId = sourceImageId,
            pageIndex = pageIndex,
            boundingBox = readBoundingBox(),
            cornerPoints = List(readCount("evidence corners")) { readPoint() },
            blockPosition = readNullableInt(),
            linePosition = readNullableInt(),
            elementPosition = readNullableInt(),
            clockwiseAngleTenths = readNullableInt(),
        )
    }

    private fun DataOutputStream.writeCorrection(correction: AppliedOcrCorrection) {
        writeBoundedString(correction.originalFragment, MAX_TEXT_BYTES)
        writeBoundedString(correction.correctedFragment, MAX_TEXT_BYTES)
        writeEnum(correction.reason)
    }

    private fun DataInputStream.readCorrection(): AppliedOcrCorrection = AppliedOcrCorrection(
        originalFragment = readBoundedString(MAX_TEXT_BYTES),
        correctedFragment = readBoundedString(MAX_TEXT_BYTES),
        reason = readEnum<OcrCorrectionReason>(),
    )

    private fun DataOutputStream.writeBoundingBox(box: InvoiceTextBoundingBox?) {
        writeBoolean(box != null)
        if (box != null) {
            writeInt(box.leftPx)
            writeInt(box.topPx)
            writeInt(box.rightPx)
            writeInt(box.bottomPx)
        }
    }

    private fun DataInputStream.readBoundingBox(): InvoiceTextBoundingBox? {
        if (!readStrictBoolean()) return null
        return InvoiceTextBoundingBox(
            leftPx = readInt(),
            topPx = readInt(),
            rightPx = readInt(),
            bottomPx = readInt(),
        )
    }

    private fun DataOutputStream.writePoint(point: InvoiceTextPoint) {
        writeInt(point.xPx)
        writeInt(point.yPx)
    }

    private fun DataInputStream.readPoint(): InvoiceTextPoint = InvoiceTextPoint(
        xPx = readInt(),
        yPx = readInt(),
    )

    private fun DataOutputStream.writeStringList(values: List<String>, field: String) {
        writeCount(values.size, field)
        values.forEach { value -> writeBoundedString(value, MAX_DETAIL_BYTES) }
    }

    private fun DataInputStream.readStringList(field: String): List<String> =
        List(readCount(field)) { readBoundedString(MAX_DETAIL_BYTES) }

    private fun DataOutputStream.writeNullableString(value: String?, maximumBytes: Int) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value, maximumBytes)
    }

    private fun DataInputStream.readNullableString(maximumBytes: Int): String? =
        if (readStrictBoolean()) readBoundedString(maximumBytes) else null

    private fun DataOutputStream.writeBoundedString(value: String, maximumBytes: Int) {
        val bytes = try {
            val encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also { target -> encoded.get(target) }
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Texto inválido para UTF-8 en el audit trail", failure)
        }
        require(bytes.size <= maximumBytes) {
            "Texto del audit trail excede $maximumBytes bytes UTF-8"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readBoundedString(maximumBytes: Int): String {
        val size = readInt()
        if (size !in 0..maximumBytes) throw IOException("Longitud de texto inválida: $size")
        val bytes = ByteArray(size).also(::readFully)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? = if (readStrictBoolean()) readInt() else null

    private fun DataInputStream.readStrictBoolean(): Boolean = when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IOException("Booleano de audit trail inválido: $value")
    }

    private fun DataOutputStream.writeCount(value: Int, field: String) {
        require(value in 0..MAX_COLLECTION_ITEMS) { "$field fuera de límite: $value" }
        writeInt(value)
    }

    private fun DataInputStream.readCount(field: String): Int = readInt().also { value ->
        if (value !in 0..MAX_COLLECTION_ITEMS) throw IOException("$field fuera de límite: $value")
    }

    private fun DataOutputStream.writeEnum(value: Enum<*>) {
        writeBoundedString(value.name, MAX_ENUM_BYTES)
    }

    private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) {
        writeBoolean(value != null)
        if (value != null) writeEnum(value)
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val name = readBoundedString(MAX_ENUM_BYTES)
        return enumValues<T>().firstOrNull { value -> value.name == name }
            ?: throw IOException("Enum de audit trail desconocido: $name")
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readStrictBoolean()) readEnum<T>() else null

    private const val MAGIC = 0x46535041 // "FSPA"
    private const val MAX_IDENTIFIER_BYTES = 64
    private const val MAX_HASH_BYTES = 64
    private const val MAX_ENUM_BYTES = 128
    private const val MAX_CANONICAL_VALUE_BYTES = 64_000
    private const val MAX_DETAIL_BYTES = 64_000
    private const val MAX_TEXT_BYTES = 1_000_000
    private const val MAX_COLLECTION_ITEMS = 20_000
}

/** Evita que una jerarquía patológica consuma memoria sin límite durante la codificación. */
private class BoundedAuditOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(count < maximumBytes) {
            "El audit trail excede $maximumBytes bytes serializados"
        }
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= maximumBytes - count) {
            "El audit trail excede $maximumBytes bytes serializados"
        }
        super.write(bytes, offset, length)
    }
}
