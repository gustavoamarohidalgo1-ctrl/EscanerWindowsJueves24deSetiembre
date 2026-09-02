package com.facturastock.app.data.repository

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.WorkflowRequest
import com.facturastock.app.domain.model.WorkflowSnapshot
import com.facturastock.app.domain.model.WorkflowStage
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.DraftWorkflowRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Solo fake explícito para pruebas antiguas; producción usa RoomDraftWorkflowRepository. */
class InMemoryDraftWorkflowRepository(
    private val uuidGenerator: UuidGenerator,
    private val clock: AppClock,
) : DraftWorkflowRepository {
    private val lock = Mutex()
    private val snapshots = mutableMapOf<DraftId, WorkflowSnapshot>()

    override suspend fun run(request: WorkflowRequest): WorkflowSnapshot = lock.withLock {
        if (request.stage == WorkflowStage.CREATED) {
            return@withLock createDraft(request)
        }

        val draftId = requireNotNull(request.draftId) {
            "draftId is required for ${request.stage}"
        }
        validateStageIdentifiers(request)
        val current = snapshots[draftId]

        // This first in-memory adapter can be entered from an ID already created by the
        // navigation scaffold. Persistent storage will own draft creation in the next step.
        if (current == null) {
            return@withLock snapshotFrom(request, draftId).also { snapshots[draftId] = it }
        }

        require(request.stage.ordinal >= current.stage.ordinal) {
            "Cannot move draft ${draftId.value} from ${current.stage} to ${request.stage}"
        }

        if (request.stage == current.stage) {
            return@withLock current
        }

        val updated = snapshotFrom(request, draftId, current)
        snapshots[draftId] = updated
        updated
    }

    private fun createDraft(request: WorkflowRequest): WorkflowSnapshot {
        require(request.draftId == null) { "CREATED must not receive a draftId" }
        require(request.captureId == null) { "CREATED must not receive a captureId" }
        require(request.lineId == null) { "CREATED must not receive a lineId" }

        val draftId = DraftId.from(uuidGenerator.newUuid())
        val snapshot = WorkflowSnapshot(
            draftId = draftId,
            stage = WorkflowStage.CREATED,
            updatedAt = clock.now(),
        )
        snapshots[draftId] = snapshot
        return snapshot
    }

    private fun validateStageIdentifiers(request: WorkflowRequest) {
        if (request.stage == WorkflowStage.IMAGE_CAPTURED) {
            requireNotNull(request.captureId) { "captureId is required for IMAGE_CAPTURED" }
        }
        if (request.stage == WorkflowStage.PRODUCTS_LINKED) {
            requireNotNull(request.lineId) { "lineId is required for PRODUCTS_LINKED" }
        }
    }

    private fun snapshotFrom(
        request: WorkflowRequest,
        draftId: DraftId,
        current: WorkflowSnapshot? = null,
    ): WorkflowSnapshot = WorkflowSnapshot(
        draftId = draftId,
        stage = request.stage,
        captureId = request.captureId ?: current?.captureId,
        lineId = request.lineId ?: current?.lineId,
        purchaseId = if (request.stage == WorkflowStage.CONFIRMED) {
            current?.purchaseId ?: PurchaseId.from(uuidGenerator.newUuid())
        } else {
            current?.purchaseId
        },
        updatedAt = clock.now(),
    )
}
