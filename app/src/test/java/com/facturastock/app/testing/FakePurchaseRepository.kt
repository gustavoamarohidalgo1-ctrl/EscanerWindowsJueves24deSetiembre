package com.facturastock.app.testing

import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseDuplicateCanonicalizer
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.RecordedPurchase
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import com.facturastock.app.domain.repository.PurchaseRepository

class FakePurchaseRepository(
    initialPurchases: List<RecordedPurchase> = emptyList(),
) : PurchaseRepository {
    val purchases = initialPurchases.toMutableList()
    /** Sentinel usado por pruebas para demostrar que no existe una escritura previa al commit. */
    val overrides: List<PurchaseDuplicateOverride> = emptyList()

    override suspend fun findById(purchaseId: PurchaseId): RecordedPurchase? =
        purchases.firstOrNull { it.purchaseId == purchaseId }

    override suspend fun findDuplicateCandidates(
        businessId: BusinessId,
        supplierId: SupplierId?,
        supplierRuc: String,
        documentType: PurchaseDocumentType,
    ): List<RecordedPurchase> {
        val canonicalRuc = PurchaseDuplicateCanonicalizer.ruc(supplierRuc)
        return purchases.filter { purchase ->
            purchase.businessId == businessId &&
                purchase.documentType == documentType &&
                (purchase.supplierId == supplierId ||
                    PurchaseDuplicateCanonicalizer.ruc(purchase.supplierRuc) == canonicalRuc)
        }
    }
}

class FakePurchaseOverrideAuthorizationRepository(
    var actor: PurchaseOverrideActor? = PurchaseOverrideActor(
        actorId = "test-owner",
        role = PurchaseOverrideRole.OWNER,
    ),
) : PurchaseOverrideAuthorizationRepository {
    override suspend fun currentActor(businessId: BusinessId): PurchaseOverrideActor? = actor
}
