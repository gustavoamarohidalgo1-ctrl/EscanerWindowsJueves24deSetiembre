package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.RecordedPurchase
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId

/** Lectura local de compras. Las excepciones de duplicado solo se escriben con el posting. */
interface PurchaseRepository {
    suspend fun findById(purchaseId: PurchaseId): RecordedPurchase?

    suspend fun findDuplicateCandidates(
        businessId: BusinessId,
        supplierId: SupplierId?,
        supplierRuc: String,
        documentType: PurchaseDocumentType,
    ): List<RecordedPurchase>
}

/** Principal offline suministrado por la política de acceso local, nunca por un valor de UI. */
interface PurchaseOverrideAuthorizationRepository {
    suspend fun currentActor(businessId: BusinessId): PurchaseOverrideActor?
}
