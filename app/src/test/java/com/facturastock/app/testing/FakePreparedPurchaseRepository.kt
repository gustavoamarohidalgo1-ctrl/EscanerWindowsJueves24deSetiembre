package com.facturastock.app.testing

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import com.facturastock.app.domain.repository.PublishPreparedPurchaseResult
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fake en memoria de la instantánea de compra preparada. Replica la semántica transaccional de
 * Room: publicar exige el CAS `NEEDS_REVIEW → READY_TO_POST` sobre el agregado de borradores y
 * reabrir devuelve el borrador a edición borrando la instantánea.
 */
class FakePreparedPurchaseRepository(
    private val drafts: FakeInvoiceDraftRepository,
    private val clock: AppClock,
) : PreparedPurchaseRepository {
    private val lock = Mutex()
    private var stored: PreparedPurchase? = null
    private val observed = MutableStateFlow<PreparedPurchase?>(null)

    /** Falla la próxima publicación antes de mutar el borrador o la instantánea. */
    var nextException: Exception? = null

    override suspend fun find(draftId: DraftId): PreparedPurchase? = lock.withLock {
        stored?.takeIf { it.draftId == draftId }
    }

    override fun observe(draftId: DraftId): Flow<PreparedPurchase?> =
        observed.map { purchase -> purchase?.takeIf { it.draftId == draftId } }

    override suspend fun publish(
        purchase: PreparedPurchase,
        expectedDraftUpdatedAt: Instant,
        expectedHeaderRevision: Long,
        expectedLinesRevision: Long,
    ): PublishPreparedPurchaseResult = lock.withLock {
        require(expectedHeaderRevision >= 0L)
        require(expectedLinesRevision >= 0L)
        nextException?.let { scheduled ->
            nextException = null
            throw scheduled
        }
        val existing = stored?.takeIf { it.draftId == purchase.draftId }
        if (existing != null && existing.logicalHash == purchase.logicalHash) {
            val current = drafts.findDraft(purchase.draftId)
            return@withLock if (current?.status == DraftStatus.READY_TO_POST) {
                PublishPreparedPurchaseResult.ALREADY_PREPARED
            } else {
                PublishPreparedPurchaseResult.CONFLICT
            }
        }
        val draft = drafts.findDraft(purchase.draftId)
        if (
            draft == null ||
            draft.updatedAt != expectedDraftUpdatedAt ||
            draft.status != DraftStatus.NEEDS_REVIEW ||
            draft.activeOcrRunId != null ||
            draft.confirmedPurchaseId != null
        ) {
            return@withLock PublishPreparedPurchaseResult.CONFLICT
        }
        drafts.updateDraft(draft.copy(status = DraftStatus.READY_TO_POST, updatedAt = clock.now()))
        stored = purchase
        observed.value = purchase
        PublishPreparedPurchaseResult.PREPARED
    }

    override suspend fun reopenForEdit(draftId: DraftId): Boolean = lock.withLock {
        val draft = drafts.findDraft(draftId)
        if (draft == null || draft.status != DraftStatus.READY_TO_POST) {
            return@withLock false
        }
        drafts.updateDraft(draft.copy(status = DraftStatus.NEEDS_REVIEW, updatedAt = clock.now()))
        if (stored?.draftId == draftId) {
            stored = null
            observed.value = null
        }
        true
    }
}
