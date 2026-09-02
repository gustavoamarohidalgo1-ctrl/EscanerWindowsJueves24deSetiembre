package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.codec.ParsedInvoiceAuditCodec
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireSha256

/** Resultado inmutable del parser ligado exactamente al snapshot OCR que lo originó. */
@Entity(
    tableName = "invoice_parsed_results",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceOcrSnapshotEntity::class,
            parentColumns = ["draftId", "runId"],
            childColumns = ["draftId", "runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["draftId", "runId"], unique = true)],
)
data class ParsedInvoiceResultEntity(
    @PrimaryKey val draftId: String,
    val runId: String,
    val parserVersion: Int,
    val contextFingerprint: String,
    val payloadCodecVersion: Int,
    val payloadSha256: String,
    val payload: ByteArray,
    val parsedAt: Long,
) {
    init {
        requireCanonicalUuid(draftId, "draftId")
        requireCanonicalUuid(runId, "runId")
        require(parserVersion > 0) { "parserVersion debe ser positivo: $parserVersion" }
        requireSha256(contextFingerprint, "contextFingerprint")
        require(payloadCodecVersion > 0) {
            "payloadCodecVersion debe ser positivo: $payloadCodecVersion"
        }
        requireSha256(payloadSha256, "payloadSha256")
        require(payload.isNotEmpty() && payload.size <= ParsedInvoiceAuditCodec.MAX_PAYLOAD_BYTES) {
            "Tamaño de payload parseado inválido: ${payload.size}"
        }
        require(parsedAt >= 0L) { "parsedAt no puede ser negativo: $parsedAt" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ParsedInvoiceResultEntity &&
            draftId == other.draftId &&
            runId == other.runId &&
            parserVersion == other.parserVersion &&
            contextFingerprint == other.contextFingerprint &&
            payloadCodecVersion == other.payloadCodecVersion &&
            payloadSha256 == other.payloadSha256 &&
            payload.contentEquals(other.payload) &&
            parsedAt == other.parsedAt

    override fun hashCode(): Int {
        var result = draftId.hashCode()
        result = 31 * result + runId.hashCode()
        result = 31 * result + parserVersion
        result = 31 * result + contextFingerprint.hashCode()
        result = 31 * result + payloadCodecVersion
        result = 31 * result + payloadSha256.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + parsedAt.hashCode()
        return result
    }
}
