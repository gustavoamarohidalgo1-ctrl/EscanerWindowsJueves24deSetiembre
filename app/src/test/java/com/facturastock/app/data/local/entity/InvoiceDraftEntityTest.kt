package com.facturastock.app.data.local.entity

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.PurchaseDocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvoiceDraftEntityTest {
    private val businessId = "123e4567-e89b-42d3-a456-426614174000"
    private val draftId = "223e4567-e89b-42d3-a456-426614174000"
    private val purchaseId = "323e4567-e89b-42d3-a456-426614174000"

    private fun draft(
        currencyCode: String? = "PEN",
        subtotalMinorUnits: Long? = 10_000L,
        taxMinorUnits: Long? = 1_800L,
        otherChargesMinorUnits: Long? = 100L,
        totalMinorUnits: Long? = 11_800L,
    ) = InvoiceDraftEntity(
        draftId = draftId,
        businessId = businessId,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        status = DraftStatus.NEEDS_REVIEW.name,
        currencyCode = currencyCode,
        subtotalMinorUnits = subtotalMinorUnits,
        taxMinorUnits = taxMinorUnits,
        otherChargesMinorUnits = otherChargesMinorUnits,
        totalMinorUnits = totalMinorUnits,
        supplierLegalNameRaw = "PROVEEDOR SAC",
        supplierLegalNameNormalized = "Proveedor SAC",
        documentType = PurchaseDocumentType.INVOICE.name,
        supplierRucNormalized = "20123456789",
        documentNumberNormalized = "F001-00012345",
        issueDateNormalized = "2026-08-07",
        headerConfidence = 975,
        confirmedPurchaseId = purchaseId,
    )

    @Test
    fun `persiste dinero como unidades menores Long sin perder precisión`() {
        val entity = draft(totalMinorUnits = Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, entity.totalMinorUnits)
        assertEquals(10_000L, entity.subtotalMinorUnits)
        assertEquals(100L, entity.otherChargesMinorUnits)
        assertEquals("PEN", entity.currencyCode)
    }

    @Test
    fun `conserva importes negativos impresos sin cambiar el signo`() {
        val entity = draft(otherChargesMinorUnits = -3L, totalMinorUnits = -1L)

        assertEquals(-3L, entity.otherChargesMinorUnits)
        assertEquals(-1L, entity.totalMinorUnits)
    }

    @Test
    fun `exige moneda cuando la cabecera tiene importes`() {
        assertThrows(IllegalArgumentException::class.java) {
            draft(currencyCode = null)
        }
    }

    @Test
    fun `admite borrador inicial sin importes ni moneda`() {
        val entity = InvoiceDraftEntity(
            draftId = draftId,
            businessId = businessId,
            createdAt = 0L,
            updatedAt = 0L,
        )

        assertEquals(DraftStatus.CREATED.name, entity.status)
        assertEquals(null, entity.currencyCode)
    }

    @Test
    fun `valida fecha ISO, número de documento y RUC normalizados`() {
        assertThrows(IllegalArgumentException::class.java) {
            draft().copy(issueDateNormalized = "07/08/2026")
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft().copy(documentNumberNormalized = "f001 12345")
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft().copy(supplierRucNormalized = "123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft().copy(documentType = "invoice")
        }
    }

    @Test
    fun `el estado se persiste por nombre y se valida contra DraftStatus`() {
        assertEquals("NEEDS_REVIEW", draft().status)
        assertThrows(IllegalArgumentException::class.java) {
            draft().copy(status = "needs_review")
        }
    }
}
