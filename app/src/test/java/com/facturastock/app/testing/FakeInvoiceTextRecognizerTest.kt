package com.facturastock.app.testing

import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.OcrImageFile
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeInvoiceTextRecognizerTest {
    @Test
    fun `same ordered pages always produce the same hierarchy and geometry`() = runTest {
        val fake = FakeInvoiceTextRecognizer()
        val pages = listOf(page(2), page(1))

        val first = fake.recognize(pages)
        val second = fake.recognize(pages)

        assertEquals(first, second)
        assertEquals(pages.map(OcrImageFile::sourceImageId), first.pages.map { it.sourceImageId })
        assertEquals(listOf(0, 1), first.pages.map { it.pageIndex })
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `scripted text is stable and an empty page has no invented regions`() = runTest {
        val fake = FakeInvoiceTextRecognizer()
        val input = page(1)
        fake.seedText(input.sourceImageId, "")

        val page = fake.recognize(listOf(input)).pages.single()

        assertEquals("", page.text)
        assertEquals(emptyList<Any>(), page.blocks)
    }

    private fun page(value: Long) = OcrImageFile(
        sourceImageId = ImageId.from(UUID(0L, value)),
        relativePath = "draft_images/test/$value.jpg",
        mimeType = "image/jpeg",
        widthPx = 1_200,
        heightPx = 1_600,
        fileSizeBytes = 1_000,
    )
}
