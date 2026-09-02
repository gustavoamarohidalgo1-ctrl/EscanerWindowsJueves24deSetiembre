package com.facturastock.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCanonicalUuidOrNull
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireIsoDateOrNull
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseStatus

/**
 * Compra local con identidad durable. Cada borrador origina como máximo una compra y
 * [idempotencyKey] impide registrar dos veces la misma orden. El comprobante es único para el
 * proveedor dentro del negocio y [documentIdentitySlot] permite una excepción explícita y
 * auditable sin debilitar la unicidad de la identidad `PRIMARY`. Los importes usan unidades
 * menores exactas, nunca `Double`.
 */
@Entity(
    tableName = "purchases",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = InvoiceDraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["sourceDraftId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["supplierId"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sourceDraftId"], unique = true),
        Index(value = ["idempotencyKey"], unique = true),
        Index(
            value = [
                "businessId",
                "supplierId",
                "documentType",
                "documentSeries",
                "documentNumber",
                "documentIdentitySlot",
            ],
            unique = true,
        ),
        Index(value = ["businessId", "createdAt", "purchaseId"]),
        Index(value = ["businessId", "status", "createdAt", "purchaseId"]),
        Index(value = ["supplierId"]),
        Index(value = ["duplicateOverrideOfPurchaseId"]),
    ],
)
data class PurchaseEntity(
    @PrimaryKey val purchaseId: String,
    val businessId: String,
    val sourceDraftId: String,
    val supplierId: String,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: String,
    val currencyCode: String,
    val subtotalMinorUnits: Long,
    val taxMinorUnits: Long,
    val otherChargesMinorUnits: Long,
    val totalMinorUnits: Long,
    val status: String = PurchaseStatus.DRAFT.name,
    val idempotencyKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    val postedAt: Long? = null,
    val voidedAt: Long? = null,
    @ColumnInfo(defaultValue = "'PRIMARY'")
    val documentIdentitySlot: String = PRIMARY_DOCUMENT_IDENTITY_SLOT,
    val duplicateOverrideOfPurchaseId: String? = null,
    val duplicateOverrideReason: String? = null,
    val duplicateOverrideActorId: String? = null,
    val duplicateOverrideRole: String? = null,
) {
    init {
        requireCanonicalUuid(purchaseId, "purchaseId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuid(sourceDraftId, "sourceDraftId")
        requireCanonicalUuid(supplierId, "supplierId")
        requireEnumName<PurchaseDocumentType>(documentType, "documentType")
        requireText(documentSeries, "documentSeries", 4)
        requireText(documentNumber, "documentNumber", 12)
        require(InvoiceDocumentNumber.parseCanonical("$documentSeries-$documentNumber") != null) {
            "documentSeries/documentNumber deben estar en formato canónico"
        }
        requireIsoDateOrNull(issueDate, "issueDate")
        requireCurrencyCode(currencyCode, "currencyCode")
        requireEnumName<PurchaseStatus>(status, "status")
        requireText(idempotencyKey, "idempotencyKey", 256)
        require(idempotencyKey == idempotencyKey.trim()) {
            "idempotencyKey no puede tener espacios exteriores"
        }
        validateDocumentIdentity()
        requireTimestamps(createdAt, updatedAt)
        require(postedAt == null || postedAt in createdAt..updatedAt) {
            "postedAt debe estar entre createdAt y updatedAt"
        }
        require(voidedAt == null || voidedAt in createdAt..updatedAt) {
            "voidedAt debe estar entre createdAt y updatedAt"
        }
        when (PurchaseStatus.valueOf(status)) {
            PurchaseStatus.DRAFT -> require(postedAt == null && voidedAt == null) {
                "Una compra DRAFT no puede tener postedAt ni voidedAt"
            }
            PurchaseStatus.POSTED -> require(postedAt != null && voidedAt == null) {
                "Una compra POSTED requiere postedAt y no puede tener voidedAt"
            }
            PurchaseStatus.VOIDED -> require(postedAt != null && voidedAt != null && voidedAt >= postedAt) {
                "Una compra VOIDED requiere postedAt y voidedAt correlativos"
            }
        }
    }

    private fun validateDocumentIdentity() {
        val overrideFields = listOf(
            duplicateOverrideOfPurchaseId,
            duplicateOverrideReason,
            duplicateOverrideActorId,
            duplicateOverrideRole,
        )
        if (documentIdentitySlot == PRIMARY_DOCUMENT_IDENTITY_SLOT) {
            require(overrideFields.all { it == null }) {
                "La identidad PRIMARY no admite metadatos de excepción"
            }
            return
        }

        require(documentIdentitySlot == sourceDraftId) {
            "La excepción documental debe usar sourceDraftId como slot"
        }
        requireCanonicalUuidOrNull(
            requireNotNull(duplicateOverrideOfPurchaseId) {
                "La excepción documental necesita compra objetivo"
            },
            "duplicateOverrideOfPurchaseId",
        )
        val reason = requireNotNull(duplicateOverrideReason) {
            "La excepción documental necesita motivo"
        }
        require(reason == reason.trim() && reason.length in 10..500) {
            "duplicateOverrideReason debe estar recortado y tener entre 10 y 500 caracteres"
        }
        val actorId = requireNotNull(duplicateOverrideActorId) {
            "La excepción documental necesita actor"
        }
        require(actorId.isNotBlank() && actorId.length in 1..128) {
            "duplicateOverrideActorId debe tener entre 1 y 128 caracteres no vacíos"
        }
        require(duplicateOverrideRole == "OWNER" || duplicateOverrideRole == "MANAGER") {
            "duplicateOverrideRole debe ser OWNER o MANAGER"
        }
    }

    private companion object {
        const val PRIMARY_DOCUMENT_IDENTITY_SLOT: String = "PRIMARY"
    }
}
