package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.SaleCart
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.CreateSaleCartResult
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.repository.enqueueBestEffort
import kotlinx.coroutines.flow.Flow

class CreateSaleCartUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: SaleRepository,
) {
    suspend operator fun invoke(currency: CurrencyCode): CreateSaleCartResult {
        val businessId = configuration.current().activeBusinessId
            ?: return CreateSaleCartResult.NoActiveBusiness
        return forBusiness(businessId, currency)
    }

    /** Contexto explícito y ya capturado por una transición de UI; nunca mezcla dos lecturas de config. */
    suspend fun forBusiness(
        businessId: BusinessId,
        currency: CurrencyCode,
    ): CreateSaleCartResult {
        val opened = repository.createOrResume(businessId, currency)
        check(opened.cart.businessId == businessId && opened.cart.currency == currency) {
            "El repositorio devolvió un carrito fuera del contexto solicitado"
        }
        return if (opened.created) {
            CreateSaleCartResult.Created(opened.cart)
        } else {
            CreateSaleCartResult.Resumed(opened.cart)
        }
    }
}

class ObserveSaleCartUseCase(private val repository: SaleRepository) {
    operator fun invoke(saleId: SaleId): Flow<SaleCart?> = repository.observe(saleId)
}

class SaveSaleCartLineUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: SaleRepository,
) {
    suspend operator fun invoke(command: SaveSaleCartLineCommand): SaleCartMutationResult {
        val businessId = configuration.current().activeBusinessId
            ?: return SaleCartMutationResult.NoActiveBusiness
        return forBusiness(businessId, command)
    }

    /**
     * Permite terminar una edición perteneciente al carrito anterior cuando la configuración ya
     * cambió. El repositorio sigue comprobando que `saleId` pertenezca exactamente a [businessId].
     */
    suspend fun forBusiness(
        businessId: BusinessId,
        command: SaveSaleCartLineCommand,
    ): SaleCartMutationResult = repository.saveLine(businessId, command)
}

class RemoveSaleCartLineUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: SaleRepository,
) {
    suspend operator fun invoke(
        saleId: SaleId,
        saleLineId: SaleLineId,
        expectedVersion: Long,
    ): SaleCartMutationResult {
        val businessId = configuration.current().activeBusinessId
            ?: return SaleCartMutationResult.NoActiveBusiness
        return repository.removeLine(businessId, saleId, saleLineId, expectedVersion)
    }
}

class CheckoutSaleUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: SaleRepository,
    private val scheduler: PurchaseBackupScheduler = DisabledPurchaseBackupScheduler,
) {
    suspend operator fun invoke(command: CheckoutSaleCommand): CheckoutSaleResult {
        val businessId = configuration.current().activeBusinessId
            ?: return CheckoutSaleResult.NoActiveBusiness
        return repository.checkout(businessId, command).also { result ->
            if (
                result is CheckoutSaleResult.Posted ||
                result is CheckoutSaleResult.AlreadyPosted
            ) {
                scheduler.enqueueBestEffort()
            }
        }
    }
}
