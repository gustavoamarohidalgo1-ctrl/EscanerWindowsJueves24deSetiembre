package com.facturastock.app.data.spark

import com.facturastock.app.data.repository.SaleContentIdentity
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.SharedSaleLine
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId

internal data class SparkLocalInventoryKey(
    val productId: ProductId,
    val locationId: LocationId,
)

internal data class SparkSaleInventoryTarget(
    val localKey: SparkLocalInventoryKey,
    val remoteProductId: String,
    val locationName: String,
    val canonicalLocationName: String,
    val balanceDocumentId: String,
    val line: SharedSaleLine,
)

internal data class SparkOutboundSale(
    val localDocument: SharedSaleDocument,
    val cloudBusinessId: BusinessId,
    val operationId: String,
    val operationDocumentId: String,
    val receiptId: String,
    val payloadHash: String,
    val wireDocument: Map<String, Any?>,
    val targets: List<SparkSaleInventoryTarget>,
)

/** Construye la misma identidad comercial remota que el callable `postSale`. */
internal object SparkSaleWire {
    fun prepare(
        document: SharedSaleDocument,
        cloudBusinessId: BusinessId,
        remoteProductIds: Map<ProductId, String>,
    ): SparkOutboundSale {
        require(document.lines.size <= MAX_SALE_LINES)
        require(document.checkoutIdempotencyKey.endsWith(":${document.contentHash}"))

        val hashParts = mutableListOf("sale-content-v1", document.currency.value)
        val targets = document.lines.map { line ->
            val remoteProductId = remoteProductIds[line.productId] ?: line.productId.value
            hashParts += listOf(
                line.saleLineId.value,
                line.position.toString(),
                remoteProductId,
                line.unitId.value,
                line.locationId.value,
                line.quantity.value.toPlainString(),
                line.unitPrice.minorUnits.toString(),
                line.discount.minorUnits.toString(),
                line.tax.minorUnits.toString(),
                line.lineTotal.minorUnits.toString(),
                document.currency.value,
            )
            SparkSaleInventoryTarget(
                localKey = SparkLocalInventoryKey(line.productId, line.locationId),
                remoteProductId = remoteProductId,
                locationName = line.locationName,
                canonicalLocationName = canonicalLocationName(line.locationName),
                balanceDocumentId = SparkFirestoreSchema.inventoryBalanceDocumentId(
                    remoteProductId,
                    line.locationName,
                ),
                line = line,
            )
        }
        require(targets.map { it.remoteProductId to it.canonicalLocationName }.distinct().size == targets.size)
        require(targets.map(SparkSaleInventoryTarget::balanceDocumentId).distinct().size == targets.size)

        val wireContentHash = SparkFirestoreSchema.lengthPrefixedSha256(hashParts)
        val wireCheckoutKey = document.checkoutIdempotencyKey.substringBeforeLast(':') +
            ":$wireContentHash"
        val operationId = SparkFirestoreSchema.saleOperationId(document.saleId.value)
        val wireLines = targets.map { target -> target.line.toWireMap(target.remoteProductId) }
        val wireDocument = linkedMapOf<String, Any?>(
            "version" to if (document.credit == null) 1L else 2L,
            "saleId" to document.saleId.value,
            "businessId" to cloudBusinessId.value,
            "status" to "POSTED",
            "currency" to document.currency.value,
            "subtotalMinorUnits" to document.subtotal.minorUnits,
            "discountMinorUnits" to document.discount.minorUnits,
            "taxMinorUnits" to document.tax.minorUnits,
            "totalMinorUnits" to document.total.minorUnits,
            "contentHash" to wireContentHash,
            "checkoutIdempotencyKey" to wireCheckoutKey,
            "createdAt" to document.createdAt.toEpochMilli(),
            "updatedAt" to document.updatedAt.toEpochMilli(),
            "postedAt" to document.postedAt.toEpochMilli(),
            "lines" to wireLines,
        )
        document.credit?.let { credit ->
            wireDocument["credit"] = linkedMapOf(
                "version" to 1L,
                "debtId" to credit.debtId.value,
                "debtorNameSnapshot" to credit.debtorNameSnapshot,
                "dueAt" to credit.dueAt?.toEpochMilli(),
            )
        }
        // Misma proyeccion y SHA que postSaleHandler. Permite reconocer un replay Spark
        // despues de activar Blaze, sin duplicar la venta ni el descuento de existencias.
        val payloadHash = SparkFirestoreSchema.sha256(
            SparkFirestoreSchema.jsonStringify(
                linkedMapOf(
                    "businessId" to cloudBusinessId.value,
                    "idempotencyKey" to operationId,
                    "operationType" to "SYNC_SALE",
                    "payloadVersion" to wireDocument.getValue("version"),
                    "document" to wireDocument,
                ),
            ),
        )
        return SparkOutboundSale(
            localDocument = document,
            cloudBusinessId = cloudBusinessId,
            operationId = operationId,
            operationDocumentId = SparkFirestoreSchema.operationDocumentId(operationId),
            receiptId = SparkFirestoreSchema.saleReceiptId(cloudBusinessId.value, operationId),
            payloadHash = payloadHash,
            wireDocument = wireDocument,
            targets = targets,
        )
    }

    fun movementId(saleId: String, saleLineId: String): String =
        SaleContentIdentity.uuid("sale-stock-movement", saleId, saleLineId).toString()

    private fun SharedSaleLine.toWireMap(remoteProductId: String): Map<String, Any?> = linkedMapOf(
        "saleLineId" to saleLineId.value,
        "position" to position.toLong(),
        "productId" to remoteProductId,
        "unitId" to unitId.value,
        "locationId" to locationId.value,
        "productName" to productName,
        "unitCode" to unitCode,
        "locationName" to locationName,
        "barcode" to barcode,
        "quantity" to quantity.value.toPlainString(),
        "unitPriceMinorUnits" to unitPrice.minorUnits,
        "discountMinorUnits" to discount.minorUnits,
        "taxMinorUnits" to tax.minorUnits,
        "lineTotalMinorUnits" to lineTotal.minorUnits,
    )

    private const val MAX_SALE_LINES = 100
}
