package com.facturastock.app.data.local.mapper

import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Conversiones entidad ↔ dominio del agregado de borrador. Las entidades ya validan sus
 * invariantes en el `init`: los importes se persisten como unidades menores `Long` más una
 * única columna de moneda, y el dominio los reconstruye como [Money] con esa moneda. Al
 * persistir, todo importe de la cabecera debe usar la moneda del borrador y el total de línea
 * la moneda de su costo unitario.
 */

internal fun InvoiceDraftEntity.toDomain(): InvoiceDraft {
    val currency = currencyCode?.let(CurrencyCode::of)
    return InvoiceDraft(
        draftId = DraftId.from(UUID.fromString(draftId)),
        businessId = BusinessId.from(UUID.fromString(businessId)),
        status = DraftStatus.valueOf(status),
        supplierId = supplierId?.let { SupplierId.from(UUID.fromString(it)) },
        supplierRucRaw = supplierRucRaw,
        supplierRucNormalized = supplierRucNormalized,
        supplierLegalNameRaw = supplierLegalNameRaw,
        supplierLegalNameNormalized = supplierLegalNameNormalized,
        documentType = documentType?.let(PurchaseDocumentType::valueOf),
        documentNumberRaw = documentNumberRaw,
        documentNumberNormalized = documentNumberNormalized,
        issueDateRaw = issueDateRaw,
        issueDate = issueDateNormalized?.let(LocalDate::parse),
        currency = currency,
        subtotal = subtotalMinorUnits?.let {
            Money.ofMinor(it, requireNotNull(currency) { "currencyCode es obligatorio con importes" })
        },
        tax = taxMinorUnits?.let {
            Money.ofMinor(it, requireNotNull(currency) { "currencyCode es obligatorio con importes" })
        },
        otherCharges = otherChargesMinorUnits?.let {
            Money.ofMinor(it, requireNotNull(currency) { "currencyCode es obligatorio con importes" })
        },
        total = totalMinorUnits?.let {
            Money.ofMinor(it, requireNotNull(currency) { "currencyCode es obligatorio con importes" })
        },
        headerConfidence = headerConfidence,
        activeOcrRunId = activeOcrRunId?.let { OcrRunId.from(UUID.fromString(it)) },
        confirmedPurchaseId = confirmedPurchaseId?.let { PurchaseId.from(UUID.fromString(it)) },
        lastError = lastError,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

internal fun InvoiceDraft.toEntity(): InvoiceDraftEntity {
    val amounts = listOfNotNull(subtotal, tax, otherCharges, total)
    require(amounts.isEmpty() || currency != null) {
        "currency es obligatoria cuando la cabecera tiene importes"
    }
    require(amounts.all { it.currency == currency }) {
        "todos los importes de la cabecera deben usar la moneda del borrador"
    }
    return InvoiceDraftEntity(
        draftId = draftId.value,
        businessId = businessId.value,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        status = status.name,
        supplierId = supplierId?.value,
        supplierRucRaw = supplierRucRaw,
        supplierRucNormalized = supplierRucNormalized,
        supplierLegalNameRaw = supplierLegalNameRaw,
        supplierLegalNameNormalized = supplierLegalNameNormalized,
        documentType = documentType?.name,
        documentNumberRaw = documentNumberRaw,
        documentNumberNormalized = documentNumberNormalized,
        issueDateRaw = issueDateRaw,
        issueDateNormalized = issueDate?.toString(),
        currencyCode = currency?.value,
        subtotalMinorUnits = subtotal?.minorUnits,
        taxMinorUnits = tax?.minorUnits,
        otherChargesMinorUnits = otherCharges?.minorUnits,
        totalMinorUnits = total?.minorUnits,
        headerConfidence = headerConfidence,
        activeOcrRunId = activeOcrRunId?.value,
        confirmedPurchaseId = confirmedPurchaseId?.value,
        lastError = lastError,
    )
}

internal fun InvoiceImageEntity.toDomain(): InvoiceImage = InvoiceImage(
    imageId = ImageId.from(UUID.fromString(imageId)),
    draftId = DraftId.from(UUID.fromString(draftId)),
    businessId = BusinessId.from(UUID.fromString(businessId)),
    pageIndex = pageIndex,
    filePath = filePath,
    sha256 = sha256,
    mimeType = mimeType,
    widthPx = widthPx,
    heightPx = heightPx,
    fileSizeBytes = fileSizeBytes,
    rotationDegrees = rotationDegrees,
    crop = if (
        cropLeftFraction != null && cropTopFraction != null &&
        cropRightFraction != null && cropBottomFraction != null
    ) {
        ImageCrop(
            left = cropLeftFraction,
            top = cropTopFraction,
            right = cropRightFraction,
            bottom = cropBottomFraction,
        )
    } else {
        null
    },
    createdAt = Instant.ofEpochMilli(createdAt),
)

internal fun InvoiceImage.toEntity(): InvoiceImageEntity = InvoiceImageEntity(
    imageId = imageId.value,
    draftId = draftId.value,
    businessId = businessId.value,
    pageIndex = pageIndex,
    filePath = filePath,
    sha256 = sha256,
    mimeType = mimeType,
    widthPx = widthPx,
    heightPx = heightPx,
    fileSizeBytes = fileSizeBytes,
    createdAt = createdAt.toEpochMilli(),
    rotationDegrees = rotationDegrees,
    cropLeftFraction = crop?.left,
    cropTopFraction = crop?.top,
    cropRightFraction = crop?.right,
    cropBottomFraction = crop?.bottom,
)

internal fun InvoiceLineEntity.toDomain(): InvoiceLine {
    val currency = unitCostCurrency?.let(CurrencyCode::of)
    return InvoiceLine(
        lineId = LineId.from(UUID.fromString(lineId)),
        draftId = DraftId.from(UUID.fromString(draftId)),
        businessId = BusinessId.from(UUID.fromString(businessId)),
        position = position,
        descriptionRaw = descriptionRaw,
        descriptionNormalized = descriptionNormalized,
        codeRaw = codeRaw,
        codeNormalized = codeNormalized,
        quantity = quantity?.let(Quantity::of),
        unitRaw = unitRaw,
        unitCodeNormalized = unitCodeNormalized,
        unitCost = unitCost?.let {
            UnitCost.of(it, requireNotNull(currency) { "unitCostCurrency es obligatorio con unitCost" })
        },
        discount = discountMinorUnits?.let {
            Money.ofMinor(it, requireNotNull(currency) { "unitCostCurrency es obligatorio con discount" })
        },
        tax = taxMinorUnits?.let {
            Money.ofMinor(it, requireNotNull(currency) { "unitCostCurrency es obligatorio con tax" })
        },
        unitId = unitId?.let { UnitId.from(UUID.fromString(it)) },
        productId = productId?.let { ProductId.from(UUID.fromString(it)) },
        lineTotal = lineTotalMinorUnits?.let {
            Money.ofMinor(it, requireNotNull(currency) { "unitCostCurrency es obligatorio con lineTotal" })
        },
        ocrConfidence = ocrConfidence,
        linkConfidence = linkConfidence,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

internal fun InvoiceLine.toEntity(): InvoiceLineEntity {
    require(discount == null || discount.minorUnits >= 0L) {
        "discount no puede ser negativo"
    }
    require(tax == null || tax.minorUnits >= 0L) {
        "tax no puede ser negativo"
    }
    val currencies = listOfNotNull(
        unitCost?.currency,
        discount?.currency,
        tax?.currency,
        lineTotal?.currency,
    ).distinct()
    require(currencies.size <= 1) {
        "todos los importes de la línea deben usar la misma moneda"
    }
    val currency = currencies.singleOrNull()
    return InvoiceLineEntity(
        lineId = lineId.value,
        draftId = draftId.value,
        businessId = businessId.value,
        position = position,
        descriptionRaw = descriptionRaw,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        descriptionNormalized = descriptionNormalized,
        codeRaw = codeRaw,
        codeNormalized = codeNormalized,
        quantity = quantity?.value?.toPlainString(),
        unitRaw = unitRaw,
        unitCodeNormalized = unitCodeNormalized,
        unitCost = unitCost?.amount?.toPlainString(),
        unitCostCurrency = currency?.value,
        discountMinorUnits = discount?.minorUnits,
        taxMinorUnits = tax?.minorUnits,
        unitId = unitId?.value,
        productId = productId?.value,
        lineTotalMinorUnits = lineTotal?.minorUnits,
        ocrConfidence = ocrConfidence,
        linkConfidence = linkConfidence,
    )
}
