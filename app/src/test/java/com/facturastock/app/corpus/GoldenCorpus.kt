package com.facturastock.app.corpus

import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.normalization.InvoiceParseContext
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manifiesto de un caso del corpus. Separa explícitamente tres contratos:
 *
 * 1. [inputImage] materializa una imagen sintética de entrada.
 * 2. [expectedOcrResource] congela el OCR esperado que alimenta al parser JVM.
 * 3. [expectedResultResource] congela una proyección cerrada del resultado del parser.
 *
 * La prueba JVM comienza en (2): no afirma que ML Kit haya producido ese OCR desde (1). El smoke
 * Android de ML Kit es un nivel independiente porque el reconocimiento físico no es determinista
 * entre modelos y dispositivos.
 */
@Serializable
data class GoldenCorpusCase(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val category: String,
    val buyerRuc: String?,
    val inputImage: GoldenInputImageContract,
    val expectedOcrResource: String,
    val expectedResultResource: String,
) {
    init {
        require(schemaVersion == CONTRACT_SCHEMA_VERSION) {
            "El manifiesto $id debe usar schemaVersion=$CONTRACT_SCHEMA_VERSION"
        }
        require(id.matches(Regex("[a-z0-9-]+"))) { "ID de corpus inválido: $id" }
        require(expectedOcrResource.isSafeCorpusResource()) { "Recurso OCR inseguro: $expectedOcrResource" }
        require(expectedResultResource.isSafeCorpusResource()) {
            "Recurso de resultado inseguro: $expectedResultResource"
        }
    }
}

@Serializable
data class GoldenInputImageContract(
    /** Capa textual usada exclusivamente para sintetizar la imagen; puede divergir del OCR futuro. */
    val textLayerResource: String,
    val outputFileName: String,
    val profile: GoldenInputImageProfile,
    val blurRadiusPx: Int,
    val horizontalSkewPermille: Int,
) {
    init {
        require(textLayerResource.isSafeCorpusResource()) { "Capa textual insegura: $textLayerResource" }
        require(outputFileName.matches(Regex("[a-z0-9-]+\\.png"))) {
            "Nombre de imagen de corpus inválido: $outputFileName"
        }
        require(blurRadiusPx in 0..8) { "Radio de blur fuera de contrato: $blurRadiusPx" }
        require(horizontalSkewPermille in -250..250) {
            "Inclinación fuera de contrato: $horizontalSkewPermille"
        }
        when (profile) {
            GoldenInputImageProfile.CLEAN -> require(blurRadiusPx == 0 && horizontalSkewPermille == 0)
            GoldenInputImageProfile.BLURRED -> require(blurRadiusPx > 0 && horizontalSkewPermille == 0)
            GoldenInputImageProfile.SKEWED -> require(blurRadiusPx == 0 && horizontalSkewPermille != 0)
        }
    }
}

@Serializable
enum class GoldenInputImageProfile {
    CLEAN,
    BLURRED,
    SKEWED,
}

/** Snapshot OCR sintético esperado. Este archivo nunca se presenta como salida medida de ML Kit. */
@Serializable
data class GoldenExpectedOcr(
    val schemaVersion: Int,
    val source: GoldenOcrSource,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val clockwiseAngleTenths: Int,
    val cells: List<GoldenCorpusCell>,
) {
    init {
        require(schemaVersion == CONTRACT_SCHEMA_VERSION)
        require(source == GoldenOcrSource.SYNTHETIC_EXPECTATION)
        require(pageWidthPx > 0 && pageHeightPx > 0)
        require(clockwiseAngleTenths in -1_800..1_800)
        require(cells.isNotEmpty()) { "Un OCR esperado no puede estar vacío" }
    }
}

@Serializable
enum class GoldenOcrSource {
    SYNTHETIC_EXPECTATION,
}

@Serializable
data class GoldenCorpusCell(
    val text: String,
    val box: List<Int>,
    val confidencePermille: Int = 950,
    val cornerPointsOnly: Boolean = false,
) {
    init {
        require(text.isNotBlank()) { "Una celda OCR no puede estar vacía" }
        require(box.size == 4) { "La caja de una celda debe tener 4 coordenadas" }
        val (left, top, right, bottom) = box
        require(left >= 0 && top >= 0 && right > left && bottom > top) {
            "Caja OCR inválida: $box"
        }
        require(confidencePermille in 0..1_000)
    }
}

/** Proyección cerrada y estable del resultado; ningún campo se omite silenciosamente. */
@Serializable
data class GoldenParserResult(
    val schemaVersion: Int,
    val header: GoldenExpectedHeader,
    val lineCount: Int,
    val lines: List<GoldenExpectedLine>,
    val totals: GoldenExpectedTotals,
    val confidence: String,
    /** Conjunto canónico exacto: ordenado y sin duplicados. */
    val warningCodes: List<String>,
) {
    init {
        require(schemaVersion == CONTRACT_SCHEMA_VERSION)
        require(lineCount == lines.size) { "lineCount debe coincidir con lines.size" }
        require(warningCodes == warningCodes.distinct().sorted()) {
            "warningCodes debe estar ordenado y sin duplicados"
        }
    }
}

@Serializable
data class GoldenExpectedHeader(
    val issuerRuc: String?,
    val issuerLegalName: String?,
    val documentType: String?,
    val documentNumber: String?,
    val issueDate: String?,
    val currency: String?,
)

@Serializable
data class GoldenExpectedLine(
    val description: String?,
    val quantity: String?,
    val totalMinorUnits: Long?,
)

@Serializable
data class GoldenExpectedTotals(
    val taxableMinorUnits: Long?,
    val exemptMinorUnits: Long?,
    val unaffectedMinorUnits: Long?,
    val subtotalMinorUnits: Long?,
    val igvMinorUnits: Long?,
    val totalMinorUnits: Long?,
)

data class GoldenCorpusFixture(
    val case: GoldenCorpusCase,
    val inputTextLayer: GoldenExpectedOcr,
    val expectedOcr: GoldenExpectedOcr,
    val expectedResult: GoldenParserResult,
)

/** Carga y valida los tres recursos, construye el snapshot OCR y materializa la imagen. */
object GoldenCorpus {
    const val RESOURCE_DIRECTORY = "golden-corpus"
    const val REPORT_DIRECTORY = "build/reports/golden-corpus"

    private const val CASES_DIRECTORY = "$RESOURCE_DIRECTORY/cases"
    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun loadCases(): List<GoldenCorpusFixture> {
        val classLoader = requireNotNull(GoldenCorpus::class.java.classLoader) {
            "ClassLoader no disponible para cargar las fixtures"
        }
        val resource = requireNotNull(classLoader.getResource(CASES_DIRECTORY)) {
            "No existe el directorio de manifiestos $CASES_DIRECTORY"
        }
        val directory = File(resource.toURI())
        val fixtures = directory.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy(File::getName)
            .map { file ->
                val case = json.decodeFromString<GoldenCorpusCase>(file.readText())
                require(file.nameWithoutExtension == case.id) {
                    "El ID ${case.id} no coincide con ${file.name}"
                }
                val inputTextLayer = loadResource<GoldenExpectedOcr>(
                    classLoader,
                    case.inputImage.textLayerResource,
                )
                val expectedOcr = loadResource<GoldenExpectedOcr>(classLoader, case.expectedOcrResource)
                val expectedResult = loadResource<GoldenParserResult>(
                    classLoader,
                    case.expectedResultResource,
                )
                validateFixture(case, inputTextLayer, expectedOcr)
                GoldenCorpusFixture(case, inputTextLayer, expectedOcr, expectedResult)
            }
        require(fixtures.map { it.case.id }.distinct().size == fixtures.size) {
            "Los IDs del corpus deben ser únicos"
        }
        return fixtures
    }

    fun parseContext(buyerRuc: String?): InvoiceParseContext = InvoiceParseContext(
        parserVersion = PARSER_VERSION,
        contextFingerprint = CONTEXT_FINGERPRINT,
        buyerRuc = buyerRuc,
        fallbackCurrency = CurrencyCode.of("PEN"),
        referenceIgvRate = TaxRate(BigDecimal("18")),
    )

    /** Nivel JVM: consume exclusivamente el OCR esperado versionado, nunca el PNG. */
    fun toSnapshot(fixture: GoldenCorpusFixture): InvoiceOcrSnapshot {
        val expectedOcr = fixture.expectedOcr
        val blocks = expectedOcr.cells.mapIndexed { position, cell ->
            cell.toBlock(position, expectedOcr.clockwiseAngleTenths)
        }
        val page = InvoiceTextPage(
            sourceImageId = IMAGE_ID,
            pageIndex = 0,
            widthPx = expectedOcr.pageWidthPx,
            heightPx = expectedOcr.pageHeightPx,
            text = expectedOcr.cells.joinToString("\n", transform = GoldenCorpusCell::text),
            blocks = blocks,
        )
        return InvoiceOcrSnapshot(
            draftId = DRAFT_ID,
            runId = RUN_ID,
            completedAt = COMPLETED_AT,
            document = InvoiceTextDocument(listOf(page)),
        )
    }

    /** Materializa la imagen de entrada sintética declarada por el manifiesto. */
    fun renderInputImage(fixture: GoldenCorpusFixture, imageFile: File) {
        require(imageFile.name == fixture.case.inputImage.outputFileName)
        GoldenCorpusImage.render(fixture, imageFile)
    }

    fun writeActualResult(result: GoldenParserResult, outputFile: File) {
        checkNotNull(outputFile.parentFile).mkdirs()
        outputFile.writeText(json.encodeToString(result) + "\n")
    }

    fun encodeResult(result: GoldenParserResult): String = json.encodeToString(result)

    private inline fun <reified T> loadResource(
        classLoader: ClassLoader,
        relativePath: String,
    ): T {
        val path = "$RESOURCE_DIRECTORY/$relativePath"
        val resource = requireNotNull(classLoader.getResource(path)) { "No existe el recurso $path" }
        return json.decodeFromString(File(resource.toURI()).readText())
    }

    private fun validateFixture(
        case: GoldenCorpusCase,
        inputTextLayer: GoldenExpectedOcr,
        expectedOcr: GoldenExpectedOcr,
    ) {
        require(case.inputImage.outputFileName == "${case.id}.png") {
            "La imagen de entrada debe usar el ID del caso"
        }
        listOf(inputTextLayer, expectedOcr).forEach { document ->
            document.cells.forEach { cell ->
                val (_, _, right, bottom) = cell.box
                require(right <= document.pageWidthPx && bottom <= document.pageHeightPx) {
                    "La celda ${cell.box} sale de la página del caso ${case.id}"
                }
            }
        }
    }

    private fun GoldenCorpusCell.toBlock(
        position: Int,
        clockwiseAngleTenths: Int,
    ): InvoiceTextBlock {
        val (left, top, right, bottom) = box
        val geometry = if (cornerPointsOnly) {
            val risePx = ((right - left) * clockwiseAngleTenths / 573).coerceIn(-40, 40)
            InvoiceTextGeometry(
                boundingBox = null,
                cornerPoints = listOf(
                    InvoiceTextPoint(left, top),
                    InvoiceTextPoint(right - 1, top + risePx),
                    InvoiceTextPoint(right - 1, bottom - 1 + risePx),
                    InvoiceTextPoint(left, bottom - 1),
                ),
            )
        } else {
            InvoiceTextGeometry(
                boundingBox = InvoiceTextBoundingBox(left, top, right, bottom),
                cornerPoints = emptyList(),
            )
        }
        return InvoiceTextBlock(
            position = position,
            text = text,
            languageTag = "es-PE",
            geometry = geometry,
            lines = listOf(
                InvoiceTextLine(
                    position = 0,
                    text = text,
                    languageTag = "es-PE",
                    geometry = geometry,
                    confidencePermille = confidencePermille,
                    clockwiseAngleTenths = clockwiseAngleTenths,
                    elements = emptyList(),
                ),
            ),
        )
    }

    private const val PARSER_VERSION = 1

    /** SHA-256 de "golden-corpus-v1": identifica el contexto de parseo de este corpus. */
    private val CONTEXT_FINGERPRINT: String = MessageDigest.getInstance("SHA-256")
        .digest("golden-corpus-v1".toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 2L))
    private val RUN_ID: OcrRunId = OcrRunId.from(UUID(0L, 3L))
    private val IMAGE_ID: ImageId = ImageId.from(UUID(0L, 4L))
    private val COMPLETED_AT: Instant = Instant.parse("2026-08-10T14:59:00Z")
}

private const val CONTRACT_SCHEMA_VERSION = 1

private fun String.isSafeCorpusResource(): Boolean =
    isNotBlank() && !startsWith('/') && !contains("..") && endsWith(".json")
