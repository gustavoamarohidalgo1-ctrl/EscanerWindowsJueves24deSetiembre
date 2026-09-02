package com.facturastock.app.feature.debtors

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.DebtId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtorsContractTest {
    @Test
    fun `payment editor accepts a positive amount up to the balance`() {
        val editor = editor(amountInput = " 12,50 ")

        assertEquals(Money.fromMajor("12.50", PEN), editor.amount)
        assertTrue(editor.isValid)
    }

    @Test
    fun `payment editor rejects zero malformed and over-balance amounts`() {
        listOf("", "0", "abc", "50.01").forEach { amount ->
            val editor = editor(amountInput = amount)

            assertNull(amount, editor.amount)
            assertFalse(amount, editor.isValid)
        }
    }

    @Test
    fun `payment editor keeps optional text inside the cloud contract limits`() {
        assertTrue(
            editor(
                amountInput = "1.00",
                noteInput = "n".repeat(DebtorsContract.MAX_NOTE_LENGTH),
                referenceInput = "r".repeat(DebtorsContract.MAX_REFERENCE_LENGTH),
            ).isValid,
        )
        assertFalse(
            editor(
                amountInput = "1.00",
                noteInput = "n".repeat(DebtorsContract.MAX_NOTE_LENGTH + 1),
            ).isValid,
        )
        assertFalse(
            editor(
                amountInput = "1.00",
                referenceInput = "r".repeat(DebtorsContract.MAX_REFERENCE_LENGTH + 1),
            ).isValid,
        )
    }

    private fun editor(
        amountInput: String,
        noteInput: String = "",
        referenceInput: String = "",
    ) = DebtorsContract.PaymentEditor(
        debtId = DEBT_ID,
        expectedVersion = 1L,
        balance = Money.fromMajor("50.00", PEN),
        amountInput = amountInput,
        noteInput = noteInput,
        referenceInput = referenceInput,
    )

    private companion object {
        val PEN = CurrencyCode.of("PEN")
        val DEBT_ID = DebtId.from(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"))
    }
}
