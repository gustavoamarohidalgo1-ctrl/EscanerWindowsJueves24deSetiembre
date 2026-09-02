package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireTimestamps

/**
 * Identidad durable entre una fila de catálogo local y el agregado canónico de la nube.
 *
 * La clave causal de la outbox sigue siendo el UUID local, para ordenar todas las ediciones de
 * la misma fila. [remoteEntityId] es exclusivamente la identidad usada en el wire. Esto evita
 * que un producto/proveedor enlazado por SKU/RUC vuelva a crearse con su UUID local en cada
 * edición posterior.
 */
@Entity(
    tableName = "catalog_sync_links",
    primaryKeys = ["localBusinessId", "entityType", "localEntityId"],
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["localBusinessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["cloudBusinessId", "entityType", "remoteEntityId"],
            unique = true,
        ),
        Index(value = ["localBusinessId"]),
    ],
)
data class CatalogSyncLinkEntity(
    val localBusinessId: String,
    val cloudBusinessId: String,
    val entityType: String,
    val localEntityId: String,
    val remoteEntityId: String,
    val remoteVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        requireCanonicalUuid(localBusinessId, "localBusinessId")
        requireCanonicalUuid(cloudBusinessId, "cloudBusinessId")
        require(entityType == "PRODUCT" || entityType == "SUPPLIER") {
            "entityType de catálogo no soportado: $entityType"
        }
        requireCanonicalUuid(localEntityId, "localEntityId")
        requireCanonicalUuid(remoteEntityId, "remoteEntityId")
        require(remoteVersion >= 1L) { "remoteVersion debe ser positiva" }
        requireTimestamps(createdAt, updatedAt)
    }
}
