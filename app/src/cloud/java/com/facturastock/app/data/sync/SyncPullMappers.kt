package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.RemoteMovementSummary
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate

/**
 * Traduce los payloads del pull (`listChanges` y la lectura puntual del documento remoto) a
 * modelos del dominio. Todo va por JSON plano (`String`/`Number`/`Map`/`List`/`null`): el
 * repositorio convierte los tipos del SDK (p. ej. `Timestamp` de Firestore) antes de llamar.
 *
 * Cualquier forma inesperada — seq no entera, UUID no canónico, estado fuera del conjunto
 * cerrado, decimal ilegible — se convierte en [AccountError.Unexpected]; el contenido del
 * payload nunca aparece en el error. Objeto puro: no toca Firebase ni Android y se prueba en
 * JVM.
 */
object SyncPullMappers {

    /** Respuesta de `listChanges`: `{changes: [...], nextCursor: N, hasMore: bool}`. */
    fun pullPage(data: Map<*, *>): SyncPullPage = decode {
        requireExactKeys(data, PULL_PAGE_KEYS)
        val entries = data[CHANGES_KEY] as? List<*> ?: malformed()
        if (entries.size > MAX_PAGE_SIZE) malformed()
        val changes = entries.map { entry ->
            purchaseChange(entry as? Map<*, *> ?: malformed())
        }
        val nextCursor = wholeNumber(data[NEXT_CURSOR_KEY])
            .takeIf { it in 0..MAX_SAFE_SYNC_SEQUENCE } ?: malformed()
        val hasMore = data[HAS_MORE_KEY] as? Boolean ?: malformed()
        if (changes.zipWithNext().any { (left, right) -> right.seq <= left.seq }) malformed()
        if (changes.lastOrNull()?.seq?.let { it != nextCursor } == true) malformed()
        if (changes.isEmpty() && hasMore) malformed()
        if (changes.sumOf { it.textBytes().toLong() } > MAX_RESPONSE_TEXT_BYTES) malformed()
        return SyncPullPage(
            changes = changes,
            nextCursor = nextCursor,
            hasMore = hasMore,
        )
    }

    /** Un cambio del libro remoto; la seq es monotónica y arranca en 1. */
    fun purchaseChange(data: Map<*, *>): RemotePurchaseChange = decode {
        requireExactKeys(data, PURCHASE_CHANGE_KEYS)
        val summary = data[MOVEMENT_SUMMARY_KEY] as? List<*> ?: malformed()
        if (summary.size > MAX_MOVEMENT_SUMMARY_SIZE) malformed()
        // El feed compacto no contiene identidad de cuenta. La lectura puntual de un conflicto
        // conserva su mapper separado, pero listChanges nunca puede reintroducir un UID.
        if (data[SYNCED_BY_KEY] != null) malformed()
        val documentType = documentType(data[DOCUMENT_TYPE_KEY])
        val expectedMovementSign = if (documentType == PurchaseDocumentType.CREDIT_NOTE.name) {
            -1
        } else {
            1
        }
        val change = RemotePurchaseChange(
            seq = wholeNumber(data[SEQ_KEY])
                .takeIf { it in 1..MAX_SAFE_SYNC_SEQUENCE } ?: malformed(),
            purchaseId = canonicalUuid(data[PURCHASE_ID_KEY]),
            status = terminalStatus(data[STATUS_KEY]),
            documentType = documentType,
            documentSeries = requiredText(data[DOCUMENT_SERIES_KEY], MAX_DOCUMENT_SERIES_LENGTH),
            documentNumber = requiredText(data[DOCUMENT_NUMBER_KEY], MAX_DOCUMENT_NUMBER_LENGTH),
            issueDate = isoDate(data[ISSUE_DATE_KEY]),
            currency = currency(data[CURRENCY_KEY]),
            supplierRuc = optionalRuc(data[SUPPLIER_RUC_KEY]),
            supplierLegalName = requiredText(data[SUPPLIER_LEGAL_NAME_KEY], MAX_NAME_LENGTH),
            totalMinorUnits = wholeNumber(data[TOTAL_MINOR_UNITS_KEY])
                .takeIf { it in 0L..MAX_MINOR_UNITS } ?: malformed(),
            movementSummary = summary.map { entry ->
                movementSummaryEntry(
                    data = entry as? Map<*, *> ?: malformed(),
                    expectedSign = expectedMovementSign,
                )
            },
            receiptId = canonicalReceiptId(data[RECEIPT_ID_KEY]),
            syncedAtMillis = optionalTimestamp(data[SYNCED_AT_MILLIS_KEY]),
            syncedBy = null,
        )
        if (change.textBytes() > MAX_CHANGE_TEXT_BYTES) malformed()
        change
    }

    /**
     * Lectura puntual del documento remoto en conflicto. [purchaseId] es la clave del
     * documento ya conocida por el cliente; el `receiptId` puede faltar en registros
     * históricos, por eso aquí es opcional.
     */
    fun purchaseDescription(
        purchaseId: String,
        data: Map<*, *>,
    ): RemotePurchaseDescription = decode {
        requireExactKeys(data, PURCHASE_DESCRIPTION_KEYS)
        RemotePurchaseDescription(
            purchaseId = canonicalUuid(purchaseId),
            status = terminalStatus(data[STATUS_KEY]),
            documentType = documentType(data[DOCUMENT_TYPE_KEY]),
            documentSeries = requiredText(
                data[DOCUMENT_SERIES_KEY],
                MAX_DOCUMENT_SERIES_LENGTH,
            ),
            documentNumber = requiredText(
                data[DOCUMENT_NUMBER_KEY],
                MAX_DOCUMENT_NUMBER_LENGTH,
            ),
            issueDate = isoDate(data[ISSUE_DATE_KEY]),
            currency = currency(data[CURRENCY_KEY]),
            supplierRuc = optionalRuc(data[SUPPLIER_RUC_KEY]),
            supplierLegalName = requiredText(data[SUPPLIER_LEGAL_NAME_KEY], MAX_NAME_LENGTH),
            totalMinorUnits = wholeNumber(data[TOTAL_MINOR_UNITS_KEY])
                .takeIf { it in 0L..MAX_MINOR_UNITS } ?: malformed(),
            receiptId = optionalText(data[RECEIPT_ID_KEY], MAX_RECEIPT_ID_LENGTH),
            syncedAtMillis = optionalTimestamp(data[SYNCED_AT_MILLIS_KEY]),
            syncedBy = optionalText(data[SYNCED_BY_KEY], MAX_FIREBASE_UID_LENGTH),
        )
    }

    private fun movementSummaryEntry(
        data: Map<*, *>,
        expectedSign: Int,
    ): RemoteMovementSummary {
        requireExactKeys(data, MOVEMENT_SUMMARY_KEYS)
        val type = movementType(data[TYPE_KEY])
        val quantityDelta = decimalText(data[QUANTITY_DELTA_KEY])
        // Functions solo persiste el movimiento PURCHASE original. Incluso una compra VOIDED
        // conserva ese resumen; el estado evita sumarlo, no transforma su tipo ni su signo.
        if (type != StockMovementType.PURCHASE || quantityDelta.signum() != expectedSign) {
            malformed()
        }
        return RemoteMovementSummary(
            productId = canonicalProductUuid(data[PRODUCT_ID_KEY]),
            productName = optionalText(data[PRODUCT_NAME_KEY], MAX_NAME_LENGTH),
            type = type,
            quantityDelta = quantityDelta,
        )
    }

    /** Entero JSON estricto: una fracción o un desbordamiento son payload malformado. */
    private fun wholeNumber(raw: Any?): Long {
        val number = raw as? Number ?: malformed()
        return try {
            when (number) {
                is Byte -> number.toLong()
                is Short -> number.toLong()
                is Int -> number.toLong()
                is Long -> number
                is BigInteger -> number.longValueExact()
                is BigDecimal -> number.longValueExact()
                // `valueOf`/`toString` conservan el valor decimal representado. Nunca se compara
                // mediante Double ni se usa `toLong`, que truncaría silenciosamente una fracción.
                is Double -> BigDecimal.valueOf(number).longValueExact()
                is Float -> BigDecimal(number.toString()).longValueExact()
                else -> BigDecimal(number.toString()).longValueExact()
            }
        } catch (_: ArithmeticException) {
            malformed()
        } catch (_: NumberFormatException) {
            malformed()
        }
    }

    private fun optionalTimestamp(raw: Any?): Long? = raw?.let(::wholeNumber)
        ?.takeIf { it in 0..MAX_SAFE_SYNC_SEQUENCE } ?: if (raw == null) null else malformed()

    private fun canonicalUuid(raw: Any?): String =
        (raw as? String)?.takeIf { PurchaseId.parse(it) != null } ?: malformed()

    private fun canonicalProductUuid(raw: Any?): String =
        (raw as? String)?.takeIf { ProductId.parse(it) != null } ?: malformed()

    private fun canonicalReceiptId(raw: Any?): String =
        (raw as? String)?.takeIf(RECEIPT_ID_REGEX::matches) ?: malformed()

    private fun requiredText(raw: Any?, maxLength: Int = MAX_GENERIC_TEXT_LENGTH): String =
        (raw as? String)?.takeIf { it.isNotBlank() && it.length <= maxLength } ?: malformed()

    private fun optionalText(raw: Any?, maxLength: Int): String? = when (raw) {
        null -> null
        else -> requiredText(raw, maxLength)
    }

    private fun optionalRuc(raw: Any?): String? = when (raw) {
        null -> null
        is String -> raw.takeIf { it == it.trim() && RucValidator.isWellFormed(it) } ?: malformed()
        else -> malformed()
    }

    private fun currency(raw: Any?): String {
        val value = requiredText(raw, MAX_CURRENCY_LENGTH)
        val parsed = try {
            CurrencyCode.of(value)
        } catch (_: Exception) {
            malformed()
        }
        return value.takeIf { parsed.value == it } ?: malformed()
    }

    private fun documentType(raw: Any?): String {
        val value = requiredText(raw, MAX_DOCUMENT_TYPE_LENGTH)
        return value.takeIf { candidate ->
            PurchaseDocumentType.entries.any { it.name == candidate }
        } ?: malformed()
    }

    /** Estado remoto reconciliable: solo los terminales del libro, nunca DRAFT. */
    private fun terminalStatus(raw: Any?): PurchaseStatus =
        PurchaseStatus.entries.firstOrNull { it.name == raw }
            ?.takeIf { it == PurchaseStatus.POSTED || it == PurchaseStatus.VOIDED }
            ?: malformed()

    private fun movementType(raw: Any?): StockMovementType =
        StockMovementType.entries.firstOrNull { it.name == raw } ?: malformed()

    private fun isoDate(raw: Any?): String {
        val value = requiredText(raw, ISO_DATE_LENGTH)
        return value.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: malformed()
    }

    private fun decimalText(raw: Any?): BigDecimal {
        val value = requiredText(raw, MAX_DECIMAL_TEXT_LENGTH)
        if (!DECIMAL_REGEX.matches(value)) malformed()
        return try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            malformed()
        }
    }

    private fun requireExactKeys(data: Map<*, *>, expected: Set<String>) {
        if (
            data.size != expected.size ||
            data.keys.any { key -> key !is String || key !in expected }
        ) {
            malformed()
        }
    }

    private fun RemotePurchaseChange.textBytes(): Int {
        var total = purchaseId.utf8Bytes() + documentType.utf8Bytes() +
            documentSeries.utf8Bytes() + documentNumber.utf8Bytes() + issueDate.utf8Bytes() +
            currency.utf8Bytes() + supplierLegalName.utf8Bytes() + receiptId.utf8Bytes()
        total += supplierRuc?.utf8Bytes() ?: 0
        movementSummary.forEach { movement ->
            total += movement.productId.utf8Bytes()
            total += movement.productName?.utf8Bytes() ?: 0
            total += movement.quantityDelta.toPlainString().utf8Bytes()
        }
        return total
    }

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (failure: AccountException) {
        throw failure
    } catch (_: Exception) {
        malformed()
    }

    private fun malformed(): Nothing = throw AccountException(AccountError.Unexpected)

    private const val CHANGES_KEY = "changes"
    private const val NEXT_CURSOR_KEY = "nextCursor"
    private const val HAS_MORE_KEY = "hasMore"
    private const val SEQ_KEY = "seq"
    private const val PURCHASE_ID_KEY = "purchaseId"
    private const val STATUS_KEY = "status"
    private const val DOCUMENT_TYPE_KEY = "documentType"
    private const val DOCUMENT_SERIES_KEY = "documentSeries"
    private const val DOCUMENT_NUMBER_KEY = "documentNumber"
    private const val ISSUE_DATE_KEY = "issueDate"
    private const val CURRENCY_KEY = "currency"
    private const val SUPPLIER_RUC_KEY = "supplierRuc"
    private const val SUPPLIER_LEGAL_NAME_KEY = "supplierLegalName"
    private const val TOTAL_MINOR_UNITS_KEY = "totalMinorUnits"
    private const val MOVEMENT_SUMMARY_KEY = "movementSummary"
    private const val PRODUCT_ID_KEY = "productId"
    private const val PRODUCT_NAME_KEY = "productName"
    private const val TYPE_KEY = "type"
    private const val QUANTITY_DELTA_KEY = "quantityDelta"
    private const val RECEIPT_ID_KEY = "receiptId"
    private const val SYNCED_AT_MILLIS_KEY = "syncedAtMillis"
    private const val SYNCED_BY_KEY = "syncedBy"
    private const val MAX_PAGE_SIZE = 200
    private const val MAX_MOVEMENT_SUMMARY_SIZE = 500
    private const val MAX_RESPONSE_TEXT_BYTES = 4_000_000L
    private const val MAX_CHANGE_TEXT_BYTES = 64_000
    private const val MAX_MINOR_UNITS = 1_000_000_000_000_000L
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_DOCUMENT_SERIES_LENGTH = 20
    private const val MAX_DOCUMENT_NUMBER_LENGTH = 32
    private const val MAX_DOCUMENT_TYPE_LENGTH = 32
    private const val MAX_CURRENCY_LENGTH = 3
    private const val MAX_RECEIPT_ID_LENGTH = 128
    private const val MAX_FIREBASE_UID_LENGTH = 128
    private const val MAX_GENERIC_TEXT_LENGTH = 256
    private const val MAX_DECIMAL_TEXT_LENGTH = 41
    private const val ISO_DATE_LENGTH = 10
    private val RECEIPT_ID_REGEX = Regex("rcpt_[0-9a-f]{32}")
    private val DECIMAL_REGEX = Regex("^-?\\d{1,21}(?:\\.\\d{1,18})?$")
    private val PULL_PAGE_KEYS = setOf(CHANGES_KEY, NEXT_CURSOR_KEY, HAS_MORE_KEY)
    private val PURCHASE_CHANGE_KEYS = setOf(
        SEQ_KEY,
        PURCHASE_ID_KEY,
        STATUS_KEY,
        DOCUMENT_TYPE_KEY,
        DOCUMENT_SERIES_KEY,
        DOCUMENT_NUMBER_KEY,
        ISSUE_DATE_KEY,
        CURRENCY_KEY,
        SUPPLIER_RUC_KEY,
        SUPPLIER_LEGAL_NAME_KEY,
        TOTAL_MINOR_UNITS_KEY,
        MOVEMENT_SUMMARY_KEY,
        RECEIPT_ID_KEY,
        SYNCED_AT_MILLIS_KEY,
        SYNCED_BY_KEY,
    )
    private val PURCHASE_DESCRIPTION_KEYS = setOf(
        STATUS_KEY,
        DOCUMENT_TYPE_KEY,
        DOCUMENT_SERIES_KEY,
        DOCUMENT_NUMBER_KEY,
        ISSUE_DATE_KEY,
        CURRENCY_KEY,
        SUPPLIER_RUC_KEY,
        SUPPLIER_LEGAL_NAME_KEY,
        TOTAL_MINOR_UNITS_KEY,
        RECEIPT_ID_KEY,
        SYNCED_AT_MILLIS_KEY,
        SYNCED_BY_KEY,
    )
    private val MOVEMENT_SUMMARY_KEYS = setOf(
        PRODUCT_ID_KEY,
        PRODUCT_NAME_KEY,
        TYPE_KEY,
        QUANTITY_DELTA_KEY,
    )
}
