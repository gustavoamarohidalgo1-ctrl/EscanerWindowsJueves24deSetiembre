package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.EnterManualInvoiceReviewResult
import com.facturastock.app.testing.FakeManualInvoiceReviewRepository
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterManualInvoiceReviewUseCaseTest {
    @Test
    fun `returns entered only after repository materializes manual review`() = runTest {
        val repository = FakeManualInvoiceReviewRepository()
        val useCase = EnterManualInvoiceReviewUseCase(repository)

        assertEquals(EnterManualInvoiceReviewResult.ENTERED, useCase(DRAFT_ID))
        assertEquals(listOf(DRAFT_ID), repository.calls)
        assertEquals(setOf(DRAFT_ID), repository.emptyHeaderDrafts)
        assertEquals(setOf(DRAFT_ID), repository.emptyLinesDrafts)
    }

    @Test
    fun `propagates unavailable without reporting a materialized review`() = runTest {
        val repository = FakeManualInvoiceReviewRepository().apply {
            nextResult = EnterManualInvoiceReviewResult.NOT_AVAILABLE
        }

        assertEquals(
            EnterManualInvoiceReviewResult.NOT_AVAILABLE,
            EnterManualInvoiceReviewUseCase(repository)(DRAFT_ID),
        )
        assertEquals(listOf(DRAFT_ID), repository.calls)
        assertEquals(emptySet<DraftId>(), repository.emptyHeaderDrafts)
        assertEquals(emptySet<DraftId>(), repository.emptyLinesDrafts)
    }

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 10L))
    }
}
