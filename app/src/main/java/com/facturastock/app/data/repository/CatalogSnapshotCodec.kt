package com.facturastock.app.data.repository

import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.ProductSalePricePolicy
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Forma semántica de una unidad; los UUID locales nunca forman parte del snapshot cloud. */
internal data class CatalogUnitSnapshot(
    val code: String,
    val name: String,
    val symbol: String?,
    val status: String,
)

/** Forma semántica de un almacén; su identidad cross-device es el nombre canónico. */
internal data class CatalogLocationSnapshot(
    val name: String,
    val status: String,
)

internal data class CatalogProductSnapshot(
    val name: String,
    val sku: String?,
    val barcode: String?,
    val salePriceMinorUnits: Long? = null,
    val salePriceCurrencyCode: String? = null,
    val inventoryUnit: CatalogUnitSnapshot,
    val purchaseUnit: CatalogUnitSnapshot?,
    val purchaseFactor: String?,
    val location: CatalogLocationSnapshot?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** `false` identifica un snapshot v1 y permite preservar el precio local al aplicarlo. */
    val includesSalePrice: Boolean = true,
)

internal data class CatalogSupplierSnapshot(
    val legalName: String,
    val ruc: String?,
    val tradeName: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Codec cerrado y determinista compartido por push y pull. El decoder exige la forma canónica
 * byte a byte después de parsear: claves extra, números ambiguos, espacios o claves duplicadas
 * nunca se aplican silenciosamente a Room.
 */
internal object CatalogSnapshotCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun encode(snapshot: CatalogProductSnapshot): String = buildString {
        append('{')
        append("\"name\":\"").append(snapshot.name.jsonEscapedForPayload()).append('"')
        appendNullableString("sku", snapshot.sku)
        appendNullableString("barcode", snapshot.barcode)
        appendNullableLong("salePriceMinorUnits", snapshot.salePriceMinorUnits)
        appendNullableString("salePriceCurrencyCode", snapshot.salePriceCurrencyCode)
        append(",\"inventoryUnit\":")
        appendUnit(snapshot.inventoryUnit)
        append(",\"purchaseUnit\":")
        snapshot.purchaseUnit?.let { appendUnit(it) } ?: append("null")
        appendNullableString("purchaseFactor", snapshot.purchaseFactor)
        append(",\"location\":")
        snapshot.location?.let { appendLocation(it) } ?: append("null")
        append(",\"status\":\"").append(snapshot.status).append('"')
        append(",\"createdAt\":").append(snapshot.createdAt)
        append(",\"updatedAt\":").append(snapshot.updatedAt)
        append('}')
    }

    fun encode(snapshot: CatalogSupplierSnapshot): String = buildString {
        append('{')
        append("\"legalName\":\"")
            .append(snapshot.legalName.jsonEscapedForPayload())
            .append('"')
        appendNullableString("ruc", snapshot.ruc)
        appendNullableString("tradeName", snapshot.tradeName)
        append(",\"status\":\"").append(snapshot.status).append('"')
        append(",\"createdAt\":").append(snapshot.createdAt)
        append(",\"updatedAt\":").append(snapshot.updatedAt)
        append('}')
    }

    fun decodeProduct(payload: String): CatalogProductSnapshot? = try {
        val parsed = json.parseToJsonElement(payload).jsonObject
        val includesSalePrice = when (parsed.keys) {
            PRODUCT_KEYS -> true
            LEGACY_PRODUCT_KEYS -> false
            else -> return null
        }
        val snapshot = parsed.run {
            CatalogProductSnapshot(
                name = requiredString("name"),
                sku = nullableString("sku"),
                barcode = nullableString("barcode"),
                salePriceMinorUnits = if (includesSalePrice) nullableLong("salePriceMinorUnits") else null,
                salePriceCurrencyCode = if (includesSalePrice) {
                    nullableString("salePriceCurrencyCode")
                } else {
                    null
                },
                inventoryUnit = requiredObject("inventoryUnit").decodeUnit(),
                purchaseUnit = nullableObject("purchaseUnit")?.decodeUnit(),
                purchaseFactor = nullableString("purchaseFactor"),
                location = nullableObject("location")?.decodeLocation(),
                status = requiredStatus("status"),
                createdAt = requiredTimestamp("createdAt"),
                updatedAt = requiredTimestamp("updatedAt"),
                includesSalePrice = includesSalePrice,
            ).also(::validateProduct)
        }
        val canonical = if (includesSalePrice) encode(snapshot) else encodeLegacyProduct(snapshot)
        snapshot.takeIf { canonical == payload }
    } catch (_: IllegalArgumentException) {
        null
    }

    fun decodeSupplier(payload: String): CatalogSupplierSnapshot? = decodeCanonical(payload) {
        requireExactKeys(SUPPLIER_KEYS)
        CatalogSupplierSnapshot(
            legalName = requiredString("legalName"),
            ruc = nullableString("ruc"),
            tradeName = nullableString("tradeName"),
            status = requiredStatus("status"),
            createdAt = requiredTimestamp("createdAt"),
            updatedAt = requiredTimestamp("updatedAt"),
        ).also(::validateSupplier)
    }

    private inline fun <reified T> decodeCanonical(
        payload: String,
        crossinline parse: JsonObject.() -> T,
    ): T? = try {
        val parsed = json.parseToJsonElement(payload).jsonObject.parse()
        val encoded = when (parsed) {
            is CatalogProductSnapshot -> encode(parsed)
            is CatalogSupplierSnapshot -> encode(parsed)
            else -> return null
        }
        parsed.takeIf { encoded == payload }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun validateProduct(snapshot: CatalogProductSnapshot) {
        requireText(snapshot.name, 200)
        requireNullableText(snapshot.sku, 64)
        requireNullableText(snapshot.barcode, 128)
        require(snapshot.sku == CatalogCanonicalizer.sku(snapshot.sku))
        require(snapshot.barcode == CatalogCanonicalizer.barcode(snapshot.barcode))
        require((snapshot.salePriceMinorUnits == null) == (snapshot.salePriceCurrencyCode == null))
        snapshot.salePriceMinorUnits?.let { price ->
            require(price in 1L..ProductSalePricePolicy.MAX_MINOR_UNITS)
        }
        snapshot.salePriceCurrencyCode?.let { currency ->
            require(CURRENCY_CODE.matches(currency) && CurrencyCode.of(currency).value == currency)
        }
        require((snapshot.purchaseUnit == null) == (snapshot.purchaseFactor == null))
        snapshot.purchaseFactor?.let { factor ->
            require(PLAIN_DECIMAL.matches(factor) && ExactDecimalPolicy.supportsInput(factor))
            val decimal = BigDecimal(factor)
            require(factor == decimal.toPlainString())
            require(decimal.signum() > 0 && ExactDecimalPolicy.supportsValue(decimal))
        }
        require(snapshot.createdAt <= snapshot.updatedAt)
    }

    private fun validateSupplier(snapshot: CatalogSupplierSnapshot) {
        requireText(snapshot.legalName, 200)
        requireNullableText(snapshot.ruc, 11)
        require(snapshot.ruc == CatalogCanonicalizer.ruc(snapshot.ruc))
        require(snapshot.ruc == null || RUC.matches(snapshot.ruc))
        requireNullableText(snapshot.tradeName, 200)
        require(snapshot.createdAt <= snapshot.updatedAt)
    }

    private fun JsonObject.decodeUnit(): CatalogUnitSnapshot {
        requireExactKeys(UNIT_KEYS)
        return CatalogUnitSnapshot(
            code = requiredString("code").also { code ->
                require(code == CatalogCanonicalizer.unitCode(code) && UNIT_CODE.matches(code))
            },
            name = requiredString("name").also { requireText(it, 100) },
            symbol = nullableString("symbol").also { requireNullableText(it, 16) },
            status = requiredStatus("status"),
        )
    }

    private fun JsonObject.decodeLocation(): CatalogLocationSnapshot {
        requireExactKeys(LOCATION_KEYS)
        return CatalogLocationSnapshot(
            name = requiredString("name").also { requireText(it, 100) },
            status = requiredStatus("status"),
        )
    }

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        require(keys == expected)
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = requireNotNull(this[key])
        require(value is JsonPrimitive && value.isString)
        return value.content
    }

    private fun JsonObject.nullableString(key: String): String? {
        val value = requireNotNull(this[key])
        if (value === JsonNull) return null
        require(value is JsonPrimitive && value.isString)
        return value.content
    }

    private fun JsonObject.requiredObject(key: String): JsonObject =
        requireNotNull(this[key]) as? JsonObject ?: throw IllegalArgumentException(key)

    private fun JsonObject.nullableObject(key: String): JsonObject? {
        val value = requireNotNull(this[key])
        return if (value === JsonNull) null else value as? JsonObject
            ?: throw IllegalArgumentException(key)
    }

    private fun JsonObject.requiredStatus(key: String): String = requiredString(key).also {
        require(it == CatalogStatus.ACTIVE.name || it == CatalogStatus.ARCHIVED.name)
    }

    private fun JsonObject.requiredTimestamp(key: String): Long {
        val primitive = requireNotNull(this[key]).jsonPrimitive
        require(!primitive.isString && CANONICAL_LONG.matches(primitive.content))
        return requireNotNull(primitive.content.toLongOrNull())
    }

    private fun JsonObject.nullableLong(key: String): Long? {
        val value = requireNotNull(this[key])
        if (value === JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException(key)
        require(!primitive.isString && CANONICAL_LONG.matches(primitive.content))
        return requireNotNull(primitive.content.toLongOrNull())
    }

    private fun StringBuilder.appendUnit(unit: CatalogUnitSnapshot) {
        append("{\"code\":\"").append(unit.code).append('"')
        append(",\"name\":\"").append(unit.name.jsonEscapedForPayload()).append('"')
        appendNullableString("symbol", unit.symbol)
        append(",\"status\":\"").append(unit.status).append("\"}")
    }

    private fun StringBuilder.appendLocation(location: CatalogLocationSnapshot) {
        append("{\"name\":\"").append(location.name.jsonEscapedForPayload()).append('"')
        append(",\"status\":\"").append(location.status).append("\"}")
    }

    private fun StringBuilder.appendNullableString(key: String, value: String?) {
        append(",\"").append(key).append("\":")
        if (value == null) {
            append("null")
        } else {
            append('"').append(value.jsonEscapedForPayload()).append('"')
        }
    }

    private fun StringBuilder.appendNullableLong(key: String, value: Long?) {
        append(",\"").append(key).append("\":")
        append(value ?: "null")
    }

    private fun encodeLegacyProduct(snapshot: CatalogProductSnapshot): String = buildString {
        append('{')
        append("\"name\":\"").append(snapshot.name.jsonEscapedForPayload()).append('"')
        appendNullableString("sku", snapshot.sku)
        appendNullableString("barcode", snapshot.barcode)
        append(",\"inventoryUnit\":")
        appendUnit(snapshot.inventoryUnit)
        append(",\"purchaseUnit\":")
        snapshot.purchaseUnit?.let { appendUnit(it) } ?: append("null")
        appendNullableString("purchaseFactor", snapshot.purchaseFactor)
        append(",\"location\":")
        snapshot.location?.let { appendLocation(it) } ?: append("null")
        append(",\"status\":\"").append(snapshot.status).append('"')
        append(",\"createdAt\":").append(snapshot.createdAt)
        append(",\"updatedAt\":").append(snapshot.updatedAt)
        append('}')
    }

    private fun requireText(value: String, maxLength: Int) {
        require(value.isNotBlank() && value.length <= maxLength)
    }

    private fun requireNullableText(value: String?, maxLength: Int) {
        value?.let { requireText(it, maxLength) }
    }

    private val PRODUCT_KEYS = setOf(
        "name",
        "sku",
        "barcode",
        "salePriceMinorUnits",
        "salePriceCurrencyCode",
        "inventoryUnit",
        "purchaseUnit",
        "purchaseFactor",
        "location",
        "status",
        "createdAt",
        "updatedAt",
    )
    private val LEGACY_PRODUCT_KEYS = PRODUCT_KEYS -
        setOf("salePriceMinorUnits", "salePriceCurrencyCode")
    private val SUPPLIER_KEYS = setOf(
        "legalName",
        "ruc",
        "tradeName",
        "status",
        "createdAt",
        "updatedAt",
    )
    private val UNIT_KEYS = setOf("code", "name", "symbol", "status")
    private val LOCATION_KEYS = setOf("name", "status")
    private val PLAIN_DECIMAL = Regex("^(0|[1-9]\\d*)(\\.\\d+)?$")
    private val RUC = Regex("^\\d{11}$")
    private val UNIT_CODE = Regex("^[A-Z0-9]{1,16}$")
    private val CANONICAL_LONG = Regex("0|[1-9]\\d*")
    private val CURRENCY_CODE = Regex("[A-Z]{3}")
}
