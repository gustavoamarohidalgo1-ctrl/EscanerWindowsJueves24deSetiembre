package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ProductProfitRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class UpdateProductSalePriceUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val productRepository: ProductRepository,
) {
    suspend operator fun invoke(
        productId: ProductId,
        expectedVersion: Long,
        salePrice: Money,
    ): ProductSalePriceMutationResult {
        if (expectedVersion < 1L || !ProductSalePricePolicy.supports(salePrice)) {
            return ProductSalePriceMutationResult.InvalidPrice
        }
        val configuration = appConfigurationRepository.current()
        val businessId = configuration.activeBusinessId
            ?: return ProductSalePriceMutationResult.NoActiveBusiness
        if (salePrice.currency != configuration.currency) {
            return ProductSalePriceMutationResult.CurrencyMismatch(
                expected = configuration.currency,
                actual = salePrice.currency,
            )
        }
        return productRepository.updateSalePrice(
            businessId = businessId,
            productId = productId,
            expectedVersion = expectedVersion,
            salePrice = salePrice,
        )
    }
}

class ObserveProductProfitsUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val productProfitRepository: ProductProfitRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<ProductProfit>> = appConfigurationRepository.observe()
        .map { it.activeBusinessId }
        .distinctUntilChanged()
        .flatMapLatest { businessId ->
            if (businessId == null) flowOf(emptyList())
            else productProfitRepository.observeForBusiness(businessId)
        }
}
