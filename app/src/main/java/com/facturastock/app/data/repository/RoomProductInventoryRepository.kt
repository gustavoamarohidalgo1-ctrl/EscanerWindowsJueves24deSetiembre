package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.InventoryDao
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.ProductInventoryPosition
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.InventoryStockAddition
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.usecase.InventoryCostingService
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.withContext

class RoomProductInventoryRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val inventoryDao: InventoryDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
    private val uuidGenerator: UuidGenerator,
) : ProductInventoryRepository {
    private val costingService = InventoryCostingService()
    override suspend fun summaryForProduct(
        businessId: BusinessId,
        productId: ProductId,
    ): ProductInventorySummary = withContext(dispatchers.io) {
        storageCatching {
            ProductInventorySummary(
                inventoryDao.listBalancesForProduct(businessId.value, productId.value).map { balance ->
                    val currency = CurrencyCode.of(balance.currencyCode)
                    ProductInventoryPosition(
                        locationId = LocationId.from(UUID.fromString(balance.locationId)),
                        quantityOnHand = BigDecimal(balance.quantityOnHand),
                        averageUnitCost = InventoryCostAmount(BigDecimal(balance.averageUnitCost), currency),
                    )
                },
            )
        }
    }

    override suspend fun setStock(
        businessId: BusinessId,
        productId: ProductId,
        locationId: LocationId,
        quantity: BigDecimal,
        currency: CurrencyCode,
    ) = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val existing = inventoryDao.findBalance(
                    businessId.value,
                    productId.value,
                    locationId.value,
                )
                val currentQty = existing?.let { BigDecimal(it.quantityOnHand) } ?: BigDecimal.ZERO
                val delta = quantity.subtract(currentQty)
                if (delta.signum() == 0) return@withTransaction
                applyAdjustment(
                    businessId = businessId,
                    productId = productId,
                    locationId = locationId,
                    quantityDelta = delta,
                    currency = currency,
                    unitCost = null,
                    idempotencyKey = null,
                )
            }
        }
    }

    override suspend fun addStock(
        businessId: BusinessId,
        productId: ProductId,
        locationId: LocationId,
        quantityToAdd: BigDecimal,
        currency: CurrencyCode,
        unitCost: BigDecimal?,
        idempotencyKey: String?,
    ) = withContext(dispatchers.io) {
        if (quantityToAdd.signum() == 0) return@withContext Unit
        storageCatching {
            database.withTransaction {
                applyAdjustment(
                    businessId = businessId,
                    productId = productId,
                    locationId = locationId,
                    quantityDelta = quantityToAdd,
                    currency = currency,
                    unitCost = unitCost,
                    idempotencyKey = idempotencyKey,
                )
                Unit
            }
        }
    }

    override suspend fun addStockBatch(
        businessId: BusinessId,
        currency: CurrencyCode,
        items: List<InventoryStockAddition>,
    ): Int = withContext(dispatchers.io) {
        if (items.isEmpty()) return@withContext 0
        storageCatching {
            database.withTransaction {
                var applied = 0
                for (item in items) {
                    if (
                        applyAdjustment(
                            businessId = businessId,
                            productId = item.productId,
                            locationId = item.locationId,
                            quantityDelta = item.quantityToAdd,
                            currency = currency,
                            unitCost = item.unitCost,
                            idempotencyKey = item.idempotencyKey,
                            appliedCostTotal = item.appliedCostTotal,
                        )
                    ) {
                        applied += 1
                    }
                }
                applied
            }
        }
    }

    /**
     * Escribe el saldo con CAS y un movimiento `ADJUSTMENT` en el mismo instante. Un CAS
     * perdido se reintenta; agotar los intentos falla cerrado en vez de anunciar un alta
     * que no quedó en disco.
     */
    private suspend fun applyAdjustment(
        businessId: BusinessId,
        productId: ProductId,
        locationId: LocationId,
        quantityDelta: BigDecimal,
        currency: CurrencyCode,
        unitCost: BigDecimal?,
        idempotencyKey: String?,
        appliedCostTotal: BigDecimal? = null,
    ): Boolean {
        if (quantityDelta.signum() == 0) return false
        val resolvedKey = idempotencyKey?.trim().orEmpty().ifEmpty {
            "inventory-adjustment:v1:${uuidGenerator.newUuid()}"
        }
        inventoryDao.findMovementByIdempotencyKey(resolvedKey)?.let { existing ->
            val matches = existing.businessId == businessId.value &&
                existing.productId == productId.value && existing.locationId == locationId.value &&
                existing.type == StockMovementType.ADJUSTMENT.name &&
                existing.currencyCode == currency.value &&
                existing.quantityDelta.toBigDecimal().compareTo(quantityDelta) == 0 &&
                ((existing.unitCost == null && unitCost == null) ||
                    (existing.unitCost != null && unitCost != null &&
                        existing.unitCost.toBigDecimal().compareTo(unitCost) == 0))
            if (!matches) throw StorageException(StorageError.ConstraintConflict("el reintento cambió el ingreso"))
            return true
        }

        repeat(MAX_CAS_ATTEMPTS) {
            val existing = inventoryDao.findBalance(
                businessId.value,
                productId.value,
                locationId.value,
            )
            if (existing != null && existing.currencyCode != currency.value) {
                throw StorageException(
                    StorageError.ConstraintConflict(
                        "el saldo existente usa ${existing.currencyCode}",
                    ),
                )
            }
            val occurredAt = maxOf(clock.now().toEpochMilli(), existing?.updatedAt ?: 0L)
            val openingQty = existing?.let { BigDecimal(it.quantityOnHand) } ?: BigDecimal.ZERO
            val resultingQty = openingQty.add(quantityDelta)
            val resultingAvg = resultingAverageCost(
                openingQty = openingQty,
                previousAverage = existing?.let { BigDecimal(it.averageUnitCost) } ?: BigDecimal.ZERO,
                quantityDelta = quantityDelta,
                unitCost = unitCost,
                appliedCostTotal = appliedCostTotal,
            )
            val persisted = if (existing == null) {
                val inserted = inventoryDao.insertBalanceIfAbsent(
                    InventoryBalanceEntity(
                        businessId = businessId.value,
                        productId = productId.value,
                        locationId = locationId.value,
                        quantityOnHand = resultingQty.toPlainString(),
                        averageUnitCost = resultingAvg,
                        currencyCode = currency.value,
                        version = 0L,
                        updatedAt = occurredAt,
                    ),
                )
                inserted != -1L
            } else {
                inventoryDao.updateBalanceIfVersion(
                    businessId = businessId.value,
                    productId = productId.value,
                    locationId = locationId.value,
                    expectedVersion = existing.version,
                    quantityOnHand = resultingQty.toPlainString(),
                    averageUnitCost = resultingAvg,
                    currencyCode = currency.value,
                    updatedAt = occurredAt,
                ) == 1
            }
            if (!persisted) return@repeat

            inventoryDao.insertMovements(
                listOf(
                    StockMovementEntity(
                        movementId = uuidGenerator.newUuid().toString(),
                        businessId = businessId.value,
                        productId = productId.value,
                        locationId = locationId.value,
                        type = StockMovementType.ADJUSTMENT.name,
                        quantityDelta = quantityDelta.toPlainString(),
                        unitCost = unitCost?.toPlainString(),
                        currencyCode = currency.value,
                        idempotencyKey = resolvedKey,
                        occurredAt = occurredAt,
                        createdAt = occurredAt,
                    ),
                ),
            )
            return true
        }
        throw StorageException(StorageError.Unavailable)
    }

    private fun resultingAverageCost(
        openingQty: BigDecimal,
        previousAverage: BigDecimal,
        quantityDelta: BigDecimal,
        unitCost: BigDecimal?,
        appliedCostTotal: BigDecimal? = null,
    ): String {
        if (quantityDelta.signum() <= 0 || unitCost == null) {
            return previousAverage.toPlainString()
        }
        val incomingTotal = appliedCostTotal ?: quantityDelta.multiply(unitCost)
        return when (
            val result = costingService.calculateAverage(
                InventoryAverageCostRequest(
                    previousQuantity = openingQty,
                    previousAverageUnitCost = previousAverage,
                    incomingInventoryQuantity = quantityDelta,
                    incomingAppliedCostTotal = incomingTotal,
                    roundingPolicy = AVERAGE_COST_ROUNDING,
                ),
            )
        ) {
            is InventoryAverageCostResult.Calculated ->
                result.calculation.resultingAverageUnitCost.toPlainString()
            is InventoryAverageCostResult.DecisionRequired ->
                throw StorageException(
                    StorageError.ConstraintConflict(
                        "el costo promedio no admite redondeo implícito",
                    ),
                )
        }
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 8
        val AVERAGE_COST_ROUNDING = InventoryCostRoundingPolicy(18, RoundingMode.HALF_EVEN)
    }
}
