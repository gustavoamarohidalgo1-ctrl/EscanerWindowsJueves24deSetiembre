package com.facturastock.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageCropTest {

    @Test
    fun `out of range or inverted rectangles are impossible to construct`() {
        val invalid = listOf(
            listOf(-1, 0, 5_000, 5_000), // left negativo
            listOf(0, -1, 5_000, 5_000), // top negativo
            listOf(0, 0, 10_001, 5_000), // right por encima del máximo
            listOf(0, 0, 5_000, 10_001), // bottom por encima del máximo
            listOf(5_000, 0, 5_000, 5_000), // right == left (sin ancho)
            listOf(6_000, 0, 5_000, 5_000), // right < left
            listOf(0, 5_000, 5_000, 5_000), // bottom == top (sin alto)
            listOf(0, 6_000, 5_000, 5_000), // bottom < top
        )
        invalid.forEach { (left, top, right, bottom) ->
            try {
                ImageCrop(left = left, top = top, right = right, bottom = bottom)
                fail("se esperaba IllegalArgumentException para [$left,$top,$right,$bottom]")
            } catch (expected: IllegalArgumentException) {
                // esperado: el recorte nunca sale del archivo
            }
        }
    }

    @Test
    fun `boundary rectangles at the image edges are valid`() {
        ImageCrop(left = 0, top = 0, right = ImageCrop.FRACTION_MAX, bottom = ImageCrop.FRACTION_MAX)
        ImageCrop(left = 0, top = 0, right = 1, bottom = 1)
        ImageCrop(
            left = ImageCrop.FRACTION_MAX - 1,
            top = ImageCrop.FRACTION_MAX - 1,
            right = ImageCrop.FRACTION_MAX,
            bottom = ImageCrop.FRACTION_MAX,
        )
    }

    @Test
    fun `rotating 90 clockwise four times restores the original crop`() {
        val crop = ImageCrop(left = 1_000, top = 2_500, right = 8_000, bottom = 9_500)

        val rotated = crop
            .rotated90Cw()
            .rotated90Cw()
            .rotated90Cw()
            .rotated90Cw()

        assertEquals(crop, rotated)
    }

    @Test
    fun `rotating 90 clockwise maps the rectangle to the rotated frame`() {
        // (l,t,r,b) -> (10000-b, l, 10000-t, r)
        assertEquals(
            ImageCrop(left = 500, top = 1_000, right = 7_500, bottom = 8_000),
            ImageCrop(left = 1_000, top = 2_500, right = 8_000, bottom = 9_500).rotated90Cw(),
        )
        // Un cuarto superior izquierdo pasa a ser superior derecho al girar en sentido horario.
        assertEquals(
            ImageCrop(left = 5_000, top = 0, right = 10_000, bottom = 5_000),
            ImageCrop(left = 0, top = 0, right = 5_000, bottom = 5_000).rotated90Cw(),
        )
    }

    @Test
    fun `rotating preserves validity at the extremes`() {
        // (0,0,1,1) -> (10000-1, 0, 10000-0, 1): sigue siendo válido en los bordes.
        val crop = ImageCrop(left = 0, top = 0, right = 1, bottom = 1)
        val rotated = crop.rotated90Cw()
        assertEquals(ImageCrop.FRACTION_MAX - 1, rotated.left)
        assertEquals(ImageCrop.FRACTION_MAX, rotated.right)
        assertEquals(1, rotated.bottom)
    }

    @Test
    fun `a full image rectangle is detected as full`() {
        assertTrue(
            ImageCrop(
                left = 0,
                top = 0,
                right = ImageCrop.FRACTION_MAX,
                bottom = ImageCrop.FRACTION_MAX,
            ).isFullImage(),
        )
        assertFalse(ImageCrop(left = 0, top = 0, right = 9_999, bottom = 10_000).isFullImage())
    }
}
