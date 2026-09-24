package com.facturastock.app.data.export

import java.io.File
import java.text.Normalizer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

/**
 * Par de fuentes (normal/negrita) del PDF de reportes.
 *
 * Preferimos incrustar una TrueType del sistema (Arial o Segoe UI de Windows) porque cubre
 * mucho más que Latin-1; si no hay ninguna disponible se usan Helvetica/Helvetica-Bold de las
 * Standard 14 con WinAnsiEncoding, que igualmente cubren á é í ó ú ñ ü ¿ ¡ · y el símbolo S/.
 * Nunca se mezclan familias: o ambas fuentes vienen del mismo par TrueType o ambas son Helvetica.
 */
internal class ReportPdfFonts private constructor(
    val regular: PDFont,
    val bold: PDFont,
    /** `true` si se incrustó una TrueType del sistema; `false` si se usan las Standard 14. */
    val embedded: Boolean,
) {
    private val sanitizers = mutableMapOf<PDFont, PdfTextSanitizer>()

    fun font(bold: Boolean): PDFont = if (bold) this.bold else regular

    fun sanitizer(font: PDFont): PdfTextSanitizer = sanitizers.getOrPut(font) { PdfTextSanitizer(font) }

    companion object {
        /** Pares normal/negrita a probar en orden dentro de la carpeta de fuentes. */
        private val CANDIDATES = listOf(
            "arial.ttf" to "arialbd.ttf",
            "segoeui.ttf" to "segoeuib.ttf",
        )

        /**
         * Carga las fuentes para [document]. Las TrueType se incrustan como subconjunto, así que
         * el PDF solo lleva los glifos usados. Cualquier fallo de lectura cae a Helvetica.
         */
        fun load(
            document: PDDocument,
            fontsDirectory: File? = windowsFontsDirectory(),
        ): ReportPdfFonts {
            val available = fontsDirectory
                ?.takeIf(File::isDirectory)
                ?.listFiles()
                ?.filter(File::isFile)
                ?.associateBy { it.name.lowercase() }
                .orEmpty()
            for ((regularName, boldName) in CANDIDATES) {
                val regularFile = available[regularName] ?: continue
                val boldFile = available[boldName] ?: continue
                val loaded = try {
                    ReportPdfFonts(
                        regular = PDType0Font.load(document, regularFile),
                        bold = PDType0Font.load(document, boldFile),
                        embedded = true,
                    )
                } catch (_: Exception) {
                    null
                }
                if (loaded != null) return loaded
            }
            return standard14()
        }

        fun standard14(): ReportPdfFonts = ReportPdfFonts(
            regular = PDType1Font(Standard14Fonts.FontName.HELVETICA),
            bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
            embedded = false,
        )

        /** `%WINDIR%\Fonts` (normalmente `C:\Windows\Fonts`); null fuera de Windows. */
        fun windowsFontsDirectory(): File? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (!os.contains("win")) return null
            val windows = System.getenv("WINDIR")?.takeIf(String::isNotBlank)
                ?: System.getenv("SystemRoot")?.takeIf(String::isNotBlank)
                ?: "C:\\Windows"
            return File(windows, "Fonts")
        }
    }
}

/**
 * Deja un texto dibujable con [font] sin que PDFBox lance `IllegalArgumentException` por un
 * carácter sin glifo o fuera de la codificación (p. ej. emoji o CJK con Helvetica/WinAnsi).
 *
 * - Conserva `\n` (salto de línea explícito); `\r\n` y `\r` se convierten en `\n`.
 * - Tabulaciones y separadores Unicode se vuelven espacio; los caracteres de control o de
 *   formato invisibles (BOM, ZWJ, ...) se descartan.
 * - Si el carácter no existe en la fuente se intenta, en orden: equivalentes tipográficos
 *   (comillas, guiones, puntos suspensivos), la letra base sin diacríticos (NFD) y, al final, `?`.
 *
 * El resultado de cada carácter se memoriza: los reportes repiten mucho los mismos caracteres.
 */
internal class PdfTextSanitizer(private val font: PDFont) {
    private val cache = HashMap<Int, String>()
    private val widths = HashMap<Int, Float>()

    fun sanitize(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            when (codePoint) {
                '\n'.code -> out.append('\n')
                '\r'.code -> {
                    if (index < text.length && text[index] == '\n') index++
                    out.append('\n')
                }
                else -> out.append(cache.getOrPut(codePoint) { replacement(codePoint) })
            }
        }
        return out.toString()
    }

    /** Ancho en puntos de texto ya saneado (sin kerning, igual que `getStringWidth`). */
    fun width(sanitized: CharSequence, size: Float): Float {
        var total = 0f
        var index = 0
        while (index < sanitized.length) {
            val codePoint = Character.codePointAt(sanitized, index)
            index += Character.charCount(codePoint)
            total += widths.getOrPut(codePoint) { glyphWidth(codePoint) }
        }
        return total * size / 1_000f
    }

    private fun glyphWidth(codePoint: Int): Float = try {
        font.getStringWidth(String(Character.toChars(codePoint)))
    } catch (_: Exception) {
        0f
    }

    private fun replacement(codePoint: Int): String {
        if (codePoint == '\t'.code) return " "
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED -> return ""
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR ->
                return if (canEncode(codePoint)) String(Character.toChars(codePoint)) else " "
        }
        if (canEncode(codePoint)) return String(Character.toChars(codePoint))
        TYPOGRAPHIC_FALLBACKS[codePoint]?.let { fallback ->
            if (fallback.all { canEncode(it.code) }) return fallback
        }
        val decomposed = Normalizer
            .normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        if (decomposed.isNotEmpty() && decomposed.codePoints().allMatch(::canEncode)) return decomposed
        return UNKNOWN
    }

    private fun canEncode(codePoint: Int): Boolean = try {
        val text = String(Character.toChars(codePoint))
        font.encode(text)
        font.getStringWidth(text)
        true
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val UNKNOWN = "?"

        val TYPOGRAPHIC_FALLBACKS: Map<Int, String> = mapOf(
            0x2018 to "'", 0x2019 to "'", 0x201A to "'", 0x2032 to "'",
            0x201C to "\"", 0x201D to "\"", 0x201E to "\"", 0x2033 to "\"",
            0x2010 to "-", 0x2011 to "-", 0x2012 to "-", 0x2013 to "-", 0x2014 to "-", 0x2212 to "-",
            0x2026 to "...",
            0x2022 to "\u00B7", 0x2027 to "\u00B7", 0x2219 to "\u00B7",
            0x20AC to "EUR",
        )
    }
}
