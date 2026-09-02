package com.facturastock.app.feature.ocr

import com.facturastock.app.domain.model.id.DraftId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRouteNavigationTest {
    @Test
    fun `manual review effect invokes only manual header navigation`() {
        val regular = mutableListOf<DraftId>()
        val manual = mutableListOf<DraftId>()
        val products = mutableListOf<Triple<Int, Int, Int>>()
        val unrelated = mutableListOf<String>()

        handleOcrEffect(
            effect = OcrContract.Effect.OpenManualReview(DRAFT_ID),
            onOpenReview = regular::add,
            onOpenManualReview = manual::add,
            onOpenProducts = { created, existing, skipped ->
                products += Triple(created, existing, skipped)
            },
            onCancelled = { unrelated += "cancel" },
            onBack = { unrelated += "back" },
            onCloseInvalidRoute = { unrelated += "invalid" },
        )

        assertEquals(listOf(DRAFT_ID), manual)
        assertTrue(regular.isEmpty())
        assertTrue(products.isEmpty())
        assertTrue(unrelated.isEmpty())
    }

    @Test
    fun `product import effect forwards exact counts only to products`() {
        val regular = mutableListOf<DraftId>()
        val manual = mutableListOf<DraftId>()
        val products = mutableListOf<Triple<Int, Int, Int>>()
        val unrelated = mutableListOf<String>()

        handleOcrEffect(
            effect = OcrContract.Effect.OpenProducts(
                createdCount = 2,
                existingCount = 1,
                skippedCount = 3,
            ),
            onOpenReview = regular::add,
            onOpenManualReview = manual::add,
            onOpenProducts = { created, existing, skipped ->
                products += Triple(created, existing, skipped)
            },
            onCancelled = { unrelated += "cancel" },
            onBack = { unrelated += "back" },
            onCloseInvalidRoute = { unrelated += "invalid" },
        )

        assertEquals(listOf(Triple(2, 1, 3)), products)
        assertTrue(regular.isEmpty())
        assertTrue(manual.isEmpty())
        assertTrue(unrelated.isEmpty())
    }

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 10L))
    }
}
