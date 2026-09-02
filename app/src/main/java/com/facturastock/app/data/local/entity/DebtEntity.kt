package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireMinorUnits
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.debtorNameSearchKey
import com.facturastock.app.domain.model.normalizeDebtorName

/** Proyección versionada de una cuenta por cobrar; sus productos viven en la venta enlazada. */
@Entity(
    tableName = "debts",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["saleId"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["saleId"], unique = true),
        Index(value = ["businessId", "status", "updatedAt", "debtId"]),
        Index(
            value = ["businessId", "normalizedDebtorName", "status", "updatedAt", "debtId"],
        ),
    ],
)
data class DebtEntity(
    @PrimaryKey val debtId: String,
    val businessId: String,
    val saleId: String,
    val debtorName: String,
    val normalizedDebtorName: String,
    val currencyCode: String,
    val originalAmountMinorUnits: Long,
    val balanceMinorUnits: Long,
    val status: String,
    val dueAt: Long? = null,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val paidAt: Long? = null,
) {
    init {
        requireCanonicalUuid(debtId, "debtId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuid(saleId, "saleId")
        requireText(debtorName, "debtorName", 120)
        require(debtorName == normalizeDebtorName(debtorName)) {
            "debtorName debe estar normalizado"
        }
        require(normalizedDebtorName == debtorNameSearchKey(debtorName)) {
            "normalizedDebtorName no coincide con debtorName"
        }
        requireCurrencyCode(currencyCode, "currencyCode")
        requireMinorUnits(originalAmountMinorUnits, "originalAmountMinorUnits")
        require(originalAmountMinorUnits > 0L) { "La deuda original debe ser positiva" }
        requireMinorUnits(balanceMinorUnits, "balanceMinorUnits")
        require(balanceMinorUnits <= originalAmountMinorUnits) {
            "El saldo no puede superar el importe original"
        }
        requireEnumName<DebtStatus>(status, "status")
        require(dueAt == null || dueAt >= 0L) { "dueAt no puede ser negativo" }
        require(version >= 1L) { "version debe ser positiva" }
        requireTimestamps(createdAt, updatedAt)
        require(paidAt == null || paidAt in createdAt..updatedAt) {
            "paidAt debe estar entre createdAt y updatedAt"
        }
        when (DebtStatus.valueOf(status)) {
            DebtStatus.OPEN -> require(balanceMinorUnits > 0L && paidAt == null) {
                "Una deuda OPEN requiere saldo positivo y no admite paidAt"
            }
            DebtStatus.PAID -> require(balanceMinorUnits == 0L && paidAt != null) {
                "Una deuda PAID requiere saldo cero y paidAt"
            }
        }
    }
}
