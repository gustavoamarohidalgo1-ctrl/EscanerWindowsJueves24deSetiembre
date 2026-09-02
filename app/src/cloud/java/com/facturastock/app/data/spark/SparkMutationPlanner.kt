package com.facturastock.app.data.spark

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SharedDebtPayment
import com.facturastock.app.domain.model.SharedDebtSnapshot
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.RemoteDebtPaymentDocument
import java.math.BigDecimal
import java.time.Instant

internal sealed class SparkMutationFailure(message: String) : RuntimeException(message) {
    data class InsufficientStock(
        val target: SparkSaleInventoryTarget,
        val requested: Quantity,
        val available: String,
    ) : SparkMutationFailure("INSUFFICIENT_STOCK")

    data object InventoryMigrationRequired : SparkMutationFailure("INVENTORY_MIGRATION_REQUIRED")
    data object Stale : SparkMutationFailure("STALE")
    data object Rejected : SparkMutationFailure("REJECTED")
    data object CorruptRemoteData : SparkMutationFailure("CORRUPT_REMOTE_DATA")
}

internal data class SparkStoredInventoryBalance(
    val documentId: String,
    val businessId: BusinessId,
    val productId: String,
    val locationName: String,
    val canonicalLocationName: String,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: BigDecimal,
    val currency: CurrencyCode,
    val version: Long,
    val lastSeq: Long,
    val updatedAtMillis: Long,
)

/** Codec estricto reutilizado por compras, anulaciones y ventas Spark. */
internal object SparkInventoryBalanceCodec {
    fun parse(
        documentId: String,
        raw: Map<String, Any?>,
        expectedBusinessId: BusinessId? = null,
        expectedProductId: String? = null,
        expectedCanonicalLocationName: String? = null,
    ): SparkStoredInventoryBalance = try {
        if (raw.long("schemaVersion") != SparkFirestoreSchema.SCHEMA_VERSION) invalid()
        val businessId = BusinessId.parse(raw.string("businessId")) ?: invalid()
        val productId = raw.string("productId")
        val locationName = raw.string("locationName")
        val canonicalLocation = raw.string("canonicalLocationName")
        if (
            expectedBusinessId?.let { it != businessId } == true ||
            expectedProductId?.let { it != productId } == true ||
            expectedCanonicalLocationName?.let { it != canonicalLocation } == true ||
            canonicalLocationName(locationName) != canonicalLocation ||
            documentId != SparkFirestoreSchema.inventoryBalanceDocumentId(productId, locationName)
        ) {
            invalid()
        }
        SparkStoredInventoryBalance(
            documentId = documentId,
            businessId = businessId,
            productId = productId,
            locationName = locationName,
            canonicalLocationName = canonicalLocation,
            quantityOnHand = raw.decimal("quantityOnHand", nonNegative = false),
            averageUnitCost = raw.decimal("averageUnitCost", nonNegative = true),
            currency = CurrencyCode.of(raw.string("currency")),
            version = raw.long("version").also { if (it < 0L) invalid() },
            lastSeq = raw.long("lastSeq").also { if (it < 0L) invalid() },
            updatedAtMillis = raw.long("updatedAtMillis").also { if (it < 0L) invalid() },
        )
    } catch (failure: SparkMutationFailure) {
        throw failure
    } catch (_: Exception) {
        throw SparkMutationFailure.CorruptRemoteData
    }

    fun record(
        businessId: BusinessId,
        productId: String,
        locationName: String,
        quantityOnHand: BigDecimal,
        averageUnitCost: BigDecimal,
        currency: CurrencyCode,
        version: Long,
        lastSeq: Long,
        updatedAtMillis: Long,
        lastSourceLocationId: String?,
        operationId: String,
        expectedVersion: Long?,
        syncedAt: Any,
    ): Map<String, Any?> {
        val persistedQuantity = quantityOnHand.normalizedInventoryDecimal()
        val persistedAverage = averageUnitCost.normalizedInventoryDecimal()
        require(version >= 0L && lastSeq in 1..SparkFirestoreSchema.MAX_SAFE_SEQUENCE)
        require(updatedAtMillis >= 0L && persistedAverage.signum() >= 0)
        require(InventoryCostingDecimalPolicy.supportsPersisted(persistedQuantity.abs()))
        require(InventoryCostingDecimalPolicy.supportsPersisted(persistedAverage))
        require(operationId.isNotBlank())
        require(expectedVersion == null || version == expectedVersion + 1L)
        return linkedMapOf(
            "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
            "businessId" to businessId.value,
            "productId" to productId,
            "locationName" to locationName,
            "canonicalLocationName" to canonicalLocationName(locationName),
            "quantityOnHand" to persistedQuantity.canonicalDecimal(),
            "averageUnitCost" to persistedAverage.canonicalDecimal(),
            "currency" to currency.value,
            "version" to version,
            "lastSeq" to lastSeq,
            "updatedAtMillis" to updatedAtMillis,
            "lastSourceLocationId" to lastSourceLocationId,
            "lastOperationId" to operationId,
            "expectedVersion" to expectedVersion,
            "syncedAt" to syncedAt,
        )
    }

    fun projection(
        productId: String,
        locationName: String,
        quantityOnHand: BigDecimal,
        averageUnitCost: BigDecimal,
        currency: CurrencyCode,
        version: Long,
        updatedAtMillis: Long,
        seq: Long,
    ): Map<String, Any?> = linkedMapOf(
        "productId" to productId,
        "locationName" to locationName,
        "quantityOnHand" to quantityOnHand.canonicalDecimal(),
        "averageUnitCost" to averageUnitCost.canonicalDecimal(),
        "currency" to currency.value,
        "version" to version,
        "updatedAtMillis" to updatedAtMillis,
        "seq" to seq,
    )
}

internal data class SparkPlannedInventoryBalance(
    val target: SparkSaleInventoryTarget,
    val expectedVersion: Long,
    val resultingVersion: Long,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currency: CurrencyCode,
    val updatedAtMillis: Long,
    val seq: Long,
) {
    fun projection(): Map<String, Any?> = linkedMapOf(
        "productId" to target.remoteProductId,
        "locationName" to target.locationName,
        "quantityOnHand" to quantityOnHand,
        "averageUnitCost" to averageUnitCost,
        "currency" to currency.value,
        "version" to resultingVersion,
        "updatedAtMillis" to updatedAtMillis,
        "seq" to seq,
    )
}

internal data class SparkSaleMutationPlan(
    val seq: Long,
    val postedAt: Instant,
    val balances: List<SparkPlannedInventoryBalance>,
)

internal object SparkSaleMutationPlanner {
    fun plan(
        outbound: SparkOutboundSale,
        metadataSeq: Long,
        rawBalancesByDocumentId: Map<String, Map<String, Any?>>, 
        now: Instant,
    ): SparkSaleMutationPlan {
        if (metadataSeq !in 0 until SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
            throw SparkMutationFailure.CorruptRemoteData
        }
        if (rawBalancesByDocumentId.keys != outbound.targets.mapTo(linkedSetOf()) { it.balanceDocumentId }) {
            throw SparkMutationFailure.InventoryMigrationRequired
        }
        val stored = outbound.targets.map { target ->
            SparkInventoryBalanceCodec.parse(
                documentId = target.balanceDocumentId,
                raw = rawBalancesByDocumentId[target.balanceDocumentId]
                    ?: throw SparkMutationFailure.InventoryMigrationRequired,
                expectedBusinessId = outbound.cloudBusinessId,
                expectedProductId = target.remoteProductId,
                expectedCanonicalLocationName = target.canonicalLocationName,
            )
        }
        val effectiveMillis = maxOf(
            now.toEpochMilli(),
            outbound.localDocument.postedAt.toEpochMilli(),
            outbound.localDocument.updatedAt.toEpochMilli(),
            stored.maxOfOrNull(SparkStoredInventoryBalance::updatedAtMillis) ?: 0L,
        )
        val nextSeq = metadataSeq + 1L
        val planned = stored.zip(outbound.targets).map { (balance, target) ->
            if (balance.currency != outbound.localDocument.currency) {
                throw SparkMutationFailure.Rejected
            }
            if (balance.quantityOnHand.signum() < 0) {
                throw SparkMutationFailure.CorruptRemoteData
            }
            val requested = target.line.quantity
            val resulting = balance.quantityOnHand.subtract(requested.value)
            if (resulting.signum() < 0) {
                throw SparkMutationFailure.InsufficientStock(
                    target = target,
                    requested = requested,
                    available = balance.quantityOnHand.canonicalDecimal(),
                )
            }
            if (balance.version >= SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
                throw SparkMutationFailure.CorruptRemoteData
            }
            SparkPlannedInventoryBalance(
                target = target,
                expectedVersion = balance.version,
                resultingVersion = balance.version + 1L,
                quantityOnHand = resulting.canonicalDecimal(),
                averageUnitCost = balance.averageUnitCost.canonicalDecimal(),
                currency = balance.currency,
                updatedAtMillis = effectiveMillis,
                seq = nextSeq,
            )
        }
        return SparkSaleMutationPlan(
            seq = nextSeq,
            postedAt = Instant.ofEpochMilli(effectiveMillis),
            balances = planned,
        )
    }

}

internal data class SparkDebtPaymentMutationPlan(
    val seq: Long,
    val debt: SharedDebtSnapshot,
    val payment: SharedDebtPayment,
)

internal object SparkDebtPaymentMutationPlanner {
    fun plan(
        cloudBusinessId: BusinessId,
        document: RemoteDebtPaymentDocument,
        rawDebt: Map<String, Any?>,
        metadataSeq: Long,
        now: Instant,
    ): SparkDebtPaymentMutationPlan {
        if (metadataSeq !in 0 until SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
            throw SparkMutationFailure.CorruptRemoteData
        }
        val current = parseDebt(rawDebt, cloudBusinessId, document.debtId)
        if (current.version >= SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
            throw SparkMutationFailure.CorruptRemoteData
        }
        if (current.version != document.expectedDebtVersion) throw SparkMutationFailure.Stale
        if (current.status != DebtStatus.OPEN || current.balance.minorUnits == 0L) {
            throw SparkMutationFailure.Stale
        }
        if (current.currency != document.amount.currency) throw SparkMutationFailure.Rejected
        if (document.amount.minorUnits > current.balance.minorUnits) throw SparkMutationFailure.Stale

        val effectiveAt = Instant.ofEpochMilli(
            maxOf(
                now.toEpochMilli(),
                document.createdAt.toEpochMilli(),
                current.updatedAt.toEpochMilli(),
            ),
        )
        val balanceAfter = current.balance - document.amount
        val paid = balanceAfter.minorUnits == 0L
        val resultingDebt = current.copy(
            balance = balanceAfter,
            status = if (paid) DebtStatus.PAID else DebtStatus.OPEN,
            version = current.version + 1L,
            updatedAt = effectiveAt,
            paidAt = effectiveAt.takeIf { paid },
        )
        val payment = SharedDebtPayment(
            paymentId = document.paymentId,
            debtId = document.debtId,
            businessId = cloudBusinessId,
            amount = document.amount,
            method = document.method,
            note = document.note,
            reference = document.reference,
            expectedDebtVersion = document.expectedDebtVersion,
            balanceAfter = balanceAfter,
            idempotencyKey = document.idempotencyKey,
            occurredAt = document.occurredAt,
            createdAt = effectiveAt,
        )
        return SparkDebtPaymentMutationPlan(
            seq = metadataSeq + 1L,
            debt = resultingDebt,
            payment = payment,
        )
    }

    fun parseDebt(
        raw: Map<String, Any?>,
        expectedBusinessId: BusinessId,
        expectedDebtId: DebtId,
        requireSchemaVersion: Boolean = true,
    ): SharedDebtSnapshot = try {
        if (
            requireSchemaVersion &&
            raw.long("schemaVersion") != SparkFirestoreSchema.SCHEMA_VERSION
        ) {
            invalid()
        }
        val businessId = BusinessId.parse(raw.string("businessId")) ?: invalid()
        val debtId = DebtId.parse(raw.string("debtId")) ?: invalid()
        val saleId = SaleId.parse(raw.string("saleId")) ?: invalid()
        if (businessId != expectedBusinessId || debtId != expectedDebtId) invalid()
        val currency = CurrencyCode.of(raw.string("currency"))
        SharedDebtSnapshot(
            debtId = debtId,
            businessId = businessId,
            saleId = saleId,
            debtorNameSnapshot = raw.string("debtorNameSnapshot"),
            currency = currency,
            originalAmount = Money.ofMinor(raw.long("originalMinorUnits"), currency),
            balance = Money.ofMinor(raw.long("balanceMinorUnits"), currency),
            status = DebtStatus.valueOf(raw.string("status")),
            dueAt = raw.optionalMillis("dueAt")?.let(Instant::ofEpochMilli),
            version = raw.long("version"),
            createdAt = Instant.ofEpochMilli(raw.long("createdAt")),
            updatedAt = Instant.ofEpochMilli(raw.long("updatedAt")),
            paidAt = raw.optionalMillis("paidAt")?.let(Instant::ofEpochMilli),
        )
    } catch (failure: SparkMutationFailure) {
        throw failure
    } catch (_: Exception) {
        throw SparkMutationFailure.CorruptRemoteData
    }
}

internal fun SharedDebtSnapshot.toWireMap(): Map<String, Any?> = linkedMapOf(
    "debtId" to debtId.value,
    "businessId" to businessId.value,
    "saleId" to saleId.value,
    "debtorNameSnapshot" to debtorNameSnapshot,
    "currency" to currency.value,
    "originalMinorUnits" to originalAmount.minorUnits,
    "balanceMinorUnits" to balance.minorUnits,
    "status" to status.name,
    "dueAt" to dueAt?.toEpochMilli(),
    "version" to version,
    "createdAt" to createdAt.toEpochMilli(),
    "updatedAt" to updatedAt.toEpochMilli(),
    "paidAt" to paidAt?.toEpochMilli(),
)

internal fun SharedDebtPayment.toWireMap(): Map<String, Any?> = linkedMapOf(
    "version" to 1L,
    "paymentId" to paymentId.value,
    "debtId" to debtId.value,
    "businessId" to businessId.value,
    "currency" to amount.currency.value,
    "amountMinorUnits" to amount.minorUnits,
    "method" to method.name,
    "note" to note,
    "reference" to reference,
    "expectedDebtVersion" to expectedDebtVersion,
    "balanceAfterMinorUnits" to balanceAfter.minorUnits,
    "idempotencyKey" to idempotencyKey,
    "occurredAt" to occurredAt.toEpochMilli(),
    "createdAt" to createdAt.toEpochMilli(),
)

internal fun parseDebtPayment(
    raw: Map<String, Any?>,
    debt: SharedDebtSnapshot,
): SharedDebtPayment = try {
    if (raw.long("version") != 1L) invalid()
    val currency = CurrencyCode.of(raw.string("currency"))
    val payment = SharedDebtPayment(
        paymentId = DebtPaymentId.parse(raw.string("paymentId")) ?: invalid(),
        debtId = DebtId.parse(raw.string("debtId")) ?: invalid(),
        businessId = BusinessId.parse(raw.string("businessId")) ?: invalid(),
        amount = Money.ofMinor(raw.long("amountMinorUnits"), currency),
        method = DebtPaymentMethod.valueOf(raw.string("method")),
        note = raw.optionalString("note"),
        reference = raw.optionalString("reference"),
        expectedDebtVersion = raw.long("expectedDebtVersion"),
        balanceAfter = Money.ofMinor(raw.long("balanceAfterMinorUnits"), currency),
        idempotencyKey = raw.string("idempotencyKey"),
        occurredAt = Instant.ofEpochMilli(raw.long("occurredAt")),
        createdAt = Instant.ofEpochMilli(raw.long("createdAt")),
    )
    if (
        payment.debtId != debt.debtId || payment.businessId != debt.businessId ||
        payment.amount.currency != debt.currency || payment.balanceAfter != debt.balance ||
        payment.expectedDebtVersion + 1L != debt.version || payment.createdAt != debt.updatedAt
    ) {
        invalid()
    }
    payment
} catch (failure: SparkMutationFailure) {
    throw failure
} catch (_: Exception) {
    throw SparkMutationFailure.CorruptRemoteData
}

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: invalid()

private fun Map<String, Any?>.optionalString(key: String): String? = when (val value = this[key]) {
    null -> null
    is String -> value
    else -> invalid()
}

private fun Map<String, Any?>.long(key: String): Long = when (val value = this[key]) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toLong()
    is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
    else -> null
} ?: invalid()

private fun Map<String, Any?>.optionalMillis(key: String): Long? = when (val value = this[key]) {
    null -> null
    else -> mapOf(key to value).long(key).also { if (it < 0L) invalid() }
}

private fun Map<String, Any?>.decimal(key: String, nonNegative: Boolean): BigDecimal {
    val text = string(key)
    val parsed = runCatching { BigDecimal(text) }.getOrNull() ?: invalid()
    if (
        !InventoryCostingDecimalPolicy.supportsPersisted(parsed.abs()) ||
        (nonNegative && parsed.signum() < 0)
    ) {
        invalid()
    }
    return parsed
}

internal fun BigDecimal.canonicalDecimal(): String =
    if (signum() == 0) "0" else stripTrailingZeros().toPlainString()

internal fun BigDecimal.normalizedInventoryDecimal(): BigDecimal {
    if (signum() == 0) return BigDecimal.ZERO
    val stripped = stripTrailingZeros()
    return if (stripped.scale() < 0) stripped.setScale(0) else stripped
}

private fun invalid(): Nothing = throw SparkMutationFailure.CorruptRemoteData
