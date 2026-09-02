package com.facturastock.app.data.sync

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.repository.CatalogSnapshotCodec
import com.facturastock.app.data.repository.DocumentBackupPayloadCodec
import com.facturastock.app.data.spark.SparkDirectBackupWriter
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.DocumentUploadPreparationException
import com.facturastock.app.domain.repository.DocumentUploadSource
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.PurchaseReadRepository
import com.facturastock.app.domain.repository.RemoteConflictMetadata
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Transporte Firebase del respaldo de compras (flavor cloud). Envía únicamente el documento
 * JSON de la compra al callable `postPurchase`: jamás imágenes, rutas de archivo ni contenido
 * OCR. El acuse solo vale si el destino devuelve la misma `idempotencyKey`; la validación
 * final la hace el procesador de la outbox.
 *
 * Solo sale con sesión [AccountSession.Active] (correo verificado) y con el enlace local ↔
 * nube resuelto: el destino es siempre el negocio de la nube enlazado, nunca otro. Un
 * `UNAUTHENTICATED` del backend dispara una única recuperación de sesión con reintento.
 */
@Singleton
class FirebasePurchaseBackupTransport @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val membershipRepository: BusinessMembershipRepository,
    private val purchaseReads: PurchaseReadRepository,
    private val database: FacturaStockDatabase,
    private val appConfiguration: AppConfigurationRepository,
    private val documentUploadPreparer: DocumentUploadPreparer,
    private val sparkDirectWriter: SparkDirectBackupWriter,
    private val dispatchers: DispatcherProvider,
) : PurchaseBackupTransport {

    override val configured: Boolean
        get() = runtime.config != null

    override suspend fun send(envelope: BackupEnvelope): BackupTransportResult =
        withContext(dispatchers.io) {
            // Sin sesión plena el respaldo no está activado: la operación no consumió intento.
            val session = accountRepository.observeSession().first()
            backupTenantGuard(session, envelope)?.let { return@withContext it }
            val expectedUid = (session as AccountSession.Active).uid
            val cloudBusinessId = envelope.targetCloudBusinessId
            try {
                // Storage exige Blaze. En Spark las fotos nunca salen del dispositivo; una
                // purga solo limpia cualquier candidato temporal que hubiera quedado preparado.
                if (
                    runtime.backendMode == FirebaseBackendMode.SPARK_DIRECT &&
                    envelope.operationType in setOf(SYNC_DOCUMENT_UPLOAD, SYNC_DOCUMENT_PURGE)
                ) {
                    if (envelope.operationType == SYNC_DOCUMENT_PURGE) {
                        documentUploadPreparer.discard(envelope.entityId)
                    }
                    return@withContext BackupTransportResult.Suppressed
                }
                val outbound = when (envelope.operationType) {
                    SYNC_PURCHASE -> OutboundCall(
                        callable = POST_PURCHASE_CALLABLE,
                        document = buildPurchaseDocument(envelope, cloudBusinessId)
                        ?: return@withContext BackupTransportResult.PermanentFailure(
                            "PURCHASE_NOT_FOUND",
                        ),
                    )
                    SYNC_PURCHASE_VOID -> OutboundCall(
                        POST_PURCHASE_CALLABLE,
                        remapPurchaseVoidDocument(envelope, cloudBusinessId)
                            ?: return@withContext BackupTransportResult.PermanentFailure(
                                "MALFORMED_PURCHASE_VOID_PAYLOAD",
                            ),
                    )
                    SYNC_PRODUCT, SYNC_SUPPLIER -> OutboundCall(
                        callable = SYNC_CATALOG_CALLABLE,
                        document = catalogDocument(envelope)
                            ?: return@withContext BackupTransportResult.PermanentFailure(
                                "MALFORMED_CATALOG_PAYLOAD",
                            ),
                    )
                    SYNC_DOCUMENT_UPLOAD -> try {
                        documentUploadCall(envelope)
                            ?: return@withContext BackupTransportResult.PermanentFailure(
                                "MALFORMED_DOCUMENT_PAYLOAD",
                            )
                    } catch (failure: DocumentUploadPreparationException) {
                        return@withContext BackupTransportResult.PermanentFailure(
                            failure.error.wireCode,
                        )
                    }
                    SYNC_DOCUMENT_PURGE -> documentPurgeCall(envelope)
                        ?: return@withContext BackupTransportResult.PermanentFailure(
                            "MALFORMED_DOCUMENT_PAYLOAD",
                        )
                    else -> return@withContext BackupTransportResult.PermanentFailure(
                        "UNSUPPORTED_OPERATION_TYPE",
                    )
                }.normalized(envelope)
                if (outbound.suppressed) return@withContext BackupTransportResult.Suppressed
                if (runtime.backendMode == FirebaseBackendMode.SPARK_DIRECT) {
                    val liveSession = accountRepository.observeSession().first()
                    backupTenantGuard(liveSession, envelope, expectedUid)
                        ?.let { return@withContext it }
                    return@withContext sparkDirectWriter.send(
                        envelope = envelope,
                        document = outbound.document,
                        expectedUid = expectedUid,
                    )
                }
                val functions = runtime.functions()
                    ?: return@withContext BackupTransportResult.Unavailable
                runtime.prepareBusiness(functions, cloudBusinessId.value)
                val result = sendWithSessionRecovery(
                    functions = functions,
                    envelope = envelope,
                    cloudBusinessId = cloudBusinessId,
                    outbound = outbound,
                    expectedUid = expectedUid,
                )
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                FunctionsErrorMapper.fromException(failure)
            }
        }

    /**
     * Llamada a `postPurchase` con una única recuperación de sesión: si el backend responde
     * `UNAUTHENTICATED` se renueva el token y se reintenta una vez; si no se recupera, la
     * operación queda transitoria con código cerrado hasta el reingreso.
     */
    private suspend fun sendWithSessionRecovery(
        functions: FirebaseFunctions,
        envelope: BackupEnvelope,
        cloudBusinessId: BusinessId,
        outbound: OutboundCall,
        expectedUid: String,
    ): BackupTransportResult = executeWithFirebaseSessionRecovery(
        accountRepository = accountRepository,
        isUnauthenticated = { failure ->
            failure is FirebaseFunctionsException &&
                failure.code == FirebaseFunctionsException.Code.UNAUTHENTICATED
        },
        attempt = attempt@{
                val liveSession = accountRepository.observeSession().first()
                backupTenantGuard(liveSession, envelope, expectedUid)
                    ?.let { return@attempt it }
                if (outbound.requiresDocumentConsent && !documentConsentIsActive()) {
                    documentUploadPreparer.discard(envelope.entityId)
                    return@attempt BackupTransportResult.Suppressed
                }
                val result = functions.getHttpsCallable(outbound.callable)
                    .call(
                        mapOf(
                            "businessId" to cloudBusinessId.value,
                            // El backend compara esta intención con el JWT que finalmente
                            // recibe. Así un cambio A → B entre la guarda y el callable no
                            // atribuye ni ejecuta la escritura con la cuenta nueva.
                            "expectedUid" to expectedUid,
                            "idempotencyKey" to requireNotNull(outbound.wireIdempotencyKey),
                            "operationType" to requireNotNull(outbound.wireOperationType),
                            "payloadVersion" to requireNotNull(outbound.wirePayloadVersion),
                            "document" to outbound.document,
                        ),
                    )
                    .await()
                val data = result.data as? Map<*, *>
                val receiptId = data?.get("receiptId") as? String
                val echoed = data?.get("idempotencyKey") as? String
                if (receiptId.isNullOrBlank() ||
                    echoed != outbound.wireIdempotencyKey
                ) {
                    BackupTransportResult.PermanentFailure("MALFORMED_ACK")
                } else {
                    // El procesador confirma la identidad durable local; el transporte ya
                    // comprobó por separado la clave real del wire (derivada del JPEG).
                    BackupTransportResult.Acknowledged(receiptId, envelope.idempotencyKey)
                }
        },
        mapFailure = { failure ->
            // Una denegacion pertenece solo al tenant de este envelope. Declararla como
            // Unavailable detendria todo el lote y permitiria que X bloqueara las purgas
            // autorizadas de Y. El mapper la deja FAILED y visible para reintento manual.
            conflictFromBackendDetails(
                failure = failure,
                operationType = envelope.operationType,
                cloudBusinessId = cloudBusinessId,
            ) ?: FunctionsErrorMapper.fromException(failure)
        },
    )

    /**
     * Un conflicto de `postPurchase` puede informar qué registro remoto chocó: el backend fija
     * `details = {existingPurchaseId, receiptId}` en el HttpsError. Solo entonces el resultado
     * lleva los IDs (nunca contenido); sin ellos se delega al mapeo por código de siempre.
     */
    private fun conflictFromBackendDetails(
        failure: Exception,
        operationType: String,
        cloudBusinessId: BusinessId,
    ): BackupTransportResult.Conflict? {
        if (failure !is FirebaseFunctionsException) return null
        val code = failure.code.name
        if (code != ALREADY_EXISTS && code != ABORTED && code != FAILED_PRECONDITION) return null
        val details = failure.details as? Map<*, *> ?: return null
        if (operationType == SYNC_PRODUCT || operationType == SYNC_SUPPLIER) {
            val entityId = (details["remoteEntityId"] as? String)
                ?.takeIf(String::isNotBlank) ?: return null
            val version = details["remoteVersion"].safePositiveLong() ?: return null
            val snapshotPayload = (details["remoteSnapshotPayload"] as? String)
                ?.takeIf(String::isNotEmpty) ?: return null
            val canonicalSnapshot = when (operationType) {
                SYNC_PRODUCT -> CatalogSnapshotCodec.decodeProduct(snapshotPayload)
                SYNC_SUPPLIER -> CatalogSnapshotCodec.decodeSupplier(snapshotPayload)
                else -> null
            }
            if (canonicalSnapshot == null) return null
            val syncedAtMillis = when (val raw = details["remoteSyncedAtMillis"]) {
                null -> null
                else -> raw.safeNonNegativeLong() ?: return null
            }
            val origin = details["remoteOrigin"] as? String ?: return null
            val conflictCode = details["conflictCode"] as? String ?: return null
            if (conflictCode !in CATALOG_CONFLICT_CODES) return null
            val remote = runCatching {
                RemoteConflictMetadata(
                    entityId = entityId,
                    version = version,
                    snapshotPayload = snapshotPayload,
                    syncedAtMillis = syncedAtMillis,
                    origin = origin,
                    cloudBusinessId = cloudBusinessId.value,
                )
            }.getOrNull() ?: return null
            return BackupTransportResult.Conflict(reason = conflictCode, remoteEntity = remote)
        }
        val existingPurchaseId = (details["existingPurchaseId"] as? String)
            ?.takeIf(String::isNotBlank) ?: return null
        val receiptId = (details["receiptId"] as? String)?.takeIf(String::isNotBlank)
        return BackupTransportResult.Conflict(
            reason = code,
            remotePurchaseId = existingPurchaseId,
            remoteReceiptId = receiptId,
        )
    }

    /**
     * Documento `backup/v1` legacy o `backup/v2` actual: proyección exacta del agregado Room
     * con importes en unidades menores y decimales como texto plano. Sin imágenes ni rutas.
     * La lectura es local (el `businessId` del envelope), pero el documento se identifica con
     * el negocio de la nube enlazado: es el único destino al que puede salir.
     */
    private suspend fun buildPurchaseDocument(
        envelope: BackupEnvelope,
        cloudBusinessId: BusinessId,
    ): Map<String, Any?>? {
        val purchaseId = envelope.purchaseId ?: return null
        val detail = purchaseReads.observePurchase(envelope.businessId, purchaseId).first()
            ?: return null
        val remoteProductIds = remoteProductIds(
            localBusinessId = envelope.businessId,
            cloudBusinessId = cloudBusinessId,
            localProductIds = detail.lines.mapTo(linkedSetOf()) { it.productId.value }.apply {
                addAll(detail.movements.map { it.productId.value })
            },
        )
        return FirebasePurchaseDocumentMapper.map(
            detail = detail,
            envelope = envelope,
            cloudBusinessId = cloudBusinessId,
            remoteProductIds = remoteProductIds,
        )
    }

    private suspend fun remapPurchaseVoidDocument(
        envelope: BackupEnvelope,
        cloudBusinessId: BusinessId,
    ): String? = runCatching {
        val localProductIds = FirebasePurchaseVoidDocumentMapper.productIds(envelope.payload)
        FirebasePurchaseVoidDocumentMapper.remap(
            payload = envelope.payload,
            remoteProductIds = remoteProductIds(
                localBusinessId = envelope.businessId,
                cloudBusinessId = cloudBusinessId,
                localProductIds = localProductIds,
            ),
        )
    }.getOrNull()

    private suspend fun remoteProductIds(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        localProductIds: Set<String>,
    ): Map<String, String> = localProductIds.associateWith { localProductId ->
        database.catalogSyncLinkDao().findByLocal(
            localBusinessId = localBusinessId.value,
            entityType = PRODUCT_ENTITY_TYPE,
            localEntityId = localProductId,
        )?.takeIf { link -> link.cloudBusinessId == cloudBusinessId.value }
            ?.remoteEntityId
            ?: localProductId
    }

    private fun catalogDocument(envelope: BackupEnvelope): Map<String, Any?>? {
        if (envelope.purchaseId != null) return null
        val wireEntityId = envelope.remoteEntityId ?: envelope.entityId
        val acceptedPayloadVersions = when (envelope.operationType) {
            SYNC_PRODUCT -> PRODUCT_CATALOG_PAYLOAD_VERSIONS
            SYNC_SUPPLIER -> SUPPLIER_CATALOG_PAYLOAD_VERSIONS
            else -> return null
        }
        if (envelope.payloadVersion !in acceptedPayloadVersions) return null
        val expectedIdempotencyKey = when (envelope.operationType) {
            SYNC_PRODUCT -> "sync-product:v${envelope.payloadVersion}:$wireEntityId:${envelope.entityVersion}"
            SYNC_SUPPLIER -> "sync-supplier:v${envelope.payloadVersion}:$wireEntityId:${envelope.entityVersion}"
            else -> return null
        }
        if (envelope.idempotencyKey != expectedIdempotencyKey) return null
        return runCatching {
            FirebaseCatalogWireMapper.pushDocument(
                operationType = envelope.operationType,
                payload = envelope.payload,
                remoteEntityId = envelope.remoteEntityId,
            )
        }.getOrNull()
    }

    private suspend fun documentUploadCall(envelope: BackupEnvelope): OutboundCall? {
        if (!documentConsentIsActive()) {
            documentUploadPreparer.discard(envelope.entityId)
            return OutboundCall.suppressedDocumentUpload()
        }
        val purchaseId = envelope.purchaseId ?: return null
        val payload = DocumentBackupPayloadCodec.decodeUpload(envelope.payload) ?: return null
        if (envelope.payloadVersion != DocumentBackupPayloadCodec.PAYLOAD_VERSION ||
            envelope.entityType != DOCUMENT_ENTITY_TYPE || envelope.entityVersion != 1L ||
            payload.purchaseId != purchaseId.value || payload.imageId != envelope.entityId ||
            envelope.idempotencyKey != DocumentBackupPayloadCodec.candidateIdempotencyKey(
                purchaseId = payload.purchaseId,
                imageId = payload.imageId,
                sourceSha256 = payload.sha256,
            )
        ) {
            return null
        }
        val prepared = documentUploadPreparer.prepare(
            DocumentUploadSource(
                imageId = payload.imageId,
                sourceSha256 = payload.sha256,
                relativeFilePath = payload.relativeFilePath,
                mimeType = payload.mimeType,
                rotationDegrees = payload.rotationDegrees,
            ),
        ) ?: return null
        val wireKey = "document-upload:v1:${payload.purchaseId}:${payload.imageId}:" +
            prepared.sha256
        val encodedContent = try {
            Base64.getEncoder().encodeToString(prepared.content)
        } finally {
            prepared.content.fill(0)
        }
        return OutboundCall(
            callable = UPLOAD_DOCUMENT_CALLABLE,
            document = mapOf(
                "version" to 1,
                "purchaseId" to payload.purchaseId,
                "imageId" to payload.imageId,
                "sha256" to prepared.sha256,
                "contentBase64" to encodedContent,
            ),
            wireIdempotencyKey = wireKey,
            requiresDocumentConsent = true,
        )
    }

    private suspend fun documentPurgeCall(envelope: BackupEnvelope): OutboundCall? {
        val purchaseId = envelope.purchaseId ?: return null
        val payload = DocumentBackupPayloadCodec.decodePurge(envelope.payload) ?: return null
        if (envelope.payloadVersion != DocumentBackupPayloadCodec.PAYLOAD_VERSION ||
            envelope.entityType != DOCUMENT_ENTITY_TYPE || envelope.entityVersion != 2L ||
            payload.purchaseId != purchaseId.value || payload.imageId != envelope.entityId ||
            envelope.idempotencyKey != DocumentBackupPayloadCodec.purgeIdempotencyKey(
                payload.purchaseId,
                payload.imageId,
            )
        ) {
            return null
        }
        documentUploadPreparer.discard(payload.imageId)
        return OutboundCall(
            callable = PURGE_DOCUMENT_CALLABLE,
            document = mapOf(
                "version" to 1,
                "purchaseId" to payload.purchaseId,
                "imageId" to payload.imageId,
            ),
            wireIdempotencyKey = envelope.idempotencyKey,
        )
    }

    private suspend fun documentConsentIsActive(): Boolean = appConfiguration.current().let {
        it.backupEnabled && it.documentBackupEnabled
    }

    private fun Any?.safePositiveLong(): Long? = safeNonNegativeLong()?.takeIf { it >= 1L }

    private fun Any?.safeNonNegativeLong(): Long? = when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        is Float -> takeIf { isFinite() && it % 1f == 0f }?.toLong()
        is Double -> takeIf { isFinite() && it % 1.0 == 0.0 }?.toLong()
        else -> null
    }?.takeIf { it >= 0L }

    private data class OutboundCall(
        val callable: String,
        val document: Any?,
        val wireIdempotencyKey: String? = null,
        val wireOperationType: String? = null,
        val wirePayloadVersion: Int? = null,
        val requiresDocumentConsent: Boolean = false,
        val suppressed: Boolean = false,
    ) {
        fun normalized(envelope: BackupEnvelope): OutboundCall = copy(
            wireIdempotencyKey = wireIdempotencyKey ?: envelope.idempotencyKey,
            wireOperationType = wireOperationType ?: envelope.operationType,
            wirePayloadVersion = wirePayloadVersion ?: envelope.payloadVersion,
        )

        companion object {
            fun suppressedDocumentUpload(): OutboundCall = OutboundCall(
                callable = "",
                document = null,
                suppressed = true,
            )
        }
    }

    private companion object {
        const val POST_PURCHASE_CALLABLE = "postPurchase"
        const val SYNC_CATALOG_CALLABLE = "syncCatalogEntity"
        const val SYNC_PURCHASE = "SYNC_PURCHASE"
        const val SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
        const val SYNC_PRODUCT = "SYNC_PRODUCT"
        const val SYNC_SUPPLIER = "SYNC_SUPPLIER"
        const val SYNC_DOCUMENT_UPLOAD = "SYNC_DOCUMENT_UPLOAD"
        const val SYNC_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
        const val PRODUCT_ENTITY_TYPE = "PRODUCT"
        const val DOCUMENT_ENTITY_TYPE = "DOCUMENT"
        const val UPLOAD_DOCUMENT_CALLABLE = "uploadPurchaseDocument"
        const val PURGE_DOCUMENT_CALLABLE = "purgePurchaseDocument"
        val PRODUCT_CATALOG_PAYLOAD_VERSIONS = 1..2
        val SUPPLIER_CATALOG_PAYLOAD_VERSIONS = 1..1
        const val ALREADY_EXISTS = "ALREADY_EXISTS"
        const val ABORTED = "ABORTED"
        const val FAILED_PRECONDITION = "FAILED_PRECONDITION"
        val CATALOG_CONFLICT_CODES = setOf(
            "CATALOG_VERSION_CONFLICT",
            "CATALOG_SEMANTIC_CONFLICT",
            "CATALOG_PRODUCT_SCHEMA_DOWNGRADE",
        )
    }
}

/**
 * Contrato aislado y directamente comprobable de recuperación de sesión del transporte.
 * Solo [FirebaseFunctionsException.Code.UNAUTHENTICATED] consume el único intento de renovación;
 * cualquier segundo rechazo exige reingreso y nunca vuelve a ejecutar [AccountRepository.recoverSession].
 */
internal suspend fun executeWithFirebaseSessionRecovery(
    accountRepository: AccountRepository,
    isUnauthenticated: (Exception) -> Boolean,
    attempt: suspend () -> BackupTransportResult,
    mapFailure: (Exception) -> BackupTransportResult,
): BackupTransportResult {
    var recoveryAttempted = false
    while (true) {
        try {
            return attempt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val unauthenticated = isUnauthenticated(failure)
            if (unauthenticated && !recoveryAttempted) {
                recoveryAttempted = true
                when (accountRepository.recoverSession()) {
                    is DomainResult.Success -> continue
                    is DomainResult.Failure ->
                        return BackupTransportResult.TransientFailure(FIREBASE_SESSION_EXPIRED)
                }
            }
            return if (unauthenticated) {
                BackupTransportResult.TransientFailure(FIREBASE_SESSION_EXPIRED)
            } else {
                mapFailure(failure)
            }
        }
    }
}

private const val FIREBASE_SESSION_EXPIRED = "SESSION_EXPIRED"

/**
 * Guardia pura compartida por las dos lecturas de sesión del transporte. Un logout o cambio
 * normal de negocio/enlace durante una pasada es indisponibilidad temporal: el procesador
 * libera el claim y nunca convierte esa carrera en FAILED permanente.
 */
internal fun backupTenantGuard(
    session: AccountSession,
    envelope: BackupEnvelope,
    expectedUid: String? = null,
): BackupTransportResult? {
    val active = session as? AccountSession.Active
        ?: return BackupTransportResult.Unavailable
    if (expectedUid != null && active.uid != expectedUid) {
        return BackupTransportResult.Unavailable
    }
    // Una purga ya lleva un tenant Room inmutable y el backend vuelve a autorizar el UID contra
    // ese negocio. No se subordina al link de UI: así puede terminar tras cambiar de negocio,
    // entrar en demo o retirar el link, sin habilitar ningún upload comercial.
    if (envelope.operationType == "SYNC_DOCUMENT_PURGE") return null
    val link = active.link
        ?: return BackupTransportResult.Unavailable
    return if (
        link.localBusinessId == envelope.businessId &&
        link.cloudBusinessId == envelope.targetCloudBusinessId
    ) {
        null
    } else {
        BackupTransportResult.Unavailable
    }
}

/** Sustituye solo la identidad de producto; el hash de impacto local sigue siendo evidencia opaca. */
internal object FirebasePurchaseVoidDocumentMapper {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun productIds(payload: String): Set<String> = impacts(parse(payload))
        .mapTo(linkedSetOf()) { impact -> impact.productId() }

    fun remap(payload: String, remoteProductIds: Map<String, String>): String {
        val root = parse(payload)
        val remappedImpacts = JsonArray(
            impacts(root).map { impact ->
                val localProductId = impact.productId()
                JsonObject(
                    impact.toMutableMap().apply {
                        this["productId"] = JsonPrimitive(
                            remoteProductIds[localProductId] ?: localProductId,
                        )
                    },
                )
            },
        )
        return JsonObject(
            root.toMutableMap().apply { this["impacts"] = remappedImpacts },
        ).toString()
    }

    private fun parse(payload: String): JsonObject {
        require(payload.isNotEmpty() && payload.length <= MAX_PAYLOAD_CHARS)
        return json.parseToJsonElement(payload).jsonObject
    }

    private fun impacts(root: JsonObject): List<JsonObject> =
        (root["impacts"] as? JsonArray)?.map { it.jsonObject }
            ?: throw IllegalArgumentException("impacts")

    private fun JsonObject.productId(): String {
        val value = getValue("productId").jsonPrimitive
        require(value.isString && CANONICAL_UUID.matches(value.content))
        return value.content
    }

    private const val MAX_PAYLOAD_CHARS = 1_000_000
    private val CANONICAL_UUID =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
}

/** Mapper puro y acotado del agregado local al wire document; visible para regresiones cloud JVM. */
internal object FirebasePurchaseDocumentMapper {
    fun map(
        detail: PurchaseReadDetail,
        envelope: BackupEnvelope,
        cloudBusinessId: BusinessId,
        remoteProductIds: Map<String, String> = emptyMap(),
    ): Map<String, Any?> = with(detail) {
        val documentVersion = when (envelope.payloadVersion) {
            LEGACY_PAYLOAD_VERSION -> LEGACY_DOCUMENT_VERSION
            TAX_AUDIT_PAYLOAD_VERSION -> TAX_AUDIT_DOCUMENT_VERSION
            CURRENT_PAYLOAD_VERSION -> CURRENT_DOCUMENT_VERSION
            else -> error("Versión SYNC_PURCHASE no soportada: ${envelope.payloadVersion}")
        }
        // El agregado de lectura también contiene compensaciones posteriores. El alta remota
        // debe ser exactamente el hecho POSTED original para que un ACK perdido pueda repetirse
        // byte a byte incluso si la compra ya fue anulada localmente.
        val postingMovements = movements.filter { it.type == StockMovementType.PURCHASE }
        val postingAuditEvents = auditEvents.filter {
            it.eventType in POSTING_AUDIT_TYPES
        }
        require(postingMovements.size == lines.size) {
            "SYNC_PURCHASE requiere un movimiento PURCHASE por línea"
        }
        require(postingAuditEvents.count { it.eventType == AuditEventType.PURCHASE_POSTED } == 1) {
            "SYNC_PURCHASE requiere exactamente un evento PURCHASE_POSTED"
        }
        val duplicateOverrideAudit = postingAuditEvents.filter {
            it.eventType == AuditEventType.PURCHASE_DUPLICATE_OVERRIDE
        }
        if (documentVersion >= TAX_AUDIT_DOCUMENT_VERSION) {
            require(duplicateOverrideAudit.size == if (duplicateOverride == null) 0 else 1) {
                "SYNC_PURCHASE v2 exige correspondencia exacta entre override y auditoría"
            }
        }
        val mappedLines = lines.map { line ->
            val legacy = linkedMapOf<String, Any?>(
                "purchaseLineId" to line.purchaseLineId,
                "position" to line.position,
                "productId" to (remoteProductIds[line.productId.value] ?: line.productId.value),
                "productName" to line.productName,
                "unitCode" to line.unitCode,
                "description" to line.description,
                "quantity" to line.quantity.toPlainString(),
                "readUnitCost" to line.readUnitCost.amount.toPlainString(),
                "taxMinorUnits" to line.tax.minorUnits,
                "totalMinorUnits" to line.total.minorUnits,
                "appliedUnitCost" to line.appliedUnitCost?.amount?.toPlainString(),
                "inventoryQuantity" to line.inventoryQuantity?.toPlainString(),
                "discount" to line.discount?.toPlainString(),
            )
            if (documentVersion >= TAX_AUDIT_DOCUMENT_VERSION) {
                val treatment = requireNotNull(line.taxTreatment) {
                    "SYNC_PURCHASE v2 no admite una decisión tributaria histórica ausente"
                }
                val evidence = requireNotNull(line.taxEvidence) {
                    "SYNC_PURCHASE v2 no admite evidencia tributaria ausente"
                }
                require(line.productProvenance != PurchaseProductProvenance.UNKNOWN_LEGACY) {
                    "SYNC_PURCHASE v2 no admite procedencia UNKNOWN_LEGACY"
                }
                legacy["taxTreatment"] = treatment.name
                legacy["taxEvidence"] = mapOf(
                    "type" to evidence.type.name,
                    "value" to evidence.value?.toPlainString(),
                )
                legacy["productProvenance"] = line.productProvenance.name
            }
            legacy
        }
        val mapped = linkedMapOf<String, Any?>(
            "version" to documentVersion,
            "purchaseId" to summary.purchaseId.value,
            "businessId" to cloudBusinessId.value,
            // La outbox representa dos hechos ordenados. Aunque Room ya muestre VOIDED al
            // reconstruir un alta pendiente, SYNC_PURCHASE publica primero el hecho POSTED;
            // SYNC_PURCHASE_VOID comunica después la compensación con su propio payload.
            "status" to PurchaseStatus.POSTED.name,
            "documentType" to summary.documentType.name,
            "documentSeries" to summary.documentSeries,
            "documentNumber" to summary.documentNumber,
            "issueDate" to summary.issueDate.toString(),
            "currency" to summary.currency.value,
            "supplierRuc" to summary.supplierRuc,
            "supplierLegalName" to summary.supplierLegalName,
            "subtotalMinorUnits" to subtotal.minorUnits,
            "taxMinorUnits" to tax.minorUnits,
            "otherChargesMinorUnits" to otherCharges.minorUnits,
            "totalMinorUnits" to summary.total.minorUnits,
            "adjustmentMinorUnits" to adjustment?.minorUnits,
            "adjustmentReason" to adjustmentReason,
            "preparedLogicalHash" to preparedLogicalHash,
            "postedAt" to summary.postedAt?.toEpochMilli(),
            "idempotencyKey" to envelope.idempotencyKey,
            "lines" to mappedLines,
            "movements" to postingMovements.map { movement ->
                val line = lines.single { it.purchaseLineId == movement.purchaseLineId }
                linkedMapOf<String, Any?>(
                    "movementId" to movement.movementId,
                    "purchaseLineId" to movement.purchaseLineId,
                    "productId" to (
                        remoteProductIds[movement.productId.value] ?: movement.productId.value
                    ),
                    "locationId" to movement.locationId.value,
                    "type" to movement.type.name,
                    "quantityDelta" to movement.quantityDelta.toPlainString(),
                    "unitCost" to if (documentVersion == CURRENT_DOCUMENT_VERSION) {
                        requireNotNull(line.appliedUnitCost) {
                            "SYNC_PURCHASE v3 exige appliedUnitCost por línea"
                        }.amount.toPlainString()
                    } else {
                        movement.unitCost?.amount?.toPlainString()
                    },
                    "occurredAt" to movement.occurredAt.toEpochMilli(),
                ).apply {
                    if (documentVersion == CURRENT_DOCUMENT_VERSION) {
                        this["locationName"] = movement.locationName
                        this["appliedCostTotal"] = requireNotNull(line.appliedCostTotal) {
                            "SYNC_PURCHASE v3 exige appliedCostTotal por línea"
                        }.toPlainString()
                    }
                }
            },
            "auditEventIds" to postingAuditEvents.map { it.auditEventId },
        )
        if (documentVersion >= TAX_AUDIT_DOCUMENT_VERSION) {
            mapped["duplicateOverride"] = duplicateOverride?.let { override ->
                mapOf(
                    "existingPurchaseId" to override.existingPurchaseId.value,
                    "sourceDraftId" to summary.sourceDraftId.value,
                    "auditEventId" to duplicateOverrideAudit.single().auditEventId,
                    // El backend valida el texto de forma transitoria y lo descarta por completo.
                    "reason" to override.reason,
                )
            }
        }
        mapped
    }

    private const val LEGACY_PAYLOAD_VERSION = 2
    private const val TAX_AUDIT_PAYLOAD_VERSION = 3
    private const val CURRENT_PAYLOAD_VERSION = 4
    private const val LEGACY_DOCUMENT_VERSION = 1
    private const val TAX_AUDIT_DOCUMENT_VERSION = 2
    private const val CURRENT_DOCUMENT_VERSION = 3
    private val POSTING_AUDIT_TYPES = setOf(
        AuditEventType.PURCHASE_POSTED,
        AuditEventType.PURCHASE_DUPLICATE_OVERRIDE,
    )
}
