package com.facturastock.app.feature.linereview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Confidence
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Editor
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Field
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Line
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.State
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Summary
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.ValueOrigin
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Test

/**
 * Ventana más pequeña que permite la app de escritorio (`Main.kt`: `window.minimumSize` =
 * 900x600) con texto al 200 %. En escritorio el editor es un `Dialog` que ocupa toda la escena,
 * así que el tamaño se fija en la escena de prueba (un `Box` más chico lo ignoraría).
 *
 * El original de Android usaba 360x240 dp: en escritorio esa ventana no puede existir y, con el
 * escalado lineal de fuente de escritorio (Android 14+ escala las fuentes grandes de forma no
 * lineal), la cabecera y el pie dejan la lista con altura cero, donde `performScrollToNode` nunca
 * termina. Por eso también se exige una altura mínima antes de desplazar.
 */
@OptIn(ExperimentalTestApi::class)
class InvoiceLineReviewCompactWindowTest {
    @Test
    fun editorFieldsSummaryAndDoneRemainReachableInTheSmallestWindowWithLargeText() =
        runDesktopComposeUiTest(width = MIN_WINDOW_WIDTH, height = MIN_WINDOW_HEIGHT) {
            val editable = line()
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 2f),
                ) {
                    FacturaStockTheme {
                        InvoiceLineReviewScreen(
                            state = State(
                                draftId = DRAFT_ID,
                                isLoading = false,
                                lines = listOf(editable),
                                editor = Editor(editable),
                                summary = Summary(
                                    lineSum = "S/ 11.80",
                                    invoiceTotal = "S/ 11.80",
                                    exactDifference = "S/ 0.00",
                                    hasDifference = false,
                                ),
                            ),
                            onAction = {},
                        )
                    }
                }
            }

            onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_LIST)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performScrollToNode(
                    hasTestTag(InvoiceLineReviewTestTags.editorField(FieldId.TOTAL)),
                )
            onNodeWithTag(InvoiceLineReviewTestTags.editorField(FieldId.TOTAL))
                .assertIsDisplayed()
            onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_SUMMARY)
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithTag(InvoiceLineReviewTestTags.EDITOR_DONE)
                .performScrollTo()
                .assertIsDisplayed()
        }

    private fun line(): Line {
        val values = mapOf(
            FieldId.DESCRIPTION to "Café molido",
            FieldId.CODE to "SKU-1",
            FieldId.QUANTITY to "1",
            FieldId.UNIT to "NIU",
            FieldId.UNIT_COST to "10.00",
            FieldId.DISCOUNT to "0.00",
            FieldId.IGV to "1.80",
            FieldId.TOTAL to "11.80",
        )
        return Line(
            lineId = LineId.from(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            position = 0,
            fields = FieldId.entries.map { field ->
                Field(
                    id = field,
                    value = values.getValue(field),
                    origin = ValueOrigin.OCR,
                    ocrValue = values.getValue(field),
                    calculatedValue = if (field == FieldId.TOTAL) "11.70" else null,
                    confidence = Confidence.HIGH,
                )
            },
            confidence = Confidence.HIGH,
            confidencePercent = 96,
            requiresReview = false,
        )
    }

    private companion object {
        /** `Main.kt` MIN_WIDTH_PX / MIN_HEIGHT_PX (densidad 1 en la escena de prueba). */
        const val MIN_WINDOW_WIDTH = 900
        const val MIN_WINDOW_HEIGHT = 600
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
    }
}
