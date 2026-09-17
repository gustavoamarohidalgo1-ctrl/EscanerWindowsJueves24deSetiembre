package com.facturastock.app.feature.matching

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceMatchUnitChoice
import com.facturastock.app.domain.model.MatchStatus
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant

/** A bounded saved-state payload contains decisions and unfinished fields, never catalog rows. */
internal object MatchingSavedDraft {
    const val KEY = "matching.review.v1"
    const val MAX_BYTES = 96 * 1024

    data class Decision(
        val lineIndex: Int,
        val sourceKey: String,
        val description: String,
        val productId: ProductId?,
        val staged: Product?,
        val quantity: BigDecimal?,
        val cost: BigDecimal?,
        val currency: CurrencyCode?,
        val unitChoice: InvoiceMatchUnitChoice?,
        val status: MatchStatus,
        val manualLineId: LineId?,
        val productVersion: Long?,
    )

    data class Snapshot(
        val businessId: BusinessId,
        val sourceFingerprint: String,
        val decisions: List<Decision>,
        val creatingIndex: Int?,
        val name: String,
        val barcode: String,
        val price: String,
        val quantity: String,
        val editingIndex: Int?,
        val editQuantity: String,
        val editCost: String,
        val editCurrency: String,
        val editUnitChoice: InvoiceMatchUnitChoice?,
    )

    fun sourceKey(item: ScannedItemMatch): String = item.sourceLineId?.value ?: item.manualLineId?.value ?: "line:${item.lineIndex}"

    fun fingerprint(items: List<ScannedItemMatch>): String {
        val text =
            items.joinToString("\u0000") {
                listOf(sourceKey(it), it.rawDescription, it.printedCode, it.quantity, it.unitCost, it.sourceCurrency, it.sourceUnitCode).joinToString("\u0001")
            }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun encode(
        state: InvoiceMatchingContract.State,
        baseline: List<ScannedItemMatch>,
        businessId: BusinessId,
    ): ByteArray {
        val original = baseline.associateBy(::sourceKey)
        val decisions =
            state.items.filter { item ->
                val before = original[sourceKey(item)]
                before == null || item.matchedProduct?.productId != before.matchedProduct?.productId ||
                    item.status != before.status || item.quantity != before.quantity || item.unitCost != before.unitCost ||
                    item.sourceCurrency != before.sourceCurrency || item.unitChoice != before.unitChoice
            }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeUTF(businessId.value)
            out.writeUTF(fingerprint(baseline))
            out.writeInt(decisions.size)
            decisions.forEach { item ->
                out.writeInt(item.lineIndex)
                out.writeUTF(sourceKey(item))
                // Only manually appended lines require their own source description.
                out.writeUTF(if (original[sourceKey(item)] == null) item.rawDescription else "")
                out.string(item.matchedProduct?.productId?.value)
                val staged = item.matchedProduct.takeIf { item.status == MatchStatus.CREATED_NEW }
                out.writeBoolean(staged != null)
                staged?.let { out.product(it) }
                out.string(item.quantity?.toPlainString())
                out.string(item.unitCost?.toPlainString())
                out.string(item.sourceCurrency?.value)
                out.string(item.unitChoice?.name)
                out.writeUTF(item.status.name)
                out.string(item.manualLineId?.value)
                out.string(item.matchedProduct?.version?.toString())
            }
            out.writeInt(state.creatingItemIndex ?: -1)
            out.writeUTF(state.createName)
            out.writeUTF(state.createBarcode)
            out.writeUTF(state.createPrice)
            out.writeUTF(state.createQuantity)
            out.writeInt(state.editingItemIndex ?: -1)
            out.writeUTF(state.editQuantity)
            out.writeUTF(state.editCost)
            out.writeUTF(state.editCurrency)
            out.string(state.editUnitChoice?.name)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): Snapshot? =
        runCatching {
            require(bytes.size in 1..MAX_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val business = BusinessId.parse(input.readUTF()) ?: error("business")
                val fingerprint = input.readUTF()
                val count = input.readInt().also { require(it in 0..2000) }
                val decisions =
                    List(count) {
                        val index = input.readInt()
                        val key = input.readUTF()
                        val description = input.readUTF()
                        val productId = input.string()?.let { requireNotNull(ProductId.parse(it)) }
                        val staged = if (input.readBoolean()) input.product(business) else null
                        Decision(
                            index,
                            key,
                            description,
                            productId,
                            staged,
                            input.string()?.toBigDecimal(),
                            input.string()?.toBigDecimal(),
                            input.string()?.let(CurrencyCode::of),
                            input.string()?.let(InvoiceMatchUnitChoice::valueOf),
                            MatchStatus.valueOf(input.readUTF()),
                            input.string()?.let { requireNotNull(LineId.parse(it)) },
                            input.string()?.toLong(),
                        )
                    }
                Snapshot(
                    business,
                    fingerprint,
                    decisions,
                    input.readInt().takeIf { it >= 0 },
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readInt().takeIf { it >= 0 },
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.string()?.let(InvoiceMatchUnitChoice::valueOf),
                ).also { require(input.available() == 0) }
            }
        }.getOrNull()

    private fun DataOutputStream.string(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeUTF)
    }

    private fun DataInputStream.string(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.product(product: Product) {
        writeUTF(product.productId.value)
        writeUTF(product.unitId.value)
        writeUTF(product.name)
        string(product.locationId?.value)
        string(product.sku)
        string(product.barcode)
        string(product.salePrice?.minorUnits?.toString())
        string(product.salePrice?.currency?.value)
        writeUTF(product.createdAt.toString())
    }

    private fun DataInputStream.product(businessId: BusinessId): Product {
        val id = requireNotNull(ProductId.parse(readUTF()))
        val unit = requireNotNull(UnitId.parse(readUTF()))
        val name = readUTF()
        val location = string()?.let { requireNotNull(LocationId.parse(it)) }
        val sku = string()
        val barcode = string()
        val minor = string()?.toLong()
        val currency = string()?.let(CurrencyCode::of)
        val createdAt = Instant.parse(readUTF())
        return Product(
            id,
            businessId,
            unit,
            name,
            locationId = location,
            sku = sku,
            barcode = barcode,
            salePrice = if (minor != null && currency != null) Money.ofMinor(minor, currency) else null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }
}
