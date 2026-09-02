package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireRucOrNull
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.PurchaseStatus

/** Hecho remoto inmutable recibido por pull; la PK incluye la secuencia monotónica. */
@Entity(
    tableName = "remote_purchase_changes",
    primaryKeys = ["cloudBusinessId", "seq"],
    indices = [
        Index(value = ["cloudBusinessId", "purchaseId"]),
        Index(value = ["cloudBusinessId", "receiptId"]),
    ],
)
data class RemotePurchaseChangeEntity(
    val cloudBusinessId: String,
    val seq: Long,
    val purchaseId: String,
    val status: String,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: String,
    val currency: String,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val totalMinorUnits: Long,
    val receiptId: String,
    val syncedAtMillis: Long?,
) {
    init {
        requireCanonicalUuid(cloudBusinessId, "cloudBusinessId")
        require(seq in 1..MAX_SAFE_SYNC_SEQUENCE)
        requireCanonicalUuid(purchaseId, "purchaseId")
        requireEnumName<PurchaseStatus>(status, "status")
        require(status == PurchaseStatus.POSTED.name || status == PurchaseStatus.VOIDED.name)
        requireText(documentType, "documentType", 32)
        requireText(documentSeries, "documentSeries", 20)
        requireText(documentNumber, "documentNumber", 32)
        requireText(issueDate, "issueDate", 10)
        requireCurrencyCode(currency, "currency")
        requireRucOrNull(supplierRuc, "supplierRuc")
        requireText(supplierLegalName, "supplierLegalName", 200)
        requireText(receiptId, "receiptId", 256)
        require(syncedAtMillis == null || syncedAtMillis >= 0L)
    }
}
