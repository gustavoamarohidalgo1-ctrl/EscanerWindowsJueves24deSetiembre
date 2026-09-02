package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE

/** Cursores durables del espejo remoto. Nunca contiene tokens ni contenido de imágenes. */
@Entity(tableName = "remote_sync_states")
data class RemoteSyncStateEntity(
    @PrimaryKey val cloudBusinessId: String,
    val purchaseSeq: Long = 0,
    /** Millis visibles o recibo v1 etiquetado; solo el repositorio interpreta completitud. */
    val purchasePulledAt: Long? = null,
    val catalogSeq: Long = 0,
    val catalogPulledAt: Long? = null,
    /** Stream append-only de toda mutacion de saldo compartido. */
    val inventorySeq: Long = 0,
    val inventoryPulledAt: Long? = null,
) {
    init {
        requireCanonicalUuid(cloudBusinessId, "cloudBusinessId")
        require(purchaseSeq in 0..MAX_SAFE_SYNC_SEQUENCE)
        require(catalogSeq in 0..MAX_SAFE_SYNC_SEQUENCE)
        require(inventorySeq in 0..MAX_SAFE_SYNC_SEQUENCE)
        // Una consulta válida puede devolver una página vacía con cursor 0. El valor también
        // admite el recibo positivo versionado de una página terminal.
        require(purchasePulledAt == null || purchasePulledAt >= 0L)
        require(catalogPulledAt == null || catalogPulledAt >= 0L)
        require(inventoryPulledAt == null || inventoryPulledAt >= 0L)
    }
}
