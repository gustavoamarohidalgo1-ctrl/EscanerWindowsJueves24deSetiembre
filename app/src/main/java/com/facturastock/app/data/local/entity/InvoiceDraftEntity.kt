package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCanonicalUuidOrNull
import com.facturastock.app.data.local.requireConfidenceOrNull
import com.facturastock.app.data.local.requireCurrencyCodeOrNull
import com.facturastock.app.data.local.requireDocumentNumberOrNull
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireIsoDateOrNull
import com.facturastock.app.data.local.requireRucOrNull
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.PurchaseDocumentType

/**
 * Borrador recuperable de factura de compra. Conserva el texto OCR original (`*Raw`) junto al
 * valor normalizado de la cabecera y la confianza en escala 0..1000 (0.000 a 1.000).
 *
 * Los importes (`subtotalMinorUnits`, `taxMinorUnits`, `otherChargesMinorUnits` y
 * `totalMinorUnits`) son unidades menores `Long` con signo en la moneda [currencyCode] ISO 4217;
 * el signo impreso (por ejemplo en una nota de crédito o un cargo negativo) se conserva y nunca se
 * usan decimales binarios. Si hay importes, la moneda es obligatoria. [status] persiste
 * [DraftStatus] por nombre y
 * [confirmedPurchaseId] enlaza lógicamente con la compra publicada. La columna antecede a la
 * tabla `purchases`, por lo que no puede convertirse en FK sin recrear el agregado circular;
 * triggers v11 validan pertenencia, bloquean huérfanos y vuelven inmutable el enlace.
 */
@Entity(
    tableName = "invoice_drafts",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["supplierId"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["businessId", "updatedAt", "draftId"]),
        Index(value = ["supplierId"]),
        Index(value = ["businessId", "status", "updatedAt", "draftId"]),
    ],
)
data class InvoiceDraftEntity(
    @PrimaryKey val draftId: String,
    val businessId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String = DraftStatus.CREATED.name,
    val supplierId: String? = null,
    val supplierRucRaw: String? = null,
    val supplierRucNormalized: String? = null,
    val supplierLegalNameRaw: String? = null,
    val supplierLegalNameNormalized: String? = null,
    val documentType: String? = null,
    val documentNumberRaw: String? = null,
    val documentNumberNormalized: String? = null,
    val issueDateRaw: String? = null,
    val issueDateNormalized: String? = null,
    val currencyCode: String? = null,
    val subtotalMinorUnits: Long? = null,
    val taxMinorUnits: Long? = null,
    val otherChargesMinorUnits: Long? = null,
    val totalMinorUnits: Long? = null,
    val headerConfidence: Int? = null,
    val activeOcrRunId: String? = null,
    val confirmedPurchaseId: String? = null,
    val lastError: String? = null,
) {
    init {
        requireCanonicalUuid(draftId, "draftId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuidOrNull(supplierId, "supplierId")
        requireCanonicalUuidOrNull(activeOcrRunId, "activeOcrRunId")
        requireCanonicalUuidOrNull(confirmedPurchaseId, "confirmedPurchaseId")
        requireEnumName<DraftStatus>(status, "status")
        requireTextOrNull(supplierRucRaw, "supplierRucRaw", 64_000)
        requireRucOrNull(supplierRucNormalized, "supplierRucNormalized")
        requireTextOrNull(supplierLegalNameRaw, "supplierLegalNameRaw", 64_000)
        requireTextOrNull(supplierLegalNameNormalized, "supplierLegalNameNormalized", 512)
        documentType?.let { requireEnumName<PurchaseDocumentType>(it, "documentType") }
        requireTextOrNull(documentNumberRaw, "documentNumberRaw", 64_000)
        requireDocumentNumberOrNull(documentNumberNormalized, "documentNumberNormalized")
        requireTextOrNull(issueDateRaw, "issueDateRaw", 64_000)
        requireIsoDateOrNull(issueDateNormalized, "issueDateNormalized")
        requireCurrencyCodeOrNull(currencyCode, "currencyCode")
        requireConfidenceOrNull(headerConfidence, "headerConfidence")
        requireTextOrNull(lastError, "lastError", 512)
        val hasAmounts = subtotalMinorUnits != null || taxMinorUnits != null ||
            otherChargesMinorUnits != null || totalMinorUnits != null
        require(!hasAmounts || currencyCode != null) {
            "currencyCode es obligatorio cuando la cabecera tiene importes"
        }
        requireTimestamps(createdAt, updatedAt)
    }
}
