package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.SupplierId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceHeaderReviewValidatorTest {
    private val validator = InvoiceHeaderReviewValidator()

    @Test
    fun `posting identity fields are required and empty financial optional fields remain valid`() {
        val optionalFieldsEmpty = validEdit().copy(
            supplierLegalName = null,
            subtotal = null,
            igv = null,
            otherCharges = null,
        )

        assertTrue(validator.validate(optionalFieldsEmpty).isValid)

        val missing = optionalFieldsEmpty.copy(
            supplierRuc = " ",
            documentType = null,
            documentSeries = null,
            documentNumber = "",
            issueDate = null,
            currency = "",
            total = null,
        )
        val validation = validator.validate(missing)

        assertFalse(validation.isValid)
        assertEquals(
            listOf(
                InvoiceHeaderEditField.SUPPLIER_RUC,
                InvoiceHeaderEditField.DOCUMENT_TYPE,
                InvoiceHeaderEditField.DOCUMENT_SERIES,
                InvoiceHeaderEditField.DOCUMENT_NUMBER,
                InvoiceHeaderEditField.ISSUE_DATE,
                InvoiceHeaderEditField.CURRENCY,
                InvoiceHeaderEditField.TOTAL,
            ),
            validation.errors.map(InvoiceHeaderValidationError::field),
        )
        assertTrue(validation.errors.all { it.code == InvoiceHeaderValidationErrorCode.REQUIRED })
    }

    @Test
    fun `malformed written optional amounts are errors instead of being silently discarded`() {
        val validation = validator.validate(
            validEdit().copy(
                supplierRuc = "123",
                documentType = "UNKNOWN",
                documentSeries = "ABCDE",
                documentNumber = "42-A",
                issueDate = "31/02/2026",
                currency = "PEN",
                subtotal = "12,345",
                igv = "texto",
                otherCharges = "1.234",
                total = "12,345",
            ),
        )

        assertEquals(
            mapOf(
                InvoiceHeaderEditField.SUPPLIER_RUC to
                    InvoiceHeaderValidationErrorCode.INVALID_RUC,
                InvoiceHeaderEditField.DOCUMENT_TYPE to
                    InvoiceHeaderValidationErrorCode.INVALID_DOCUMENT_TYPE,
                InvoiceHeaderEditField.DOCUMENT_SERIES to
                    InvoiceHeaderValidationErrorCode.INVALID_SERIES,
                InvoiceHeaderEditField.DOCUMENT_NUMBER to
                    InvoiceHeaderValidationErrorCode.INVALID_NUMBER,
                InvoiceHeaderEditField.ISSUE_DATE to
                    InvoiceHeaderValidationErrorCode.INVALID_DATE,
                InvoiceHeaderEditField.SUBTOTAL to
                    InvoiceHeaderValidationErrorCode.INVALID_AMOUNT,
                InvoiceHeaderEditField.IGV to
                    InvoiceHeaderValidationErrorCode.INVALID_AMOUNT,
                InvoiceHeaderEditField.OTHER_CHARGES to
                    InvoiceHeaderValidationErrorCode.INVALID_AMOUNT,
                InvoiceHeaderEditField.TOTAL to
                    InvoiceHeaderValidationErrorCode.INVALID_AMOUNT,
            ),
            validation.errors.associate { it.field to it.code },
        )
        assertEquals(
            InvoiceHeaderValidationErrorCode.INVALID_CURRENCY,
            validator.validate(validEdit().copy(currency = "SOLES"))
                .errorFor(InvoiceHeaderEditField.CURRENCY)
                ?.code,
        )
    }

    @Test
    fun `checksum mismatch and exact three cent difference are warnings only`() {
        val validation = validator.validate(
            validEdit().copy(
                supplierRuc = "12345678901",
                subtotal = "100.00",
                igv = "18,00",
                otherCharges = null,
                total = "118.03",
            ),
        )

        assertTrue(validation.isValid)
        assertEquals(
            listOf(
                InvoiceHeaderValidationWarningCode.RUC_CHECKSUM_MISMATCH,
                InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE,
            ),
            validation.warnings.map(InvoiceHeaderValidationWarning::code),
        )
        val difference = validation.warnings.single {
            it.code == InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE
        }.difference
        assertEquals(3L, difference?.minorUnits)
        assertEquals(CurrencyCode.of("PEN"), difference?.currency)
    }

    @Test
    fun `signed charges remain exact and are not normalized away`() {
        val edit = validEdit().copy(
            subtotal = "100.00",
            igv = "18.00",
            otherCharges = "-0,03",
            total = "117.97",
        )

        val validation = validator.validate(edit)
        val projected = validator.project(edit, existingDraft())

        assertTrue(validation.isValid)
        assertTrue(validation.warnings.none {
            it.code == InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE
        })
        assertEquals(-3L, projected.otherCharges?.minorUnits)
        assertEquals(11_797L, projected.total?.minorUnits)
    }

    @Test
    fun `projection normalizes resolved values preserves partial input and clears explicit blanks`() {
        val original = existingDraft()
        val resolved = validator.project(
            validEdit().copy(
                supplierLegalName = "  Nuevo Proveedor SAC  ",
                documentSeries = "f001",
                documentNumber = "00000042",
                issueDate = "10/08/2026",
                subtotal = "100,00",
                igv = "18.00",
                otherCharges = "+0.20",
                total = "118.20",
            ),
            original,
        )

        // La identidad del catálogo se conserva mientras el RUC siga siendo el mismo; editar la
        // razón social no crea ni desvincula proveedores implícitamente.
        assertEquals(original.supplierId, resolved.supplierId)
        assertEquals("Nuevo Proveedor SAC", resolved.supplierLegalNameNormalized)
        assertEquals(PurchaseDocumentType.INVOICE, resolved.documentType)
        assertEquals("F001-00000042", resolved.documentNumberNormalized)
        assertEquals(LocalDate.of(2026, 8, 10), resolved.issueDate)
        assertEquals(10_000L, resolved.subtotal?.minorUnits)
        assertEquals(1_800L, resolved.tax?.minorUnits)
        assertEquals(20L, resolved.otherCharges?.minorUnits)
        assertEquals(11_820L, resolved.total?.minorUnits)

        val partial = validator.project(
            validEdit().copy(
                supplierRuc = "20",
                documentSeries = "F00?",
                documentNumber = "42A",
                issueDate = "10/",
                currency = "PE",
                subtotal = "12,345",
                igv = "?",
                otherCharges = "-",
                total = "118.999",
            ),
            original,
        )

        assertEquals(original.supplierId, partial.supplierId)
        assertEquals(original.supplierRucNormalized, partial.supplierRucNormalized)
        assertEquals(original.documentNumberNormalized, partial.documentNumberNormalized)
        assertEquals(original.issueDate, partial.issueDate)
        assertEquals(original.currency, partial.currency)
        assertEquals(original.subtotal, partial.subtotal)
        assertEquals(original.tax, partial.tax)
        assertEquals(original.otherCharges, partial.otherCharges)
        assertEquals(original.total, partial.total)
        assertEquals("10/", partial.issueDateRaw)

        val cleared = validator.project(
            validEdit().copy(
                supplierRuc = "",
                documentSeries = "",
                documentNumber = "",
                issueDate = "",
                subtotal = "",
                igv = "",
                otherCharges = "",
                total = "",
            ),
            original,
        )

        assertNull(cleared.supplierId)
        assertNull(cleared.supplierRucNormalized)
        assertNull(cleared.documentNumberNormalized)
        assertNull(cleared.issueDate)
        assertNull(cleared.subtotal)
        assertNull(cleared.tax)
        assertNull(cleared.otherCharges)
        assertNull(cleared.total)
    }

    private fun validEdit(): InvoiceHeaderEdit = InvoiceHeaderEdit(
        draftId = DRAFT_ID,
        supplierRuc = "20123456786",
        supplierLegalName = "Proveedor Andino SAC",
        documentType = PurchaseDocumentType.INVOICE.name,
        documentSeries = "F001",
        documentNumber = "42",
        issueDate = "10/08/2026",
        currency = "PEN",
        subtotal = "100.00",
        igv = "18.00",
        otherCharges = "0.00",
        total = "118.00",
        revision = 1L,
        updatedAt = NOW,
    )

    private fun existingDraft(): InvoiceDraft {
        val currency = CurrencyCode.of("PEN")
        return InvoiceDraft(
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            status = DraftStatus.NEEDS_REVIEW,
            supplierId = SUPPLIER_ID,
            supplierRucRaw = "20123456786",
            supplierRucNormalized = "20123456786",
            supplierLegalNameRaw = "Proveedor Andino SAC",
            supplierLegalNameNormalized = "Proveedor Andino SAC",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumberRaw = "F001-42",
            documentNumberNormalized = "F001-42",
            issueDateRaw = "10/08/2026",
            issueDate = LocalDate.of(2026, 8, 10),
            currency = currency,
            subtotal = Money.ofMinor(10_000L, currency),
            tax = Money.ofMinor(1_800L, currency),
            otherCharges = Money.zero(currency),
            total = Money.ofMinor(11_800L, currency),
            createdAt = NOW,
            updatedAt = NOW,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000023"),
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
        )
        val SUPPLIER_ID: SupplierId = SupplierId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000002"),
        )
    }
}
