package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId

/**
 * Sobre mínimo que sale de la cola local hacia el transporte de respaldo. El transporte remoto
 * futuro solo recibe este contenido; la UI y el resto del dominio nunca dependen de su respuesta.
 */
data class BackupEnvelope(
    val operationId: String,
    val businessId: BusinessId,
    /** Tenant Room inmutable; el transporte jamás lo recalcula desde la sesión vigente. */
    val targetCloudBusinessId: BusinessId,
    val purchaseId: PurchaseId?,
    val idempotencyKey: String,
    val operationType: String,
    val payloadVersion: Int,
    val payload: String,
    /** Identidad causal/local; nunca se usa como identidad cloud si [remoteEntityId] existe. */
    val entityType: String = "PURCHASE",
    val entityId: String = purchaseId?.value ?: operationId,
    val entityVersion: Long = 1L,
    /** Identidad canónica cloud resuelta de forma durable para catálogo multi-dispositivo. */
    val remoteEntityId: String? = null,
) {
    init {
        require(operationId.isNotBlank()) { "operationId requerido" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey requerido" }
        require(operationType.isNotBlank()) { "operationType requerido" }
        require(payloadVersion >= 1) { "payloadVersion debe ser >= 1: $payloadVersion" }
        require(payload.isNotEmpty()) { "payload requerido" }
        require(entityType in setOf("PURCHASE", "PRODUCT", "SUPPLIER", "DOCUMENT"))
        require(entityId.isNotBlank())
        require(entityVersion >= 1L)
        require(remoteEntityId == null || CANONICAL_REMOTE_ENTITY_ID.matches(remoteEntityId))
        require(remoteEntityId == null || entityType == "PRODUCT" || entityType == "SUPPLIER")
    }
}

/**
 * Resultado cerrado de un intento de envío. `reason` es un código sanitizado (ver
 * `ProcessPurchaseBackupOutboxUseCase`): nunca contiene cuerpos de respuesta, rutas ni
 * contenido del comprobante.
 */
sealed interface BackupTransportResult {
    /**
     * Acuse remoto. Solo confirma el respaldo si `echoedIdempotencyKey` coincide exactamente
     * con la clave enviada; un acuse de otra operación no valida esta.
     */
    data class Acknowledged(
        val receiptId: String,
        val echoedIdempotencyKey: String,
    ) : BackupTransportResult {
        init {
            require(receiptId.isNotBlank()) { "receiptId requerido" }
            require(echoedIdempotencyKey.isNotBlank()) { "echoedIdempotencyKey requerido" }
        }
    }

    /** Fallo recuperable (5xx, timeout, corte a mitad): la operación reintenta sola. */
    data class TransientFailure(val reason: String) : BackupTransportResult

    /** Fallo definitivo (4xx no conciliable): queda en ERROR y exige acción manual. */
    data class PermanentFailure(val reason: String) : BackupTransportResult

    /**
     * El destino conoce un estado incompatible: requiere conciliación explícita.
     * [remotePurchaseId]/[remoteReceiptId] identifican el registro remoto en conflicto
     * cuando el servidor los informa (solo IDs; nunca contenido del comprobante).
     */
    data class Conflict(
        val reason: String,
        val remotePurchaseId: String? = null,
        val remoteReceiptId: String? = null,
        val remoteEntity: RemoteConflictMetadata? = null,
    ) : BackupTransportResult

    /** Consentimiento retirado antes del IO remoto; el candidato queda terminal sin subir. */
    data object Suppressed : BackupTransportResult

    /** No hay transporte configurado: la operación no consumió intento alguno. */
    data object Unavailable : BackupTransportResult
}

/** Comparación cerrada de un conflicto CAS; nunca incluye UID, correo ni texto de error. */
data class RemoteConflictMetadata(
    val entityId: String,
    val version: Long,
    val snapshotPayload: String,
    val syncedAtMillis: Long?,
    val origin: String = "CLOUD",
    /** Negocio cloud real usado en el request; no es el UUID local del negocio. */
    val cloudBusinessId: String? = null,
) {
    init {
        require(CANONICAL_REMOTE_ENTITY_ID.matches(entityId))
        require(version >= 1L)
        require(snapshotPayload.isNotEmpty() && snapshotPayload.length <= 64_000)
        require(syncedAtMillis == null || syncedAtMillis >= 0L)
        require(origin == "CLOUD")
        require(cloudBusinessId == null || CANONICAL_REMOTE_ENTITY_ID.matches(cloudBusinessId))
    }
}

private val CANONICAL_REMOTE_ENTITY_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/**
 * Puerto del transporte de respaldo. El proyecto no configura backend: la implementación
 * productiva declara `configured = false` y la cola permanece honestamente en PENDING_SYNC.
 */
interface PurchaseBackupTransport {
    /** Debe ser estable durante la vida del proceso: el procesador lo consulta una vez por pasada. */
    val configured: Boolean

    suspend fun send(envelope: BackupEnvelope): BackupTransportResult
}
