package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.core.time.SystemAppClock
import com.facturastock.app.core.coroutines.SuspendMutex
import com.facturastock.app.domain.model.ExportedAuditEvent
import com.facturastock.app.domain.model.ExportedBusiness
import com.facturastock.app.domain.model.ExportedDuplicateOverride
import com.facturastock.app.domain.model.ExportedInventoryBalance
import com.facturastock.app.domain.model.ExportedInventoryLocation
import com.facturastock.app.domain.model.ExportedProduct
import com.facturastock.app.domain.model.ExportedPurchase
import com.facturastock.app.domain.model.ExportedPurchaseLine
import com.facturastock.app.domain.model.ExportedRetainedImage
import com.facturastock.app.domain.model.ExportedStockMovement
import com.facturastock.app.domain.model.ExportedSupplier
import com.facturastock.app.domain.model.ExportedSupplierProductAlias
import com.facturastock.app.domain.model.ExportedUnit
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageRetentionDecider
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseRetainedImage
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.PrivateFileSweepReport
import com.facturastock.app.domain.model.OcrVersionSweepReport
import com.facturastock.app.domain.model.PrivacyMaintenanceStep
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.RetainedImageMigrationState
import com.facturastock.app.domain.model.RetentionSweepReport
import com.facturastock.app.domain.model.UserDataExport
import com.facturastock.app.domain.model.UserDataExportWriteResult
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.DocumentUploadArtifactSweepReport
import com.facturastock.app.domain.repository.DisabledDocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.InventoryReadRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.OcrVersionSweepDecision
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseReadRepository
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.domain.repository.RetainedImageStore
import com.facturastock.app.domain.repository.RetainedImageReadResult
import com.facturastock.app.domain.repository.RetentionFileSweep
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.repository.UserDataExportWriter
import com.facturastock.app.domain.repository.enqueueBestEffort
import com.facturastock.app.domain.repository.enqueueImmediateBestEffort
import com.facturastock.app.domain.repository.enqueuePrivacyPurgeBestEffort
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Actualiza la política global de retención de imágenes. Gobierna solo archivos de imagen:
 * los registros contables jamás se borran por retención. El mantenimiento periódico y los
 * hooks de OCR/confirmación leen la política fresca en cada ejecución.
 */
class UpdateImageRetentionPolicyUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
) {
    suspend operator fun invoke(policy: ImageRetentionPolicy) {
        appConfigurationRepository.updateImageRetentionPolicy(policy)
    }
}

/**
 * Activa o desactiva el respaldo comercial. Al desactivarse se cancela el drenado ordinario,
 * pero se despierta el canal mínimo de privacidad: los tombstones explícitos de purga siguen
 * pudiendo llegar al servidor aunque no se suban ni descarguen datos comerciales.
 */
class UpdateBackupEnabledUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val purchaseBackupScheduler: PurchaseBackupScheduler,
) {
    suspend operator fun invoke(enabled: Boolean): BackupPreferenceUpdate {
        appConfigurationRepository.updateBackupEnabled(enabled)
        val schedulerUpdated = if (enabled) {
            try {
                purchaseBackupScheduler.enqueue()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        } else {
            // Son dos efectos independientes posteriores al commit. Un ENOSPC al cancelar la
            // cadena comercial no debe impedir que el tombstone de privacidad sea despertado.
            var regularCancelled = false
            var privacyWoken = false
            try {
                regularCancelled = try {
                    purchaseBackupScheduler.cancelRegular()
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            } finally {
                // OFF ya es durable. Una cancelacion de navegacion en esta microventana no
                // puede suprimir el wake que cumple tombstones remotos.
                privacyWoken = withContext(NonCancellable) {
                    try {
                        purchaseBackupScheduler.enqueuePrivacyPurge()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            regularCancelled && privacyWoken
        }
        return BackupPreferenceUpdate(enabled = enabled, schedulerUpdated = schedulerUpdated)
    }
}

/** Resultado real: la preferencia ya se persistió; el segundo flag informa el wake/cancel. */
data class BackupPreferenceUpdate(
    val enabled: Boolean,
    val schedulerUpdated: Boolean,
)

/** Opt-in independiente para respaldar documentos cifrados, desactivado por defecto. */
class UpdateDocumentBackupEnabledUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val documentLifecycle: DocumentBackupLifecycleRepository =
        DisabledDocumentBackupLifecycleRepository,
    private val purchaseBackupScheduler: PurchaseBackupScheduler =
        DisabledPurchaseBackupScheduler,
    private val appClock: AppClock = SystemAppClock(),
) {
    suspend operator fun invoke(enabled: Boolean) {
        appConfigurationRepository.updateDocumentBackupEnabled(enabled)
        if (!enabled) {
            try {
                documentLifecycle.withdrawAllOpenUploads(appClock.now())
            } finally {
                // La preferencia OFF ya quedó durable. Incluso una cancelación o un fallo Room
                // posterior debe intentar el wake corto; startup cierra la microventana de kill.
                withContext(NonCancellable) {
                    try {
                        purchaseBackupScheduler.enqueuePrivacyPurge()
                    } catch (_: Exception) {
                        // El siguiente arranque vuelve a reconstruir el request desde Room.
                    }
                }
            }
            return
        }
        val configuration = appConfigurationRepository.current()
        if (configuration.backupEnabled) {
            try {
                val requestedAt = appClock.now()
                configuration.activeBusinessId?.let { businessId ->
                    when (val days = configuration.imageRetentionPolicy.retentionDays) {
                        null -> if (
                            configuration.imageRetentionPolicy == ImageRetentionPolicy.KEEP
                        ) {
                            documentLifecycle.ensureRetainedUploads(
                                businessId = businessId,
                                postedAfterExclusive = null,
                                requestedAt = requestedAt,
                            )
                        }
                        else -> documentLifecycle.ensureRetainedUploads(
                            businessId = businessId,
                            postedAfterExclusive = requestedAt.minus(Duration.ofDays(days)),
                            requestedAt = requestedAt,
                        )
                    }
                }
            } finally {
                // Incluso si ENOSPC impide bootstrap ahora, el worker queda despertado y lo
                // reconstruye idempotentemente en el próximo intento/arranque.
                purchaseBackupScheduler.enqueueBestEffort()
            }
        }
    }
}

/** Persiste el bloqueo local opcional; la UI autentica con biometría o credencial. */
class UpdateBiometricLockEnabledUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
) {
    suspend operator fun invoke(enabled: Boolean) {
        appConfigurationRepository.updateBiometricLockEnabled(enabled)
    }
}

/**
 * Persiste el opt-in y sincroniza la colección de SDK. Al desactivar, corta primero el canal;
 * al activar, solo abre el canal después de que DataStore haya confirmado la preferencia.
 */
class UpdateDiagnosticsConsentUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val observability: ProductionObservability,
) {
    suspend operator fun invoke(enabled: Boolean) {
        if (!enabled) observability.updateConsent(false)
        appConfigurationRepository.updateDiagnosticsEnabled(enabled)
        if (enabled) observability.updateConsent(true)
    }
}

/**
 * Hook de ciclo de vida tras publicarse un run OCR: con [ImageRetentionPolicy.AFTER_OCR] borra
 * los archivos ORIGINALES del borrador (sus versiones OCR de trabajo se conservan hasta la
 * confirmación). Mejor esfuerzo: un fallo de archivos jamás rompe la publicación del OCR; la
 * pasada de mantenimiento actúa de red de seguridad.
 */
class ApplyImageRetentionAfterOcrUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val draftFileStore: DraftFileStore,
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    suspend operator fun invoke(originalImages: List<InvoiceImage>) {
        try {
            val policy = appConfigurationRepository.current().imageRetentionPolicy
            if (ImageRetentionDecider.shouldDeleteAfterOcr(policy)) {
                // Una base legacy puede tener varias filas apuntando al mismo archivo. Solo se
                // borra cuando las referencias incluidas por ESTE run cubren todas las filas
                // Room actuales; una lectura incierta conserva el original. Recibir las filas
                // exactas del run evita releer todo el historial OCR tras cada captura.
                val imagesByPath = originalImages.groupBy(InvoiceImage::filePath)
                val safePaths = imagesByPath.mapNotNull { (path, imageIds) ->
                    path.takeIf {
                        invoiceDraftRepository.referenceCoverage(
                            path,
                            imageIds.mapTo(mutableSetOf(), InvoiceImage::imageId),
                        ) ==
                            ImagePathReferenceCoverage.COMPLETE
                    }
                }
                if (safePaths.isNotEmpty()) draftFileStore.deleteFiles(safePaths)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Mejor esfuerzo: el OCR ya quedó publicado de forma durable.
        }
    }
}

/** Resultado cerrado de revalidar todas las filas que comparten un artefacto físico. */
private enum class ImagePathReferenceCoverage {
    COMPLETE,
    BLOCKED_BY_OTHER_REFERENCE,
    UNAVAILABLE,
}

/**
 * Demuestra identidad, no solo cardinalidad, mediante un snapshot SQL único. Cero referencias
 * es seguro (el archivo ya es huérfano); con filas vivas, todas deben pertenecer al conjunto de
 * candidatas. Que una candidata haya desaparecido concurrentemente también es seguro.
 */
private suspend fun InvoiceDraftRepository.referenceCoverage(
    path: String,
    candidateImageIds: Set<ImageId>,
): ImagePathReferenceCoverage {
    val currentImageIds = try {
        listImageIdsReferencingPath(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return ImagePathReferenceCoverage.UNAVAILABLE
    }
    return if (currentImageIds.all { imageId -> imageId in candidateImageIds }) {
        ImagePathReferenceCoverage.COMPLETE
    } else {
        ImagePathReferenceCoverage.BLOCKED_BY_OTHER_REFERENCE
    }
}

/**
 * Hook de ciclo de vida tras el commit durable de una compra. Las políticas que borran en un
 * hito ([ImageRetentionPolicy.AFTER_OCR] y [ImageRetentionPolicy.AFTER_CONFIRM]) procesan solo
 * las referencias exactas de ese borrador: cualquier respaldo recibe una intención PURGE durable
 * antes del borrado local, sin recorrer todo el historial en el camino crítico. Siempre despierta
 * un worker inmediato y durable; el periódico queda como red de seguridad.
 */
class ApplyImageRetentionAfterConfirmUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val draftFileStore: DraftFileStore,
    private val runPrivacyMaintenanceUseCase: RunPrivacyMaintenanceUseCase? = null,
    private val purchaseReadRepository: PurchaseReadRepository? = null,
    private val retainedImageStore: RetainedImageStore? = null,
    private val privacyMaintenanceScheduler: PrivacyMaintenanceScheduler? = null,
) {
    suspend operator fun invoke(draftId: DraftId, businessId: BusinessId? = null) {
        try {
            val policy = try {
                appConfigurationRepository.current().imageRetentionPolicy
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
            val resolvedBusinessId = businessId
            val maintenance = runPrivacyMaintenanceUseCase
            if (maintenance != null && resolvedBusinessId != null) {
                maintenance.applyToConfirmedDraft(draftId, resolvedBusinessId, policy)
                return
            }

            // Fallback para construcciones unitarias mínimas: nunca borra originales sin el
            // coordinador de purga durable, pero sí limpia OCR y cifra referencias exactas.
            try {
                draftFileStore.deleteOcrVersions(draftId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // La compra ya está confirmada.
            }
            val reads = purchaseReadRepository ?: return
            val store = retainedImageStore ?: return
            if (resolvedBusinessId == null) return
            reads.listRetainedImagesForDraft(resolvedBusinessId, draftId).forEach { image ->
                bestEffortEncrypt(store, image.relativeFilePath)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // La compra ya quedó publicada; el worker inmediato es la red de seguridad.
        } finally {
            // El commit de compra ya ocurrió. La cancelación de navegación no puede evitar
            // que WorkManager reciba el hito durable de retención.
            withContext(NonCancellable) {
                try {
                    privacyMaintenanceScheduler?.enqueueImmediate()
                } catch (_: Exception) {
                    // El mantenimiento de arranque conserva la red de seguridad durable.
                }
            }
        }
    }

    private suspend fun bestEffortEncrypt(store: RetainedImageStore, relativePath: String) {
        try {
            store.encryptInPlace(relativePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // El worker reintenta la referencia exacta pendiente.
        }
    }
}

/** Lee una imagen retenida de forma transparente, sin exponer rutas directas a la UI. */
class ReadRetainedImageUseCase(
    private val retainedImageStore: RetainedImageStore,
) {
    suspend operator fun invoke(relativePath: String): ByteArray? =
        retainedImageStore.readDecrypted(relativePath)

    suspend fun forDisplay(relativePath: String): RetainedImageReadResult =
        retainedImageStore.readForDisplay(relativePath)
}

/**
 * Pasada de mantenimiento de privacidad: barre temporales de importación huérfanos,
 * directorios de imágenes de borradores inexistentes y versiones OCR de borradores ya
 * confirmados; aplica la política de retención a las imágenes de compras terminales y migra al
 * cifrado en reposo las que siguen retenidas en claro. La política se configura por instalación
 * y por eso se aplica a todos los negocios locales. Con [forceImageDeletion] borra todas las
 * imágenes de borradores abiertos y compras terminales de todos esos negocios.
 *
 * NUNCA toca filas de la base de datos: Room solo se revalida dentro del lock de cada subárbol,
 * justo antes de barrerlo. Así un borrador creado mientras corre la pasada no puede confundirse
 * con un huérfano; cada borrado/cifrado individual es de mejor esfuerzo y se cuenta solo cuando
 * ocurrió de verdad.
 */
class RunPrivacyMaintenanceUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val purchaseReadRepository: PurchaseReadRepository,
    private val retentionFileSweep: RetentionFileSweep,
    private val draftFileStore: DraftFileStore,
    private val retainedImageStore: RetainedImageStore,
    private val documentLifecycle: DocumentBackupLifecycleRepository =
        DisabledDocumentBackupLifecycleRepository,
    private val purchaseBackupScheduler: PurchaseBackupScheduler =
        DisabledPurchaseBackupScheduler,
    private val privacyMaintenanceScheduler: PrivacyMaintenanceScheduler? = null,
    private val ocrRunActivityRegistry: OcrRunActivityRegistry = OcrRunActivityRegistry(),
    private val appClock: AppClock,
) {
    /**
     * Camino exacto post-commit: O(páginas del borrador), no O(historial). Toda fuente que no
     * pueda borrarse con seguridad se cifra antes de devolver el control; una pasada durable ya
     * queda despertada por [ApplyImageRetentionAfterConfirmUseCase].
     */
    suspend fun applyToConfirmedDraft(
        draftId: DraftId,
        businessId: BusinessId,
        policy: ImageRetentionPolicy,
    ) {
        try {
            draftFileStore.deleteOcrVersions(draftId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Derivados regenerables; el sweep de confirmados vuelve a intentarlo.
        }
        val refs = try {
            purchaseReadRepository.listRetainedImagesForDraft(businessId, draftId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        val deleteAtMilestone = policy == ImageRetentionPolicy.AFTER_OCR ||
            policy == ImageRetentionPolicy.AFTER_CONFIRM
        var durablePurgeCreated = false
        for ((relativePath, pathRefs) in refs.groupBy(RetainedImageRef::relativeFilePath)) {
            val referenceCoverage = invoiceDraftRepository.referenceCoverage(
                relativePath,
                pathRefs.mapTo(mutableSetOf(), RetainedImageRef::imageId),
            )
            if (!deleteAtMilestone) {
                if (referenceCoverage == ImagePathReferenceCoverage.COMPLETE) {
                    encryptRetainedBestEffort(relativePath)
                }
                continue
            }
            val authorizations = pathRefs.map { ref ->
                authorizePurge(ref, appClock.now()).also { authorization ->
                    if (authorization.isDurable) durablePurgeCreated = true
                }
            }
            // Una fila legacy de otro draft/compra puede compartir el único archivo. Solo se
            // elimina cuando este grupo demuestra cubrir TODAS las referencias Room actuales.
            val coversEveryReference =
                referenceCoverage == ImagePathReferenceCoverage.COMPLETE
            val deleted = authorizations.all { it.mayDelete } &&
                coversEveryReference &&
                deleteRetainedPath(relativePath).confirmsAbsence
            if (!deleted && referenceCoverage == ImagePathReferenceCoverage.COMPLETE) {
                encryptRetainedBestEffort(relativePath)
            }
        }
        if (durablePurgeCreated) purchaseBackupScheduler.enqueuePrivacyPurgeBestEffort()
    }

    suspend operator fun invoke(forceImageDeletion: Boolean = false): RetentionSweepReport =
        PROCESS_MUTEX.withLock {
            val requestTime = appClock.now()
            if (forceImageDeletion) {
                // El checkpoint precede todo borrado: una interrupción nunca olvida la orden.
                appConfigurationRepository.requestForceImageDeletion(requestTime)
                // El wake-up se confirma inmediatamente después del checkpoint, antes de tocar
                // Room/filesystem. Así cualquier fallo posterior conserva una pasada inmediata;
                // el arranque y el periódico cubren además el mínimo intervalo entre ambos stores.
                privacyMaintenanceScheduler?.enqueueImmediate()
            }
            val persistedCutoff = appConfigurationRepository.forceImageDeletionRequestedAt()
            // Los defaults de test/adaptadores legacy no persisten; la invocación directa sigue
            // cubriendo su snapshot actual sin convertir capturas futuras en candidatos.
            val forceDeletionCutoff = persistedCutoff ?: requestTime.takeIf { forceImageDeletion }
            val effectiveForceDeletion = forceDeletionCutoff != null
            val report = runMaintenance(effectiveForceDeletion, forceDeletionCutoff)
            val forceDeletionStillPending =
                effectiveForceDeletion && report.hasRetryableForceDeletionWork
            if (effectiveForceDeletion && !forceDeletionStillPending) {
                // Fallar aquí debe llegar al worker como retry: devolver SUCCESS con el
                // checkpoint vivo haría que una ejecución futura repitiera la intención.
                appConfigurationRepository.clearForceImageDeletionRequest()
            }
            report.copy(forceDeletionStillPending = forceDeletionStillPending)
        }

    private suspend fun runMaintenance(
        forceImageDeletion: Boolean,
        forceDeletionCutoff: Instant?,
    ): RetentionSweepReport {
        val now = appClock.now()
        val failedSteps = mutableSetOf<PrivacyMaintenanceStep>()
        val retryableFailedSteps = mutableSetOf<PrivacyMaintenanceStep>()
        // Independiente de Room: seguro aunque la lectura de IDs falle después.
        val staleImports = bestEffortSweep(
            PrivacyMaintenanceStep.STALE_IMPORTS,
            failedSteps,
            retryableFailedSteps,
        ) {
            retentionFileSweep.sweepStaleImports(now, forceDeletionCutoff)
        }.deleted
        val staleCacheEntries = bestEffortSweep(
            PrivacyMaintenanceStep.STALE_CACHE,
            failedSteps,
            retryableFailedSteps,
        ) {
            retentionFileSweep.sweepStaleCache(now, forceDeletionCutoff)
        }.deleted
        val stalePrivateTemps = bestEffortSweep(
            PrivacyMaintenanceStep.STALE_PRIVATE_TEMPS,
            failedSteps,
            retryableFailedSteps,
        ) {
            retentionFileSweep.sweepStalePrivateTemps(now, forceDeletionCutoff)
        }.deleted
        val orphanDocumentArtifacts = try {
            documentLifecycle.sweepOrphanedPreparedArtifacts().also { report ->
                if (report.failed > 0) {
                    failedSteps += PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS
                    retryableFailedSteps +=
                        PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failedSteps += PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS
            retryableFailedSteps += PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS
            DocumentUploadArtifactSweepReport()
        }

        val orphanDraftDirs = bestEffortSweep(
            PrivacyMaintenanceStep.ORPHAN_DRAFT_DIRECTORIES,
            failedSteps,
            retryableFailedSteps,
        ) {
            retentionFileSweep.sweepOrphanDraftImageDirs(
                now = now,
                existingDraftIds = invoiceDraftRepository.listAllDraftIds(),
                forceDeletionCutoff = forceDeletionCutoff,
                pathIsReferencedAnywhere = invoiceDraftRepository::isImagePathReferenced,
            ) { draftId ->
                invoiceDraftRepository.findDraft(draftId) != null
            }
        }.deleted
        val committedOcrRuns = if (forceImageDeletion) {
            0
        } else {
            bestEffortSweep(
                PrivacyMaintenanceStep.COMMITTED_OCR_RUNS,
                failedSteps,
                retryableFailedSteps,
            ) {
                retentionFileSweep.sweepCommittedOcrVersions(
                    committedDraftIds = invoiceDraftRepository.listCommittedDraftIds(),
                ) { draftId ->
                    invoiceDraftRepository.findDraft(draftId)?.confirmedPurchaseId != null
                }
            }.deleted
        }
        val discardedOcrReport = if (forceImageDeletion) {
            OcrVersionSweepReport()
        } else {
            try {
                retentionFileSweep.sweepAllDraftOcrVersions(
                    existingDraftIds = invoiceDraftRepository.listAllDraftIds(),
                ) { draftId ->
                    val current = invoiceDraftRepository.findDraft(draftId)
                    when {
                        ocrRunActivityRegistry.isActive(draftId) ->
                            OcrVersionSweepDecision.RETRY_LATER
                        current == null -> OcrVersionSweepDecision.DELETE
                        current.status in DISCARDABLE_OCR_STATUSES ->
                            OcrVersionSweepDecision.DELETE
                        current.status == DraftStatus.OCR_PROCESSING ->
                            OcrVersionSweepDecision.DELETE
                        else -> OcrVersionSweepDecision.SKIP
                    }
                }.also { report ->
                    if (report.failed > 0) {
                        failedSteps += PrivacyMaintenanceStep.DISCARDED_OCR_RUNS
                    }
                    if (report.retryableFailed > 0) {
                        retryableFailedSteps += PrivacyMaintenanceStep.DISCARDED_OCR_RUNS
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedSteps += PrivacyMaintenanceStep.DISCARDED_OCR_RUNS
                retryableFailedSteps += PrivacyMaintenanceStep.DISCARDED_OCR_RUNS
                OcrVersionSweepReport()
            }
        }
        var forcedOcrReport = OcrVersionSweepReport()

        var afterOcrDeleteAttempted = 0
        var afterOcrDeleted = 0
        var afterOcrAlreadyAbsent = 0
        var afterOcrDeleteFailed = 0
        var afterOcrDeleteRetryableFailed = 0
        var draftDeleteAttempted = 0
        var draftDeleted = 0
        var draftAlreadyAbsent = 0
        var draftDeleteFailed = 0
        var draftDeleteRetryableFailed = 0
        var retentionDeleteAttempted = 0
        var purgeIntentAttempted = 0
        var purgeIntentDurable = 0
        var purgeIntentNotRequired = 0
        var purgeIntentFailed = 0
        var purgeIntentLegacyDestinationUnknown = 0
        var immediateArtifactDeleteFailed = 0
        var retentionDeleted = 0
        var retentionAlreadyAbsent = 0
        var retentionDeleteFailed = 0
        var retentionDeleteRetryableFailed = 0
        var encryptedMigrated = 0
        var encryptionAttempted = 0
        var encryptionAlreadySatisfied = 0
        var encryptionFailed = 0
        var encryptionRetryableFailed = 0
        val policy = appConfigurationRepository.current().imageRetentionPolicy
        if (forceImageDeletion) {
            val openDraftImages = invoiceDraftRepository.listOpenDraftImages()
                .filter { image ->
                    forceDeletionCutoff == null || !image.createdAt.isAfter(forceDeletionCutoff)
                }
            val outcomes = deleteDraftImagePaths(openDraftImages)
            draftDeleteAttempted = outcomes.size
            outcomes.forEach { outcome ->
                when (outcome.result) {
                    PrivateImageDeletionResult.DELETED -> draftDeleted++
                    PrivateImageDeletionResult.ALREADY_ABSENT -> draftAlreadyAbsent++
                    PrivateImageDeletionResult.REJECTED_UNSAFE,
                    PrivateImageDeletionResult.FAILED -> {
                        draftDeleteFailed++
                        if (outcome.retryable) draftDeleteRetryableFailed++
                    }
                }
            }
            forcedOcrReport = try {
                retentionFileSweep.sweepAllDraftOcrVersions(
                    existingDraftIds = invoiceDraftRepository.listAllDraftIds(),
                ) { draftId ->
                    val currentDraft = invoiceDraftRepository.findDraft(draftId)
                    when {
                        ocrRunActivityRegistry.isActive(draftId) ->
                            OcrVersionSweepDecision.RETRY_LATER
                        currentDraft == null -> OcrVersionSweepDecision.DELETE
                        forceDeletionCutoff != null &&
                            currentDraft.createdAt.isAfter(forceDeletionCutoff) ->
                            OcrVersionSweepDecision.SKIP
                        currentDraft.status == DraftStatus.OCR_PROCESSING -> {
                            if (ocrRunActivityRegistry.isActive(draftId)) {
                                OcrVersionSweepDecision.RETRY_LATER
                            } else {
                                // Estado residual tras muerte de proceso: no existe lector vivo.
                                OcrVersionSweepDecision.DELETE
                            }
                        }
                        else -> OcrVersionSweepDecision.DELETE
                    }
                }.also { report ->
                    if (report.failed > 0) {
                        failedSteps += PrivacyMaintenanceStep.FORCED_OCR_RUNS
                    }
                    if (report.retryableFailed > 0) {
                        retryableFailedSteps += PrivacyMaintenanceStep.FORCED_OCR_RUNS
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedSteps += PrivacyMaintenanceStep.FORCED_OCR_RUNS
                retryableFailedSteps += PrivacyMaintenanceStep.FORCED_OCR_RUNS
                OcrVersionSweepReport()
            }
        } else if (policy == ImageRetentionPolicy.AFTER_OCR) {
            val outcomes = deleteDraftImagePaths(
                invoiceDraftRepository.listImagesWithPublishedOcr(),
            )
            afterOcrDeleteAttempted = outcomes.size
            outcomes.forEach { outcome ->
                when (outcome.result) {
                    PrivateImageDeletionResult.DELETED -> afterOcrDeleted++
                    PrivateImageDeletionResult.ALREADY_ABSENT -> afterOcrAlreadyAbsent++
                    PrivateImageDeletionResult.REJECTED_UNSAFE,
                    PrivateImageDeletionResult.FAILED -> {
                        afterOcrDeleteFailed++
                        if (outcome.retryable) afterOcrDeleteRetryableFailed++
                    }
                }
            }
        }
        val unreferencedDraftImages = bestEffortSweep(
            PrivacyMaintenanceStep.UNREFERENCED_DRAFT_IMAGES,
            failedSteps,
            retryableFailedSteps,
        ) {
            retentionFileSweep.sweepUnreferencedDraftImages(
                now = now,
                existingDraftIds = invoiceDraftRepository.listAllDraftIds(),
                forceDeletionCutoff = forceDeletionCutoff,
                pathIsReferencedAnywhere = invoiceDraftRepository::isImagePathReferenced,
            ) { draftId ->
                val draft = invoiceDraftRepository.findDraft(draftId)
                    // El draft pudo desaparecer después del snapshot del barrido de directorios.
                    // Tratarlo como cero referencias permite retirar finales viejos bajo lock.
                    ?: return@sweepUnreferencedDraftImages emptySet()
                if (forceDeletionCutoff != null && draft.createdAt.isAfter(forceDeletionCutoff)) {
                    // null queda reservado para un skip deliberado por estar fuera del cutoff.
                    return@sweepUnreferencedDraftImages null
                }
                invoiceDraftRepository.observeImages(draftId).first()
                    .mapTo(mutableSetOf(), InvoiceImage::filePath)
            }
        }
        val survivors = mutableListOf<RetainedImageRef>()
        val retainedCoverageByPath = mutableMapOf<String, ImagePathReferenceCoverage>()
        val retainedRefs = purchaseReadRepository.listAllRetainedImagesForRetention()
        retainedRefs.groupBy(RetainedImageRef::relativeFilePath).forEach { (relativePath, pathRefs) ->
            val referenceCoverage = invoiceDraftRepository.referenceCoverage(
                relativePath,
                pathRefs.mapTo(mutableSetOf(), RetainedImageRef::imageId),
            )
            retainedCoverageByPath[relativePath] = referenceCoverage
            val decisions = pathRefs.map { ref ->
                val belongsToForceRequest = forceImageDeletion &&
                    (forceDeletionCutoff == null ||
                        !ref.sourceImageCreatedAt.isAfter(forceDeletionCutoff))
                val mustDelete = belongsToForceRequest ||
                    ImageRetentionDecider.shouldDeleteRetainedImage(policy, ref.postedAt, now)
                if (!mustDelete) {
                    survivors += ref
                    return@map RetentionDeletionDecision(ref, mustDelete = false)
                }

                retentionDeleteAttempted++
                purgeIntentAttempted++
                val authorization = authorizePurge(ref, now)
                when (authorization.result) {
                    DocumentPurgeIntentResult.DURABLE -> purgeIntentDurable++
                    DocumentPurgeIntentResult.DURABLE_ARTIFACT_RETRY_REQUIRED -> {
                        // La intención remota sí quedó durable. El derivado cifrado atascado
                        // permanece como trabajo local explícito aunque el original pueda salir.
                        purgeIntentDurable++
                        immediateArtifactDeleteFailed++
                        failedSteps += PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS
                        retryableFailedSteps +=
                            PrivacyMaintenanceStep.ORPHAN_DOCUMENT_UPLOAD_ARTIFACTS
                    }
                    DocumentPurgeIntentResult.NOT_REQUIRED -> purgeIntentNotRequired++
                    DocumentPurgeIntentResult.LEGACY_DESTINATION_UNKNOWN ->
                        purgeIntentLegacyDestinationUnknown++
                    null -> purgeIntentFailed++
                }
                RetentionDeletionDecision(
                    ref = ref,
                    mustDelete = true,
                    authorization = authorization,
                )
            }
            val candidates = decisions.filter(RetentionDeletionDecision::mustDelete)
            if (candidates.isEmpty()) return@forEach

            val everyAliasIsInScope = candidates.size == decisions.size
            val everyPurgeAuthorized = candidates.all { it.authorization?.mayDelete == true }
            val coversEveryRoomReference =
                referenceCoverage == ImagePathReferenceCoverage.COMPLETE
            val result = if (everyAliasIsInScope && everyPurgeAuthorized && coversEveryRoomReference) {
                deleteRetainedPath(relativePath)
            } else {
                PrivateImageDeletionResult.FAILED
            }
            when (result) {
                PrivateImageDeletionResult.DELETED -> retentionDeleted += candidates.size
                PrivateImageDeletionResult.ALREADY_ABSENT ->
                    retentionAlreadyAbsent += candidates.size
                PrivateImageDeletionResult.REJECTED_UNSAFE,
                PrivateImageDeletionResult.FAILED,
                -> {
                    val retryableAuthorizationBlock = candidates.any { decision ->
                        decision.authorization?.let { !it.mayDelete && it.failureIsRetryable } == true
                    }
                    candidates.forEach { decision ->
                        retentionDeleteFailed++
                        val retryable = when {
                            result == PrivateImageDeletionResult.REJECTED_UNSAFE -> false
                            decision.authorization?.mayDelete != true ->
                                decision.authorization?.failureIsRetryable == true
                            !everyAliasIsInScope -> false
                            !everyPurgeAuthorized -> retryableAuthorizationBlock
                            referenceCoverage == ImagePathReferenceCoverage.UNAVAILABLE -> true
                            !coversEveryRoomReference -> false
                            else -> true
                        }
                        if (retryable) retentionDeleteRetryableFailed++
                        // Falla cerrado respecto del borrado, pero no deja el archivo que
                        // sobrevive fuera de la migración/autenticación de cifrado local.
                        survivors += decision.ref
                    }
                }
            }
        }
        if (purgeIntentDurable > 0) {
            purchaseBackupScheduler.enqueuePrivacyPurgeBestEffort()
        }
        // Cifrado en reposo de las supervivientes. El encabezado solo clasifica el artefacto:
        // incluso un envelope existente debe pasar por encryptInPlace para autenticar su tag y
        // confirmar/reparar la publicación durable antes de declararlo satisfecho.
        survivors.groupBy(RetainedImageRef::relativeFilePath).forEach { (relativePath, pathRefs) ->
            val candidateCount = pathRefs.size
            encryptionAttempted += candidateCount
            when (retainedCoverageByPath[relativePath]) {
                ImagePathReferenceCoverage.BLOCKED_BY_OTHER_REFERENCE -> {
                    // Un draft abierto consume bytes planos directamente; cifrar el artefacto
                    // compartido lo volvería ilegible. Se conserva y se reporta sin retry loop.
                    encryptionFailed += candidateCount
                    return@forEach
                }
                ImagePathReferenceCoverage.UNAVAILABLE, null -> {
                    encryptionFailed += candidateCount
                    encryptionRetryableFailed += candidateCount
                    return@forEach
                }
                ImagePathReferenceCoverage.COMPLETE -> Unit
            }
            when (bestEffortMigrationState(relativePath)) {
                RetainedImageMigrationState.ENVELOPED -> {
                    if (bestEffortFlag {
                            retainedImageStore.encryptInPlace(relativePath)
                        }
                    ) {
                        encryptionAlreadySatisfied += candidateCount
                    } else {
                        encryptionFailed += candidateCount
                        encryptionRetryableFailed += candidateCount
                    }
                }
                RetainedImageMigrationState.ABSENT ->
                    encryptionAlreadySatisfied += candidateCount
                RetainedImageMigrationState.PLAINTEXT -> {
                    if (bestEffortFlag {
                            retainedImageStore.encryptInPlace(relativePath)
                        }
                    ) {
                        encryptedMigrated += candidateCount
                    } else {
                        encryptionFailed += candidateCount
                        encryptionRetryableFailed += candidateCount
                    }
                }

                // Un envelope corrupto no se sobrescribe y no mejorará con reintentos: sigue
                // visible en el reporte, pero no provoca un bucle infinito de WorkManager.
                RetainedImageMigrationState.CORRUPT -> encryptionFailed += candidateCount
                RetainedImageMigrationState.UNAVAILABLE -> {
                    encryptionFailed += candidateCount
                    encryptionRetryableFailed += candidateCount
                }
            }
        }
        return RetentionSweepReport(
            staleImports = staleImports,
            staleCacheEntries = staleCacheEntries,
            stalePrivateTemps = stalePrivateTemps,
            orphanDraftDirs = orphanDraftDirs,
            unreferencedDraftImagesDeleted = unreferencedDraftImages.deleted,
            committedOcrRuns = committedOcrRuns,
            discardedOcrRuns = discardedOcrReport.deleted,
            afterOcrDeleteAttempted = afterOcrDeleteAttempted,
            afterOcrDeleted = afterOcrDeleted,
            afterOcrAlreadyAbsent = afterOcrAlreadyAbsent,
            afterOcrDeleteFailed = afterOcrDeleteFailed,
            afterOcrDeleteRetryableFailed = afterOcrDeleteRetryableFailed,
            draftDeleteAttempted = draftDeleteAttempted,
            draftDeleted = draftDeleted,
            draftAlreadyAbsent = draftAlreadyAbsent,
            draftDeleteFailed = draftDeleteFailed,
            draftDeleteRetryableFailed = draftDeleteRetryableFailed,
            retentionDeleteAttempted = retentionDeleteAttempted,
            purgeIntentAttempted = purgeIntentAttempted,
            purgeIntentDurable = purgeIntentDurable,
            purgeIntentNotRequired = purgeIntentNotRequired,
            purgeIntentFailed = purgeIntentFailed,
            purgeIntentLegacyDestinationUnknown = purgeIntentLegacyDestinationUnknown,
            retentionDeleted = retentionDeleted,
            retentionAlreadyAbsent = retentionAlreadyAbsent,
            retentionDeleteFailed = retentionDeleteFailed,
            retentionDeleteRetryableFailed = retentionDeleteRetryableFailed,
            encryptedMigrated = encryptedMigrated,
            encryptionAttempted = encryptionAttempted,
            encryptionAlreadySatisfied = encryptionAlreadySatisfied,
            encryptionFailed = encryptionFailed,
            encryptionRetryableFailed = encryptionRetryableFailed,
            failedSteps = failedSteps.toSet(),
            retryableFailedSteps = retryableFailedSteps.toSet(),
            forcedOcrDeleteAttempted = forcedOcrReport.attempted,
            forcedOcrDeleted = forcedOcrReport.deleted,
            forcedOcrAlreadyAbsent = forcedOcrReport.alreadyAbsent,
            forcedOcrDeleteFailed = forcedOcrReport.failed,
            forcedOcrDeleteRetryableFailed = forcedOcrReport.retryableFailed,
            orphanDocumentArtifactsDeleted = orphanDocumentArtifacts.deleted,
            orphanDocumentArtifactsFailed =
                orphanDocumentArtifacts.failed + immediateArtifactDeleteFailed,
        )
    }

    private suspend fun authorizePurge(
        ref: RetainedImageRef,
        requestedAt: Instant,
    ): PurgeAuthorization {
        val result = try {
            documentLifecycle.ensurePurge(
                businessId = ref.businessId,
                image = ref,
                requestedAt = requestedAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return PurgeAuthorization(result)
    }

    private data class PurgeAuthorization(
        val result: DocumentPurgeIntentResult?,
    ) {
        val mayDelete: Boolean
            get() = result == DocumentPurgeIntentResult.DURABLE ||
                result == DocumentPurgeIntentResult.DURABLE_ARTIFACT_RETRY_REQUIRED ||
                result == DocumentPurgeIntentResult.NOT_REQUIRED
        val isDurable: Boolean
            get() = result == DocumentPurgeIntentResult.DURABLE ||
                result == DocumentPurgeIntentResult.DURABLE_ARTIFACT_RETRY_REQUIRED
        val failureIsRetryable: Boolean
            get() = result == null
    }

    private data class RetentionDeletionDecision(
        val ref: RetainedImageRef,
        val mustDelete: Boolean,
        val authorization: PurgeAuthorization? = null,
    )

    private companion object {
        /** Serializa worker periódico, worker inmediato y UI aunque Hilt cree más de una instancia. */
        val PROCESS_MUTEX = SuspendMutex()
        val DISCARDABLE_OCR_STATUSES = setOf(
            DraftStatus.CREATED,
            DraftStatus.CAPTURED,
            DraftStatus.ERROR,
        )
    }

    private suspend fun bestEffortSweep(
        step: PrivacyMaintenanceStep,
        failedSteps: MutableSet<PrivacyMaintenanceStep>,
        retryableFailedSteps: MutableSet<PrivacyMaintenanceStep>,
        block: suspend () -> PrivateFileSweepReport,
    ): PrivateFileSweepReport = try {
        block().also { report ->
            if (report.failed > 0) failedSteps += step
            if (report.retryableFailed > 0) retryableFailedSteps += step
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failedSteps += step
        retryableFailedSteps += step
        PrivateFileSweepReport()
    }

    private suspend fun bestEffortFlag(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /** Una excepción de filesystem no puede reclasificar una purga remota ya confirmada. */
    private suspend fun deleteRetainedPath(relativePath: String): PrivateImageDeletionResult = try {
        retainedImageStore.delete(relativePath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PrivateImageDeletionResult.FAILED
    }

    private suspend fun bestEffortMigrationState(
        relativePath: String,
    ): RetainedImageMigrationState = try {
        retainedImageStore.encryptionMigrationState(relativePath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RetainedImageMigrationState.UNAVAILABLE
    }

    private suspend fun encryptRetainedBestEffort(relativePath: String) {
        try {
            retainedImageStore.encryptInPlace(relativePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Mejor esfuerzo; el worker ya quedó despertado y la pasada periódica persiste.
        }
    }

    /**
     * Autoriza el borrado por artefacto, no solo por fila. Los paths canónicos nuevos son
     * exclusivos por imageId, pero una base migrada puede contener aliases. Un alias fuera del
     * snapshot queda como fallo visible no reintentable: repetir no lo vuelve seguro y, durante
     * un borrado global, no debe mantener eternamente vivo el checkpoint.
     */
    private suspend fun deleteDraftImagePaths(
        images: List<InvoiceImage>,
    ): List<DraftPathDeletionOutcome> {
        if (images.isEmpty()) return emptyList()
        val groups = images.groupBy(InvoiceImage::filePath)
        val outcomes = mutableMapOf<String, DraftPathDeletionOutcome>()
        val safePaths = mutableListOf<String>()
        groups.forEach { (path, scopedImages) ->
            val coverage = invoiceDraftRepository.referenceCoverage(
                path,
                scopedImages.mapTo(mutableSetOf(), InvoiceImage::imageId),
            )
            when (coverage) {
                ImagePathReferenceCoverage.UNAVAILABLE -> outcomes[path] = DraftPathDeletionOutcome(
                    result = PrivateImageDeletionResult.FAILED,
                    retryable = true,
                )
                ImagePathReferenceCoverage.BLOCKED_BY_OTHER_REFERENCE ->
                    outcomes[path] = DraftPathDeletionOutcome(
                        result = PrivateImageDeletionResult.FAILED,
                        retryable = false,
                    )
                ImagePathReferenceCoverage.COMPLETE -> safePaths += path
            }
        }
        val safeResults = if (safePaths.isEmpty()) {
            emptyList()
        } else {
            try {
                draftFileStore.deleteFiles(safePaths)
                    .takeIf { results -> results.size == safePaths.size }
                    ?: List(safePaths.size) { PrivateImageDeletionResult.FAILED }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                List(safePaths.size) { PrivateImageDeletionResult.FAILED }
            }
        }
        safePaths.zip(safeResults).forEach { (path, result) ->
            outcomes[path] = DraftPathDeletionOutcome(
                result = result,
                retryable = result == PrivateImageDeletionResult.FAILED,
            )
        }
        return groups.keys.map { path -> checkNotNull(outcomes[path]) }
    }

    private data class DraftPathDeletionOutcome(
        val result: PrivateImageDeletionResult,
        val retryable: Boolean,
    )
}

private val PrivateImageDeletionResult.confirmsAbsence: Boolean
    get() = this == PrivateImageDeletionResult.DELETED ||
        this == PrivateImageDeletionResult.ALREADY_ABSENT

/**
 * Exporta los datos del negocio activo a un modelo estable y versionado ([UserDataExport]):
 * negocio, catálogos completos, compras, saldos, libro de movimientos y auditoría publicada.
 * Sigue siendo un registro contable y no una descarga integral de la cuenta o de los borradores;
 * el modelo enumera expresamente sus exclusiones. Las fotos figuran solo como metadatos.
 */
class ExportUserDataUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val businessRepository: BusinessRepository,
    private val productRepository: ProductRepository,
    private val supplierRepository: SupplierRepository,
    private val unitRepository: UnitRepository,
    private val inventoryLocationRepository: InventoryLocationRepository,
    private val supplierProductAliasRepository: SupplierProductAliasRepository,
    private val inventoryReadRepository: InventoryReadRepository,
    private val purchaseReadRepository: PurchaseReadRepository,
    private val appClock: AppClock,
) {
    /**
     * Exige dos lecturas completas e idénticas antes de entregar el JSON. Room no expone una
     * transacción a través de estos puertos de dominio; esta verificación optimista evita mezclar
     * revisiones y falla cerrado si el libro continúa cambiando durante la exportación.
     */
    suspend operator fun invoke(): UserDataExport? {
        val exportedAt = appClock.now()
        var previous: UserDataExport? = null
        repeat(MAX_SNAPSHOT_READS) {
            val current = readSnapshot(exportedAt)
            if (current != null && current == previous) return current
            previous = current
        }
        return null
    }

    private suspend fun readSnapshot(exportedAt: Instant): UserDataExport? {
        val businessId = appConfigurationRepository.current().activeBusinessId
            ?: return UserDataExport(
                exportedAt = exportedAt,
                business = null,
                products = emptyList(),
                suppliers = emptyList(),
                units = emptyList(),
                inventoryLocations = emptyList(),
                supplierProductAliases = emptyList(),
                purchases = emptyList(),
                inventoryBalances = emptyList(),
                stockMovements = emptyList(),
                auditEvents = emptyList(),
            )
        val foundBusiness = businessRepository.findById(businessId) ?: return null
        val business = foundBusiness.let { found ->
            ExportedBusiness(
                businessId = found.businessId.value,
                legalName = found.legalName,
                ruc = found.ruc,
                tradeName = found.tradeName,
                status = found.status.name,
                createdAt = found.createdAt,
                updatedAt = found.updatedAt,
            )
        }
        val productModels = productRepository.observeForBusiness(businessId).first()
        val products = productModels.map { product ->
            ExportedProduct(
                productId = product.productId.value,
                unitId = product.unitId.value,
                locationId = product.locationId?.value,
                name = product.name,
                sku = product.sku,
                barcode = product.barcode,
                purchaseUnitId = product.purchaseUnitId?.value,
                purchaseFactor = product.purchaseFactor,
                status = product.status.name,
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
                version = product.version,
            )
        }.sortedBy(ExportedProduct::productId)
        val suppliers = supplierRepository.observeForBusiness(businessId).first().map { supplier ->
            ExportedSupplier(
                supplierId = supplier.supplierId.value,
                legalName = supplier.legalName,
                ruc = supplier.ruc,
                tradeName = supplier.tradeName,
                status = supplier.status.name,
                createdAt = supplier.createdAt,
                updatedAt = supplier.updatedAt,
                version = supplier.version,
            )
        }.sortedBy(ExportedSupplier::supplierId)
        val units = unitRepository.observeForBusiness(businessId).first().map { unit ->
            ExportedUnit(
                unitId = unit.unitId.value,
                code = unit.code,
                name = unit.name,
                symbol = unit.symbol,
                status = unit.status.name,
                createdAt = unit.createdAt,
                updatedAt = unit.updatedAt,
            )
        }.sortedBy(ExportedUnit::unitId)
        val locations = inventoryLocationRepository.observeForBusiness(businessId).first()
            .map { location ->
                ExportedInventoryLocation(
                    locationId = location.locationId.value,
                    name = location.name,
                    status = location.status.name,
                    createdAt = location.createdAt,
                    updatedAt = location.updatedAt,
                )
            }
            .sortedBy(ExportedInventoryLocation::locationId)
        val aliases = productModels.flatMap { product ->
            supplierProductAliasRepository.listForProduct(product.productId)
        }.filter { alias -> alias.businessId == businessId }
            .map { alias ->
                ExportedSupplierProductAlias(
                    aliasId = alias.aliasId.value,
                    supplierId = alias.supplierId.value,
                    productId = alias.productId.value,
                    alias = alias.alias,
                    createdAt = alias.createdAt,
                    updatedAt = alias.updatedAt,
                )
            }
            .sortedBy(ExportedSupplierProductAlias::aliasId)
        val purchaseDetails = mutableListOf<PurchaseReadDetail>()
        purchaseReadRepository.observePurchases(businessId).first().forEach { summary ->
            val detail = purchaseReadRepository
                .observePurchase(businessId, summary.purchaseId)
                .first()
                ?: return null
            purchaseDetails += detail
        }
        purchaseDetails.sortBy { detail -> detail.summary.purchaseId.value }
        val purchases = purchaseDetails.map { detail -> detail.toExported() }
        val purchaseLineByMovementId = purchaseDetails.flatMap { detail ->
            detail.movements.map { movement -> movement.movementId to movement.purchaseLineId }
        }.toMap()
        val inventoryItems = inventoryReadRepository.observeInventory(businessId).first()
            .sortedBy { item -> item.productId.value }
        val balances = inventoryItems.flatMap { item ->
            item.positions.map { position ->
                ExportedInventoryBalance(
                    productId = item.productId.value,
                    locationId = position.locationId.value,
                    quantityOnHand = position.quantityOnHand,
                    averageUnitCost = position.averageUnitCost.amount,
                    currency = position.averageUnitCost.currency.value,
                    version = position.version,
                    updatedAt = position.updatedAt,
                    alerts = position.alerts.map { it.name }.sorted(),
                )
            }
        }.sortedWith(compareBy(ExportedInventoryBalance::productId).thenBy { it.locationId })
        val stockMovements = inventoryItems.flatMap { item ->
            val detail = inventoryReadRepository
                .observeProduct(businessId, item.productId)
                .first()
                ?: return null
            detail.movements.map { movement ->
                ExportedStockMovement(
                    movementId = movement.movementId,
                    productId = movement.productId.value,
                    locationId = movement.locationId.value,
                    type = movement.type.name,
                    quantityDelta = movement.quantityDelta,
                    unitCost = movement.unitCost?.amount,
                    currency = movement.unitCost?.currency?.value,
                    purchaseId = movement.purchaseId?.value,
                    purchaseLineId = purchaseLineByMovementId[movement.movementId],
                    purchaseDocumentNumber = movement.purchaseDocumentNumber,
                    occurredAt = movement.occurredAt,
                    createdAt = movement.createdAt,
                    alerts = movement.alerts.map { it.name }.sorted(),
                )
            }
        }.sortedWith(
            compareBy(ExportedStockMovement::occurredAt)
                .thenBy(ExportedStockMovement::createdAt)
                .thenBy(ExportedStockMovement::movementId),
        )
        val auditEvents = purchaseReadRepository.listAuditEvents(businessId).map { event ->
            ExportedAuditEvent(
                auditEventId = event.auditEventId,
                purchaseId = event.purchaseId?.value,
                eventType = event.eventType.name,
                entityType = event.entityType,
                entityId = event.entityId,
                occurredAt = event.occurredAt,
            )
        }.sortedWith(
            compareBy(ExportedAuditEvent::occurredAt).thenBy(ExportedAuditEvent::auditEventId),
        )
        return UserDataExport(
            exportedAt = exportedAt,
            business = business,
            products = products,
            suppliers = suppliers,
            units = units,
            inventoryLocations = locations,
            supplierProductAliases = aliases,
            purchases = purchases,
            inventoryBalances = balances,
            stockMovements = stockMovements,
            auditEvents = auditEvents,
        )
    }

    private companion object {
        /** Dos iguales son el mínimo; cuatro lecturas permiten estabilizar tras una mutación. */
        const val MAX_SNAPSHOT_READS = 4
    }

    private fun PurchaseReadDetail.toExported(): ExportedPurchase = ExportedPurchase(
        purchaseId = summary.purchaseId.value,
        sourceDraftId = summary.sourceDraftId.value,
        supplierId = supplierId.value,
        supplierRuc = summary.supplierRuc,
        supplierLegalName = summary.supplierLegalName,
        documentType = summary.documentType.name,
        documentSeries = summary.documentSeries,
        documentNumber = summary.documentNumber,
        issueDate = summary.issueDate,
        currency = summary.currency,
        subtotal = subtotal,
        tax = tax,
        otherCharges = otherCharges,
        adjustment = adjustment,
        adjustmentReason = adjustmentReason,
        total = summary.total,
        status = summary.status.name,
        postedAt = summary.postedAt,
        preparedLogicalHash = preparedLogicalHash,
        acceptedWarnings = acceptedWarnings.sorted(),
        duplicateOverride = duplicateOverride?.let { override ->
            ExportedDuplicateOverride(
                existingPurchaseId = override.existingPurchaseId.value,
                reason = override.reason,
                actorId = override.actorId,
                actorRole = override.actorRole.name,
            )
        },
        lines = lines.map { line ->
            ExportedPurchaseLine(
                purchaseLineId = line.purchaseLineId,
                position = line.position,
                productId = line.productId.value,
                productName = line.productName,
                unitId = line.unitId.value,
                description = line.description,
                rawText = line.rawText,
                quantity = line.quantity,
                unitCode = line.unitCode,
                unitSymbol = line.unitSymbol,
                readUnitCost = line.readUnitCost.amount,
                appliedUnitCost = line.appliedUnitCost?.amount,
                inventoryQuantity = line.inventoryQuantity,
                discount = line.discount,
                taxMinorUnits = line.tax.minorUnits,
                totalMinorUnits = line.total.minorUnits,
                productProvenance = line.productProvenance.name,
                taxTreatment = line.taxTreatment?.name,
                taxEvidenceType = line.taxEvidence?.type?.name,
                taxEvidenceValue = line.taxEvidence?.value,
            )
        },
        retainedImages = images.map { image -> image.toExported() },
    )

    private fun PurchaseRetainedImage.toExported(): ExportedRetainedImage =
        ExportedRetainedImage(
            imageId = imageId.value,
            pageIndex = pageIndex,
            mimeType = mimeType,
            widthPx = widthPx,
            heightPx = heightPx,
            rotationDegrees = rotationDegrees,
            cropLeftFraction = cropLeftFraction,
            cropTopFraction = cropTopFraction,
            cropRightFraction = cropRightFraction,
            cropBottomFraction = cropBottomFraction,
        )
}

/**
 * Genera la exportación solo después de que el usuario eligió el documento SAF y confirma el
 * resultado real del cierre de escritura. El JSON/PII no pasa por SavedState ni por efectos UI.
 */
class WriteUserDataExportUseCase(
    private val exportUserData: ExportUserDataUseCase,
    private val writer: UserDataExportWriter,
) {
    suspend operator fun invoke(documentUri: String): UserDataExportWriteResult {
        val export = exportUserData() ?: return UserDataExportWriteResult(
            status = UserDataExportWriteStatus.SOURCE_UNAVAILABLE,
        )
        val status = writer.write(documentUri, export)
        return UserDataExportWriteResult(
            status = status,
            export = export.takeIf { status == UserDataExportWriteStatus.WRITTEN },
        )
    }
}
