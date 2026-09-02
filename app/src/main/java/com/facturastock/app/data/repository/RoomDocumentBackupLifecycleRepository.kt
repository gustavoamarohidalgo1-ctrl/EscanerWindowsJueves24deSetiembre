package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.DocumentUploadArtifactSweepReport
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.DisabledDocumentUploadPreparer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class RoomDocumentBackupLifecycleRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
    private val documentUploadPreparer: DocumentUploadPreparer = DisabledDocumentUploadPreparer,
) : DocumentBackupLifecycleRepository {

    override suspend fun isBackedUp(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        imageId: ImageId,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val outbox = database.outboxOperationDao()
                val upload = outbox.findForEntityVersion(
                    businessId = businessId.value,
                    entityType = DOCUMENT_ENTITY_TYPE,
                    entityId = imageId.value,
                    entityVersion = UPLOAD_ENTITY_VERSION,
                ) ?: return@withTransaction false
                val purge = outbox.findForEntityVersion(
                    businessId = businessId.value,
                    entityType = DOCUMENT_ENTITY_TYPE,
                    entityId = imageId.value,
                    entityVersion = PURGE_ENTITY_VERSION,
                )
                val binding = database.cloudBusinessBindingDao().findByLocal(businessId.value)
                upload.operationType == SYNC_DOCUMENT_UPLOAD &&
                    upload.purchaseId == purchaseId.value &&
                    upload.status == OutboxOperationStatus.COMPLETED.name &&
                    upload.completedAt != null &&
                    purge == null &&
                    upload.targetCloudBusinessId != null &&
                    binding?.cloudBusinessId == upload.targetCloudBusinessId
            }
        }
    }

    override suspend fun ensurePurge(
        businessId: BusinessId,
        image: RetainedImageRef,
        requestedAt: Instant,
    ): DocumentPurgeIntentResult = withContext(dispatchers.io) {
        storageCatching {
            val result = database.withTransaction {
                requireNotNull(
                    ensurePurgeInTransaction(
                        businessId = businessId.value,
                        purchaseId = image.purchaseId.value,
                        imageId = image.imageId.value,
                        requestedAt = requestedAt.toEpochMilli(),
                    ).result,
                ) { "La imagen retenida no pertenece a una compra terminal del negocio" }
            }
            if (
                result == DocumentPurgeIntentResult.DURABLE &&
                !artifactDiscarded(image.imageId.value)
            ) {
                DocumentPurgeIntentResult.DURABLE_ARTIFACT_RETRY_REQUIRED
            } else {
                result
            }
        }
    }

    override suspend fun ensureRetainedUploads(
        businessId: BusinessId,
        postedAfterExclusive: Instant?,
        requestedAt: Instant,
    ): Int = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val outbox = database.outboxOperationDao()
                var inserted = 0
                database.purchaseDao().listRetainedDocumentCandidates(
                    businessId = businessId.value,
                    postedAfterExclusive = postedAfterExclusive?.toEpochMilli(),
                    documentEntityType = DOCUMENT_ENTITY_TYPE,
                    uploadEntityVersion = UPLOAD_ENTITY_VERSION,
                    purgeEntityVersion = PURGE_ENTITY_VERSION,
                ).forEach { image ->
                    val payload = DocumentBackupPayloadCodec.encodeUpload(
                        purchaseId = image.purchaseId,
                        imageId = image.imageId,
                        sha256 = image.sha256,
                        relativeFilePath = image.filePath,
                        mimeType = image.mimeType,
                        widthPx = image.widthPx,
                        heightPx = image.heightPx,
                        fileSizeBytes = image.fileSizeBytes,
                        rotationDegrees = image.rotationDegrees,
                    )
                    // Una fila histórica con formato no soportado no abre una superficie de
                    // subida. Las demás imágenes se bootstrappean en la misma transacción.
                    if (DocumentBackupPayloadCodec.decodeUpload(payload) == null) return@forEach
                    outbox.insert(
                        OutboxOperationEntity(
                            operationId = DocumentBackupPayloadCodec.candidateOperationId(
                                image.purchaseId,
                                image.imageId,
                            ),
                            businessId = businessId.value,
                            purchaseId = image.purchaseId,
                            idempotencyKey =
                                DocumentBackupPayloadCodec.candidateIdempotencyKey(
                                    image.purchaseId,
                                    image.imageId,
                                    image.sha256,
                                ),
                            operationType = SYNC_DOCUMENT_UPLOAD,
                            payload = payload,
                            status = OutboxOperationStatus.PENDING.name,
                            createdAt = requestedAt.toEpochMilli(),
                            updatedAt = requestedAt.toEpochMilli(),
                            nextAttemptAt = requestedAt.toEpochMilli(),
                            payloadVersion = DocumentBackupPayloadCodec.PAYLOAD_VERSION,
                            entityType = DOCUMENT_ENTITY_TYPE,
                            entityId = image.imageId,
                            entityVersion = UPLOAD_ENTITY_VERSION,
                        ),
                    )
                    inserted++
                }
                inserted
            }
        }
    }

    override suspend fun withdrawOpenUploads(
        businessId: BusinessId,
        requestedAt: Instant,
    ): Int = withContext(dispatchers.io) {
        storageCatching {
            val changed = database.withTransaction {
                val outbox = database.outboxOperationDao()
                val uploads = outbox.listDocumentUploadsNeedingWithdrawal(
                    businessId = businessId.value,
                    uploadOperationType = SYNC_DOCUMENT_UPLOAD,
                    purgeOperationType = SYNC_DOCUMENT_PURGE,
                    entityType = DOCUMENT_ENTITY_TYPE,
                    uploadEntityVersion = UPLOAD_ENTITY_VERSION,
                    purgeEntityVersion = PURGE_ENTITY_VERSION,
                    statuses = WITHDRAWABLE_UPLOAD_STATUSES,
                )
                withdrawUploadsInTransaction(uploads, requestedAt.toEpochMilli())
            }
            attemptArtifactSweep()
            changed
        }
    }

    override suspend fun withdrawAllOpenUploads(requestedAt: Instant): Int =
        withContext(dispatchers.io) {
            storageCatching {
                val changed = database.withTransaction {
                    val uploads = database.outboxOperationDao()
                        .listAllDocumentUploadsNeedingWithdrawal(
                            uploadOperationType = SYNC_DOCUMENT_UPLOAD,
                            purgeOperationType = SYNC_DOCUMENT_PURGE,
                            entityType = DOCUMENT_ENTITY_TYPE,
                            uploadEntityVersion = UPLOAD_ENTITY_VERSION,
                            purgeEntityVersion = PURGE_ENTITY_VERSION,
                            statuses = WITHDRAWABLE_UPLOAD_STATUSES,
                        )
                    withdrawUploadsInTransaction(uploads, requestedAt.toEpochMilli())
                }
                attemptArtifactSweep()
                changed
            }
        }

    override suspend fun sweepOrphanedPreparedArtifacts(): DocumentUploadArtifactSweepReport =
        withContext(dispatchers.io) {
            storageCatching {
                val activeImageIds = database.withTransaction {
                    database.outboxOperationDao().listEntityIdsByTypeAndStatuses(
                        operationType = SYNC_DOCUMENT_UPLOAD,
                        entityType = DOCUMENT_ENTITY_TYPE,
                        entityVersion = UPLOAD_ENTITY_VERSION,
                        statuses = OPEN_UPLOAD_STATUSES,
                    ).map { rawId ->
                        ImageId.parse(rawId)
                            ?: throw IOException("Identidad documental local inválida")
                    }.toSet()
                }
                documentUploadPreparer.sweepOrphans(activeImageIds)
            }
        }

    private suspend fun artifactDiscarded(imageId: String): Boolean = try {
        documentUploadPreparer.discard(imageId) in setOf(
            PrivateImageDeletionResult.DELETED,
            PrivateImageDeletionResult.ALREADY_ABSENT,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun withdrawUploadsInTransaction(
        uploads: List<OutboxOperationEntity>,
        requestedAt: Long,
    ): Int {
        var changed = 0
        uploads.forEach { upload ->
            val purchaseId = upload.purchaseId ?: return@forEach
            val outcome = ensurePurgeInTransaction(
                businessId = upload.businessId,
                purchaseId = purchaseId,
                imageId = upload.entityId,
                requestedAt = requestedAt,
            )
            if (
                outcome.result != null &&
                (outcome.resolvedUploads > 0 || outcome.purgeInserted)
            ) {
                changed++
            }
        }
        return changed
    }

    /**
     * El borrado local no gobierna el drenado remoto: los tombstones ya hicieron commit. Un
     * `.fse` temporalmente no borrable queda visible al mantenimiento de privacidad, que lo
     * reintenta; nunca debe impedir que el worker entregue PURGE a todos los tenants.
     */
    private suspend fun attemptArtifactSweep() {
        try {
            sweepOrphanedPreparedArtifacts()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort. RunPrivacyMaintenanceUseCase conserva el reporte retryable.
        }
    }

    private suspend fun ensurePurgeInTransaction(
        businessId: String,
        purchaseId: String,
        imageId: String,
        requestedAt: Long,
    ): PurgeOutcome {
        val purchase = database.purchaseDao().findById(purchaseId)
            ?: return PurgeOutcome(result = null)
        if (purchase.businessId != businessId ||
            purchase.status !in setOf(PurchaseStatus.POSTED.name, PurchaseStatus.VOIDED.name)
        ) {
            return PurgeOutcome(result = null)
        }
        val outbox = database.outboxOperationDao()
        val existingPurge = outbox.findForEntityVersion(
            businessId = businessId,
            entityType = DOCUMENT_ENTITY_TYPE,
            entityId = imageId,
            entityVersion = PURGE_ENTITY_VERSION,
        )
        val upload = outbox.findForEntityVersion(
            businessId = businessId,
            entityType = DOCUMENT_ENTITY_TYPE,
            entityId = imageId,
            entityVersion = UPLOAD_ENTITY_VERSION,
        )
        if (upload == null) {
            // La ausencia de upload no basta como supresión: un bootstrap concurrente o futuro
            // podría crearlo después de que el original local ya fue borrado. La versión 2 es a
            // la vez tombstone remoto idempotente y marca local durable que bloquea altas v1.
            val currentTarget = database.cloudBusinessBindingDao().findByLocal(businessId)
                ?.cloudBusinessId
            if (existingPurge != null) {
                return if (
                    existingPurge.operationType == SYNC_DOCUMENT_PURGE &&
                    existingPurge.purchaseId == purchaseId &&
                    (existingPurge.targetCloudBusinessId == null ||
                        currentTarget == null ||
                        existingPurge.targetCloudBusinessId == currentTarget)
                ) {
                    PurgeOutcome(result = DocumentPurgeIntentResult.DURABLE)
                } else {
                    PurgeOutcome(
                        result = DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN,
                    )
                }
            }
            insertPurge(
                outbox = outbox,
                businessId = businessId,
                purchaseId = purchaseId,
                imageId = imageId,
                requestedAt = requestedAt,
                targetCloudBusinessId = currentTarget,
            )
            return PurgeOutcome(
                result = DocumentPurgeIntentResult.DURABLE,
                purgeInserted = true,
            )
        }
        if (upload.operationType != SYNC_DOCUMENT_UPLOAD || upload.purchaseId != purchaseId) {
            return PurgeOutcome(result = null)
        }

        // Replay exacto de una supresión local demostrablemente anterior al wire. Esta fila se
        // conserva como evidencia terminal y, deliberadamente, no se fija al primer binding.
        // Mantenimientos repetidos antes o después de enlazar deben seguir siendo idempotentes.
        if (upload.isProvablySuppressedWithoutSend() && existingPurge == null) {
            return PurgeOutcome(result = DocumentPurgeIntentResult.NOT_REQUIRED)
        }

        val uploadTarget = upload.targetCloudBusinessId
        if (uploadTarget == null) {
            // Solo una fila nunca reclamada prueba que ningún byte pudo salir. Una operación
            // attempted/terminal de v19 no conserva destino: ni el enlace actual ni una fila
            // PURGE sin tenant permiten reconstruirlo con seguridad.
            if (!upload.isProvablyNeverSent() || existingPurge != null) {
                return PurgeOutcome(
                    result = DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN,
                )
            }
            val resolved = outbox.resolveOpenWithoutClaim(
                operationId = upload.operationId,
                openStatuses = OPEN_UPLOAD_STATUSES,
                resolvedStatus = OutboxOperationStatus.RESOLVED.name,
                resolvedAt = maxOf(requestedAt, upload.updatedAt),
            )
            if (resolved != 1) {
                return PurgeOutcome(
                    result = DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN,
                )
            }
            return PurgeOutcome(
                result = DocumentPurgeIntentResult.NOT_REQUIRED,
                resolvedUploads = resolved,
            )
        }

        val binding = database.cloudBusinessBindingDao().findByLocal(businessId)
        if (binding?.cloudBusinessId != uploadTarget) {
            return PurgeOutcome(
                result = DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN,
            )
        }
        if (existingPurge != null && (
                existingPurge.operationType != SYNC_DOCUMENT_PURGE ||
                    existingPurge.purchaseId != purchaseId ||
                    existingPurge.targetCloudBusinessId != uploadTarget
                )
        ) {
            return PurgeOutcome(
                result = DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN,
            )
        }
        if (existingPurge == null) {
            insertPurge(
                outbox = outbox,
                businessId = businessId,
                purchaseId = purchaseId,
                imageId = imageId,
                requestedAt = requestedAt,
                targetCloudBusinessId = uploadTarget,
            )
        }
        // La intención PURGE ya es durable antes de cancelar incluso un claim en vuelo. El
        // tombstone remoto hace segura la carrera: si el upload termina tarde, el callable lo
        // elimina o lo rechaza; la fila local ya no puede bloquear la versión 2 indefinidamente.
        val resolved = outbox.resolveOpenWithoutClaim(
            operationId = upload.operationId,
            openStatuses = OPEN_UPLOAD_STATUSES,
            resolvedStatus = OutboxOperationStatus.RESOLVED.name,
            resolvedAt = maxOf(requestedAt, upload.updatedAt),
        )
        return PurgeOutcome(
            result = DocumentPurgeIntentResult.DURABLE,
            resolvedUploads = resolved,
            purgeInserted = existingPurge == null,
        )
    }

    private suspend fun insertPurge(
        outbox: com.facturastock.app.data.local.dao.OutboxOperationDao,
        businessId: String,
        purchaseId: String,
        imageId: String,
        requestedAt: Long,
        targetCloudBusinessId: String?,
    ) {
        val idempotencyKey = DocumentBackupPayloadCodec.purgeIdempotencyKey(purchaseId, imageId)
        outbox.insert(
            OutboxOperationEntity(
                operationId = deterministicUuid(idempotencyKey).toString(),
                businessId = businessId,
                purchaseId = purchaseId,
                idempotencyKey = idempotencyKey,
                operationType = SYNC_DOCUMENT_PURGE,
                payload = DocumentBackupPayloadCodec.encodePurge(purchaseId, imageId),
                status = OutboxOperationStatus.PENDING.name,
                createdAt = requestedAt,
                updatedAt = requestedAt,
                nextAttemptAt = requestedAt,
                payloadVersion = DocumentBackupPayloadCodec.PAYLOAD_VERSION,
                entityType = DOCUMENT_ENTITY_TYPE,
                entityId = imageId,
                entityVersion = PURGE_ENTITY_VERSION,
                targetCloudBusinessId = targetCloudBusinessId,
            ),
        )
    }

    private fun deterministicUuid(key: String): UUID {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("facturastock:$key".toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    private fun OutboxOperationEntity.isProvablyNeverSent(): Boolean =
        status == OutboxOperationStatus.PENDING.name && attemptCount == 0 &&
            completedAt == null && claimToken == null && claimLeaseUntil == null

    private fun OutboxOperationEntity.isProvablySuppressedWithoutSend(): Boolean =
        operationType == SYNC_DOCUMENT_UPLOAD && entityType == DOCUMENT_ENTITY_TYPE &&
            entityVersion == UPLOAD_ENTITY_VERSION &&
            status == OutboxOperationStatus.RESOLVED.name && attemptCount == 0 &&
            completedAt == null && claimToken == null && claimLeaseUntil == null

    private data class PurgeOutcome(
        val result: DocumentPurgeIntentResult?,
        val resolvedUploads: Int = 0,
        val purgeInserted: Boolean = false,
    )

    private companion object {
        const val SYNC_DOCUMENT_UPLOAD = "SYNC_DOCUMENT_UPLOAD"
        const val SYNC_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
        const val DOCUMENT_ENTITY_TYPE = "DOCUMENT"
        const val PURGE_ENTITY_VERSION = 2L
        const val UPLOAD_ENTITY_VERSION = 1L
        val OPEN_UPLOAD_STATUSES = listOf(
            OutboxOperationStatus.PENDING.name,
            OutboxOperationStatus.PROCESSING.name,
            OutboxOperationStatus.FAILED.name,
            OutboxOperationStatus.CONFLICT.name,
        )
        // El opt-out no solo cancela intentos: también debe tombstonear documentos cuyo upload
        // ya recibió ACK, y estados ambiguos que pudieron alcanzar el wire antes de cerrarse.
        val WITHDRAWABLE_UPLOAD_STATUSES = OPEN_UPLOAD_STATUSES + listOf(
            OutboxOperationStatus.RESOLVED.name,
            OutboxOperationStatus.COMPLETED.name,
        )
    }
}
