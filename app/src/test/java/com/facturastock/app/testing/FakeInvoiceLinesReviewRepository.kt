package com.facturastock.app.testing

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.repository.InvoiceLinesEditPublication
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fake en memoria del snapshot CAS del editor de líneas. Replica la semántica de Room: guarda
 * solo si la revisión esperada coincide, trata un contenido idéntico como no-op idempotente y
 * publica la proyección tipada sobre el agregado de borradores en el mismo commit simulado.
 */
class FakeInvoiceLinesReviewRepository(
    private val drafts: FakeInvoiceDraftRepository,
) : InvoiceLinesReviewRepository {
    private val lock = Mutex()
    private val flow = MutableStateFlow<InvoiceLinesEdit?>(null)
    private var stored: InvoiceLinesEdit? = null

    /** Hook previo al guardado, para suspender o fallar el commit en pruebas. */
    var beforeSave: suspend () -> Unit = {}

    /** Última proyección tipada publicada junto al snapshot. */
    var lastProjection: List<InvoiceLine> = emptyList()
        private set

    /** IDs cuyo enlace fue declarado autoritativo en la última publicación exitosa. */
    var lastCatalogLinkLineIds: Set<LineId> = emptySet()
        private set

    suspend fun seed(edit: InvoiceLinesEdit) {
        lock.withLock {
            stored = edit
            flow.value = edit
        }
    }

    override suspend fun find(draftId: DraftId): InvoiceLinesEdit? = lock.withLock {
        stored?.takeIf { it.draftId == draftId }
    }

    override fun observe(draftId: DraftId): Flow<InvoiceLinesEdit?> = flow

    override suspend fun saveIfNewer(
        publication: InvoiceLinesEditPublication,
    ): SaveInvoiceLinesEditResult {
        beforeSave()
        return lock.withLock {
            val draft = drafts.findDraft(publication.edit.draftId)
                ?: return@withLock SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE
            if (
                draft.status != DraftStatus.NEEDS_REVIEW ||
                draft.activeOcrRunId != null ||
                draft.confirmedPurchaseId != null
            ) {
                return@withLock SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE
            }
            val current = stored
            if (current != null) {
                when {
                    current.revision == publication.edit.revision &&
                        current == publication.edit ->
                        return@withLock SaveInvoiceLinesEditResult.ALREADY_SAVED

                    current.revision > publication.edit.revision ->
                        return@withLock SaveInvoiceLinesEditResult.STALE_REVISION

                    current.revision == publication.edit.revision ->
                        return@withLock SaveInvoiceLinesEditResult.CONFLICT

                    publication.expectedRevision != current.revision ->
                        return@withLock SaveInvoiceLinesEditResult.STALE_REVISION
                }
            } else if (publication.expectedRevision != null) {
                return@withLock SaveInvoiceLinesEditResult.STALE_REVISION
            }
            drafts.replaceLines(publication.edit.draftId, publication.projectedLines)
            lastProjection = publication.projectedLines
            lastCatalogLinkLineIds = publication.catalogLinkLineIds
            stored = publication.edit
            flow.value = publication.edit
            SaveInvoiceLinesEditResult.SAVED
        }
    }
}
