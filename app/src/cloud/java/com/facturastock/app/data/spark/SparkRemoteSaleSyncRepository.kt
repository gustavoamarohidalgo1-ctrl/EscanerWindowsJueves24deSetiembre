package com.facturastock.app.data.spark

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.data.sync.FirebaseSaleWireMapper
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.AuthorizedLocalBalance
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.RemoteSalePostResult
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Venta autoritativa para Spark: una transacción Firestore crea la venta/deuda y descuenta todos
 * los saldos. No llama Functions ni Storage. El binding Hilt decide cuándo usar esta ruta.
 */
@Singleton
class SparkRemoteSaleSyncRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val appConfiguration: AppConfigurationRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
    private val database: FacturaStockDatabase,
    private val inventoryBootstrapper: SparkInventoryBootstrapper,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : RemoteSaleSyncRepository {
    override val available: Boolean
        get() = runtime.config != null

    override suspend fun postSale(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): RemoteSalePostResult = withContext<RemoteSalePostResult>(dispatchers.io) {
        val cloudBusinessId = cloudBusinessBindings.targetFor(localBusinessId)
            ?: return@withContext RemoteSalePostResult.NotRequired
        if (!available || !appConfiguration.current().backupEnabled) {
            return@withContext RemoteSalePostResult.OnlineRequired
        }
        val session = accountRepository.observeSession().first()
        if (session !is AccountSession.Active) {
            return@withContext RemoteSalePostResult.OnlineRequired
        }
        val link = session.link
        if (
            link == null || link.localBusinessId != localBusinessId ||
            link.cloudBusinessId != cloudBusinessId || link.role != BusinessRole.OWNER ||
            !cloudBusinessBindings.matches(localBusinessId, cloudBusinessId)
        ) {
            return@withContext RemoteSalePostResult.Rejected
        }
        val firestore = runtime.firestore()
            ?: return@withContext RemoteSalePostResult.OnlineRequired
        val outbound = buildOutbound(localBusinessId, cloudBusinessId, document)
            ?: return@withContext RemoteSalePostResult.Rejected
        try {
            val businessRef = firestore.collection(SparkFirestoreSchema.BUSINESSES)
                .document(cloudBusinessId.value)
            val saleRef = businessRef.collection(SparkFirestoreSchema.SALES)
                .document(document.saleId.value)
            val keyRef = businessRef.collection(SparkFirestoreSchema.SALE_KEYS)
                .document(outbound.operationDocumentId)
            val metadataRef = businessRef.collection(SparkFirestoreSchema.SYNC)
                .document(SparkFirestoreSchema.INVENTORY_METADATA)
            val changeRef = businessRef.collection(SparkFirestoreSchema.INVENTORY_CHANGES)
                .document(outbound.receiptId)
            val debtRef = document.credit?.let { credit ->
                businessRef.collection(SparkFirestoreSchema.DEBTS).document(credit.debtId.value)
            }
            val productRefs = outbound.targets
                .map(SparkSaleInventoryTarget::remoteProductId)
                .distinct()
                .associateWith { id ->
                    businessRef.collection(SparkFirestoreSchema.PRODUCTS).document(id)
                }
            val balanceRefs = outbound.targets.associate { target ->
                target.balanceDocumentId to businessRef
                    .collection(SparkFirestoreSchema.INVENTORY_BALANCES)
                    .document(target.balanceDocumentId)
            }
            val submit: suspend () -> SparkSaleAck = {
                firestore.runTransaction { transaction ->
                    postInTransaction(
                        transaction = transaction,
                        session = session,
                        outbound = outbound,
                        businessRef = businessRef,
                        saleRef = saleRef,
                        keyRef = keyRef,
                        metadataRef = metadataRef,
                        changeRef = changeRef,
                        debtRef = debtRef,
                        productRefs = productRefs,
                        balanceRefs = balanceRefs,
                    )
                }.await()
            }
            val ack = try {
                submit()
            } catch (_: SparkMutationFailure.InventoryMigrationRequired) {
                // Las compras abiertas ya estan reflejadas en Room pero aun no en cloud.
                // Se revierten de la semilla y la outbox las reaplica sin duplicarlas.
                if (
                    inventoryBootstrapper.ensureInitialized(
                        firestore = firestore,
                        localBusinessId = localBusinessId,
                        cloudBusinessId = cloudBusinessId,
                        ownerUid = session.uid,
                    ) != SparkInventoryBootstrapState.EMPTY_LOCAL_INVENTORY
                ) {
                    submit()
                } else {
                    throw SparkMutationFailure.InventoryMigrationRequired
                }
            }
            ack.toResult(outbound)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SparkMutationFailure.InsufficientStock) {
            RemoteSalePostResult.InsufficientStock(
                productId = failure.target.localKey.productId,
                locationId = failure.target.localKey.locationId,
                requested = failure.requested,
                available = failure.available,
            )
        } catch (_: SparkMutationFailure.InventoryMigrationRequired) {
            RemoteSalePostResult.InventoryMigrationRequired
        } catch (_: SparkMutationFailure.Stale) {
            RemoteSalePostResult.Rejected
        } catch (_: SparkMutationFailure.Rejected) {
            RemoteSalePostResult.Rejected
        } catch (_: SparkMutationFailure.CorruptRemoteData) {
            RemoteSalePostResult.Rejected
        } catch (failure: Exception) {
            mapSaleFailure(failure)
        }
    }

    override suspend fun pullInventoryChanges(
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SharedInventoryPullPage> = withContext(dispatchers.io) {
        if (sinceSeq !in 0..SparkFirestoreSchema.MAX_SAFE_SEQUENCE ||
            limit !in 1..SparkFirestoreSchema.MAX_PULL_LIMIT
        ) {
            return@withContext DomainResult.Failure(AccountError.Unexpected)
        }
        val firestore = runtime.firestore()
            ?: return@withContext DomainResult.Failure(AccountError.Unavailable)
        val session = accountRepository.observeSession().first()
        if (session !is AccountSession.Active) {
            return@withContext DomainResult.Failure(AccountError.NotAuthenticated)
        }
        val link = session.link
            ?: return@withContext DomainResult.Failure(AccountError.Unavailable)
        if (
            link.cloudBusinessId != cloudBusinessId || link.role != BusinessRole.OWNER ||
            !cloudBusinessBindings.matches(link.localBusinessId, cloudBusinessId)
        ) {
            return@withContext DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
        }
        try {
            val businessRef = firestore.collection(SparkFirestoreSchema.BUSINESSES)
                .document(cloudBusinessId.value)
            // La consulta se hace antes del metadata. Si otra transacción entra entre ambas
            // lecturas, latestSeq avanza y `hasMore` obliga a solicitar la página siguiente.
            val snapshot = businessRef.collection(SparkFirestoreSchema.INVENTORY_CHANGES)
                .whereGreaterThan("seq", sinceSeq)
                .orderBy("seq")
                .limit(limit.toLong() + 1L)
                .get(Source.SERVER)
                .await()
            val metadata = businessRef.collection(SparkFirestoreSchema.SYNC)
                .document(SparkFirestoreSchema.INVENTORY_METADATA)
                .get(Source.SERVER)
                .await()
            val bootstrap = if (sinceSeq == 0L) {
                businessRef.collection(SparkFirestoreSchema.SYNC)
                    .document(SPARK_INVENTORY_BOOTSTRAP_DOCUMENT)
                    .get(Source.SERVER)
                    .await()
            } else {
                null
            }
            val bootstrapChange = if (sinceSeq == 0L) {
                businessRef.collection(SparkFirestoreSchema.INVENTORY_CHANGES)
                    .document(SparkFirestoreSchema.inventoryBootstrapReceiptId(cloudBusinessId.value))
                    .get(Source.SERVER)
                    .await()
            } else {
                null
            }
            inventoryBootstrapper.requirePullOnboarding(
                localBusinessId = link.localBusinessId,
                cloudBusinessId = cloudBusinessId,
                sinceSeq = sinceSeq,
                metadata = metadata.data,
                bootstrap = bootstrap?.data,
                bootstrapChange = bootstrapChange?.data,
            )
            val latestSeq = metadata.data?.safeLong("seq") ?: 0L
            if (latestSeq !in sinceSeq..SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
                throw AccountException(AccountError.Unexpected)
            }
            val selected = snapshot.documents.take(limit)
            val nextCursor = selected.lastOrNull()?.data?.safeLong("seq") ?: sinceSeq
            val hasMore = snapshot.documents.size > selected.size || nextCursor < latestSeq
            val raw = linkedMapOf<String, Any?>(
                "changes" to selected.map(DocumentSnapshot::inventoryChangeWireMap),
                "nextCursor" to nextCursor,
                "hasMore" to hasMore,
                "latestSeq" to latestSeq,
            )
            DomainResult.Success(
                FirebaseSaleWireMapper.pullPage(
                    raw = raw,
                    expectedBusinessId = cloudBusinessId,
                    expectedPreviousSeq = sinceSeq,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AccountException) {
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            DomainResult.Failure(mapFirestoreAccountFailure(failure))
        }
    }

    private fun postInTransaction(
        transaction: Transaction,
        session: AccountSession.Active,
        outbound: SparkOutboundSale,
        businessRef: DocumentReference,
        saleRef: DocumentReference,
        keyRef: DocumentReference,
        metadataRef: DocumentReference,
        changeRef: DocumentReference,
        debtRef: DocumentReference?,
        productRefs: Map<String, DocumentReference>,
        balanceRefs: Map<String, DocumentReference>,
    ): SparkSaleAck {
        // Firestore exige todas las lecturas antes de la primera escritura.
        val business = transaction.get(businessRef)
        val existingSale = transaction.get(saleRef)
        val key = transaction.get(keyRef)
        val metadata = transaction.get(metadataRef)
        val existingChange = transaction.get(changeRef)
        val existingDebt = debtRef?.let(transaction::get)
        val products = productRefs.mapValues { (_, reference) -> transaction.get(reference) }
        val balances = balanceRefs.mapValues { (_, reference) -> transaction.get(reference) }

        requireOwnedBusiness(business, session.uid)
        if (existingSale.exists()) {
            return replayAck(existingSale, key, existingChange, outbound)
        }
        if (key.exists() || existingChange.exists() || existingDebt?.exists() == true) {
            throw SparkMutationFailure.Rejected
        }
        outbound.targets.forEach { target ->
            requireUsableProduct(products[target.remoteProductId], target, outbound.localDocument.currency)
        }
        if (balances.values.any { !it.exists() }) {
            throw SparkMutationFailure.InventoryMigrationRequired
        }
        val metadataSeq = metadata.data?.safeLong("seq") ?: 0L
        val plan = SparkSaleMutationPlanner.plan(
            outbound = outbound,
            metadataSeq = metadataSeq,
            rawBalancesByDocumentId = balances.mapValues { (_, snapshot) ->
                snapshot.data ?: throw SparkMutationFailure.InventoryMigrationRequired
            },
            now = clock.now(),
        )
        val serverTimestamp = FieldValue.serverTimestamp()
        val effectiveDocument = LinkedHashMap(outbound.wireDocument).apply {
            this["updatedAt"] = plan.postedAt.toEpochMilli()
            this["postedAt"] = plan.postedAt.toEpochMilli()
        }
        val projections = plan.balances.map(SparkPlannedInventoryBalance::projection)
        val movements = plan.balances.map { balance ->
            val line = balance.target.line
            val movement = linkedMapOf<String, Any?>(
                "movementId" to SparkSaleWire.movementId(
                    outbound.localDocument.saleId.value,
                    line.saleLineId.value,
                ),
                "saleId" to outbound.localDocument.saleId.value,
                "saleLineId" to line.saleLineId.value,
                "productId" to balance.target.remoteProductId,
                "sourceLocationId" to line.locationId.value,
                "locationName" to balance.target.locationName,
                "canonicalLocationName" to balance.target.canonicalLocationName,
                "type" to "SALE",
                "quantityDelta" to line.quantity.value.negate().canonicalDecimal(),
                "unitCost" to balance.averageUnitCost,
                "currency" to balance.currency.value,
                "occurredAt" to plan.postedAt.toEpochMilli(),
            )
            movement
        }
        val feedSale = LinkedHashMap(effectiveDocument).apply {
            this["receiptId"] = outbound.receiptId
            this["seq"] = plan.seq
            this["movements"] = movements
        }
        val openedDebt = outbound.localDocument.credit?.let { credit ->
            linkedMapOf<String, Any?>(
                "debtId" to credit.debtId.value,
                "businessId" to outbound.cloudBusinessId.value,
                "saleId" to outbound.localDocument.saleId.value,
                "debtorNameSnapshot" to credit.debtorNameSnapshot,
                "currency" to outbound.localDocument.currency.value,
                "originalMinorUnits" to outbound.localDocument.total.minorUnits,
                "balanceMinorUnits" to outbound.localDocument.total.minorUnits,
                "status" to DebtStatus.OPEN.name,
                "dueAt" to credit.dueAt?.toEpochMilli(),
                "version" to 1L,
                "createdAt" to plan.postedAt.toEpochMilli(),
                "updatedAt" to plan.postedAt.toEpochMilli(),
                "paidAt" to null,
            )
        }
        val expectedVersions = plan.balances.associate { balance ->
            balance.target.balanceDocumentId to balance.expectedVersion
        }
        val resultingVersions = plan.balances.associate { balance ->
            balance.target.balanceDocumentId to balance.resultingVersion
        }

        val saleRecord = LinkedHashMap(effectiveDocument).apply {
            this["schemaVersion"] = SparkFirestoreSchema.SCHEMA_VERSION
            this["operationId"] = outbound.operationId
            this["idempotencyKey"] = outbound.operationId
            this["syncPayloadHash"] = outbound.payloadHash
            this["actorUid"] = session.uid
            this["authorizedRole"] = BusinessRole.OWNER.name
            this["receiptId"] = outbound.receiptId
            this["inventorySeq"] = plan.seq
            this["expectedInventoryVersions"] = expectedVersions
            this["resultingInventoryVersions"] = resultingVersions
            this["balances"] = projections
            this["syncedAt"] = serverTimestamp
            if (openedDebt != null) this["debt"] = openedDebt
        }
        transaction.set(saleRef, saleRecord)
        @Suppress("UNCHECKED_CAST")
        val wireLines = effectiveDocument.getValue("lines") as List<Map<String, Any?>>
        wireLines.forEach { line ->
            val lineId = line.getValue("saleLineId") as String
            transaction.set(
                saleRef.collection(SparkFirestoreSchema.SALE_LINES).document(lineId),
                LinkedHashMap(line).apply {
                    this["schemaVersion"] = SparkFirestoreSchema.SCHEMA_VERSION
                    this["businessId"] = outbound.cloudBusinessId.value
                    this["saleId"] = outbound.localDocument.saleId.value
                    this["operationId"] = outbound.operationId
                    this["syncedAt"] = serverTimestamp
                },
            )
        }
        plan.balances.forEach { balance ->
            val reference = balanceRefs.getValue(balance.target.balanceDocumentId)
            transaction.set(
                reference,
                SparkInventoryBalanceCodec.record(
                    businessId = outbound.cloudBusinessId,
                    productId = balance.target.remoteProductId,
                    locationName = balance.target.locationName,
                    quantityOnHand = BigDecimal(balance.quantityOnHand),
                    averageUnitCost = BigDecimal(balance.averageUnitCost),
                    currency = balance.currency,
                    version = balance.resultingVersion,
                    lastSeq = plan.seq,
                    updatedAtMillis = plan.postedAt.toEpochMilli(),
                    lastSourceLocationId = balance.target.line.locationId.value,
                    operationId = outbound.operationId,
                    expectedVersion = balance.expectedVersion,
                    syncedAt = serverTimestamp,
                ),
            )
        }
        movements.zip(plan.balances).forEach { (movement, balance) ->
            val movementId = movement.getValue("movementId") as String
            transaction.set(
                businessRef.collection(SparkFirestoreSchema.STOCK_MOVEMENTS).document(movementId),
                LinkedHashMap(movement).apply {
                    this["schemaVersion"] = SparkFirestoreSchema.SCHEMA_VERSION
                    this["businessId"] = outbound.cloudBusinessId.value
                    this["operationId"] = outbound.operationId
                    this["expectedInventoryVersion"] = balance.expectedVersion
                    this["resultingInventoryVersion"] = balance.resultingVersion
                    this["syncedAt"] = serverTimestamp
                },
            )
        }
        if (debtRef != null && openedDebt != null) {
            transaction.set(
                debtRef,
                LinkedHashMap(openedDebt).apply {
                    this["schemaVersion"] = SparkFirestoreSchema.SCHEMA_VERSION
                    this["operationId"] = outbound.operationId
                    this["openingOperationId"] = outbound.operationId
                    this["lastOperationId"] = outbound.operationId
                    this["receiptId"] = outbound.receiptId
                    this["inventorySeq"] = plan.seq
                    this["actorUid"] = session.uid
                    this["authorizedRole"] = BusinessRole.OWNER.name
                    this["syncedAt"] = serverTimestamp
                },
            )
        }
        transaction.set(
            changeRef,
            linkedMapOf(
                "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                "businessId" to outbound.cloudBusinessId.value,
                "operationId" to outbound.operationId,
                "kind" to "SALE",
                "seq" to plan.seq,
                "receiptId" to outbound.receiptId,
                "sale" to feedSale,
                "balances" to projections,
                "syncedAt" to serverTimestamp,
            ),
        )
        transaction.set(
            keyRef,
            linkedMapOf(
                "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                "businessId" to outbound.cloudBusinessId.value,
                "operationId" to outbound.operationId,
                "saleId" to outbound.localDocument.saleId.value,
                "receiptId" to outbound.receiptId,
                "seq" to plan.seq,
                "requestHash" to outbound.payloadHash,
                "expectedInventoryVersions" to expectedVersions,
                "resultingInventoryVersions" to resultingVersions,
                "createdAt" to serverTimestamp,
            ),
        )
        transaction.set(
            metadataRef,
            linkedMapOf(
                "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                "businessId" to outbound.cloudBusinessId.value,
                "seq" to plan.seq,
                "lastKind" to "SALE",
                "lastReceiptId" to outbound.receiptId,
                "lastOperationId" to outbound.operationId,
                "updatedAt" to serverTimestamp,
            ),
            SetOptions.merge(),
        )
        return SparkSaleAck(
            seq = plan.seq,
            postedAt = plan.postedAt,
            receiptId = outbound.receiptId,
            balances = plan.balances.map { balance ->
                SparkAckBalance(
                    remoteProductId = balance.target.remoteProductId,
                    locationName = balance.target.locationName,
                    quantityOnHand = balance.quantityOnHand,
                    averageUnitCost = balance.averageUnitCost,
                    currency = balance.currency,
                    version = balance.resultingVersion,
                    updatedAt = plan.postedAt,
                )
            },
        )
    }

    private suspend fun buildOutbound(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): SparkOutboundSale? = database.withTransaction {
        val remoteIds = linkedMapOf<ProductId, String>()
        document.lines.forEach { line ->
            val link = database.catalogSyncLinkDao().findByLocal(
                localBusinessId = localBusinessId.value,
                entityType = PRODUCT,
                localEntityId = line.productId.value,
            )
            if (link != null && link.cloudBusinessId != cloudBusinessId.value) {
                return@withTransaction null
            }
            val previous = remoteIds.putIfAbsent(
                line.productId,
                link?.remoteEntityId ?: line.productId.value,
            )
            if (previous != null && previous != (link?.remoteEntityId ?: line.productId.value)) {
                return@withTransaction null
            }
        }
        runCatching { SparkSaleWire.prepare(document, cloudBusinessId, remoteIds) }.getOrNull()
    }
    private fun SparkSaleAck.toResult(outbound: SparkOutboundSale): RemoteSalePostResult {
        val targets = outbound.targets.associateBy { target ->
            target.remoteProductId to target.canonicalLocationName
        }
        val mapped = balances.map { balance ->
            val target = targets[balance.remoteProductId to canonicalLocationName(balance.locationName)]
                ?: return RemoteSalePostResult.Rejected
            AuthorizedLocalBalance(
                productId = target.localKey.productId,
                locationId = target.localKey.locationId,
                quantityOnHand = balance.quantityOnHand,
                averageUnitCost = balance.averageUnitCost,
                currencyCode = balance.currency.value,
                remoteVersion = balance.version,
                updatedAt = balance.updatedAt,
            )
        }
        if (mapped.size != targets.size || mapped.map { it.productId to it.locationId }.distinct().size != mapped.size) {
            return RemoteSalePostResult.Rejected
        }
        return RemoteSalePostResult.Authorized(
            saleId = outbound.localDocument.saleId,
            receiptId = receiptId,
            seq = seq,
            postedAt = postedAt,
            balances = mapped,
        )
    }

    private companion object {
        const val PRODUCT = "PRODUCT"
    }
}

private data class SparkAckBalance(
    val remoteProductId: String,
    val locationName: String,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currency: CurrencyCode,
    val version: Long,
    val updatedAt: Instant,
)

private data class SparkSaleAck(
    val seq: Long,
    val postedAt: Instant,
    val receiptId: String,
    val balances: List<SparkAckBalance>,
)

private fun requireOwnedBusiness(snapshot: DocumentSnapshot, uid: String) {
    val data = snapshot.data ?: throw SparkMutationFailure.Rejected
    if (
        data["ownerUid"] != uid || data["accountDeletionLocked"] == true ||
        data["sparkDirectWritesEnabled"] == false
    ) {
        throw SparkMutationFailure.Rejected
    }
}

private fun requireUsableProduct(
    snapshot: DocumentSnapshot?,
    target: SparkSaleInventoryTarget,
    currency: CurrencyCode,
) {
    val data = snapshot?.data ?: throw SparkMutationFailure.Rejected
    if (data["entityType"] != "PRODUCT") throw SparkMutationFailure.Rejected
    @Suppress("UNCHECKED_CAST")
    val product = data["snapshot"] as? Map<String, Any?> ?: throw SparkMutationFailure.Rejected
    if (product["status"] != "ACTIVE") throw SparkMutationFailure.Rejected
    @Suppress("UNCHECKED_CAST")
    val unit = product["inventoryUnit"] as? Map<String, Any?>
        ?: throw SparkMutationFailure.Rejected
    if (unit["code"] != target.line.unitCode) throw SparkMutationFailure.Rejected
    val priceCurrency = product["salePriceCurrencyCode"]
    if (priceCurrency != null && priceCurrency != currency.value) throw SparkMutationFailure.Rejected
}

private fun replayAck(
    sale: DocumentSnapshot,
    key: DocumentSnapshot,
    change: DocumentSnapshot,
    outbound: SparkOutboundSale,
): SparkSaleAck {
    val data = sale.data ?: throw SparkMutationFailure.CorruptRemoteData
    val keyData = key.data ?: throw SparkMutationFailure.CorruptRemoteData
    val changeData = change.data ?: throw SparkMutationFailure.CorruptRemoteData
    if (
        data["operationId"] != outbound.operationId ||
        data["syncPayloadHash"] != outbound.payloadHash ||
        data["receiptId"] != outbound.receiptId ||
        keyData["operationId"] != outbound.operationId ||
        keyData["requestHash"] != outbound.payloadHash ||
        changeData["operationId"] != outbound.operationId ||
        changeData["receiptId"] != outbound.receiptId
    ) {
        throw SparkMutationFailure.Rejected
    }
    val seq = data.safeLong("inventorySeq")
    if (keyData.safeLong("seq") != seq || changeData.safeLong("seq") != seq) {
        throw SparkMutationFailure.CorruptRemoteData
    }
    val postedAt = Instant.ofEpochMilli(data.safeLong("postedAt"))
    @Suppress("UNCHECKED_CAST")
    val balances = data["balances"] as? List<Map<String, Any?>>
        ?: throw SparkMutationFailure.CorruptRemoteData
    return SparkSaleAck(
        seq = seq,
        postedAt = postedAt,
        receiptId = outbound.receiptId,
        balances = balances.map { raw ->
            SparkAckBalance(
                remoteProductId = raw.requiredString("productId"),
                locationName = raw.requiredString("locationName"),
                quantityOnHand = raw.requiredString("quantityOnHand"),
                averageUnitCost = raw.requiredString("averageUnitCost"),
                currency = CurrencyCode.of(raw.requiredString("currency")),
                version = raw.safeLong("version"),
                updatedAt = Instant.ofEpochMilli(raw.safeLong("updatedAtMillis")),
            )
        },
    )
}

private fun DocumentSnapshot.inventoryChangeWireMap(): Map<String, Any?> {
    val data = data ?: throw AccountException(AccountError.Unexpected)
    val kind = data.requiredString("kind")
    return linkedMapOf<String, Any?>(
        "kind" to kind,
        "seq" to data.safeLong("seq"),
        "receiptId" to data.requiredString("receiptId"),
        "sale" to data["sale"],
        "balances" to data["balances"],
        "syncedAtMillis" to (data["syncedAt"] as? Timestamp)?.toDate()?.time,
    ).apply {
        if (kind == "DEBT_PAYMENT") {
            this["debt"] = data["debt"]
            this["payment"] = data["payment"]
        }
    }
}

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: throw SparkMutationFailure.CorruptRemoteData

private fun Map<String, Any?>.safeLong(key: String): Long = when (val value = this[key]) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toLong()
    is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
    else -> null
} ?: throw SparkMutationFailure.CorruptRemoteData

private fun mapSaleFailure(failure: Exception): RemoteSalePostResult = when (failure) {
    is FirebaseNetworkException, is IOException -> RemoteSalePostResult.OnlineRequired
    is FirebaseFirestoreException -> when (failure.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
        FirebaseFirestoreException.Code.UNAUTHENTICATED,
        FirebaseFirestoreException.Code.CANCELLED,
        -> RemoteSalePostResult.OnlineRequired
        else -> RemoteSalePostResult.Rejected
    }
    else -> RemoteSalePostResult.Rejected
}

private fun mapFirestoreAccountFailure(failure: Exception): AccountError = when (failure) {
    is FirebaseNetworkException, is IOException -> AccountError.NetworkUnavailable
    is FirebaseFirestoreException -> when (failure.code) {
        FirebaseFirestoreException.Code.UNAUTHENTICATED -> AccountError.SessionExpired
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> AccountError.PermissionDenied
        FirebaseFirestoreException.Code.NOT_FOUND -> AccountError.NotFound
        FirebaseFirestoreException.Code.ALREADY_EXISTS,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.FAILED_PRECONDITION,
        -> AccountError.Conflict
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.INTERNAL,
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
        FirebaseFirestoreException.Code.CANCELLED,
        -> AccountError.NetworkUnavailable
        else -> AccountError.Unexpected
    }
    else -> AccountError.Unexpected
}

private const val SPARK_INVENTORY_BOOTSTRAP_DOCUMENT = "inventoryBootstrap"
