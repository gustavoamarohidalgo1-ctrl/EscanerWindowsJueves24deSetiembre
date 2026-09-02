package com.facturastock.app.testing

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.InvoiceOcrSnapshotRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Snapshot fake determinista: una clave por borrador y reemplazo completo en cada publicación. */
class FakeInvoiceOcrSnapshotRepository(
    private val drafts: FakeInvoiceDraftRepository,
) : InvoiceOcrSnapshotRepository {
    private val lock = Mutex()
    private val snapshots = mutableMapOf<DraftId, InvoiceOcrSnapshot>()
    private val recordedPublications = mutableListOf<InvoiceOcrSnapshot>()

    var nextException: Exception? = null
    var beforePublish: suspend (InvoiceOcrSnapshot) -> Unit = {}
    var afterPublish: suspend (InvoiceOcrSnapshot) -> Unit = {}

    val publications: List<InvoiceOcrSnapshot>
        get() = recordedPublications.toList()

    val snapshotCount: Int
        get() = snapshots.size

    override suspend fun publish(snapshot: InvoiceOcrSnapshot): Boolean {
        beforePublish(snapshot)
        nextException?.let { scheduled ->
            nextException = null
            throw scheduled
        }
        val claimed = drafts.finishOcrRun(
            draftId = snapshot.draftId,
            runId = snapshot.runId,
            newStatus = DraftStatus.OCR_READY,
        )
        if (!claimed) return false
        lock.withLock {
            snapshots[snapshot.draftId] = snapshot
            recordedPublications += snapshot
        }
        drafts.markOcrSnapshotPublished(snapshot.draftId)
        afterPublish(snapshot)
        return true
    }

    override suspend fun find(draftId: DraftId): InvoiceOcrSnapshot? =
        lock.withLock { snapshots[draftId] }
}
