package com.facturastock.app.data.files

import java.awt.AlphaComposite
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageInputStream
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.round

/**
 * Cabeceras de una imagen leídas sin decodificar píxeles; equivalente de escritorio de
 * `BitmapFactory.Options` con `inJustDecodeBounds`. Igual que en Android, una imagen ilegible
 * deja `outWidth`/`outHeight` en `-1` y `outMimeType` en `null`.
 */
internal class DecodedImageBounds(
    val outWidth: Int,
    val outHeight: Int,
    val outMimeType: String?,
) {
    internal companion object {
        val UNREADABLE = DecodedImageBounds(outWidth = -1, outHeight = -1, outMimeType = null)
    }
}

/**
 * Códec de escritorio sobre `javax.imageio` + `java.awt.image` que reproduce las operaciones de
 * `BitmapFactory`/`Bitmap` que usa la capa de archivos:
 *
 * - `inJustDecodeBounds` → [ImageReader.getWidth]/[ImageReader.getHeight] sin leer píxeles.
 * - `inSampleSize` → `ImageReadParam.setSourceSubsampling` (mismo tamaño resultante
 *   `ceil(lado / muestra)`), y `BitmapRegionDecoder` → `ImageReadParam.sourceRegion`.
 * - `Bitmap.Config.ARGB_8888` → [BufferedImage.TYPE_INT_ARGB] (sRGB, alfa no premultiplicado
 *   en `getRGB`, igual que `Bitmap.getPixels`).
 * - `Bitmap.compress(JPEG, q)` → escritor JPEG de ImageIO con calidad `q / 100f`, que el JDK
 *   convierte a la misma escala lineal de libjpeg (`jpeg_quality_scaling`), con las tablas IJG
 *   estándar y submuestreo 4:2:0, como Skia. El alfa se aplana sobre negro, que es lo que
 *   obtiene Skia al descartar el canal de un bitmap premultiplicado.
 *
 * Privacidad: jamás se usa `ImageIO.createImageInputStream`/`createImageOutputStream`, porque
 * con la caché de disco activada volcarían bytes de la imagen (por ejemplo, plaintext recién
 * descifrado) a un temporal fuera de la carpeta privada. Los flujos se crean explícitamente
 * en memoria o directamente sobre el archivo.
 */
internal object DesktopImageCodec {

    /** Cabeceras del archivo sin cargar píxeles; nunca lanza por contenido inválido. */
    fun decodeBounds(file: File): DecodedImageBounds =
        openFile(file)?.use(::readBounds) ?: DecodedImageBounds.UNREADABLE

    /** Cabeceras de bytes en memoria; nunca lanza por contenido inválido. */
    fun decodeBounds(bytes: ByteArray): DecodedImageBounds =
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use(::readBounds)

    /**
     * Decodifica [file] con una muestra potencia de dos y, opcionalmente, solo [region] (en
     * coordenadas de la fuente). Devuelve `null` si el contenido no es decodificable, como
     * `BitmapFactory.decodeFile`; un [OutOfMemoryError] siempre se propaga al llamador.
     */
    fun decodeFile(file: File, sampleSize: Int = 1, region: Rectangle? = null): BufferedImage? =
        openFile(file)?.use { stream -> readImage(stream, sampleSize, region) }

    /** Variante en memoria de [decodeFile], equivalente a `BitmapFactory.decodeByteArray`. */
    fun decodeByteArray(bytes: ByteArray, sampleSize: Int = 1): BufferedImage? =
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            readImage(stream, sampleSize, region = null)
        }

    /**
     * Codifica [image] como JPEG con calidad [quality] (0..100) en [output]. Devuelve `false`
     * si no hay escritor JPEG disponible; los fallos del flujo se propagan como [IOException]
     * y las excepciones de tiempo de ejecución del propio flujo (p. ej. cancelación) intactas.
     */
    @Throws(IOException::class)
    fun compressJpeg(image: BufferedImage, quality: Int, output: OutputStream): Boolean {
        require(quality in 0..100)
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return false
        val writer = writers.next()
        val opaque = toOpaqueRgb(image)
        try {
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(opaque, null, null), param)
                stream.flush()
            }
        } finally {
            writer.dispose()
            if (opaque !== image) opaque.flush()
        }
        return true
    }

    /**
     * Rota en sentido horario como `Matrix.postRotate` + `Bitmap.createBitmap`. Los múltiplos de
     * 90° se resuelven con giros de cuadrante exactos (sin interpolación, píxel a píxel); otros
     * ángulos usan el rectángulo envolvente redondeado, igual que Android. Devuelve la misma
     * instancia si no hay rotación.
     */
    fun rotate(source: BufferedImage, rotationDegrees: Int, filter: Boolean): BufferedImage {
        val normalized = ((rotationDegrees % FULL_TURN) + FULL_TURN) % FULL_TURN
        if (normalized == 0) return source
        val transform = AffineTransform.getRotateInstance(Math.toRadians(normalized.toDouble()))
        val (targetWidth, targetHeight) = if (normalized % QUARTER_TURN == 0) {
            transform.setToQuadrantRotation(normalized / QUARTER_TURN)
            if (normalized == 180) source.width to source.height else source.height to source.width
        } else {
            val bounds = transform.createTransformedShape(
                Rectangle2D.Double(0.0, 0.0, source.width.toDouble(), source.height.toDouble()),
            ).bounds2D
            round(bounds.width).toInt() to round(bounds.height).toInt()
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            throw IllegalArgumentException("Dimensiones rotadas inválidas")
        }
        val mapped = transform.createTransformedShape(
            Rectangle2D.Double(0.0, 0.0, source.width.toDouble(), source.height.toDouble()),
        ).bounds2D
        val placement = AffineTransform.getTranslateInstance(-mapped.x, -mapped.y).apply {
            concatenate(transform)
        }
        val rotated = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = rotated.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (filter && normalized % QUARTER_TURN != 0) {
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                } else {
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                },
            )
            graphics.drawImage(source, placement, null)
        } finally {
            graphics.dispose()
        }
        return rotated
    }

    /** Copia independiente del rectángulo pedido, como `Bitmap.createBitmap(src, x, y, w, h)`. */
    fun crop(source: BufferedImage, left: Int, top: Int, width: Int, height: Int): BufferedImage {
        require(width > 0 && height > 0) { "Recorte vacío" }
        require(left >= 0 && top >= 0) { "Recorte fuera de la imagen" }
        require(left + width <= source.width && top + height <= source.height) {
            "Recorte fuera de la imagen"
        }
        val cropped = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = cropped.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.drawImage(source.getSubimage(left, top, width, height), 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return cropped
    }

    /**
     * Escalado equivalente a `Bitmap.createScaledBitmap(src, w, h, filter)`: bilineal con
     * [filter], vecino más cercano sin él, y la misma instancia si el tamaño no cambia.
     */
    fun scale(
        source: BufferedImage,
        width: Int,
        height: Int,
        filter: Boolean = true,
    ): BufferedImage {
        require(width > 0 && height > 0) { "Dimensiones de escalado inválidas" }
        if (width == source.width && height == source.height) return source
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (filter) {
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                } else {
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                },
            )
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return scaled
    }

    private fun openFile(file: File): ImageInputStream? = try {
        FileImageInputStream(file)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun firstReader(stream: ImageInputStream): ImageReader? = try {
        val readers = ImageIO.getImageReaders(stream)
        if (readers.hasNext()) readers.next() else null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun readBounds(stream: ImageInputStream): DecodedImageBounds {
        val reader = firstReader(stream) ?: return DecodedImageBounds.UNREADABLE
        return try {
            reader.setInput(stream, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            val mimeType = reader.originatingProvider?.mimeTypes
                ?.firstOrNull()
                ?.trim()
                ?.lowercase(Locale.ROOT)
            if (width <= 0 || height <= 0) {
                DecodedImageBounds.UNREADABLE
            } else {
                DecodedImageBounds(outWidth = width, outHeight = height, outMimeType = mimeType)
            }
        } catch (_: IOException) {
            DecodedImageBounds.UNREADABLE
        } catch (_: RuntimeException) {
            DecodedImageBounds.UNREADABLE
        } finally {
            reader.dispose()
        }
    }

    private fun readImage(
        stream: ImageInputStream,
        sampleSize: Int,
        region: Rectangle?,
    ): BufferedImage? {
        require(sampleSize >= 1)
        val reader = firstReader(stream) ?: return null
        return try {
            reader.setInput(stream, true, true)
            val param = reader.defaultReadParam
            if (region != null) param.sourceRegion = region
            if (sampleSize > 1) param.setSourceSubsampling(sampleSize, sampleSize, 0, 0)
            val decoded = reader.read(0, param) ?: return null
            toArgb(decoded)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: java.awt.color.CMMException) {
            null
        } catch (_: java.awt.image.RasterFormatException) {
            null
        } finally {
            reader.dispose()
        }
    }

    /** Normaliza cualquier modelo de color decodificado a ARGB_8888 sRGB. */
    private fun toArgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_ARGB) return image
        val converted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = converted.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        image.flush()
        return converted
    }

    /**
     * El escritor JPEG del JDK no admite alfa. El lienzo RGB nace en negro y se pinta encima con
     * `SrcOver`: el resultado es el color premultiplicado, igual que el JPEG de Skia.
     */
    private fun toOpaqueRgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB) return image
        val opaque = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = opaque.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return opaque
    }

    private const val FULL_TURN = 360
    private const val QUARTER_TURN = 90
}
