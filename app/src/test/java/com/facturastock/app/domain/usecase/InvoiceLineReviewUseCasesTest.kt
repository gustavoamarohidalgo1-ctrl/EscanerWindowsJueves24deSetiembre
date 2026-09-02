package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceLineReviewUseCasesTest {
    private val validator = InvoiceLineReviewValidator()
    private val calculator = InvoiceLineReviewCalculator()

    @Test
    fun `summary reports an exact signed three-cent difference`() {
        val edit = snapshot(
            line(seed = 1, position = 0, total = "100.00"),
            line(seed = 2, position = 1, total = "18.03"),
        )

        val summary = calculator.summarize(
            edit = edit,
            invoiceTotal = Money.ofMinor(11_800L, PEN),
            currency = PEN,
        )

        assertEquals(11_803L, summary.lineSum?.minorUnits)
        assertEquals(3L, summary.difference?.minorUnits)
        assertEquals(PEN, summary.difference?.currency)
        assertTrue(summary.unresolvedLineIds.isEmpty())
        assertFalse(summary.arithmeticOverflow)
    }

    @Test
    fun `a missing total remains unresolved and is never changed into zero`() {
        val unresolved = line(seed = 1, position = 0, total = null)

        val summary = calculator.summarize(
            edit = snapshot(unresolved),
            invoiceTotal = Money.ofMinor(10_000L, PEN),
            currency = PEN,
        )

        assertNull(summary.lineSum)
        assertNull(summary.difference)
        assertEquals(setOf(unresolved.lineId), summary.unresolvedLineIds)
    }

    @Test
    fun `signed printed line totals participate in the exact sum without changing sign`() {
        val credit = line(seed = 1, position = 0, total = "-0.03")

        val summary = calculator.summarize(
            edit = snapshot(credit),
            invoiceTotal = Money.ofMinor(0L, PEN),
            currency = PEN,
        )

        assertEquals(-3L, summary.lineSum?.minorUnits)
        assertEquals(-3L, summary.difference?.minorUnits)
        assertTrue(validator.validate(credit, PEN).isValid)
    }

    @Test
    fun `calculator exposes a comparable value but never invents eighteen percent IGV`() {
        val printed = line(
            seed = 1,
            position = 0,
            quantity = "1",
            unitCost = "100.00",
            discount = null,
            igv = null,
            total = "118.00",
            taxTreatment = InventoryTaxTreatment.INCLUDED,
        )

        val recalculated = calculator.recalculate(printed, PEN)

        assertEquals("100.00", recalculated.total.calculated)
        assertEquals("118.00", recalculated.total.selectedValue)
        assertEquals(InvoiceLineValueSource.OCR, recalculated.total.selectedSource)
        assertNull(recalculated.igv.selectedValue)
    }

    @Test
    fun `written contributor selects the calculation while OCR and written values stay separate`() {
        val printed = line(
            seed = 1,
            position = 0,
            quantity = "1",
            unitCost = "100.00",
            igv = "18.00",
            total = "117.99",
            taxTreatment = InventoryTaxTreatment.EXCLUDED,
        )
        val changedQuantity = printed.copy(
            quantity = printed.quantity.copy(
                written = "2",
                selectedSource = InvoiceLineValueSource.WRITTEN,
            ),
        )

        val recalculated = calculator.recalculate(changedQuantity, PEN)

        assertEquals("117.99", recalculated.total.ocr)
        assertEquals("218.00", recalculated.total.calculated)
        assertEquals("218.00", recalculated.total.selectedValue)
        assertEquals(InvoiceLineValueSource.CALCULATED, recalculated.total.selectedSource)
    }

    @Test
    fun `validation keeps required malformed and negative fields distinguishable`() {
        val invalid = line(seed = 1, position = 0).copy(
            description = written(" "),
            quantity = written("-"),
            unitCost = written("-1.00"),
            discount = written("texto"),
            igv = written("1.234"),
        )

        val errors = validator.validate(invalid, PEN).errors.associate { it.field to it.code }

        assertEquals(
            InvoiceLineValidationErrorCode.REQUIRED,
            errors[InvoiceLineEditField.DESCRIPTION],
        )
        assertEquals(
            InvoiceLineValidationErrorCode.INVALID_DECIMAL,
            errors[InvoiceLineEditField.QUANTITY],
        )
        assertEquals(
            InvoiceLineValidationErrorCode.NEGATIVE_AMOUNT,
            errors[InvoiceLineEditField.UNIT_COST],
        )
        assertEquals(
            InvoiceLineValidationErrorCode.INVALID_AMOUNT,
            errors[InvoiceLineEditField.DISCOUNT],
        )
        // PEN has two fraction digits; no rounding is silently applied.
        assertEquals(
            InvoiceLineValidationErrorCode.INVALID_AMOUNT,
            errors[InvoiceLineEditField.IGV],
        )
    }

    private fun snapshot(vararg lines: InvoiceLineEdit) = InvoiceLinesEdit(
        draftId = DRAFT_ID,
        lines = lines.toList(),
        revision = 1L,
        updatedAt = NOW,
    )

    private fun line(
        seed: Int,
        position: Int,
        quantity: String = "1",
        unitCost: String? = "1.00",
        discount: String? = null,
        igv: String? = null,
        total: String? = "1.00",
        taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.EXEMPT,
    ) = InvoiceLineEdit(
        lineId = lineId(seed),
        position = position,
        origin = InvoiceLineEditOrigin.OCR,
        sourcePosition = position,
        ocrRawText = "Producto $seed $quantity ${total.orEmpty()}",
        taxTreatment = taxTreatment,
        description = ocr("Producto $seed"),
        quantity = ocr(quantity),
        unitCost = unitCost?.let(::ocr) ?: InvoiceLineEditValue(),
        discount = discount?.let(::ocr) ?: InvoiceLineEditValue(),
        igv = igv?.let(::ocr) ?: InvoiceLineEditValue(),
        total = total?.let(::ocr) ?: InvoiceLineEditValue(),
        confidencePermille = 950,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun ocr(value: String) = InvoiceLineEditValue(
        ocr = value,
        selectedSource = InvoiceLineValueSource.OCR,
    )

    private fun written(value: String) = InvoiceLineEditValue(
        written = value,
        selectedSource = InvoiceLineValueSource.WRITTEN,
    )

    private fun lineId(seed: Int): LineId = LineId.from(
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed)),
    )

    private companion object {
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
    }
}
