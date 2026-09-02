package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.codec.InvoiceHeaderEditCodec
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireSha256

/** Último formulario de revisión persistido para un borrador. */
@Entity(
    tableName = "invoice_header_edits",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceDraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InvoiceHeaderEditEntity(
    @PrimaryKey val draftId: String,
    val revision: Long,
    val payloadCodecVersion: Int,
    val payloadSha256: String,
    val payload: ByteArray,
    val updatedAt: Long,
) {
    init {
        requireCanonicalUuid(draftId, "draftId")
        require(revision >= 0L) { "revision no puede ser negativa: $revision" }
        require(payloadCodecVersion > 0) {
            "payloadCodecVersion debe ser positivo: $payloadCodecVersion"
        }
        requireSha256(payloadSha256, "payloadSha256")
        require(payload.isNotEmpty() && payload.size <= InvoiceHeaderEditCodec.MAX_PAYLOAD_BYTES) {
            "Tamaño de formulario de cabecera inválido: ${payload.size}"
        }
        require(updatedAt >= 0L) { "updatedAt no puede ser negativo: $updatedAt" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is InvoiceHeaderEditEntity &&
            draftId == other.draftId &&
            revision == other.revision &&
            payloadCodecVersion == other.payloadCodecVersion &&
            payloadSha256 == other.payloadSha256 &&
            payload.contentEquals(other.payload) &&
            updatedAt == other.updatedAt

    override fun hashCode(): Int {
        var result = draftId.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + payloadCodecVersion
        result = 31 * result + payloadSha256.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
