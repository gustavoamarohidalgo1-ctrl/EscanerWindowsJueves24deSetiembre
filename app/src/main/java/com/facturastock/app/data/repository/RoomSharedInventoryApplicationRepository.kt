package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.SaleWithLines
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.DebtEntity
import com.facturastock.app.data.local.entity.DebtPaymentEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.RemoteSyncStateEntity
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.SharedInventoryBalance
import com.facturastock.app.domain.model.SharedInventoryChange
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedDebtPayment
import com.facturastock.app.domain.model.SharedDebtSnapshot
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.SharedSaleCredit
import com.facturastock.app.domain.model.SharedSaleLine
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.debtorNameSearchKey
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AuditPayloadPolicy
import com.facturastock.app.domain.repository.SharedInventoryApplicationRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Consume el stream autoritativo de inventario sin generar ecos de outbox. Hechos, grafo de
 * venta y cursor se confirman juntos; cualquier referencia ambigua revierte la pagina completa.
 */
@Singleton
class RoomSharedInventoryApplicationRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
) : SharedInventoryApplicationRepository {
    override suspend fun lastAppliedSeq(cloudBusinessId: BusinessId): Long =
        withContext(dispatchers.io) {
            storageCatching {
                database.remoteSyncDao().findState(cloudBusinessId.value)?.inventorySeq ?: 0L
            }
        }

    override suspend fun applyPage(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        expectedPreviousSeq: Long,
        page: SharedInventoryPullPage,
        appliedAt: Instant,
    ): DomainResult<Int> = withContext(dispatchers.io) {
        try {
            validatePage(expectedPreviousSeq, page)
            val appliedAtMillis = appliedAt.toEpochMilli()
            if (appliedAtMillis < 0L) throw InventoryApplicationConflict()
            val materializedSales = storageCatching {
                database.withTransaction {
                    requireBinding(localBusinessId, cloudBusinessId)
                    val state = database.remoteSyncDao().findState(cloudBusinessId.value)
                        ?: RemoteSyncStateEntity(cloudBusinessId.value)
                    if (state.inventorySeq != expectedPreviousSeq) {
                        throw InventoryApplicationConflict()
                    }

                    val resolver = ApplicationResolver.create(
                        database = database,
                        localBusinessId = localBusinessId.value,
                        cloudBusinessId = cloudBusinessId.value,
                        appliedAtMillis = appliedAtMillis,
                    )
                    var sales = 0
                    page.changes.forEach { change ->
                        sales += applyChange(
                            localBusinessId = localBusinessId,
                            cloudBusinessId = cloudBusinessId,
                            change = change,
                            resolver = resolver,
                        )
                    }
                    database.remoteSyncDao().upsertState(
                        state.copy(
                            inventorySeq = page.nextCursor,
                            inventoryPulledAt = appliedAtMillis,
                        ),
                    )
                    sales
                }
            }
            DomainResult.Success(materializedSales)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InventoryApplicationConflict) {
            DomainResult.Failure(AccountError.Conflict)
        } catch (storage: StorageException) {
            DomainResult.Failure(storage.error)
        } catch (_: IllegalArgumentException) {
            DomainResult.Failure(AccountError.Conflict)
        } catch (_: ArithmeticException) {
            DomainResult.Failure(AccountError.Conflict)
        } catch (_: Exception) {
            DomainResult.Failure(AccountError.Unexpected)
        }
    }

    private suspend fun requireBinding(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ) {
        val local = database.cloudBusinessBindingDao().findByLocal(localBusinessId.value)
        val cloud = database.cloudBusinessBindingDao().findByCloud(cloudBusinessId.value)
        if (
            local?.cloudBusinessId != cloudBusinessId.value ||
            cloud?.localBusinessId != localBusinessId.value ||
            database.businessDao().findById(localBusinessId.value) == null
        ) {
            throw InventoryApplicationConflict()
        }
    }

    private suspend fun applyChange(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        change: SharedInventoryChange,
        resolver: ApplicationResolver,
    ): Int {
        if (change.kind == com.facturastock.app.domain.model.SharedInventoryChangeKind.DEBT_PAYMENT) {
            applyDebtPayment(
                localBusinessId = localBusinessId,
                cloudBusinessId = cloudBusinessId,
                debt = change.debt ?: throw InventoryApplicationConflict(),
                payment = change.payment ?: throw InventoryApplicationConflict(),
            )
            return 0
        }
        val balances = change.balances.map { resolver.resolveBalance(it) }
        if (balances.map(ResolvedBalance::localKey).distinct().size != balances.size) {
            throw InventoryApplicationConflict()
        }
        val sale = change.sale
        if (sale == null) {
            balances.forEach {
                applyAuthoritativeBalance(localBusinessId = localBusinessId, resolved = it)
            }
            return 0
        }
        val lines = sale.lines.map { resolver.resolveLine(it) }
        if (lines.map(ResolvedSaleLine::localKey).distinct().size != lines.size) {
            throw InventoryApplicationConflict()
        }
        val balancesByRemoteKey = balances.associateBy(ResolvedBalance::remoteKey)
        val existingGraph = database.saleDao().findWithLines(sale.saleId.value)
        // El ACK puede haber publicado la venta antes de que el cursor alcance este evento.
        // Un evento anterior de la misma página puede haber reemplazado su saldo: siempre
        // aplicar la instantánea en orden del feed. materializeSale verifica por separado el
        // grafo ya publicado sin duplicar venta, movimientos ni auditoría.
        balances.forEach {
            applyAuthoritativeBalance(localBusinessId = localBusinessId, resolved = it)
        }
        val materialized = materializeSale(
            localBusinessId = localBusinessId,
            document = sale,
            resolvedLines = lines,
            balancesByRemoteKey = balancesByRemoteKey,
            graph = existingGraph,
        )
        materializeSaleDebt(localBusinessId, sale, sale.credit)
        return materialized
    }

    private suspend fun materializeSaleDebt(
        localBusinessId: BusinessId,
        sale: SharedSaleDocument,
        credit: SharedSaleCredit?,
    ) {
        val dao = database.debtDao()
        val existingForSale = dao.findDebtForSale(sale.saleId.value)
        if (credit == null) {
            if (existingForSale != null) throw InventoryApplicationConflict()
            return
        }
        val expectedDebtId = SaleContentIdentity.uuid("sale-debt", sale.saleId.value).toString()
        if (credit.debtId.value != expectedDebtId || sale.total.minorUnits <= 0L) {
            throw InventoryApplicationConflict()
        }
        val postedAt = sale.postedAt.toEpochMilli()
        val expected = DebtEntity(
            debtId = credit.debtId.value,
            businessId = localBusinessId.value,
            saleId = sale.saleId.value,
            debtorName = credit.debtorNameSnapshot,
            normalizedDebtorName = debtorNameSearchKey(credit.debtorNameSnapshot),
            currencyCode = sale.currency.value,
            originalAmountMinorUnits = sale.total.minorUnits,
            balanceMinorUnits = sale.total.minorUnits,
            status = DebtStatus.OPEN.name,
            dueAt = credit.dueAt?.toEpochMilli(),
            version = 1L,
            createdAt = postedAt,
            updatedAt = postedAt,
            paidAt = null,
        )
        val existingById = dao.findDebt(credit.debtId.value)
        when {
            existingForSale == null && existingById == null -> dao.insertDebt(expected)
            existingForSale != null && existingForSale == existingById &&
                existingForSale.hasDebtStateAtOrAfter(expected) &&
                dao.countPayments(existingForSale.debtId).toLong() == existingForSale.version - 1L ->
                Unit
            else -> throw InventoryApplicationConflict()
        }
    }

    private suspend fun applyDebtPayment(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        debt: SharedDebtSnapshot,
        payment: SharedDebtPayment,
    ) {
        if (
            debt.businessId != cloudBusinessId || payment.businessId != cloudBusinessId ||
            debt.debtId != payment.debtId || debt.currency != payment.amount.currency ||
            debt.version != payment.expectedDebtVersion + 1L || debt.balance != payment.balanceAfter ||
            debt.updatedAt != payment.createdAt ||
            debt.debtId.value != SaleContentIdentity.uuid("sale-debt", debt.saleId.value).toString()
        ) {
            throw InventoryApplicationConflict()
        }
        val dao = database.debtDao()
        val current = dao.findDebt(debt.debtId.value) ?: throw InventoryApplicationConflict()
        val expectedDebt = debt.toLocalEntity(localBusinessId)
        val expectedPayment = payment.toLocalEntity(localBusinessId)
        val paymentById = dao.findPayment(payment.paymentId.value)
        val paymentByKey = dao.findPaymentByIdempotencyKey(payment.idempotencyKey)

        if (current.version >= debt.version) {
            if (
                paymentById != expectedPayment || paymentByKey != expectedPayment ||
                !current.hasDebtStateAtOrAfter(expectedDebt) ||
                dao.countPayments(current.debtId).toLong() != current.version - 1L
            ) {
                throw InventoryApplicationConflict()
            }
            return
        }
        if (
            current.version != payment.expectedDebtVersion || paymentById != null ||
            paymentByKey != null || current.businessId != localBusinessId.value ||
            current.saleId != debt.saleId.value || current.debtorName != debt.debtorNameSnapshot ||
            current.currencyCode != debt.currency.value ||
            current.originalAmountMinorUnits != debt.originalAmount.minorUnits ||
            current.dueAt != debt.dueAt?.toEpochMilli() || current.status != DebtStatus.OPEN.name ||
            current.balanceMinorUnits != Math.addExact(
                debt.balance.minorUnits,
                payment.amount.minorUnits,
            ) || debt.updatedAt < java.time.Instant.ofEpochMilli(current.updatedAt)
        ) {
            throw InventoryApplicationConflict()
        }
        // Los triggers enlazan ambas mitades: el pago valida contra el saldo anterior y la
        // transición de deuda exige encontrar ese pago dentro de la misma transacción.
        dao.insertPayment(expectedPayment)
        if (
            dao.applyPaymentIfVersion(
                debtId = current.debtId,
                businessId = current.businessId,
                expectedVersion = payment.expectedDebtVersion,
                currencyCode = current.currencyCode,
                balanceAfterMinorUnits = debt.balance.minorUnits,
                status = debt.status.name,
                updatedAt = debt.updatedAt.toEpochMilli(),
                paidAt = debt.paidAt?.toEpochMilli(),
            ) != 1
        ) {
            throw InventoryApplicationConflict()
        }
        if (
            dao.findDebt(debt.debtId.value) != expectedDebt ||
            dao.findPayment(payment.paymentId.value) != expectedPayment
        ) {
            throw InventoryApplicationConflict()
        }
    }

    private suspend fun applyAuthoritativeBalance(
        localBusinessId: BusinessId,
        resolved: ResolvedBalance,
    ) {
        val incoming = resolved.balance
        val inventory = database.inventoryDao()
        val existing = inventory.findBalance(
            businessId = localBusinessId.value,
            productId = resolved.product.productId,
            locationId = resolved.location.locationId,
        )
        val quantity = incoming.quantityOnHand.toPlainString()
        val cost = incoming.averageUnitCost.toPlainString()
        val currency = incoming.currency.value
        val updatedAt = incoming.updatedAt.toEpochMilli()
        if (updatedAt < 0L) throw InventoryApplicationConflict()
        if (existing == null) {
            if (
                inventory.insertBalanceIfAbsent(
                    InventoryBalanceEntity(
                        businessId = localBusinessId.value,
                        productId = resolved.product.productId,
                        locationId = resolved.location.locationId,
                        quantityOnHand = quantity,
                        averageUnitCost = cost,
                        currencyCode = currency,
                        version = incoming.version,
                        updatedAt = updatedAt,
                    ),
                ) == -1L
            ) {
                throw InventoryApplicationConflict()
            }
            return
        }

        val sameSnapshot = existing.quantityOnHand == quantity &&
            existing.averageUnitCost == cost && existing.currencyCode == currency &&
            existing.updatedAt == updatedAt
        if (sameSnapshot) return
        // La secuencia contigua del feed es la autoridad. Ni `version` (contadores de dominios
        // distintos) ni `updatedAt` (relojes de dispositivos distintos) sirven para ordenar el
        // saldo remoto frente al local. Saltar una fila y avanzar el cursor dejaría un saldo
        // obsoleto de forma permanente.
        if (
            inventory.updateAuthoritativeBalanceIfVersion(
                businessId = existing.businessId,
                productId = existing.productId,
                locationId = existing.locationId,
                expectedVersion = existing.version,
                quantityOnHand = quantity,
                averageUnitCost = cost,
                currencyCode = currency,
                updatedAt = updatedAt,
            ) != 1
        ) {
            throw InventoryApplicationConflict()
        }
    }

    private suspend fun materializeSale(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
        resolvedLines: List<ResolvedSaleLine>,
        balancesByRemoteKey: Map<RemoteInventoryKey, ResolvedBalance>,
        graph: SaleWithLines?,
    ): Int {
        val expectedLines = resolvedLines.map { it.toEntity(document) }
        val totals = SaleCartTotals.of(document.currency.value, expectedLines)
        if (
            totals.subtotal != document.subtotal.minorUnits ||
            totals.discount != document.discount.minorUnits ||
            totals.tax != document.tax.minorUnits ||
            totals.total != document.total.minorUnits
        ) {
            throw InventoryApplicationConflict()
        }
        val localCheckout = localCheckoutIdentity(document, totals.contentHash)
        val postedAt = document.postedAt.toEpochMilli()
        val createdAt = document.createdAt.toEpochMilli()
        val summaryAt = document.updatedAt.toEpochMilli()
        if (createdAt < 0L || summaryAt !in createdAt..postedAt) {
            throw InventoryApplicationConflict()
        }
        val expectedMovements = resolvedLines.map { line ->
            val balance = balancesByRemoteKey[line.remoteKey]
                ?: throw InventoryApplicationConflict()
            if (balance.balance.updatedAt.toEpochMilli() != postedAt) {
                throw InventoryApplicationConflict()
            }
            line.toMovement(
                businessId = localBusinessId.value,
                saleId = document.saleId.value,
                postedAt = postedAt,
                unitCost = balance.balance.averageUnitCost.toPlainString(),
                costCurrency = balance.balance.currency.value,
            )
        }
        val expectedAudit = expectedAudit(localBusinessId.value, document, postedAt)
        if (graph?.sale?.status == SaleStatus.POSTED.name) {
            verifyPostedSale(
                graph = graph,
                document = document,
                expectedLines = expectedLines,
                expectedMovements = expectedMovements,
                expectedAudit = expectedAudit,
                localCheckoutKey = localCheckout.key,
            )
            return 0
        }

        val keyOwner = database.saleDao()
            .findByCheckoutIdempotencyKey(localCheckout.key)
        if (keyOwner != null && keyOwner.saleId != document.saleId.value) {
            throw InventoryApplicationConflict()
        }
        expectedLines.forEach { expected ->
            val stored = database.saleDao().findLine(expected.saleLineId)
            if (stored != null && stored != expected) throw InventoryApplicationConflict()
        }

        val draft = graph ?: insertEmptyRemoteDraft(
            localBusinessId = localBusinessId,
            document = document,
            createdAt = createdAt,
        )
        val postingExpectedVersion = prepareDraftSummary(
            graph = draft,
            localBusinessId = localBusinessId.value,
            document = document,
            expectedLines = expectedLines,
            totals = totals,
            summaryAt = summaryAt,
            expectedNormalDraftVersion = localCheckout.expectedVersion,
        )
        ensureMovements(expectedMovements)
        ensureAudit(expectedAudit)
        if (
            database.saleDao().markPostedIfVersionAndHash(
                saleId = document.saleId.value,
                businessId = localBusinessId.value,
                expectedVersion = postingExpectedVersion,
                expectedContentHash = totals.contentHash,
                checkoutIdempotencyKey = localCheckout.key,
                postedAt = postedAt,
            ) != 1
        ) {
            throw InventoryApplicationConflict()
        }
        database.saleDao().deletePendingCheckout(document.saleId.value)
        return 1
    }

    private suspend fun insertEmptyRemoteDraft(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
        createdAt: Long,
    ): SaleWithLines {
        val empty = SaleEntity(
            saleId = document.saleId.value,
            businessId = localBusinessId.value,
            status = SaleStatus.DRAFT.name,
            currencyCode = document.currency.value,
            subtotalMinorUnits = 0L,
            discountMinorUnits = 0L,
            taxMinorUnits = 0L,
            totalMinorUnits = 0L,
            contentHash = SaleContentIdentity.hash(document.currency.value, emptyList()),
            draftSlot = "remote:${document.saleId.value}",
            version = 0L,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        database.saleDao().insertSale(empty)
        return SaleWithLines(empty, emptyList())
    }

    private suspend fun prepareDraftSummary(
        graph: SaleWithLines,
        localBusinessId: String,
        document: SharedSaleDocument,
        expectedLines: List<SaleLineEntity>,
        totals: SaleCartTotals,
        summaryAt: Long,
        expectedNormalDraftVersion: Long,
    ): Long {
        val sale = graph.sale
        if (
            sale.businessId != localBusinessId || sale.saleId != document.saleId.value ||
            sale.createdAt != document.createdAt.toEpochMilli() ||
            sale.status != SaleStatus.DRAFT.name ||
            sale.currencyCode != document.currency.value ||
            sale.checkoutIdempotencyKey != null || sale.postedAt != null
        ) {
            throw InventoryApplicationConflict()
        }
        return when (sale.draftSlot) {
            "remote:${document.saleId.value}" -> {
                prepareSyntheticDraft(graph, document, expectedLines, totals, summaryAt)
                1L
            }
            "$localBusinessId:${document.currency.value}" -> {
                if (
                    sale.version != expectedNormalDraftVersion ||
                    sale.updatedAt > summaryAt ||
                    sale.subtotalMinorUnits != totals.subtotal ||
                    sale.discountMinorUnits != totals.discount ||
                    sale.taxMinorUnits != totals.tax || sale.totalMinorUnits != totals.total ||
                    sale.contentHash != totals.contentHash ||
                    graph.lines.sortedBy(SaleLineEntity::position) != expectedLines
                ) {
                    throw InventoryApplicationConflict()
                }
                expectedNormalDraftVersion
            }
            else -> throw InventoryApplicationConflict()
        }
    }

    private suspend fun prepareSyntheticDraft(
        graph: SaleWithLines,
        document: SharedSaleDocument,
        expectedLines: List<SaleLineEntity>,
        totals: SaleCartTotals,
        summaryAt: Long,
    ) {
        val sale = graph.sale
        when (sale.version) {
            0L -> {
                val emptyHash = SaleContentIdentity.hash(document.currency.value, emptyList())
                if (
                    sale.subtotalMinorUnits != 0L || sale.discountMinorUnits != 0L ||
                    sale.taxMinorUnits != 0L || sale.totalMinorUnits != 0L ||
                    sale.contentHash != emptyHash || sale.updatedAt > summaryAt
                ) {
                    throw InventoryApplicationConflict()
                }
                val expectedById = expectedLines.associateBy(SaleLineEntity::saleLineId)
                if (graph.lines.any { expectedById[it.saleLineId] != it }) {
                    throw InventoryApplicationConflict()
                }
                val existingIds = graph.lines.mapTo(hashSetOf(), SaleLineEntity::saleLineId)
                val missing = expectedLines.filterNot { it.saleLineId in existingIds }
                if (missing.isNotEmpty()) database.saleDao().insertLines(missing)
                if (
                    database.saleDao().updateDraftSummaryIfVersion(
                        saleId = sale.saleId,
                        businessId = sale.businessId,
                        expectedVersion = 0L,
                        subtotalMinorUnits = totals.subtotal,
                        discountMinorUnits = totals.discount,
                        taxMinorUnits = totals.tax,
                        totalMinorUnits = totals.total,
                        contentHash = totals.contentHash,
                        updatedAt = summaryAt,
                    ) != 1
                ) {
                    throw InventoryApplicationConflict()
                }
            }
            1L -> {
                if (
                    graph.lines.sortedBy(SaleLineEntity::position) != expectedLines ||
                    sale.subtotalMinorUnits != totals.subtotal ||
                    sale.discountMinorUnits != totals.discount || sale.taxMinorUnits != totals.tax ||
                    sale.totalMinorUnits != totals.total || sale.contentHash != totals.contentHash ||
                    sale.updatedAt != summaryAt
                ) {
                    throw InventoryApplicationConflict()
                }
            }
            else -> throw InventoryApplicationConflict()
        }
    }

    private suspend fun ensureMovements(expected: List<StockMovementEntity>) {
        val missing = buildList {
            expected.forEach { movement ->
                val byId = database.inventoryDao().findMovementById(movement.movementId)
                val byKey = database.inventoryDao()
                    .findMovementByIdempotencyKey(movement.idempotencyKey)
                if (byId == null && byKey == null) {
                    add(movement)
                } else if (byId != movement || byKey != movement) {
                    throw InventoryApplicationConflict()
                }
            }
        }
        if (missing.isNotEmpty()) database.inventoryDao().insertMovements(missing)
    }

    private suspend fun ensureAudit(expected: AuditEventEntity) {
        val events = database.auditEventDao().listForEntity(
            businessId = expected.businessId,
            entityType = expected.entityType,
            entityId = expected.entityId,
        )
        val stored = events.singleOrNull { it.auditEventId == expected.auditEventId }
        if (stored == null) {
            if (events.any { it.eventType == AuditEventType.SALE_POSTED.name }) {
                throw InventoryApplicationConflict()
            }
            database.auditEventDao().insert(expected)
        } else if (stored != expected) {
            throw InventoryApplicationConflict()
        }
    }

    private suspend fun verifyPostedSale(
        graph: SaleWithLines,
        document: SharedSaleDocument,
        expectedLines: List<SaleLineEntity>,
        expectedMovements: List<StockMovementEntity>,
        expectedAudit: AuditEventEntity,
        localCheckoutKey: String,
    ) {
        val sale = graph.sale
        val totals = SaleCartTotals.of(document.currency.value, expectedLines)
        if (
            sale.saleId != document.saleId.value || sale.businessId != expectedAudit.businessId ||
            sale.currencyCode != document.currency.value ||
            sale.subtotalMinorUnits != totals.subtotal ||
            sale.discountMinorUnits != totals.discount || sale.taxMinorUnits != totals.tax ||
            sale.totalMinorUnits != totals.total || sale.contentHash != totals.contentHash ||
            sale.draftSlot != null ||
            sale.checkoutIdempotencyKey != localCheckoutKey ||
            sale.createdAt != document.createdAt.toEpochMilli() ||
            sale.postedAt != document.postedAt.toEpochMilli() || sale.updatedAt != sale.postedAt ||
            graph.lines.sortedBy(SaleLineEntity::position) != expectedLines
        ) {
            throw InventoryApplicationConflict()
        }
        val movements = database.inventoryDao().listMovementsForSale(
            businessId = sale.businessId,
            saleId = sale.saleId,
        )
        if (
            movements.sortedBy(StockMovementEntity::movementId) !=
            expectedMovements.sortedBy(StockMovementEntity::movementId)
        ) {
            throw InventoryApplicationConflict()
        }
        val audit = database.auditEventDao().listForEntity(
            businessId = sale.businessId,
            entityType = "SALE",
            entityId = sale.saleId,
        ).singleOrNull { it.auditEventId == expectedAudit.auditEventId }
        if (audit != expectedAudit) throw InventoryApplicationConflict()
    }

    private fun expectedAudit(
        businessId: String,
        document: SharedSaleDocument,
        postedAt: Long,
    ): AuditEventEntity = AuditEventEntity(
        auditEventId = SaleContentIdentity.uuid(
            "sale-posted-audit",
            document.saleId.value,
        ).toString(),
        businessId = businessId,
        eventType = AuditEventType.SALE_POSTED.name,
        entityType = "SALE",
        entityId = document.saleId.value,
        payload = AuditPayloadPolicy.encode(
            AuditEventType.SALE_POSTED,
            mapOf("version" to "1"),
        ),
        occurredAt = postedAt,
    )

    private fun localCheckoutIdentity(
        document: SharedSaleDocument,
        localContentHash: String,
    ): LocalCheckoutIdentity {
        val requiredPrefix = "sale-checkout:v1:${document.saleId.value}:"
        val separator = document.checkoutIdempotencyKey.lastIndexOf(':')
        val expectedVersion = document.checkoutIdempotencyKey
            .takeIf { separator > requiredPrefix.length }
            ?.substring(requiredPrefix.length, separator)
            ?.toLongOrNull()
        if (
            !document.checkoutIdempotencyKey.startsWith(requiredPrefix) ||
            separator <= requiredPrefix.length ||
            expectedVersion == null || expectedVersion !in 0L until Long.MAX_VALUE ||
            document.checkoutIdempotencyKey.substring(separator + 1) != document.contentHash
        ) {
            throw InventoryApplicationConflict()
        }
        return LocalCheckoutIdentity(
            key = document.checkoutIdempotencyKey.substring(0, separator + 1) + localContentHash,
            expectedVersion = expectedVersion,
        )
    }
}

private data class LocalCheckoutIdentity(
    val key: String,
    val expectedVersion: Long,
)

private class ApplicationResolver(
    private val database: FacturaStockDatabase,
    private val localBusinessId: String,
    private val cloudBusinessId: String,
    private val appliedAtMillis: Long,
    private val locations: MutableMap<String, MutableList<InventoryLocationEntity>>,
) {
    private val products = mutableMapOf<String, ProductEntity>()

    suspend fun resolveBalance(balance: SharedInventoryBalance): ResolvedBalance = ResolvedBalance(
        balance = balance,
        product = resolveProduct(balance.productId.value),
        location = resolveLocation(balance.locationName),
    )

    suspend fun resolveLine(line: SharedSaleLine): ResolvedSaleLine = ResolvedSaleLine(
        line = line,
        product = resolveProduct(line.productId.value),
        location = resolveLocation(line.locationName),
    )

    private suspend fun resolveProduct(remoteProductId: String): ProductEntity =
        products.getOrPutSuspend(remoteProductId) {
            val link = database.catalogSyncLinkDao().findByRemote(
                cloudBusinessId = cloudBusinessId,
                entityType = SHARED_PRODUCT_ENTITY_TYPE,
                remoteEntityId = remoteProductId,
            )
            if (
                link != null &&
                (link.localBusinessId != localBusinessId || link.cloudBusinessId != cloudBusinessId)
            ) {
                throw InventoryApplicationConflict()
            }
            if (link == null) {
                val localLink = database.catalogSyncLinkDao().findByLocal(
                    localBusinessId = localBusinessId,
                    entityType = SHARED_PRODUCT_ENTITY_TYPE,
                    localEntityId = remoteProductId,
                )
                if (localLink != null && localLink.remoteEntityId != remoteProductId) {
                    throw InventoryApplicationConflict()
                }
            }
            val localProductId = link?.localEntityId ?: remoteProductId
            database.productDao().findById(localProductId)
                ?.takeIf { it.businessId == localBusinessId }
                ?: throw InventoryApplicationConflict()
        }

    private suspend fun resolveLocation(remoteName: String): InventoryLocationEntity {
        val canonicalName = canonicalLocationName(remoteName)
        val matches = locations[canonicalName].orEmpty()
        if (matches.size > 1) throw InventoryApplicationConflict()
        matches.singleOrNull()?.let { return it }

        val displayName = sharedLocationDisplayName(remoteName)
        val location = InventoryLocationEntity(
            locationId = SaleContentIdentity.uuid(
                "shared-inventory-location",
                cloudBusinessId,
                canonicalName,
            ).toString(),
            businessId = localBusinessId,
            name = displayName,
            createdAt = appliedAtMillis,
            updatedAt = appliedAtMillis,
        )
        database.inventoryLocationDao().findById(location.locationId)?.let { existing ->
            if (
                existing.businessId != localBusinessId ||
                canonicalLocationName(existing.name) != canonicalName
            ) {
                throw InventoryApplicationConflict()
            }
            locations.getOrPut(canonicalName) { mutableListOf() }.add(existing)
            return existing
        }
        database.inventoryLocationDao().insert(location)
        locations.getOrPut(canonicalName) { mutableListOf() }.add(location)
        return location
    }

    companion object {
        suspend fun create(
            database: FacturaStockDatabase,
            localBusinessId: String,
            cloudBusinessId: String,
            appliedAtMillis: Long,
        ): ApplicationResolver {
            val locations = database.inventoryLocationDao().listForBusiness(localBusinessId)
                .groupBy { canonicalLocationName(it.name) }
                .mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableList() }
            return ApplicationResolver(
                database,
                localBusinessId,
                cloudBusinessId,
                appliedAtMillis,
                locations,
            )
        }
    }
}

private data class RemoteInventoryKey(
    val remoteProductId: String,
    val canonicalLocationName: String,
)

private data class ResolvedBalance(
    val balance: SharedInventoryBalance,
    val product: ProductEntity,
    val location: InventoryLocationEntity,
) {
    val remoteKey = RemoteInventoryKey(
        balance.productId.value,
        canonicalLocationName(balance.locationName),
    )
    val localKey = product.productId to location.locationId
}

private data class ResolvedSaleLine(
    val line: SharedSaleLine,
    val product: ProductEntity,
    val location: InventoryLocationEntity,
) {
    val remoteKey = RemoteInventoryKey(
        line.productId.value,
        canonicalLocationName(line.locationName),
    )
    val localKey = product.productId to location.locationId

    fun toEntity(document: SharedSaleDocument): SaleLineEntity = SaleLineEntity(
        saleLineId = line.saleLineId.value,
        saleId = document.saleId.value,
        productId = product.productId,
        unitId = product.unitId,
        locationId = location.locationId,
        position = line.position,
        productNameSnapshot = line.productName,
        unitCodeSnapshot = line.unitCode,
        locationNameSnapshot = location.name,
        barcodeSnapshot = line.barcode,
        quantity = line.quantity.toString(),
        unitPriceMinorUnits = line.unitPrice.minorUnits,
        discountMinorUnits = line.discount.minorUnits,
        taxMinorUnits = line.tax.minorUnits,
        lineTotalMinorUnits = line.lineTotal.minorUnits,
        currencyCode = document.currency.value,
    )

    fun toMovement(
        businessId: String,
        saleId: String,
        postedAt: Long,
        unitCost: String,
        costCurrency: String,
    ): StockMovementEntity = StockMovementEntity(
        movementId = SaleContentIdentity.uuid(
            "sale-stock-movement",
            saleId,
            line.saleLineId.value,
        ).toString(),
        businessId = businessId,
        saleId = saleId,
        saleLineId = line.saleLineId.value,
        productId = product.productId,
        locationId = location.locationId,
        type = StockMovementType.SALE.name,
        quantityDelta = "-${line.quantity}",
        unitCost = unitCost,
        currencyCode = costCurrency,
        idempotencyKey = "sale-stock:v1:$saleId:${line.saleLineId.value}",
        occurredAt = postedAt,
        createdAt = postedAt,
    )
}

/**
 * Un ACK o un cambio del feed puede llegar después de que el mismo dispositivo ya confirmó
 * pagos posteriores. La fila actual sigue siendo una materialización válida del hecho histórico
 * únicamente si conserva toda su identidad y avanzó de forma estrictamente reductora.
 */
private fun DebtEntity.hasDebtStateAtOrAfter(expected: DebtEntity): Boolean {
    if (
        debtId != expected.debtId || businessId != expected.businessId ||
        saleId != expected.saleId || debtorName != expected.debtorName ||
        normalizedDebtorName != expected.normalizedDebtorName ||
        currencyCode != expected.currencyCode ||
        originalAmountMinorUnits != expected.originalAmountMinorUnits ||
        dueAt != expected.dueAt || createdAt != expected.createdAt ||
        version < expected.version
    ) {
        return false
    }
    if (version == expected.version) return this == expected
    return expected.status == DebtStatus.OPEN.name &&
        balanceMinorUnits < expected.balanceMinorUnits && updatedAt >= expected.updatedAt
}

private fun SharedDebtSnapshot.toLocalEntity(localBusinessId: BusinessId): DebtEntity = DebtEntity(
    debtId = debtId.value,
    businessId = localBusinessId.value,
    saleId = saleId.value,
    debtorName = debtorNameSnapshot,
    normalizedDebtorName = debtorNameSearchKey(debtorNameSnapshot),
    currencyCode = currency.value,
    originalAmountMinorUnits = originalAmount.minorUnits,
    balanceMinorUnits = balance.minorUnits,
    status = status.name,
    dueAt = dueAt?.toEpochMilli(),
    version = version,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    paidAt = paidAt?.toEpochMilli(),
)

private fun SharedDebtPayment.toLocalEntity(
    localBusinessId: BusinessId,
): DebtPaymentEntity = DebtPaymentEntity(
    paymentId = paymentId.value,
    debtId = debtId.value,
    businessId = localBusinessId.value,
    currencyCode = amount.currency.value,
    amountMinorUnits = amount.minorUnits,
    method = method.name,
    note = note,
    reference = reference,
    expectedDebtVersion = expectedDebtVersion,
    balanceAfterMinorUnits = balanceAfter.minorUnits,
    idempotencyKey = idempotencyKey,
    occurredAt = occurredAt.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
)

private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspend(
    key: K,
    defaultValue: suspend () -> V,
): V = this[key] ?: defaultValue().also { put(key, it) }

private fun validatePage(expectedPreviousSeq: Long, page: SharedInventoryPullPage) {
    if (expectedPreviousSeq !in 0..MAX_SAFE_SYNC_SEQUENCE) {
        throw InventoryApplicationConflict()
    }
    var expected = expectedPreviousSeq
    page.changes.forEach { change ->
        if (expected == MAX_SAFE_SYNC_SEQUENCE || change.seq != expected + 1L) {
            throw InventoryApplicationConflict()
        }
        expected = change.seq
    }
    if (page.nextCursor != expected) throw InventoryApplicationConflict()
}

private fun sharedLocationDisplayName(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .also { display ->
            if (display.isEmpty() || display.length > 100) throw InventoryApplicationConflict()
        }

private class InventoryApplicationConflict : RuntimeException()

private const val SHARED_PRODUCT_ENTITY_TYPE = "PRODUCT"
