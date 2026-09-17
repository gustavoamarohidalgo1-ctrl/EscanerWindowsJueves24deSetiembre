package com.facturastock.app.feature.debtors

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtLine
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.repository.SaleVoidLine
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidRepository
import com.facturastock.app.domain.repository.SaleVoidResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

internal class FakeDebtVoidRepository : SaleVoidRepository {
    var previewResult: SaleVoidPreviewResult = SaleVoidPreviewResult.Ready(DebtDeletionFixtures.preview())
    var confirmResult: SaleVoidResult = SaleVoidResult.Voided
    var previewGate: CompletableDeferred<Unit>? = null
    var confirmGate: CompletableDeferred<Unit>? = null
    var previewFailure: Throwable? = null
    var confirmFailure: Throwable? = null
    val previews = mutableListOf<Pair<BusinessId, SaleId>>()
    val confirmations = mutableListOf<SaleVoidPreview>()

    override suspend fun preview(
        businessId: BusinessId,
        saleId: SaleId,
    ): SaleVoidPreviewResult {
        previews += businessId to saleId
        previewGate?.await()
        previewFailure?.let { throw it }
        return previewResult
    }

    override suspend fun confirm(preview: SaleVoidPreview): SaleVoidResult {
        confirmations += preview
        confirmGate?.await()
        confirmFailure?.let { throw it }
        return confirmResult
    }
}

internal class FakeDebtDeletionRepository : DebtRepository {
    val detail = MutableStateFlow<DebtDetail?>(DebtDeletionFixtures.detail())
    val paymentCommands = mutableListOf<RecordDebtPaymentCommand>()
    val paymentBusinesses = mutableListOf<BusinessId>()
    var paymentResult: RecordDebtPaymentResult = RecordDebtPaymentResult.OnlineRequired
    var paymentFailure: Throwable? = null
    var paymentGate: CompletableDeferred<Unit>? = null

    override fun observeOpen(businessId: BusinessId) = observeAll(businessId).map { list -> list.filter { it.status == DebtStatus.OPEN } }

    override fun observeAll(businessId: BusinessId) = detail.map { listOfNotNull(it?.debt?.takeIf { debt -> debt.businessId == businessId }) }

    override fun observeDetail(
        businessId: BusinessId,
        debtId: DebtId,
    ) = detail.map {
        it?.takeIf { item -> item.debt.businessId == businessId && item.debt.debtId == debtId }
    }

    override suspend fun recordPayment(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
    ): RecordDebtPaymentResult {
        paymentCommands += command
        paymentBusinesses += businessId
        paymentGate?.await()
        paymentFailure?.let { throw it }
        return paymentResult
    }
}

internal object DebtDeletionFixtures {
    val now: Instant = Instant.parse("2026-09-12T15:00:00Z")
    val businessId = BusinessId.from(UUID(1L, 1L))
    val debtId = DebtId.from(UUID(2L, 1L))
    val saleId = SaleId.from(UUID(3L, 1L))

    fun money(value: String) = Money.fromMajor(value, CurrencyCode.of("PEN"))

    fun summary(paid: Boolean = false) =
        DebtSummary(
            debtId,
            businessId,
            saleId,
            "Ana Torres",
            money("20"),
            money(if (paid) "0" else "15"),
            if (paid) DebtStatus.PAID else DebtStatus.OPEN,
            1,
            null,
            2L,
            now,
            now,
            now.takeIf { paid },
        )

    fun detail(debt: DebtSummary = summary()) =
        DebtDetail(
            debt,
            listOf(
                DebtLine(
                    SaleLineId.from(UUID(4L, 1L)),
                    ProductId.from(UUID(5L, 1L)),
                    "Arroz",
                    null,
                    Quantity.of("2"),
                    money("10"),
                    debt.originalAmount,
                ),
            ),
            emptyList(),
        )

    fun preview(
        debt: DebtSummary = summary(),
        hash: String = "a".repeat(64),
    ) = SaleVoidPreview(
        debt.businessId,
        debt.saleId,
        debt.createdAt,
        debt.originalAmount,
        Money.ofMinor(debt.originalAmount.minorUnits - debt.balance.minorUnits, debt.originalAmount.currency),
        debt.balance,
        listOf(SaleVoidLine("Arroz", "Principal", "NIU", Quantity.of("2"))),
        hash,
    )
}
