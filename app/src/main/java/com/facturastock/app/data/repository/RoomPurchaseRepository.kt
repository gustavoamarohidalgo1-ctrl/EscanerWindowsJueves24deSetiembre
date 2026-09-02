package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.dao.PurchaseDao
import com.facturastock.app.data.local.dao.PurchaseDuplicateCandidateRow
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseDuplicateCanonicalizer
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.RecordedPurchase
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.PurchaseRepository
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.withContext

class RoomPurchaseRepository @Inject constructor(
    private val purchaseDao: PurchaseDao,
    private val dispatchers: DispatcherProvider,
) : PurchaseRepository {
    override suspend fun findById(purchaseId: PurchaseId): RecordedPurchase? =
        withContext(dispatchers.io) {
            storageCatching { purchaseDao.findRecordedRows(purchaseId.value).toRecordedPurchase() }
        }

    override suspend fun findDuplicateCandidates(
        businessId: BusinessId,
        supplierId: SupplierId?,
        supplierRuc: String,
        documentType: PurchaseDocumentType,
    ): List<RecordedPurchase> = withContext(dispatchers.io) {
        storageCatching {
            purchaseDao.listDuplicateCandidateRows(
                businessId = businessId.value,
                supplierId = supplierId?.value,
                supplierRuc = PurchaseDuplicateCanonicalizer.ruc(supplierRuc).orEmpty(),
                documentType = documentType.name,
            ).groupBy(PurchaseDuplicateCandidateRow::purchaseId)
                .values
                .mapNotNull { rows -> rows.toRecordedPurchase() }
        }
    }

    private fun List<PurchaseDuplicateCandidateRow>.toRecordedPurchase(): RecordedPurchase? {
        val first = firstOrNull() ?: return null
        val purchaseId = PurchaseId.parse(first.purchaseId) ?: return null
        val businessId = BusinessId.parse(first.businessId) ?: return null
        val draftId = DraftId.parse(first.sourceDraftId) ?: return null
        val supplierId = SupplierId.parse(first.supplierId) ?: return null
        val currency = CurrencyCode.of(first.currencyCode)
        return RecordedPurchase(
            purchaseId = purchaseId,
            businessId = businessId,
            sourceDraftId = draftId,
            supplierId = supplierId,
            supplierRuc = first.supplierRuc,
            supplierLegalName = first.supplierLegalName,
            documentType = PurchaseDocumentType.valueOf(first.documentType),
            documentSeries = first.documentSeries,
            documentNumber = first.documentNumber,
            issueDate = LocalDate.parse(first.issueDate),
            currency = currency,
            total = Money.ofMinor(first.totalMinorUnits, currency),
            status = PurchaseStatus.valueOf(first.status),
            imageHashes = mapNotNullTo(linkedSetOf(), PurchaseDuplicateCandidateRow::imageSha256),
        )
    }
}
