package com.facturastock.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.DebtEntity
import com.facturastock.app.data.local.entity.DebtPaymentEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.SaleVoidEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.AuditPayloadPolicy
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import com.facturastock.app.domain.repository.SaleVoidLine
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidRepository
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.domain.usecase.InventoryCostingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject

/** El recibo separado compensa stock y retira las proyecciones sin reescribir historia contable. */
class RoomSaleVoidRepository
    @Inject
    constructor(
        private val database: FacturaStockDatabase,
        private val appClock: AppClock,
        private val dispatchers: DispatcherProvider,
        private val configuration: AppConfigurationRepository,
        private val authorization: PurchaseOverrideAuthorizationRepository,
    ) : SaleVoidRepository {
        override suspend fun preview(
            businessId: BusinessId,
            saleId: SaleId,
        ): SaleVoidPreviewResult =
            withContext(dispatchers.io) {
                try {
                    database.withTransaction {
                        val snapshot = load(businessId, saleId)
                        validateContext(businessId, snapshot.actor)
                        SaleVoidPreviewResult.Ready(snapshot.preview)
                    }
                } catch (rejected: Rejected) {
                    rejected.result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    SaleVoidPreviewResult.InvalidHistory
                } catch (_: ArithmeticException) {
                    SaleVoidPreviewResult.InvalidHistory
                } catch (failure: SQLiteFullException) {
                    throw StorageException(StorageError.InsufficientSpace, failure)
                } catch (failure: SQLiteException) {
                    throw StorageException(StorageError.Unavailable, failure)
                }
            }

        override suspend fun confirm(preview: SaleVoidPreview): SaleVoidResult =
            withContext(dispatchers.io) {
                try {
                    database.withTransaction {
                        val snapshot = load(preview.businessId, preview.saleId)
                        // También compara lo mostrado: un objeto alterado no autoriza otro impacto.
                        if (snapshot.preview != preview) return@withTransaction SaleVoidResult.Stale
                        validateContext(preview.businessId, snapshot.actor)
                        commit(snapshot)
                        SaleVoidResult.Voided
                    }
                } catch (rejected: Rejected) {
                    rejected.result.toConfirmResult()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: BalanceRace) {
                    SaleVoidResult.Stale
                } catch (_: SQLiteConstraintException) {
                    // La transacción ya revirtió movimientos, saldo y auditoría antes de clasificar.
                    if (database.saleVoidDao().findBySaleId(preview.businessId.value, preview.saleId.value) != null) {
                        SaleVoidResult.AlreadyVoided
                    } else {
                        SaleVoidResult.InvalidHistory
                    }
                } catch (_: IllegalArgumentException) {
                    SaleVoidResult.InvalidHistory
                } catch (_: ArithmeticException) {
                    SaleVoidResult.InvalidHistory
                } catch (failure: SQLiteFullException) {
                    throw StorageException(StorageError.InsufficientSpace, failure)
                } catch (failure: SQLiteException) {
                    throw StorageException(StorageError.Unavailable, failure)
                }
            }

        private suspend fun validateContext(
            businessId: BusinessId,
            expectedActor: PurchaseOverrideActor? = null,
        ): PurchaseOverrideActor {
            if (configuration.current().activeBusinessId != businessId) reject(SaleVoidPreviewResult.NoActiveBusiness)
            val business = database.businessDao().findById(businessId.value)
            if (business?.status != CatalogStatus.ACTIVE.name) reject(SaleVoidPreviewResult.NoActiveBusiness)
            if (database.cloudBusinessBindingDao().findByLocal(businessId.value) != null) {
                reject(SaleVoidPreviewResult.SharedBusinessUnsupported)
            }
            val actor =
                authorization
                    .currentActor(businessId)
                    ?.takeIf { it.canVoidPurchase } ?: reject(SaleVoidPreviewResult.Unauthorized)
            if (expectedActor != null && actor != expectedActor) reject(SaleVoidPreviewResult.Unauthorized)
            // El proveedor de autorización puede suspender y permitir un cambio de negocio.
            if (configuration.current().activeBusinessId != businessId) reject(SaleVoidPreviewResult.NoActiveBusiness)
            return actor
        }

        private suspend fun load(
            businessId: BusinessId,
            saleId: SaleId,
        ): Snapshot {
            val actor = validateContext(businessId)
            val graph =
                database.saleDao().findWithLines(saleId.value)
                    ?: reject(SaleVoidPreviewResult.NotFound)
            val sale = graph.sale
            if (sale.businessId != businessId.value || sale.status != SaleStatus.POSTED.name) {
                reject(SaleVoidPreviewResult.NotFound)
            }
            if (database.saleVoidDao().findBySaleId(businessId.value, saleId.value) != null) {
                reject(SaleVoidPreviewResult.AlreadyVoided)
            }
            val lines = graph.lines.sortedBy(SaleLineEntity::position)
            require(lines.isNotEmpty() && sale.postedAt != null && sale.totalMinorUnits >= 0L)
            require(lines.all { it.saleId == sale.saleId && it.currencyCode == sale.currencyCode })
            require(lines.map { it.position }.distinct().size == lines.size)
            require(lines.map { it.productId to it.locationId }.distinct().size == lines.size)
            require(sale.contentHash == SaleContentIdentity.hash(sale.currencyCode, lines))
            require(lines.fold(0L) { sum, line -> Math.addExact(sum, requireNotNull(line.lineTotalMinorUnits)) } == sale.totalMinorUnits)
            val movements = database.inventoryDao().listMovementsForSale(businessId.value, saleId.value)
            require(movements.size == lines.size)
            val byLine = movements.groupBy(StockMovementEntity::saleLineId)
            lines.forEach { line ->
                val movement = requireNotNull(byLine[line.saleLineId]).single()
                require(movement.type == StockMovementType.SALE.name)
                require(movement.businessId == sale.businessId && movement.saleId == sale.saleId)
                require(movement.productId == line.productId && movement.locationId == line.locationId)
                require(movement.purchaseId == null && movement.purchaseLineId == null)
                require(movement.currencyCode == sale.currencyCode && movement.unitCost != null)
                require(BigDecimal(movement.quantityDelta).compareTo(BigDecimal(line.quantity).negate()) == 0)
                require(movement.occurredAt == sale.postedAt)
                require(movement.idempotencyKey == "sale-stock:v1:${sale.saleId}:${line.saleLineId}")
            }
            val changes =
                movements
                    .groupBy { it.productId to it.locationId }
                    .entries
                    .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                    .map { (key, originals) ->
                        val balance = requireNotNull(database.inventoryDao().findBalance(sale.businessId, key.first, key.second))
                        require(balance.currencyCode == sale.currencyCode && balance.version < Long.MAX_VALUE)
                        val incoming =
                            originals.fold(BigDecimal.ZERO) { sum, movement ->
                                sum.subtract(BigDecimal(movement.quantityDelta))
                            }
                        val cost =
                            originals.fold(BigDecimal.ZERO) { sum, movement ->
                                sum.add(BigDecimal(movement.quantityDelta).negate().multiply(BigDecimal(requireNotNull(movement.unitCost))))
                            }
                        val average =
                            InventoryCostingService().calculateAverage(
                                InventoryAverageCostRequest(
                                    previousQuantity = BigDecimal(balance.quantityOnHand),
                                    previousAverageUnitCost = BigDecimal(balance.averageUnitCost),
                                    incomingInventoryQuantity = incoming,
                                    incomingAppliedCostTotal = cost,
                                    roundingPolicy = InventoryCostRoundingPolicy(18, RoundingMode.HALF_EVEN),
                                ),
                            )
                        require(average is InventoryAverageCostResult.Calculated)
                        val resulting =
                            balance.copy(
                                quantityOnHand = average.calculation.resultingQuantity.toPlainString(),
                                averageUnitCost = average.calculation.resultingAverageUnitCost.toPlainString(),
                                version = balance.version + 1L,
                            )
                        BalanceChange(
                            balance,
                            resulting,
                            database.inventoryDao().latestMovementTimestamp(
                                sale.businessId,
                                key.first,
                                key.second,
                            ) ?: 0L,
                        )
                    }
            val debt = database.debtDao().findDebtForSale(sale.saleId)
            val payments =
                debt
                    ?.let { database.debtDao().findPaymentsForDebt(it.debtId) }
                    .orEmpty()
                    .sortedBy(DebtPaymentEntity::expectedDebtVersion)
            validateDebt(sale, debt, payments)
            val currency = CurrencyCode.of(sale.currencyCode)
            val latestAt =
                maxOf(
                    sale.updatedAt,
                    requireNotNull(sale.postedAt),
                    movements.maxOf { maxOf(it.createdAt, it.occurredAt) },
                    changes.maxOf { maxOf(it.before.updatedAt, it.latestMovementAt) },
                    debt?.updatedAt ?: 0L,
                    payments.maxOfOrNull { maxOf(it.createdAt, it.occurredAt) } ?: 0L,
                )
            require(latestAt < Long.MAX_VALUE)
            return Snapshot(
                sale,
                movements,
                changes,
                actor,
                latestAt,
                SaleVoidPreview(
                    businessId,
                    saleId,
                    Instant.ofEpochMilli(requireNotNull(sale.postedAt)),
                    total = Money.ofMinor(sale.totalMinorUnits, currency),
                    refundAmount = Money.ofMinor(sale.totalMinorUnits - (debt?.balanceMinorUnits ?: 0L), currency),
                    debtBalanceToCancel = debt?.let { Money.ofMinor(it.balanceMinorUnits, currency) },
                    lines =
                        lines.map {
                            SaleVoidLine(
                                it.productNameSnapshot,
                                it.locationNameSnapshot,
                                it.unitCodeSnapshot,
                                Quantity.of(it.quantity),
                            )
                        },
                    impactHash = seal(sale, lines, movements, changes, debt, payments, actor),
                ),
            )
        }

        private fun validateDebt(
            sale: SaleEntity,
            debt: DebtEntity?,
            payments: List<DebtPaymentEntity>,
        ) {
            if (debt == null) {
                require(payments.isEmpty())
                return
            }
            require(debt.businessId == sale.businessId && debt.saleId == sale.saleId)
            require(debt.currencyCode == sale.currencyCode && debt.originalAmountMinorUnits == sale.totalMinorUnits)
            require(debt.createdAt == sale.postedAt)
            var balance = debt.originalAmountMinorUnits
            var version = 1L
            var updatedAt = debt.createdAt
            payments.forEach { payment ->
                require(payment.debtId == debt.debtId && payment.businessId == sale.businessId)
                require(payment.currencyCode == sale.currencyCode && payment.expectedDebtVersion == version)
                require(payment.amountMinorUnits <= balance && payment.createdAt >= updatedAt)
                balance -= payment.amountMinorUnits
                require(payment.balanceAfterMinorUnits == balance)
                version = Math.incrementExact(version)
                updatedAt = payment.createdAt
            }
            require(balance == debt.balanceMinorUnits && version == debt.version && updatedAt == debt.updatedAt)
        }

        private suspend fun commit(snapshot: Snapshot) {
            val sale = snapshot.sale
            val voidedAt = maxOf(appClock.now().toEpochMilli(), snapshot.latestAt + 1L)
            database.inventoryDao().insertMovements(
                snapshot.movements.map { original ->
                    original.copy(
                        movementId =
                            SaleContentIdentity
                                .uuid(
                                    "sale-void-stock-movement",
                                    sale.saleId,
                                    requireNotNull(original.saleLineId),
                                ).toString(),
                        type = StockMovementType.SALE_VOID.name,
                        quantityDelta = BigDecimal(original.quantityDelta).negate().toPlainString(),
                        idempotencyKey = "sale-void-stock:v1:${sale.saleId}:${original.saleLineId}",
                        occurredAt = voidedAt,
                        createdAt = voidedAt,
                    )
                },
            )
            snapshot.balances.forEach { change ->
                val before = change.before
                if (database.inventoryDao().updateBalanceIfVersion(
                        before.businessId,
                        before.productId,
                        before.locationId,
                        before.version,
                        change.after.quantityOnHand,
                        change.after.averageUnitCost,
                        before.currencyCode,
                        voidedAt,
                    ) != 1
                ) {
                    throw BalanceRace()
                }
            }
            database.auditEventDao().insert(
                AuditEventEntity(
                    auditEventId = SaleContentIdentity.uuid("sale-voided-audit", sale.saleId).toString(),
                    businessId = sale.businessId,
                    eventType = AuditEventType.SALE_VOIDED.name,
                    entityType = "SALE",
                    entityId = sale.saleId,
                    payload =
                        AuditPayloadPolicy.encode(
                            AuditEventType.SALE_VOIDED,
                            mapOf("version" to "1", "actorRole" to snapshot.actor.role.name),
                        ),
                    occurredAt = voidedAt,
                ),
            )
            // El trigger del recibo verifica que la compensación y su auditoría ya están completas.
            database.saleVoidDao().insert(
                SaleVoidEntity(
                    saleId = sale.saleId,
                    businessId = sale.businessId,
                    impactHash = snapshot.preview.impactHash,
                    refundedAmountMinorUnits = snapshot.preview.refundAmount.minorUnits,
                    cancelledDebtBalanceMinorUnits = snapshot.preview.debtBalanceToCancel?.minorUnits ?: 0L,
                    currencyCode = sale.currencyCode,
                    actorId = snapshot.actor.actorId,
                    actorRole = snapshot.actor.role.name,
                    voidedAt = voidedAt,
                ),
            )
        }

        private data class BalanceChange(
            val before: InventoryBalanceEntity,
            val after: InventoryBalanceEntity,
            val latestMovementAt: Long,
        )

        private data class Snapshot(
            val sale: SaleEntity,
            val movements: List<StockMovementEntity>,
            val balances: List<BalanceChange>,
            val actor: PurchaseOverrideActor,
            val latestAt: Long,
            val preview: SaleVoidPreview,
        )

        private fun seal(
            sale: SaleEntity,
            lines: List<SaleLineEntity>,
            movements: List<StockMovementEntity>,
            balances: List<BalanceChange>,
            debt: DebtEntity?,
            payments: List<DebtPaymentEntity>,
            actor: PurchaseOverrideActor,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")

            fun add(vararg values: Any?) {
                values.forEach { value ->
                    val bytes = value?.toString()?.toByteArray(Charsets.UTF_8)
                    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
                    if (bytes != null) digest.update(bytes)
                }
            }
            add(
                "sale-void-impact-v1",
                actor.actorId,
                actor.role,
                "average:18:HALF_EVEN",
                sale.saleId,
                sale.businessId,
                sale.status,
                sale.currencyCode,
                sale.subtotalMinorUnits,
                sale.discountMinorUnits,
                sale.taxMinorUnits,
                sale.totalMinorUnits,
                sale.contentHash,
                sale.checkoutIdempotencyKey,
                sale.version,
                sale.createdAt,
                sale.updatedAt,
                sale.postedAt,
                lines.size,
            )
            lines.forEach {
                add(
                    it.saleLineId,
                    it.saleId,
                    it.productId,
                    it.unitId,
                    it.locationId,
                    it.position,
                    it.productNameSnapshot,
                    it.unitCodeSnapshot,
                    it.locationNameSnapshot,
                    it.barcodeSnapshot,
                    it.quantity,
                    it.unitPriceMinorUnits,
                    it.discountMinorUnits,
                    it.taxMinorUnits,
                    it.lineTotalMinorUnits,
                    it.currencyCode,
                )
            }
            add(movements.size)
            movements.sortedBy(StockMovementEntity::movementId).forEach {
                add(
                    it.movementId,
                    it.businessId,
                    it.purchaseId,
                    it.purchaseLineId,
                    it.saleId,
                    it.saleLineId,
                    it.productId,
                    it.locationId,
                    it.type,
                    it.quantityDelta,
                    it.unitCost,
                    it.currencyCode,
                    it.idempotencyKey,
                    it.occurredAt,
                    it.createdAt,
                )
            }
            add(balances.size)
            balances.forEach { change ->
                with(change.before) {
                    add(
                        businessId,
                        productId,
                        locationId,
                        quantityOnHand,
                        averageUnitCost,
                        currencyCode,
                        version,
                        updatedAt,
                        change.latestMovementAt,
                    )
                }
            }
            add(debt != null)
            debt?.let {
                add(
                    it.debtId,
                    it.businessId,
                    it.saleId,
                    it.debtorName,
                    it.normalizedDebtorName,
                    it.currencyCode,
                    it.originalAmountMinorUnits,
                    it.balanceMinorUnits,
                    it.status,
                    it.dueAt,
                    it.version,
                    it.createdAt,
                    it.updatedAt,
                    it.paidAt,
                )
            }
            add(payments.size)
            payments.forEach {
                add(
                    it.paymentId,
                    it.debtId,
                    it.businessId,
                    it.currencyCode,
                    it.amountMinorUnits,
                    it.method,
                    it.note,
                    it.reference,
                    it.expectedDebtVersion,
                    it.balanceAfterMinorUnits,
                    it.idempotencyKey,
                    it.occurredAt,
                    it.createdAt,
                )
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private class Rejected(
            val result: SaleVoidPreviewResult,
        ) : RuntimeException()

        private class BalanceRace : RuntimeException()

        private fun reject(result: SaleVoidPreviewResult): Nothing = throw Rejected(result)

        private fun SaleVoidPreviewResult.toConfirmResult(): SaleVoidResult =
            when (this) {
                SaleVoidPreviewResult.AlreadyVoided -> SaleVoidResult.AlreadyVoided
                SaleVoidPreviewResult.NoActiveBusiness -> SaleVoidResult.NoActiveBusiness
                SaleVoidPreviewResult.NotFound -> SaleVoidResult.NotFound
                SaleVoidPreviewResult.Unauthorized -> SaleVoidResult.Unauthorized
                SaleVoidPreviewResult.SharedBusinessUnsupported -> SaleVoidResult.SharedBusinessUnsupported
                SaleVoidPreviewResult.InvalidHistory -> SaleVoidResult.InvalidHistory
                is SaleVoidPreviewResult.Ready -> error("Ready no representa un rechazo")
            }
    }
