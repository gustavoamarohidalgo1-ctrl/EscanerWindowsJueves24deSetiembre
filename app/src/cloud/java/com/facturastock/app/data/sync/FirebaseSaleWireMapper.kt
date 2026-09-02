package com.facturastock.app.data.sync

import com.facturastock.app.data.repository.SaleContentIdentity
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SharedInventoryBalance
import com.facturastock.app.domain.model.SharedInventoryChange
import com.facturastock.app.domain.model.SharedInventoryChangeKind
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedDebtPayment
import com.facturastock.app.domain.model.SharedDebtSnapshot
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.SharedSaleCredit
import com.facturastock.app.domain.model.SharedSaleLine
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import kotlin.math.floor

internal data class FirebaseSalePostBalance(
    val productId: ProductId,
    val locationName: String,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currency: CurrencyCode,
    val version: Long,
    val updatedAt: Instant,
)

internal data class FirebaseSalePostAck(
    val receiptId: String,
    val idempotencyKey: String,
    val status: String,
    val seq: Long,
    val postedAt: Instant,
    val balances: List<FirebaseSalePostBalance>,
    val debt: SharedDebtSnapshot?,
)

internal data class FirebaseInventoryBootstrapAck(
    val receiptId: String,
    val idempotencyKey: String,
    val status: String,
    val seq: Long,
    val balances: List<FirebaseSalePostBalance>,
)

internal data class FirebaseDebtPaymentAck(
    val receiptId: String,
    val idempotencyKey: String,
    val status: String,
    val seq: Long,
    val debt: SharedDebtSnapshot,
    val payment: SharedDebtPayment,
)

/** Frontera cerrada de los callables de venta/inventario; cualquier campo extra falla cerrado. */
internal object FirebaseSaleWireMapper {
    fun postAck(raw: Map<*, *>): FirebaseSalePostAck {
        val baseKeys = setOf(
            "receiptId",
            "idempotencyKey",
            "status",
            "seq",
            "postedAtMillis",
            "balances",
        )
        val keys = raw.stringKeys()
        val hasDebt = when (keys) {
            baseKeys -> false
            baseKeys + "debt" -> true
            else -> malformed()
        }
        val status = raw.requiredString("status")
        if (status != "RECORDED" && status != "ALREADY_RECORDED") malformed()
        val seq = raw["seq"].requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE)
        val balances = raw["balances"].requiredList().map { item ->
            val balance = item.requiredMap()
            parsePostBalance(balance, seq)
        }
        if (balances.isEmpty()) malformed()
        ensureUniqueBalances(balances.map { it.productId to it.locationName })
        return FirebaseSalePostAck(
            receiptId = raw.requiredString("receiptId").takeIf(RECEIPT::matches) ?: malformed(),
            idempotencyKey = raw.requiredString("idempotencyKey").takeIf(String::isNotBlank)
                ?: malformed(),
            status = status,
            seq = seq,
            postedAt = raw["postedAtMillis"].requiredInstant(),
            balances = balances,
            debt = if (hasDebt) parseDebt(raw["debt"].requiredMap()) else null,
        )
    }

    fun bootstrapAck(raw: Map<*, *>): FirebaseInventoryBootstrapAck {
        raw.requireKeys("receiptId", "idempotencyKey", "status", "seq", "balances")
        val receipt = raw.requiredString("receiptId")
            .takeIf(INVENTORY_BOOTSTRAP_RECEIPT::matches) ?: malformed()
        val status = raw.requiredString("status")
        if (status != "BOOTSTRAPPED" && status != "ALREADY_BOOTSTRAPPED") malformed()
        val seq = raw["seq"].requiredLong(1L, 1L)
        val balances = raw["balances"].requiredList().map { item ->
            parsePostBalance(item.requiredMap(), seq)
        }
        if (balances.isEmpty()) malformed()
        ensureUniqueBalances(balances.map { it.productId to it.locationName })
        return FirebaseInventoryBootstrapAck(
            receiptId = receipt,
            idempotencyKey = raw.requiredString("idempotencyKey"),
            status = status,
            seq = seq,
            balances = balances,
        )
    }

    fun debtPaymentAck(raw: Map<*, *>): FirebaseDebtPaymentAck {
        raw.requireKeys(
            "receiptId",
            "idempotencyKey",
            "status",
            "seq",
            "debt",
            "payment",
        )
        val status = raw.requiredString("status")
        if (status != "RECORDED" && status != "ALREADY_RECORDED") malformed()
        val receiptId = raw.requiredString("receiptId")
            .takeIf(DEBT_PAYMENT_RECEIPT::matches) ?: malformed()
        val debt = parseDebt(raw["debt"].requiredMap())
        val payment = parsePayment(raw["payment"].requiredMap(), debt)
        return FirebaseDebtPaymentAck(
            receiptId = receiptId,
            idempotencyKey = raw.requiredString("idempotencyKey"),
            status = status,
            seq = raw["seq"].requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE),
            debt = debt,
            payment = payment,
        )
    }

    fun pullPage(
        raw: Map<*, *>,
        expectedBusinessId: BusinessId,
        expectedPreviousSeq: Long,
    ): SharedInventoryPullPage {
        if (expectedPreviousSeq !in 0L..MAX_SAFE_SYNC_SEQUENCE) malformed()
        raw.requireKeys("changes", "nextCursor", "hasMore", "latestSeq")
        val nextCursor = raw["nextCursor"].requiredLong(0L, MAX_SAFE_SYNC_SEQUENCE)
        val latestSeq = raw["latestSeq"].requiredLong(nextCursor, MAX_SAFE_SYNC_SEQUENCE)
        val hasMore = raw["hasMore"] as? Boolean ?: malformed()
        if (hasMore != (nextCursor < latestSeq)) malformed()
        val changes = raw["changes"].requiredList().mapIndexed { index, item ->
            val change = parseChange(item.requiredMap(), expectedBusinessId)
            val expectedSeq = expectedPreviousSeq.checkedNextSeq(index) ?: malformed()
            if (change.seq != expectedSeq) malformed()
            change
        }
        if (nextCursor != (changes.lastOrNull()?.seq ?: expectedPreviousSeq)) malformed()
        return try {
            SharedInventoryPullPage(
                changes = changes,
                nextCursor = nextCursor,
                hasMore = hasMore,
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    private fun parseChange(
        raw: Map<*, *>,
        expectedBusinessId: BusinessId,
    ): SharedInventoryChange {
        val baseKeys = setOf(
            "kind",
            "seq",
            "receiptId",
            "sale",
            "balances",
            "syncedAtMillis",
        )
        val kind = runCatching {
            SharedInventoryChangeKind.valueOf(raw.requiredString("kind"))
        }.getOrNull() ?: malformed()
        when (kind) {
            SharedInventoryChangeKind.DEBT_PAYMENT ->
                if (raw.stringKeys() != baseKeys + setOf("debt", "payment")) malformed()
            else -> if (raw.stringKeys() != baseKeys) malformed()
        }
        val seq = raw["seq"].requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE)
        val receiptId = raw.requiredString("receiptId")
            .takeIf(CHANGE_RECEIPT::matches) ?: malformed()
        when (val synced = raw["syncedAtMillis"]) {
            null -> Unit
            else -> synced.requiredLong(0L, MAX_SAFE_SYNC_SEQUENCE)
        }
        val balances = raw["balances"].requiredList().map { item ->
            parseSharedBalance(item.requiredMap(), seq)
        }
        val sale = when (kind) {
            SharedInventoryChangeKind.SALE -> parseSale(
                raw = raw["sale"].requiredMap(),
                expectedSeq = seq,
                expectedReceiptId = receiptId,
                expectedBusinessId = expectedBusinessId,
                balances = balances,
            )
            SharedInventoryChangeKind.PURCHASE,
            SharedInventoryChangeKind.PURCHASE_VOID,
            SharedInventoryChangeKind.DEBT_PAYMENT,
            -> if (raw["sale"] == null) null else malformed()
        }
        val debt = if (kind == SharedInventoryChangeKind.DEBT_PAYMENT) {
            parseDebt(raw["debt"].requiredMap()).also { parsed ->
                if (parsed.businessId != expectedBusinessId) malformed()
            }
        } else {
            null
        }
        val payment = if (kind == SharedInventoryChangeKind.DEBT_PAYMENT) {
            parsePayment(raw["payment"].requiredMap(), checkNotNull(debt))
        } else {
            null
        }
        return try {
            SharedInventoryChange(
                seq = seq,
                kind = kind,
                balances = balances,
                sale = sale,
                debt = debt,
                payment = payment,
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    private fun parseSale(
        raw: Map<*, *>,
        expectedSeq: Long,
        expectedReceiptId: String,
        expectedBusinessId: BusinessId,
        balances: List<SharedInventoryBalance>,
    ): SharedSaleDocument {
        val version = raw["version"].requiredLong(1L, 2L)
        val baseKeys = setOf(
            "version",
            "saleId",
            "businessId",
            "status",
            "currency",
            "subtotalMinorUnits",
            "discountMinorUnits",
            "taxMinorUnits",
            "totalMinorUnits",
            "contentHash",
            "checkoutIdempotencyKey",
            "createdAt",
            "updatedAt",
            "postedAt",
            "lines",
            "receiptId",
            "seq",
            "movements",
        )
        when (version) {
            1L -> if (raw.stringKeys() != baseKeys) malformed()
            2L -> if (raw.stringKeys() != baseKeys + "credit") malformed()
            else -> malformed()
        }
        val businessId = BusinessId.parse(raw.requiredString("businessId")) ?: malformed()
        if (businessId != expectedBusinessId) malformed()
        if (raw.requiredString("status") != "POSTED") malformed()
        val receiptId = raw.requiredString("receiptId").takeIf(RECEIPT::matches) ?: malformed()
        if (receiptId != expectedReceiptId) malformed()
        if (raw["seq"].requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE) != expectedSeq) malformed()
        val currency = raw.requiredCurrency("currency")
        val saleId = SaleId.parse(raw.requiredString("saleId")) ?: malformed()
        val rawLines = raw["lines"].requiredList()
        if (rawLines.isEmpty() || rawLines.size > MAX_SALE_LINES) malformed()
        val lines = rawLines.map { parseLine(it.requiredMap(), currency) }
        val remoteKeys = lines.map { line ->
            line.productId to canonicalLocationName(line.locationName)
        }
        if (remoteKeys.distinct().size != remoteKeys.size) malformed()
        val balancesByRemoteKey = balances.associateBy { balance ->
            balance.productId to canonicalLocationName(balance.locationName)
        }
        if (balancesByRemoteKey.keys != remoteKeys.toSet()) malformed()
        val postedAtMillis = raw["postedAt"].requiredLong(0L, MAX_SAFE_SYNC_SEQUENCE)
        balances.forEach { balance ->
            if (
                balance.currency != currency || balance.quantityOnHand.signum() < 0 ||
                balance.version < 1L || balance.updatedAt.toEpochMilli() != postedAtMillis
            ) {
                malformed()
            }
        }
        validateMovements(
            raw = raw["movements"].requiredList(),
            saleId = saleId,
            lines = lines,
            currency = currency,
            postedAtMillis = postedAtMillis,
            balancesByRemoteKey = balancesByRemoteKey,
        )
        val contentHash = raw.requiredString("contentHash")
        if (saleContentHash(currency, lines) != contentHash) malformed()
        val checkoutIdempotencyKey = raw.requiredString("checkoutIdempotencyKey")
        validateCheckoutIdempotencyKey(checkoutIdempotencyKey, saleId, contentHash)
        return try {
            SharedSaleDocument(
                saleId = saleId,
                currency = currency,
                subtotal = Money.ofMinor(
                    raw["subtotalMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                discount = Money.ofMinor(
                    raw["discountMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                tax = Money.ofMinor(
                    raw["taxMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                total = Money.ofMinor(
                    raw["totalMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                contentHash = contentHash,
                checkoutIdempotencyKey = checkoutIdempotencyKey,
                createdAt = raw["createdAt"].requiredInstant(),
                updatedAt = raw["updatedAt"].requiredInstant(),
                postedAt = Instant.ofEpochMilli(postedAtMillis),
                lines = lines,
                credit = if (version == 2L) {
                    parseSaleCredit(raw["credit"].requiredMap(), saleId)
                } else {
                    null
                },
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    private fun parseSaleCredit(raw: Map<*, *>, saleId: SaleId): SharedSaleCredit {
        raw.requireKeys("version", "debtId", "debtorNameSnapshot", "dueAt")
        if (raw["version"].requiredLong(1L, 1L) != 1L) malformed()
        val debtId = DebtId.parse(raw.requiredString("debtId")) ?: malformed()
        if (debtId.value != SaleContentIdentity.uuid("sale-debt", saleId.value).toString()) {
            malformed()
        }
        return try {
            SharedSaleCredit(
                debtId = debtId,
                debtorNameSnapshot = raw.requiredString("debtorNameSnapshot"),
                dueAt = raw["dueAt"].optionalInstant(),
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    private fun parseDebt(raw: Map<*, *>): SharedDebtSnapshot {
        raw.requireKeys(
            "debtId",
            "businessId",
            "saleId",
            "debtorNameSnapshot",
            "currency",
            "originalMinorUnits",
            "balanceMinorUnits",
            "status",
            "dueAt",
            "version",
            "createdAt",
            "updatedAt",
            "paidAt",
        )
        val businessId = BusinessId.parse(raw.requiredString("businessId")) ?: malformed()
        val saleId = SaleId.parse(raw.requiredString("saleId")) ?: malformed()
        val debtId = DebtId.parse(raw.requiredString("debtId")) ?: malformed()
        if (debtId.value != SaleContentIdentity.uuid("sale-debt", saleId.value).toString()) {
            malformed()
        }
        val currency = raw.requiredCurrency("currency")
        val parsed = try {
            SharedDebtSnapshot(
                debtId = debtId,
                businessId = businessId,
                saleId = saleId,
                debtorNameSnapshot = raw.requiredString("debtorNameSnapshot"),
                currency = currency,
                originalAmount = Money.ofMinor(
                    raw["originalMinorUnits"].requiredLong(1L, MAX_MINOR_UNITS),
                    currency,
                ),
                balance = Money.ofMinor(
                    raw["balanceMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                status = runCatching { DebtStatus.valueOf(raw.requiredString("status")) }
                    .getOrNull() ?: malformed(),
                dueAt = raw["dueAt"].optionalInstant(),
                version = raw["version"].requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE - 1L),
                createdAt = raw["createdAt"].requiredInstant(),
                updatedAt = raw["updatedAt"].requiredInstant(),
                paidAt = raw["paidAt"].optionalInstant(),
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
        if (parsed.status == DebtStatus.PAID && parsed.paidAt != parsed.updatedAt) malformed()
        return parsed
    }

    private fun parsePayment(
        raw: Map<*, *>,
        debt: SharedDebtSnapshot,
    ): SharedDebtPayment {
        raw.requireKeys(
            "version",
            "paymentId",
            "debtId",
            "businessId",
            "currency",
            "amountMinorUnits",
            "method",
            "note",
            "reference",
            "expectedDebtVersion",
            "balanceAfterMinorUnits",
            "idempotencyKey",
            "occurredAt",
            "createdAt",
        )
        if (raw["version"].requiredLong(1L, 1L) != 1L) malformed()
        val businessId = BusinessId.parse(raw.requiredString("businessId")) ?: malformed()
        val currency = raw.requiredCurrency("currency")
        if (currency != debt.currency) malformed()
        val payment = try {
            SharedDebtPayment(
                paymentId = DebtPaymentId.parse(raw.requiredString("paymentId")) ?: malformed(),
                debtId = DebtId.parse(raw.requiredString("debtId")) ?: malformed(),
                businessId = businessId,
                amount = Money.ofMinor(
                    raw["amountMinorUnits"].requiredLong(1L, MAX_MINOR_UNITS),
                    currency,
                ),
                method = runCatching {
                    DebtPaymentMethod.valueOf(raw.requiredString("method"))
                }.getOrNull() ?: malformed(),
                note = raw.optionalString("note", 500),
                reference = raw.optionalString("reference", 120),
                expectedDebtVersion = raw["expectedDebtVersion"]
                    .requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE - 1L),
                balanceAfter = Money.ofMinor(
                    raw["balanceAfterMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                idempotencyKey = raw.requiredString("idempotencyKey"),
                occurredAt = raw["occurredAt"].requiredInstant(),
                createdAt = raw["createdAt"].requiredInstant(),
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
        if (
            payment.debtId != debt.debtId || payment.businessId != debt.businessId ||
            debt.version != payment.expectedDebtVersion + 1L ||
            debt.balance != payment.balanceAfter || debt.updatedAt != payment.createdAt
        ) {
            malformed()
        }
        return payment
    }

    private fun parseLine(
        raw: Map<*, *>,
        currency: CurrencyCode,
    ): SharedSaleLine {
        raw.requireKeys(
            "saleLineId",
            "position",
            "productId",
            "unitId",
            "locationId",
            "productName",
            "unitCode",
            "locationName",
            "barcode",
            "quantity",
            "unitPriceMinorUnits",
            "discountMinorUnits",
            "taxMinorUnits",
            "lineTotalMinorUnits",
        )
        val barcode = when (val value = raw["barcode"]) {
            null -> null
            is String -> value.takeIf {
                it.isNotEmpty() && it.length <= 128 && it == it.trim()
            } ?: malformed()
            else -> malformed()
        }
        val lineId = SaleLineId.parse(raw.requiredString("saleLineId")) ?: malformed()
        val productName = raw.requiredBoundedText("productName", 200)
        val unitCode = raw.requiredBoundedText("unitCode", 16)
        val locationName = raw.requiredString("locationName")
        if (locationName != normalizedLocationDisplayName(locationName)) malformed()
        val quantityText = raw.requiredString("quantity")
        if (!WIRE_QUANTITY.matches(quantityText)) malformed()
        return try {
            SharedSaleLine(
                saleLineId = lineId,
                position = raw["position"].requiredLong(0L, Int.MAX_VALUE.toLong()).toInt(),
                productId = ProductId.parse(raw.requiredString("productId")) ?: malformed(),
                unitId = UnitId.parse(raw.requiredString("unitId")) ?: malformed(),
                locationId = LocationId.parse(raw.requiredString("locationId")) ?: malformed(),
                productName = productName,
                unitCode = unitCode,
                locationName = locationName,
                barcode = barcode,
                quantity = Quantity.of(quantityText),
                unitPrice = Money.ofMinor(
                    raw["unitPriceMinorUnits"].requiredLong(1L, MAX_MINOR_UNITS),
                    currency,
                ),
                discount = Money.ofMinor(
                    raw["discountMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                tax = Money.ofMinor(
                    raw["taxMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
                lineTotal = Money.ofMinor(
                    raw["lineTotalMinorUnits"].requiredLong(0L, MAX_MINOR_UNITS),
                    currency,
                ),
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    private fun validateMovements(
        raw: List<*>,
        saleId: SaleId,
        lines: List<SharedSaleLine>,
        currency: CurrencyCode,
        postedAtMillis: Long,
        balancesByRemoteKey: Map<Pair<ProductId, String>, SharedInventoryBalance>,
    ) {
        if (raw.size != lines.size) malformed()
        val linesById = lines.associateBy(SharedSaleLine::saleLineId)
        val movementIds = hashSetOf<String>()
        raw.forEach { item ->
            val movement = item.requiredMap()
            movement.requireKeys(
                "movementId",
                "saleId",
                "saleLineId",
                "productId",
                "sourceLocationId",
                "locationName",
                "canonicalLocationName",
                "type",
                "quantityDelta",
                "unitCost",
                "currency",
                "occurredAt",
            )
            val movementId = movement.requiredString("movementId")
            if (movement.requiredString("saleId") != saleId.value) malformed()
            val lineId = SaleLineId.parse(movement.requiredString("saleLineId")) ?: malformed()
            val line = linesById[lineId] ?: malformed()
            val expectedMovementId = SaleContentIdentity.uuid(
                "sale-stock-movement",
                saleId.value,
                lineId.value,
            ).toString()
            if (movementId != expectedMovementId || !movementIds.add(movementId)) malformed()
            if (
                movement.requiredString("productId") != line.productId.value ||
                movement.requiredString("sourceLocationId") != line.locationId.value ||
                movement.requiredString("locationName") != line.locationName ||
                movement.requiredString("canonicalLocationName") !=
                canonicalLocationName(line.locationName) ||
                movement.requiredString("type") != "SALE" ||
                movement.requiredString("currency") != currency.value ||
                movement["occurredAt"].requiredLong(0L, MAX_SAFE_SYNC_SEQUENCE) != postedAtMillis
            ) {
                malformed()
            }
            val delta = movement.requiredString("quantityDelta").requiredDecimal(negative = true)
            if (delta.compareTo(line.quantity.value.negate()) != 0) malformed()
            val unitCost = movement.requiredString("unitCost").requiredDecimal(negative = false)
            val balance = balancesByRemoteKey[
                line.productId to canonicalLocationName(line.locationName)
            ] ?: malformed()
            if (unitCost.compareTo(balance.averageUnitCost) != 0) malformed()
        }
    }

    private fun parsePostBalance(raw: Map<*, *>, expectedSeq: Long): FirebaseSalePostBalance {
        raw.requireBalanceKeys()
        if (raw["seq"].requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE) != expectedSeq) malformed()
        val quantity = raw.requiredString("quantityOnHand").requiredDecimal(negative = false)
        val average = raw.requiredString("averageUnitCost").requiredDecimal(negative = false)
        return FirebaseSalePostBalance(
            productId = ProductId.parse(raw.requiredString("productId")) ?: malformed(),
            locationName = raw.requiredString("locationName").takeIf(String::isNotBlank)
                ?: malformed(),
            quantityOnHand = quantity.canonicalText(),
            averageUnitCost = average.canonicalText(),
            currency = raw.requiredCurrency("currency"),
            version = raw["version"].requiredLong(0L, MAX_SAFE_SYNC_SEQUENCE),
            updatedAt = raw["updatedAtMillis"].requiredInstant(),
        )
    }

    private fun parseSharedBalance(raw: Map<*, *>, expectedSeq: Long): SharedInventoryBalance {
        raw.requireBalanceKeys()
        if (raw["seq"].requiredLong(1L, MAX_SAFE_SYNC_SEQUENCE) != expectedSeq) malformed()
        return try {
            SharedInventoryBalance(
                productId = ProductId.parse(raw.requiredString("productId")) ?: malformed(),
                locationName = raw.requiredString("locationName"),
                quantityOnHand = raw.requiredString("quantityOnHand").requiredDecimal(
                    negative = true,
                ),
                averageUnitCost = raw.requiredString("averageUnitCost").requiredDecimal(
                    negative = false,
                ),
                currency = raw.requiredCurrency("currency"),
                version = raw["version"].requiredLong(0L, MAX_SAFE_SYNC_SEQUENCE),
                updatedAt = raw["updatedAtMillis"].requiredInstant(),
            )
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    private fun Map<*, *>.requireBalanceKeys() = requireKeys(
        "productId",
        "locationName",
        "quantityOnHand",
        "averageUnitCost",
        "currency",
        "version",
        "updatedAtMillis",
        "seq",
    )

    private fun ensureUniqueBalances(keys: List<Pair<ProductId, String>>) {
        if (keys.map { it.first to canonicalLocationName(it.second) }.distinct().size != keys.size) {
            malformed()
        }
    }

    private fun saleContentHash(
        currency: CurrencyCode,
        lines: List<SharedSaleLine>,
    ): String = buildList {
        add("sale-content-v1")
        add(currency.value)
        lines.forEach { line ->
            add(line.saleLineId.value)
            add(line.position.toString())
            add(line.productId.value)
            add(line.unitId.value)
            add(line.locationId.value)
            add(line.quantity.toString())
            add(line.unitPrice.minorUnits.toString())
            add(line.discount.minorUnits.toString())
            add(line.tax.minorUnits.toString())
            add(line.lineTotal.minorUnits.toString())
            add(currency.value)
        }
    }.lengthPrefixedSha256()

    private fun validateCheckoutIdempotencyKey(
        value: String,
        saleId: SaleId,
        contentHash: String,
    ) {
        val prefix = "sale-checkout:v1:${saleId.value}:"
        val separator = value.lastIndexOf(':')
        val version = value.takeIf { it.startsWith(prefix) && separator > prefix.length }
            ?.substring(prefix.length, separator)
            ?.takeIf { digits -> digits.all(Char::isDigit) }
            ?.toLongOrNull()
        if (
            version == null || version !in 0L..MAX_SAFE_SYNC_SEQUENCE ||
            value.substring(separator + 1) != contentHash
        ) {
            malformed()
        }
    }

    private val RECEIPT = Regex("^sale_[0-9a-f]{32}$")
    private val CHANGE_RECEIPT =
        Regex("^(?:sale|rcpt|inventory_bootstrap|debt_payment)_[0-9a-f]{32}$")
    private val INVENTORY_BOOTSTRAP_RECEIPT =
        Regex("^inventory_bootstrap_[0-9a-f]{32}$")
    private val DEBT_PAYMENT_RECEIPT = Regex("^debt_payment_[0-9a-f]{32}$")
    private val WIRE_QUANTITY = Regex("^(?:0|[1-9][0-9]{0,20})(?:\\.[0-9]{1,18})?$")
    private const val MAX_SALE_LINES = 100
    private const val MAX_MINOR_UNITS = 1_000_000_000_000_000L
}

private fun Any?.requiredMap(): Map<*, *> = this as? Map<*, *> ?: malformed()

private fun Any?.requiredList(): List<*> = this as? List<*> ?: malformed()

private fun Map<*, *>.requiredString(key: String): String =
    (this[key] as? String)?.takeIf { it.isNotEmpty() } ?: malformed()

private fun Map<*, *>.requiredBoundedText(key: String, maximumLength: Int): String =
    requiredString(key).takeIf { value ->
        value.length <= maximumLength && value == value.trim()
    } ?: malformed()

private fun Map<*, *>.requiredCurrency(key: String): CurrencyCode {
    val raw = requiredString(key)
    return runCatching { CurrencyCode.of(raw) }.getOrNull()
        ?.takeIf { it.value == raw } ?: malformed()
}

private fun Map<*, *>.optionalString(key: String, maximumLength: Int): String? =
    when (val value = this[key]) {
        null -> null
        is String -> value.takeIf { it.isNotEmpty() && it.length <= maximumLength } ?: malformed()
        else -> malformed()
    }

private fun Map<*, *>.stringKeys(): Set<String> =
    keys.mapTo(linkedSetOf()) { it as? String ?: malformed() }

private fun Map<*, *>.requireKeys(vararg expected: String) {
    val actual = keys.map { it as? String ?: malformed() }.toSet()
    if (actual != expected.toSet() || size != expected.size) malformed()
}

private fun Any?.requiredInstant(): Instant =
    Instant.ofEpochMilli(requiredLong(0L, MAX_SAFE_SYNC_SEQUENCE))

private fun Any?.optionalInstant(): Instant? = when (this) {
    null -> null
    else -> requiredInstant()
}

private fun Any?.requiredLong(minimum: Long, maximum: Long): Long {
    val value = when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        is Float -> toDouble().exactIntegralLongOrNull()
        is Double -> exactIntegralLongOrNull()
        else -> null
    } ?: malformed()
    return value.takeIf { it in minimum..maximum } ?: malformed()
}

private fun Double.exactIntegralLongOrNull(): Long? {
    if (!isFinite() || this != floor(this) || this < Long.MIN_VALUE || this > Long.MAX_VALUE) {
        return null
    }
    return toLong().takeIf { it.toDouble() == this }
}

private fun Long.checkedNextSeq(offset: Int): Long? {
    val delta = offset.toLong() + 1L
    if (this > MAX_SAFE_SYNC_SEQUENCE - delta) return null
    return this + delta
}

private fun normalizedLocationDisplayName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .also { normalized ->
            if (
                normalized.isEmpty() || normalized.length > 100 ||
                normalized.any { it.code in 0x00..0x1f || it.code == 0x7f }
            ) {
                malformed()
            }
        }

private fun List<String>.lengthPrefixedSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEach { part ->
        val bytes = part.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun String.requiredDecimal(negative: Boolean): BigDecimal {
    if (!SIGNED_DECIMAL.matches(this)) malformed()
    val value = try {
        BigDecimal(this)
    } catch (_: NumberFormatException) {
        malformed()
    }
    if ((!negative && value.signum() < 0) || !InventoryCostingDecimalPolicy.supportsPersisted(value.abs())) {
        malformed()
    }
    return value
}

private fun BigDecimal.canonicalText(): String {
    val stripped = stripTrailingZeros()
    return if (stripped.scale() < 0) stripped.setScale(0).toPlainString() else stripped.toPlainString()
}

private val SIGNED_DECIMAL = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")

private fun malformed(): Nothing = throw AccountException(AccountError.Unexpected)
