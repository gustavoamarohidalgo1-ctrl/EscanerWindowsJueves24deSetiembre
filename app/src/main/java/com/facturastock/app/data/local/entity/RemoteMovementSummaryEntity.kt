package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireDecimalText
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.StockMovementType

/** Movimiento compacto de una fila de [RemotePurchaseChangeEntity]. */
@Entity(
    tableName = "remote_movement_summaries",
    primaryKeys = ["cloudBusinessId", "seq", "position"],
    foreignKeys = [
        ForeignKey(
            entity = RemotePurchaseChangeEntity::class,
            parentColumns = ["cloudBusinessId", "seq"],
            childColumns = ["cloudBusinessId", "seq"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RemoteMovementSummaryEntity(
    val cloudBusinessId: String,
    val seq: Long,
    val position: Int,
    val productId: String,
    val productName: String?,
    val type: String,
    val quantityDelta: String,
) {
    init {
        requireCanonicalUuid(cloudBusinessId, "cloudBusinessId")
        require(seq in 1..MAX_SAFE_SYNC_SEQUENCE)
        require(position >= 0)
        requireCanonicalUuid(productId, "productId")
        requireTextOrNull(productName, "productName", 200)
        requireEnumName<StockMovementType>(type, "type")
        requireDecimalText(quantityDelta, "quantityDelta", allowZero = false)
    }
}
