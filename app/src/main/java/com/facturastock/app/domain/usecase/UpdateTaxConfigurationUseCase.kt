package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.repository.AppConfigurationRepository

/**
 * Actualiza la tasa de impuesto y la política de costos. Aplica solo a cálculos futuros: las
 * compras ya registradas nunca se recalculan.
 */
class UpdateTaxConfigurationUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
) {
    suspend operator fun invoke(taxRate: TaxRate, costPolicy: CostPolicy) {
        appConfigurationRepository.updateTaxRate(taxRate)
        appConfigurationRepository.updateCostPolicy(costPolicy)
    }
}
