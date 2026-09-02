package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.BackupBackoffPolicy
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAgeBucket
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.DisabledDocumentUploadPreparer
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.PendingBackupOperation
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.RemoteConflictMetadata
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Resultado cerrado de una pasada de drenado de la outbox de respaldo. */
sealed interface OutboxPassResult {
    /** No hay transporte configurado: la cola quedó intacta y nada consumió intentos. */
    data object TransportUnavailable : OutboxPassResult

    /**
     * La pasada se ejecutó. [processed] son claims ganados; [reachedBatchLimit] pide otra
     * pasada inmediata; [nextWakeupAt] es el próximo vencimiento (reintento diferido o lease)
     * que conviene programar, o `null` si la cola no necesita despertar.
     * [targetTemporarilyUnavailable] indica que la sesión/enlace fijado para esta pasada dejó
     * de estar vigente: ninguna fila pendiente debe convertirse en FAILED por ese cambio.
     */
    data class Drained(
        val processed: Int,
        val completed: Int,
        val scheduledRetries: Int,
        val permanentlyFailed: Int,
        val conflicts: Int,
        val released: Int,
        val suppressed: Int,
        val reachedBatchLimit: Boolean,
        val nextWakeupAt: Instant?,
        val targetTemporarilyUnavailable: Boolean = false,
    ) : OutboxPassResult
}

/**
 * Drena la outbox de respaldo contra el transporte configurado. Reglas duras:
 *
 * - SYNCED solo tras un acuse cuyo `echoedIdempotencyKey` coincide con la clave enviada; un
 *   acuse de otra operación es un fallo permanente de integridad, nunca una confirmación.
 * - Un fallo transitorio vuelve a PENDING con backoff exponencial durable; al agotar
 *   [BackupBackoffPolicy.MAX_ATTEMPTS] queda FAILED y solo vuelve por reintento manual.
 * - Permanente → FAILED; conflicto → CONFLICT. Ambos exigen acción humana.
 * - La concurrencia se resuelve con el CAS de claim y el token del intento: dos workers nunca
 *   envían ni cierran la misma operación dos veces.
 * - `SYNC_PURCHASE_VOID` no se reclama ni se envía hasta que el alta de esa misma compra está
 *   COMPLETED en la outbox durable. Un fallo o backoff del alta no bloquea compras distintas.
 * - `lastError` persiste solo códigos cerrados `[A-Z0-9_]`; razones arbitrarias del transporte
 *   se sustituyen por el código genérico de su clase.
 */
class ProcessPurchaseBackupOutboxUseCase(
    private val outbox: PurchaseBackupOutboxRepository,
    private val transport: PurchaseBackupTransport,
    private val clock: AppClock,
    private val uuidGenerator: UuidGenerator,
    private val observability: ProductionObservability = DisabledProductionObservability,
    private val documentUploadPreparer: DocumentUploadPreparer =
        DisabledDocumentUploadPreparer,
) {
    suspend operator fun invoke(
        batchLimit: Int = DEFAULT_BATCH_LIMIT,
        /** Tenant fijado por Room. El worker productivo siempre lo proporciona. */
        targetCloudBusinessId: BusinessId? = null,
        /** Con master sync apagado solo permite cumplir tombstones de privacidad durables. */
        onlyDocumentPurges: Boolean = false,
        /** Compuerta fresca comprobada entre operaciones; `false` deja el resto sin reclamar. */
        processingAllowed: suspend () -> Boolean = { true },
        /** Revalida que el tenant fijado siga siendo la sesión activa antes de cada envío. */
        targetAvailable: suspend () -> Boolean = { true },
        /** Tenants confirmados para el UID del run cuando privacy drena varios negocios. */
        authorizedDocumentPurgeTargets: Set<BusinessId>? = null,
    ): OutboxPassResult {
        require(batchLimit >= 1) { "batchLimit debe ser >= 1: $batchLimit" }
        if (!transport.configured) return OutboxPassResult.TransportUnavailable

        outbox.recoverExpiredClaims(clock.now())
        val ready = listReadyForTarget(
            now = clock.now(),
            limit = batchLimit,
            onlyDocumentPurges = onlyDocumentPurges,
            targetCloudBusinessId = targetCloudBusinessId,
            authorizedDocumentPurgeTargets = authorizedDocumentPurgeTargets,
        )

        var completed = 0
        var scheduledRetries = 0
        var permanentlyFailed = 0
        var conflicts = 0
        var released = 0
        var suppressed = 0
        var haltedByUnavailable = false
        var haltedByPolicy = false

        for (pending in ready) {
            currentCoroutineContext().ensureActive()
            if (!processingAllowed()) {
                // No se puede des-enviar una solicitud ya en vuelo, pero sí impedir que la
                // siguiente operación sea reclamada cuando el usuario desactiva el respaldo.
                haltedByPolicy = true
                break
            }
            if (!targetAvailable()) {
                // Cambiar de sesión o negocio es una indisponibilidad normal y temporal. La
                // fila aún no fue reclamada, por lo que queda PENDING sin consumir intentos.
                haltedByUnavailable = true
                break
            }
            if (!hasCompletedPurchasePostIfRequired(pending)) {
                // No se reclama: el intento de la anulación permanece intacto hasta que el alta
                // alcance COMPLETED. La selección Room tampoco deja que ocupe sitio en el lote;
                // esta segunda barrera protege el contrato frente a otros adaptadores y carreras.
                continue
            }
            val claimToken = uuidGenerator.newUuid().toString()
            val claimedAt = clock.now()
            val claimed = outbox.claim(
                operationId = pending.envelope.operationId,
                targetCloudBusinessId = pending.envelope.targetCloudBusinessId,
                claimToken = claimToken,
                claimedAt = claimedAt,
                leaseUntil = claimedAt.plusMillis(BackupBackoffPolicy.CLAIM_LEASE_MILLIS),
            )
            if (!claimed) continue // Otro worker ganó el CAS; su token cierra ese intento.

            // Cierra la carrera entre la comprobación anterior y el CAS de Room. Si el usuario
            // cambió de sesión/enlace en ese intervalo, se libera el claim antes de cualquier
            // IO remoto y el intento vuelve exactamente a PENDING.
            val targetAvailableAfterClaim = try {
                targetAvailable()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // La lectura fresca también puede fallar por almacenamiento. El worker
                // reintentará, pero esta fila no debe quedar PROCESSING hasta vencer el lease.
                outbox.release(pending.envelope.operationId, claimToken, clock.now())
                throw failure
            }
            if (!targetAvailableAfterClaim) {
                if (outbox.release(pending.envelope.operationId, claimToken, clock.now())) {
                    released++
                    recordOperation(
                        pending,
                        OperationalOutcome.SKIPPED,
                        OperationalErrorCode.TRANSPORT_UNAVAILABLE,
                    )
                }
                haltedByUnavailable = true
                break
            }

            val attempt = pending.attemptCount + 1
            val result = sendSafely(pending)
            // Un SDK/fake defectuoso puede marcar el Job como cancelado y aun así devolver un
            // valor. Nunca se confirma ni se muta la outbox después de esa cancelación.
            currentCoroutineContext().ensureActive()
            val isValidAcknowledgement = result is BackupTransportResult.Acknowledged &&
                result.echoedIdempotencyKey == pending.envelope.idempotencyKey
            val targetAvailableAfterSend = if (isValidAcknowledgement) {
                true
            } else {
                try {
                    targetAvailable()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // El wire es idempotente. Ante una lectura local fallida se libera antes de
                    // pedir reintento, en vez de inmovilizar la fila hasta vencer todo el lease.
                    outbox.release(pending.envelope.operationId, claimToken, clock.now())
                    throw failure
                }
            }
            if (!targetAvailableAfterSend) {
                // La llamada pudo quedar en la microventana guardia→SDK mientras el usuario
                // cambiaba de cuenta. Un 4xx producido por esa carrera no vuelve FAILED a la
                // operación del tenant anterior: se libera y se reevalúa cuando L→X vuelva.
                if (outbox.release(pending.envelope.operationId, claimToken, clock.now())) {
                    released++
                    recordOperation(
                        pending,
                        OperationalOutcome.SKIPPED,
                        OperationalErrorCode.TRANSPORT_UNAVAILABLE,
                    )
                }
                haltedByUnavailable = true
                break
            }
            when (result) {
                is BackupTransportResult.Acknowledged ->
                    if (result.echoedIdempotencyKey == pending.envelope.idempotencyKey) {
                        if (outbox.complete(pending.envelope.operationId, claimToken, clock.now())) {
                            completed++
                            discardAcknowledgedDocumentArtifact(pending)
                            recordOperation(pending, OperationalOutcome.SUCCEEDED)
                        }
                    } else {
                        permanentlyFailed += fail(
                            pending, claimToken,
                            targetStatus = OutboxOperationStatus.FAILED,
                            error = ERROR_INVALID_ACK,
                            nextAttemptAt = null,
                        )
                    }
                is BackupTransportResult.TransientFailure ->
                    if (attempt >= BackupBackoffPolicy.MAX_ATTEMPTS) {
                        permanentlyFailed += fail(
                            pending, claimToken,
                            targetStatus = OutboxOperationStatus.FAILED,
                            error = sanitize(result.reason, ERROR_TRANSIENT),
                            nextAttemptAt = null,
                        )
                    } else {
                        scheduledRetries += fail(
                            pending, claimToken,
                            targetStatus = OutboxOperationStatus.PENDING,
                            error = sanitize(result.reason, ERROR_TRANSIENT),
                            nextAttemptAt = clock.now()
                                .plusMillis(BackupBackoffPolicy.backoffMillis(attempt)),
                        )
                    }
                is BackupTransportResult.PermanentFailure ->
                    permanentlyFailed += fail(
                        pending, claimToken,
                        targetStatus = OutboxOperationStatus.FAILED,
                        error = sanitize(result.reason, ERROR_PERMANENT),
                        nextAttemptAt = null,
                    )
                is BackupTransportResult.Conflict ->
                    conflicts += fail(
                        pending, claimToken,
                        targetStatus = OutboxOperationStatus.CONFLICT,
                        error = sanitize(result.reason, ERROR_CONFLICT),
                        nextAttemptAt = null,
                        conflictRemotePurchaseId = result.remotePurchaseId,
                        conflictReceiptId = result.remoteReceiptId,
                        remoteEntity = result.remoteEntity,
                    )
                BackupTransportResult.Suppressed -> {
                    if (outbox.resolveSuppressed(
                            pending.envelope.operationId,
                            claimToken,
                            clock.now(),
                        )
                    ) {
                        suppressed++
                        recordOperation(pending, OperationalOutcome.SKIPPED)
                    }
                }
                BackupTransportResult.Unavailable -> {
                    // El transporte dejó de estar configurado a mitad de pasada: el intento
                    // nunca salió, así que no consume `attemptCount`. No tiene sentido seguir.
                    if (outbox.release(pending.envelope.operationId, claimToken, clock.now())) {
                        released++
                        recordOperation(
                            pending,
                            OperationalOutcome.SKIPPED,
                            OperationalErrorCode.TRANSPORT_UNAVAILABLE,
                        )
                    }
                    haltedByUnavailable = true
                    break
                }
            }
        }

        // También se revalida al cerrar el snapshot: una sesión que cambió mientras la última
        // request ya estaba en vuelo no debe activar otra pasada ni un pull bajo el enlace nuevo.
        if (!haltedByUnavailable && !haltedByPolicy && !targetAvailable()) {
            haltedByUnavailable = true
        }

        // Completar un alta puede volver elegible una anulación que no pertenecía al snapshot
        // inicial. Se pide otra pasada inmediata solo si ahora existe trabajo realmente listo.
        val reachedInitialLimit = ready.size >= batchLimit
        val hasImmediatelyReadyWork = !reachedInitialLimit && !haltedByUnavailable &&
            !haltedByPolicy &&
            listReadyForTarget(
                now = clock.now(),
                limit = 1,
                onlyDocumentPurges = onlyDocumentPurges,
                targetCloudBusinessId = targetCloudBusinessId,
                authorizedDocumentPurgeTargets = authorizedDocumentPurgeTargets,
            ).isNotEmpty()
        val wakeupAt = listOfNotNull(
            if (onlyDocumentPurges && targetCloudBusinessId == null) {
                outbox.findNextDocumentPurgeAttemptAt(clock.now())
            } else {
                outbox.findNextAttemptAt(clock.now(), targetCloudBusinessId)
            },
            if (onlyDocumentPurges && targetCloudBusinessId == null) {
                outbox.findNextDocumentPurgeClaimLeaseExpiry(clock.now())
            } else {
                outbox.findNextClaimLeaseExpiry(clock.now(), targetCloudBusinessId)
            },
        ).minOrNull()
        return OutboxPassResult.Drained(
            processed = completed + scheduledRetries + permanentlyFailed + conflicts + released +
                suppressed,
            completed = completed,
            scheduledRetries = scheduledRetries,
            permanentlyFailed = permanentlyFailed,
            conflicts = conflicts,
            released = released,
            suppressed = suppressed,
            reachedBatchLimit = !haltedByPolicy && !haltedByUnavailable &&
                (reachedInitialLimit || hasImmediatelyReadyWork),
            nextWakeupAt = wakeupAt,
            targetTemporarilyUnavailable = haltedByUnavailable,
        )
    }

    private suspend fun hasCompletedPurchasePostIfRequired(
        pending: PendingBackupOperation,
    ): Boolean {
        if (pending.envelope.operationType !in PURCHASE_POST_DEPENDENT_OPERATIONS) return true
        val purchaseId = pending.envelope.purchaseId ?: return false
        return outbox.isPurchasePostCompleted(
            businessId = pending.envelope.businessId,
            purchaseId = purchaseId,
            targetCloudBusinessId = pending.envelope.targetCloudBusinessId,
        )
    }

    /** Defensa del puerto: un adapter defectuoso nunca puede colar una fila de otro tenant. */
    private suspend fun listReadyForTarget(
        now: Instant,
        limit: Int,
        onlyDocumentPurges: Boolean,
        targetCloudBusinessId: BusinessId?,
        authorizedDocumentPurgeTargets: Set<BusinessId>?,
    ): List<PendingBackupOperation> = (if (
        onlyDocumentPurges && targetCloudBusinessId == null &&
        authorizedDocumentPurgeTargets != null
    ) {
        outbox.listReadyDocumentPurges(now, limit, authorizedDocumentPurgeTargets)
    } else {
        outbox.listReady(now, limit, onlyDocumentPurges, targetCloudBusinessId)
    }).filter { pending ->
        targetCloudBusinessId == null ||
            pending.envelope.targetCloudBusinessId == targetCloudBusinessId
    }

    private suspend fun sendSafely(pending: PendingBackupOperation): BackupTransportResult =
        try {
            transport.send(pending.envelope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Un transporte defectuoso no filtra detalles a la cola: se trata como transitorio
            // y el intento queda registrado con un código cerrado.
            BackupTransportResult.TransientFailure(ERROR_TRANSPORT_EXCEPTION)
        }

    /**
     * El JPEG derivado es parte del protocolo de reintento: se conserva hasta que el ACK haya
     * ganado el CAS durable de Room. Tras ese commit su limpieza es local, idempotente y de
     * mejor esfuerzo; una caída deja que el barrido de huérfanos la complete al reiniciar.
     */
    private suspend fun discardAcknowledgedDocumentArtifact(
        pending: PendingBackupOperation,
    ) {
        if (pending.envelope.operationType != SYNC_DOCUMENT_UPLOAD) return
        try {
            // Si el worker se cancela justo después del commit, todavía se intenta retirar el
            // artefacto. Nunca se revierte COMPLETED por un fallo de limpieza local.
            withContext(NonCancellable) {
                documentUploadPreparer.discard(pending.envelope.entityId)
            }
        } catch (_: Exception) {
            // El sweep de startup usa Room como autoridad y vuelve a intentarlo.
        }
    }

    private suspend fun fail(
        pending: PendingBackupOperation,
        claimToken: String,
        targetStatus: OutboxOperationStatus,
        error: String,
        nextAttemptAt: Instant?,
        conflictRemotePurchaseId: String? = null,
        conflictReceiptId: String? = null,
        remoteEntity: RemoteConflictMetadata? = null,
    ): Int {
        val failedAt = clock.now()
        val changed = if (remoteEntity == null) {
            outbox.fail(
                operationId = pending.envelope.operationId,
                claimToken = claimToken,
                targetStatus = targetStatus,
                error = error,
                nextAttemptAt = nextAttemptAt,
                failedAt = failedAt,
                conflictRemotePurchaseId = conflictRemotePurchaseId,
                conflictReceiptId = conflictReceiptId,
            )
        } else {
            outbox.failWithRemoteConflict(
                operationId = pending.envelope.operationId,
                claimToken = claimToken,
                targetStatus = targetStatus,
                error = error,
                nextAttemptAt = nextAttemptAt,
                failedAt = failedAt,
                conflictRemotePurchaseId = conflictRemotePurchaseId,
                conflictReceiptId = conflictReceiptId,
                remoteEntity = remoteEntity,
            )
        }
        if (!changed) return 0
        recordOperation(
            pending = pending,
            outcome = when (targetStatus) {
                OutboxOperationStatus.PENDING -> OperationalOutcome.RETRY_SCHEDULED
                OutboxOperationStatus.CONFLICT -> OperationalOutcome.CONFLICT
                else -> OperationalOutcome.FAILED
            },
            errorCode = error.toOperationalOutboxErrorCode(),
        )
        return 1
    }

    private suspend fun recordOperation(
        pending: PendingBackupOperation,
        outcome: OperationalOutcome,
        errorCode: OperationalErrorCode? = null,
    ) {
        val identifiers = runCatching {
            InternalIdentifiers(
                businessId = pending.envelope.businessId,
                purchaseId = pending.envelope.purchaseId,
                operationId = pending.envelope.operationId,
            )
        }.getOrElse {
            // Envelopes legados/no productivos pueden no tipar operationId; jamás se reenvía.
            InternalIdentifiers(
                businessId = pending.envelope.businessId,
                purchaseId = pending.envelope.purchaseId,
            )
        }
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.BACKUP_SYNC,
                outcome = outcome,
                identifiers = identifiers,
                errorCode = errorCode,
                ageBucket = pending.createdAt?.let { createdAt ->
                    operationalAgeBucket(now = clock.now(), createdAt = createdAt)
                },
            ),
        )
    }

    private fun sanitize(reason: String, fallback: String): String =
        if (SAFE_ERROR_CODE.matches(reason)) reason else fallback

    companion object {
        const val DEFAULT_BATCH_LIMIT = 50
        const val ERROR_TRANSIENT = "TRANSIENT_FAILURE"
        const val ERROR_PERMANENT = "PERMANENT_FAILURE"
        const val ERROR_CONFLICT = "CONFLICT"
        const val ERROR_INVALID_ACK = "INVALID_ACK"
        const val ERROR_TRANSPORT_EXCEPTION = "UNEXPECTED_TRANSPORT_ERROR"
        private const val SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
        private const val SYNC_DOCUMENT_UPLOAD = "SYNC_DOCUMENT_UPLOAD"
        private const val SYNC_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
        private val PURCHASE_POST_DEPENDENT_OPERATIONS = setOf(
            SYNC_PURCHASE_VOID,
            SYNC_DOCUMENT_UPLOAD,
            SYNC_DOCUMENT_PURGE,
        )
        private val SAFE_ERROR_CODE = Regex("[A-Z0-9_]{1,64}")
    }
}

/**
 * Conserva el detalle operacional necesario para alertas sin reenviar mensajes del backend.
 * La entrada ya pasó por [ProcessPurchaseBackupOutboxUseCase.sanitize], por lo que solo se
 * clasifican códigos cerrados y conocidos; cualquier código futuro cae en una categoría segura.
 */
private fun String.toOperationalOutboxErrorCode(): OperationalErrorCode = when (this) {
    ProcessPurchaseBackupOutboxUseCase.ERROR_INVALID_ACK,
    ProcessPurchaseBackupOutboxUseCase.ERROR_CONFLICT,
    "ALREADY_EXISTS",
    "ABORTED",
    "FAILED_PRECONDITION",
    "HTTP_409",
    -> OperationalErrorCode.INTEGRITY_CONFLICT
    "INTERNAL",
    "UNAVAILABLE",
    "DEADLINE_EXCEEDED",
    "HTTP_500",
    "HTTP_502",
    "HTTP_503",
    "HTTP_504",
    -> OperationalErrorCode.BACKEND_5XX
    "RESOURCE_EXHAUSTED",
    "HTTP_429",
    -> OperationalErrorCode.BACKEND_RATE_LIMITED
    "NETWORK_UNAVAILABLE" -> OperationalErrorCode.TRANSPORT_UNAVAILABLE
    ProcessPurchaseBackupOutboxUseCase.ERROR_TRANSIENT,
    ProcessPurchaseBackupOutboxUseCase.ERROR_TRANSPORT_EXCEPTION,
    "CATALOG_NOT_READY",
    "DUPLICATE_TARGET_NOT_SYNCED",
    -> OperationalErrorCode.TRANSIENT_FAILURE
    ProcessPurchaseBackupOutboxUseCase.ERROR_PERMANENT ->
        OperationalErrorCode.PERMANENT_FAILURE
    else -> OperationalErrorCode.UNEXPECTED
}

/** Convierte tiempo local en un catalogo cerrado; el timestamp exacto nunca llega al sink. */
internal fun operationalAgeBucket(ageMillis: Long): OperationalAgeBucket {
    require(ageMillis >= 0L)
    return when {
        ageMillis < 15L * 60L * 1_000L -> OperationalAgeBucket.UNDER_15_MINUTES
        ageMillis < 60L * 60L * 1_000L ->
            OperationalAgeBucket.FROM_15_MINUTES_TO_1_HOUR
        ageMillis < 6L * 60L * 60L * 1_000L -> OperationalAgeBucket.FROM_1_TO_6_HOURS
        ageMillis < 24L * 60L * 60L * 1_000L -> OperationalAgeBucket.FROM_6_TO_24_HOURS
        ageMillis < 7L * 24L * 60L * 60L * 1_000L -> OperationalAgeBucket.FROM_1_TO_7_DAYS
        else -> OperationalAgeBucket.OVER_7_DAYS
    }
}

/** Un reloj atrasado es una señal propia; no se disfraza como una operación recién creada. */
internal fun operationalAgeBucket(
    now: Instant,
    createdAt: Instant,
): OperationalAgeBucket = if (createdAt.isAfter(now)) {
    OperationalAgeBucket.CLOCK_SKEW_OR_FUTURE
} else {
    operationalAgeBucket(now.toEpochMilli() - createdAt.toEpochMilli())
}
