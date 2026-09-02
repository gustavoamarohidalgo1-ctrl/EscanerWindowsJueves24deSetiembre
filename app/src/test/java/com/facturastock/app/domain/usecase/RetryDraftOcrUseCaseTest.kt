package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryDraftOcrUseCaseTest {
    private val clock = AppClock { Instant.parse("2026-08-01T12:00:00Z") }
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val useCase = RetryDraftOcrUseCase(drafts)

    @Test
    fun `resets an interrupted draft back to CAPTURED`() = runTest {
        drafts.createDraft(
            draft(
                draftId(1),
                status = DraftStatus.OCR_PROCESSING,
                activeOcrRunId = runId(1),
            ),
        )

        val reset = useCase(draftId(1), runId(1))

        assertTrue(reset)
        assertEquals(DraftStatus.CAPTURED, drafts.findDraft(draftId(1))?.status)
    }

    @Test
    fun `returns false when the draft no longer exists`() = runTest {
        assertFalse(useCase(draftId(9), null))
    }

    @Test
    fun `stale retry cannot clear a newer token`() = runTest {
        drafts.createDraft(
            draft(
                draftId(2),
                status = DraftStatus.OCR_PROCESSING,
                activeOcrRunId = runId(2),
            ),
        )

        assertFalse(useCase(draftId(2), runId(1)))
        assertEquals(DraftStatus.OCR_PROCESSING, drafts.findDraft(draftId(2))?.status)
        assertEquals(runId(2), drafts.findDraft(draftId(2))?.activeOcrRunId)
    }

    @Test
    fun `retry cannot degrade a closed or confirmed draft`() = runTest {
        drafts.createDraft(
            draft(
                draftId(3),
                status = DraftStatus.READY_TO_POST,
                confirmedPurchaseId = PurchaseId.from(uuid(40)),
            ),
        )

        assertFalse(useCase(draftId(3), null))
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId(3))?.status)
    }

    private fun draft(
        id: DraftId,
        status: DraftStatus,
        activeOcrRunId: OcrRunId? = null,
        confirmedPurchaseId: PurchaseId? = null,
    ) = InvoiceDraft(
        draftId = id,
        businessId = BUSINESS_ID,
        status = status,
        activeOcrRunId = activeOcrRunId,
        confirmedPurchaseId = confirmedPurchaseId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

        fun draftId(seed: Int): DraftId = DraftId.from(uuid(seed))
        fun runId(seed: Int): OcrRunId = OcrRunId.from(uuid(seed + 100))
    }
}
