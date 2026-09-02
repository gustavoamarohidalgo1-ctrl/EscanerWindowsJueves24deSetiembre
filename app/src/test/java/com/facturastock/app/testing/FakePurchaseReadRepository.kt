package com.facturastock.app.testing

import com.facturastock.app.domain.model.BusinessAuditEventRead
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseHistoryPage
import com.facturastock.app.domain.model.PurchaseHistoryRequest
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PurchaseReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Fake reactivo de las proyecciones de compras. Cada negocio tiene un StateFlow independiente,
 * igual que las consultas Room que sustituye en pruebas.
 */
class FakePurchaseReadRepository : PurchaseReadRepository {
    private val purchaseFlows = mutableMapOf<BusinessId, MutableStateFlow<List<PurchaseReadSummary>>>()
    private val detailFlows =
        mutableMapOf<Pair<BusinessId, PurchaseId>, MutableStateFlow<PurchaseReadDetail?>>()

    private val mutableObservedPurchaseBusinesses = mutableListOf<BusinessId>()
    private val mutableObservedHistoryRequests =
        mutableListOf<Pair<BusinessId, PurchaseHistoryRequest>>()
    private val mutableObservedDetails = mutableListOf<Pair<BusinessId, PurchaseId>>()

    val observedPurchaseBusinesses: List<BusinessId>
        get() = mutableObservedPurchaseBusinesses.toList()

    val observedDetails: List<Pair<BusinessId, PurchaseId>>
        get() = mutableObservedDetails.toList()

    val observedHistoryRequests: List<Pair<BusinessId, PurchaseHistoryRequest>>
        get() = mutableObservedHistoryRequests.toList()

    /** Fallo programable para verificar que la pantalla puede revivir un observer terminado. */
    var observePurchasesFailure: Throwable? = null

    override fun observePurchases(businessId: BusinessId): Flow<List<PurchaseReadSummary>> {
        mutableObservedPurchaseBusinesses += businessId
        return flow {
            observePurchasesFailure?.let { throw it }
            emitAll(purchasesFor(businessId))
        }
    }

    override fun observePurchaseHistory(
        businessId: BusinessId,
        request: PurchaseHistoryRequest,
    ): Flow<PurchaseHistoryPage> {
        mutableObservedHistoryRequests += businessId to request
        return flow {
            observePurchasesFailure?.let { throw it }
            emitAll(
                purchasesFor(businessId).map { purchases ->
                    val matches = purchases.filter(request::matches)
                    PurchaseHistoryPage(
                        items = matches.take(request.visibleItemLimit),
                        hasMore = matches.size > request.visibleItemLimit,
                    )
                },
            )
        }
    }

    override fun observePurchase(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): Flow<PurchaseReadDetail?> {
        mutableObservedDetails += businessId to purchaseId
        return detailFor(businessId, purchaseId)
    }

    override suspend fun listRetainedImagesForRetention(
        businessId: BusinessId,
    ): List<RetainedImageRef> = retainedImagesFor(businessId)

    override suspend fun listAllRetainedImagesForRetention(): List<RetainedImageRef> =
        retainedImages.entries
            .sortedBy { (businessId, _) -> businessId.value }
            .flatMap { (_, images) -> images }

    override suspend fun listRetainedImagesForDraft(
        businessId: BusinessId,
        draftId: DraftId,
    ): List<RetainedImageRef> = retainedImagesFor(businessId).filter { it.draftId == draftId }

    override suspend fun listAuditEvents(businessId: BusinessId): List<BusinessAuditEventRead> =
        auditEvents[businessId].orEmpty()

    fun setAuditEvents(businessId: BusinessId, events: List<BusinessAuditEventRead>) {
        auditEvents[businessId] = events.toList()
    }

    /** Fija la lista plana de imágenes retenidas que el mantenimiento verá para el negocio. */
    fun setRetainedImages(businessId: BusinessId, images: List<RetainedImageRef>) {
        retainedImages[businessId] = images.toList()
    }

    private val retainedImages = mutableMapOf<BusinessId, List<RetainedImageRef>>()
    private val auditEvents = mutableMapOf<BusinessId, List<BusinessAuditEventRead>>()

    private fun retainedImagesFor(businessId: BusinessId): List<RetainedImageRef> =
        retainedImages[businessId].orEmpty()

    fun replacePurchases(
        businessId: BusinessId,
        purchases: List<PurchaseReadSummary>,
    ) {
        require(purchases.all { it.businessId == businessId })
        require(purchases.map(PurchaseReadSummary::purchaseId).distinct().size == purchases.size)
        purchasesFor(businessId).value = purchases.toList()
    }

    fun setPurchaseDetail(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        detail: PurchaseReadDetail?,
    ) {
        require(detail == null || detail.summary.businessId == businessId)
        require(detail == null || detail.summary.purchaseId == purchaseId)
        detailFor(businessId, purchaseId).value = detail
    }

    private fun purchasesFor(
        businessId: BusinessId,
    ): MutableStateFlow<List<PurchaseReadSummary>> = purchaseFlows.getOrPut(businessId) {
        MutableStateFlow(emptyList())
    }

    private fun detailFor(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): MutableStateFlow<PurchaseReadDetail?> = detailFlows.getOrPut(businessId to purchaseId) {
        MutableStateFlow(null)
    }
}
