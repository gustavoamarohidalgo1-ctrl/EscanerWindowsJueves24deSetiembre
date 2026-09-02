package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftResumeTargetTest {
    @Test
    fun `maps every draft status to its resume destination`() {
        assertEquals(
            DraftResumeTarget.Source(DRAFT_ID),
            draft(DraftStatus.CREATED).resumeTarget(),
        )
        assertEquals(
            DraftResumeTarget.Processing(DRAFT_ID),
            draft(DraftStatus.CAPTURED).resumeTarget(),
        )
        assertEquals(
            DraftResumeTarget.InterruptedOcr,
            draft(DraftStatus.OCR_PROCESSING).resumeTarget(),
        )
        assertEquals(
            DraftResumeTarget.Processing(DRAFT_ID),
            draft(DraftStatus.OCR_READY).resumeTarget(),
        )
        assertEquals(
            DraftResumeTarget.Processing(DRAFT_ID),
            draft(DraftStatus.NEEDS_REVIEW).resumeTarget(),
        )
        assertEquals(
            DraftResumeTarget.Summary(DRAFT_ID),
            draft(DraftStatus.READY_TO_POST).resumeTarget(),
        )
        assertEquals(
            DraftResumeTarget.Summary(DRAFT_ID),
            draft(DraftStatus.COMMITTED).resumeTarget(),
        )
        assertEquals(
            DraftResumeTarget.Processing(DRAFT_ID),
            draft(DraftStatus.ERROR).resumeTarget(),
        )
    }

    @Test
    fun `confirmed purchase takes precedence over any status`() {
        DraftStatus.entries.forEach { status ->
            assertEquals(
                DraftResumeTarget.PurchaseDetail(PURCHASE_ID),
                draft(status, confirmedPurchaseId = PURCHASE_ID).resumeTarget(),
            )
        }
    }

    private fun draft(
        status: DraftStatus,
        confirmedPurchaseId: PurchaseId? = null,
    ) = InvoiceDraft(
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        status = status,
        confirmedPurchaseId = confirmedPurchaseId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
        )
        val PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
        )
    }
}
