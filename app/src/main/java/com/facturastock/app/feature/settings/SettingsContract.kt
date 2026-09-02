package com.facturastock.app.feature.settings

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.RetentionSweepReport
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.math.BigDecimal

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
        val showDemoEnterDialog: Boolean = false,
        val showDemoExitDialog: Boolean = false,
        val showDeleteImagesDialog: Boolean = false,
        val pendingImageRetentionPolicy: ImageRetentionPolicy? = null,
        val isStartingDemoScenario: Boolean = false,
        val privacyOperation: PrivacyOperation? = null,
        val awaitingExportDestination: Boolean = false,
        val privacyResult: PrivacyResult? = null,
        val demoScenarioFailure: DemoScenarioFailure? = null,
        val savedFeedback: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val isDemoMode: Boolean
            get() = config?.isDemoMode == true

        val diagnosticsEnabled: Boolean
            get() = config?.diagnosticsEnabled == true

        val backupEnabled: Boolean
            get() = config?.backupEnabled == true

        val documentBackupEnabled: Boolean
            get() = config?.documentBackupEnabled == true

        val biometricLockEnabled: Boolean
            get() = config?.biometricLockEnabled == true

        val imageRetentionPolicy: ImageRetentionPolicy
            get() = config?.imageRetentionPolicy ?: ImageRetentionPolicy.KEEP

        val isPrivacyBusy: Boolean
            get() = privacyOperation != null

        val isRucWellFormed: Boolean
            get() = ruc.isBlank() || RucValidator.isWellFormed(ruc)

        val isTaxRateValid: Boolean
            get() = taxRatePercent.trim().toBigDecimalOrNull()
                ?.let { it >= BigDecimal.ZERO && it <= BigDecimal("100") } == true

        val canSaveBusiness: Boolean
            get() =
                !isSaving && !isStartingDemoScenario && business != null &&
                    legalName.isNotBlank() &&
                    legalName.length <= BUSINESS_NAME_MAX_LENGTH &&
                    tradeName.length <= BUSINESS_NAME_MAX_LENGTH &&
                    isRucWellFormed

        val canSaveTax: Boolean
            get() = !isSaving && !isStartingDemoScenario && isTaxRateValid

        val canStartDemoScenario: Boolean
            get() = isDemoMode && !isSaving && !isStartingDemoScenario
    }

    enum class Failure {
        LOAD_FAILED,
        SAVE_FAILED,
    }

    enum class DemoScenarioFailure {
        NOT_IN_DEMO_MODE,
        CONFLICT,
        START_FAILED,
    }

    enum class PrivacyOperation {
        EXPORT,
        DELETE_IMAGES,
        CLEAN_FILES,
        UPDATE_RETENTION,
    }

    sealed interface PrivacyResult {
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
        ) : PrivacyResult

        data class ImagesDeleted(val report: RetentionSweepReport) : PrivacyResult

        data class FilesCleaned(val report: RetentionSweepReport) : PrivacyResult

        data class RetentionPolicyUpdated(
            val policy: ImageRetentionPolicy,
            val maintenanceAttempted: Boolean,
            val report: RetentionSweepReport?,
        ) : PrivacyResult

        data class BackupUpdated(
            val enabled: Boolean,
            val schedulerUpdated: Boolean,
        ) : PrivacyResult

        /** El provider SAF tampoco confirmó borrar o truncar el destino después de fallar. */
        data object ExportDestinationCleanupUnconfirmed : PrivacyResult

        data object Failed : PrivacyResult
    }

    sealed interface Action : UiAction {
        data class LegalNameChanged(val value: String) : Action
        data class TradeNameChanged(val value: String) : Action
        data class RucChanged(val value: String) : Action
        data class TaxRateChanged(val value: String) : Action
        data class CostPolicySelected(val value: CostPolicy) : Action
        data class DiagnosticsConsentChanged(val enabled: Boolean) : Action
        data class ImageRetentionPolicySelected(val policy: ImageRetentionPolicy) : Action
        data object ConfirmImageRetentionPolicy : Action
        data class BackupEnabledChanged(val enabled: Boolean) : Action
        data class DocumentBackupEnabledChanged(val enabled: Boolean) : Action
        data class BiometricLockEnabledChanged(val enabled: Boolean) : Action
        data object ExportData : Action
        data class ExportDestinationSelected(val documentUri: String) : Action
        data object ExportCanceled : Action
        data object DeleteImages : Action
        data object ConfirmDeleteImages : Action
        data object CleanPrivateFiles : Action
        data object SaveBusinessProfile : Action
        data object SaveTaxConfiguration : Action
        data object ConfirmTaxConfiguration : Action
        data object EnterDemoMode : Action
        data object ConfirmEnterDemoMode : Action
        data object StartDemoScenario : Action
        data object ExitDemoMode : Action
        data object ConfirmExitDemoMode : Action
        data object OpenAccount : Action
        data object OpenSync : Action
        data object OpenPrivacyPolicy : Action
        data object DismissPrivacyResult : Action
        data object DismissDialogs : Action
        data object Retry : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenDemoCapture(
            val draftId: DraftId,
            val captureId: CaptureId,
        ) : Effect

        data class OpenDemoPurchase(val purchaseId: PurchaseId) : Effect

        data object OpenAccount : Effect
        data object OpenSync : Effect
        data object OpenPrivacyPolicy : Effect
        data object CreateExportDocument : Effect
    }
}
