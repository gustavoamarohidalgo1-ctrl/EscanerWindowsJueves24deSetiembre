package com.facturastock.app.data.spark

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class SparkPreparedVoid(
    val purchaseId: String,
    val impactHash: String,
    val reason: String,
    val impacts: List<SparkVoidImpact>,
    val canonicalPayloadText: String,
    val payloadHash: String,
)

internal data class SparkVoidImpact(
    val productId: String,
    val locationId: String,
    val reversalQuantity: BigDecimal,
    val currency: String,
)

/** Canonicalizacion byte a byte equivalente a `canonicalizeVoidPayload()` de Functions. */
internal object SparkPurchaseVoidCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun prepare(
        payloadText: String,
        expectedPurchaseId: String?,
        idempotencyKey: String,
    ): SparkPreparedVoid {
        require(payloadText.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES)
        val root = json.parseToJsonElement(payloadText).jsonObject
        require(root.keys == ROOT_KEYS)
        require(root.long(VERSION) == 1L)
        val purchaseId = root.string(PURCHASE_ID).also { require(CANONICAL_UUID.matches(it)) }
        require(purchaseId == expectedPurchaseId)
        require(idempotencyKey == "sync-purchase-void:v1:$purchaseId")
        val impactHash = root.string(IMPACT_HASH).also { require(SHA_256.matches(it)) }
        val actorId = root.string(ACTOR_ID).also { require(CANONICAL_UUID.matches(it)) }
        val role = root.string(ROLE).also { require(it in ACTOR_ROLES) }
        val reason = root.string(REASON).also {
            require(it == it.trim() && it.length in REASON_MIN_LENGTH..REASON_MAX_LENGTH)
        }
        require(root.string(NEGATIVE_STOCK_POLICY) == ALLOW_NEGATIVE_POLICY)
        require(root.string(AVERAGE_UNIT_COST_POLICY) == PRESERVE_AVERAGE_POLICY)
        val declaredNegativeCount = root.long(NEGATIVE_IMPACT_COUNT).also { require(it >= 0L) }
        val canonicalImpacts = root.getValue(IMPACTS).jsonArray.map(::canonicalImpact)
            .sortedWith(compareBy(CanonicalImpact::productId, CanonicalImpact::locationId))
        require(canonicalImpacts.size <= MAX_IMPACTS)
        require(
            canonicalImpacts.map { it.productId to it.locationId }.distinct().size ==
                canonicalImpacts.size,
        )
        require(declaredNegativeCount == canonicalImpacts.count(CanonicalImpact::negative).toLong())
        val canonical = linkedMapOf<String, Any?>(
            VERSION to 1L,
            PURCHASE_ID to purchaseId,
            IMPACT_HASH to impactHash,
            ACTOR_ID to actorId,
            ROLE to role,
            REASON to reason,
            NEGATIVE_STOCK_POLICY to ALLOW_NEGATIVE_POLICY,
            AVERAGE_UNIT_COST_POLICY to PRESERVE_AVERAGE_POLICY,
            NEGATIVE_IMPACT_COUNT to declaredNegativeCount,
            IMPACTS to canonicalImpacts.map(CanonicalImpact::wireMap),
        )
        val canonicalPayloadText = SparkFirestoreSchema.jsonStringify(canonical)
        return SparkPreparedVoid(
            purchaseId = purchaseId,
            impactHash = impactHash,
            reason = reason,
            impacts = canonicalImpacts.map { impact ->
                SparkVoidImpact(
                    productId = impact.productId,
                    locationId = impact.locationId,
                    reversalQuantity = BigDecimal(impact.reversalQuantity),
                    currency = impact.currency,
                )
            },
            canonicalPayloadText = canonicalPayloadText,
            payloadHash = SparkFirestoreSchema.sha256(canonicalPayloadText),
        )
    }

    /**
     * Replica `validateVoidImpactSemantics()` de Functions con aritmetica decimal exacta.
     *
     * La compra remota es la fuente causal: cada destino PURCHASE debe aparecer exactamente una
     * vez en el payload y su cantidad de reversa debe ser el opuesto de la suma respaldada. Los
     * fallos estructurales de la compra son corrupcion remota; las diferencias del payload son una
     * solicitud invalida y se rechazan antes de cualquier escritura.
     */
    fun validatePurchaseSemantics(
        purchase: Map<String, Any?>,
        payload: SparkPreparedVoid,
    ) {
        val movements = purchase[MOVEMENTS] as? List<*>
            ?: throw SparkMutationFailure.CorruptRemoteData
        val expectedSign = if (purchase[DOCUMENT_TYPE] == CREDIT_NOTE) -1 else 1
        val quantitiesByKey = linkedMapOf<Pair<String, String>, BigDecimal>()
        movements.forEach { raw ->
            val movement = raw as? Map<*, *> ?: return@forEach
            if (movement[TYPE] != PURCHASE) return@forEach
            val productId = movement[PRODUCT_ID] as? String
                ?: throw SparkMutationFailure.CorruptRemoteData
            val locationId = movement[LOCATION_ID] as? String
                ?: throw SparkMutationFailure.CorruptRemoteData
            val quantityText = movement[QUANTITY_DELTA] as? String
                ?: throw SparkMutationFailure.CorruptRemoteData
            if (
                !CANONICAL_UUID.matches(productId) || !CANONICAL_UUID.matches(locationId) ||
                !DECIMAL.matches(quantityText)
            ) {
                throw SparkMutationFailure.CorruptRemoteData
            }
            val quantity = BigDecimal(quantityText)
            if (quantity.signum() != expectedSign) {
                throw SparkMutationFailure.CorruptRemoteData
            }
            val key = productId to locationId
            quantitiesByKey[key] = quantitiesByKey.getOrDefault(key, BigDecimal.ZERO) + quantity
        }
        if (quantitiesByKey.isEmpty()) throw SparkMutationFailure.CorruptRemoteData
        require(payload.impacts.size == quantitiesByKey.size) { VOID_IMPACT_SET_MISMATCH }
        payload.impacts.forEach { impact ->
            val original = quantitiesByKey[impact.productId to impact.locationId]
                ?: throw IllegalArgumentException(VOID_IMPACT_SET_MISMATCH)
            require((original + impact.reversalQuantity).compareTo(BigDecimal.ZERO) == 0) {
                VOID_IMPACT_REVERSAL_MISMATCH
            }
        }
    }

    private fun canonicalImpact(raw: kotlinx.serialization.json.JsonElement): CanonicalImpact {
        val impact = raw.jsonObject
        require(impact.keys == IMPACT_KEYS)
        val productId = impact.string(PRODUCT_ID).also { require(CANONICAL_UUID.matches(it)) }
        val locationId = impact.string(LOCATION_ID).also { require(CANONICAL_UUID.matches(it)) }
        val currentQuantity = impact.decimalText(CURRENT_QUANTITY)
        val reversalQuantity = impact.decimalText(REVERSAL_QUANTITY)
        val resultingQuantity = impact.decimalText(RESULTING_QUANTITY)
        val averageUnitCost = impact.decimalText(CURRENT_AVERAGE_UNIT_COST)
        require(reversalQuantity.signum() != 0)
        require((currentQuantity + reversalQuantity).compareTo(resultingQuantity) == 0)
        require(averageUnitCost.signum() >= 0)
        val currency = impact.string(CURRENCY).also { require(CURRENCY_CODE.matches(it)) }
        val balanceVersion = impact.long(BALANCE_VERSION).also { require(it >= 0L) }
        val negative = impact.boolean(NEGATIVE)
        require(negative == (resultingQuantity.signum() < 0))
        return CanonicalImpact(
            productId = productId,
            locationId = locationId,
            currentQuantity = impact.string(CURRENT_QUANTITY),
            reversalQuantity = impact.string(REVERSAL_QUANTITY),
            resultingQuantity = impact.string(RESULTING_QUANTITY),
            currentAverageUnitCost = impact.string(CURRENT_AVERAGE_UNIT_COST),
            currency = currency,
            balanceVersion = balanceVersion,
            negative = negative,
        )
    }

    private fun JsonObject.string(key: String): String {
        val primitive = getValue(key).jsonPrimitive
        require(primitive.isString)
        return primitive.content
    }

    private fun JsonObject.long(key: String): Long {
        val primitive = getValue(key).jsonPrimitive
        require(!primitive.isString)
        return primitive.content.toLong()
    }

    private fun JsonObject.boolean(key: String): Boolean {
        val primitive = getValue(key).jsonPrimitive
        require(!primitive.isString && primitive.content in setOf("true", "false"))
        return primitive.content.toBooleanStrict()
    }

    private fun JsonObject.decimalText(key: String): BigDecimal {
        val value = string(key)
        require(DECIMAL.matches(value))
        return BigDecimal(value)
    }

    private data class CanonicalImpact(
        val productId: String,
        val locationId: String,
        val currentQuantity: String,
        val reversalQuantity: String,
        val resultingQuantity: String,
        val currentAverageUnitCost: String,
        val currency: String,
        val balanceVersion: Long,
        val negative: Boolean,
    ) {
        fun wireMap(): Map<String, Any?> = linkedMapOf(
            PRODUCT_ID to productId,
            LOCATION_ID to locationId,
            CURRENT_QUANTITY to currentQuantity,
            REVERSAL_QUANTITY to reversalQuantity,
            RESULTING_QUANTITY to resultingQuantity,
            CURRENT_AVERAGE_UNIT_COST to currentAverageUnitCost,
            CURRENCY to currency,
            BALANCE_VERSION to balanceVersion,
            NEGATIVE to negative,
        )
    }

    private val ROOT_KEYS = setOf(
        VERSION,
        PURCHASE_ID,
        IMPACT_HASH,
        ACTOR_ID,
        ROLE,
        REASON,
        NEGATIVE_STOCK_POLICY,
        AVERAGE_UNIT_COST_POLICY,
        NEGATIVE_IMPACT_COUNT,
        IMPACTS,
    )
    private val IMPACT_KEYS = setOf(
        PRODUCT_ID,
        LOCATION_ID,
        CURRENT_QUANTITY,
        REVERSAL_QUANTITY,
        RESULTING_QUANTITY,
        CURRENT_AVERAGE_UNIT_COST,
        CURRENCY,
        BALANCE_VERSION,
        NEGATIVE,
    )
    private val CANONICAL_UUID =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val SHA_256 = Regex("^[0-9a-f]{64}$")
    private val CURRENCY_CODE = Regex("^[A-Z]{3}$")
    private val DECIMAL = Regex("^-?[0-9]{1,21}(\\.[0-9]{1,18})?$")
    private val ACTOR_ROLES = setOf("OWNER", "MANAGER")
    private const val MAX_DOCUMENT_BYTES = 1_000_000
    private const val MAX_IMPACTS = 100
    private const val REASON_MIN_LENGTH = 10
    private const val REASON_MAX_LENGTH = 500
    private const val ALLOW_NEGATIVE_POLICY = "ALLOW_WITH_VISIBLE_WARNING"
    private const val PRESERVE_AVERAGE_POLICY = "PRESERVE_CURRENT"
    private const val VERSION = "version"
    private const val PURCHASE_ID = "purchaseId"
    private const val IMPACT_HASH = "impactHash"
    private const val ACTOR_ID = "actorId"
    private const val ROLE = "role"
    private const val REASON = "reason"
    private const val NEGATIVE_STOCK_POLICY = "negativeStockPolicy"
    private const val AVERAGE_UNIT_COST_POLICY = "averageUnitCostPolicy"
    private const val NEGATIVE_IMPACT_COUNT = "negativeImpactCount"
    private const val IMPACTS = "impacts"
    private const val PRODUCT_ID = "productId"
    private const val LOCATION_ID = "locationId"
    private const val CURRENT_QUANTITY = "currentQuantity"
    private const val REVERSAL_QUANTITY = "reversalQuantity"
    private const val RESULTING_QUANTITY = "resultingQuantity"
    private const val CURRENT_AVERAGE_UNIT_COST = "currentAverageUnitCost"
    private const val CURRENCY = "currency"
    private const val BALANCE_VERSION = "balanceVersion"
    private const val NEGATIVE = "negative"
    private const val MOVEMENTS = "movements"
    private const val DOCUMENT_TYPE = "documentType"
    private const val CREDIT_NOTE = "CREDIT_NOTE"
    private const val TYPE = "type"
    private const val PURCHASE = "PURCHASE"
    private const val QUANTITY_DELTA = "quantityDelta"
    private const val VOID_IMPACT_SET_MISMATCH = "VOID_IMPACT_SET_MISMATCH"
    private const val VOID_IMPACT_REVERSAL_MISMATCH = "VOID_IMPACT_REVERSAL_MISMATCH"
}
