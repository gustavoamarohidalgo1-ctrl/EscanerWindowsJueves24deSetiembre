package com.facturastock.app.corpus

import com.facturastock.app.domain.normalization.InvoiceParser
import com.facturastock.app.domain.normalization.ParsedInvoice
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nivel JVM del corpus: toma el OCR sintético esperado, ejecuta `InvoiceParser` y exige igualdad
 * con una proyección JSON cerrada. La imagen se materializa como entrada auditable, pero esta
 * prueba no afirma que un motor OCR haya producido el snapshot desde sus píxeles.
 */
class GoldenCorpusTest {
    @Test
    fun `cada fixture cumple el contrato OCR a resultado cerrado`() {
        val fixtures = GoldenCorpus.loadCases()
        assertTrue("El corpus dorado debe contener al menos una fixture", fixtures.isNotEmpty())

        val reportDirectory = File(GoldenCorpus.REPORT_DIRECTORY)
        val imagesDirectory = File(reportDirectory, "input-images")
        val actualResultsDirectory = File(reportDirectory, "actual-results")
        val parser = InvoiceParser()
        val results = fixtures.map { fixture ->
            val case = fixture.case
            GoldenCorpus.renderInputImage(
                fixture,
                File(imagesDirectory, case.inputImage.outputFileName),
            )
            val parsed = parser.parse(
                GoldenCorpus.toSnapshot(fixture),
                GoldenCorpus.parseContext(case.buyerRuc),
            )
            val actual = parsed.toGoldenResult()
            GoldenCorpus.writeActualResult(actual, File(actualResultsDirectory, "${case.id}.json"))
            evaluate(fixture, actual)
        }

        val reportFile = File(reportDirectory, REPORT_FILE_NAME)
        writeReport(results, reportFile)
        results.forEach { result -> println(result.summaryLine()) }

        assertTrue(
            "El informe del corpus dorado debe haberse escrito en ${reportFile.path}",
            reportFile.isFile && reportFile.length() > 0L,
        )
        val failed = results.filterNot(CaseResult::passed)
        assertTrue(
            buildString {
                appendLine(
                    "Casos del corpus dorado con contrato incumplido: ${failed.size}/${results.size}",
                )
                failed.forEach { result ->
                    appendLine()
                    appendLine("== ${result.fixture.case.id} (${result.fixture.case.title}) ==")
                    result.failedChecks().forEach { check ->
                        appendLine(
                            "  - ${check.field}: esperado=${check.expected} real=${check.actual}",
                        )
                    }
                }
            },
            failed.isEmpty(),
        )
    }

    @Test
    fun `manifiesto OCR y resultado son contratos separados y explícitos`() {
        GoldenCorpus.loadCases().forEach { fixture ->
            val case = fixture.case
            assertTrue(case.inputImage.textLayerResource.startsWith("expected-ocr/"))
            assertTrue(case.expectedOcrResource.startsWith("expected-ocr/"))
            assertTrue(case.expectedResultResource.startsWith("expected-results/"))
            assertTrue(case.expectedOcrResource != case.expectedResultResource)
            assertTrue(case.inputImage.outputFileName == "${case.id}.png")
        }
    }

    @Test
    fun `el oracle cerrado rechaza cualquier warning inesperado`() {
        val expected = GoldenCorpus.loadCases().first().expectedResult
        val unexpected = expected.copy(
            warningCodes = (expected.warningCodes + "UNEXPECTED_WARNING").distinct().sorted(),
        )

        assertFalse(matchesGoldenParserContract(expected, unexpected))
    }

    private fun evaluate(
        fixture: GoldenCorpusFixture,
        actual: GoldenParserResult,
    ): CaseResult {
        val expected = fixture.expectedResult
        val checks = mutableListOf<FieldCheck>()

        fun check(field: String, expectedValue: Any?, actualValue: Any?) {
            checks += FieldCheck(field, display(expectedValue), display(actualValue))
        }

        check("schemaVersion", expected.schemaVersion, actual.schemaVersion)
        check("RUC emisor", expected.header.issuerRuc, actual.header.issuerRuc)
        check("Razón social del emisor", expected.header.issuerLegalName, actual.header.issuerLegalName)
        check("Tipo de comprobante", expected.header.documentType, actual.header.documentType)
        check("Número de comprobante", expected.header.documentNumber, actual.header.documentNumber)
        check("Fecha de emisión", expected.header.issueDate, actual.header.issueDate)
        check("Moneda", expected.header.currency, actual.header.currency)
        check("Cantidad de líneas", expected.lineCount, actual.lineCount)

        val maximumLineCount = maxOf(expected.lines.size, actual.lines.size)
        repeat(maximumLineCount) { index ->
            val expectedLine = expected.lines.getOrNull(index)
            val actualLine = actual.lines.getOrNull(index)
            val label = "Línea ${index + 1}"
            check("$label: presencia", expectedLine != null, actualLine != null)
            check("$label: descripción", expectedLine?.description, actualLine?.description)
            check("$label: cantidad", expectedLine?.quantity, actualLine?.quantity)
            check(
                "$label: total (unidades menores)",
                expectedLine?.totalMinorUnits,
                actualLine?.totalMinorUnits,
            )
        }

        check(
            "Op. gravada (unidades menores)",
            expected.totals.taxableMinorUnits,
            actual.totals.taxableMinorUnits,
        )
        check(
            "Op. exonerada (unidades menores)",
            expected.totals.exemptMinorUnits,
            actual.totals.exemptMinorUnits,
        )
        check(
            "Op. inafecta (unidades menores)",
            expected.totals.unaffectedMinorUnits,
            actual.totals.unaffectedMinorUnits,
        )
        check(
            "Subtotal (unidades menores)",
            expected.totals.subtotalMinorUnits,
            actual.totals.subtotalMinorUnits,
        )
        check("IGV (unidades menores)", expected.totals.igvMinorUnits, actual.totals.igvMinorUnits)
        check(
            "Total del comprobante (unidades menores)",
            expected.totals.totalMinorUnits,
            actual.totals.totalMinorUnits,
        )
        check("Confianza global", expected.confidence, actual.confidence)
        check(
            "Advertencias exactas",
            expected.warningCodes.joinToString().ifEmpty { "(ninguna)" },
            actual.warningCodes.joinToString().ifEmpty { "(ninguna)" },
        )

        return CaseResult(
            fixture = fixture,
            actual = actual,
            checks = checks,
            contractMatches = matchesGoldenParserContract(expected, actual),
        )
    }

    private fun writeReport(results: List<CaseResult>, reportFile: File) {
        val passed = results.count(CaseResult::passed)
        val totalChecks = results.sumOf { result -> result.checks.size }
        val passedChecks = results.sumOf { result -> result.checks.count(FieldCheck::passed) }
        val markdown = buildString {
            appendLine("# Informe del corpus dorado de parsing de comprobantes")
            appendLine()
            appendLine(
                "> Nivel JVM determinista **OCR esperado → parser → resultado cerrado**. Las " +
                    "imágenes son entradas sintéticas auditables; este informe no afirma haber " +
                    "medido ML Kit ni universalidad sobre comprobantes reales.",
            )
            appendLine()
            appendLine("- Versión del parser: 1 (contexto `golden-corpus-v1`).")
            appendLine("- Casos superados: $passed/${results.size}.")
            appendLine("- Comprobaciones cumplidas: $passedChecks/$totalChecks.")
            appendLine("- Oracle de advertencias: conjunto exacto, sin warnings adicionales permitidos.")
            appendLine()
            results.forEach { result ->
                val case = result.fixture.case
                appendLine("## `${case.id}` — ${case.title}")
                appendLine()
                appendLine("- Categoría: ${case.category}.")
                appendLine("- Imagen de entrada: `input-images/${case.inputImage.outputFileName}`.")
                appendLine("- Perfil visual: `${case.inputImage.profile}`.")
                appendLine("- OCR esperado versionado: `${case.expectedOcrResource}`.")
                appendLine("- Resultado esperado versionado: `${case.expectedResultResource}`.")
                appendLine("- Resultado real serializado: `actual-results/${case.id}.json`.")
                appendLine("- Resultado: " + if (result.passed) "✓ superado" else "✗ con errores")
                appendLine()
                appendLine("| Campo | Esperado | Real | Resultado |")
                appendLine("| --- | --- | --- | --- |")
                result.checks.forEach { check ->
                    appendLine(
                        "| ${check.field} | ${check.expected} | ${check.actual} | " +
                            (if (check.passed) "✓" else "✗") + " |",
                    )
                }
                appendLine()
                val failedChecks = result.failedChecks()
                if (failedChecks.isEmpty()) {
                    appendLine("Errores: ninguno.")
                } else {
                    appendLine("Errores:")
                    failedChecks.forEach { check ->
                        appendLine("- ${check.field}: esperado `${check.expected}`, real `${check.actual}`.")
                    }
                }
                appendLine()
            }
            appendLine("## Resumen")
            appendLine()
            appendLine("- Casos superados: **$passed/${results.size}**.")
            appendLine("- Comprobaciones cumplidas: **$passedChecks/$totalChecks**.")
            appendLine()
        }
        checkNotNull(reportFile.parentFile).mkdirs()
        reportFile.writeText(markdown)
    }

    private fun ParsedInvoice.toGoldenResult(): GoldenParserResult = GoldenParserResult(
        schemaVersion = 1,
        header = GoldenExpectedHeader(
            issuerRuc = header.issuerRuc.selected?.value,
            issuerLegalName = header.issuerLegalName.selected?.value,
            documentType = header.documentType.selected?.value?.name,
            documentNumber = header.documentNumber.selected?.value?.normalized,
            issueDate = header.issueDate.selected?.value?.toString(),
            currency = header.currency.selected?.value?.value,
        ),
        lineCount = lineItems.items.size,
        lines = lineItems.items.map { item ->
            GoldenExpectedLine(
                description = item.description?.value,
                quantity = item.quantity?.value?.value?.toPlainString(),
                totalMinorUnits = item.total?.value?.minorUnits,
            )
        },
        totals = GoldenExpectedTotals(
            taxableMinorUnits = totals.read.taxableOperations.selected?.candidate?.value?.minorUnits,
            exemptMinorUnits = totals.read.exemptOperations.selected?.candidate?.value?.minorUnits,
            unaffectedMinorUnits = totals.read.unaffectedOperations.selected?.candidate?.value?.minorUnits,
            subtotalMinorUnits = totals.read.subtotal.selected?.candidate?.value?.minorUnits,
            igvMinorUnits = totals.read.igv.selected?.candidate?.value?.minorUnits,
            totalMinorUnits = totals.read.total.selected?.candidate?.value?.minorUnits,
        ),
        confidence = confidence.name,
        warningCodes = warnings.map { warning -> warning.code.name }.distinct().sorted(),
    )

    private fun display(value: Any?): String = value?.toString() ?: "(ausente)"

    private data class FieldCheck(
        val field: String,
        val expected: String,
        val actual: String,
        val passed: Boolean = expected == actual,
    )

    private data class CaseResult(
        val fixture: GoldenCorpusFixture,
        val actual: GoldenParserResult,
        val checks: List<FieldCheck>,
        val contractMatches: Boolean,
    ) {
        val passed: Boolean
            get() = contractMatches && checks.all(FieldCheck::passed)

        fun failedChecks(): List<FieldCheck> {
            val failed = checks.filterNot(FieldCheck::passed)
            if (contractMatches || failed.isNotEmpty()) return failed
            return listOf(
                FieldCheck(
                    field = "Resultado canónico completo",
                    expected = GoldenCorpus.encodeResult(fixture.expectedResult),
                    actual = GoldenCorpus.encodeResult(actual),
                    passed = false,
                ),
            )
        }

        fun summaryLine(): String = if (passed) {
            "[✓] ${fixture.case.id} — ${checks.size}/${checks.size} comprobaciones cumplidas"
        } else {
            "[✗] ${fixture.case.id} — fallan: ${failedChecks().joinToString { it.field }}"
        }
    }

    private companion object {
        const val REPORT_FILE_NAME = "golden-corpus-report.md"
    }
}

internal fun matchesGoldenParserContract(
    expected: GoldenParserResult,
    actual: GoldenParserResult,
): Boolean = expected == actual
