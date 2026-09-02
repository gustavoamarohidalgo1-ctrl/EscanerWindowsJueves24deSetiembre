package com.facturastock.app.data.repository

import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.OutboxOperationStatus
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal const val SYNC_PRODUCT = "SYNC_PRODUCT"
internal const val SYNC_SUPPLIER = "SYNC_SUPPLIER"
internal const val PRODUCT_ENTITY_TYPE = "PRODUCT"
internal const val SUPPLIER_ENTITY_TYPE = "SUPPLIER"
internal const val CATALOG_PRODUCT_SYNC_PAYLOAD_VERSION = 2
internal const val CATALOG_SUPPLIER_SYNC_PAYLOAD_VERSION = 1

/** Construye snapshots canónicos, mínimos e inmutables para la outbox de catálogo. */
internal object CatalogSyncOutbox {
    fun product(
        entity: ProductEntity,
        expectedVersion: Long,
        inventoryUnit: UnitEntity,
        purchaseUnit: UnitEntity?,
        location: InventoryLocationEntity?,
        remoteEntityId: String? = null,
    ): OutboxOperationEntity {
        requireTargetVersion(expectedVersion, entity.version)
        require(inventoryUnit.unitId == entity.unitId)
        require(purchaseUnit?.unitId == entity.purchaseUnitId)
        require(location?.locationId == entity.locationId)
        require(inventoryUnit.businessId == entity.businessId)
        require(purchaseUnit == null || purchaseUnit.businessId == entity.businessId)
        require(location == null || location.businessId == entity.businessId)
        val wireEntityId = remoteEntityId ?: entity.productId
        val idempotencyKey = idempotencyKey(
            PRODUCT_ENTITY_TYPE,
            wireEntityId,
            entity.version,
            CATALOG_PRODUCT_SYNC_PAYLOAD_VERSION,
        )
        return operation(
            businessId = entity.businessId,
            entityType = PRODUCT_ENTITY_TYPE,
            entityId = entity.productId,
            entityVersion = entity.version,
            remoteEntityId = remoteEntityId,
            idempotencyKey = idempotencyKey,
            operationType = SYNC_PRODUCT,
            payload = productPayload(
                entity = entity,
                expectedVersion = expectedVersion,
                inventoryUnit = inventoryUnit,
                purchaseUnit = purchaseUnit,
                location = location,
                wireEntityId = wireEntityId,
            ),
            payloadVersion = CATALOG_PRODUCT_SYNC_PAYLOAD_VERSION,
            timestamp = entity.updatedAt,
        )
    }

    fun supplier(
        entity: SupplierEntity,
        expectedVersion: Long,
        remoteEntityId: String? = null,
    ): OutboxOperationEntity {
        requireTargetVersion(expectedVersion, entity.version)
        val wireEntityId = remoteEntityId ?: entity.supplierId
        val idempotencyKey = idempotencyKey(
            SUPPLIER_ENTITY_TYPE,
            wireEntityId,
            entity.version,
            CATALOG_SUPPLIER_SYNC_PAYLOAD_VERSION,
        )
        return operation(
            businessId = entity.businessId,
            entityType = SUPPLIER_ENTITY_TYPE,
            entityId = entity.supplierId,
            entityVersion = entity.version,
            remoteEntityId = remoteEntityId,
            idempotencyKey = idempotencyKey,
            operationType = SYNC_SUPPLIER,
            payload = supplierPayload(entity, expectedVersion, wireEntityId),
            payloadVersion = CATALOG_SUPPLIER_SYNC_PAYLOAD_VERSION,
            timestamp = entity.updatedAt,
        )
    }

    private fun operation(
        businessId: String,
        entityType: String,
        entityId: String,
        entityVersion: Long,
        remoteEntityId: String?,
        idempotencyKey: String,
        operationType: String,
        payload: String,
        payloadVersion: Int,
        timestamp: Long,
    ): OutboxOperationEntity = OutboxOperationEntity(
        operationId = deterministicUuid(idempotencyKey).toString(),
        businessId = businessId,
        purchaseId = null,
        entityType = entityType,
        entityId = entityId,
        entityVersion = entityVersion,
        remoteEntityId = remoteEntityId,
        idempotencyKey = idempotencyKey,
        operationType = operationType,
        payload = payload,
        status = OutboxOperationStatus.PENDING.name,
        attemptCount = 0,
        createdAt = timestamp,
        updatedAt = timestamp,
        payloadVersion = payloadVersion,
    )

    private fun productPayload(
        entity: ProductEntity,
        expectedVersion: Long,
        inventoryUnit: UnitEntity,
        purchaseUnit: UnitEntity?,
        location: InventoryLocationEntity?,
        wireEntityId: String,
    ): String = buildString {
        append("{\"version\":2")
        append(",\"entityId\":\"").append(wireEntityId).append('"')
        append(",\"expectedVersion\":").append(expectedVersion)
        append(",\"targetVersion\":").append(entity.version)
        append(",\"mutation\":\"UPSERT\"")
        append(",\"snapshot\":")
        append(CatalogSnapshotCodec.encode(entity.toSnapshot(inventoryUnit, purchaseUnit, location)))
        append('}')
    }

    private fun supplierPayload(
        entity: SupplierEntity,
        expectedVersion: Long,
        wireEntityId: String,
    ): String = buildString {
        append("{\"version\":1")
        append(",\"entityId\":\"").append(wireEntityId).append('"')
        append(",\"expectedVersion\":").append(expectedVersion)
        append(",\"targetVersion\":").append(entity.version)
        append(",\"mutation\":\"UPSERT\"")
        append(",\"snapshot\":")
        append(CatalogSnapshotCodec.encode(entity.toSnapshot()))
        append('}')
    }

    private fun requireTargetVersion(expectedVersion: Long, targetVersion: Long) {
        require(expectedVersion >= 0L) { "expectedVersion no puede ser negativo" }
        require(expectedVersion < Long.MAX_VALUE) { "La versión de catálogo se agotó" }
        require(targetVersion == expectedVersion + 1L) {
            "targetVersion debe ser expectedVersion + 1"
        }
    }

    fun rebind(operation: OutboxOperationEntity, remoteEntityId: String): OutboxOperationEntity {
        require(operation.entityType in setOf(PRODUCT_ENTITY_TYPE, SUPPLIER_ENTITY_TYPE))
        val previousWireId = operation.remoteEntityId ?: operation.entityId
        val previousField = "\"entityId\":\"$previousWireId\""
        val reboundField = "\"entityId\":\"$remoteEntityId\""
        require(operation.payload.countOccurrences(previousField) == 1) {
            "Payload de catálogo sin una identidad única"
        }
        return operation.copy(
            remoteEntityId = remoteEntityId,
            idempotencyKey = idempotencyKey(
                operation.entityType,
                remoteEntityId,
                operation.entityVersion,
                operation.payloadVersion,
            ),
            payload = operation.payload.replaceFirst(previousField, reboundField),
        )
    }

    private fun idempotencyKey(
        entityType: String,
        wireEntityId: String,
        version: Long,
        payloadVersion: Int,
    ): String =
        when (entityType) {
            PRODUCT_ENTITY_TYPE -> "sync-product:v$payloadVersion:$wireEntityId:$version"
            SUPPLIER_ENTITY_TYPE -> "sync-supplier:v$payloadVersion:$wireEntityId:$version"
            else -> error("Tipo de catálogo no soportado: $entityType")
        }

    /** UUID v5-like estable; un reintento local reconstruye exactamente la misma operación. */
    private fun deterministicUuid(idempotencyKey: String): UUID {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(idempotencyKey.toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}

private fun String.countOccurrences(value: String): Int {
    var count = 0
    var from = 0
    while (true) {
        val found = indexOf(value, from)
        if (found < 0) return count
        count++
        from = found + value.length
    }
}

internal fun ProductEntity.toSnapshot(
    inventoryUnit: UnitEntity,
    purchaseUnit: UnitEntity?,
    location: InventoryLocationEntity?,
) = CatalogProductSnapshot(
    name = name,
    sku = sku,
    barcode = barcode,
    salePriceMinorUnits = salePriceMinorUnits,
    salePriceCurrencyCode = salePriceCurrencyCode,
    inventoryUnit = inventoryUnit.toSnapshot(),
    purchaseUnit = purchaseUnit?.toSnapshot(),
    purchaseFactor = purchaseFactor,
    location = location?.let { CatalogLocationSnapshot(it.name, it.status) },
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun SupplierEntity.toSnapshot() = CatalogSupplierSnapshot(
    legalName = legalName,
    ruc = ruc,
    tradeName = tradeName,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** Extrae exclusivamente el snapshot interno de un payload local generado por este builder. */
internal fun OutboxOperationEntity.catalogSnapshotPayload(): String? {
    return payload.catalogSnapshotPayload()
}

/** Variante para proyecciones que cargan el sobre únicamente cuando la UI debe compararlo. */
internal fun String.catalogSnapshotPayload(): String? {
    val marker = "\"snapshot\":"
    val markerIndex = indexOf(marker)
    if (markerIndex < 0 || !endsWith('}')) return null
    return substring(markerIndex + marker.length, length - 1)
}

private fun UnitEntity.toSnapshot() = CatalogUnitSnapshot(
    code = code,
    name = name,
    symbol = symbol,
    status = status,
)
