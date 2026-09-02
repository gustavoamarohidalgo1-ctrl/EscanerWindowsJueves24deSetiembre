package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid

/**
 * Destino cloud inmutable de un negocio local.
 *
 * La fila sobrevive a sign-out y revocaciones: volver a autenticar solo puede recuperar el
 * mismo destino. [boundLegacyOperationCount] deja evidencia durable de cuántas operaciones
 * nunca intentadas se fijaron al crear el vínculo; no contiene UID, correo ni payload.
 */
@Entity(
    tableName = "cloud_business_bindings",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["localBusinessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["cloudBusinessId"], unique = true)],
)
data class CloudBusinessBindingEntity(
    @PrimaryKey val localBusinessId: String,
    val cloudBusinessId: String,
    val createdAt: Long,
    val boundLegacyOperationCount: Int,
) {
    init {
        requireCanonicalUuid(localBusinessId, "localBusinessId")
        requireCanonicalUuid(cloudBusinessId, "cloudBusinessId")
        require(createdAt >= 0L) { "createdAt no puede ser negativo" }
        require(boundLegacyOperationCount >= 0) {
            "boundLegacyOperationCount no puede ser negativo"
        }
    }
}
