package com.facturastock.app.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvoiceImageEntityTest {
    private val businessId = "123e4567-e89b-42d3-a456-426614174000"
    private val draftId = "223e4567-e89b-42d3-a456-426614174000"
    private val imageId = "523e4567-e89b-42d3-a456-426614174000"
    private val hash = "b".repeat(64)

    private fun image(
        filePath: String = "captures/$draftId/page-0.jpg",
        rotationDegrees: Int = 90,
        cropLeftFraction: Int? = null,
        cropTopFraction: Int? = null,
        cropRightFraction: Int? = null,
        cropBottomFraction: Int? = null,
    ) = InvoiceImageEntity(
        imageId = imageId,
        draftId = draftId,
        businessId = businessId,
        pageIndex = 0,
        filePath = filePath,
        sha256 = hash,
        mimeType = "image/jpeg",
        widthPx = 3_000,
        heightPx = 4_000,
        fileSizeBytes = 1_234_567L,
        createdAt = 1_000L,
        rotationDegrees = rotationDegrees,
        cropLeftFraction = cropLeftFraction,
        cropTopFraction = cropTopFraction,
        cropRightFraction = cropRightFraction,
        cropBottomFraction = cropBottomFraction,
    )

    @Test
    fun `guarda metadatos de archivo privado sin BLOB ni ruta absoluta`() {
        val entity = image()

        assertEquals("captures/$draftId/page-0.jpg", entity.filePath)
        assertThrows(IllegalArgumentException::class.java) {
            image(filePath = "/sdcard/DCIM/photo.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            image(filePath = "captures/../secrets/page-0.jpg")
        }
    }

    @Test
    fun `solo admite rotaciones de noventa grados`() {
        listOf(0, 90, 180, 270).forEach { image(rotationDegrees = it) }
        assertThrows(IllegalArgumentException::class.java) {
            image(rotationDegrees = 45)
        }
    }

    @Test
    fun `el recorte exige las cuatro aristas normalizadas en rango y orden`() {
        val cropped = image(
            cropLeftFraction = 10,
            cropTopFraction = 20,
            cropRightFraction = 9_990,
            cropBottomFraction = 9_980,
        )
        assertEquals(10, cropped.cropLeftFraction)

        // Las cuatro aristas vienen juntas o no hay recorte.
        assertThrows(IllegalArgumentException::class.java) {
            image(cropLeftFraction = 10, cropTopFraction = 20, cropRightFraction = 9_990)
        }
        // Nada fuera del rango normalizado.
        assertThrows(IllegalArgumentException::class.java) {
            image(
                cropLeftFraction = -1,
                cropTopFraction = 20,
                cropRightFraction = 9_990,
                cropBottomFraction = 9_980,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            image(
                cropLeftFraction = 10,
                cropTopFraction = 20,
                cropRightFraction = 10_001,
                cropBottomFraction = 9_980,
            )
        }
        // Ni invertidas ni vacías.
        assertThrows(IllegalArgumentException::class.java) {
            image(
                cropLeftFraction = 100,
                cropTopFraction = 20,
                cropRightFraction = 100,
                cropBottomFraction = 9_980,
            )
        }
    }

    @Test
    fun `exige hash SHA-256, dimensiones y tamaño positivos`() {
        assertThrows(IllegalArgumentException::class.java) {
            image().copy(sha256 = "no-es-un-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            image().copy(widthPx = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            image().copy(fileSizeBytes = 0L)
        }
    }
}
