package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireSha256

/**
 * Instantánea congelada de compra preparada (READY_TO_POST). Una por borrador; se borra al
 * reabrir la edición. El contenido completo vive en [payload] con su versión de codec y hash;
 * [logicalHash] permite la idempotencia por contenido sin decodificar.
 */
@Entity(
    tableName = "prepared_purchases",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceDraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PreparedPurchaseEntity(
    @PrimaryKey val draftId: String,
    val logicalHash: String,
    val payloadCodecVersion: Int,
    val payloadSha256: String,
    val payload: ByteArray,
    val preparedAt: Long,
) {
    init {
        requireCanonicalUuid(draftId, "draftId")
        requireSha256(logicalHash, "logicalHash")
        require(payloadCodecVersion > 0) {
            "payloadCodecVersion debe ser positivo: $payloadCodecVersion"
        }
        requireSha256(payloadSha256, "payloadSha256")
        require(payload.isNotEmpty() && payload.size <= PreparedPurchaseCodec.MAX_PAYLOAD_BYTES) {
            "Tamaño de compra preparada inválido: ${payload.size}"
        }
        require(preparedAt >= 0L) { "preparedAt no puede ser negativo: $preparedAt" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PreparedPurchaseEntity &&
            draftId == other.draftId &&
            logicalHash == other.logicalHash &&
            payloadCodecVersion == other.payloadCodecVersion &&
            payloadSha256 == other.payloadSha256 &&
            payload.contentEquals(other.payload) &&
            preparedAt == other.preparedAt

    override fun hashCode(): Int {
        var result = draftId.hashCode()
        result = 31 * result + logicalHash.hashCode()
        result = 31 * result + payloadCodecVersion
        result = 31 * result + payloadSha256.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + preparedAt.hashCode()
        return result
    }
}
