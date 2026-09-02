package com.facturastock.app.data.repository

import com.facturastock.app.data.local.dao.LedgerDocumentIdentityRow
import com.facturastock.app.domain.model.PurchaseDocumentIdentity
import com.facturastock.app.domain.model.id.PurchaseId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSyncReconciliationRepositoryTest {

    @Test
    fun `la proyeccion conserva principal y todas las excepciones por identidad`() {
        val primary = purchaseId(1)
        val firstOverride = purchaseId(2)
        val secondOverride = purchaseId(3)

        val index = listOf(
            row(primary, number = "000123"),
            row(firstOverride, number = "000123"),
            row(secondOverride, number = "000123"),
        ).toLocalDocumentIndex()

        val identity = requireNotNull(
            PurchaseDocumentIdentity.normalized(RUC, "INVOICE", "F001", "000123"),
        )
        assertEquals(listOf(primary, firstOverride, secondOverride), index.getValue(identity))
    }

    @Test
    fun `la proyeccion no colapsa correlativos con distinto padding ni identidad sin RUC`() {
        val padded = purchaseId(1)
        val unpadded = purchaseId(2)
        val withoutRuc = purchaseId(3)

        val index = listOf(
            row(padded, number = "000123"),
            row(unpadded, number = "123"),
            row(withoutRuc, number = "000123", ruc = null),
        ).toLocalDocumentIndex()

        assertEquals(2, index.size)
        assertEquals(
            listOf(padded),
            index.getValue(
                requireNotNull(
                    PurchaseDocumentIdentity.normalized(RUC, "INVOICE", "F001", "000123"),
                ),
            ),
        )
        assertEquals(
            listOf(unpadded),
            index.getValue(
                requireNotNull(
                    PurchaseDocumentIdentity.normalized(RUC, "INVOICE", "F001", "123"),
                ),
            ),
        )
        assertTrue(index.values.flatten().contains(padded))
        assertTrue(index.values.flatten().contains(unpadded))
        assertFalse(index.values.flatten().contains(withoutRuc))
    }

    private fun row(
        purchaseId: PurchaseId,
        number: String,
        ruc: String? = RUC,
    ) = LedgerDocumentIdentityRow(
        purchaseId = purchaseId.value,
        supplierRuc = ruc,
        documentType = "INVOICE",
        documentSeries = "F001",
        documentNumber = number,
    )

    private companion object {
        const val RUC = "20123456789"

        fun purchaseId(seed: Int): PurchaseId = PurchaseId.from(
            UUID.fromString("00000000-0000-4000-8000-%012d".format(seed)),
        )
    }
}
