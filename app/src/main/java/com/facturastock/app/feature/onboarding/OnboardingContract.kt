package com.facturastock.app.feature.onboarding

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.math.BigDecimal

object OnboardingContract {
    const val BUSINESS_NAME_MAX_LENGTH = 200
    const val WAREHOUSE_NAME_MAX_LENGTH = 100
    const val DEFAULT_WAREHOUSE_NAME = "Almacén Principal"

    @Immutable
    data class State(
        val businessName: String = "",
        val ruc: String = "",
        val taxRatePercent: String = "18",
        val costPolicy: CostPolicy = CostPolicy.NET,
        val warehouseName: String = DEFAULT_WAREHOUSE_NAME,
        val isSaving: Boolean = false,
        val rucChecksumWarning: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val isRucWellFormed: Boolean
            get() = ruc.isBlank() || RucValidator.isWellFormed(ruc)

        val isTaxRateValid: Boolean
            get() = taxRatePercent.trim().toBigDecimalOrNull()
                ?.let { it >= BigDecimal.ZERO && it <= BigDecimal("100") } == true

        val canSave: Boolean
            get() = !isSaving &&
                businessName.isNotBlank() &&
                businessName.length <= BUSINESS_NAME_MAX_LENGTH &&
                warehouseName.isNotBlank() &&
                warehouseName.length <= WAREHOUSE_NAME_MAX_LENGTH &&
                isRucWellFormed &&
                isTaxRateValid
    }

    enum class Failure {
        SAVE_FAILED,
    }

    sealed interface Action : UiAction {
        data class BusinessNameChanged(val value: String) : Action
        data class RucChanged(val value: String) : Action
        data class TaxRateChanged(val value: String) : Action
        data class CostPolicySelected(val value: CostPolicy) : Action
        data class WarehouseNameChanged(val value: String) : Action
        data object Save : Action
    }

    sealed interface Effect : UiEffect {
        data object OnboardingCompleted : Effect
    }
}
