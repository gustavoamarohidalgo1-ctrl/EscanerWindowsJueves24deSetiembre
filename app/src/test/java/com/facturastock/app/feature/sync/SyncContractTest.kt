package com.facturastock.app.feature.sync

import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.repository.OutboxOperationView
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncContractTest {

    @Test
    fun `document projection exposes every closed user facing state`() {
        val cases = listOf(
            Triple("SYNC_DOCUMENT_UPLOAD", OutboxOperationStatus.RESOLVED, LOCAL_ONLY),
            Triple("SYNC_DOCUMENT_UPLOAD", OutboxOperationStatus.PENDING, PENDING_UPLOAD),
            Triple("SYNC_DOCUMENT_UPLOAD", OutboxOperationStatus.PROCESSING, UPLOADING),
            Triple("SYNC_DOCUMENT_UPLOAD", OutboxOperationStatus.COMPLETED, BACKED_UP),
            Triple("SYNC_DOCUMENT_UPLOAD", OutboxOperationStatus.FAILED, ERROR),
            Triple("SYNC_DOCUMENT_PURGE", OutboxOperationStatus.PENDING, PURGE_PENDING),
            Triple("SYNC_DOCUMENT_PURGE", OutboxOperationStatus.COMPLETED, DELETED_FROM_CLOUD),
        )
        val state = SyncContract.State(
            outbox = cases.mapIndexed { index, (type, status, _) ->
                documentOperation(index, type, status)
            },
        )

        val byEntity = state.documentOperations.associate { it.operation.entityId to it.state }

        cases.forEachIndexed { index, (_, _, expected) ->
            assertEquals(expected, byEntity.getValue("image-$index"))
        }
        assertEquals(cases.size, state.documentOperations.size)
        assertEquals(emptyList<OutboxOperationView>(), state.pendingOperations)
        assertEquals(emptyList<OutboxOperationView>(), state.conflictOperations)
    }

    private fun documentOperation(
        index: Int,
        operationType: String,
        status: OutboxOperationStatus,
    ) = OutboxOperationView(
        operationId = "operation-$index",
        operationType = operationType,
        purchaseId = null,
        status = status,
        attemptCount = 0,
        lastError = null,
        nextAttemptAt = null,
        updatedAt = Instant.ofEpochSecond(index.toLong()),
        conflictRemotePurchaseId = null,
        conflictReceiptId = null,
        entityType = "DOCUMENT",
        entityId = "image-$index",
        entityVersion = if (operationType == "SYNC_DOCUMENT_PURGE") 2 else 1,
    )

    private companion object {
        val LOCAL_ONLY = SyncContract.DocumentSyncState.LOCAL_ONLY
        val PENDING_UPLOAD = SyncContract.DocumentSyncState.PENDING_UPLOAD
        val UPLOADING = SyncContract.DocumentSyncState.UPLOADING
        val BACKED_UP = SyncContract.DocumentSyncState.BACKED_UP
        val ERROR = SyncContract.DocumentSyncState.ERROR
        val PURGE_PENDING = SyncContract.DocumentSyncState.PURGE_PENDING
        val DELETED_FROM_CLOUD = SyncContract.DocumentSyncState.DELETED_FROM_CLOUD
    }
}
