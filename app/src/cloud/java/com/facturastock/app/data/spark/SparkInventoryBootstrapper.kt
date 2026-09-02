package com.facturastock.app.data.spark

import androidx.room.withTransaction
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.RemoteSyncStateEntity
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PurchaseReadRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class SparkInventoryBootstrapState {
    BOOTSTRAPPED,
    ALREADY_INITIALIZED,
    EMPTY_LOCAL_INVENTORY,
}

internal enum class SparkInventoryOnboardingDecision {
    CONTINUE,
    ACKNOWLEDGE_MATCHING_BASELINE,
    REJECT,
}

/** Evidencia inmutable que une metadata, bootstrap y el primer evento del ledger remoto. */
internal data class SparkInventoryBootstrapEvidence(
    val ledgerHash: String,
    val metadataSeq: Long,
)

/**
 * Decisión pura usada tanto por el bootstrap como por el pull inicial.
 *
 * Un cursor cero solo puede adoptar el stream si Room está realmente vacío o si su baseline
 * reconstruido coincide byte a byte con la semilla remota. Así un segundo dispositivo nunca
 * sustituye inventario legacy distinto por el contenido del primero de forma silenciosa.
 */
internal object SparkInventoryOnboardingPolicy {
    fun decide(
        localInventorySeq: Long,
        requestedSinceSeq: Long,
        localLedgerHash: String?,
        remoteEvidence: SparkInventoryBootstrapEvidence?,
    ): SparkInventoryOnboardingDecision {
        if (
            localInventorySeq !in 0..SparkFirestoreSchema.MAX_SAFE_SEQUENCE ||
            requestedSinceSeq !in 0..SparkFirestoreSchema.MAX_SAFE_SEQUENCE ||
            localInventorySeq != requestedSinceSeq
        ) {
            return SparkInventoryOnboardingDecision.REJECT
        }
        if (requestedSinceSeq > 0L || localLedgerHash == null) {
            return SparkInventoryOnboardingDecision.CONTINUE
        }
        return if (remoteEvidence?.ledgerHash == localLedgerHash) {
            SparkInventoryOnboardingDecision.ACKNOWLEDGE_MATCHING_BASELINE
        } else {
            SparkInventoryOnboardingDecision.REJECT
        }
    }

    fun acknowledgeBootstrap(state: RemoteSyncStateEntity): RemoteSyncStateEntity {
        return if (state.inventorySeq == 0L) {
            state.copy(inventorySeq = BOOTSTRAP_SEQUENCE)
        } else {
            state
        }
    }
}

/**
 * Inicializa una sola vez el ledger Spark desde la verdad Room.
 *
 * Room ya contiene todas las compras pendientes, aunque cloud todavia no. Para no contabilizarlas
 * dos veces se revierten primero en orden causal inverso y luego la outbox las reaplica una a una.
 * La venta que provoca el bootstrap aun no ha tocado Room y, por tanto, no forma parte del ajuste.
 */
@Singleton
class SparkInventoryBootstrapper @Inject constructor(
    private val database: FacturaStockDatabase,
    private val purchaseReads: PurchaseReadRepository,
) {
    internal suspend fun ensureInitialized(
        firestore: FirebaseFirestore,
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        ownerUid: String,
    ): SparkInventoryBootstrapState {
        val localCursor = localInventoryCursor(cloudBusinessId)
        val businessRef = firestore.collection(SparkFirestoreSchema.BUSINESSES)
            .document(cloudBusinessId.value)
        val metadataRef = businessRef.collection(SparkFirestoreSchema.SYNC)
            .document(SparkFirestoreSchema.INVENTORY_METADATA)
        val bootstrapRef = businessRef.collection(SparkFirestoreSchema.SYNC)
            .document(INVENTORY_BOOTSTRAP_DOCUMENT)
        val knownMetadata = metadataRef.get(Source.SERVER).await()
        if (knownMetadata.exists()) {
            val seq = knownMetadata.data?.bootstrapLong("seq")
                ?: throw SparkMutationFailure.CorruptRemoteData
            if (seq !in BOOTSTRAP_SEQUENCE..SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
                throw SparkMutationFailure.CorruptRemoteData
            }
            if (localCursor > seq) throw SparkMutationFailure.CorruptRemoteData
            if (localCursor > 0L) {
                return SparkInventoryBootstrapState.ALREADY_INITIALIZED
            }
            val payload = buildPayload(
                localBusinessId = localBusinessId,
                cloudBusinessId = cloudBusinessId,
            ) ?: throw SparkMutationFailure.InventoryMigrationRequired
            val changeRef = businessRef.collection(SparkFirestoreSchema.INVENTORY_CHANGES)
                .document(payload.receiptId)
            val knownBootstrap = bootstrapRef.get(Source.SERVER).await()
            val knownChange = changeRef.get(Source.SERVER).await()
            val evidence = sparkInventoryBootstrapEvidence(
                metadata = knownMetadata.data,
                bootstrap = knownBootstrap.data,
                change = knownChange.data,
                cloudBusinessId = cloudBusinessId.value,
            ) ?: throw SparkMutationFailure.CorruptRemoteData
            if (
                SparkInventoryOnboardingPolicy.decide(
                    localInventorySeq = localCursor,
                    requestedSinceSeq = 0L,
                    localLedgerHash = payload.ledgerHash,
                    remoteEvidence = evidence,
                ) != SparkInventoryOnboardingDecision.ACKNOWLEDGE_MATCHING_BASELINE
            ) {
                throw SparkMutationFailure.InventoryMigrationRequired
            }
            acknowledgeBootstrap(cloudBusinessId)
            return SparkInventoryBootstrapState.ALREADY_INITIALIZED
        }
        if (localCursor > 0L) throw SparkMutationFailure.InventoryMigrationRequired
        val payload = buildPayload(
            localBusinessId = localBusinessId,
            cloudBusinessId = cloudBusinessId,
        ) ?: return SparkInventoryBootstrapState.EMPTY_LOCAL_INVENTORY
        val changeRef = businessRef.collection(SparkFirestoreSchema.INVENTORY_CHANGES)
            .document(payload.receiptId)
        val productRefs = payload.balances
            .map(SparkBootstrapBalance::remoteProductId)
            .distinct()
            .associateWith { remoteProductId ->
                businessRef.collection(SparkFirestoreSchema.PRODUCTS).document(remoteProductId)
            }
        val balanceRefs = payload.balances.associate { balance ->
            balance.balanceDocumentId to businessRef
                .collection(SparkFirestoreSchema.INVENTORY_BALANCES)
                .document(balance.balanceDocumentId)
        }
        val result = firestore.runTransaction { transaction ->
            // Firestore Android exige terminar todas las lecturas antes de la primera escritura.
            val business = transaction.get(businessRef)
            val bootstrap = transaction.get(bootstrapRef)
            val metadata = transaction.get(metadataRef)
            val change = transaction.get(changeRef)
            val products = productRefs.mapValues { (_, ref) -> transaction.get(ref) }
            val existingBalances = balanceRefs.mapValues { (_, ref) -> transaction.get(ref) }
            requireBootstrapOwner(business, ownerUid)

            if (metadata.exists()) {
                val evidence = sparkInventoryBootstrapEvidence(
                    metadata = metadata.data,
                    bootstrap = bootstrap.data,
                    change = change.data,
                    cloudBusinessId = cloudBusinessId.value,
                ) ?: throw SparkMutationFailure.CorruptRemoteData
                if (evidence.ledgerHash != payload.ledgerHash) {
                    throw SparkMutationFailure.InventoryMigrationRequired
                }
                return@runTransaction SparkInventoryBootstrapState.ALREADY_INITIALIZED
            }
            if (bootstrap.exists() || change.exists() || existingBalances.values.any { it.exists() }) {
                throw SparkMutationFailure.InventoryMigrationRequired
            }
            requireBootstrapProducts(
                snapshots = products,
                balances = payload.balances,
                cloudBusinessId = cloudBusinessId,
                ownerUid = ownerUid,
            )
            val serverTimestamp = FieldValue.serverTimestamp()
            val projections = payload.balances.map { balance ->
                SparkInventoryBalanceCodec.projection(
                    productId = balance.remoteProductId,
                    locationName = balance.locationName,
                    quantityOnHand = balance.quantityOnHand,
                    averageUnitCost = balance.averageUnitCost,
                    currency = balance.currency,
                    version = 0L,
                    updatedAtMillis = balance.updatedAtMillis,
                    seq = BOOTSTRAP_SEQUENCE,
                )
            }
            payload.balances.forEach { balance ->
                transaction.set(
                    balanceRefs.getValue(balance.balanceDocumentId),
                    SparkInventoryBalanceCodec.record(
                        businessId = cloudBusinessId,
                        productId = balance.remoteProductId,
                        locationName = balance.locationName,
                        quantityOnHand = balance.quantityOnHand,
                        averageUnitCost = balance.averageUnitCost,
                        currency = balance.currency,
                        version = 0L,
                        lastSeq = BOOTSTRAP_SEQUENCE,
                        updatedAtMillis = balance.updatedAtMillis,
                        lastSourceLocationId = balance.sourceLocationId,
                        operationId = payload.operationId,
                        expectedVersion = null,
                        syncedAt = serverTimestamp,
                    ),
                )
            }
            transaction.set(
                changeRef,
                linkedMapOf(
                    "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                    "businessId" to cloudBusinessId.value,
                    "operationId" to payload.operationId,
                    "kind" to PURCHASE,
                    "seq" to BOOTSTRAP_SEQUENCE,
                    "receiptId" to payload.receiptId,
                    "sale" to null,
                    "balances" to projections,
                    "bootstrap" to true,
                    "syncedAt" to serverTimestamp,
                ),
            )
            transaction.set(
                bootstrapRef,
                linkedMapOf(
                    "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                    "businessId" to cloudBusinessId.value,
                    "ownerUid" to ownerUid,
                    "operationId" to payload.operationId,
                    "idempotencyKey" to payload.operationId,
                    "requestHash" to payload.requestHash,
                    "ledgerHash" to payload.ledgerHash,
                    "locations" to payload.locations,
                    "receiptId" to payload.receiptId,
                    "seq" to BOOTSTRAP_SEQUENCE,
                    "balances" to projections,
                    "authorizedRole" to BusinessRole.OWNER.name,
                    "syncedAt" to serverTimestamp,
                ),
            )
            transaction.set(
                metadataRef,
                linkedMapOf(
                    "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                    "businessId" to cloudBusinessId.value,
                    "seq" to BOOTSTRAP_SEQUENCE,
                    "bootstrapComplete" to true,
                    "bootstrapLedgerHash" to payload.ledgerHash,
                    "lastKind" to PURCHASE,
                    "lastReceiptId" to payload.receiptId,
                    "lastOperationId" to payload.operationId,
                    "updatedAt" to serverTimestamp,
                ),
            )
            SparkInventoryBootstrapState.BOOTSTRAPPED
        }.await()
        acknowledgeBootstrap(cloudBusinessId)
        return result
    }

    /** Bloquea el primer pull antes de que una página pueda reemplazar el inventario Room. */
    internal suspend fun requirePullOnboarding(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        metadata: Map<String, Any?>?,
        bootstrap: Map<String, Any?>?,
        bootstrapChange: Map<String, Any?>?,
    ) {
        val localCursor = localInventoryCursor(cloudBusinessId)
        val payload = if (sinceSeq == 0L) {
            try {
                buildPayload(localBusinessId, cloudBusinessId)
            } catch (_: SparkMutationFailure) {
                throw AccountException(AccountError.Conflict)
            } catch (_: IllegalArgumentException) {
                throw AccountException(AccountError.Conflict)
            }
        } else {
            null
        }
        val hasRemoteBootstrapState = metadata != null || bootstrap != null || bootstrapChange != null
        val evidence = if (sinceSeq == 0L) {
            sparkInventoryBootstrapEvidence(
                metadata = metadata,
                bootstrap = bootstrap,
                change = bootstrapChange,
                cloudBusinessId = cloudBusinessId.value,
            )
        } else {
            null
        }
        if (sinceSeq == 0L && hasRemoteBootstrapState && evidence == null) {
            throw AccountException(AccountError.Conflict)
        }
        if (
            SparkInventoryOnboardingPolicy.decide(
                localInventorySeq = localCursor,
                requestedSinceSeq = sinceSeq,
                localLedgerHash = payload?.takeUnless(SparkBootstrapCommitPayload::effectivelyEmpty)
                    ?.ledgerHash,
                remoteEvidence = evidence,
            ) == SparkInventoryOnboardingDecision.REJECT
        ) {
            throw AccountException(AccountError.Conflict)
        }
    }

    private suspend fun localInventoryCursor(cloudBusinessId: BusinessId): Long =
        database.remoteSyncDao().findState(cloudBusinessId.value)?.inventorySeq ?: 0L

    private suspend fun acknowledgeBootstrap(cloudBusinessId: BusinessId) {
        database.withTransaction {
            val dao = database.remoteSyncDao()
            val current = dao.findState(cloudBusinessId.value)
                ?: RemoteSyncStateEntity(cloudBusinessId.value)
            val acknowledged = SparkInventoryOnboardingPolicy.acknowledgeBootstrap(current)
            if (acknowledged != current) {
                dao.upsertState(acknowledged)
            }
        }
    }

    private suspend fun buildPayload(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ): SparkBootstrapCommitPayload? {
        val snapshot = database.withTransaction {
            val stored = database.inventoryDao().listBalancesForBusiness(localBusinessId.value)
            if (stored.isEmpty()) return@withTransaction null
            if (stored.size > MAX_BOOTSTRAP_BALANCES) {
                throw SparkMutationFailure.InventoryMigrationRequired
            }
            val dao = database.outboxOperationDao()
            val operations = (
                dao.listByTypeAndStatuses(
                    businessId = localBusinessId.value,
                    operationType = SYNC_PURCHASE,
                    statuses = OPEN_OUTBOX_STATUSES,
                ) + dao.listByTypeAndStatuses(
                    businessId = localBusinessId.value,
                    operationType = SYNC_PURCHASE_VOID,
                    statuses = OPEN_OUTBOX_STATUSES,
                )
            ).filter { operation ->
                operation.targetCloudBusinessId == cloudBusinessId.value
            }
            SparkLocalBootstrapSnapshot(stored = stored, openOperations = operations)
        } ?: return null

        val locations = linkedMapOf<String, Map<String, String>>()
        val remoteKeys = hashSetOf<Pair<String, String>>()
        val balances = buildList {
            for (balance in snapshot.stored) {
                val quantity = parseSparkBootstrapQuantity(balance.quantityOnHand)
                val average = balance.averageUnitCost.bootstrapDecimal(nonNegative = true)
                val location = database.inventoryLocationDao().findById(balance.locationId)
                    ?.takeIf { it.businessId == localBusinessId.value }
                    ?: throw SparkMutationFailure.InventoryMigrationRequired
                val remoteProduct = remoteProduct(
                    localBusinessId = localBusinessId,
                    cloudBusinessId = cloudBusinessId,
                    localProductId = balance.productId,
                )
                val remoteProductId = remoteProduct.remoteEntityId
                val remoteKey = remoteProductId to canonicalLocationName(location.name)
                if (!remoteKeys.add(remoteKey)) {
                    throw SparkMutationFailure.InventoryMigrationRequired
                }
                locations.putIfAbsent(
                    location.locationId,
                    linkedMapOf(
                        "sourceLocationId" to location.locationId,
                        "locationName" to location.name,
                    ),
                )
                add(
                    SparkBootstrapBalance(
                        remoteProductId = remoteProductId,
                        sourceLocationId = location.locationId,
                        locationName = location.name,
                        balanceDocumentId = SparkFirestoreSchema.inventoryBalanceDocumentId(
                            remoteProductId,
                            location.name,
                        ),
                        quantityOnHand = quantity,
                        averageUnitCost = average,
                        currency = CurrencyCode.of(balance.currencyCode),
                        updatedAtMillis = balance.updatedAt,
                        minimumProductVersion = remoteProduct.minimumVersion,
                    ),
                )
            }
        }.associateByTo(linkedMapOf()) { balance ->
            balance.remoteProductId to canonicalLocationName(balance.locationName)
        }

        if (snapshot.openOperations.isNotEmpty()) {
            val detailCache = mutableMapOf<String, PurchaseReadDetail>()
            val effectsByOperation = snapshot.openOperations.associateWith { operation ->
                val purchaseId = operation.purchaseId
                    ?: throw SparkMutationFailure.InventoryMigrationRequired
                val detail = detailCache.getOrPut(purchaseId) {
                    purchaseReads.observePurchase(
                        businessId = localBusinessId,
                        purchaseId = PurchaseId.parse(purchaseId)
                            ?: throw SparkMutationFailure.InventoryMigrationRequired,
                    ).first() ?: throw SparkMutationFailure.InventoryMigrationRequired
                }
                when (operation.operationType) {
                    SYNC_PURCHASE -> purchaseEffects(localBusinessId, cloudBusinessId, detail)
                    SYNC_PURCHASE_VOID -> voidEffects(
                        localBusinessId = localBusinessId,
                        cloudBusinessId = cloudBusinessId,
                        operation = operation,
                        detail = detail,
                    )
                    else -> throw SparkMutationFailure.InventoryMigrationRequired
                }
            }
            snapshot.openOperations.sortedWith(
                compareByDescending<OutboxOperationEntity> { it.createdAt }
                    .thenByDescending { it.entityVersion }
                    .thenByDescending { it.operationId },
            ).forEach { operation ->
                effectsByOperation.getValue(operation).forEach { effect ->
                    val key = effect.remoteProductId to effect.canonicalLocationName
                    val current = balances[key]
                        ?: throw SparkMutationFailure.InventoryMigrationRequired
                    if (current.currency != effect.currency) throw SparkMutationFailure.Rejected
                    balances[key] = reversePendingEffect(current, effect)
                }
            }
        }

        val sortedBalances = balances.values.sortedWith(
            compareBy(SparkBootstrapBalance::remoteProductId)
                .thenBy { canonicalLocationName(it.locationName) },
        )
        val operationId = SparkFirestoreSchema.inventoryBootstrapOperationId(cloudBusinessId.value)
        val receiptId = SparkFirestoreSchema.inventoryBootstrapReceiptId(cloudBusinessId.value)
        val locationValues = locations.values.sortedBy { it.getValue("sourceLocationId") }
        val requestHash = SparkFirestoreSchema.lengthPrefixedSha256(
            buildList {
                add("spark-inventory-bootstrap-payload-v1")
                add(cloudBusinessId.value)
                locationValues.forEach { location ->
                    add(location.getValue("sourceLocationId"))
                    add(location.getValue("locationName"))
                }
                sortedBalances.forEach { balance ->
                    add(balance.remoteProductId)
                    add(balance.sourceLocationId)
                    add(balance.locationName)
                    add(balance.quantityOnHand.canonicalDecimal())
                    add(balance.averageUnitCost.canonicalDecimal())
                    add(balance.currency.value)
                    add(balance.updatedAtMillis.toString())
                }
            },
        )
        return SparkBootstrapCommitPayload(
            operationId = operationId,
            receiptId = receiptId,
            requestHash = requestHash,
            ledgerHash = sparkInventoryCausalLedgerHash(
                cloudBusinessId = cloudBusinessId.value,
                balances = sortedBalances,
            ),
            locations = locationValues,
            balances = sortedBalances,
            effectivelyEmpty = sortedBalances.all { balance ->
                balance.quantityOnHand.signum() == 0
            },
        )
    }

    private suspend fun purchaseEffects(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        detail: PurchaseReadDetail,
    ): List<SparkPendingInventoryEffect> {
        val lines = detail.lines.associateBy { it.purchaseLineId }
        return detail.movements.filter { it.type == StockMovementType.PURCHASE }
            .groupBy { movement ->
                val remoteProductId = remoteProduct(
                    localBusinessId,
                    cloudBusinessId,
                    movement.productId.value,
                ).remoteEntityId
                Triple(
                    remoteProductId,
                    canonicalLocationName(movement.locationName),
                    movement.locationName,
                )
            }.map { (key, grouped) ->
                val quantity = grouped.fold(BigDecimal.ZERO) { total, movement ->
                    total + movement.quantityDelta
                }
                val incomingValue = grouped.fold(BigDecimal.ZERO) { total, movement ->
                    val applied = lines[movement.purchaseLineId]?.appliedCostTotal
                        ?: throw SparkMutationFailure.InventoryMigrationRequired
                    total + if (movement.quantityDelta.signum() < 0) applied.negate() else applied
                }
                SparkPendingInventoryEffect(
                    remoteProductId = key.first,
                    locationName = key.third,
                    canonicalLocationName = key.second,
                    quantityDelta = quantity,
                    incomingValue = incomingValue,
                    currency = detail.summary.currency,
                    preserveAverage = quantity.signum() <= 0,
                )
            }
    }

    private suspend fun voidEffects(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        operation: OutboxOperationEntity,
        detail: PurchaseReadDetail,
    ): List<SparkPendingInventoryEffect> {
        val root = json.parseToJsonElement(operation.payload).jsonObject
        if (root.getValue("version").jsonPrimitive.content.toLongOrNull() != 1L) {
            throw SparkMutationFailure.InventoryMigrationRequired
        }
        val locationNames = detail.movements.filter { it.type == StockMovementType.PURCHASE }
            .associate { movement ->
                (movement.productId.value to movement.locationId.value) to movement.locationName
            }
        val rawEffects = root.getValue("impacts").jsonArray.map { element ->
            val impact = element.jsonObject
            val localProductId = impact.getValue("productId").jsonPrimitive.content
            val locationId = impact.getValue("locationId").jsonPrimitive.content
            val locationName = locationNames[localProductId to locationId]
                ?: throw SparkMutationFailure.InventoryMigrationRequired
            SparkPendingInventoryEffect(
                remoteProductId = remoteProduct(
                    localBusinessId,
                    cloudBusinessId,
                    localProductId,
                ).remoteEntityId,
                locationName = locationName,
                canonicalLocationName = canonicalLocationName(locationName),
                quantityDelta = impact.getValue("reversalQuantity").jsonPrimitive.content
                    .bootstrapDecimal(nonNegative = false),
                incomingValue = BigDecimal.ZERO,
                currency = CurrencyCode.of(impact.getValue("currency").jsonPrimitive.content),
                preserveAverage = true,
            )
        }
        return rawEffects.groupBy { it.remoteProductId to it.canonicalLocationName }
            .map { (_, grouped) ->
                grouped.first().copy(
                    quantityDelta = grouped.fold(BigDecimal.ZERO) { total, effect ->
                        total + effect.quantityDelta
                    },
                )
            }
    }

    private suspend fun remoteProduct(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        localProductId: String,
    ): SparkRemoteProduct {
        val link = database.catalogSyncLinkDao().findByLocal(
            localBusinessId = localBusinessId.value,
            entityType = PRODUCT,
            localEntityId = localProductId,
        )
        if (link != null && link.cloudBusinessId != cloudBusinessId.value) {
            throw SparkMutationFailure.InventoryMigrationRequired
        }
        return SparkRemoteProduct(
            // La primera creación conserva el UUID local en cloud. Si hubo convergencia por
            // SKU, ese documento no existirá y la lectura transaccional de products/{id}
            // abortará sin sembrar un saldo huérfano; el siguiente pull instalará el link real.
            remoteEntityId = link?.remoteEntityId ?: localProductId,
            minimumVersion = link?.remoteVersion ?: 1L,
        )
    }

    internal companion object {
        fun reversePendingEffect(
            current: SparkBootstrapBalance,
            effect: SparkPendingInventoryEffect,
        ): SparkBootstrapBalance {
            val previousQuantity = current.quantityOnHand - effect.quantityDelta
            if (
                previousQuantity.signum() < 0 &&
                !effect.preserveAverage &&
                effect.quantityDelta.signum() > 0
            ) {
                throw SparkMutationFailure.InventoryMigrationRequired
            }
            val previousAverage = when {
                effect.preserveAverage || effect.quantityDelta.signum() <= 0 ->
                    current.averageUnitCost
                previousQuantity.signum() == 0 -> BigDecimal.ZERO
                else -> {
                    val previousValue = current.quantityOnHand * current.averageUnitCost -
                        effect.incomingValue
                    if (previousValue.signum() < 0) {
                        throw SparkMutationFailure.InventoryMigrationRequired
                    }
                    previousValue.divide(
                        previousQuantity,
                        INVENTORY_DIVISION_SCALE,
                        RoundingMode.HALF_EVEN,
                    ).normalizedInventoryDecimal()
                }
            }
            if (
                !InventoryCostingDecimalPolicy.supportsPersisted(previousQuantity) ||
                !InventoryCostingDecimalPolicy.supportsPersisted(previousAverage)
            ) {
                throw SparkMutationFailure.InventoryMigrationRequired
            }
            return current.copy(
                quantityOnHand = previousQuantity.normalizedInventoryDecimal(),
                averageUnitCost = previousAverage,
            )
        }

        private const val INVENTORY_DIVISION_SCALE = 18
    }
}

internal data class SparkBootstrapBalance(
    val remoteProductId: String,
    val sourceLocationId: String,
    val locationName: String,
    val balanceDocumentId: String,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: BigDecimal,
    val currency: CurrencyCode,
    val updatedAtMillis: Long,
    val minimumProductVersion: Long = 1L,
)

/**
 * Identidad economica estable del baseline remoto.
 *
 * El requestHash conserva toda la evidencia administrativa de la semilla, incluidos IDs locales,
 * nombre visible y timestamps. Esos campos no se pueden reconstruir al revertir una outbox nueva
 * despues de perder el ACK. El ledger de recuperacion usa solo la clave cloud y el estado que afecta
 * operaciones posteriores. Una fila cero tampoco es causal: una compra pendiente sobre una clave
 * nueva deja precisamente esa fila al deshacerse y no debe convertir un ACK perdido en migracion.
 */
internal fun sparkInventoryCausalLedgerHash(
    cloudBusinessId: String,
    balances: List<SparkBootstrapBalance>,
): String = causalLedgerHash(
    cloudBusinessId = cloudBusinessId,
    balances = balances.map { balance ->
        SparkCausalBootstrapBalance(
            remoteProductId = balance.remoteProductId,
            canonicalLocationName = canonicalLocationName(balance.locationName),
            quantityOnHand = balance.quantityOnHand,
            averageUnitCost = balance.averageUnitCost,
            currency = balance.currency,
        )
    },
)

private data class SparkCausalBootstrapBalance(
    val remoteProductId: String,
    val canonicalLocationName: String,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: BigDecimal,
    val currency: CurrencyCode,
)

private fun causalLedgerHash(
    cloudBusinessId: String,
    balances: List<SparkCausalBootstrapBalance>,
): String {
    val nonZero = balances.filter { balance -> balance.quantityOnHand.signum() != 0 }
        .sortedWith(
            compareBy(SparkCausalBootstrapBalance::remoteProductId)
                .thenBy(SparkCausalBootstrapBalance::canonicalLocationName),
        )
    require(
        nonZero.map { balance ->
            balance.remoteProductId to balance.canonicalLocationName
        }.distinct().size == nonZero.size,
    )
    return SparkFirestoreSchema.lengthPrefixedSha256(
        buildList {
            add(SPARK_CAUSAL_LEDGER_HASH_VERSION)
            add(cloudBusinessId)
            nonZero.forEach { balance ->
                add(balance.remoteProductId)
                add(balance.canonicalLocationName)
                add(balance.quantityOnHand.canonicalDecimal())
                add(balance.averageUnitCost.canonicalDecimal())
                add(balance.currency.value)
            }
        },
    )
}

private data class SparkRemoteProduct(
    val remoteEntityId: String,
    val minimumVersion: Long,
)

internal data class SparkPendingInventoryEffect(
    val remoteProductId: String,
    val locationName: String,
    val canonicalLocationName: String,
    val quantityDelta: BigDecimal,
    val incomingValue: BigDecimal,
    val currency: CurrencyCode,
    val preserveAverage: Boolean,
)

private data class SparkBootstrapCommitPayload(
    val operationId: String,
    val receiptId: String,
    val requestHash: String,
    val ledgerHash: String,
    val locations: List<Map<String, String>>,
    val balances: List<SparkBootstrapBalance>,
    val effectivelyEmpty: Boolean,
)

private data class SparkLocalBootstrapSnapshot(
    val stored: List<com.facturastock.app.data.local.entity.InventoryBalanceEntity>,
    val openOperations: List<OutboxOperationEntity>,
)

private fun requireBootstrapOwner(snapshot: DocumentSnapshot, uid: String) {
    val data = snapshot.data ?: throw SparkMutationFailure.Rejected
    if (
        data["ownerUid"] != uid || data["accountDeletionLocked"] == true ||
        data["sparkDirectWritesEnabled"] == false
    ) {
        throw SparkMutationFailure.Rejected
    }
}

private fun requireBootstrapProducts(
    snapshots: Map<String, DocumentSnapshot>,
    balances: List<SparkBootstrapBalance>,
    cloudBusinessId: BusinessId,
    ownerUid: String,
) {
    val requirements = balances.groupBy(SparkBootstrapBalance::remoteProductId)
    if (snapshots.keys != requirements.keys) {
        throw SparkMutationFailure.InventoryMigrationRequired
    }
    requirements.forEach { (remoteProductId, productBalances) ->
        val minimumVersion = productBalances.maxOf(SparkBootstrapBalance::minimumProductVersion)
        if (
            !isCompatibleSparkBootstrapProduct(
                data = snapshots[remoteProductId]?.data,
                cloudBusinessId = cloudBusinessId.value,
                ownerUid = ownerUid,
                remoteProductId = remoteProductId,
                minimumVersion = minimumVersion,
            )
        ) {
            throw SparkMutationFailure.InventoryMigrationRequired
        }
    }
}

internal fun isCompatibleSparkBootstrapProduct(
    data: Map<String, Any?>?,
    cloudBusinessId: String,
    ownerUid: String,
    remoteProductId: String,
    minimumVersion: Long,
): Boolean {
    data ?: return false
    val version = data.bootstrapLongOrNull("version") ?: return false
    if (
        data.bootstrapLongOrNull("schemaVersion") != SparkFirestoreSchema.SCHEMA_VERSION ||
        data["businessId"] != cloudBusinessId ||
        data["ownerUid"] != ownerUid ||
        data["entityId"] != remoteProductId ||
        data["entityType"] != PRODUCT ||
        data["mutation"] != "UPSERT" ||
        version < minimumVersion
    ) {
        return false
    }
    @Suppress("UNCHECKED_CAST")
    val product = data["snapshot"] as? Map<String, Any?> ?: return false
    @Suppress("UNCHECKED_CAST")
    val inventoryUnit = product["inventoryUnit"] as? Map<String, Any?> ?: return false
    return product["status"] == "ACTIVE" &&
        inventoryUnit["status"] == "ACTIVE" &&
        (inventoryUnit["code"] as? String)?.isNotBlank() == true
}

/** Devuelve null ante evidencia ausente o parcialmente manipulada. */
internal fun sparkInventoryBootstrapEvidence(
    metadata: Map<String, Any?>?,
    bootstrap: Map<String, Any?>?,
    change: Map<String, Any?>?,
    cloudBusinessId: String,
): SparkInventoryBootstrapEvidence? {
    if (metadata == null && bootstrap == null && change == null) return null
    metadata ?: return null
    bootstrap ?: return null
    change ?: return null
    val metadataSeq = metadata.bootstrapLongOrNull("seq") ?: return null
    val bootstrapSeq = bootstrap.bootstrapLongOrNull("seq") ?: return null
    val changeSeq = change.bootstrapLongOrNull("seq") ?: return null
    val operationId = SparkFirestoreSchema.inventoryBootstrapOperationId(cloudBusinessId)
    val receiptId = SparkFirestoreSchema.inventoryBootstrapReceiptId(cloudBusinessId)
    val requestHash = bootstrap["requestHash"] as? String ?: return null
    val ledgerHash = bootstrap["ledgerHash"] as? String ?: return null
    val bootstrapBalances = bootstrap["balances"] ?: return null
    val causalLedgerHash = sparkInventoryCausalLedgerHashFromWire(
        cloudBusinessId = cloudBusinessId,
        rawBalances = bootstrapBalances,
    ) ?: return null
    if (
        metadata.bootstrapLongOrNull("schemaVersion") !=
        SparkFirestoreSchema.SCHEMA_VERSION ||
        bootstrap.bootstrapLongOrNull("schemaVersion") !=
        SparkFirestoreSchema.SCHEMA_VERSION ||
        change.bootstrapLongOrNull("schemaVersion") !=
        SparkFirestoreSchema.SCHEMA_VERSION ||
        metadata["businessId"] != cloudBusinessId ||
        bootstrap["businessId"] != cloudBusinessId ||
        change["businessId"] != cloudBusinessId ||
        metadataSeq !in BOOTSTRAP_SEQUENCE..SparkFirestoreSchema.MAX_SAFE_SEQUENCE ||
        bootstrapSeq != BOOTSTRAP_SEQUENCE ||
        changeSeq != BOOTSTRAP_SEQUENCE ||
        metadata["bootstrapComplete"] != true ||
        metadata["bootstrapLedgerHash"] != ledgerHash ||
        bootstrap["operationId"] != operationId ||
        bootstrap["idempotencyKey"] != operationId ||
        bootstrap["receiptId"] != receiptId ||
        change["operationId"] != operationId ||
        change["receiptId"] != receiptId ||
        change["kind"] != PURCHASE ||
        change["bootstrap"] != true ||
        change["balances"] != bootstrapBalances ||
        !SHA_256.matches(requestHash) ||
        !SHA_256.matches(ledgerHash) ||
        ledgerHash != causalLedgerHash
    ) {
        return null
    }
    return SparkInventoryBootstrapEvidence(
        ledgerHash = ledgerHash,
        metadataSeq = metadataSeq,
    )
}

private fun sparkInventoryCausalLedgerHashFromWire(
    cloudBusinessId: String,
    rawBalances: Any?,
): String? = try {
    val rawList = rawBalances as? List<*> ?: return null
    if (rawList.isEmpty() || rawList.size > MAX_BOOTSTRAP_BALANCES) return null
    val balances = rawList.map { raw ->
        val untyped = raw as? Map<*, *> ?: return null
        if (untyped.keys.any { it !is String }) return null
        @Suppress("UNCHECKED_CAST")
        val balance = untyped as Map<String, Any?>
        if (balance.keys != SPARK_BOOTSTRAP_PROJECTION_FIELDS) return null
        val remoteProductId = balance["productId"] as? String ?: return null
        val locationName = balance["locationName"] as? String ?: return null
        val canonicalName = canonicalLocationName(locationName)
        val quantityText = balance["quantityOnHand"] as? String ?: return null
        val averageText = balance["averageUnitCost"] as? String ?: return null
        val quantity = quantityText.bootstrapDecimal(nonNegative = false)
        val average = averageText.bootstrapDecimal(nonNegative = true)
        val currency = CurrencyCode.of(balance["currency"] as? String ?: return null)
        val version = balance.bootstrapLongOrNull("version") ?: return null
        val updatedAt = balance.bootstrapLongOrNull("updatedAtMillis") ?: return null
        val seq = balance.bootstrapLongOrNull("seq") ?: return null
        if (
            remoteProductId.isBlank() || canonicalName.isBlank() ||
            quantityText != quantity.canonicalDecimal() ||
            averageText != average.canonicalDecimal() ||
            version != 0L || updatedAt < 0L || seq != BOOTSTRAP_SEQUENCE
        ) {
            return null
        }
        SparkCausalBootstrapBalance(
            remoteProductId = remoteProductId,
            canonicalLocationName = canonicalName,
            quantityOnHand = quantity,
            averageUnitCost = average,
            currency = currency,
        )
    }
    if (
        balances.map { balance ->
            balance.remoteProductId to balance.canonicalLocationName
        }.distinct().size != balances.size
    ) {
        return null
    }
    causalLedgerHash(cloudBusinessId, balances)
} catch (_: Exception) {
    null
}

private fun Map<String, Any?>.bootstrapLong(key: String): Long = when (val value = this[key]) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toLong()
    is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
    else -> null
} ?: throw SparkMutationFailure.CorruptRemoteData

private fun Map<String, Any?>.bootstrapLongOrNull(key: String): Long? = when (val value = this[key]) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toLong()
    is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
    else -> null
}

private fun String.bootstrapDecimal(nonNegative: Boolean): BigDecimal = try {
    BigDecimal(this).also { value ->
        if (
            (nonNegative && value.signum() < 0) ||
            !InventoryCostingDecimalPolicy.supportsPersisted(value.abs())
        ) {
            throw SparkMutationFailure.InventoryMigrationRequired
        }
    }
} catch (failure: SparkMutationFailure) {
    throw failure
} catch (_: Exception) {
    throw SparkMutationFailure.InventoryMigrationRequired
}

internal fun parseSparkBootstrapQuantity(value: String): BigDecimal =
    value.bootstrapDecimal(nonNegative = false)

private const val INVENTORY_BOOTSTRAP_DOCUMENT = "inventoryBootstrap"
private const val BOOTSTRAP_SEQUENCE = 1L
private const val MAX_BOOTSTRAP_BALANCES = 200
private const val SPARK_CAUSAL_LEDGER_HASH_VERSION = "spark-inventory-bootstrap-ledger-v2"
private const val PRODUCT = "PRODUCT"
private const val PURCHASE = "PURCHASE"
private const val SYNC_PURCHASE = "SYNC_PURCHASE"
private const val SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
private val SHA_256 = Regex("^[0-9a-f]{64}$")
private val SPARK_BOOTSTRAP_PROJECTION_FIELDS = setOf(
    "productId",
    "locationName",
    "quantityOnHand",
    "averageUnitCost",
    "currency",
    "version",
    "updatedAtMillis",
    "seq",
)
private val OPEN_OUTBOX_STATUSES = listOf(
    OutboxOperationStatus.PENDING.name,
    OutboxOperationStatus.PROCESSING.name,
    OutboxOperationStatus.FAILED.name,
    OutboxOperationStatus.CONFLICT.name,
)
private val json = Json { ignoreUnknownKeys = false; isLenient = false }
