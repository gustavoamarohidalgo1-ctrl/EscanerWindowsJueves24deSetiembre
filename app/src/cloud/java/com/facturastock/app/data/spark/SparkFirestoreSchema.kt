package com.facturastock.app.data.spark

import com.facturastock.app.domain.model.canonicalLocationName
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

/**
 * Esquema compartido por las mutaciones directas del plan Spark.
 *
 * Los paths y los recibos conservan la forma usada por Functions. Esto permite activar Blaze
 * después sin mover el ledger. Los documentos Spark añaden `businessId`, `operationId` y las
 * versiones anterior/resultante para que las reglas puedan exigir una única transacción CAS.
 */
internal object SparkFirestoreSchema {
    const val BUSINESSES = "businesses"
    const val MEMBERS = "members"
    const val PRODUCTS = "products"
    const val SALES = "sales"
    const val SALE_LINES = "lines"
    const val DEBTS = "debts"
    const val DEBT_PAYMENTS = "payments"
    const val INVENTORY_BALANCES = "inventoryBalances"
    const val STOCK_MOVEMENTS = "stockMovements"
    const val INVENTORY_CHANGES = "inventorySyncChanges"
    const val SALE_KEYS = "saleSyncKeys"
    const val DEBT_PAYMENT_KEYS = "debtPaymentSyncKeys"
    const val SYNC = "sync"
    const val INVENTORY_METADATA = "inventoryMetadata"

    const val SCHEMA_VERSION = 1L
    const val MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L
    const val MAX_PULL_LIMIT = 200

    fun saleOperationId(saleId: String): String = "sync-sale:v1:$saleId"

    fun debtPaymentOperationId(debtId: String, paymentId: String): String =
        "debt-payment:v1:$debtId:$paymentId"

    fun inventoryBootstrapOperationId(businessId: String): String =
        "inventory-bootstrap:v1:$businessId"

    fun saleReceiptId(businessId: String, operationId: String): String =
        "sale_${sha256("facturastock:sale:v1:$businessId:$operationId").take(RECEIPT_HASH_LENGTH)}"

    fun debtPaymentReceiptId(businessId: String, operationId: String): String =
        "debt_payment_${
            sha256("facturastock:debt-payment:v1:$businessId:$operationId")
                .take(RECEIPT_HASH_LENGTH)
        }"

    fun inventoryBootstrapReceiptId(businessId: String): String =
        "inventory_bootstrap_${
            sha256("facturastock:inventory-bootstrap:v1:$businessId")
                .take(RECEIPT_HASH_LENGTH)
        }"

    fun operationDocumentId(operationId: String): String = sha256(operationId)

    /** JSON compacto con orden de insercion, equivalente a `JSON.stringify` para el wire. */
    fun jsonStringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> JsonPrimitive(value).toString()
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().toString()
        is Float -> value.takeIf { it.isFinite() }?.toString()
            ?: throw IllegalArgumentException("float")
        is Double -> value.takeIf { it.isFinite() }?.toString()
            ?: throw IllegalArgumentException("double")
        is Map<*, *> -> value.entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { (key, child) ->
            require(key is String)
            JsonPrimitive(key).toString() + ":" + jsonStringify(child)
        }
        is Iterable<*> -> value.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { child -> jsonStringify(child) }
        else -> throw IllegalArgumentException("json value")
    }

    /** Misma identidad que `inventoryBalanceRef` en `functions/inventorySync.js`. */
    fun inventoryBalanceDocumentId(productId: String, locationName: String): String {
        val canonical = canonicalLocationName(locationName)
        val jsonArray = listOf("inventory-balance-v1", productId, canonical)
            .joinToString(prefix = "[", postfix = "]", separator = ",") { value ->
                "\"${value.jsonEscaped()}\""
            }
        return sha256(jsonArray)
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /** Hash inequívoco para replays; no depende del orden de iteración de un `Map`. */
    fun lengthPrefixedSha256(parts: Iterable<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update(
                byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                ),
            )
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun String.jsonEscaped(): String = buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private const val RECEIPT_HASH_LENGTH = 32
}
