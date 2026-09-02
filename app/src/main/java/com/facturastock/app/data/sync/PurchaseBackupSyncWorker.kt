package com.facturastock.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.core.time.SystemAppClock
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.observability.toOperationalErrorCode
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.CatalogSyncBootstrapRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import com.facturastock.app.domain.repository.DisabledCatalogSyncBootstrapRepository
import com.facturastock.app.domain.repository.DisabledDocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueAtBestEffort
import com.facturastock.app.domain.repository.enqueuePrivacyPurgeAtBestEffort
import com.facturastock.app.domain.repository.enqueuePrivacyPurgeSuccessorAtBestEffort
import com.facturastock.app.domain.repository.enqueueSuccessorAtBestEffort
import com.facturastock.app.domain.usecase.OutboxPassResult
import com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCase
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * Worker del drenado de respaldo. Toda la decisión de negocio vive en
 * [ProcessPurchaseBackupOutboxUseCase]; aquí solo se traduce el resultado de cada pasada:
 * lote lleno o dependencia recién desbloqueada → otra pasada inmediata (con tope), cola
 * drenada → follow-up diferido si queda algún reintento programado o lease por vencer, fallo de
 * la pasada → `Result.retry()` y el backoff exponencial de WorkManager. La cancelación se
 * propaga sin convertirse en error.
 * La auditoría operacional usa [ProductionObservability]: es opt-in y no altera el resultado.
 *
 * Tras drenar la cola se intenta además el pull incremental del negocio enlazado
 * (push-then-pull): es una lectura réplica de mejor esfuerzo — si no hay sesión plena se
 * omite; un fallo permanente conserva el push y termina con éxito, mientras uno recuperable
 * conserva el push pero devuelve `Result.retry()` para programar otra pasada real.
 */
class PurchaseBackupSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processor: ProcessPurchaseBackupOutboxUseCase,
    private val scheduler: PurchaseBackupScheduler,
    private val observability: ProductionObservability,
    private val accountRepository: AccountRepository,
    private val membershipRepository: BusinessMembershipRepository,
    private val pullRemoteChanges: PullRemoteChangesUseCase,
    private val appConfiguration: AppConfigurationRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
    private val catalogBootstrap: CatalogSyncBootstrapRepository =
        DisabledCatalogSyncBootstrapRepository,
    private val documentLifecycle: DocumentBackupLifecycleRepository =
        DisabledDocumentBackupLifecycleRepository,
    private val appClock: AppClock = SystemAppClock(),
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val requestIsPurgeOnly = inputData.getBoolean(INPUT_PURGE_ONLY, false)
        val scheduledWakeAtMillis = inputData.getLong(
            INPUT_SCHEDULED_WAKE_AT_EPOCH_MILLIS,
            NO_SCHEDULED_WAKE,
        )
        var businessId: BusinessId? = null
        var catalogBootstrapBusinessId: BusinessId? = null
        var documentBootstrapKey: Pair<BusinessId, ImageRetentionPolicy>? = null
        var privacyAuthorization: PrivacyAuthorization? = null
        repeat(MAX_PASSES_PER_RUN) {
            currentCoroutineContext().ensureActive()
            // Lectura fresca antes de cada pasada: desactivar el respaldo corta una corrida
            // ya iniciada en el siguiente límite seguro. El procesador repite esta compuerta
            // entre operaciones para no reclamar todo un lote con una lectura obsoleta.
            val configuration = try {
                appConfiguration.current()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // DataStore también puede fallar por ENOSPC. WorkManager conserva el request.
                recordSync(
                    OperationalOutcome.RETRY_SCHEDULED,
                    businessId = businessId,
                    failure = failure,
                )
                return Result.retry()
            }
            businessId = configuration.activeBusinessId
            if (!configuration.documentBackupEnabled) {
                try {
                    // Es global y puramente local: ocurre antes de validar sesión/link y no omite
                    // uploads de negocios históricos solo porque hoy no estén activos en la UI.
                    documentLifecycle.withdrawAllOpenUploads(appClock.now())
                    // Si el opt-in vuelve durante esta misma corrida, relee Room. Los
                    // tombstones existentes impiden recrear candidatos ya retirados.
                    documentBootstrapKey = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    recordSync(
                        OperationalOutcome.RETRY_SCHEDULED,
                        businessId = businessId,
                        failure = failure,
                    )
                    return Result.retry()
                }
            }
            // Demo nunca emite datos comerciales, incluso si heredó el opt-in del negocio real
            // o existe un binding corrupto de una versión anterior. El mismo wake puede seguir
            // cumpliendo tombstones privacy-only autorizados del UID.
            val purgeOnly = requestIsPurgeOnly || !configuration.backupEnabled ||
                configuration.isDemoMode
            if (purgeOnly && privacyAuthorization == null) {
                val expectedUid = try {
                    (accountRepository.observeSession().first() as? AccountSession.Active)?.uid
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    recordSync(
                        outcome = OperationalOutcome.RETRY_SCHEDULED,
                        businessId = businessId,
                        failure = failure,
                    )
                    return Result.retry()
                }
                if (expectedUid != null) {
                    val targets = try {
                        when (val memberships = membershipRepository.listMyMemberships(expectedUid)) {
                            is DomainResult.Success -> memberships.value
                                // El backend exige capacidad de escritura tambien para purgar.
                                // Excluir READER evita que una fila denegada de X encabece y
                                // retrase las purgas de Y en el lote multi-tenant.
                                .filter { it.role.canSyncPurchases }
                                .map { it.businessId }
                                .toSet()
                            is DomainResult.Failure -> {
                                recordSync(
                                    outcome = OperationalOutcome.RETRY_SCHEDULED,
                                    businessId = businessId,
                                    errorCode = memberships.error.toOperationalErrorCode(),
                                )
                                return Result.retry()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        recordSync(
                            outcome = OperationalOutcome.RETRY_SCHEDULED,
                            businessId = businessId,
                            failure = failure,
                        )
                        return Result.retry()
                    }
                    privacyAuthorization = PrivacyAuthorization(expectedUid, targets)
                }
            }
            val privacyExpectedUid = privacyAuthorization?.uid
            val privacyTargets = privacyAuthorization?.targets.orEmpty()
            val targetCloudBusinessId = if (purgeOnly) {
                // El procesador usa el conjunto autorizado en una consulta `IN`; null aquí
                // no es una fila legacy sino el selector multi-tenant privacy-only.
                null
            } else {
                val pinnedTarget = try {
                    pinActiveCloudBusiness(configuration.activeBusinessId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    recordSync(
                        outcome = OperationalOutcome.RETRY_SCHEDULED,
                        businessId = businessId,
                        failure = failure,
                    )
                    return Result.retry()
                }
                when (pinnedTarget) {
                    is PinnedTarget.Available -> pinnedTarget.cloudBusinessId
                    PinnedTarget.NotLinked -> null
                    PinnedTarget.Blocked -> {
                        recordSync(
                            outcome = OperationalOutcome.FAILED,
                            businessId = businessId,
                            errorCode = OperationalErrorCode.INTEGRITY_CONFLICT,
                        )
                        return Result.success()
                    }
                }
            }
            if (!purgeOnly && configuration.documentBackupEnabled) {
                val activeBusinessId = configuration.activeBusinessId
                if (activeBusinessId != null) {
                    val policy = configuration.imageRetentionPolicy
                    val bootstrapKey = activeBusinessId to policy
                    if (bootstrapKey != documentBootstrapKey) {
                        try {
                            val requestedAt = appClock.now()
                            when (policy) {
                                ImageRetentionPolicy.AFTER_OCR,
                                ImageRetentionPolicy.AFTER_CONFIRM,
                                -> {
                                    // AFTER_OCR/AFTER_CONFIRM nunca conservan una fuente apta
                                    // para respaldo documental.
                                    documentBootstrapKey = bootstrapKey
                                }
                                ImageRetentionPolicy.KEEP -> {
                                    documentLifecycle.ensureRetainedUploads(
                                        businessId = activeBusinessId,
                                        postedAfterExclusive = null,
                                        requestedAt = requestedAt,
                                    )
                                    documentBootstrapKey = bootstrapKey
                                }
                                ImageRetentionPolicy.DAYS_30,
                                ImageRetentionPolicy.DAYS_90,
                                -> {
                                    val retentionDays = requireNotNull(policy.retentionDays)
                                    val postedAfterExclusive = requestedAt.minus(
                                        Duration.ofDays(retentionDays),
                                    )
                                    documentLifecycle.ensureRetainedUploads(
                                        businessId = activeBusinessId,
                                        postedAfterExclusive = postedAfterExclusive,
                                        requestedAt = requestedAt,
                                    )
                                    documentBootstrapKey = bootstrapKey
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // La preferencia ya es durable. Un reinicio repite este bootstrap
                            // idempotente antes de reclamar cualquier upload.
                            recordSync(
                                OperationalOutcome.RETRY_SCHEDULED,
                                businessId = businessId,
                                failure = failure,
                            )
                            return Result.retry()
                        }
                    }
                }
            }
            val activeBusinessId = configuration.activeBusinessId
            if (
                !purgeOnly && activeBusinessId != null &&
                activeBusinessId != catalogBootstrapBusinessId
            ) {
                try {
                    catalogBootstrap.ensurePendingSnapshots(activeBusinessId)
                    catalogBootstrapBusinessId = activeBusinessId
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // La semilla y cada snapshot son una transacción Room: ENOSPC nunca deja
                    // versiones rebasadas sin su operación durable.
                    recordSync(
                        OperationalOutcome.RETRY_SCHEDULED,
                        businessId = businessId,
                        failure = failure,
                    )
                    return Result.retry()
                }
            }
            val pass = try {
                if ((!purgeOnly && targetCloudBusinessId == null) ||
                    (purgeOnly && (
                        privacyExpectedUid == null || privacyTargets.orEmpty().isEmpty()
                        ))
                ) {
                    OutboxPassResult.TransportUnavailable
                } else processor(
                    targetCloudBusinessId = targetCloudBusinessId,
                    onlyDocumentPurges = purgeOnly,
                    authorizedDocumentPurgeTargets = if (purgeOnly) privacyTargets else null,
                    processingAllowed = {
                        // Una request que ya salió no se puede retirar; esta lectura ocurre
                        // antes de reclamar la siguiente fila durable.
                        purgeOnly || appConfiguration.current().backupEnabled
                    },
                    targetAvailable = {
                        if (purgeOnly) {
                            privacySessionStillActive(requireNotNull(privacyExpectedUid))
                        } else {
                            pinnedTargetStillActive(
                                localBusinessId = requireNotNull(activeBusinessId),
                                cloudBusinessId = requireNotNull(targetCloudBusinessId),
                            )
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordSync(
                    outcome = OperationalOutcome.RETRY_SCHEDULED,
                    businessId = businessId,
                    failure = failure,
                )
                return Result.retry()
            }
            currentCoroutineContext().ensureActive()
            when (pass) {
                OutboxPassResult.TransportUnavailable -> {
                    recordSync(
                        outcome = OperationalOutcome.SKIPPED,
                        businessId = businessId,
                        errorCode = OperationalErrorCode.TRANSPORT_UNAVAILABLE,
                    )
                    return Result.success()
                }
                is OutboxPassResult.Drained -> {
                    if (pass.targetTemporarilyUnavailable) {
                        // Un logout o cambio normal de negocio no es un fallo de la operación:
                        // el procesador ya dejó cualquier claim sin enviar en PENDING.
                        recordSync(
                            outcome = OperationalOutcome.SKIPPED,
                            businessId = businessId,
                            errorCode = OperationalErrorCode.TRANSPORT_UNAVAILABLE,
                        )
                        return Result.success()
                    }
                    if (pass.reachedBatchLimit) return@repeat
                    if (purgeOnly) {
                        val followUpScheduled = pass.nextWakeupAt?.let {
                            if (it.toEpochMilli() == scheduledWakeAtMillis) {
                                scheduler.enqueuePrivacyPurgeSuccessorAtBestEffort(
                                    attemptAt = it,
                                    runningWorkId = id.toString(),
                                )
                            } else {
                                scheduler.enqueuePrivacyPurgeAtBestEffort(it)
                            }
                        } ?: true
                        val purgeOutcome = when {
                            pass.conflicts > 0 ->
                                OperationalOutcome.CONFLICT to
                                    OperationalErrorCode.INTEGRITY_CONFLICT
                            pass.permanentlyFailed > 0 ->
                                OperationalOutcome.FAILED to
                                    OperationalErrorCode.PERMANENT_FAILURE
                            pass.scheduledRetries > 0 ->
                                OperationalOutcome.RETRY_SCHEDULED to
                                    OperationalErrorCode.TRANSIENT_FAILURE
                            else -> OperationalOutcome.SUCCEEDED to null
                        }
                        recordSync(
                            outcome = purgeOutcome.first,
                            businessId = businessId,
                            errorCode = purgeOutcome.second,
                        )
                        if (!followUpScheduled) return Result.retry()
                        return Result.success()
                    }
                    val pullEnabled = try {
                        appConfiguration.current().backupEnabled
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        recordSync(
                            OperationalOutcome.RETRY_SCHEDULED,
                            businessId = businessId,
                            failure = failure,
                        )
                        return Result.retry()
                    }
                    if (!pullEnabled) {
                        // El push ya confirmado se conserva; el pull aún no empezó.
                        recordSync(OperationalOutcome.SKIPPED, businessId = businessId)
                        return Result.success()
                    }
                    val pullLocalBusinessId = activeBusinessId
                    val pullCloudBusinessId = targetCloudBusinessId
                    if (pullLocalBusinessId == null || pullCloudBusinessId == null) {
                        recordSync(
                            outcome = OperationalOutcome.SKIPPED,
                            businessId = businessId,
                            errorCode = OperationalErrorCode.TRANSPORT_UNAVAILABLE,
                        )
                        return Result.success()
                    }
                    val pullAttempt = pullLinkedBusinessLedger(
                        expectedLocalBusinessId = pullLocalBusinessId,
                        expectedCloudBusinessId = pullCloudBusinessId,
                    )
                    val followUpScheduled = pass.nextWakeupAt?.let {
                        if (it.toEpochMilli() == scheduledWakeAtMillis) {
                            scheduler.enqueueSuccessorAtBestEffort(
                                attemptAt = it,
                                runningWorkId = id.toString(),
                            )
                        } else {
                            scheduler.enqueueAtBestEffort(it)
                        }
                    } ?: true
                    if (!followUpScheduled) {
                        // El nextAttemptAt sigue en Room; RETRY conserva el request actual
                        // cuando la base de WorkManager no pudo crear el follow-up (ENOSPC).
                        recordSync(
                            outcome = OperationalOutcome.RETRY_SCHEDULED,
                            businessId = businessId,
                            errorCode = OperationalErrorCode.STORAGE_INSUFFICIENT_SPACE,
                        )
                        return Result.retry()
                    }
                    val outboxOutcome = when {
                        pass.conflicts > 0 ->
                            OperationalOutcome.CONFLICT to OperationalErrorCode.INTEGRITY_CONFLICT
                        pass.permanentlyFailed > 0 ->
                            OperationalOutcome.FAILED to OperationalErrorCode.PERMANENT_FAILURE
                        pass.scheduledRetries > 0 ->
                            OperationalOutcome.RETRY_SCHEDULED to
                                OperationalErrorCode.TRANSIENT_FAILURE
                        else -> OperationalOutcome.SUCCEEDED to null
                    }
                    if (pullAttempt is PullAttempt.Failed) {
                        // Si la outbox también tuvo una señal relevante se conserva antes de
                        // informar el fallo del pull; ambos representan fases distintas.
                        if (outboxOutcome.first != OperationalOutcome.SUCCEEDED) {
                            recordSync(
                                outcome = outboxOutcome.first,
                                businessId = businessId,
                                errorCode = outboxOutcome.second,
                            )
                        }
                        recordSync(
                            outcome = pullAttempt.outcome,
                            businessId = businessId,
                            errorCode = pullAttempt.errorCode,
                        )
                        return if (pullAttempt.outcome == OperationalOutcome.RETRY_SCHEDULED) {
                            Result.retry()
                        } else {
                            Result.success()
                        }
                    }
                    recordSync(
                        outcome = outboxOutcome.first,
                        businessId = businessId,
                        errorCode = outboxOutcome.second,
                    )
                    return Result.success()
                }
            }
        }
        // RETRY conserva el mismo request y aplica el backoff de WorkManager sin añadir otro
        // eslabón a la cadena única ni perder la cola durable.
        recordSync(
            outcome = OperationalOutcome.RETRY_SCHEDULED,
            businessId = businessId,
            errorCode = OperationalErrorCode.TRANSIENT_FAILURE,
        )
        return Result.retry()
    }

    /** Fija una sola vez el destino antes de crear candidatos o reclamar cualquier fila. */
    private suspend fun pinActiveCloudBusiness(localBusinessId: BusinessId?): PinnedTarget {
        val session = accountRepository.observeSession().first() as? AccountSession.Active
            ?: return PinnedTarget.NotLinked
        val link = session.link ?: return PinnedTarget.NotLinked
        if (localBusinessId == null || link.localBusinessId != localBusinessId) {
            return PinnedTarget.NotLinked
        }
        return when (
            cloudBusinessBindings.bindOnce(
                localBusinessId = localBusinessId,
                cloudBusinessId = link.cloudBusinessId,
                boundAt = appClock.now(),
            )
        ) {
            CloudBusinessBindingResult.Bound,
            CloudBusinessBindingResult.AlreadyBound,
            -> PinnedTarget.Available(link.cloudBusinessId)
            CloudBusinessBindingResult.LocalBusinessAlreadyBound,
            CloudBusinessBindingResult.CloudBusinessAlreadyBound,
            CloudBusinessBindingResult.LegacyDestinationUnknown,
            CloudBusinessBindingResult.AmbiguousInventoryLocations,
            -> PinnedTarget.Blocked
        }
    }

    /**
     * Compuerta fresca por operación. Room conserva el enlace inmutable, pero la sesión y el
     * negocio activo pueden cambiar mientras un worker sigue vivo; ambos deben seguir
     * coincidiendo exactamente con el par L→X fijado al iniciar la pasada.
     */
    private suspend fun pinnedTargetStillActive(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        requireBackupEnabled: Boolean = false,
    ): Boolean {
        val configuration = appConfiguration.current()
        if (configuration.activeBusinessId != localBusinessId) return false
        if (requireBackupEnabled && !configuration.backupEnabled) return false
        val session = accountRepository.observeSession().first() as? AccountSession.Active
            ?: return false
        val link = session.link ?: return false
        if (link.localBusinessId != localBusinessId || link.cloudBusinessId != cloudBusinessId) {
            return false
        }
        return cloudBusinessBindings.matches(localBusinessId, cloudBusinessId)
    }

    /** Privacy no depende del negocio activo ni del link de UI; solo de la misma cuenta viva. */
    private suspend fun privacySessionStillActive(expectedUid: String): Boolean =
        (accountRepository.observeSession().first() as? AccountSession.Active)?.uid == expectedUid

    private suspend fun recordSync(
        outcome: OperationalOutcome,
        businessId: BusinessId? = null,
        errorCode: OperationalErrorCode? = null,
        failure: Throwable? = null,
    ) {
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.BACKUP_SYNC,
                outcome = outcome,
                identifiers = InternalIdentifiers(businessId = businessId),
                errorCode = errorCode,
            ),
            failure,
        )
    }

    /**
     * Pull de mejor esfuerzo tras el push: solo con sesión plena y enlace vigente. Cualquier
     * fallo permanente mantiene el push ya confirmado y termina en éxito; uno recuperable se
     * devuelve como `Result.retry()` para que WorkManager programe una pasada real.
     */
    private suspend fun pullLinkedBusinessLedger(
        expectedLocalBusinessId: BusinessId,
        expectedCloudBusinessId: BusinessId,
    ): PullAttempt {
        val targetStillActive = try {
            pinnedTargetStillActive(
                localBusinessId = expectedLocalBusinessId,
                cloudBusinessId = expectedCloudBusinessId,
                requireBackupEnabled = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return PullAttempt.Failed(
                outcome = OperationalOutcome.RETRY_SCHEDULED,
                errorCode = failure.toOperationalErrorCode(),
            )
        }
        if (!targetStillActive) return PullAttempt.Skipped
        val session = try {
            accountRepository.observeSession().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return PullAttempt.Failed(
                outcome = OperationalOutcome.RETRY_SCHEDULED,
                errorCode = failure.toOperationalErrorCode(),
            )
        }
        val link = (session as? AccountSession.Active)?.link ?: return PullAttempt.Skipped
        if (
            link.localBusinessId != expectedLocalBusinessId ||
            link.cloudBusinessId != expectedCloudBusinessId
        ) {
            return PullAttempt.Skipped
        }
        return try {
            when (
                val pulled = pullRemoteChanges(
                    localBusinessId = link.localBusinessId,
                    cloudBusinessId = link.cloudBusinessId,
                )
            ) {
                is DomainResult.Success -> PullAttempt.Succeeded
                is DomainResult.Failure -> {
                    val errorCode = pulled.error.toOperationalErrorCode()
                    PullAttempt.Failed(
                        outcome = if (errorCode.isRetryablePullFailure()) {
                            OperationalOutcome.RETRY_SCHEDULED
                        } else {
                            OperationalOutcome.FAILED
                        },
                        errorCode = errorCode,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Mejor esfuerzo: un resultado Failure o una excepción no rompen el worker.
            PullAttempt.Failed(
                outcome = OperationalOutcome.RETRY_SCHEDULED,
                errorCode = failure.toOperationalErrorCode(),
            )
        }
    }

    private sealed interface PullAttempt {
        data object Succeeded : PullAttempt
        data object Skipped : PullAttempt
        data class Failed(
            val outcome: OperationalOutcome,
            val errorCode: OperationalErrorCode,
        ) : PullAttempt
    }

    private sealed interface PinnedTarget {
        data class Available(val cloudBusinessId: BusinessId) : PinnedTarget
        data object NotLinked : PinnedTarget
        data object Blocked : PinnedTarget
    }

    private data class PrivacyAuthorization(
        val uid: String,
        val targets: Set<BusinessId>,
    )

    companion object {
        const val MAX_PASSES_PER_RUN = 10
        const val INPUT_PURGE_ONLY = "purchase_backup.purge_only"
        const val INPUT_SCHEDULED_WAKE_AT_EPOCH_MILLIS =
            "purchase_backup.scheduled_wake_at_epoch_millis"
        private const val NO_SCHEDULED_WAKE = Long.MIN_VALUE
    }
}

private fun OperationalErrorCode.isRetryablePullFailure(): Boolean = when (this) {
    OperationalErrorCode.ACCOUNT_UNAVAILABLE,
    OperationalErrorCode.ACCOUNT_NETWORK_UNAVAILABLE,
    OperationalErrorCode.STORAGE_UNAVAILABLE,
    OperationalErrorCode.STORAGE_INSUFFICIENT_SPACE,
    OperationalErrorCode.TRANSPORT_UNAVAILABLE,
    OperationalErrorCode.TRANSIENT_FAILURE,
    OperationalErrorCode.UNEXPECTED,
    -> true
    else -> false
}
