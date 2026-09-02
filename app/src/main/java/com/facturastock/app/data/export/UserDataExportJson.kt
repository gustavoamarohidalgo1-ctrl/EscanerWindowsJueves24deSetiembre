package com.facturastock.app.data.export

import com.facturastock.app.domain.model.ExportedAuditEvent
import com.facturastock.app.domain.model.ExportedBusiness
import com.facturastock.app.domain.model.ExportedDuplicateOverride
import com.facturastock.app.domain.model.ExportedInventoryBalance
import com.facturastock.app.domain.model.ExportedInventoryLocation
import com.facturastock.app.domain.model.ExportedProduct
import com.facturastock.app.domain.model.ExportedPurchase
import com.facturastock.app.domain.model.ExportedPurchaseLine
import com.facturastock.app.domain.model.ExportedRetainedImage
import com.facturastock.app.domain.model.ExportedStockMovement
import com.facturastock.app.domain.model.ExportedSupplier
import com.facturastock.app.domain.model.ExportedSupplierProductAlias
import com.facturastock.app.domain.model.ExportedUnit
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.UserDataExport
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Serializador JSON determinista del libro contable exportable. Las claves permanecen en orden
 * alfabético en cada objeto; fechas y decimales usan representaciones ISO/planas exactas. No
 * depende de reflexión, de modo que el mismo contrato funciona bajo R8.
 */
object UserDataExportJson {

    /** Compatibilidad para consumidores que necesitan el documento completo en memoria. */
    fun serialize(export: UserDataExport): String = buildString { writeTo(export, this) }

    /**
     * Escribe el mismo contrato JSON de forma incremental. El destino SAF usa esta ruta para no
     * materializar una segunda copia, potencialmente grande, de toda la exportación.
     */
    fun writeTo(export: UserDataExport, destination: Appendable): Unit = with(destination) {
        append('{')
        appendKey("auditEvents")
        appendArray(export.auditEvents) { appendAuditEvent(it) }
        append(',')
        appendKey("business")
        appendBusiness(export.business)
        append(',')
        appendKey("excludedData")
        appendArray(export.excludedData) { appendString(it) }
        append(',')
        appendKey("exportKind")
        appendString(export.exportKind)
        append(',')
        appendKey("exportedAt")
        appendInstant(export.exportedAt)
        append(',')
        appendKey("imageFilesIncluded")
        append(export.imageFilesIncluded)
        append(',')
        appendKey("inventoryBalances")
        appendArray(export.inventoryBalances) { appendInventoryBalance(it) }
        append(',')
        appendKey("inventoryLocations")
        appendArray(export.inventoryLocations) { appendInventoryLocation(it) }
        append(',')
        appendKey("products")
        appendArray(export.products) { appendProduct(it) }
        append(',')
        appendKey("purchases")
        appendArray(export.purchases) { appendPurchase(it) }
        append(',')
        appendKey("schemaVersion")
        append(export.schemaVersion)
        append(',')
        appendKey("stockMovements")
        appendArray(export.stockMovements) { appendStockMovement(it) }
        append(',')
        appendKey("supplierProductAliases")
        appendArray(export.supplierProductAliases) { appendSupplierProductAlias(it) }
        append(',')
        appendKey("suppliers")
        appendArray(export.suppliers) { appendSupplier(it) }
        append(',')
        appendKey("units")
        appendArray(export.units) { appendUnit(it) }
        append('}')
        Unit
    }

    private fun Appendable.appendBusiness(business: ExportedBusiness?) {
        if (business == null) {
            append(NULL)
            return
        }
        append('{')
        appendKey("businessId")
        appendString(business.businessId)
        append(',')
        appendKey("createdAt")
        appendInstant(business.createdAt)
        append(',')
        appendKey("legalName")
        appendString(business.legalName)
        append(',')
        appendKey("ruc")
        appendNullableString(business.ruc)
        append(',')
        appendKey("status")
        appendString(business.status)
        append(',')
        appendKey("tradeName")
        appendNullableString(business.tradeName)
        append(',')
        appendKey("updatedAt")
        appendInstant(business.updatedAt)
        append('}')
    }

    private fun Appendable.appendSupplier(supplier: ExportedSupplier) {
        append('{')
        appendKey("createdAt")
        appendInstant(supplier.createdAt)
        append(',')
        appendKey("legalName")
        appendString(supplier.legalName)
        append(',')
        appendKey("ruc")
        appendNullableString(supplier.ruc)
        append(',')
        appendKey("status")
        appendString(supplier.status)
        append(',')
        appendKey("supplierId")
        appendString(supplier.supplierId)
        append(',')
        appendKey("tradeName")
        appendNullableString(supplier.tradeName)
        append(',')
        appendKey("updatedAt")
        appendInstant(supplier.updatedAt)
        append(',')
        appendKey("version")
        append(supplier.version)
        append('}')
    }

    private fun Appendable.appendUnit(unit: ExportedUnit) {
        append('{')
        appendKey("code")
        appendString(unit.code)
        append(',')
        appendKey("createdAt")
        appendInstant(unit.createdAt)
        append(',')
        appendKey("name")
        appendString(unit.name)
        append(',')
        appendKey("status")
        appendString(unit.status)
        append(',')
        appendKey("symbol")
        appendNullableString(unit.symbol)
        append(',')
        appendKey("unitId")
        appendString(unit.unitId)
        append(',')
        appendKey("updatedAt")
        appendInstant(unit.updatedAt)
        append('}')
    }

    private fun Appendable.appendInventoryLocation(location: ExportedInventoryLocation) {
        append('{')
        appendKey("createdAt")
        appendInstant(location.createdAt)
        append(',')
        appendKey("locationId")
        appendString(location.locationId)
        append(',')
        appendKey("name")
        appendString(location.name)
        append(',')
        appendKey("status")
        appendString(location.status)
        append(',')
        appendKey("updatedAt")
        appendInstant(location.updatedAt)
        append('}')
    }

    private fun Appendable.appendProduct(product: ExportedProduct) {
        append('{')
        appendKey("barcode")
        appendNullableString(product.barcode)
        append(',')
        appendKey("createdAt")
        appendInstant(product.createdAt)
        append(',')
        appendKey("locationId")
        appendNullableString(product.locationId)
        append(',')
        appendKey("name")
        appendString(product.name)
        append(',')
        appendKey("productId")
        appendString(product.productId)
        append(',')
        appendKey("purchaseFactor")
        appendNullableDecimal(product.purchaseFactor)
        append(',')
        appendKey("purchaseUnitId")
        appendNullableString(product.purchaseUnitId)
        append(',')
        appendKey("sku")
        appendNullableString(product.sku)
        append(',')
        appendKey("status")
        appendString(product.status)
        append(',')
        appendKey("unitId")
        appendString(product.unitId)
        append(',')
        appendKey("updatedAt")
        appendInstant(product.updatedAt)
        append(',')
        appendKey("version")
        append(product.version)
        append('}')
    }

    private fun Appendable.appendSupplierProductAlias(alias: ExportedSupplierProductAlias) {
        append('{')
        appendKey("alias")
        appendString(alias.alias)
        append(',')
        appendKey("aliasId")
        appendString(alias.aliasId)
        append(',')
        appendKey("createdAt")
        appendInstant(alias.createdAt)
        append(',')
        appendKey("productId")
        appendString(alias.productId)
        append(',')
        appendKey("supplierId")
        appendString(alias.supplierId)
        append(',')
        appendKey("updatedAt")
        appendInstant(alias.updatedAt)
        append('}')
    }

    private fun Appendable.appendPurchase(purchase: ExportedPurchase) {
        append('{')
        appendKey("acceptedWarnings")
        appendArray(purchase.acceptedWarnings) { appendString(it) }
        append(',')
        appendKey("adjustmentMinorUnits")
        appendNullableMoney(purchase.adjustment)
        append(',')
        appendKey("adjustmentReason")
        appendNullableString(purchase.adjustmentReason)
        append(',')
        appendKey("currency")
        appendString(purchase.currency.value)
        append(',')
        appendKey("documentNumber")
        appendString(purchase.documentNumber)
        append(',')
        appendKey("documentSeries")
        appendString(purchase.documentSeries)
        append(',')
        appendKey("documentType")
        appendString(purchase.documentType)
        append(',')
        appendKey("duplicateOverride")
        appendDuplicateOverride(purchase.duplicateOverride)
        append(',')
        appendKey("issueDate")
        appendString(purchase.issueDate.toJsonDate())
        append(',')
        appendKey("lines")
        appendArray(purchase.lines) { appendPurchaseLine(it) }
        append(',')
        appendKey("otherChargesMinorUnits")
        appendMoney(purchase.otherCharges)
        append(',')
        appendKey("postedAt")
        appendNullableInstant(purchase.postedAt)
        append(',')
        appendKey("preparedLogicalHash")
        appendString(purchase.preparedLogicalHash)
        append(',')
        appendKey("purchaseId")
        appendString(purchase.purchaseId)
        append(',')
        appendKey("retainedImages")
        appendArray(purchase.retainedImages) { appendRetainedImage(it) }
        append(',')
        appendKey("sourceDraftId")
        appendString(purchase.sourceDraftId)
        append(',')
        appendKey("status")
        appendString(purchase.status)
        append(',')
        appendKey("subtotalMinorUnits")
        appendMoney(purchase.subtotal)
        append(',')
        appendKey("supplierId")
        appendString(purchase.supplierId)
        append(',')
        appendKey("supplierLegalName")
        appendString(purchase.supplierLegalName)
        append(',')
        appendKey("supplierRuc")
        appendNullableString(purchase.supplierRuc)
        append(',')
        appendKey("taxMinorUnits")
        appendMoney(purchase.tax)
        append(',')
        appendKey("totalMinorUnits")
        appendMoney(purchase.total)
        append('}')
    }

    private fun Appendable.appendDuplicateOverride(value: ExportedDuplicateOverride?) {
        if (value == null) {
            append(NULL)
            return
        }
        append('{')
        appendKey("actorId")
        appendString(value.actorId)
        append(',')
        appendKey("actorRole")
        appendString(value.actorRole)
        append(',')
        appendKey("existingPurchaseId")
        appendString(value.existingPurchaseId)
        append(',')
        appendKey("reason")
        appendString(value.reason)
        append('}')
    }

    private fun Appendable.appendRetainedImage(image: ExportedRetainedImage) {
        append('{')
        appendKey("cropBottomFraction")
        appendNullableInt(image.cropBottomFraction)
        append(',')
        appendKey("cropLeftFraction")
        appendNullableInt(image.cropLeftFraction)
        append(',')
        appendKey("cropRightFraction")
        appendNullableInt(image.cropRightFraction)
        append(',')
        appendKey("cropTopFraction")
        appendNullableInt(image.cropTopFraction)
        append(',')
        appendKey("heightPx")
        append(image.heightPx)
        append(',')
        appendKey("imageId")
        appendString(image.imageId)
        append(',')
        appendKey("mimeType")
        appendString(image.mimeType)
        append(',')
        appendKey("pageIndex")
        append(image.pageIndex)
        append(',')
        appendKey("rotationDegrees")
        append(image.rotationDegrees)
        append(',')
        appendKey("widthPx")
        append(image.widthPx)
        append('}')
    }

    private fun Appendable.appendPurchaseLine(line: ExportedPurchaseLine) {
        append('{')
        appendKey("appliedUnitCost")
        appendNullableDecimal(line.appliedUnitCost)
        append(',')
        appendKey("description")
        appendString(line.description)
        append(',')
        appendKey("discount")
        appendNullableDecimal(line.discount)
        append(',')
        appendKey("inventoryQuantity")
        appendNullableDecimal(line.inventoryQuantity)
        append(',')
        appendKey("position")
        append(line.position)
        append(',')
        appendKey("productId")
        appendString(line.productId)
        append(',')
        appendKey("productName")
        appendString(line.productName)
        append(',')
        appendKey("productProvenance")
        appendString(line.productProvenance)
        append(',')
        appendKey("purchaseLineId")
        appendString(line.purchaseLineId)
        append(',')
        appendKey("quantity")
        appendDecimal(line.quantity)
        append(',')
        appendKey("rawText")
        appendString(line.rawText)
        append(',')
        appendKey("readUnitCost")
        appendDecimal(line.readUnitCost)
        append(',')
        appendKey("taxEvidenceType")
        appendNullableString(line.taxEvidenceType)
        append(',')
        appendKey("taxEvidenceValue")
        appendNullableDecimal(line.taxEvidenceValue)
        append(',')
        appendKey("taxMinorUnits")
        append(line.taxMinorUnits)
        append(',')
        appendKey("taxTreatment")
        appendNullableString(line.taxTreatment)
        append(',')
        appendKey("totalMinorUnits")
        append(line.totalMinorUnits)
        append(',')
        appendKey("unitCode")
        appendString(line.unitCode)
        append(',')
        appendKey("unitId")
        appendString(line.unitId)
        append(',')
        appendKey("unitSymbol")
        appendNullableString(line.unitSymbol)
        append('}')
    }

    private fun Appendable.appendInventoryBalance(balance: ExportedInventoryBalance) {
        append('{')
        appendKey("alerts")
        appendArray(balance.alerts) { appendString(it) }
        append(',')
        appendKey("averageUnitCost")
        appendDecimal(balance.averageUnitCost)
        append(',')
        appendKey("currency")
        appendString(balance.currency)
        append(',')
        appendKey("locationId")
        appendString(balance.locationId)
        append(',')
        appendKey("productId")
        appendString(balance.productId)
        append(',')
        appendKey("quantityOnHand")
        appendDecimal(balance.quantityOnHand)
        append(',')
        appendKey("updatedAt")
        appendInstant(balance.updatedAt)
        append(',')
        appendKey("version")
        append(balance.version)
        append('}')
    }

    private fun Appendable.appendStockMovement(movement: ExportedStockMovement) {
        append('{')
        appendKey("alerts")
        appendArray(movement.alerts) { appendString(it) }
        append(',')
        appendKey("createdAt")
        appendInstant(movement.createdAt)
        append(',')
        appendKey("currency")
        appendNullableString(movement.currency)
        append(',')
        appendKey("locationId")
        appendString(movement.locationId)
        append(',')
        appendKey("movementId")
        appendString(movement.movementId)
        append(',')
        appendKey("occurredAt")
        appendInstant(movement.occurredAt)
        append(',')
        appendKey("productId")
        appendString(movement.productId)
        append(',')
        appendKey("purchaseDocumentNumber")
        appendNullableString(movement.purchaseDocumentNumber)
        append(',')
        appendKey("purchaseId")
        appendNullableString(movement.purchaseId)
        append(',')
        appendKey("purchaseLineId")
        appendNullableString(movement.purchaseLineId)
        append(',')
        appendKey("quantityDelta")
        appendDecimal(movement.quantityDelta)
        append(',')
        appendKey("type")
        appendString(movement.type)
        append(',')
        appendKey("unitCost")
        appendNullableDecimal(movement.unitCost)
        append('}')
    }

    private fun Appendable.appendAuditEvent(event: ExportedAuditEvent) {
        append('{')
        appendKey("auditEventId")
        appendString(event.auditEventId)
        append(',')
        appendKey("entityId")
        appendString(event.entityId)
        append(',')
        appendKey("entityType")
        appendString(event.entityType)
        append(',')
        appendKey("eventType")
        appendString(event.eventType)
        append(',')
        appendKey("occurredAt")
        appendInstant(event.occurredAt)
        append(',')
        appendKey("purchaseId")
        appendNullableString(event.purchaseId)
        append('}')
    }

    private fun <T> Appendable.appendArray(
        items: List<T>,
        appendItem: Appendable.(T) -> Unit,
    ) {
        append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            appendItem(item)
        }
        append(']')
    }

    private fun Appendable.appendKey(key: String) {
        appendString(key)
        append(':')
    }

    private fun Appendable.appendString(value: String) {
        append('"')
        appendJsonEscaped(value)
        append('"')
    }

    private fun Appendable.appendJsonEscaped(value: String) {
        var unchangedStart = 0
        value.forEachIndexed { index, character ->
            val escaped = when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (character.code < 0x20) {
                    "\\u%04x".format(character.code)
                } else {
                    null
                }
            }
            if (escaped != null) {
                if (unchangedStart < index) append(value, unchangedStart, index)
                append(escaped)
                unchangedStart = index + 1
            }
        }
        if (unchangedStart < value.length) append(value, unchangedStart, value.length)
    }

    private fun Appendable.appendNullableString(value: String?) {
        if (value == null) append(NULL) else appendString(value)
    }

    private fun Appendable.appendInstant(value: Instant) {
        appendString(value.toString())
    }

    private fun Appendable.appendNullableInstant(value: Instant?) {
        if (value == null) append(NULL) else appendInstant(value)
    }

    private fun Appendable.appendMoney(value: Money) {
        append(value.minorUnits)
    }

    private fun Appendable.appendNullableMoney(value: Money?) {
        if (value == null) append(NULL) else appendMoney(value)
    }

    private fun Appendable.appendDecimal(value: BigDecimal) {
        append(value.toPlainString())
    }

    private fun Appendable.appendNullableDecimal(value: BigDecimal?) {
        if (value == null) append(NULL) else appendDecimal(value)
    }

    private fun Appendable.appendNullableInt(value: Int?) {
        if (value == null) append(NULL) else append(value)
    }

    private fun Appendable.append(value: Boolean) = append(value.toString())

    private fun Appendable.append(value: Int) = append(value.toString())

    private fun Appendable.append(value: Long) = append(value.toString())

    private fun LocalDate.toJsonDate(): String = toString()

    private const val NULL = "null"
}
