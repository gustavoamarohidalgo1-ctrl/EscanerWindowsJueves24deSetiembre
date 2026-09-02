package com.facturastock.app.data.local

import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.domain.model.PurchaseNegativeStockPolicy
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Identidades estables: un reinicio o doble toque produce las mismas claves operativas. */
internal object PurchaseVoidIdentity {
    fun movementId(purchaseId: String, originalMovementId: String): String =
        uuid("movement", purchaseId, originalMovementId).toString()

    fun movementKey(purchaseId: String, originalMovementId: String): String =
        "void-purchase:v1:$purchaseId:movement:$originalMovementId"

    fun auditId(purchaseId: String): String = uuid("audit", purchaseId).toString()

    fun outboxId(purchaseId: String): String = uuid("outbox", purchaseId).toString()

    fun outboxKey(purchaseId: String): String = "sync-purchase-void:v1:$purchaseId"

    private fun uuid(kind: String, vararg components: String): UUID {
        val canonicalName = buildString {
            append("facturastock:purchase-void:v1:").append(kind)
            components.forEach { component -> append('\u001f').append(component) }
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalName.toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}

/**
 * Sella la compra, cada entrada original y cada saldo de apertura sin depender de aritmética
 * SQLite aproximada. Los campos se codifican con longitud para que la concatenación sea unívoca.
 */
internal object PurchaseVoidImpactSeal {
    fun create(
        purchase: PurchaseEntity,
        purchaseMovements: List<StockMovementEntity>,
        balances: List<InventoryBalanceEntity>,
        actorId: String,
        actorRole: String,
    ): String {
        val canonical = buildString {
            field("purchase-void-impact:v1")
            field(PurchaseNegativeStockPolicy.ALLOW_WITH_VISIBLE_WARNING.name)
            field(actorId)
            field(actorRole)
            field(purchase.purchaseId)
            field(purchase.businessId)
            field(purchase.status)
            field(purchase.updatedAt.toString())
            field(purchase.postedAt?.toString().orEmpty())
            purchaseMovements.sortedBy(StockMovementEntity::movementId).forEach { movement ->
                field("movement")
                field(movement.movementId)
                field(movement.purchaseLineId.orEmpty())
                field(movement.productId)
                field(movement.locationId)
                field(movement.type)
                field(movement.quantityDelta)
                field(movement.unitCost.orEmpty())
                field(movement.currencyCode)
                field(movement.idempotencyKey)
                field(movement.occurredAt.toString())
                field(movement.createdAt.toString())
            }
            balances.sortedWith(
                compareBy(InventoryBalanceEntity::productId)
                    .thenBy(InventoryBalanceEntity::locationId),
            ).forEach { balance ->
                field("balance")
                field(balance.businessId)
                field(balance.productId)
                field(balance.locationId)
                field(balance.quantityOnHand)
                field(balance.averageUnitCost)
                field(balance.currencyCode)
                field(balance.version.toString())
                field(balance.updatedAt.toString())
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun StringBuilder.field(value: String) {
        append(value.length).append(':').append(value).append('|')
    }
}
