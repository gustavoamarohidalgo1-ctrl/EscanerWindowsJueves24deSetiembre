package com.facturastock.app.data.sync

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.account.AccountErrorMapper
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.AuthorizedLocalBalance
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.RemoteSalePostResult
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Autoridad cloud de una venta compartida. Si existe un enlace durable y el respaldo está activo,
 * el saldo se descuenta en Functions antes del commit Room; un fallo de red nunca se convierte en
 * una venta local que el segundo dispositivo no pueda conocer.
 */
@Singleton
class FirebaseRemoteSaleSyncRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val appConfiguration: AppConfigurationRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
) : RemoteSaleSyncRepository {
    override val available: Boolean
        get() = runtime.config != null

    override suspend fun postSale(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): RemoteSalePostResult = withContext<RemoteSalePostResult>(dispatchers.io) {
        val cloudBusinessId = cloudBusinessBindings.targetFor(localBusinessId)
            ?: return@withContext RemoteSalePostResult.NotRequired
        // Un binding durable no desaparece al apagar la preferencia. Permitir checkout local en
        // ese estado bifurcaría el stock entre teléfonos; se exige reactivar la sincronización.
        if (!available || !appConfiguration.current().backupEnabled) {
            return@withContext RemoteSalePostResult.OnlineRequired
        }
        val session = accountRepository.observeSession().first()
        if (session !is AccountSession.Active) {
            return@withContext RemoteSalePostResult.OnlineRequired
        }
        val sessionLink = session.link
        if (
            sessionLink == null || sessionLink.localBusinessId != localBusinessId ||
            sessionLink.cloudBusinessId != cloudBusinessId ||
            !cloudBusinessBindings.matches(localBusinessId, cloudBusinessId)
        ) {
            return@withContext RemoteSalePostResult.Rejected
        }
        val functions = runtime.functions()
            ?: return@withContext RemoteSalePostResult.OnlineRequired
        val outbound = buildOutbound(localBusinessId, cloudBusinessId, document)
            ?: return@withContext RemoteSalePostResult.Rejected
        try {
            runtime.prepareBusiness(functions, cloudBusinessId.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withContext mapPostFailure(failure, outbound.localByRemote)
        }
        postWithOptionalBootstrap(
            functions = functions,
            localBusinessId = localBusinessId,
            cloudBusinessId = cloudBusinessId,
            expectedUid = session.uid,
            document = document,
            outbound = outbound,
        )
    }

    override suspend fun pullInventoryChanges(
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SharedInventoryPullPage> = withContext(dispatchers.io) {
        val functions = runtime.functions()
            ?: return@withContext DomainResult.Failure(AccountError.Unavailable)
        val session = accountRepository.observeSession().first()
        if (session !is AccountSession.Active) {
            return@withContext DomainResult.Failure(AccountError.NotAuthenticated)
        }
        val link = session.link ?: return@withContext DomainResult.Failure(AccountError.Unavailable)
        if (
            link.cloudBusinessId != cloudBusinessId ||
            !cloudBusinessBindings.matches(link.localBusinessId, cloudBusinessId)
        ) {
            return@withContext DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
        }
        try {
            runtime.prepareBusiness(functions, cloudBusinessId.value)
            val result = functions.getHttpsCallable(LIST_INVENTORY_CHANGES_CALLABLE)
                .call(
                    mapOf(
                        "businessId" to cloudBusinessId.value,
                        "expectedUid" to session.uid,
                        "sinceSeq" to sinceSeq,
                        "limit" to limit,
                    ),
                )
                .await()
            val raw = result.data as? Map<*, *> ?: throw AccountException(AccountError.Unexpected)
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
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    private suspend fun buildOutbound(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): OutboundSale? = database.withTransaction {
        val localByRemote = linkedMapOf<RemoteInventoryKey, LocalInventoryKey>()
        val hashParts = mutableListOf("sale-content-v1", document.currency.value)
        val wireLines = document.lines.map { line ->
            val link = database.catalogSyncLinkDao().findByLocal(
                localBusinessId = localBusinessId.value,
                entityType = PRODUCT,
                localEntityId = line.productId.value,
            )
            if (link != null && link.cloudBusinessId != cloudBusinessId.value) {
                return@withTransaction null
            }
            val remoteProductId = link?.remoteEntityId ?: line.productId.value
            val remoteKey = RemoteInventoryKey(
                productId = remoteProductId,
                canonicalLocation = canonicalLocationName(line.locationName),
            )
            val localKey = LocalInventoryKey(line.productId, line.locationId)
            val previous = localByRemote.putIfAbsent(remoteKey, localKey)
            if (previous != null && previous != localKey) return@withTransaction null
            hashParts += listOf(
                line.saleLineId.value,
                line.position.toString(),
                remoteProductId,
                line.unitId.value,
                line.locationId.value,
                line.quantity.value.toPlainString(),
                line.unitPrice.minorUnits.toString(),
                line.discount.minorUnits.toString(),
                line.tax.minorUnits.toString(),
                line.lineTotal.minorUnits.toString(),
                document.currency.value,
            )
            linkedMapOf<String, Any?>(
                "saleLineId" to line.saleLineId.value,
                "position" to line.position,
                "productId" to remoteProductId,
                "unitId" to line.unitId.value,
                "locationId" to line.locationId.value,
                "productName" to line.productName,
                "unitCode" to line.unitCode,
                "locationName" to line.locationName,
                "barcode" to line.barcode,
                "quantity" to line.quantity.value.toPlainString(),
                "unitPriceMinorUnits" to line.unitPrice.minorUnits,
                "discountMinorUnits" to line.discount.minorUnits,
                "taxMinorUnits" to line.tax.minorUnits,
                "lineTotalMinorUnits" to line.lineTotal.minorUnits,
            )
        }
        val wireContentHash = hashParts.lengthPrefixedSha256()
        if (!document.checkoutIdempotencyKey.endsWith(":${document.contentHash}")) {
            return@withTransaction null
        }
        val wireCheckoutKey = document.checkoutIdempotencyKey.substringBeforeLast(':') +
            ":$wireContentHash"
        val idempotencyKey = "sync-sale:v1:${document.saleId.value}"
        val payloadVersion = if (document.credit == null) 1 else 2
        val wireDocument = linkedMapOf<String, Any?>(
            "version" to payloadVersion,
            "saleId" to document.saleId.value,
            "businessId" to cloudBusinessId.value,
            "status" to "POSTED",
            "currency" to document.currency.value,
            "subtotalMinorUnits" to document.subtotal.minorUnits,
            "discountMinorUnits" to document.discount.minorUnits,
            "taxMinorUnits" to document.tax.minorUnits,
            "totalMinorUnits" to document.total.minorUnits,
            "contentHash" to wireContentHash,
            "checkoutIdempotencyKey" to wireCheckoutKey,
            "createdAt" to document.createdAt.toEpochMilli(),
            "updatedAt" to document.updatedAt.toEpochMilli(),
            "postedAt" to document.postedAt.toEpochMilli(),
            "lines" to wireLines,
        )
        document.credit?.let { credit ->
            wireDocument["credit"] = linkedMapOf(
                "version" to 1,
                "debtId" to credit.debtId.value,
                "debtorNameSnapshot" to credit.debtorNameSnapshot,
                "dueAt" to credit.dueAt?.toEpochMilli(),
            )
        }
        OutboundSale(
            idempotencyKey = idempotencyKey,
            payloadVersion = payloadVersion,
            cloudBusinessId = cloudBusinessId,
            localByRemote = localByRemote,
            document = wireDocument,
        )
    }

    private fun authorizedResult(
        document: SharedSaleDocument,
        outbound: OutboundSale,
        ack: FirebaseSalePostAck,
    ): RemoteSalePostResult {
        val credit = document.credit
        val authorizedDebt = ack.debt
        val debtMatches = if (credit == null) {
            authorizedDebt == null
        } else {
            authorizedDebt != null &&
                authorizedDebt.businessId == outbound.cloudBusinessId &&
                authorizedDebt.debtId == credit.debtId &&
                authorizedDebt.saleId == document.saleId &&
                authorizedDebt.debtorNameSnapshot == credit.debtorNameSnapshot &&
                authorizedDebt.currency == document.currency &&
                authorizedDebt.originalAmount == document.total &&
                authorizedDebt.balance == document.total &&
                authorizedDebt.status == com.facturastock.app.domain.model.DebtStatus.OPEN &&
                authorizedDebt.dueAt == credit.dueAt &&
                authorizedDebt.version == 1L &&
                authorizedDebt.createdAt == ack.postedAt &&
                authorizedDebt.updatedAt == ack.postedAt &&
                authorizedDebt.paidAt == null
        }
        if (
            ack.idempotencyKey != outbound.idempotencyKey ||
            ack.postedAt < document.updatedAt || !debtMatches ||
            ack.balances.any { balance ->
                balance.currency != document.currency || balance.version < 1L ||
                    balance.updatedAt != ack.postedAt
            }
        ) {
            return RemoteSalePostResult.Rejected
        }
        val mapped = buildList {
            for (balance in ack.balances) {
                val local = outbound.localByRemote[
                    RemoteInventoryKey(
                        productId = balance.productId.value,
                        canonicalLocation = canonicalLocationName(balance.locationName),
                    )
                ] ?: return RemoteSalePostResult.Rejected
                add(
                    AuthorizedLocalBalance(
                        productId = local.productId,
                        locationId = local.locationId,
                        quantityOnHand = balance.quantityOnHand,
                        averageUnitCost = balance.averageUnitCost,
                        currencyCode = balance.currency.value,
                        remoteVersion = balance.version,
                        updatedAt = balance.updatedAt,
                    ),
                )
            }
        }
        if (
            mapped.size != outbound.localByRemote.size ||
            mapped.map { it.productId to it.locationId }.distinct().size != mapped.size
        ) {
            return RemoteSalePostResult.Rejected
        }
        return RemoteSalePostResult.Authorized(
            saleId = document.saleId,
            receiptId = ack.receiptId,
            seq = ack.seq,
            postedAt = ack.postedAt,
            balances = mapped,
        )
    }

    private suspend fun postWithOptionalBootstrap(
        functions: FirebaseFunctions,
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        expectedUid: String,
        document: SharedSaleDocument,
        outbound: OutboundSale,
    ): RemoteSalePostResult {
        var bootstrapAttempted = false
        while (true) {
            try {
                val result = functions.getHttpsCallable(POST_SALE_CALLABLE)
                    .call(
                        mapOf(
                            "businessId" to cloudBusinessId.value,
                            "expectedUid" to expectedUid,
                            "idempotencyKey" to outbound.idempotencyKey,
                            "operationType" to OPERATION_TYPE,
                            "payloadVersion" to outbound.payloadVersion,
                            "document" to outbound.document,
                        ),
                    )
                    .await()
                val raw = result.data as? Map<*, *>
                    ?: throw AccountException(AccountError.Unexpected)
                return authorizedResult(document, outbound, FirebaseSaleWireMapper.postAck(raw))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val mapped = mapPostFailure(failure, outbound.localByRemote)
                if (
                    mapped == RemoteSalePostResult.InventoryMigrationRequired &&
                    !bootstrapAttempted
                ) {
                    bootstrapAttempted = true
                    if (
                        bootstrapLegacyInventory(
                            functions = functions,
                            localBusinessId = localBusinessId,
                            cloudBusinessId = cloudBusinessId,
                            expectedUid = expectedUid,
                        )
                    ) {
                        continue
                    }
                }
                return mapped
            }
        }
    }

    /** Migración one-shot para instalaciones anteriores al ledger v4; el backend exige admin. */
    private suspend fun bootstrapLegacyInventory(
        functions: FirebaseFunctions,
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        expectedUid: String,
    ): Boolean {
        val payload = buildBootstrapPayload(localBusinessId, cloudBusinessId) ?: return false
        return try {
            val result = functions.getHttpsCallable(BOOTSTRAP_INVENTORY_CALLABLE)
                .call(
                    mapOf(
                        "businessId" to cloudBusinessId.value,
                        "expectedUid" to expectedUid,
                        "idempotencyKey" to payload.idempotencyKey,
                        "payloadVersion" to 1,
                        "locations" to payload.locations,
                        "balances" to payload.balances,
                    ),
                )
                .await()
            val raw = result.data as? Map<*, *> ?: return false
            val ack = FirebaseSaleWireMapper.bootstrapAck(raw)
            if (ack.idempotencyKey != payload.idempotencyKey) return false
            val ackKeys = ack.balances.mapTo(hashSetOf()) { balance ->
                RemoteInventoryKey(
                    productId = balance.productId.value,
                    canonicalLocation = canonicalLocationName(balance.locationName),
                )
            }
            ackKeys == payload.remoteKeys
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun buildBootstrapPayload(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ): InventoryBootstrapPayload? = database.withTransaction {
        val stored = database.inventoryDao().listBalancesForBusiness(localBusinessId.value)
        if (stored.isEmpty() || stored.size > MAX_BOOTSTRAP_BALANCES) {
            return@withTransaction null
        }
        val locations = linkedMapOf<String, Map<String, String>>()
        val remoteKeys = hashSetOf<RemoteInventoryKey>()
        val balances = buildList {
            for (balance in stored) {
                val quantity = balance.quantityOnHand.validNonNegativeDecimalOrNull()
                    ?: return@withTransaction null
                val average = balance.averageUnitCost.validNonNegativeDecimalOrNull()
                    ?: return@withTransaction null
                val location = database.inventoryLocationDao().findById(balance.locationId)
                    ?.takeIf { it.businessId == localBusinessId.value }
                    ?: return@withTransaction null
                val link = database.catalogSyncLinkDao().findByLocal(
                    localBusinessId = localBusinessId.value,
                    entityType = PRODUCT,
                    localEntityId = balance.productId,
                )
                if (link != null && link.cloudBusinessId != cloudBusinessId.value) {
                    return@withTransaction null
                }
                val remoteProductId = link?.remoteEntityId ?: balance.productId
                val key = RemoteInventoryKey(
                    productId = remoteProductId,
                    canonicalLocation = canonicalLocationName(location.name),
                )
                if (!remoteKeys.add(key)) return@withTransaction null
                locations.putIfAbsent(
                    location.locationId,
                    linkedMapOf(
                        "sourceLocationId" to location.locationId,
                        "locationName" to location.name,
                    ),
                )
                add(
                    linkedMapOf(
                        "productId" to remoteProductId,
                        "locationName" to location.name,
                        "quantityOnHand" to quantity,
                        "averageUnitCost" to average,
                        "currency" to balance.currencyCode,
                    ),
                )
            }
        }
        val key = "inventory-bootstrap:v1:${cloudBusinessId.value}"
        InventoryBootstrapPayload(
            idempotencyKey = key,
            locations = locations.values.toList(),
            balances = balances,
            remoteKeys = remoteKeys,
        )
    }

    private fun mapPostFailure(
        failure: Exception,
        localByRemote: Map<RemoteInventoryKey, LocalInventoryKey>,
    ): RemoteSalePostResult {
        if (failure is FirebaseFunctionsException) {
            val message = failure.message.orEmpty()
            if (INVENTORY_MIGRATION_CODES.any(message::contains)) {
                return RemoteSalePostResult.InventoryMigrationRequired
            }
            if (
                failure.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
                message.contains(INSUFFICIENT_STOCK)
            ) {
                val details = failure.details as? Map<*, *>
                    ?: return RemoteSalePostResult.Rejected
                val remoteProduct = details["productId"] as? String
                    ?: return RemoteSalePostResult.Rejected
                val locationName = details["locationName"] as? String
                    ?: return RemoteSalePostResult.Rejected
                val requestedText = details["requested"] as? String
                    ?: return RemoteSalePostResult.Rejected
                val availableText = details["available"] as? String
                    ?: return RemoteSalePostResult.Rejected
                val local = localByRemote[
                    RemoteInventoryKey(remoteProduct, canonicalLocationName(locationName))
                ] ?: return RemoteSalePostResult.Rejected
                val requested = runCatching { Quantity.of(requestedText) }.getOrNull()
                    ?: return RemoteSalePostResult.Rejected
                val available = availableText.validNonNegativeDecimalOrNull()
                    ?: return RemoteSalePostResult.Rejected
                return RemoteSalePostResult.InsufficientStock(
                    productId = local.productId,
                    locationId = local.locationId,
                    requested = requested,
                    available = available,
                )
            }
            if (
                failure.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                failure.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
                failure.code == FirebaseFunctionsException.Code.INTERNAL ||
                failure.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ||
                failure.code == FirebaseFunctionsException.Code.UNAUTHENTICATED
            ) {
                return RemoteSalePostResult.OnlineRequired
            }
            return RemoteSalePostResult.Rejected
        }
        return if (failure is FirebaseNetworkException || failure is IOException) {
            RemoteSalePostResult.OnlineRequired
        } else {
            RemoteSalePostResult.Rejected
        }
    }

    private companion object {
        const val POST_SALE_CALLABLE = "postSale"
        const val BOOTSTRAP_INVENTORY_CALLABLE = "bootstrapInventoryBalances"
        const val LIST_INVENTORY_CHANGES_CALLABLE = "listSalesInventoryChanges"
        const val OPERATION_TYPE = "SYNC_SALE"
        const val PRODUCT = "PRODUCT"
        const val MAX_BOOTSTRAP_BALANCES = 200
        const val INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK"
        val INVENTORY_MIGRATION_CODES = setOf(
            "INVENTORY_BALANCE_MIGRATION_REQUIRED",
            "INVENTORY_WIRE_MIGRATION_REQUIRED",
        )
    }
}

private data class RemoteInventoryKey(
    val productId: String,
    val canonicalLocation: String,
)

private data class LocalInventoryKey(
    val productId: ProductId,
    val locationId: LocationId,
)

private data class OutboundSale(
    val idempotencyKey: String,
    val payloadVersion: Int,
    val cloudBusinessId: BusinessId,
    val document: Map<String, Any?>,
    val localByRemote: Map<RemoteInventoryKey, LocalInventoryKey>,
)

private data class InventoryBootstrapPayload(
    val idempotencyKey: String,
    val locations: List<Map<String, String>>,
    val balances: List<Map<String, String>>,
    val remoteKeys: Set<RemoteInventoryKey>,
)

private fun String.validNonNegativeDecimalOrNull(): String? {
    val value = try {
        BigDecimal(this)
    } catch (_: NumberFormatException) {
        return null
    }
    if (value.signum() < 0 || !InventoryCostingDecimalPolicy.supportsPersisted(value)) return null
    val stripped = value.stripTrailingZeros()
    return if (stripped.scale() < 0) stripped.setScale(0).toPlainString() else stripped.toPlainString()
}

private fun List<String>.lengthPrefixedSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEach { part ->
        val bytes = part.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
