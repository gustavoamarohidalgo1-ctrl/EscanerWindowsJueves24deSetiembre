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
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.normalizeOptionalDebtText

/** Pago inmutable; `expectedDebtVersion` fija la transición CAS que produjo. */
@Entity(
    tableName = "debt_payments",
    foreignKeys = [
        ForeignKey(
            entity = DebtEntity::class,
            parentColumns = ["debtId"],
            childColumns = ["debtId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["debtId", "expectedDebtVersion"], unique = true),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["businessId", "occurredAt", "paymentId"]),
    ],
)
data class DebtPaymentEntity(
    @PrimaryKey val paymentId: String,
    val debtId: String,
    val businessId: String,
    val currencyCode: String,
    val amountMinorUnits: Long,
    val method: String,
    val note: String? = null,
    val reference: String? = null,
    val expectedDebtVersion: Long,
    val balanceAfterMinorUnits: Long,
    val idempotencyKey: String,
    val occurredAt: Long,
    val createdAt: Long,
) {
    init {
        requireCanonicalUuid(paymentId, "paymentId")
        requireCanonicalUuid(debtId, "debtId")
        requireCanonicalUuid(businessId, "businessId")
        requireCurrencyCode(currencyCode, "currencyCode")
        requireMinorUnits(amountMinorUnits, "amountMinorUnits")
        require(amountMinorUnits > 0L) { "El pago debe ser positivo" }
        requireEnumName<DebtPaymentMethod>(method, "method")
        require(note == normalizeOptionalDebtText(note, 500)) { "note debe estar normalizada" }
        require(reference == normalizeOptionalDebtText(reference, 120)) {
            "reference debe estar normalizada"
        }
        require(expectedDebtVersion >= 1L) { "expectedDebtVersion debe ser positiva" }
        requireMinorUnits(balanceAfterMinorUnits, "balanceAfterMinorUnits")
        requireText(idempotencyKey, "idempotencyKey", 256)
        require(idempotencyKey == "debt-payment:v1:$debtId:$paymentId") {
            "idempotencyKey no corresponde al pago"
        }
        require(occurredAt >= 0L) { "occurredAt no puede ser negativo" }
        require(createdAt >= occurredAt) { "createdAt no puede preceder occurredAt" }
    }
}
