package com.facturastock.app.feature.settings

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.math.BigDecimal

/** Ajustes mínimos: perfil del negocio (razón social, RUC, IGV) y exportación de datos. */
object SettingsContract {
    const val BUSINESS_NAME_MAX_LENGTH = 200

    @Immutable
    data class State(
        val config: AppConfiguration? = null,
        val business: Business? = null,
        val legalName: String = "",
        val tradeName: String = "",
        val ruc: String = "",
        val taxRatePercent: String = "18",
        val costPolicy: CostPolicy = CostPolicy.NET,
        val isSaving: Boolean = false,
        val rucChecksumWarning: Boolean = false,
        val showTaxWarningDialog: Boolean = false,
        val isExporting: Boolean = false,
        val awaitingExportDestination: Boolean = false,
        val exportResult: ExportResult? = null,
        val savedFeedback: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val isRucWellFormed: Boolean
            get() = ruc.isBlank() || RucValidator.isWellFormed(ruc)

        val isTaxRateValid: Boolean
            get() = taxRatePercent.trim().toBigDecimalOrNull()
                ?.let { it >= BigDecimal.ZERO && it <= BigDecimal("100") } == true

        val canSaveBusiness: Boolean
            get() =
                !isSaving && !isExporting && business != null &&
                    legalName.isNotBlank() &&
                    legalName.length <= BUSINESS_NAME_MAX_LENGTH &&
                    tradeName.length <= BUSINESS_NAME_MAX_LENGTH &&
                    isRucWellFormed

        val canSaveTax: Boolean
            get() = !isSaving && !isExporting && isTaxRateValid

        val canExport: Boolean
            get() = !isSaving && !isExporting && !awaitingExportDestination
    }

    enum class Failure {
        LOAD_FAILED,
        SAVE_FAILED,
    }

    sealed interface ExportResult {
        data class Exported(
            val products: Int,
            val suppliers: Int,
            val units: Int,
            val inventoryLocations: Int,
            val supplierProductAliases: Int,
            val purchases: Int,
            val purchaseLines: Int,
            val inventoryBalances: Int,
            val stockMovements: Int,
            val auditEvents: Int,
            val retainedImageMetadata: Int,
        ) : ExportResult

        /** El provider SAF tampoco confirmó borrar o truncar el destino después de fallar. */
        data object DestinationCleanupUnconfirmed : ExportResult

        data object Failed : ExportResult
    }

    sealed interface Action : UiAction {
        data class LegalNameChanged(val value: String) : Action
        data class TradeNameChanged(val value: String) : Action
        data class RucChanged(val value: String) : Action
        data class TaxRateChanged(val value: String) : Action
        data class CostPolicySelected(val value: CostPolicy) : Action
        data object SaveBusinessProfile : Action
        data object SaveTaxConfiguration : Action
        data object ConfirmTaxConfiguration : Action
        data object ExportData : Action
        data class ExportDestinationSelected(val documentUri: String) : Action
        data object ExportCanceled : Action
        data object DismissExportResult : Action
        data object DismissDialogs : Action
        data object Retry : Action
    }

    sealed interface Effect : UiEffect {
        data object CreateExportDocument : Effect
    }
}
