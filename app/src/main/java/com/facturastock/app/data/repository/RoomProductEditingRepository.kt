package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.InventoryMovementReadRow
import com.facturastock.app.data.local.dao.updateCas
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.ProductEditingInvalidField
import com.facturastock.app.domain.repository.ProductEditingPosition
import com.facturastock.app.domain.repository.ProductEditingRepository
import com.facturastock.app.domain.repository.ProductEditingResult
import com.facturastock.app.domain.repository.ProductEditingSnapshot
import com.facturastock.app.domain.repository.ProductStockEdit
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale
import javax.inject.Inject

/** Edición local atómica. El libro anterior y las ventas publicadas nunca se reescriben. */
class RoomProductEditingRepository
    @Inject
    constructor(
        private val database: FacturaStockDatabase,
        private val dispatchers: DispatcherProvider,
        private val clock: AppClock,
        private val uuidGenerator: UuidGenerator,
        private val backupScheduler: PurchaseBackupScheduler = DisabledPurchaseBackupScheduler,
    ) : ProductEditingRepository {
        override suspend fun load(
            businessId: BusinessId,
            productId: ProductId,
            defaultCurrency: CurrencyCode,
        ): ProductEditingSnapshot? =
            withContext(dispatchers.io) {
                storageCatching { database.withTransaction { loadSnapshot(businessId, productId, defaultCurrency) } }
            }

        override suspend fun save(
            expected: ProductEditingSnapshot,
            candidate: Product,
            stockEdits: List<ProductStockEdit>,
        ): ProductEditingResult =
            withContext(dispatchers.io) {
                val result =
                    storageCatching {
                        database.withTransaction { saveInTransaction(expected, candidate, stockEdits) }
                    }
                if (result is ProductEditingResult.Saved && result.changed) backupScheduler.enqueueBestEffort()
                result
            }

        private suspend fun loadSnapshot(
            businessId: BusinessId,
            productId: ProductId,
            defaultCurrency: CurrencyCode,
        ): ProductEditingSnapshot? {
            val product =
                database
                    .productDao()
                    .findById(productId.value)
                    ?.takeIf { it.businessId == businessId.value } ?: return null
            val balances =
                database
                    .inventoryDao()
                    .listBalancesForProduct(businessId.value, productId.value)
                    .associateBy { it.locationId }
            val movements =
                if (balances.values.any { it.averageUnitCost.toBigDecimal().signum() == 0 }) {
                    database
                        .inventoryDao()
                        .listReadMovementsForProduct(businessId.value, productId.value)
                        .groupBy { it.locationId }
                } else {
                    // knownAverage sólo necesita evidencia histórica para distinguir cero
                    // explícito de costo desconocido. Los demás saldos ya contienen su costo.
                    emptyMap()
                }
            val locations = database.inventoryLocationDao().listForBusiness(businessId.value)
            val positions =
                locations
                    .filter { it.status == CatalogStatus.ACTIVE.name || it.locationId in balances }
                    .map { location ->
                        val balance = balances[location.locationId]
                        ProductEditingPosition(
                            locationId = requireNotNull(LocationId.parse(location.locationId)),
                            locationName = location.name,
                            locationStatus = CatalogStatus.valueOf(location.status),
                            quantityOnHand = balance?.quantityOnHand?.toBigDecimal() ?: BigDecimal.ZERO,
                            averageUnitCost = balance?.knownAverage(movements[location.locationId].orEmpty()),
                            currency = balance?.currencyCode?.let(CurrencyCode::of) ?: defaultCurrency,
                            balanceVersion = balance?.version,
                        )
                    }
            return ProductEditingSnapshot(
                product = product.toDomain(),
                positions = positions,
                inventoryEditable =
                    product.status == CatalogStatus.ACTIVE.name &&
                        database.businessDao().findById(businessId.value)?.status == CatalogStatus.ACTIVE.name &&
                        database.unitDao().findById(product.unitId)?.status == CatalogStatus.ACTIVE.name &&
                        database.cloudBusinessBindingDao().findByLocal(businessId.value) == null,
                defaultCurrency = defaultCurrency,
            )
        }

        private suspend fun saveInTransaction(
            expected: ProductEditingSnapshot,
            candidate: Product,
            stockEdits: List<ProductStockEdit>,
        ): ProductEditingResult {
            val original = expected.product
            if (candidate.productId != original.productId || candidate.businessId != original.businessId) {
                return invalid(ProductEditingInvalidField.OWNERSHIP)
            }
            // La ubicación del formulario selecciona un saldo; no cambia el almacén por defecto.
            if (candidate.copy(
                    name = original.name,
                    sku = original.sku,
                    barcode = original.barcode,
                    salePrice = original.salePrice,
                ) != original
            ) {
                return invalid(ProductEditingInvalidField.IMMUTABLE_FIELD)
            }
            val productDao = database.productDao()
            val current =
                productDao
                    .findById(original.productId.value)
                    ?.takeIf { it.businessId == original.businessId.value } ?: return ProductEditingResult.NotFound
            if (current.toDomain() != original) return ProductEditingResult.Stale
            if (database.businessDao().findById(original.businessId.value)?.status != CatalogStatus.ACTIVE.name) {
                return invalid(ProductEditingInvalidField.OWNERSHIP)
            }
            val name = if (candidate.name == original.name) current.name else candidate.name.trim()
            if (name.trim().length !in 1..200) return invalid(ProductEditingInvalidField.NAME)
            val sku = if (candidate.sku == original.sku) current.sku else CatalogCanonicalizer.sku(candidate.sku)
            if (sku != null && sku.length !in 1..64) return invalid(ProductEditingInvalidField.SKU)
            val barcode =
                if (candidate.barcode == original.barcode) {
                    current.barcode
                } else {
                    if (!BarcodeValue.isValidOptional(candidate.barcode)) return invalid(ProductEditingInvalidField.BARCODE)
                    BarcodeValue.optionalOf(candidate.barcode)?.value
                }
            if (candidate.salePrice != null && candidate.salePrice.currency !=
                (original.salePrice?.currency ?: expected.defaultCurrency)
            ) {
                return invalid(ProductEditingInvalidField.CURRENCY)
            }
            sku?.let { value ->
                if (productDao.findBySku(original.businessId.value, value)?.productId?.let { it != current.productId } == true) {
                    return ProductEditingResult.Duplicate(CatalogDuplicateField.SKU)
                }
            }
            barcode?.let { value ->
                if (productDao.findByBarcode(original.businessId.value, value)?.productId?.let { it != current.productId } == true) {
                    return ProductEditingResult.Duplicate(CatalogDuplicateField.BARCODE)
                }
            }
            val metadata =
                current.copy(
                    name = name,
                    normalizedName = if (name == current.name) current.normalizedName else name.lowercase(Locale.ROOT),
                    sku = sku,
                    barcode = barcode,
                    salePriceMinorUnits = candidate.salePrice?.minorUnits,
                    salePriceCurrencyCode = candidate.salePrice?.currency?.value,
                )
            val metadataChanged = metadata != current
            if (metadataChanged && current.version == Long.MAX_VALUE) return ProductEditingResult.Stale
            if (expected.positions
                    .map { it.locationId }
                    .distinct()
                    .size != expected.positions.size ||
                stockEdits.map { it.locationId }.distinct().size != stockEdits.size
            ) {
                return invalid(ProductEditingInvalidField.LOCATION)
            }

            val movements = database.inventoryDao().listReadMovementsForProduct(original.businessId.value, original.productId.value)
            val plans = mutableListOf<StockPlan>()
            for (edit in stockEdits.sortedBy { it.locationId.value }) {
                val position =
                    expected.positions.singleOrNull { it.locationId == edit.locationId }
                        ?: return invalid(ProductEditingInvalidField.LOCATION)
                if (!edit.quantityOnHand.isSupportedNonNegative()) return invalid(ProductEditingInvalidField.QUANTITY)
                if (edit.averageUnitCost?.isSupportedNonNegative() == false) return invalid(ProductEditingInvalidField.COST)
                val existing = database.inventoryDao().findBalance(original.businessId.value, original.productId.value, edit.locationId.value)
                if (existing?.version != position.balanceVersion ||
                    (existing == null && position.quantityOnHand.signum() != 0) ||
                    (
                        existing != null && (
                            !existing.quantityOnHand.toBigDecimal().same(position.quantityOnHand) ||
                                existing.currencyCode != position.currency.value ||
                                !existing.knownAverage(movements.filter { it.locationId == edit.locationId.value }).sameNullable(position.averageUnitCost)
                        )
                    )
                ) {
                    return ProductEditingResult.Stale
                }
                if (existing == null && position.currency != expected.defaultCurrency) return invalid(ProductEditingInvalidField.CURRENCY)
                val quantityChanged = !edit.quantityOnHand.same(position.quantityOnHand)
                val costChanged = edit.averageUnitCost != null && !edit.averageUnitCost.sameNullable(position.averageUnitCost)
                // Entradas nuevas usan 38/18 como UnitCost. Un promedio derivado 128/36 ya
                // almacenado puede conservarse intacto al corregir cantidad o metadatos.
                if (costChanged && !ExactDecimalPolicy.supportsValue(requireNotNull(edit.averageUnitCost))) {
                    return invalid(ProductEditingInvalidField.COST)
                }
                if (!quantityChanged && !costChanged) continue
                if (database.cloudBusinessBindingDao().findByLocal(original.businessId.value) != null) {
                    return invalid(ProductEditingInvalidField.SHARED_INVENTORY)
                }
                val location = database.inventoryLocationDao().findById(edit.locationId.value)
                if (location == null || location.businessId != original.businessId.value || location.status != CatalogStatus.ACTIVE.name) {
                    return invalid(ProductEditingInvalidField.LOCATION)
                }
                if (current.status != CatalogStatus.ACTIVE.name ||
                    database.unitDao().findById(current.unitId)?.status != CatalogStatus.ACTIVE.name
                ) {
                    return invalid(ProductEditingInvalidField.IMMUTABLE_FIELD)
                }
                if (existing?.version == Long.MAX_VALUE) return ProductEditingResult.Stale
                val targetCost = if (costChanged) edit.averageUnitCost else position.averageUnitCost
                if (edit.quantityOnHand > position.quantityOnHand && targetCost == null) return invalid(ProductEditingInvalidField.COST)
                val steps = adjustmentSteps(position, edit, costChanged, targetCost)
                if (steps.any { !InventoryCostingDecimalPolicy.supportsPersisted(it.delta) }) {
                    return invalid(ProductEditingInvalidField.QUANTITY)
                }
                plans +=
                    StockPlan(
                        position,
                        existing,
                        edit.quantityOnHand,
                        targetCost ?: existing?.averageUnitCost?.toBigDecimal() ?: BigDecimal.ZERO,
                        steps,
                    )
            }
            if (!metadataChanged && plans.isEmpty()) {
                return ProductEditingResult.Saved(requireNotNull(loadSnapshot(original.businessId, original.productId, expected.defaultCurrency)), false)
            }

            // Se calcula todo antes de escribir. Tras el primer write cualquier fallo debe lanzar
            // una excepción, para que Room revierta producto, saldos, ajustes y outbox juntos.
            var timestamp =
                maxOf(
                    clock.now().toEpochMilli(),
                    current.updatedAt,
                    movements.maxOfOrNull { maxOf(it.occurredAt, it.createdAt) } ?: 0L,
                    plans.maxOfOrNull { it.existing?.updatedAt ?: 0L } ?: 0L,
                )
            val persistedBalances = mutableListOf<InventoryBalanceEntity>()
            val appended = mutableListOf<StockMovementEntity>()
            for (plan in plans) {
                val newMovements =
                    plan.steps.mapIndexed { index, step ->
                        timestamp = Math.addExact(timestamp, 1L)
                        StockMovementEntity(
                            movementId = uuidGenerator.newUuid().toString(),
                            businessId = original.businessId.value,
                            productId = original.productId.value,
                            locationId = plan.position.locationId.value,
                            type = StockMovementType.ADJUSTMENT.name,
                            quantityDelta = step.delta.toPlainString(),
                            unitCost = step.cost?.toPlainString(),
                            currencyCode = plan.position.currency.value,
                            idempotencyKey = "product-edit:v1:${original.productId.value}:${plan.position.locationId.value}:${plan.position.balanceVersion ?: "absent"}:$index",
                            occurredAt = timestamp,
                            createdAt = timestamp,
                        )
                    }
                val balance =
                    InventoryBalanceEntity(
                        businessId = original.businessId.value,
                        productId = original.productId.value,
                        locationId = plan.position.locationId.value,
                        quantityOnHand = plan.quantity.toPlainString(),
                        averageUnitCost = plan.cost.toPlainString(),
                        currencyCode = plan.position.currency.value,
                        version = plan.existing?.version?.plus(1L) ?: 0L,
                        updatedAt = timestamp,
                    )
                val written =
                    if (plan.existing == null) {
                        database.inventoryDao().insertBalanceIfAbsent(balance) != -1L
                    } else {
                        database.inventoryDao().updateBalanceIfVersion(
                            balance.businessId,
                            balance.productId,
                            balance.locationId,
                            plan.existing.version,
                            balance.quantityOnHand,
                            balance.averageUnitCost,
                            balance.currencyCode,
                            balance.updatedAt,
                        ) == 1
                    }
                if (!written) throw StorageException(StorageError.Unavailable)
                database.inventoryDao().insertMovements(newMovements)
                persistedBalances += balance
                appended += newMovements
            }
            val storedProduct =
                if (metadataChanged) {
                    val requested = metadata.copy(updatedAt = maxOf(clock.now().toEpochMilli(), current.updatedAt))
                    if (productDao.updateCas(requested) != 1) throw StorageException(StorageError.Unavailable)
                    val stored = requested.copy(version = current.version + 1L)
                    insertCatalogOutbox(stored, current.version)
                    stored
                } else {
                    current
                }
            if (productDao.findById(current.productId) != storedProduct) throw StorageException(StorageError.Unavailable)
            for (balance in persistedBalances) {
                if (database.inventoryDao().findBalance(balance.businessId, balance.productId, balance.locationId) != balance) {
                    throw StorageException(StorageError.Unavailable)
                }
            }
            for (movement in appended) {
                if (database.inventoryDao().findMovementById(movement.movementId) != movement) throw StorageException(StorageError.Unavailable)
            }
            return ProductEditingResult.Saved(requireNotNull(loadSnapshot(original.businessId, original.productId, expected.defaultCurrency)), true)
        }

        private suspend fun insertCatalogOutbox(
            product: ProductEntity,
            expectedVersion: Long,
        ) {
            val link = database.catalogSyncLinkDao().findByLocal(product.businessId, PRODUCT_ENTITY_TYPE, product.productId)
            database.outboxOperationDao().insert(
                CatalogSyncOutbox.product(
                    entity = product,
                    expectedVersion = expectedVersion,
                    inventoryUnit = requireNotNull(database.unitDao().findById(product.unitId)),
                    purchaseUnit = product.purchaseUnitId?.let { requireNotNull(database.unitDao().findById(it)) },
                    location = product.locationId?.let { requireNotNull(database.inventoryLocationDao().findById(it)) },
                    remoteEntityId = link?.remoteEntityId,
                ),
            )
        }

        /** Un cero de la proyección sin evidencia de costo no pasa a ser un cero confirmado. */
        private fun InventoryBalanceEntity.knownAverage(movements: List<InventoryMovementReadRow>): BigDecimal? {
            val stored = averageUnitCost.toBigDecimal()
            if (stored.signum() != 0) return stored
            var quantity = BigDecimal.ZERO
            var known = false
            for (movement in movements) {
                val delta = movement.quantityDelta.toBigDecimal()
                if (delta.signum() > 0) {
                    known = if (quantity.signum() <= 0) movement.unitCost != null else known && movement.unitCost != null
                }
                quantity += delta
            }
            return stored.takeIf { known }
        }

        /** Corregir el promedio retira y reingresa el saldo, nunca reescribe su procedencia. */
        private fun adjustmentSteps(
            position: ProductEditingPosition,
            edit: ProductStockEdit,
            costChanged: Boolean,
            targetCost: BigDecimal?,
        ): List<AdjustmentStep> =
            buildList {
                if (!costChanged) {
                    add(AdjustmentStep(edit.quantityOnHand - position.quantityOnHand, targetCost))
                } else {
                    if (position.quantityOnHand.signum() != 0) {
                        add(AdjustmentStep(-position.quantityOnHand, if (position.quantityOnHand.signum() > 0) position.averageUnitCost else targetCost))
                    }
                    if (edit.quantityOnHand.signum() > 0) {
                        add(AdjustmentStep(edit.quantityOnHand, targetCost))
                    } else {
                        // Stock cero también conserva un costo editable. El par técnico tiene suma
                        // cero y orden inequívoco; ambos pasos son ADJUSTMENT, no compras ni ventas.
                        add(AdjustmentStep(BigDecimal.ONE, targetCost))
                        add(AdjustmentStep(BigDecimal.ONE.negate(), targetCost))
                    }
                }
            }

        private fun invalid(field: ProductEditingInvalidField) = ProductEditingResult.Invalid(field)

        private fun BigDecimal.isSupportedNonNegative() = signum() >= 0 && InventoryCostingDecimalPolicy.supportsPersisted(this)

        private fun BigDecimal.same(other: BigDecimal) = compareTo(other) == 0

        private fun BigDecimal?.sameNullable(other: BigDecimal?) = if (this == null || other == null) this == other else same(other)

        private data class AdjustmentStep(
            val delta: BigDecimal,
            val cost: BigDecimal?,
        )

        private data class StockPlan(
            val position: ProductEditingPosition,
            val existing: InventoryBalanceEntity?,
            val quantity: BigDecimal,
            val cost: BigDecimal,
            val steps: List<AdjustmentStep>,
        )
    }
