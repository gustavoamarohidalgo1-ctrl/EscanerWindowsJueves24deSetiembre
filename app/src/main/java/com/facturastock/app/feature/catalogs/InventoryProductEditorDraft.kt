package com.facturastock.app.feature.catalogs

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductEditingPosition
import com.facturastock.app.domain.repository.ProductEditingSnapshot
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import com.facturastock.app.feature.catalogs.CatalogsContract.InventoryBalanceDraft
import java.time.Instant

/** Snapshot acotado compatible con SavedState: conserva también los campos ocultos y el CAS. */
internal fun Form.ProductForm.inventoryDraftFields(): ArrayList<String> {
    val product = requireNotNull(original)
    val fields =
        arrayListOf(
            product.businessId.value,
            product.productId.value,
            product.unitId.value,
            product.name,
            product.locationId?.value.orEmpty(),
            product.sku.orEmpty(),
            product.barcode.orEmpty(),
            product.purchaseUnitId?.value.orEmpty(),
            product.purchaseFactor?.toPlainString().orEmpty(),
            product.salePrice
                ?.minorUnits
                ?.toString()
                .orEmpty(),
            product.salePrice
                ?.currency
                ?.value
                .orEmpty(),
            product.status.name,
            product.createdAt.toString(),
            product.updatedAt.toString(),
            product.version.toString(),
            title,
            salePrice,
            requireNotNull(saleCurrency).value,
            sku,
            barcode,
            isSkuInputTooLong.toString(),
            isBarcodeInputTooLong.toString(),
        )
    inventorySnapshot?.let { snapshot ->
        fields +=
            listOf(
                "balances-v1",
                snapshot.inventoryEditable.toString(),
                snapshot.defaultCurrency.value,
                locationId?.value.orEmpty(),
                snapshot.positions.size.toString(),
            )
        snapshot.positions.forEach { position ->
            val draft = inventoryBalanceDrafts.single { it.locationId == position.locationId }
            fields +=
                listOf(
                    position.locationId.value,
                    position.locationName,
                    position.locationStatus.name,
                    position.quantityOnHand.toPlainString(),
                    position.averageUnitCost?.toPlainString().orEmpty(),
                    position.currency.value,
                    position.balanceVersion?.toString().orEmpty(),
                    draft.quantity,
                    draft.purchasePrice,
                )
        }
    }
    return fields
}

internal fun restoreInventoryProductForm(fields: ArrayList<String>): Form.ProductForm? =
    runCatching {
        require(fields.all { it.length <= 256 })
        require(fields.size in setOf(18, 22) || (fields.size >= 27 && fields[22] == "balances-v1"))
        val original =
            Product(
                businessId = requireNotNull(BusinessId.parse(fields[0])),
                productId = requireNotNull(ProductId.parse(fields[1])),
                unitId = requireNotNull(UnitId.parse(fields[2])),
                name = fields[3],
                locationId = fields[4].takeIf(String::isNotEmpty)?.let { requireNotNull(LocationId.parse(it)) },
                sku = fields[5].takeIf(String::isNotEmpty),
                barcode = fields[6].takeIf(String::isNotEmpty),
                purchaseUnitId = fields[7].takeIf(String::isNotEmpty)?.let { requireNotNull(UnitId.parse(it)) },
                purchaseFactor = fields[8].takeIf(String::isNotEmpty)?.toBigDecimal(),
                salePrice = fields[9].takeIf(String::isNotEmpty)?.let { Money.ofMinor(it.toLong(), CurrencyCode.of(fields[10])) },
                status = CatalogStatus.valueOf(fields[11]),
                createdAt = Instant.parse(fields[12]),
                updatedAt = Instant.parse(fields[13]),
                version = fields[14].toLong().also { require(it > 0L) },
            )
        val form =
            inventoryProductForm(original, CurrencyCode.of(fields[17])).copy(
                title = fields[15],
                salePrice = fields[16],
                sku = fields.getOrNull(18) ?: original.sku.orEmpty(),
                barcode = fields.getOrNull(19) ?: original.barcode.orEmpty(),
                isSkuInputTooLong = fields.getOrNull(20)?.toBooleanStrict() ?: false,
                isBarcodeInputTooLong = fields.getOrNull(21)?.toBooleanStrict() ?: false,
            )
        if (fields.size <= 22) return@runCatching form
        val count = fields[26].toInt()
        require(count in 0..2000 && fields.size == 27 + count * 9)
        val positions =
            (0 until count).map { index ->
                val offset = 27 + index * 9
                ProductEditingPosition(
                    locationId = requireNotNull(LocationId.parse(fields[offset])),
                    locationName = fields[offset + 1],
                    locationStatus = CatalogStatus.valueOf(fields[offset + 2]),
                    quantityOnHand = fields[offset + 3].toBigDecimal(),
                    averageUnitCost = fields[offset + 4].takeIf(String::isNotEmpty)?.toBigDecimal(),
                    currency = CurrencyCode.of(fields[offset + 5]),
                    balanceVersion = fields[offset + 6].takeIf(String::isNotEmpty)?.toLong()?.also { require(it >= 0L) },
                )
            }
        require(positions.map { it.locationId }.distinct().size == positions.size)
        val snapshot = ProductEditingSnapshot(original, positions, fields[23].toBooleanStrict(), CurrencyCode.of(fields[24]))
        val drafts =
            positions.mapIndexed { index, position ->
                InventoryBalanceDraft(position.locationId, fields[27 + index * 9 + 7], fields[27 + index * 9 + 8])
            }
        val selected = fields[25].takeIf(String::isNotEmpty)?.let { requireNotNull(LocationId.parse(it)) }
        require(selected == null || positions.any { it.locationId == selected })
        form.copy(inventorySnapshot = snapshot, inventoryBalanceDrafts = drafts).selectInventoryLocation(selected)
    }.getOrNull()

internal fun inventoryProductForm(
    product: Product,
    currency: CurrencyCode,
) = Form.ProductForm(
    productId = product.productId,
    title = product.name,
    sku = product.sku.orEmpty(),
    barcode = product.barcode.orEmpty(),
    unitId = product.unitId,
    locationId = product.locationId,
    purchaseUnitId = product.purchaseUnitId,
    purchaseFactor = product.purchaseFactor?.toPlainString().orEmpty(),
    salePrice =
        product.salePrice
            ?.toMajor()
            ?.toPlainString()
            .orEmpty(),
    isInventoryOrigin = true,
    saleCurrency = product.salePrice?.currency ?: currency,
    original = product,
)

internal fun Form.ProductForm.withInventorySnapshot(snapshot: ProductEditingSnapshot): Form.ProductForm {
    require(snapshot.positions.size <= 2000)
    val drafts =
        snapshot.positions.map { position ->
            InventoryBalanceDraft(position.locationId, position.displayQuantity(), position.displayUnitCost())
        }
    return copy(inventorySnapshot = snapshot, inventoryBalanceDrafts = drafts)
        .selectInventoryLocation(snapshot.positions.singleOrNull()?.locationId)
}

internal fun Form.ProductForm.selectInventoryLocation(selected: LocationId?): Form.ProductForm {
    val position = inventorySnapshot?.positions?.singleOrNull { it.locationId == selected }
    val draft = inventoryBalanceDrafts.singleOrNull { it.locationId == selected }
    return copy(
        locationId = position?.locationId,
        quantity = draft?.quantity.orEmpty(),
        purchasePrice = draft?.purchasePrice.orEmpty(),
        inventoryCurrency = position?.currency,
    )
}

internal fun ProductEditingPosition.displayQuantity(): String = if (balanceVersion == null) "" else quantityOnHand.toPlainString()

/** Quita sólo ceros decimales sobrantes; el costo original conserva su precisión en el snapshot. */
internal fun ProductEditingPosition.displayUnitCost(): String = averageUnitCost?.stripTrailingZeros()?.toPlainString().orEmpty()
