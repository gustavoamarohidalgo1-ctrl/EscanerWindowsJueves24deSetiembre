package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant

enum class WorkflowStage {
    CREATED,
    SOURCE_SELECTED,
    IMAGE_CAPTURED,
    OCR_PROCESSED,
    HEADER_REVIEWED,
    LINES_REVIEWED,
    PRODUCTS_LINKED,
    SUMMARY_REVIEWED,
    CONFIRMED,
}

data class WorkflowRequest(
    val stage: WorkflowStage,
    val draftId: DraftId? = null,
    val captureId: CaptureId? = null,
    val lineId: LineId? = null,
)

data class WorkflowSnapshot(
    val draftId: DraftId,
    val stage: WorkflowStage,
    val captureId: CaptureId? = null,
    val lineId: LineId? = null,
    val purchaseId: PurchaseId? = null,
    val updatedAt: Instant,
)
