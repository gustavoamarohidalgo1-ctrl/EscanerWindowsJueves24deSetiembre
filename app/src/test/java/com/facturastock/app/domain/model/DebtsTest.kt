package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.SaleId
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DebtsTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun debtorNameUsesNfkcAndCollapsesWhitespaceWithoutChangingCapitalization() {
        assertEquals("Mar\u00eda P\u00e9rez", normalizeDebtorName("  Mar\u00eda\t  P\u00e9rez\n"))
        assertEquals("Maria", normalizeDebtorName("\uff2daria"))
        assertEquals("mar\u00eda p\u00e9rez", debtorNameSearchKey("Mar\u00eda P\u00e9rez"))
    }

    @Test
    fun debtorNameRejectsEmptyOversizedAndControlContent() {
        listOf(" ", "A", "A".repeat(121), "Ana\u0000P\u00e9rez").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { normalizeDebtorName(invalid) }
        }
    }

    @Test
    fun debtSummaryRequiresPositiveLineCountAndCoherentPaidState() {
        assertThrows(IllegalArgumentException::class.java) {
            summary(lineCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            summary(status = DebtStatus.PAID, balance = Money.zero(pen), paidAt = null)
        }
    }

    private fun summary(
        status: DebtStatus = DebtStatus.OPEN,
        balance: Money = Money.ofMinor(1_000L, pen),
        lineCount: Int = 1,
        paidAt: Instant? = null,
    ): DebtSummary = DebtSummary(
        debtId = DebtId.from(uuid(1)),
        businessId = BusinessId.from(uuid(2)),
        saleId = SaleId.from(uuid(3)),
        debtorName = "Mar\u00eda P\u00e9rez",
        originalAmount = Money.ofMinor(1_000L, pen),
        balance = balance,
        status = status,
        lineCount = lineCount,
        dueAt = null,
        version = 1L,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        paidAt = paidAt,
    )

    private fun uuid(seed: Int): UUID = UUID(seed.toLong(), seed.toLong())
}
