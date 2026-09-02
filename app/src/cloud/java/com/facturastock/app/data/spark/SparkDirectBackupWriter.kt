package com.facturastock.app.data.spark

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.RemoteConflictMetadata
import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Sustituto cliente de los callables que requieren Blaze. Solo existe en el flavor cloud y el
 * transporte lo usa exclusivamente cuando [FirebaseBackendMode] selecciona SPARK_DIRECT.
 *
 * Cada mutacion se ejecuta como una transaccion Firestore: clave idempotente, hecho contable,
 * proyeccion de inventario, secuencias y feed cambian juntos o no cambia nada. Las rules Spark
 * vuelven a comprobar propietario, forma, versiones y relaciones con `getAfter`.
 */
@Singleton
class SparkDirectBackupWriter @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val inventoryBootstrapper: SparkInventoryBootstrapper,
) {
    suspend fun send(
        envelope: BackupEnvelope,
        document: Any?,
        expectedUid: String,
    ): BackupTransportResult {
        val firestore = runtime.firestore() ?: return BackupTransportResult.Unavailable
        if (runtime.auth()?.currentUser?.uid != expectedUid) return BackupTransportResult.Unavailable
        return try {
            when (envelope.operationType) {
                SYNC_PRODUCT, SYNC_SUPPLIER -> writeCatalog(
                    firestore = firestore,
                    envelope = envelope,
                    ownerUid = expectedUid,
                )

                SYNC_PURCHASE -> {
                    val purchaseDocument = document.stringMapOrNull()
                        ?: return BackupTransportResult.PermanentFailure(MALFORMED_PURCHASE)
                    // La validacion completa precede al bootstrap irreversible: un envelope
                    // malformado nunca puede sembrar inventario aunque luego sea rechazado.
                    val prepared = preparePurchase(envelope, purchaseDocument)
                    if (!purchaseRemoteRecordExists(firestore, prepared, envelope)) {
                        if (!purchaseCatalogIsReady(firestore, prepared, envelope)) {
                            return BackupTransportResult.TransientFailure(CATALOG_NOT_READY)
                        }
                        inventoryBootstrapper.ensureInitialized(
                            firestore = firestore,
                            localBusinessId = envelope.businessId,
                            cloudBusinessId = envelope.targetCloudBusinessId,
                            ownerUid = expectedUid,
                        )
                    }
                    writePurchase(
                        firestore = firestore,
                        envelope = envelope,
                        prepared = prepared,
                        ownerUid = expectedUid,
                    )
                }

                SYNC_PURCHASE_VOID -> writeVoid(
                    firestore = firestore,
                    envelope = envelope,
                    payloadText = document as? String
                        ?: return BackupTransportResult.PermanentFailure(MALFORMED_VOID),
                    ownerUid = expectedUid,
                )

                else -> BackupTransportResult.PermanentFailure(UNSUPPORTED_OPERATION)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            BackupTransportResult.PermanentFailure(MALFORMED_SPARK_PAYLOAD)
        } catch (_: ArithmeticException) {
            BackupTransportResult.PermanentFailure(MALFORMED_SPARK_PAYLOAD)
        } catch (failure: SparkMutationFailure) {
            when (failure) {
                SparkMutationFailure.CorruptRemoteData ->
                    BackupTransportResult.PermanentFailure(CORRUPT_REMOTE_DATA)
                SparkMutationFailure.InventoryMigrationRequired ->
                    BackupTransportResult.Conflict(INVENTORY_MIGRATION_REQUIRED)
                SparkMutationFailure.Stale -> BackupTransportResult.Conflict(STALE_REMOTE_STATE)
                SparkMutationFailure.Rejected ->
                    BackupTransportResult.PermanentFailure(REJECTED_REMOTE_STATE)
                is SparkMutationFailure.InsufficientStock ->
                    BackupTransportResult.Conflict(INSUFFICIENT_STOCK)
            }
        } catch (failure: Exception) {
            firestoreFailure(failure)
        }
    }

    private suspend fun writeCatalog(
        firestore: FirebaseFirestore,
        envelope: BackupEnvelope,
        ownerUid: String,
    ): BackupTransportResult {
        val mutation = SparkCatalogCodec.parseMutation(
            operationType = envelope.operationType,
            payload = envelope.payload,
            remoteEntityId = envelope.remoteEntityId,
        )
        val businessId = envelope.targetCloudBusinessId.value
        val receiptId = SparkCatalogCodec.receiptId(envelope.idempotencyKey)
        val operationId = SparkCatalogCodec.operationDocumentId(envelope.idempotencyKey)
        val requestHash = SparkCatalogCodec.requestHash(
            businessId = businessId,
            idempotencyKey = envelope.idempotencyKey,
            payloadVersion = envelope.payloadVersion,
            mutation = mutation,
        )
        val business = firestore.collection(BUSINESSES).document(businessId)
        val entityRef = business.collection(mutation.collection).document(mutation.entityId)
        val operationRef = business.collection(CATALOG_OPERATIONS).document(operationId)
        val metadataRef = business.collection(SYNC).document(CATALOG_METADATA)
        val changeRef = business.collection(CATALOG_CHANGES).document(receiptId)

        return firestore.runTransaction { tx ->
            val operation = tx.get(operationRef)
            val entity = tx.get(entityRef)
            val metadata = tx.get(metadataRef)
            val change = tx.get(changeRef)
            if (operation.exists()) {
                val saved = operation.data.orEmpty()
                return@runTransaction if (
                    saved[REQUEST_HASH] == requestHash &&
                    saved[IDEMPOTENCY_KEY] == envelope.idempotencyKey &&
                    saved[RECEIPT_ID] == receiptId &&
                    saved[ENTITY_ID] == mutation.entityId
                ) {
                    BackupTransportResult.Acknowledged(receiptId, envelope.idempotencyKey)
                } else {
                    catalogConflict(entity, businessId)
                }
            }
            if (change.exists()) return@runTransaction catalogConflict(entity, businessId)
            val current = entity.data.orEmpty()
            val currentVersion = if (entity.exists()) current.safeLong(VERSION) else 0L
            if (currentVersion != mutation.expectedVersion) {
                return@runTransaction catalogConflict(entity, businessId)
            }
            if (entity.exists()) {
                if (current[ENTITY_TYPE] != mutation.entityType) {
                    return@runTransaction BackupTransportResult.PermanentFailure(
                        CORRUPT_REMOTE_DATA,
                    )
                }
                val oldCreatedAt = current.snapshotValue(CREATED_AT)
                val newCreatedAt = mutation.snapshot[CREATED_AT]
                if (oldCreatedAt != null && oldCreatedAt != newCreatedAt) {
                    return@runTransaction BackupTransportResult.Conflict(
                        CATALOG_CREATED_AT_IMMUTABLE,
                        remoteEntity = current.catalogConflictMetadata(businessId),
                    )
                }
            }
            val oldSemanticKeys = if (entity.exists()) {
                try {
                    SparkCatalogCodec.semanticKeys(
                        entityType = mutation.entityType,
                        snapshot = current[SNAPSHOT].stringMapOrNull()
                            ?: throw SparkMutationFailure.CorruptRemoteData,
                    )
                } catch (failure: SparkMutationFailure) {
                    throw failure
                } catch (_: Exception) {
                    throw SparkMutationFailure.CorruptRemoteData
                }
            } else {
                emptySet()
            }
            val newSemanticKeys = SparkCatalogCodec.semanticKeys(
                entityType = mutation.entityType,
                snapshot = mutation.snapshot,
            )
            val semanticRefs = (oldSemanticKeys + newSemanticKeys).associateWith { key ->
                business.collection(key.collection).document(key.documentId)
            }
            val semanticSnapshots = semanticRefs.mapValues { (_, reference) ->
                tx.get(reference)
            }
            val semanticOwners = semanticSnapshots.mapValues { (_, snapshot) ->
                snapshot.semanticIndexOwnerOrNull()
            }
            val semanticPlan = try {
                SparkCatalogCodec.semanticIndexPlan(
                    entityId = mutation.entityId,
                    currentVersion = currentVersion,
                    oldKeys = oldSemanticKeys,
                    newKeys = newSemanticKeys,
                    owners = semanticOwners,
                )
            } catch (_: IllegalArgumentException) {
                throw SparkMutationFailure.CorruptRemoteData
            }
            if (semanticPlan is SparkCatalogSemanticIndexPlan.Conflict) {
                val collisionEntity = tx.get(
                    business.collection(mutation.collection).document(semanticPlan.ownerEntityId),
                )
                return@runTransaction BackupTransportResult.Conflict(
                    reason = CATALOG_SEMANTIC_CONFLICT,
                    remoteEntity = collisionEntity.data?.catalogConflictMetadata(businessId),
                )
            }
            semanticPlan as SparkCatalogSemanticIndexPlan.Apply
            val seq = metadata.nextSequence()
            val serverTime = FieldValue.serverTimestamp()
            tx.set(
                entityRef,
                linkedMapOf(
                    SCHEMA_VERSION to 1L,
                    BUSINESS_ID to businessId,
                    OWNER_UID to ownerUid,
                    OPERATION_ID to operationId,
                    ENTITY_ID to mutation.entityId,
                    ENTITY_TYPE to mutation.entityType,
                    VERSION to mutation.targetVersion,
                    MUTATION to mutation.mutation,
                    SNAPSHOT to mutation.snapshot,
                    SNAPSHOT_PAYLOAD to mutation.snapshotPayload,
                    SNAPSHOT_SHA256 to mutation.snapshotSha256,
                    RECEIPT_ID to receiptId,
                    SYNCED_AT to serverTime,
                ),
            )
            tx.set(
                metadataRef,
                linkedMapOf(
                    SCHEMA_VERSION to 1L,
                    BUSINESS_ID to businessId,
                    OWNER_UID to ownerUid,
                    SEQ to seq,
                    LAST_OPERATION_ID to operationId,
                    UPDATED_AT to serverTime,
                ),
            )
            tx.set(
                changeRef,
                linkedMapOf(
                    SCHEMA_VERSION to 1L,
                    SEQ to seq,
                    ENTITY_TYPE to mutation.entityType,
                    ENTITY_ID to mutation.entityId,
                    VERSION to mutation.targetVersion,
                    MUTATION to mutation.mutation,
                    SNAPSHOT to mutation.snapshot,
                    SNAPSHOT_PAYLOAD to mutation.snapshotPayload,
                    SNAPSHOT_SHA256 to mutation.snapshotSha256,
                    RECEIPT_ID to receiptId,
                    SYNCED_AT to serverTime,
                    OWNER_UID to ownerUid,
                ),
            )
            tx.set(
                operationRef,
                linkedMapOf(
                    SCHEMA_VERSION to 1L,
                    BUSINESS_ID to businessId,
                    OWNER_UID to ownerUid,
                    OPERATION_ID to operationId,
                    IDEMPOTENCY_KEY to envelope.idempotencyKey,
                    REQUEST_HASH to requestHash,
                    OPERATION_TYPE to envelope.operationType,
                    ENTITY_ID to mutation.entityId,
                    VERSION to mutation.targetVersion,
                    RECEIPT_ID to receiptId,
                    SEQ to seq,
                    CREATED_AT to serverTime,
                ),
            )
            semanticPlan.upserts.forEach { key ->
                tx.set(
                    semanticRefs.getValue(key),
                    linkedMapOf(
                        ENTITY_ID to mutation.entityId,
                        VERSION to mutation.targetVersion,
                    ),
                )
            }
            semanticPlan.removals.forEach { key ->
                if (semanticSnapshots.getValue(key).exists()) {
                    tx.delete(semanticRefs.getValue(key))
                }
            }
            BackupTransportResult.Acknowledged(receiptId, envelope.idempotencyKey)
        }.await()
    }

    private suspend fun writePurchase(
        firestore: FirebaseFirestore,
        envelope: BackupEnvelope,
        prepared: PreparedPurchase,
        ownerUid: String,
    ): BackupTransportResult {
        val businessId = envelope.targetCloudBusinessId.value
        val business = firestore.collection(BUSINESSES).document(businessId)
        val purchaseRef = business.collection(PURCHASES).document(prepared.purchaseId)
        val keyRef = business.collection(PURCHASE_KEYS).document(prepared.operationDocumentId)
        val metadataRef = business.collection(SYNC).document(PURCHASE_METADATA)
        val inventoryMetadataRef = business.collection(SYNC).document(INVENTORY_METADATA)
        val syncChangeRef = business.collection(PURCHASE_CHANGES).document(prepared.purchaseId)
        val inventoryChangeRef = business.collection(INVENTORY_CHANGES).document(prepared.receiptId)
        val identityRef = business.collection(DOCUMENT_INDEX).document(prepared.identityDocumentId)
        val overrideTargetRef = prepared.duplicateOverride?.let { override ->
            business.collection(PURCHASES).document(override.existingPurchaseId)
        }
        val overrideSlotRef = prepared.overrideSlotDocumentId?.let { slotId ->
            business.collection(DOCUMENT_INDEX).document(slotId)
        }
        val productRefs = prepared.effects.map(InventoryEffect::productId).distinct()
            .associateWith { productId -> business.collection(PRODUCTS).document(productId) }
        val balanceRefs = prepared.effects.associateWith { effect ->
            business.collection(INVENTORY_BALANCES).document(effect.balanceDocumentId)
        }

        return firestore.runTransaction { tx ->
            val existingKey = tx.get(keyRef)
            val existingPurchase = tx.get(purchaseRef)
            val metadata = tx.get(metadataRef)
            val inventoryMetadata = tx.get(inventoryMetadataRef)
            val existingChange = tx.get(syncChangeRef)
            val existingInventoryChange = tx.get(inventoryChangeRef)
            val existingIdentity = tx.get(identityRef)
            val overrideTarget = overrideTargetRef?.let(tx::get)
            val existingOverrideSlot = overrideSlotRef?.let(tx::get)
            val products = productRefs.mapValues { (_, ref) -> tx.get(ref) }
            val balanceSnapshots = balanceRefs.mapValues { (_, ref) -> tx.get(ref) }

            if (existingKey.exists()) {
                val key = existingKey.data.orEmpty()
                if (
                    key[IDEMPOTENCY_KEY] != envelope.idempotencyKey ||
                    key[REQUEST_HASH] != prepared.requestHash ||
                    key[PURCHASE_ID] != prepared.purchaseId ||
                    key[RECEIPT_ID] != prepared.receiptId
                ) {
                    return@runTransaction BackupTransportResult.Conflict(
                        PURCHASE_REPLAY_MISMATCH,
                        remotePurchaseId = key[PURCHASE_ID] as? String,
                        remoteReceiptId = key[RECEIPT_ID] as? String,
                    )
                }
                val saved = existingPurchase.data
                val change = existingChange.data
                val inventoryChange = existingInventoryChange.data
                if (
                    saved == null || change == null || inventoryChange == null ||
                    saved[IDEMPOTENCY_KEY] != envelope.idempotencyKey ||
                    saved[SYNC_PAYLOAD_HASH] != prepared.requestHash ||
                    saved[RECEIPT_ID] != prepared.receiptId ||
                    change[PURCHASE_ID] != prepared.purchaseId ||
                    change[STATUS] != saved[STATUS] || change[SEQ] != saved[SEQ] ||
                    change[RECEIPT_ID] != saved[RECEIPT_ID] ||
                    inventoryChange[KIND] != PURCHASE_KIND ||
                    inventoryChange[SEQ] != saved[INVENTORY_SEQ] ||
                    inventoryChange[RECEIPT_ID] != saved[RECEIPT_ID]
                ) {
                    return@runTransaction BackupTransportResult.PermanentFailure(
                        CORRUPT_REMOTE_DATA,
                    )
                }
                return@runTransaction BackupTransportResult.Acknowledged(
                    prepared.receiptId,
                    envelope.idempotencyKey,
                )
            }
            if (existingPurchase.exists()) {
                val saved = existingPurchase.data.orEmpty()
                return@runTransaction if (
                    saved[IDEMPOTENCY_KEY] == envelope.idempotencyKey &&
                    saved[SYNC_PAYLOAD_HASH] == prepared.requestHash &&
                    saved[RECEIPT_ID] == prepared.receiptId
                ) {
                    BackupTransportResult.PermanentFailure(SPARK_OPERATION_KEY_MISSING)
                } else {
                    BackupTransportResult.Conflict(
                        PURCHASE_REPLAY_MISMATCH,
                        remotePurchaseId = prepared.purchaseId,
                        remoteReceiptId = saved[RECEIPT_ID] as? String,
                    )
                }
            }
            if (existingChange.exists() || existingInventoryChange.exists()) {
                return@runTransaction BackupTransportResult.PermanentFailure(
                    CORRUPT_REMOTE_DATA,
                )
            }
            if (products.values.any { product ->
                    !SparkPurchaseCatalogPreflight.isActiveProduct(product.data)
                }
            ) {
                return@runTransaction BackupTransportResult.TransientFailure(CATALOG_NOT_READY)
            }
            val duplicateOverride = prepared.duplicateOverride
            if (duplicateOverride == null && existingIdentity.exists()) {
                val identity = existingIdentity.data.orEmpty()
                return@runTransaction BackupTransportResult.Conflict(
                    DOCUMENT_IDENTITY_EXISTS,
                    remotePurchaseId = identity[PURCHASE_ID] as? String,
                    remoteReceiptId = identity[RECEIPT_ID] as? String,
                )
            }
            if (duplicateOverride != null) {
                if (overrideTarget == null || !overrideTarget.exists()) {
                    return@runTransaction BackupTransportResult.TransientFailure(
                        DUPLICATE_TARGET_NOT_SYNCED,
                    )
                }
                val target = overrideTarget.data.orEmpty()
                if (target[STATUS] !in DUPLICATE_TARGET_STATUSES) {
                    return@runTransaction BackupTransportResult.Conflict(
                        DUPLICATE_TARGET_STATUS,
                        remotePurchaseId = overrideTarget.id,
                        remoteReceiptId = target[RECEIPT_ID] as? String,
                    )
                }
                if (!SparkPurchaseDuplicateOverrideCodec.targetMatches(
                        target = target,
                        businessId = businessId,
                        identityDocumentId = prepared.identityDocumentId,
                    )
                ) {
                    return@runTransaction BackupTransportResult.Conflict(
                        DUPLICATE_TARGET_IDENTITY_MISMATCH,
                        remotePurchaseId = overrideTarget.id,
                        remoteReceiptId = target[RECEIPT_ID] as? String,
                    )
                }
                if (
                    !existingIdentity.exists() ||
                    !SparkPurchaseDuplicateOverrideCodec.primaryIndexMatches(
                        index = existingIdentity.data.orEmpty(),
                        targetPurchaseId = duplicateOverride.existingPurchaseId,
                        targetReceiptId = target[RECEIPT_ID] as? String,
                    )
                ) {
                    return@runTransaction BackupTransportResult.PermanentFailure(
                        DOCUMENT_PRIMARY_INDEX_MISSING,
                    )
                }
                if (existingOverrideSlot?.exists() == true) {
                    val slot = existingOverrideSlot.data.orEmpty()
                    return@runTransaction BackupTransportResult.Conflict(
                        DUPLICATE_OVERRIDE_SLOT_EXISTS,
                        remotePurchaseId = slot[PURCHASE_ID] as? String,
                        remoteReceiptId = slot[RECEIPT_ID] as? String,
                    )
                }
            }

            val seq = metadata.nextSequence()
            val inventorySeq = inventoryMetadata.nextSequence()
            val serverTime = FieldValue.serverTimestamp()
            val plans = prepared.effects.map { effect ->
                planInventoryBalance(
                    businessId = envelope.targetCloudBusinessId,
                    effect = effect,
                    snapshot = requireNotNull(balanceSnapshots[effect]),
                    currency = prepared.currency,
                    inventorySeq = inventorySeq,
                    updatedAtMillis = prepared.postedAtMillis,
                    operationId = prepared.operationId,
                    serverTime = serverTime,
                    mode = InventoryMutationMode.PURCHASE,
                )
            }
            val root = LinkedHashMap(prepared.document)
            root[DUPLICATE_OVERRIDE] = SparkPurchaseDuplicateOverrideCodec.persistedMap(
                prepared.duplicateOverride,
            )
            root.putAll(
                linkedMapOf(
                    SCHEMA_VERSION to 1L,
                    OWNER_UID to ownerUid,
                    SYNC_PAYLOAD_HASH to prepared.requestHash,
                    SEQ to seq,
                    MOVEMENT_SUMMARY to prepared.movementSummary,
                    RECEIPT_ID to prepared.receiptId,
                    SYNCED_AT to serverTime,
                    SYNCED_BY to ownerUid,
                    INVENTORY_SEQ to inventorySeq,
                    LAST_OPERATION_ID to prepared.operationId,
                ),
            )
            tx.set(purchaseRef, root)
            tx.set(
                syncChangeRef,
                purchaseChange(
                    prepared = prepared,
                    seq = seq,
                    ownerUid = ownerUid,
                    operationId = prepared.operationId,
                    serverTime = serverTime,
                ),
            )
            tx.set(
                keyRef,
                operationKey(
                    businessId = businessId,
                    operationId = prepared.operationId,
                    idempotencyKey = envelope.idempotencyKey,
                    purchaseId = prepared.purchaseId,
                    receiptId = prepared.receiptId,
                    seq = seq,
                    inventorySeq = inventorySeq,
                    requestHash = prepared.requestHash,
                    ownerUid = ownerUid,
                    serverTime = serverTime,
                ),
            )
            if (duplicateOverride == null) {
                tx.set(
                    identityRef,
                    linkedMapOf(
                        SCHEMA_VERSION to 1L,
                        BUSINESS_ID to businessId,
                        OWNER_UID to ownerUid,
                        PURCHASE_ID to prepared.purchaseId,
                        RECEIPT_ID to prepared.receiptId,
                        SLOT to PRIMARY,
                        CREATED_AT to serverTime,
                    ),
                )
            } else {
                tx.set(
                    requireNotNull(overrideSlotRef),
                    linkedMapOf(
                        SCHEMA_VERSION to 1L,
                        BUSINESS_ID to businessId,
                        OWNER_UID to ownerUid,
                        PURCHASE_ID to prepared.purchaseId,
                        RECEIPT_ID to prepared.receiptId,
                        SLOT to OVERRIDE,
                        PRIMARY_IDENTITY_HASH to prepared.identityDocumentId,
                        SOURCE_DRAFT_ID to duplicateOverride.sourceDraftId,
                        EXISTING_PURCHASE_ID to duplicateOverride.existingPurchaseId,
                        CREATED_AT to serverTime,
                    ),
                )
            }
            tx.set(
                metadataRef,
                purchaseMetadata(
                    businessId = businessId,
                    seq = seq,
                    purchaseId = prepared.purchaseId,
                    receiptId = prepared.receiptId,
                    operationId = prepared.operationId,
                    serverTime = serverTime,
                ),
            )
            plans.forEach { plan -> tx.set(plan.reference, plan.record) }
            tx.set(
                inventoryChangeRef,
                inventoryChange(
                    businessId = businessId,
                    kind = PURCHASE_KIND,
                    seq = inventorySeq,
                    receiptId = prepared.receiptId,
                    operationId = prepared.operationId,
                    balances = plans.map(InventoryBalancePlan::projection),
                    serverTime = serverTime,
                ),
            )
            tx.set(
                inventoryMetadataRef,
                inventoryMetadata(
                    businessId = businessId,
                    seq = inventorySeq,
                    kind = PURCHASE_KIND,
                    receiptId = prepared.receiptId,
                    operationId = prepared.operationId,
                    serverTime = serverTime,
                ),
                SetOptions.merge(),
            )
            BackupTransportResult.Acknowledged(prepared.receiptId, envelope.idempotencyKey)
        }.await()
    }

    private suspend fun writeVoid(
        firestore: FirebaseFirestore,
        envelope: BackupEnvelope,
        payloadText: String,
        ownerUid: String,
    ): BackupTransportResult {
        val prepared = SparkPurchaseVoidCodec.prepare(
            payloadText = payloadText,
            expectedPurchaseId = envelope.purchaseId?.value,
            idempotencyKey = envelope.idempotencyKey,
        )
        val businessId = envelope.targetCloudBusinessId.value
        val operationId = envelope.idempotencyKey
        val operationDocumentId = SparkFirestoreSchema.operationDocumentId(operationId)
        val receiptId = purchaseReceiptId(businessId, envelope.idempotencyKey)
        // Functions ordena impacts por identidad remota y reconstruye todas las claves antes
        // de hashear. Persistir la misma cadena garantiza replay Spark -> Blaze.
        val requestHash = prepared.payloadHash
        val business = firestore.collection(BUSINESSES).document(businessId)
        val purchaseRef = business.collection(PURCHASES).document(prepared.purchaseId)
        val keyRef = business.collection(PURCHASE_KEYS).document(operationDocumentId)
        val metadataRef = business.collection(SYNC).document(PURCHASE_METADATA)
        val inventoryMetadataRef = business.collection(SYNC).document(INVENTORY_METADATA)
        val syncChangeRef = business.collection(PURCHASE_CHANGES).document(prepared.purchaseId)
        val inventoryChangeRef = business.collection(INVENTORY_CHANGES).document(receiptId)
        val voidRecordRef = purchaseRef.collection(VOID_RECORD).document(VOID_RECORD_DOCUMENT)

        return firestore.runTransaction { tx ->
            val existingKey = tx.get(keyRef)
            val purchaseSnapshot = tx.get(purchaseRef)
            val metadata = tx.get(metadataRef)
            val inventoryMetadata = tx.get(inventoryMetadataRef)
            val changeSnapshot = tx.get(syncChangeRef)
            val inventoryChangeSnapshot = tx.get(inventoryChangeRef)
            val voidRecordSnapshot = tx.get(voidRecordRef)

            if (existingKey.exists()) {
                val key = existingKey.data.orEmpty()
                return@runTransaction if (
                    key[REQUEST_HASH] == requestHash &&
                    key[PURCHASE_ID] == prepared.purchaseId &&
                    key[RECEIPT_ID] == receiptId
                ) {
                    BackupTransportResult.Acknowledged(receiptId, envelope.idempotencyKey)
                } else {
                    BackupTransportResult.Conflict(
                        VOID_CONFLICT,
                        remotePurchaseId = prepared.purchaseId,
                        remoteReceiptId = key[RECEIPT_ID] as? String,
                    )
                }
            }
            if (!purchaseSnapshot.exists() || !changeSnapshot.exists()) {
                return@runTransaction BackupTransportResult.Conflict(PURCHASE_NOT_SYNCED)
            }
            val purchase = purchaseSnapshot.data.orEmpty()
            if (purchase[STATUS] == VOIDED) {
                return@runTransaction BackupTransportResult.Conflict(
                    VOID_CONFLICT,
                    remotePurchaseId = prepared.purchaseId,
                    remoteReceiptId = purchase[VOID_RECEIPT_ID] as? String,
                )
            }
            if (purchase[STATUS] != POSTED || inventoryChangeSnapshot.exists() ||
                voidRecordSnapshot.exists()
            ) {
                return@runTransaction BackupTransportResult.PermanentFailure(
                    CORRUPT_REMOTE_DATA,
                )
            }
            SparkPurchaseVoidCodec.validatePurchaseSemantics(purchase, prepared)
            val effects = voidEffects(prepared, purchase)
            val balanceRefs = effects.associateWith { effect ->
                business.collection(INVENTORY_BALANCES).document(effect.balanceDocumentId)
            }
            val balanceSnapshots = balanceRefs.mapValues { (_, ref) -> tx.get(ref) }
            val seq = metadata.nextSequence()
            val inventorySeq = inventoryMetadata.nextSequence()
            val serverTime = FieldValue.serverTimestamp()
            val currency = CurrencyCode.of(purchase.requiredString(CURRENCY))
            val nowMillis = maxOf(
                System.currentTimeMillis(),
                balanceSnapshots.values.maxOfOrNull { snapshot ->
                    snapshot.data.orEmpty().safeLongOrNull(UPDATED_AT_MILLIS) ?: 0L
                } ?: 0L,
            )
            val plans = effects.map { effect ->
                planInventoryBalance(
                    businessId = envelope.targetCloudBusinessId,
                    effect = effect,
                    snapshot = requireNotNull(balanceSnapshots[effect]),
                    currency = currency,
                    inventorySeq = inventorySeq,
                    updatedAtMillis = nowMillis,
                    operationId = operationId,
                    serverTime = serverTime,
                    mode = InventoryMutationMode.VOID,
                )
            }
            val updatedPurchase = LinkedHashMap(purchase).apply {
                this[STATUS] = VOIDED
                this[SEQ] = seq
                this[VOID_RECEIPT_ID] = receiptId
                this[VOID_IDEMPOTENCY_KEY] = envelope.idempotencyKey
                this[VOID_PAYLOAD_HASH] = requestHash
                this[VOID_REASON] = prepared.reason
                this[VOIDED_AT] = serverTime
                this[VOIDED_BY] = ownerUid
                this[VOID_INVENTORY_SEQ] = inventorySeq
                this[LAST_OPERATION_ID] = operationId
            }
            val originalChange = changeSnapshot.data.orEmpty()
            val updatedChange = LinkedHashMap(originalChange).apply {
                this[STATUS] = VOIDED
                this[SEQ] = seq
                this[OPERATION_ID] = operationId
                this[SYNCED_AT] = serverTime
            }
            tx.set(purchaseRef, updatedPurchase)
            tx.set(syncChangeRef, updatedChange)
            tx.set(
                voidRecordRef,
                linkedMapOf(
                    SCHEMA_VERSION to 1L,
                    BUSINESS_ID to businessId,
                    OWNER_UID to ownerUid,
                    CLOUD_ACTOR_UID to ownerUid,
                    PURCHASE_ID to prepared.purchaseId,
                    OPERATION_ID to operationId,
                    IDEMPOTENCY_KEY to envelope.idempotencyKey,
                    RECEIPT_ID to receiptId,
                    PAYLOAD to prepared.canonicalPayloadText,
                    IMPACT_HASH to prepared.impactHash,
                    VOID_REASON to prepared.reason,
                    SYNCED_AT to serverTime,
                ),
            )
            tx.set(
                keyRef,
                operationKey(
                    businessId = businessId,
                    operationId = operationId,
                    idempotencyKey = envelope.idempotencyKey,
                    purchaseId = prepared.purchaseId,
                    receiptId = receiptId,
                    seq = seq,
                    inventorySeq = inventorySeq,
                    requestHash = requestHash,
                    ownerUid = ownerUid,
                    serverTime = serverTime,
                ),
            )
            tx.set(
                metadataRef,
                purchaseMetadata(
                    businessId = businessId,
                    seq = seq,
                    purchaseId = prepared.purchaseId,
                    receiptId = receiptId,
                    operationId = operationId,
                    serverTime = serverTime,
                ),
            )
            plans.forEach { plan -> tx.set(plan.reference, plan.record) }
            tx.set(
                inventoryChangeRef,
                inventoryChange(
                    businessId = businessId,
                    kind = PURCHASE_VOID_KIND,
                    seq = inventorySeq,
                    receiptId = receiptId,
                    operationId = operationId,
                    balances = plans.map(InventoryBalancePlan::projection),
                    serverTime = serverTime,
                ),
            )
            tx.set(
                inventoryMetadataRef,
                inventoryMetadata(
                    businessId = businessId,
                    seq = inventorySeq,
                    kind = PURCHASE_VOID_KIND,
                    receiptId = receiptId,
                    operationId = operationId,
                    serverTime = serverTime,
                ),
                SetOptions.merge(),
            )
            BackupTransportResult.Acknowledged(receiptId, envelope.idempotencyKey)
        }.await()
    }

    private fun preparePurchase(
        envelope: BackupEnvelope,
        document: Map<String, Any?>,
    ): PreparedPurchase {
        require(document.safeLong(VERSION) == CURRENT_PURCHASE_DOCUMENT_VERSION)
        val businessId = document.requiredString(BUSINESS_ID)
        require(businessId == envelope.targetCloudBusinessId.value)
        val purchaseId = document.requiredString(PURCHASE_ID)
        require(purchaseId == envelope.purchaseId?.value)
        require(document.requiredString(STATUS) == POSTED)
        require(document.requiredString(IDEMPOTENCY_KEY) == envelope.idempotencyKey)
        val currency = CurrencyCode.of(document.requiredString(CURRENCY))
        val postedAt = document.safeLong(POSTED_AT)
        val lines = document.requiredMapList(LINES)
        val lineNames = lines.associate { line ->
            line.requiredString(PURCHASE_LINE_ID) to line.requiredString(PRODUCT_NAME)
        }
        val movements = document.requiredMapList(MOVEMENTS)
        require(movements.isNotEmpty())
        val auditEventIds = (document[AUDIT_EVENT_IDS] as? List<*>)?.map { auditEventId ->
            (auditEventId as? String)?.also { require(CANONICAL_UUID.matches(it)) }
                ?: throw IllegalArgumentException(AUDIT_EVENT_IDS)
        } ?: throw IllegalArgumentException(AUDIT_EVENT_IDS)
        val duplicateOverride = SparkPurchaseDuplicateOverrideCodec.parse(
            raw = document[DUPLICATE_OVERRIDE],
            purchaseId = purchaseId,
            auditEventIds = auditEventIds,
        )
        val movementSummary = movements.map { movement ->
            val lineId = movement.requiredString(PURCHASE_LINE_ID)
            linkedMapOf<String, Any?>(
                PRODUCT_ID to movement.requiredString(PRODUCT_ID),
                PRODUCT_NAME to lineNames[lineId],
                TYPE to movement.requiredString(TYPE).also { require(it == PURCHASE_KIND) },
                QUANTITY_DELTA to movement.requiredDecimal(QUANTITY_DELTA).canonicalDecimal(),
            )
        }
        val effects = purchaseEffects(movements)
        val operationId = envelope.idempotencyKey
        // Misma proyeccion y SHA que purchasePayloadHash() en Functions. Asi un ACK perdido
        // antes de cambiar a Blaze se reconoce como replay, no como una compra incompatible.
        val hashDocument = LinkedHashMap(document).apply {
            this[DUPLICATE_OVERRIDE] = SparkPurchaseDuplicateOverrideCodec.hashMap(
                duplicateOverride,
            )
        }
        val persistedDocument = LinkedHashMap(document).apply {
            this[DUPLICATE_OVERRIDE] = SparkPurchaseDuplicateOverrideCodec.persistedMap(
                duplicateOverride,
            )
        }
        val requestHash = SparkFirestoreSchema.sha256(
            SparkFirestoreSchema.jsonStringify(hashDocument),
        )
        val identityDocumentId = SparkPurchaseDuplicateOverrideCodec.identityDocumentId(document)
        return PreparedPurchase(
            document = persistedDocument,
            purchaseId = purchaseId,
            currency = currency,
            postedAtMillis = postedAt,
            movementSummary = movementSummary,
            effects = effects,
            operationId = operationId,
            operationDocumentId = SparkFirestoreSchema.operationDocumentId(operationId),
            receiptId = purchaseReceiptId(businessId, envelope.idempotencyKey),
            requestHash = requestHash,
            identityDocumentId = identityDocumentId,
            duplicateOverride = duplicateOverride,
            overrideSlotDocumentId = duplicateOverride?.let { override ->
                SparkPurchaseDuplicateOverrideCodec.slotDocumentId(
                    identityDocumentId = identityDocumentId,
                    sourceDraftId = override.sourceDraftId,
                )
            },
        )
    }

    private suspend fun purchaseCatalogIsReady(
        firestore: FirebaseFirestore,
        prepared: PreparedPurchase,
        envelope: BackupEnvelope,
    ): Boolean = coroutineScope {
        val business = firestore.collection(BUSINESSES)
            .document(envelope.targetCloudBusinessId.value)
        prepared.effects.map(InventoryEffect::productId).distinct().map { productId ->
            async {
                business.collection(PRODUCTS).document(productId).get(Source.SERVER).await()
            }
        }.awaitAll().all { product ->
            SparkPurchaseCatalogPreflight.isActiveProduct(product.data)
        }
    }

    private suspend fun purchaseRemoteRecordExists(
        firestore: FirebaseFirestore,
        prepared: PreparedPurchase,
        envelope: BackupEnvelope,
    ): Boolean = coroutineScope {
        val business = firestore.collection(BUSINESSES)
            .document(envelope.targetCloudBusinessId.value)
        business.collection(PURCHASES).document(prepared.purchaseId)
            .get(Source.SERVER).await().exists()
    }

    private fun purchaseEffects(movements: List<Map<String, Any?>>): List<InventoryEffect> =
        movements.groupBy { movement ->
            val productId = movement.requiredString(PRODUCT_ID)
            val locationName = movement.requiredString(LOCATION_NAME)
            productId to canonicalLocationName(locationName)
        }.map { (key, grouped) ->
            val displayName = grouped.first().requiredString(LOCATION_NAME)
            val quantity = grouped.fold(BigDecimal.ZERO) { total, movement ->
                total + movement.requiredDecimal(QUANTITY_DELTA)
            }
            val incoming = grouped.fold(BigDecimal.ZERO) { total, movement ->
                val delta = movement.requiredDecimal(QUANTITY_DELTA)
                val applied = movement.requiredDecimal(APPLIED_COST_TOTAL).also {
                    require(it.signum() >= 0)
                }
                total + if (delta.signum() < 0) applied.negate() else applied
            }
            InventoryEffect(
                productId = key.first,
                locationName = displayName,
                canonicalLocationName = key.second,
                balanceDocumentId = SparkFirestoreSchema.inventoryBalanceDocumentId(
                    key.first,
                    displayName,
                ),
                quantityDelta = quantity,
                incomingValue = incoming,
                sourceLocationIds = grouped.mapTo(sortedSetOf()) {
                    it.requiredString(LOCATION_ID)
                },
            )
        }.sortedWith(compareBy(InventoryEffect::productId, InventoryEffect::canonicalLocationName))

    private fun voidEffects(
        payload: SparkPreparedVoid,
        purchase: Map<String, Any?>,
    ): List<InventoryEffect> {
        val currency = purchase.requiredString(CURRENCY)
        val movements = purchase.requiredMapList(MOVEMENTS)
        val locationByOrigin = movements.associate { movement ->
            (movement.requiredString(PRODUCT_ID) to movement.requiredString(LOCATION_ID)) to
                movement.requiredString(LOCATION_NAME)
        }
        return payload.impacts.groupBy { impact ->
            val locationName = locationByOrigin[impact.productId to impact.locationId]
                ?: throw IllegalArgumentException(LOCATION_NAME)
            require(impact.currency == currency)
            impact.productId to canonicalLocationName(locationName)
        }.map { (key, grouped) ->
            val first = grouped.first()
            val locationName = requireNotNull(locationByOrigin[first.productId to first.locationId])
            InventoryEffect(
                productId = key.first,
                locationName = locationName,
                canonicalLocationName = key.second,
                balanceDocumentId = SparkFirestoreSchema.inventoryBalanceDocumentId(
                    key.first,
                    locationName,
                ),
                quantityDelta = grouped.fold(BigDecimal.ZERO) { total, impact ->
                    total + impact.reversalQuantity
                },
                incomingValue = BigDecimal.ZERO,
                sourceLocationIds = grouped.mapTo(sortedSetOf(), SparkVoidImpact::locationId),
            )
        }.sortedWith(compareBy(InventoryEffect::productId, InventoryEffect::canonicalLocationName))
    }

    private fun planInventoryBalance(
        businessId: com.facturastock.app.domain.model.id.BusinessId,
        effect: InventoryEffect,
        snapshot: DocumentSnapshot,
        currency: CurrencyCode,
        inventorySeq: Long,
        updatedAtMillis: Long,
        operationId: String,
        serverTime: FieldValue,
        mode: InventoryMutationMode,
    ): InventoryBalancePlan {
        val stored = if (snapshot.exists()) {
            SparkInventoryBalanceCodec.parse(
                documentId = snapshot.id,
                raw = snapshot.data.orEmpty(),
                expectedBusinessId = businessId,
                expectedProductId = effect.productId,
                expectedCanonicalLocationName = effect.canonicalLocationName,
            )
        } else {
            null
        }
        if (stored == null && (mode == InventoryMutationMode.VOID || effect.quantityDelta.signum() <= 0)) {
            throw SparkMutationFailure.InventoryMigrationRequired
        }
        if (stored != null && stored.currency != currency) throw SparkMutationFailure.Rejected
        val openingQuantity = stored?.quantityOnHand ?: BigDecimal.ZERO
        val openingAverage = stored?.averageUnitCost ?: BigDecimal.ZERO
        val resultingQuantity = openingQuantity + effect.quantityDelta
        val resultingAverage = when {
            mode != InventoryMutationMode.PURCHASE || effect.quantityDelta.signum() <= 0 ->
                openingAverage
            openingQuantity.signum() <= 0 -> effect.incomingValue.divide(
                effect.quantityDelta,
                INVENTORY_DIVISION_SCALE,
                RoundingMode.HALF_EVEN,
            )
            else -> (openingQuantity * openingAverage + effect.incomingValue).divide(
                resultingQuantity,
                INVENTORY_DIVISION_SCALE,
                RoundingMode.HALF_EVEN,
            )
        }.stripTrailingZeros()
        if (resultingAverage.signum() < 0) throw SparkMutationFailure.CorruptRemoteData
        val expectedVersion = stored?.version
        val resultingVersion = expectedVersion?.plus(1L) ?: 0L
        if (resultingVersion >= SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
            throw SparkMutationFailure.CorruptRemoteData
        }
        val effectiveUpdatedAt = maxOf(updatedAtMillis, stored?.updatedAtMillis ?: 0L)
        val displayName = stored?.locationName ?: effect.locationName
        val record = SparkInventoryBalanceCodec.record(
            businessId = businessId,
            productId = effect.productId,
            locationName = displayName,
            quantityOnHand = resultingQuantity,
            averageUnitCost = resultingAverage,
            currency = currency,
            version = resultingVersion,
            lastSeq = inventorySeq,
            updatedAtMillis = effectiveUpdatedAt,
            lastSourceLocationId = effect.sourceLocationIds.singleOrNull(),
            operationId = operationId,
            expectedVersion = expectedVersion,
            syncedAt = serverTime,
        )
        val projection = SparkInventoryBalanceCodec.projection(
            productId = effect.productId,
            locationName = displayName,
            quantityOnHand = resultingQuantity,
            averageUnitCost = resultingAverage,
            currency = currency,
            version = resultingVersion,
            updatedAtMillis = effectiveUpdatedAt,
            seq = inventorySeq,
        )
        return InventoryBalancePlan(snapshot.reference, record, projection)
    }

    private fun purchaseChange(
        prepared: PreparedPurchase,
        seq: Long,
        ownerUid: String,
        operationId: String,
        serverTime: FieldValue,
    ): Map<String, Any?> = linkedMapOf(
        SCHEMA_VERSION to 1L,
        BUSINESS_ID to prepared.document.requiredString(BUSINESS_ID),
        OWNER_UID to ownerUid,
        OPERATION_ID to operationId,
        SEQ to seq,
        PURCHASE_ID to prepared.purchaseId,
        STATUS to POSTED,
        DOCUMENT_TYPE to prepared.document[DOCUMENT_TYPE],
        DOCUMENT_SERIES to prepared.document[DOCUMENT_SERIES],
        DOCUMENT_NUMBER to prepared.document[DOCUMENT_NUMBER],
        ISSUE_DATE to prepared.document[ISSUE_DATE],
        CURRENCY to prepared.document[CURRENCY],
        SUPPLIER_RUC to prepared.document[SUPPLIER_RUC],
        SUPPLIER_LEGAL_NAME to prepared.document[SUPPLIER_LEGAL_NAME],
        TOTAL_MINOR_UNITS to prepared.document[TOTAL_MINOR_UNITS],
        MOVEMENT_SUMMARY to prepared.movementSummary,
        RECEIPT_ID to prepared.receiptId,
        SYNCED_AT to serverTime,
    )

    private fun operationKey(
        businessId: String,
        operationId: String,
        idempotencyKey: String,
        purchaseId: String,
        receiptId: String,
        seq: Long,
        inventorySeq: Long,
        requestHash: String,
        ownerUid: String,
        serverTime: FieldValue,
    ): Map<String, Any?> = linkedMapOf(
        SCHEMA_VERSION to 1L,
        BUSINESS_ID to businessId,
        OWNER_UID to ownerUid,
        OPERATION_ID to operationId,
        IDEMPOTENCY_KEY to idempotencyKey,
        PURCHASE_ID to purchaseId,
        RECEIPT_ID to receiptId,
        SEQ to seq,
        INVENTORY_SEQ to inventorySeq,
        REQUEST_HASH to requestHash,
        CREATED_AT to serverTime,
    )

    private fun purchaseMetadata(
        businessId: String,
        seq: Long,
        purchaseId: String,
        receiptId: String,
        operationId: String,
        serverTime: FieldValue,
    ): Map<String, Any?> = linkedMapOf(
        SCHEMA_VERSION to 1L,
        BUSINESS_ID to businessId,
        SEQ to seq,
        LAST_SYNCED_AT to serverTime,
        LAST_PURCHASE_ID to purchaseId,
        LAST_RECEIPT_ID to receiptId,
        LAST_OPERATION_ID to operationId,
    )

    private fun inventoryMetadata(
        businessId: String,
        seq: Long,
        kind: String,
        receiptId: String,
        operationId: String,
        serverTime: FieldValue,
    ): Map<String, Any?> = linkedMapOf(
        SCHEMA_VERSION to 1L,
        BUSINESS_ID to businessId,
        SEQ to seq,
        LAST_KIND to kind,
        LAST_RECEIPT_ID to receiptId,
        LAST_OPERATION_ID to operationId,
        UPDATED_AT to serverTime,
    )

    private fun inventoryChange(
        businessId: String,
        kind: String,
        seq: Long,
        receiptId: String,
        operationId: String,
        balances: List<Map<String, Any?>>,
        serverTime: FieldValue,
    ): Map<String, Any?> = linkedMapOf(
        SCHEMA_VERSION to 1L,
        BUSINESS_ID to businessId,
        KIND to kind,
        SEQ to seq,
        RECEIPT_ID to receiptId,
        OPERATION_ID to operationId,
        SALE to null,
        BALANCES to balances,
        SYNCED_AT to serverTime,
    )

    private fun catalogConflict(
        snapshot: DocumentSnapshot,
        businessId: String,
    ): BackupTransportResult.Conflict = BackupTransportResult.Conflict(
        reason = CATALOG_VERSION_CONFLICT,
        remoteEntity = snapshot.data?.catalogConflictMetadata(businessId),
    )

    private fun Map<String, Any?>.catalogConflictMetadata(
        businessId: String,
    ): RemoteConflictMetadata? = runCatching {
        RemoteConflictMetadata(
            entityId = requiredString(ENTITY_ID),
            version = safeLong(VERSION),
            snapshotPayload = requiredString(SNAPSHOT_PAYLOAD),
            syncedAtMillis = (this[SYNCED_AT] as? Timestamp)?.toDate()?.time,
            origin = CLOUD,
            cloudBusinessId = businessId,
        )
    }.getOrNull()

    private fun DocumentSnapshot.semanticIndexOwnerOrNull(): SparkCatalogSemanticIndexOwner? {
        if (!exists()) return null
        val values = data ?: throw SparkMutationFailure.CorruptRemoteData
        if (values.keys != SEMANTIC_INDEX_KEYS) throw SparkMutationFailure.CorruptRemoteData
        val entityId = values[ENTITY_ID] as? String
            ?: throw SparkMutationFailure.CorruptRemoteData
        val version = values.safeLongOrNull(VERSION)
            ?: throw SparkMutationFailure.CorruptRemoteData
        if (!CANONICAL_UUID.matches(entityId) || version !in 1..SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
            throw SparkMutationFailure.CorruptRemoteData
        }
        return SparkCatalogSemanticIndexOwner(entityId, version)
    }

    private fun DocumentSnapshot.nextSequence(): Long {
        val current = if (exists()) data.orEmpty().safeLong(SEQ) else 0L
        if (current !in 0 until SparkFirestoreSchema.MAX_SAFE_SEQUENCE) {
            throw SparkMutationFailure.CorruptRemoteData
        }
        return current + 1L
    }

    private fun firestoreFailure(failure: Throwable): BackupTransportResult =
        when (SparkFirestoreErrorMapper.fromException(failure)) {
            AccountError.NetworkUnavailable, AccountError.Unavailable ->
                BackupTransportResult.TransientFailure(NETWORK_UNAVAILABLE)
            AccountError.SessionExpired, AccountError.NotAuthenticated ->
                BackupTransportResult.TransientFailure(SESSION_EXPIRED)
            AccountError.Conflict -> BackupTransportResult.TransientFailure(SPARK_CONTENTION)
            AccountError.PermissionDenied ->
                BackupTransportResult.PermanentFailure(PERMISSION_DENIED)
            else -> BackupTransportResult.TransientFailure(UNEXPECTED_SPARK_ERROR)
        }

    private fun purchaseReceiptId(businessId: String, idempotencyKey: String): String =
        "rcpt_" + SparkFirestoreSchema.sha256(
            "facturastock:backup:$businessId:$idempotencyKey",
        ).take(32)

    private fun Any?.stringMapOrNull(): Map<String, Any?>? {
        val raw = this as? Map<*, *> ?: return null
        if (raw.keys.any { it !is String }) return null
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (this[key] as? String)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(key)

    private fun Map<String, Any?>.requiredDecimal(key: String): BigDecimal =
        BigDecimal(requiredString(key))

    private fun Map<String, Any?>.requiredMapList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.map { value ->
            value.stringMapOrNull() ?: throw IllegalArgumentException(key)
        } ?: throw IllegalArgumentException(key)

    private fun Map<String, Any?>.safeLong(key: String): Long =
        safeLongOrNull(key) ?: throw IllegalArgumentException(key)

    private fun Map<String, Any?>.safeLongOrNull(key: String): Long? = when (val value = this[key]) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toLong()
        is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        else -> null
    }

    private fun Map<String, Any?>.snapshotValue(key: String): Any? =
        this[SNAPSHOT].stringMapOrNull()?.get(key)

    private data class PreparedPurchase(
        val document: Map<String, Any?>,
        val purchaseId: String,
        val currency: CurrencyCode,
        val postedAtMillis: Long,
        val movementSummary: List<Map<String, Any?>>,
        val effects: List<InventoryEffect>,
        val operationId: String,
        val operationDocumentId: String,
        val receiptId: String,
        val requestHash: String,
        val identityDocumentId: String,
        val duplicateOverride: SparkPurchaseDuplicateOverride?,
        val overrideSlotDocumentId: String?,
    )

    private data class InventoryEffect(
        val productId: String,
        val locationName: String,
        val canonicalLocationName: String,
        val balanceDocumentId: String,
        val quantityDelta: BigDecimal,
        val incomingValue: BigDecimal,
        val sourceLocationIds: Set<String>,
    )

    private data class InventoryBalancePlan(
        val reference: DocumentReference,
        val record: Map<String, Any?>,
        val projection: Map<String, Any?>,
    )

    private enum class InventoryMutationMode { PURCHASE, VOID }

    private companion object {
        const val INVENTORY_DIVISION_SCALE = 18
        const val CURRENT_PURCHASE_DOCUMENT_VERSION = 3L
        const val BUSINESSES = "businesses"
        const val PURCHASES = "purchases"
        const val PRODUCTS = "products"
        const val PURCHASE_KEYS = "syncKeys"
        const val PURCHASE_CHANGES = "syncChanges"
        const val DOCUMENT_INDEX = "documentIndex"
        const val CATALOG_OPERATIONS = "catalogSyncOperations"
        const val CATALOG_CHANGES = "catalogSyncChanges"
        const val SYNC = "sync"
        const val PURCHASE_METADATA = "metadata"
        const val CATALOG_METADATA = "catalogMetadata"
        const val INVENTORY_METADATA = "inventoryMetadata"
        const val INVENTORY_BALANCES = "inventoryBalances"
        const val INVENTORY_CHANGES = "inventorySyncChanges"
        const val VOID_RECORD = "voidRecord"
        const val VOID_RECORD_DOCUMENT = "record"
        const val SYNC_PRODUCT = "SYNC_PRODUCT"
        const val SYNC_SUPPLIER = "SYNC_SUPPLIER"
        const val SYNC_PURCHASE = "SYNC_PURCHASE"
        const val SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
        const val PURCHASE_KIND = "PURCHASE"
        const val PURCHASE_VOID_KIND = "PURCHASE_VOID"
        const val POSTED = "POSTED"
        const val VOIDED = "VOIDED"
        const val PRIMARY = "PRIMARY"
        const val OVERRIDE = "OVERRIDE"
        const val CLOUD = "CLOUD"
        const val SCHEMA_VERSION = "schemaVersion"
        const val BUSINESS_ID = "businessId"
        const val OWNER_UID = "ownerUid"
        const val CLOUD_ACTOR_UID = "cloudActorUid"
        const val OPERATION_ID = "operationId"
        const val OPERATION_TYPE = "operationType"
        const val IDEMPOTENCY_KEY = "idempotencyKey"
        const val REQUEST_HASH = "requestHash"
        const val ENTITY_ID = "entityId"
        const val ENTITY_TYPE = "entityType"
        const val VERSION = "version"
        const val MUTATION = "mutation"
        const val SNAPSHOT = "snapshot"
        const val SNAPSHOT_PAYLOAD = "snapshotPayload"
        const val SNAPSHOT_SHA256 = "snapshotSha256"
        const val PURCHASE_ID = "purchaseId"
        const val EXISTING_PURCHASE_ID = "existingPurchaseId"
        const val STATUS = "status"
        const val DOCUMENT_TYPE = "documentType"
        const val DOCUMENT_SERIES = "documentSeries"
        const val DOCUMENT_NUMBER = "documentNumber"
        const val ISSUE_DATE = "issueDate"
        const val CURRENCY = "currency"
        const val SUPPLIER_RUC = "supplierRuc"
        const val SUPPLIER_LEGAL_NAME = "supplierLegalName"
        const val TOTAL_MINOR_UNITS = "totalMinorUnits"
        const val POSTED_AT = "postedAt"
        const val LINES = "lines"
        const val MOVEMENTS = "movements"
        const val PURCHASE_LINE_ID = "purchaseLineId"
        const val PRODUCT_ID = "productId"
        const val PRODUCT_NAME = "productName"
        const val LOCATION_ID = "locationId"
        const val LOCATION_NAME = "locationName"
        const val TYPE = "type"
        const val QUANTITY_DELTA = "quantityDelta"
        const val APPLIED_COST_TOTAL = "appliedCostTotal"
        const val MOVEMENT_SUMMARY = "movementSummary"
        const val RECEIPT_ID = "receiptId"
        const val SYNC_PAYLOAD_HASH = "syncPayloadHash"
        const val SYNCED_AT = "syncedAt"
        const val SYNCED_BY = "syncedBy"
        const val SEQ = "seq"
        const val INVENTORY_SEQ = "inventorySeq"
        const val UPDATED_AT = "updatedAt"
        const val UPDATED_AT_MILLIS = "updatedAtMillis"
        const val CREATED_AT = "createdAt"
        const val LAST_SYNCED_AT = "lastSyncedAt"
        const val LAST_PURCHASE_ID = "lastPurchaseId"
        const val LAST_RECEIPT_ID = "lastReceiptId"
        const val LAST_OPERATION_ID = "lastOperationId"
        const val LAST_KIND = "lastKind"
        const val KIND = "kind"
        const val SALE = "sale"
        const val BALANCES = "balances"
        const val SLOT = "slot"
        const val PRIMARY_IDENTITY_HASH = "primaryIdentityHash"
        const val DUPLICATE_OVERRIDE = "duplicateOverride"
        const val SOURCE_DRAFT_ID = "sourceDraftId"
        const val AUDIT_EVENT_IDS = "auditEventIds"
        const val PAYLOAD = "payload"
        const val IMPACTS = "impacts"
        const val IMPACT_HASH = "impactHash"
        const val REASON = "reason"
        const val REVERSAL_QUANTITY = "reversalQuantity"
        const val VOID_RECEIPT_ID = "voidReceiptId"
        const val VOID_IDEMPOTENCY_KEY = "voidIdempotencyKey"
        const val VOID_PAYLOAD_HASH = "voidPayloadHash"
        const val VOID_REASON = "voidReason"
        const val VOIDED_AT = "voidedAt"
        const val VOIDED_BY = "voidedBy"
        const val VOID_INVENTORY_SEQ = "voidInventorySeq"
        const val CATALOG_VERSION_CONFLICT = "CATALOG_VERSION_CONFLICT"
        const val CATALOG_SEMANTIC_CONFLICT = "CATALOG_SEMANTIC_CONFLICT"
        const val CATALOG_CREATED_AT_IMMUTABLE = "CATALOG_CREATED_AT_IMMUTABLE"
        const val DOCUMENT_IDENTITY_EXISTS = "DOCUMENT_IDENTITY_EXISTS"
        const val DUPLICATE_TARGET_NOT_SYNCED = "DUPLICATE_TARGET_NOT_SYNCED"
        const val DUPLICATE_TARGET_STATUS = "DUPLICATE_TARGET_STATUS"
        const val DUPLICATE_TARGET_IDENTITY_MISMATCH = "DUPLICATE_TARGET_IDENTITY_MISMATCH"
        const val DOCUMENT_PRIMARY_INDEX_MISSING = "DOCUMENT_PRIMARY_INDEX_MISSING"
        const val DUPLICATE_OVERRIDE_SLOT_EXISTS = "DUPLICATE_OVERRIDE_SLOT_EXISTS"
        const val CATALOG_NOT_READY = "CATALOG_NOT_READY"
        const val PURCHASE_REPLAY_MISMATCH = "PURCHASE_REPLAY_MISMATCH"
        const val VOID_CONFLICT = "VOID_CONFLICT"
        const val PURCHASE_NOT_SYNCED = "PURCHASE_NOT_SYNCED"
        const val SPARK_OPERATION_KEY_MISSING = "SPARK_OPERATION_KEY_MISSING"
        const val CORRUPT_REMOTE_DATA = "CORRUPT_REMOTE_DATA"
        const val INVENTORY_MIGRATION_REQUIRED = "INVENTORY_MIGRATION_REQUIRED"
        const val STALE_REMOTE_STATE = "STALE_REMOTE_STATE"
        const val REJECTED_REMOTE_STATE = "REJECTED_REMOTE_STATE"
        const val INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK"
        const val MALFORMED_PURCHASE = "MALFORMED_PURCHASE_PAYLOAD"
        const val MALFORMED_VOID = "MALFORMED_PURCHASE_VOID_PAYLOAD"
        const val MALFORMED_SPARK_PAYLOAD = "MALFORMED_SPARK_PAYLOAD"
        const val UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION_TYPE"
        const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
        const val SPARK_CONTENTION = "SPARK_CONTENTION"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val UNEXPECTED_SPARK_ERROR = "UNEXPECTED_SPARK_ERROR"
        val SEMANTIC_INDEX_KEYS = setOf(ENTITY_ID, VERSION)
        val DUPLICATE_TARGET_STATUSES = setOf(POSTED, VOIDED)
        val CANONICAL_UUID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
