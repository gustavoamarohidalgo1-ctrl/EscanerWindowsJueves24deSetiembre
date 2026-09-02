package com.facturastock.app.data.local.mapper

import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Conversiones entidad ↔ dominio de los catálogos. Las entidades ya validan sus invariantes en
 * el `init`, por lo que los IDs persistidos siempre son UUID canónicos y los enums vienen por
 * nombre. Los campos derivados de persistencia (`normalizedName`, `aliasNormalized`) se
 * recalculan en la entidad y nunca cruzan al dominio.
 */

internal fun BusinessEntity.toDomain(): Business = Business(
    businessId = BusinessId.from(UUID.fromString(businessId)),
    legalName = legalName,
    ruc = ruc,
    tradeName = tradeName,
    status = CatalogStatus.valueOf(status),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun Business.toEntity(): BusinessEntity = BusinessEntity(
    businessId = businessId.value,
    legalName = legalName,
    ruc = ruc,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    tradeName = tradeName,
    status = status.name,
)

internal fun SupplierEntity.toDomain(): Supplier = Supplier(
    supplierId = SupplierId.from(UUID.fromString(supplierId)),
    businessId = BusinessId.from(UUID.fromString(businessId)),
    legalName = legalName,
    ruc = ruc,
    tradeName = tradeName,
    status = CatalogStatus.valueOf(status),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    version = version,
)

internal fun Supplier.toEntity(): SupplierEntity = SupplierEntity(
    supplierId = supplierId.value,
    businessId = businessId.value,
    legalName = legalName,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    ruc = CatalogCanonicalizer.ruc(ruc),
    tradeName = tradeName,
    status = status.name,
    version = version,
)

internal fun UnitEntity.toDomain(): UnitOfMeasure = UnitOfMeasure(
    unitId = UnitId.from(UUID.fromString(unitId)),
    businessId = BusinessId.from(UUID.fromString(businessId)),
    code = code,
    name = name,
    symbol = symbol,
    status = CatalogStatus.valueOf(status),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun UnitOfMeasure.toEntity(): UnitEntity = UnitEntity(
    unitId = unitId.value,
    businessId = businessId.value,
    code = CatalogCanonicalizer.unitCode(code),
    name = name,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    symbol = symbol,
    status = status.name,
)

internal fun InventoryLocationEntity.toDomain(): InventoryLocation = InventoryLocation(
    locationId = LocationId.from(UUID.fromString(locationId)),
    businessId = BusinessId.from(UUID.fromString(businessId)),
    name = name,
    status = CatalogStatus.valueOf(status),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun InventoryLocation.toEntity(): InventoryLocationEntity = InventoryLocationEntity(
    locationId = locationId.value,
    businessId = businessId.value,
    name = name,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    status = status.name,
)

internal fun ProductEntity.toDomain(): Product = Product(
    productId = ProductId.from(UUID.fromString(productId)),
    businessId = BusinessId.from(UUID.fromString(businessId)),
    unitId = UnitId.from(UUID.fromString(unitId)),
    name = name,
    locationId = locationId?.let { LocationId.from(UUID.fromString(it)) },
    sku = CatalogCanonicalizer.sku(sku),
    // Lectura compatible: v1..v20 permitían Unicode. No se recanonicaliza ni se pierde el valor;
    // cualquier escritura posterior vuelve a pasar por el mapper estricto Product.toEntity().
    barcode = barcode,
    purchaseUnitId = purchaseUnitId?.let { UnitId.from(UUID.fromString(it)) },
    purchaseFactor = purchaseFactor?.let(::BigDecimal),
    salePrice = salePriceMinorUnits?.let { minorUnits ->
        Money.ofMinor(minorUnits, CurrencyCode.of(requireNotNull(salePriceCurrencyCode)))
    },
    status = CatalogStatus.valueOf(status),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    version = version,
)

internal fun Product.toEntity(): ProductEntity = ProductEntity(
    productId = productId.value,
    businessId = businessId.value,
    unitId = unitId.value,
    name = name,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    locationId = locationId?.value,
    sku = CatalogCanonicalizer.sku(sku),
    barcode = CatalogCanonicalizer.barcode(barcode),
    // normalizedName se deriva de name en la entidad; no se pasa explícitamente.
    purchaseUnitId = purchaseUnitId?.value,
    purchaseFactor = purchaseFactor?.toPlainString(),
    salePriceMinorUnits = salePrice?.minorUnits,
    salePriceCurrencyCode = salePrice?.currency?.value,
    status = status.name,
    version = version,
)

internal fun SupplierProductAliasEntity.toDomain(): SupplierProductAlias = SupplierProductAlias(
    aliasId = AliasId.from(UUID.fromString(aliasId)),
    businessId = BusinessId.from(UUID.fromString(businessId)),
    supplierId = SupplierId.from(UUID.fromString(supplierId)),
    productId = ProductId.from(UUID.fromString(productId)),
    alias = alias,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun SupplierProductAlias.toEntity(): SupplierProductAliasEntity = SupplierProductAliasEntity(
    aliasId = aliasId.value,
    businessId = businessId.value,
    supplierId = supplierId.value,
    productId = productId.value,
    alias = alias,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    // aliasNormalized se deriva de alias en la entidad; no se pasa explícitamente.
)
