package com.facturastock.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.PostedSaleProfitPageKey
import com.facturastock.app.data.local.dao.PostedSaleProfitRow
import com.facturastock.app.data.local.dao.SaleWithLines
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.DebtEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.PendingSaleCheckoutEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.domain.model.PendingSaleCheckout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.ProductProfitDecimalPolicy
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.RealizedSaleLineProfit
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SaleCart
import com.facturastock.app.domain.model.SaleCartLine
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.SaleSummary
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.SharedSaleCredit
import com.facturastock.app.domain.model.SharedSaleLine
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.calculateSaleLineGross
import com.facturastock.app.domain.model.calculateSaleLineTotal
import com.facturastock.app.domain.model.debtorNameSearchKey
import com.facturastock.app.domain.model.normalizeDebtorName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.AuditPayloadPolicy
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.DisabledRemoteSaleSyncRepository
import com.facturastock.app.domain.repository.OpenedSaleCart
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.RemoteSalePostResult
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.usecase.findAutomaticBarcodeRecovery
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** Persistencia local completa de carrito y checkout; deliberadamente no crea outbox. */
class RoomSaleRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val appClock: AppClock,
    private val uuidGenerator: UuidGenerator,
    private val dispatchers: DispatcherProvider,
    private val remoteSales: RemoteSaleSyncRepository = DisabledRemoteSaleSyncRepository,
) : SaleRepository {
    private val checkoutMutex = Mutex()
    override fun observe(saleId: SaleId): Flow<SaleCart?> =
        database.saleDao().observeWithLines(saleId.value)
            .map { graph -> graph?.toDomain() }
            .flowOn(dispatchers.io)

    override fun observeRecentPosted(
        businessId: BusinessId,
        limit: Int,
    ): Flow<List<SaleSummary>> {
        require(limit in 1..50) { "limit debe estar entre 1 y 50" }
        return database.saleDao().observeRecentPosted(businessId.value, limit)
            .map { rows ->
                rows.map { row ->
                    val currency = CurrencyCode.of(row.currencyCode)
                    SaleSummary(
                        saleId = checkNotNull(SaleId.parse(row.saleId)),
                        total = Money.ofMinor(row.totalMinorUnits, currency),
                        lineCount = row.lineCount,
                        postedAt = Instant.ofEpochMilli(row.postedAt),
                    )
                }
            }
            .flowOn(dispatchers.io)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePostedProfits(
        businessId: BusinessId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<RealizedSaleProfit>> {
        require(startInclusive < endExclusive) {
            "El intervalo de ventas debe tener una duración positiva"
        }
        val dao = database.saleDao()
        val startMillis = startInclusive.toEpochMilli()
        val endMillis = endExclusive.toEpochMilli()
        return dao.observePostedProfitPageKeys(
            businessId = businessId.value,
            startInclusive = startMillis,
            endExclusive = endMillis,
            beforePostedAt = null,
            beforeSaleId = null,
            limit = POSTED_PROFIT_PAGE_SIZE,
        ).mapLatest { firstPage ->
            // Las cabeceras POSTED, sus líneas y movimientos quedan congelados por triggers.
            // Cada invalidación reinicia el recorrido; las filas contables crudas solo viven un
            // lote a la vez, aunque el mes contenga miles de ventas.
            loadPostedSaleProfitsPaged(
                firstPage = firstPage,
                pageSize = POSTED_PROFIT_PAGE_SIZE,
                loadNextPage = { cursor, limit ->
                    dao.listPostedProfitPageKeys(
                        businessId = businessId.value,
                        startInclusive = startMillis,
                        endExclusive = endMillis,
                        beforePostedAt = cursor.postedAt,
                        beforeSaleId = cursor.saleId,
                        limit = limit,
                    )
                },
                loadRows = { saleIds ->
                    dao.listPostedProfitRowsForSales(
                        businessId = businessId.value,
                        saleIds = saleIds,
                    )
                },
            )
        }
            .flowOn(dispatchers.io)
    }

    override suspend fun createOrResume(
        businessId: BusinessId,
        currency: CurrencyCode,
    ): OpenedSaleCart = withContext(dispatchers.io) {
        try {
            database.withTransaction {
                val business = database.businessDao().findById(businessId.value)
                require(business?.status == CatalogStatus.ACTIVE.name) {
                    "El negocio activo no existe o está archivado"
                }
                database.saleDao().findActiveDraft(businessId.value, currency.value)?.let { draft ->
                    return@withTransaction OpenedSaleCart(draft.toDomain(), created = false)
                }
                val now = maxOf(appClock.now().toEpochMilli(), business.updatedAt)
                val sale = SaleEntity(
                    saleId = SaleId.from(uuidGenerator.newUuid()).value,
                    businessId = businessId.value,
                    status = SaleStatus.DRAFT.name,
                    currencyCode = currency.value,
                    subtotalMinorUnits = 0L,
                    discountMinorUnits = 0L,
                    taxMinorUnits = 0L,
                    totalMinorUnits = 0L,
                    contentHash = SaleContentIdentity.hash(currency.value, emptyList()),
                    draftSlot = "${businessId.value}:${currency.value}",
                    version = 0L,
                    createdAt = now,
                    updatedAt = now,
                )
                database.saleDao().insertSale(sale)
                OpenedSaleCart(SaleWithLines(sale, emptyList()).toDomain(), created = true)
            }
        } catch (_: SQLiteConstraintException) {
            // Una carrera por el slot puede terminar aquí. La reanudación conserva la misma
            // precondición ACTIVE dentro de otra transacción; nunca devuelve un draft archivado.
            database.withTransaction {
                val business = database.businessDao().findById(businessId.value)
                require(business?.status == CatalogStatus.ACTIVE.name) {
                    "El negocio activo no existe o está archivado"
                }
                val existing = database.saleDao().findActiveDraft(businessId.value, currency.value)
                if (existing != null) OpenedSaleCart(existing.toDomain(), created = false)
                else throw StorageException(StorageError.Unavailable)
            }
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
    }

    override suspend fun saveLine(
        businessId: BusinessId,
        command: SaveSaleCartLineCommand,
    ): SaleCartMutationResult = withContext(dispatchers.io) {
        try {
            database.withTransaction { saveLineInTransaction(businessId, command) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidSaleTotals) {
            SaleCartMutationResult.InvalidTotals
        } catch (_: SaleCartCasConflict) {
            classifyMutationCasConflict(
                businessId = businessId,
                saleId = command.saleId,
                saleLineId = command.saleLineId,
                expectedVersion = command.expectedVersion,
            )
        } catch (_: SQLiteConstraintException) {
            classifyMutationConflict(businessId, command.saleId, command.expectedVersion)
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
    }

    private suspend fun saveLineInTransaction(
        businessId: BusinessId,
        command: SaveSaleCartLineCommand,
    ): SaleCartMutationResult {
        val graph = database.saleDao().findWithLines(command.saleId.value)
            ?: return SaleCartMutationResult.NotFound
        val sale = graph.sale
        if (sale.businessId != businessId.value) return SaleCartMutationResult.NotFound
        if (sale.status != SaleStatus.DRAFT.name) return SaleCartMutationResult.NotDraft
        if (graph.pendingCheckout != null) return SaleCartMutationResult.CheckoutPending
        if (sale.version != command.expectedVersion) return SaleCartMutationResult.Stale
        if (listOfNotNull(command.unitPrice, command.discount, command.tax).any {
                it.currency.value != sale.currencyCode
            }
        ) {
            return SaleCartMutationResult.CurrencyMismatch
        }

        val product = database.productDao().findById(command.productId.value)
        if (
            product == null || product.businessId != businessId.value ||
            product.status != CatalogStatus.ACTIVE.name
        ) {
            return SaleCartMutationResult.ProductUnavailable
        }
        command.barcodeRecovery?.let { expectation ->
            if (product.barcode != expectation.expectedStoredBarcode ||
                product.version != expectation.expectedProductVersion
            ) {
                return SaleCartMutationResult.BarcodeRecoveryChanged
            }
            // Releer sólo el candidato no detecta un nuevo competidor. El catálogo se evalúa bajo la
            // misma transacción que insertará la línea, sin filtrar stock o estado; se omiten sólo
            // productos sin código ni SKU, que nunca pueden coincidir con la lectura.
            val catalog =
                database.productDao().listScannerIdentityCandidates(businessId.value).map { it.toDomain() }
            val match = findAutomaticBarcodeRecovery(expectation.scannedBarcode, businessId, catalog)
            if (match == null || match.productId != command.productId ||
                match.barcode != expectation.expectedStoredBarcode
            ) {
                return SaleCartMutationResult.BarcodeRecoveryChanged
            }
        }
        val unit = database.unitDao().findById(product.unitId)
        if (
            unit == null || unit.businessId != businessId.value ||
            unit.status != CatalogStatus.ACTIVE.name
        ) {
            return SaleCartMutationResult.ProductUnavailable
        }
        val location = database.inventoryLocationDao().findById(command.locationId.value)
        if (
            location == null || location.businessId != businessId.value ||
            location.status != CatalogStatus.ACTIVE.name
        ) {
            return SaleCartMutationResult.LocationUnavailable
        }

        val existing = command.saleLineId?.let { database.saleDao().findLine(it.value) }
        if (command.saleLineId != null && existing?.saleId != sale.saleId) {
            return SaleCartMutationResult.LineNotFound
        }
        val occupying = database.saleDao().findLineForProductLocation(
            saleId = sale.saleId,
            productId = product.productId,
            locationId = location.locationId,
        )
        if (occupying != null && occupying.saleLineId != existing?.saleLineId) {
            return SaleCartMutationResult.DuplicateProductLocation
        }

        val discount = command.discount ?: Money.zero(CurrencyCode.of(sale.currencyCode))
        val tax = command.tax ?: Money.zero(CurrencyCode.of(sale.currencyCode))
        val lineTotal = command.unitPrice?.let { price ->
            exactSaleTotals {
                calculateSaleLineTotal(
                    unitPrice = price,
                    quantity = command.quantity,
                    discount = discount,
                    tax = tax,
                )
            }
        }
        val line = SaleLineEntity(
            saleLineId = existing?.saleLineId
                ?: SaleLineId.from(uuidGenerator.newUuid()).value,
            saleId = sale.saleId,
            productId = product.productId,
            unitId = unit.unitId,
            locationId = location.locationId,
            position = existing?.position ?: (database.saleDao().maxPosition(sale.saleId) + 1),
            productNameSnapshot = product.name,
            unitCodeSnapshot = unit.code,
            locationNameSnapshot = location.name,
            barcodeSnapshot = product.barcode,
            quantity = command.quantity.value.toPlainString(),
            unitPriceMinorUnits = command.unitPrice?.minorUnits,
            discountMinorUnits = discount.minorUnits,
            taxMinorUnits = tax.minorUnits,
            lineTotalMinorUnits = lineTotal?.minorUnits,
            currencyCode = sale.currencyCode,
        )
        if (existing == null) {
            database.saleDao().insertLine(line)
        } else {
            if (database.saleDao().updateLineEntity(line) != 1) {
                throw SaleCartCasConflict()
            }
        }
        return updateSummary(sale, command.expectedVersion)
    }

    override suspend fun removeLine(
        businessId: BusinessId,
        saleId: SaleId,
        saleLineId: SaleLineId,
        expectedVersion: Long,
    ): SaleCartMutationResult = withContext(dispatchers.io) {
        try {
            database.withTransaction {
                val graph = database.saleDao().findWithLines(saleId.value)
                    ?: return@withTransaction SaleCartMutationResult.NotFound
                val sale = graph.sale
                if (sale.businessId != businessId.value) {
                    return@withTransaction SaleCartMutationResult.NotFound
                }
                if (sale.status != SaleStatus.DRAFT.name) {
                    return@withTransaction SaleCartMutationResult.NotDraft
                }
                if (graph.pendingCheckout != null) return@withTransaction SaleCartMutationResult.CheckoutPending
                if (sale.version != expectedVersion) {
                    return@withTransaction SaleCartMutationResult.Stale
                }
                if (graph.lines.none { it.saleLineId == saleLineId.value }) {
                    return@withTransaction SaleCartMutationResult.LineNotFound
                }
                database.saleDao().deleteLines(sale.saleId)
                database.saleDao().insertLines(
                    graph.lines.sortedBy(SaleLineEntity::position)
                        .filterNot { it.saleLineId == saleLineId.value }
                        .mapIndexed { position, line -> line.copy(position = position) },
                )
                updateSummary(sale, expectedVersion)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidSaleTotals) {
            SaleCartMutationResult.InvalidTotals
        } catch (_: SaleCartCasConflict) {
            classifyMutationCasConflict(
                businessId = businessId,
                saleId = saleId,
                saleLineId = saleLineId,
                expectedVersion = expectedVersion,
            )
        } catch (_: SQLiteConstraintException) {
            classifyMutationConflict(businessId, saleId, expectedVersion)
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
    }

    private suspend fun updateSummary(
        previous: SaleEntity,
        expectedVersion: Long,
    ): SaleCartMutationResult {
        val graph = checkNotNull(database.saleDao().findWithLines(previous.saleId))
        val summary = SaleCartTotals.of(previous.currencyCode, graph.lines)
        val updatedAt = maxOf(appClock.now().toEpochMilli(), previous.updatedAt)
        if (
            database.saleDao().updateDraftSummaryIfVersion(
                saleId = previous.saleId,
                businessId = previous.businessId,
                expectedVersion = expectedVersion,
                subtotalMinorUnits = summary.subtotal,
                discountMinorUnits = summary.discount,
                taxMinorUnits = summary.tax,
                totalMinorUnits = summary.total,
                contentHash = summary.contentHash,
                updatedAt = updatedAt,
            ) != 1
        ) {
            throw SaleCartCasConflict()
        }
        return SaleCartMutationResult.Saved(
            checkNotNull(database.saleDao().findWithLines(previous.saleId)).toDomain(),
        )
    }

    private suspend fun classifyMutationConflict(
        businessId: BusinessId,
        saleId: SaleId,
        expectedVersion: Long,
    ): SaleCartMutationResult {
        val sale = database.saleDao().findSale(saleId.value)
            ?: return SaleCartMutationResult.NotFound
        if (sale.businessId != businessId.value) return SaleCartMutationResult.NotFound
        if (sale.status != SaleStatus.DRAFT.name) return SaleCartMutationResult.NotDraft
        if (database.saleDao().findPendingCheckout(saleId.value) != null) return SaleCartMutationResult.CheckoutPending
        return if (sale.version != expectedVersion) {
            SaleCartMutationResult.Stale
        } else {
            SaleCartMutationResult.DuplicateProductLocation
        }
    }

    private suspend fun classifyMutationCasConflict(
        businessId: BusinessId,
        saleId: SaleId,
        saleLineId: SaleLineId?,
        expectedVersion: Long,
    ): SaleCartMutationResult {
        val sale = database.saleDao().findSale(saleId.value)
            ?: return SaleCartMutationResult.NotFound
        if (sale.businessId != businessId.value) return SaleCartMutationResult.NotFound
        if (sale.status != SaleStatus.DRAFT.name) return SaleCartMutationResult.NotDraft
        if (saleLineId != null && database.saleDao().findLine(saleLineId.value) == null) {
            return SaleCartMutationResult.LineNotFound
        }
        // Incluso si otro escritor dejó la misma versión por un fallo externo, el llamador debe
        // recargar el agregado: nunca se confirma parcialmente la mutación cuya transacción revirtió.
        return SaleCartMutationResult.Stale
    }

    override suspend fun checkout(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
    ): CheckoutSaleResult = checkoutMutex.withLock { checkoutLocked(businessId, command) }

    private suspend fun checkoutLocked(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
    ): CheckoutSaleResult = withContext(dispatchers.io) {
        val checkoutKey = SaleContentIdentity.checkoutKey(command)
        val preparation = try {
            database.withTransaction { prepareSharedCheckout(businessId, command, checkoutKey) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidSaleTotals) {
            return@withContext CheckoutSaleResult.InvalidTotals
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
        if (preparation is SaleCheckoutPreparation.Rejected) {
            return@withContext preparation.result
        }
        val document = (preparation as SaleCheckoutPreparation.Ready).document
        val remote = try {
            remoteSales.postSale(businessId, document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteSalePostResult.OnlineRequired
        }
        // Solo una respuesta definitiva libera la intención; también si el commit local falla
        // o se cancela. Un resultado remoto ambiguo debe sobrevivir al cierre de la pantalla.
        val canReleasePending = remote is RemoteSalePostResult.InsufficientStock ||
            remote == RemoteSalePostResult.InventoryMigrationRequired ||
            (remote == RemoteSalePostResult.NotRequired && preparation.cloudBusinessId == null)
        try {
            when (remote) {
                RemoteSalePostResult.NotRequired -> if (preparation.cloudBusinessId != null) {
                    CheckoutSaleResult.OnlineRequired
                } else commitCheckout(
                    businessId = businessId,
                    command = command,
                    checkoutKey = checkoutKey,
                    authorization = null,
                )
                is RemoteSalePostResult.Authorized -> {
                    if (remote.saleId != command.saleId) {
                        CheckoutSaleResult.RemoteRejected
                    } else {
                        commitCheckout(businessId, command, checkoutKey, remote)
                    }
                }
                is RemoteSalePostResult.InsufficientStock -> CheckoutSaleResult.InsufficientStock(
                    productId = remote.productId,
                    locationId = remote.locationId,
                    requested = remote.requested,
                    available = remote.available,
                )
                RemoteSalePostResult.InventoryMigrationRequired ->
                    CheckoutSaleResult.InventoryMigrationRequired
                RemoteSalePostResult.OnlineRequired -> CheckoutSaleResult.OnlineRequired
                RemoteSalePostResult.Rejected -> CheckoutSaleResult.RemoteRejected
            }
        } finally {
            if (canReleasePending) {
                withContext(NonCancellable) {
                    database.withTransaction { database.saleDao().deletePendingCheckout(command.saleId.value) }
                }
            }
        }
    }

    private suspend fun commitCheckout(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
        checkoutKey: String,
        authorization: RemoteSalePostResult.Authorized?,
    ): CheckoutSaleResult {
        try {
            return database.withTransaction {
                checkoutInTransaction(businessId, command, checkoutKey, authorization)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidSaleTotals) {
            return CheckoutSaleResult.InvalidTotals
        } catch (_: SaleCheckoutRace) {
            return CheckoutSaleResult.RetryableConflict
        } catch (_: SQLiteConstraintException) {
            return classifyCheckoutConflict(businessId, command, checkoutKey)
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
    }

    private suspend fun prepareSharedCheckout(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
        checkoutKey: String,
    ): SaleCheckoutPreparation {
        val graph = database.saleDao().findWithLines(command.saleId.value)
            ?: return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.NotFound)
        val sale = graph.sale
        if (sale.businessId != businessId.value) {
            return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.NotFound)
        }
        if (sale.status == SaleStatus.POSTED.name) {
            val result = if (
                sale.checkoutIdempotencyKey == checkoutKey &&
                postedSettlementMatches(sale, command)
            ) {
                CheckoutSaleResult.AlreadyPosted(command.saleId)
            } else {
                CheckoutSaleResult.CartChanged
            }
            return SaleCheckoutPreparation.Rejected(result)
        }
        if (sale.version != command.expectedVersion) {
            return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.Stale)
        }
        if (sale.contentHash != command.expectedContentHash) {
            return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.CartChanged)
        }
        val pending = graph.pendingCheckout
        val cloudBusinessId = database.cloudBusinessBindingDao().findByLocal(businessId.value)?.cloudBusinessId
        if (pending != null && (
                pending.businessId != businessId.value || pending.expectedVersion != command.expectedVersion ||
                    pending.contentHash != command.expectedContentHash ||
                    pending.checkoutIdempotencyKey != checkoutKey ||
                    pending.debtorName != command.debtorName?.let(::normalizeDebtorName) ||
                    pending.debtDueAt != command.debtDueAt?.toEpochMilli()
            )
        ) return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.CartChanged)
        if (pending != null && pending.cloudBusinessId != cloudBusinessId) {
            return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.OnlineRequired)
        }
        val lines = graph.lines.sortedBy(SaleLineEntity::position)
        if (lines.isEmpty()) return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.EmptyCart)
        val totals = SaleCartTotals.of(sale.currencyCode, lines)
        if (
            totals.contentHash != sale.contentHash || totals.subtotal != sale.subtotalMinorUnits ||
            totals.discount != sale.discountMinorUnits || totals.tax != sale.taxMinorUnits ||
            totals.total != sale.totalMinorUnits
        ) {
            return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.CartChanged)
        }
        if (command.debtorName != null && totals.total <= 0L) {
            return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.InvalidCreditTerms)
        }
        lines.firstOrNull { it.unitPriceMinorUnits == null || it.lineTotalMinorUnits == null }
            ?.let { line ->
                return SaleCheckoutPreparation.Rejected(
                    CheckoutSaleResult.IncompleteLine(
                        checkNotNull(SaleLineId.parse(line.saleLineId)),
                    ),
                )
            }
        val business = database.businessDao().findById(businessId.value)
        if (business?.status != CatalogStatus.ACTIVE.name) {
            return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.NoActiveBusiness)
        }
        val currency = CurrencyCode.of(sale.currencyCode)
        val sharedLines = lines.map { line ->
            val product = database.productDao().findById(line.productId)
            val unit = database.unitDao().findById(line.unitId)
            if (
                product == null || product.businessId != businessId.value ||
                (pending == null && product.status != CatalogStatus.ACTIVE.name) || product.unitId != line.unitId ||
                unit == null || unit.businessId != businessId.value ||
                (pending == null && unit.status != CatalogStatus.ACTIVE.name)
            ) {
                return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.ProductUnavailable)
            }
            val location = database.inventoryLocationDao().findById(line.locationId)
            if (
                location == null || location.businessId != businessId.value ||
                (pending == null && location.status != CatalogStatus.ACTIVE.name)
            ) {
                return SaleCheckoutPreparation.Rejected(CheckoutSaleResult.LocationUnavailable)
            }
            SharedSaleLine(
                saleLineId = checkNotNull(SaleLineId.parse(line.saleLineId)),
                position = line.position,
                productId = checkNotNull(ProductId.parse(line.productId)),
                unitId = checkNotNull(UnitId.parse(line.unitId)),
                locationId = checkNotNull(LocationId.parse(line.locationId)),
                productName = line.productNameSnapshot,
                unitCode = line.unitCodeSnapshot,
                locationName = line.locationNameSnapshot,
                barcode = line.barcodeSnapshot,
                quantity = Quantity.of(line.quantity),
                unitPrice = Money.ofMinor(requireNotNull(line.unitPriceMinorUnits), currency),
                discount = Money.ofMinor(line.discountMinorUnits, currency),
                tax = Money.ofMinor(line.taxMinorUnits, currency),
                lineTotal = Money.ofMinor(requireNotNull(line.lineTotalMinorUnits), currency),
            )
        }
        val updatedAt = Instant.ofEpochMilli(sale.updatedAt)
        if (pending == null) {
            database.saleDao().insertPendingCheckout(
                PendingSaleCheckoutEntity(
                    saleId = sale.saleId,
                    businessId = sale.businessId,
                    expectedVersion = command.expectedVersion,
                    contentHash = command.expectedContentHash,
                    checkoutIdempotencyKey = checkoutKey,
                    debtorName = command.debtorName?.let(::normalizeDebtorName),
                    debtDueAt = command.debtDueAt?.toEpochMilli(),
                    cloudBusinessId = cloudBusinessId,
                    createdAt = maxOf(appClock.now().toEpochMilli(), sale.updatedAt),
                ),
            )
        }
        return SaleCheckoutPreparation.Ready(
            SharedSaleDocument(
                saleId = command.saleId,
                currency = currency,
                subtotal = Money.ofMinor(totals.subtotal, currency),
                discount = Money.ofMinor(totals.discount, currency),
                tax = Money.ofMinor(totals.tax, currency),
                total = Money.ofMinor(totals.total, currency),
                contentHash = totals.contentHash,
                checkoutIdempotencyKey = checkoutKey,
                createdAt = Instant.ofEpochMilli(sale.createdAt),
                updatedAt = updatedAt,
                // Estable entre reintentos/caidas; el backend devuelve su timestamp almacenado.
                postedAt = updatedAt,
                lines = sharedLines,
                credit = command.debtorName?.let { rawName ->
                    SharedSaleCredit(
                        debtId = debtIdForSale(sale.saleId),
                        debtorNameSnapshot = normalizeDebtorName(rawName),
                        dueAt = command.debtDueAt,
                    )
                },
            ),
            cloudBusinessId = cloudBusinessId,
        )
    }

    private suspend fun checkoutInTransaction(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
        checkoutKey: String,
        authorization: RemoteSalePostResult.Authorized? = null,
    ): CheckoutSaleResult {
        val graph = database.saleDao().findWithLines(command.saleId.value)
            ?: return CheckoutSaleResult.NotFound
        val sale = graph.sale
        if (sale.businessId != businessId.value) return CheckoutSaleResult.NotFound
        if (sale.status == SaleStatus.POSTED.name) {
            return if (
                sale.checkoutIdempotencyKey == checkoutKey &&
                postedSettlementMatches(sale, command)
            ) {
                CheckoutSaleResult.AlreadyPosted(command.saleId)
            } else {
                CheckoutSaleResult.CartChanged
            }
        }
        if (sale.version != command.expectedVersion) return CheckoutSaleResult.Stale
        if (sale.contentHash != command.expectedContentHash) return CheckoutSaleResult.CartChanged
        val lines = graph.lines.sortedBy(SaleLineEntity::position)
        if (lines.isEmpty()) return CheckoutSaleResult.EmptyCart
        val recomputed = SaleCartTotals.of(sale.currencyCode, lines)
        if (
            recomputed.contentHash != sale.contentHash ||
            recomputed.subtotal != sale.subtotalMinorUnits ||
            recomputed.discount != sale.discountMinorUnits ||
            recomputed.tax != sale.taxMinorUnits ||
            recomputed.total != sale.totalMinorUnits
        ) {
            return CheckoutSaleResult.CartChanged
        }
        if (command.debtorName != null && recomputed.total <= 0L) {
            return CheckoutSaleResult.InvalidCreditTerms
        }
        lines.firstOrNull { it.unitPriceMinorUnits == null || it.lineTotalMinorUnits == null }
            ?.let { incomplete ->
                return CheckoutSaleResult.IncompleteLine(
                    checkNotNull(SaleLineId.parse(incomplete.saleLineId)),
                )
            }
        val business = database.businessDao().findById(businessId.value)
        if (business?.status != CatalogStatus.ACTIVE.name) {
            return CheckoutSaleResult.NoActiveBusiness
        }

        // Un ACK definitivo completa la intención histórica congelada, aunque el catálogo
        // se haya archivado mientras se esperaba la red. El checkout local sigue exigiendo
        // catálogo activo y nunca puede aprovechar esta excepción.
        val pending = graph.pendingCheckout
        val completingAuthorizedCheckout = authorization != null && pending != null &&
            pending.businessId == businessId.value && pending.expectedVersion == command.expectedVersion &&
            pending.contentHash == command.expectedContentHash && pending.checkoutIdempotencyKey == checkoutKey &&
            pending.debtorName == command.debtorName?.let(::normalizeDebtorName) &&
            pending.debtDueAt == command.debtDueAt?.toEpochMilli() &&
            pending.cloudBusinessId == database.cloudBusinessBindingDao().findByLocal(businessId.value)?.cloudBusinessId
        if (authorization != null && !completingAuthorizedCheckout) {
            return CheckoutSaleResult.RemoteRejected
        }

        val authorizedByKey = authorization?.balances?.let { authorized ->
            val indexed = authorized.associateBy { it.productId.value to it.locationId.value }
            if (indexed.size != authorized.size) return CheckoutSaleResult.RemoteRejected
            indexed
        }
        val balances = linkedMapOf<Pair<String, String>, InventoryBalanceEntity?>()
        for (line in lines) {
            val product = database.productDao().findById(line.productId)
            val unit = database.unitDao().findById(line.unitId)
            if (
                product == null || product.businessId != businessId.value ||
                (!completingAuthorizedCheckout && product.status != CatalogStatus.ACTIVE.name) ||
                product.unitId != line.unitId ||
                unit == null || unit.businessId != businessId.value ||
                (!completingAuthorizedCheckout && unit.status != CatalogStatus.ACTIVE.name)
            ) {
                return CheckoutSaleResult.ProductUnavailable
            }
            val location = database.inventoryLocationDao().findById(line.locationId)
            if (
                location == null || location.businessId != businessId.value ||
                (!completingAuthorizedCheckout && location.status != CatalogStatus.ACTIVE.name)
            ) {
                return CheckoutSaleResult.LocationUnavailable
            }
            val key = line.productId to line.locationId
            val balance = if (balances.containsKey(key)) {
                balances[key]
            } else {
                database.inventoryDao().findBalance(
                    businessId = businessId.value,
                    productId = line.productId,
                    locationId = line.locationId,
                )
            }
            val requested = Quantity.of(line.quantity)
            if (authorization == null) {
                val available = balance?.quantityOnHand ?: "0"
                if (balance == null || BigDecimal(available) < requested.value) {
                    return CheckoutSaleResult.InsufficientStock(
                        productId = checkNotNull(ProductId.parse(line.productId)),
                        locationId = checkNotNull(LocationId.parse(line.locationId)),
                        requested = requested,
                        available = available,
                    )
                }
            } else if (authorizedByKey?.containsKey(key) != true) {
                return CheckoutSaleResult.RemoteRejected
            }
            balances[key] = balance
        }

        if (authorizedByKey != null && authorizedByKey.keys != balances.keys) {
            return CheckoutSaleResult.RemoteRejected
        }
        val postedAt = if (authorization == null) {
            maxOf(
                appClock.now().toEpochMilli(),
                sale.updatedAt,
                balances.values.filterNotNull().maxOf { it.updatedAt },
            )
        } else {
            authorization.postedAt.toEpochMilli().takeIf { it >= sale.updatedAt }
                ?: return CheckoutSaleResult.RemoteRejected
        }
        val movementCosts = linkedMapOf<Pair<String, String>, Pair<String, String>>()
        for ((key, balance) in balances) {
            val requested = lines.asSequence()
                .filter { it.productId == key.first && it.locationId == key.second }
                .map { BigDecimal(it.quantity) }
                .fold(BigDecimal.ZERO, BigDecimal::add)
            val remoteBalance = authorizedByKey?.getValue(key)
            if (remoteBalance == null) {
                val current = checkNotNull(balance)
                val resulting = BigDecimal(current.quantityOnHand).subtract(requested)
                if (
                    database.inventoryDao().updateBalanceIfVersion(
                        businessId = current.businessId,
                        productId = current.productId,
                        locationId = current.locationId,
                        expectedVersion = current.version,
                        quantityOnHand = resulting.toPlainString(),
                        averageUnitCost = current.averageUnitCost,
                        currencyCode = current.currencyCode,
                        updatedAt = postedAt,
                    ) != 1
                ) {
                    throw SaleCheckoutRace()
                }
                movementCosts[key] = current.averageUnitCost to current.currencyCode
            } else {
                val quantity = remoteBalance.quantityOnHand.toPersistedReportDecimalOrNull()
                    ?: return CheckoutSaleResult.RemoteRejected
                val averageCost = remoteBalance.averageUnitCost.toPersistedReportDecimalOrNull()
                    ?.takeIf { it.signum() >= 0 }
                    ?: return CheckoutSaleResult.RemoteRejected
                if (quantity.signum() < 0 || remoteBalance.remoteVersion < 0L) {
                    return CheckoutSaleResult.RemoteRejected
                }
                val currency = runCatching { CurrencyCode.of(remoteBalance.currencyCode) }.getOrNull()
                    ?: return CheckoutSaleResult.RemoteRejected
                val balanceUpdatedAt = maxOf(
                    remoteBalance.updatedAt.toEpochMilli(),
                    balance?.updatedAt ?: 0L,
                    postedAt,
                )
                if (balance == null) {
                    val inserted = database.inventoryDao().insertBalanceIfAbsent(
                        InventoryBalanceEntity(
                            businessId = sale.businessId,
                            productId = key.first,
                            locationId = key.second,
                            quantityOnHand = quantity.toPlainString(),
                            averageUnitCost = averageCost.toPlainString(),
                            currencyCode = currency.value,
                            version = remoteBalance.remoteVersion,
                            updatedAt = balanceUpdatedAt,
                        ),
                    )
                    if (inserted == -1L) throw SaleCheckoutRace()
                } else if (
                    database.inventoryDao().updateBalanceIfVersion(
                        businessId = balance.businessId,
                        productId = balance.productId,
                        locationId = balance.locationId,
                        expectedVersion = balance.version,
                        quantityOnHand = quantity.toPlainString(),
                        averageUnitCost = averageCost.toPlainString(),
                        currencyCode = currency.value,
                        updatedAt = balanceUpdatedAt,
                    ) != 1
                ) {
                    throw SaleCheckoutRace()
                }
                movementCosts[key] = averageCost.toPlainString() to currency.value
            }
        }
        database.inventoryDao().insertMovements(
            lines.map { line ->
                val cost = movementCosts.getValue(line.productId to line.locationId)
                StockMovementEntity(
                    movementId = SaleContentIdentity.uuid(
                        "sale-stock-movement",
                        sale.saleId,
                        line.saleLineId,
                    ).toString(),
                    businessId = sale.businessId,
                    saleId = sale.saleId,
                    saleLineId = line.saleLineId,
                    productId = line.productId,
                    locationId = line.locationId,
                    type = StockMovementType.SALE.name,
                    quantityDelta = BigDecimal(line.quantity).negate().toPlainString(),
                    unitCost = cost.first,
                    currencyCode = cost.second,
                    idempotencyKey = "sale-stock:v1:${sale.saleId}:${line.saleLineId}",
                    occurredAt = postedAt,
                    createdAt = postedAt,
                )
            },
        )
        database.auditEventDao().insert(
            AuditEventEntity(
                auditEventId = SaleContentIdentity.uuid("sale-posted-audit", sale.saleId).toString(),
                businessId = sale.businessId,
                purchaseId = null,
                eventType = AuditEventType.SALE_POSTED.name,
                entityType = "SALE",
                entityId = sale.saleId,
                payload = AuditPayloadPolicy.encode(
                    AuditEventType.SALE_POSTED,
                    mapOf("version" to "1"),
                ),
                occurredAt = postedAt,
            ),
        )
        if (
            database.saleDao().markPostedIfVersionAndHash(
                saleId = sale.saleId,
                businessId = sale.businessId,
                expectedVersion = command.expectedVersion,
                expectedContentHash = command.expectedContentHash,
                checkoutIdempotencyKey = checkoutKey,
                postedAt = postedAt,
            ) != 1
        ) {
            throw SaleCheckoutRace()
        }
        command.debtorName?.let { rawName ->
            val cleanName = normalizeDebtorName(rawName)
            database.debtDao().insertDebt(
                DebtEntity(
                    debtId = debtIdForSale(sale.saleId).value,
                    businessId = sale.businessId,
                    saleId = sale.saleId,
                    debtorName = cleanName,
                    normalizedDebtorName = debtorNameSearchKey(cleanName),
                    currencyCode = sale.currencyCode,
                    originalAmountMinorUnits = sale.totalMinorUnits,
                    balanceMinorUnits = sale.totalMinorUnits,
                    status = DebtStatus.OPEN.name,
                    dueAt = command.debtDueAt?.toEpochMilli(),
                    version = 1L,
                    createdAt = postedAt,
                    updatedAt = postedAt,
                ),
            )
        }
        database.saleDao().deletePendingCheckout(sale.saleId)
        return CheckoutSaleResult.Posted(command.saleId)
    }

    private suspend fun classifyCheckoutConflict(
        businessId: BusinessId,
        command: CheckoutSaleCommand,
        checkoutKey: String,
    ): CheckoutSaleResult {
        val sale = database.saleDao().findSale(command.saleId.value)
            ?: return CheckoutSaleResult.NotFound
        if (sale.businessId != businessId.value) return CheckoutSaleResult.NotFound
        return if (
            sale.status == SaleStatus.POSTED.name && sale.checkoutIdempotencyKey == checkoutKey &&
            postedSettlementMatches(sale, command)
        ) {
            CheckoutSaleResult.AlreadyPosted(command.saleId)
        } else {
            CheckoutSaleResult.RetryableConflict
        }
    }

    private suspend fun postedSettlementMatches(
        sale: SaleEntity,
        command: CheckoutSaleCommand,
    ): Boolean {
        val debt = database.debtDao().findDebtForSale(sale.saleId)
        val rawName = command.debtorName
        if (rawName == null) return debt == null
        val cleanName = normalizeDebtorName(rawName)
        return debt != null && debt.debtId == debtIdForSale(sale.saleId).value &&
            debt.businessId == sale.businessId && debt.debtorName == cleanName &&
            debt.currencyCode == sale.currencyCode &&
            debt.originalAmountMinorUnits == sale.totalMinorUnits &&
            debt.dueAt == command.debtDueAt?.toEpochMilli()
    }

    private fun debtIdForSale(saleId: String): DebtId = DebtId.from(
        SaleContentIdentity.uuid("sale-debt", saleId),
    )

    private fun SaleWithLines.toDomain(): SaleCart {
        val currency = CurrencyCode.of(sale.currencyCode)
        val domainLines = lines.sortedBy(SaleLineEntity::position).map { line ->
            val quantity = Quantity.of(line.quantity)
            val unitPrice = line.unitPriceMinorUnits?.let { Money.ofMinor(it, currency) }
            val discount = Money.ofMinor(line.discountMinorUnits, currency)
            val tax = Money.ofMinor(line.taxMinorUnits, currency)
            SaleCartLine(
                saleLineId = checkNotNull(SaleLineId.parse(line.saleLineId)),
                productId = checkNotNull(ProductId.parse(line.productId)),
                unitId = checkNotNull(UnitId.parse(line.unitId)),
                locationId = checkNotNull(LocationId.parse(line.locationId)),
                position = line.position,
                productName = line.productNameSnapshot,
                unitCode = line.unitCodeSnapshot,
                locationName = line.locationNameSnapshot,
                barcode = line.barcodeSnapshot,
                quantity = quantity,
                unitPrice = unitPrice,
                discount = discount,
                tax = tax,
                lineTotal = line.lineTotalMinorUnits?.let { Money.ofMinor(it, currency) },
            )
        }
        return SaleCart(
            saleId = checkNotNull(SaleId.parse(sale.saleId)),
            businessId = checkNotNull(BusinessId.parse(sale.businessId)),
            status = SaleStatus.valueOf(sale.status),
            currency = currency,
            lines = domainLines,
            subtotal = Money.ofMinor(sale.subtotalMinorUnits, currency),
            discount = Money.ofMinor(sale.discountMinorUnits, currency),
            tax = Money.ofMinor(sale.taxMinorUnits, currency),
            total = Money.ofMinor(sale.totalMinorUnits, currency),
            contentHash = sale.contentHash,
            version = sale.version,
            createdAt = Instant.ofEpochMilli(sale.createdAt),
            updatedAt = Instant.ofEpochMilli(sale.updatedAt),
            postedAt = sale.postedAt?.let(Instant::ofEpochMilli),
            pendingCheckout = pendingCheckout?.takeIf { sale.status == SaleStatus.DRAFT.name }?.let {
                PendingSaleCheckout(it.debtorName, it.debtDueAt?.let(Instant::ofEpochMilli))
            },
        )
    }
}

/**
 * Recorre las claves con cursor estable y materializa las líneas crudas de un solo lote. La lista
 * final mantiene una proyección compacta por venta; el consumo transitorio deja de crecer con el
 * total de líneas del periodo.
 */
internal suspend fun loadPostedSaleProfitsPaged(
    firstPage: List<PostedSaleProfitPageKey>,
    pageSize: Int,
    loadNextPage: suspend (
        cursor: PostedSaleProfitPageKey,
        limit: Int,
    ) -> List<PostedSaleProfitPageKey>,
    loadRows: suspend (saleIds: List<String>) -> List<PostedSaleProfitRow>,
): List<RealizedSaleProfit> {
    require(pageSize in 1..MAX_POSTED_PROFIT_PAGE_SIZE) {
        "El tamaño de página del reporte debe estar entre 1 y $MAX_POSTED_PROFIT_PAGE_SIZE"
    }
    val profits = mutableListOf<RealizedSaleProfit>()
    val seenSaleIds = mutableSetOf<String>()
    var previousCursor: PostedSaleProfitPageKey? = null
    var page = firstPage

    while (page.isNotEmpty()) {
        require(page.size <= pageSize) { "La consulta excedió el tamaño de página solicitado" }
        requirePostedProfitPageOrder(page, previousCursor)
        require(page.all { seenSaleIds.add(it.saleId) }) {
            "La paginación del reporte repitió una venta"
        }

        val pageIds = page.map(PostedSaleProfitPageKey::saleId)
        val pageIdSet = pageIds.toSet()
        val rows = loadRows(pageIds)
        require(rows.all { it.saleId in pageIdSet }) {
            "La consulta de detalle devolvió una venta ajena a la página"
        }
        val profitsById = realizedSaleProfitsFromRows(rows).associateBy { it.saleId.value }
        require(profitsById.keys == pageIdSet) {
            "La consulta de detalle no devolvió exactamente las ventas de la página"
        }
        pageIds.forEach { saleId -> profits += checkNotNull(profitsById[saleId]) }

        if (page.size < pageSize) break
        previousCursor = page.last()
        page = loadNextPage(checkNotNull(previousCursor), pageSize)
    }
    return profits
}

private fun requirePostedProfitPageOrder(
    page: List<PostedSaleProfitPageKey>,
    previousCursor: PostedSaleProfitPageKey?,
) {
    previousCursor?.let { cursor ->
        require(page.first().isStrictlyOlderThan(cursor)) {
            "La página siguiente no comienza después de su cursor"
        }
    }
    require(page.zipWithNext().all { (newer, older) -> older.isStrictlyOlderThan(newer) }) {
        "Las claves paginadas deben estar en orden cronológico descendente estable"
    }
}

private fun PostedSaleProfitPageKey.isStrictlyOlderThan(
    other: PostedSaleProfitPageKey,
): Boolean = postedAt < other.postedAt || (postedAt == other.postedAt && saleId < other.saleId)

/**
 * Agrega en Kotlin porque SQLite no ofrece aritmética decimal exacta para `unitCost` TEXT.
 * Una venta con cualquier línea no costeable conserva sus ingresos, pero no publica costo ni
 * ganancia parciales. `groupBy` conserva el orden de primera aparición entregado por la consulta;
 * el caso de uso aplica una única ordenación defensiva al resultado comercial final.
 */
internal fun realizedSaleProfitsFromRows(
    rows: List<PostedSaleProfitRow>,
): List<RealizedSaleProfit> = rows
    .groupBy(PostedSaleProfitRow::saleId)
    .values
    .map(::realizedSaleProfitFromRows)

private fun realizedSaleProfitFromRows(rows: List<PostedSaleProfitRow>): RealizedSaleProfit {
    require(rows.isNotEmpty())
    val first = rows.first()
    require(rows.all { row ->
        row.saleId == first.saleId && row.totalMinorUnits == first.totalMinorUnits &&
            row.saleCurrencyCode == first.saleCurrencyCode && row.postedAt == first.postedAt
    }) { "Las filas del reporte no comparten la misma cabecera de venta" }

    val currency = CurrencyCode.of(first.saleCurrencyCode)
    val lines = rows
        .groupBy(PostedSaleProfitRow::saleLineId)
        .values
        .map { lineRows -> realizedSaleLineProfitFromRows(lineRows, currency) }
        .sortedWith(
            compareBy<RealizedSaleLineProfit>(RealizedSaleLineProfit::position)
                .thenBy { it.saleLineId.value },
        )
    val netRevenueMinorUnits = lines.fold(0L) { total, line ->
        Math.addExact(total, line.netRevenue.minorUnits)
    }
    val issues = linkedSetOf<RealizedProfitIssue>()
    lines.forEach { line -> issues += line.issues }
    var accumulatedCost = BigDecimal.ZERO
    if (issues.isEmpty()) {
        lines.forEach { line ->
            accumulatedCost = accumulatedCost.add(requireNotNull(line.historicalCost).amount)
            if (!ProductProfitDecimalPolicy.supports(accumulatedCost)) {
                issues += RealizedProfitIssue.DECIMAL_LIMIT_EXCEEDED
            }
        }
    }

    val netRevenue = Money.ofMinor(netRevenueMinorUnits, currency)
    val historicalCost = if (issues.isEmpty()) {
        ExactMonetaryAmount(accumulatedCost, currency)
    } else {
        null
    }
    val grossProfit = historicalCost?.let { cost ->
        ExactMonetaryAmount(netRevenue.toMajor().subtract(cost.amount), currency)
    }
    return RealizedSaleProfit(
        saleId = checkNotNull(SaleId.parse(first.saleId)),
        totalCharged = Money.ofMinor(first.totalMinorUnits, currency),
        netRevenue = netRevenue,
        historicalCost = historicalCost,
        grossProfit = grossProfit,
        lines = lines,
        postedAt = Instant.ofEpochMilli(first.postedAt),
        issues = issues.toSet(),
    )
}

private fun realizedSaleLineProfitFromRows(
    rows: List<PostedSaleProfitRow>,
    saleCurrency: CurrencyCode,
): RealizedSaleLineProfit {
    require(rows.isNotEmpty())
    val first = rows.first()
    require(rows.all { row ->
        row.saleLineId == first.saleLineId && row.productId == first.productId &&
            row.linePosition == first.linePosition &&
            row.productNameSnapshot == first.productNameSnapshot &&
            row.unitCodeSnapshot == first.unitCodeSnapshot &&
            row.locationNameSnapshot == first.locationNameSnapshot &&
            row.lineCurrencyCode == first.lineCurrencyCode &&
            row.lineQuantity == first.lineQuantity &&
            row.lineTotalMinorUnits == first.lineTotalMinorUnits &&
            row.lineTaxMinorUnits == first.lineTaxMinorUnits
    }) { "Las filas del reporte no comparten la misma cabecera de línea" }

    val lineTotalMinorUnits = requireNotNull(first.lineTotalMinorUnits) {
        "Una venta confirmada no puede tener una línea sin total"
    }
    val lineNetMinorUnits = Math.subtractExact(lineTotalMinorUnits, first.lineTaxMinorUnits)
    require(lineNetMinorUnits >= 0L) { "El impuesto de línea no puede superar su total" }
    val lineQuantity = first.lineQuantity.toPersistedReportDecimalOrNull()
    val quantity = lineQuantity
        ?.takeIf { it.signum() > 0 }
        ?.let { persisted -> runCatching { Quantity.of(persisted) }.getOrNull() }
    val issues = linkedSetOf<RealizedProfitIssue>()
    val lineCurrency = runCatching { CurrencyCode.of(first.lineCurrencyCode) }.getOrNull()
    if (lineCurrency != saleCurrency) issues += RealizedProfitIssue.INVALID_PERSISTED_DATA
    if (quantity == null) issues += RealizedProfitIssue.INVALID_PERSISTED_DATA

    var historicalCostAmount: BigDecimal? = null
    when {
        rows.size != 1 -> issues += RealizedProfitIssue.INVALID_PERSISTED_DATA
        first.movementId == null || first.movementQuantityDelta == null ||
            first.movementUnitCost == null || first.movementCurrencyCode == null -> {
            issues += RealizedProfitIssue.MISSING_HISTORICAL_COST
        }
        else -> {
            val movementQuantity = first.movementQuantityDelta.toPersistedReportDecimalOrNull()
            val unitCost = first.movementUnitCost.toPersistedReportDecimalOrNull()
            val costCurrency = runCatching {
                CurrencyCode.of(first.movementCurrencyCode)
            }.getOrNull()
            if (
                lineQuantity == null || lineQuantity.signum() <= 0 ||
                movementQuantity == null || movementQuantity.signum() >= 0 ||
                movementQuantity.abs().compareTo(lineQuantity) != 0 ||
                unitCost == null || unitCost.signum() < 0 || costCurrency == null
            ) {
                issues += RealizedProfitIssue.INVALID_PERSISTED_DATA
            } else if (costCurrency != saleCurrency) {
                issues += RealizedProfitIssue.COST_CURRENCY_MISMATCH
            } else {
                val extendedCost = movementQuantity.abs().multiply(unitCost)
                if (ProductProfitDecimalPolicy.supports(extendedCost)) {
                    historicalCostAmount = extendedCost
                } else {
                    issues += RealizedProfitIssue.DECIMAL_LIMIT_EXCEEDED
                }
            }
        }
    }

    val netRevenue = Money.ofMinor(lineNetMinorUnits, saleCurrency)
    val historicalCost = historicalCostAmount
        ?.takeIf { issues.isEmpty() }
        ?.let { ExactMonetaryAmount(it, saleCurrency) }
    return RealizedSaleLineProfit(
        saleLineId = checkNotNull(SaleLineId.parse(first.saleLineId)),
        productId = checkNotNull(ProductId.parse(first.productId)),
        position = first.linePosition,
        productName = first.productNameSnapshot,
        unitCode = first.unitCodeSnapshot,
        locationName = first.locationNameSnapshot,
        quantity = quantity,
        totalCharged = Money.ofMinor(lineTotalMinorUnits, saleCurrency),
        netRevenue = netRevenue,
        historicalCost = historicalCost,
        grossProfit = historicalCost?.let { cost ->
            ExactMonetaryAmount(netRevenue.toMajor().subtract(cost.amount), saleCurrency)
        },
        issues = issues.toSet(),
    )
}

private fun String.toPersistedReportDecimalOrNull(): BigDecimal? {
    if (length > MAX_REPORT_DECIMAL_CHARACTERS || !SIGNED_REPORT_DECIMAL.matches(this)) return null
    return runCatching { BigDecimal(this) }.getOrNull()?.takeIf {
        InventoryCostingDecimalPolicy.supportsPersisted(it.abs())
    }
}

private sealed interface SaleCheckoutPreparation {
    data class Ready(val document: SharedSaleDocument, val cloudBusinessId: String?) : SaleCheckoutPreparation
    data class Rejected(val result: CheckoutSaleResult) : SaleCheckoutPreparation
}

internal data class SaleCartTotals(
    val subtotal: Long,
    val discount: Long,
    val tax: Long,
    val total: Long,
    val contentHash: String,
) {
    companion object {
        fun of(currencyCode: String, lines: List<SaleLineEntity>): SaleCartTotals = exactSaleTotals {
            var subtotal = 0L
            var discount = 0L
            var tax = 0L
            val currency = CurrencyCode.of(currencyCode)
            lines.sortedBy(SaleLineEntity::position).forEach { line ->
                line.unitPriceMinorUnits?.let { unitPrice ->
                    val gross = calculateSaleLineGross(
                        Money.ofMinor(unitPrice, currency),
                        Quantity.of(line.quantity),
                    ).minorUnits
                    subtotal = Math.addExact(subtotal, gross)
                }
                discount = Math.addExact(discount, line.discountMinorUnits)
                tax = Math.addExact(tax, line.taxMinorUnits)
            }
            val total = Math.addExact(Math.subtractExact(subtotal, discount), tax)
            SaleCartTotals(
                subtotal = subtotal,
                discount = discount,
                tax = tax,
                total = total,
                contentHash = SaleContentIdentity.hash(currencyCode, lines),
            )
        }
    }
}

/** Convierte únicamente fallos de aritmética comercial en un resultado cerrado del repositorio. */
private inline fun <T> exactSaleTotals(block: () -> T): T = try {
    block()
} catch (failure: DomainRuleViolation) {
    throw InvalidSaleTotals(failure)
} catch (failure: ArithmeticException) {
    throw InvalidSaleTotals(failure)
} catch (failure: IllegalArgumentException) {
    throw InvalidSaleTotals(failure)
}

internal object SaleContentIdentity {
    fun hash(currencyCode: String, lines: List<SaleLineEntity>): String = digest(
        buildList {
            add("sale-content-v1")
            add(currencyCode)
            lines.sortedBy(SaleLineEntity::position).forEach { line ->
                add(line.saleLineId)
                add(line.position.toString())
                add(line.productId)
                add(line.unitId)
                add(line.locationId)
                add(line.quantity)
                add(line.unitPriceMinorUnits?.toString() ?: "<price-missing>")
                add(line.discountMinorUnits.toString())
                add(line.taxMinorUnits.toString())
                add(line.lineTotalMinorUnits?.toString() ?: "<total-missing>")
                add(line.currencyCode)
            }
        },
    ).toHex()

    fun checkoutKey(command: CheckoutSaleCommand): String =
        "sale-checkout:v1:${command.saleId.value}:${command.expectedVersion}:" +
            command.expectedContentHash

    fun uuid(vararg parts: String): UUID {
        val bytes = digest(parts.toList())
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    private fun digest(parts: List<String>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

private class SaleCheckoutRace : RuntimeException()
private class SaleCartCasConflict : RuntimeException()
private class InvalidSaleTotals(cause: Throwable) : RuntimeException(cause)

internal const val POSTED_PROFIT_PAGE_SIZE = 100
private const val MAX_POSTED_PROFIT_PAGE_SIZE = 500
private const val MAX_REPORT_DECIMAL_CHARACTERS = 166
private val SIGNED_REPORT_DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")
