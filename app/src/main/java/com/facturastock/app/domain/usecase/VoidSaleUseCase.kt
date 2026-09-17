package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidRepository
import com.facturastock.app.domain.repository.SaleVoidResult

class VoidSaleUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: SaleVoidRepository,
) {
    suspend fun preview(
        businessId: BusinessId,
        saleId: SaleId,
    ): SaleVoidPreviewResult {
        if (configuration.current().activeBusinessId != businessId) {
            return SaleVoidPreviewResult.NoActiveBusiness
        }
        return repository.preview(businessId, saleId)
    }

    suspend fun confirm(preview: SaleVoidPreview): SaleVoidResult {
        if (configuration.current().activeBusinessId != preview.businessId) {
            return SaleVoidResult.NoActiveBusiness
        }
        return repository.confirm(preview)
    }
}
