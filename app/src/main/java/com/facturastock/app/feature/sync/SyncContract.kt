package com.facturastock.app.feature.sync

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.ReconciliationReport
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.usecase.SyncPullOutcome
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object SyncContract {
    @Immutable
    data class State(
        val session: AccountSession? = null,
        val activeBusinessId: BusinessId? = null,
        val backupEnabled: Boolean = false,
        val documentBackupEnabled: Boolean = false,
        val remoteLedgerAvailable: Boolean = false,
        val outbox: List<OutboxOperationView> = emptyList(),
        val purchases: List<PurchaseReadSummary> = emptyList(),
        val cursor: SyncCursor? = null,
        val remoteDescriptions: Map<String, RemoteDescriptionState> = emptyMap(),
        val workingOperationId: String? = null,
        val keepRemoteCandidate: OutboxOperationView? = null,
        val isPulling: Boolean = false,
        val pullOutcome: SyncPullOutcome? = null,
        val isComparing: Boolean = false,
        val report: ReconciliationReport? = null,
        val isRecordingReview: Boolean = false,
        val feedback: Feedback? = null,
        val failure: AccountError? = null,
        /** Fallo exclusivo de los Flows de sesión/configuración que abren la pantalla. */
        val initialLoadFailure: AccountError? = null,
    ) : UiState {
        val activeSession: AccountSession.Active?
            get() = session as? AccountSession.Active

        /** Enlace válido exclusivamente para el negocio local que está activo en Room. */
        val activeCloudLink: CloudBusinessLink?
            get() = activeSession?.link?.takeIf { link ->
                link.localBusinessId == activeBusinessId
            }

        val cloudBusinessId: BusinessId?
            get() = activeCloudLink?.cloudBusinessId

        /** La sincronización solo existe con libro remoto disponible, sesión plena y enlace. */
        val cloudLinked: Boolean
            get() = remoteLedgerAvailable && activeCloudLink != null

        val syncAvailable: Boolean
            get() = cloudLinked && backupEnabled

        /** Cola pendiente visible: lo que falta por respaldar (los conflictos van aparte). */
        val pendingOperations: List<OutboxOperationView>
            get() = outbox.filter { operation ->
                operation.entityType != DOCUMENT_ENTITY_TYPE &&
                    (
                        operation.status == OutboxOperationStatus.PENDING ||
                            operation.status == OutboxOperationStatus.PROCESSING ||
                            operation.status == OutboxOperationStatus.FAILED
                        )
            }

        val conflictOperations: List<OutboxOperationView>
            get() = outbox.filter {
                it.entityType != DOCUMENT_ENTITY_TYPE &&
                    it.status == OutboxOperationStatus.CONFLICT
            }

        /**
         * Último hecho durable por imagen. La versión 2 (purga) prevalece sobre la versión 1
         * (subida), incluso si los timestamps empatan tras una recuperación de proceso.
         */
        val documentOperations: List<DocumentOperation>
            get() = outbox.asSequence()
                .filter { it.entityType == DOCUMENT_ENTITY_TYPE }
                .groupBy(OutboxOperationView::entityId)
                .values
                .mapNotNull { operations ->
                    operations.maxWithOrNull(
                        compareBy<OutboxOperationView> { it.entityVersion }
                            .thenBy { it.updatedAt }
                            .thenBy { it.operationId },
                    )
                }
                .map { operation ->
                    DocumentOperation(
                        operation = operation,
                        state = operation.toDocumentSyncState(),
                    )
                }
                .sortedWith(
                    compareByDescending<DocumentOperation> { it.operation.updatedAt }
                        .thenBy { it.operation.entityId },
                )

        val isWorking: Boolean
            get() =
                workingOperationId != null || isPulling || isComparing || isRecordingReview

        val canPull: Boolean
            get() = syncAvailable && !isWorking

        val canCompare: Boolean
            get() = syncAvailable && !isWorking

        val canRecordReview: Boolean
            get() =
                syncAvailable &&
                    report?.businessId == activeCloudLink?.localBusinessId &&
                    !isWorking
    }

    /** Estado de la descripción remota de un conflicto, cargada bajo demanda por operación. */
    sealed interface RemoteDescriptionState {
        data object Loading : RemoteDescriptionState
        data class Loaded(val description: RemotePurchaseDescription?) : RemoteDescriptionState
        data object Unavailable : RemoteDescriptionState
    }

    enum class Feedback {
        OPERATION_REQUEUED,
        CONFLICT_RESOLVED,
        REVIEW_RECORDED,
    }

    @Immutable
    data class DocumentOperation(
        val operation: OutboxOperationView,
        val state: DocumentSyncState,
    )

    enum class DocumentSyncState {
        LOCAL_ONLY,
        PENDING_UPLOAD,
        UPLOADING,
        BACKED_UP,
        ERROR,
        PURGE_PENDING,
        DELETED_FROM_CLOUD,
    }

    sealed interface Action : UiAction {
        data class RetryFailedOperation(val operation: OutboxOperationView) : Action
        data class KeepRemoteRequested(val operation: OutboxOperationView) : Action
        data object KeepRemoteConfirmed : Action
        data class RetryConflictOperation(val operation: OutboxOperationView) : Action
        data class RemoteDescriptionRetry(val operation: OutboxOperationView) : Action
        data object SyncNow : Action
        data object CompareWithCloud : Action
        data object RecordReview : Action
        data object DismissDialogs : Action
        data object BackSelected : Action
        data object RetryInitialLoad : Action
    }

    sealed interface Effect : UiEffect {
        data object Back : Effect
    }
}

private fun OutboxOperationView.toDocumentSyncState(): SyncContract.DocumentSyncState =
    when (operationType) {
        OPERATION_TYPE_DOCUMENT_UPLOAD -> when (status) {
            OutboxOperationStatus.PENDING -> SyncContract.DocumentSyncState.PENDING_UPLOAD
            OutboxOperationStatus.PROCESSING -> SyncContract.DocumentSyncState.UPLOADING
            OutboxOperationStatus.COMPLETED -> SyncContract.DocumentSyncState.BACKED_UP
            OutboxOperationStatus.FAILED,
            OutboxOperationStatus.CONFLICT,
            -> SyncContract.DocumentSyncState.ERROR
            OutboxOperationStatus.RESOLVED -> SyncContract.DocumentSyncState.LOCAL_ONLY
        }
        OPERATION_TYPE_DOCUMENT_PURGE -> when (status) {
            OutboxOperationStatus.PENDING,
            OutboxOperationStatus.PROCESSING,
            -> SyncContract.DocumentSyncState.PURGE_PENDING
            OutboxOperationStatus.FAILED,
            OutboxOperationStatus.CONFLICT,
            -> SyncContract.DocumentSyncState.ERROR
            OutboxOperationStatus.COMPLETED,
            OutboxOperationStatus.RESOLVED,
            -> SyncContract.DocumentSyncState.DELETED_FROM_CLOUD
        }
        else -> SyncContract.DocumentSyncState.ERROR
    }

private const val DOCUMENT_ENTITY_TYPE = "DOCUMENT"
private const val OPERATION_TYPE_DOCUMENT_UPLOAD = "SYNC_DOCUMENT_UPLOAD"
private const val OPERATION_TYPE_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
