package com.facturastock.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.usecase.EnterDemoModeUseCase
import com.facturastock.app.domain.usecase.ExitDemoModeUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.StartDemoInvoiceScenarioResult
import com.facturastock.app.domain.usecase.StartDemoInvoiceScenarioUseCase
import com.facturastock.app.domain.usecase.UpdateBusinessProfileUseCase
import com.facturastock.app.domain.usecase.RunPrivacyMaintenanceUseCase
import com.facturastock.app.domain.usecase.UpdateBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateBiometricLockEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateDiagnosticsConsentUseCase
import com.facturastock.app.domain.usecase.UpdateDocumentBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateImageRetentionPolicyUseCase
import com.facturastock.app.domain.usecase.UpdateTaxConfigurationUseCase
import com.facturastock.app.domain.usecase.WriteUserDataExportUseCase
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeAppConfigurationUseCase: ObserveAppConfigurationUseCase,
    private val businessRepository: BusinessRepository,
    private val updateBusinessProfileUseCase: UpdateBusinessProfileUseCase,
    private val updateTaxConfigurationUseCase: UpdateTaxConfigurationUseCase,
    private val enterDemoModeUseCase: EnterDemoModeUseCase,
    private val exitDemoModeUseCase: ExitDemoModeUseCase,
    private val startDemoInvoiceScenarioUseCase: StartDemoInvoiceScenarioUseCase,
    private val updateDiagnosticsConsentUseCase: UpdateDiagnosticsConsentUseCase,
    private val updateImageRetentionPolicyUseCase: UpdateImageRetentionPolicyUseCase,
    private val updateBackupEnabledUseCase: UpdateBackupEnabledUseCase,
    private val updateDocumentBackupEnabledUseCase: UpdateDocumentBackupEnabledUseCase,
    private val updateBiometricLockEnabledUseCase: UpdateBiometricLockEnabledUseCase,
    private val writeUserDataExportUseCase: WriteUserDataExportUseCase,
    private val runPrivacyMaintenanceUseCase: RunPrivacyMaintenanceUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<SettingsContract.State, SettingsContract.Action, SettingsContract.Effect>(
    initialState = restoredSettingsState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var loadedBusinessId: BusinessId? = BusinessId.parse(
        savedStateHandle.get<String>(LOADED_BUSINESS_ID_KEY),
    )
    private var configurationJob: Job? = null
    private var businessFieldsDirty =
        savedStateHandle.get<Boolean>(BUSINESS_FIELDS_DIRTY_KEY) == true
    private var taxFieldsDirty = savedStateHandle.get<Boolean>(TAX_FIELDS_DIRTY_KEY) == true
    private var demoScenarioStartRequested = false
    private val privacyOperationClaimed = AtomicBoolean(false)
    private val saveOperationClaimed = AtomicBoolean(false)

    init {
        observeConfiguration()
    }

    override fun onAction(action: SettingsContract.Action) {
        when (action) {
            is SettingsContract.Action.LegalNameChanged -> {
                val value = action.value.take(SettingsContract.BUSINESS_NAME_MAX_LENGTH)
                updateField(LEGAL_NAME_KEY, value) {
                    businessFieldsDirty = true
                    savedStateHandle[BUSINESS_FIELDS_DIRTY_KEY] = true
                    copy(legalName = value, savedFeedback = false)
                }
            }

            is SettingsContract.Action.TradeNameChanged -> {
                val value = action.value.take(SettingsContract.BUSINESS_NAME_MAX_LENGTH)
                updateField(TRADE_NAME_KEY, value) {
                    businessFieldsDirty = true
                    savedStateHandle[BUSINESS_FIELDS_DIRTY_KEY] = true
                    copy(tradeName = value, savedFeedback = false)
                }
            }

            is SettingsContract.Action.RucChanged ->
                updateField(RUC_KEY, action.value) {
                    businessFieldsDirty = true
                    savedStateHandle[BUSINESS_FIELDS_DIRTY_KEY] = true
                    copy(ruc = action.value, rucChecksumWarning = false, savedFeedback = false)
                }

            is SettingsContract.Action.TaxRateChanged ->
                updateField(TAX_RATE_KEY, action.value) {
                    taxFieldsDirty = true
                    savedStateHandle[TAX_FIELDS_DIRTY_KEY] = true
                    copy(taxRatePercent = action.value, savedFeedback = false)
                }

            is SettingsContract.Action.CostPolicySelected ->
                updateField(COST_POLICY_KEY, action.value.name) {
                    taxFieldsDirty = true
                    savedStateHandle[TAX_FIELDS_DIRTY_KEY] = true
                    copy(costPolicy = action.value, savedFeedback = false)
                }

            is SettingsContract.Action.DiagnosticsConsentChanged ->
                updateDiagnosticsConsent(action.enabled)

            is SettingsContract.Action.ImageRetentionPolicySelected ->
                selectImageRetentionPolicy(action.policy)

            SettingsContract.Action.ConfirmImageRetentionPolicy ->
                uiState.value.pendingImageRetentionPolicy?.let { policy ->
                    applyImageRetentionPolicy(policy, runMaintenance = true)
                }

            is SettingsContract.Action.BackupEnabledChanged ->
                updateBackupPreference(action.enabled)

            is SettingsContract.Action.DocumentBackupEnabledChanged ->
                updatePrivacyPreference {
                    updateDocumentBackupEnabledUseCase(action.enabled)
                }

            is SettingsContract.Action.BiometricLockEnabledChanged ->
                updatePrivacyPreference {
                    updateBiometricLockEnabledUseCase(action.enabled)
                }

            SettingsContract.Action.ExportData -> executeMain {
                if (
                    !privacyOperationClaimed.get() &&
                    !uiState.value.isPrivacyBusy &&
                    !uiState.value.awaitingExportDestination
                ) {
                    updateState { copy(awaitingExportDestination = true) }
                    emitEffect(SettingsContract.Effect.CreateExportDocument)
                }
            }

            is SettingsContract.Action.ExportDestinationSelected ->
                writeExport(action.documentUri)

            SettingsContract.Action.ExportCanceled -> executeMain {
                updateState { copy(awaitingExportDestination = false) }
            }

            SettingsContract.Action.DeleteImages -> executeMain {
                if (!privacyOperationClaimed.get() && !uiState.value.isPrivacyBusy) {
                    updateState { copy(showDeleteImagesDialog = true) }
                }
            }

            SettingsContract.Action.ConfirmDeleteImages -> runPrivacyMaintenance(
                forceImageDeletion = true,
                operation = SettingsContract.PrivacyOperation.DELETE_IMAGES,
            )

            SettingsContract.Action.CleanPrivateFiles -> runPrivacyMaintenance(
                forceImageDeletion = false,
                operation = SettingsContract.PrivacyOperation.CLEAN_FILES,
            )

            SettingsContract.Action.SaveBusinessProfile -> saveBusinessProfile()

            SettingsContract.Action.SaveTaxConfiguration -> executeMain {
                updateState { copy(showTaxWarningDialog = true) }
            }

            SettingsContract.Action.ConfirmTaxConfiguration -> saveTaxConfiguration()

            SettingsContract.Action.EnterDemoMode -> executeMain {
                updateState { copy(showDemoEnterDialog = true) }
            }

            SettingsContract.Action.ConfirmEnterDemoMode -> enterDemoMode()

            SettingsContract.Action.StartDemoScenario -> startDemoScenario()

            SettingsContract.Action.ExitDemoMode -> executeMain {
                if (!uiState.value.isStartingDemoScenario && !demoScenarioStartRequested) {
                    updateState { copy(showDemoExitDialog = true) }
                }
            }

            SettingsContract.Action.ConfirmExitDemoMode -> exitDemoMode()

            SettingsContract.Action.OpenAccount -> executeMain {
                emitEffect(SettingsContract.Effect.OpenAccount)
            }

            SettingsContract.Action.OpenSync -> executeMain {
                emitEffect(SettingsContract.Effect.OpenSync)
            }

            SettingsContract.Action.OpenPrivacyPolicy -> executeMain {
                emitEffect(SettingsContract.Effect.OpenPrivacyPolicy)
            }

            SettingsContract.Action.DismissPrivacyResult -> executeMain {
                updateState { copy(privacyResult = null) }
            }

            SettingsContract.Action.DismissDialogs -> executeMain {
                updateState {
                    copy(
                        showTaxWarningDialog = false,
                        showDemoEnterDialog = false,
                        showDemoExitDialog = false,
                        showDeleteImagesDialog = false,
                        pendingImageRetentionPolicy = null,
                    )
                }
            }

            SettingsContract.Action.Retry -> observeConfiguration()
        }
    }

    private fun observeConfiguration() {
        configurationJob?.cancel()
        configurationJob = executeMain {
            try {
                observeAppConfigurationUseCase().collectLatest { config ->
                    updateState {
                        if (taxFieldsDirty) {
                            copy(config = config, failure = null)
                        } else {
                            copy(
                                config = config,
                                taxRatePercent = config.taxRate.percent.toPlainString(),
                                costPolicy = config.costPolicy,
                                failure = null,
                            )
                        }
                    }
                    val activeId = config.activeBusinessId
                    val activeChanged = activeId != loadedBusinessId
                    if (activeChanged) {
                        loadedBusinessId = activeId
                        if (activeId == null) {
                            savedStateHandle.remove<String>(LOADED_BUSINESS_ID_KEY)
                        } else {
                            savedStateHandle[LOADED_BUSINESS_ID_KEY] = activeId.value
                        }
                        businessFieldsDirty = false
                        savedStateHandle[BUSINESS_FIELDS_DIRTY_KEY] = false
                    }
                    val businessFlow = activeId?.let { businessId ->
                        businessRepository.observeById(businessId).map { businessId to it }
                    } ?: flowOf(null to null)
                    var replaceForBusinessChange = activeChanged
                    businessFlow.collect { (_, business) ->
                        val replaceFields = replaceForBusinessChange || !businessFieldsDirty
                        replaceForBusinessChange = false
                        if (replaceFields) {
                            savedStateHandle[LEGAL_NAME_KEY] = business?.legalName.orEmpty()
                            savedStateHandle[TRADE_NAME_KEY] = business?.tradeName.orEmpty()
                            savedStateHandle[RUC_KEY] = business?.ruc.orEmpty()
                        }
                        updateState {
                            copy(
                                business = business,
                                legalName = if (replaceFields) {
                                    business?.legalName.orEmpty()
                                } else {
                                    legalName
                                },
                                tradeName = if (replaceFields) {
                                    business?.tradeName.orEmpty()
                                } else {
                                    tradeName
                                },
                                ruc = if (replaceFields) business?.ruc.orEmpty() else ruc,
                                rucChecksumWarning = if (replaceFields) false else rucChecksumWarning,
                                failure = null,
                            )
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(failure = SettingsContract.Failure.LOAD_FAILED) }
            }
        }
    }

    private fun saveBusinessProfile() {
        val snapshot = uiState.value
        val currentBusiness = snapshot.business
        if (!snapshot.canSaveBusiness || currentBusiness == null) return
        val trimmedRuc = snapshot.ruc.trim()
        // Doble seguro: un RUC mal formado nunca se guarda.
        if (trimmedRuc.isNotEmpty() && !RucValidator.isWellFormed(trimmedRuc)) return

        // Advertencia no bloqueante: el primer guardado con checksum inválido muestra el
        // aviso; un segundo guardado con el mismo RUC confirma de forma explícita.
        if (
            trimmedRuc.isNotEmpty() &&
            !RucValidator.hasValidChecksum(trimmedRuc) &&
            !snapshot.rucChecksumWarning
        ) {
            executeMain {
                updateState { copy(rucChecksumWarning = true) }
            }
            return
        }

        executeClaimedSaveIo(
            before = {
                updateState { copy(isSaving = true, failure = null, savedFeedback = false) }
            },
            operation = {
                val updated = currentBusiness.copy(
                    legalName = snapshot.legalName.trim(),
                    tradeName = snapshot.tradeName.trim().ifEmpty { null },
                    ruc = trimmedRuc.ifEmpty { null },
                )
                updateBusinessProfileUseCase(updated)
                updated
            },
            onSuccess = { updated ->
                businessFieldsDirty = false
                savedStateHandle[BUSINESS_FIELDS_DIRTY_KEY] = false
                savedStateHandle[LEGAL_NAME_KEY] = updated.legalName
                savedStateHandle[TRADE_NAME_KEY] = updated.tradeName.orEmpty()
                savedStateHandle[RUC_KEY] = updated.ruc.orEmpty()
                updateState {
                    copy(
                        isSaving = false,
                        business = updated,
                        legalName = updated.legalName,
                        tradeName = updated.tradeName.orEmpty(),
                        ruc = updated.ruc.orEmpty(),
                        rucChecksumWarning = false,
                        savedFeedback = true,
                    )
                }
            },
            onFailure = {
                updateState {
                    copy(isSaving = false, failure = SettingsContract.Failure.SAVE_FAILED)
                }
            },
        )
    }

    private fun saveTaxConfiguration() {
        val snapshot = uiState.value
        if (!snapshot.isTaxRateValid) return
        executeClaimedSaveIo(
            before = {
                updateState {
                    copy(
                        showTaxWarningDialog = false,
                        isSaving = true,
                        failure = null,
                        savedFeedback = false,
                    )
                }
            },
            operation = {
                updateTaxConfigurationUseCase(
                    TaxRate(BigDecimal(snapshot.taxRatePercent.trim())),
                    snapshot.costPolicy,
                )
            },
            onSuccess = {
                taxFieldsDirty = false
                savedStateHandle[TAX_FIELDS_DIRTY_KEY] = false
                updateState { copy(isSaving = false, savedFeedback = true) }
            },
            onFailure = {
                updateState {
                    copy(isSaving = false, failure = SettingsContract.Failure.SAVE_FAILED)
                }
            },
        )
    }

    private fun updateDiagnosticsConsent(enabled: Boolean) {
        if (uiState.value.isSaving || enabled == uiState.value.diagnosticsEnabled) return
        executeClaimedSaveIo(
            before = {
                updateState { copy(isSaving = true, failure = null, savedFeedback = false) }
            },
            operation = { updateDiagnosticsConsentUseCase(enabled) },
            onSuccess = {
                updateState { copy(isSaving = false, savedFeedback = true) }
            },
            onFailure = {
                updateState {
                    copy(isSaving = false, failure = SettingsContract.Failure.SAVE_FAILED)
                }
            },
        )
    }

    private fun updatePrivacyPreference(operation: suspend () -> Unit) {
        if (
            privacyOperationClaimed.get() ||
            uiState.value.isSaving ||
            uiState.value.isPrivacyBusy
        ) {
            return
        }
        executeClaimedSaveIo(
            before = {
                updateState {
                    copy(isSaving = true, failure = null, savedFeedback = false)
                }
            },
            operation = operation,
            onSuccess = {
                updateState { copy(isSaving = false, savedFeedback = true) }
            },
            onFailure = {
                updateState {
                    copy(isSaving = false, failure = SettingsContract.Failure.SAVE_FAILED)
                }
            },
        )
    }

    private fun updateBackupPreference(enabled: Boolean) {
        if (
            privacyOperationClaimed.get() ||
            uiState.value.isSaving ||
            uiState.value.isPrivacyBusy
        ) {
            return
        }
        executeClaimedSaveIo(
            before = {
                updateState {
                    copy(
                        isSaving = true,
                        failure = null,
                        savedFeedback = false,
                        privacyResult = null,
                    )
                }
            },
            operation = { updateBackupEnabledUseCase(enabled) },
            onSuccess = { result ->
                updateState {
                    copy(
                        isSaving = false,
                        privacyResult = SettingsContract.PrivacyResult.BackupUpdated(
                            enabled = result.enabled,
                            schedulerUpdated = result.schedulerUpdated,
                        ),
                    )
                }
            },
            onFailure = {
                updateState {
                    copy(isSaving = false, failure = SettingsContract.Failure.SAVE_FAILED)
                }
            },
        )
    }

    private fun writeExport(documentUri: String) {
        if (documentUri.isBlank()) return
        executeClaimedPrivacyIo(
            claimedOperation = SettingsContract.PrivacyOperation.EXPORT,
            before = {
                updateState {
                    copy(
                        privacyOperation = SettingsContract.PrivacyOperation.EXPORT,
                        awaitingExportDestination = false,
                        privacyResult = null,
                    )
                }
            },
            operation = { writeUserDataExportUseCase(documentUri) },
            onSuccess = { result ->
                updateState {
                    copy(
                        privacyOperation = null,
                        privacyResult = result.export?.let {
                            SettingsContract.PrivacyResult.Exported(
                                products = it.products.size,
                                suppliers = it.suppliers.size,
                                units = it.units.size,
                                inventoryLocations = it.inventoryLocations.size,
                                supplierProductAliases = it.supplierProductAliases.size,
                                purchases = it.purchases.size,
                                purchaseLines = it.purchases.sumOf { purchase ->
                                    purchase.lines.size
                                },
                                inventoryBalances = it.inventoryBalances.size,
                                stockMovements = it.stockMovements.size,
                                auditEvents = it.auditEvents.size,
                                retainedImageMetadata = it.purchases.sumOf { purchase ->
                                    purchase.retainedImages.size
                                },
                            )
                        } ?: if (
                            result.status ==
                            UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
                        ) {
                            SettingsContract.PrivacyResult.ExportDestinationCleanupUnconfirmed
                        } else {
                            SettingsContract.PrivacyResult.Failed
                        },
                    )
                }
            },
            onFailure = {
                updateState {
                    copy(
                        privacyOperation = null,
                        privacyResult = SettingsContract.PrivacyResult.Failed,
                    )
                }
            },
        )
    }

    private fun selectImageRetentionPolicy(policy: ImageRetentionPolicy) {
        val snapshot = uiState.value
        if (
            privacyOperationClaimed.get() ||
            snapshot.isPrivacyBusy ||
            snapshot.isSaving ||
            policy == snapshot.imageRetentionPolicy
        ) {
            return
        }
        if (policy == ImageRetentionPolicy.KEEP) {
            applyImageRetentionPolicy(policy, runMaintenance = false)
        } else {
            executeMain {
                updateState {
                    copy(
                        pendingImageRetentionPolicy = policy,
                        privacyResult = null,
                    )
                }
            }
        }
    }

    private fun applyImageRetentionPolicy(
        policy: ImageRetentionPolicy,
        runMaintenance: Boolean,
    ) {
        executeClaimedPrivacyIo(
            claimedOperation = SettingsContract.PrivacyOperation.UPDATE_RETENTION,
            before = {
                updateState {
                    copy(
                        pendingImageRetentionPolicy = null,
                        privacyOperation = SettingsContract.PrivacyOperation.UPDATE_RETENTION,
                        privacyResult = null,
                    )
                }
            },
            operation = {
                updateImageRetentionPolicyUseCase(policy)
                val report = if (runMaintenance) {
                    try {
                        runPrivacyMaintenanceUseCase(forceImageDeletion = false)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                SettingsContract.PrivacyResult.RetentionPolicyUpdated(
                    policy = policy,
                    maintenanceAttempted = runMaintenance,
                    report = report,
                )
            },
            onSuccess = { result ->
                updateState {
                    copy(
                        privacyOperation = null,
                        privacyResult = result,
                    )
                }
            },
            onFailure = {
                updateState {
                    copy(
                        privacyOperation = null,
                        privacyResult = SettingsContract.PrivacyResult.Failed,
                    )
                }
            },
        )
    }

    private fun runPrivacyMaintenance(
        forceImageDeletion: Boolean,
        operation: SettingsContract.PrivacyOperation,
    ) {
        executeClaimedPrivacyIo(
            claimedOperation = operation,
            before = {
                updateState {
                    copy(
                        showDeleteImagesDialog = false,
                        privacyOperation = operation,
                        privacyResult = null,
                    )
                }
            },
            operation = { runPrivacyMaintenanceUseCase(forceImageDeletion) },
            onSuccess = { report ->
                updateState {
                    copy(
                        privacyOperation = null,
                        privacyResult = if (forceImageDeletion) {
                            SettingsContract.PrivacyResult.ImagesDeleted(report)
                        } else {
                            SettingsContract.PrivacyResult.FilesCleaned(report)
                        },
                    )
                }
            },
            onFailure = {
                updateState {
                    copy(
                        privacyOperation = null,
                        privacyResult = SettingsContract.PrivacyResult.Failed,
                    )
                }
            },
        )
    }

    /**
     * Reclama la operación antes de publicar un coroutine. El snapshot Compose se actualiza en
     * el siguiente turno de Main, por lo que `privacyOperation` por sí solo no absorbe dos
     * eventos recibidos en el mismo frame. La bandera atómica se libera al completar el Job en
     * éxito, fallo o cancelación; la cancelación limpia además el estado busy antes de relanzarse.
     */
    private fun <T> executeClaimedPrivacyIo(
        claimedOperation: SettingsContract.PrivacyOperation,
        before: () -> Unit,
        operation: suspend () -> T,
        onSuccess: suspend (T) -> Unit,
        onFailure: suspend (Throwable) -> Unit,
    ) {
        if (
            saveOperationClaimed.get() ||
            uiState.value.isPrivacyBusy ||
            !privacyOperationClaimed.compareAndSet(false, true)
        ) {
            return
        }
        executeIo(
            before = before,
            operation = operation,
            onSuccess = onSuccess,
            onFailure = onFailure,
            onCancellation = {
                updateState {
                    if (privacyOperation == claimedOperation) {
                        copy(privacyOperation = null)
                    } else {
                        this
                    }
                }
            },
        ).invokeOnCompletion {
            privacyOperationClaimed.set(false)
        }
    }

    /** Reclama cualquier persistencia de Settings antes del siguiente turno de Main. */
    private fun <T> executeClaimedSaveIo(
        before: () -> Unit,
        operation: suspend () -> T,
        onSuccess: suspend (T) -> Unit,
        onFailure: suspend (Throwable) -> Unit,
    ) {
        if (
            privacyOperationClaimed.get() ||
            uiState.value.isSaving ||
            uiState.value.isPrivacyBusy ||
            !saveOperationClaimed.compareAndSet(false, true)
        ) {
            return
        }
        executeIo(
            before = before,
            operation = operation,
            onSuccess = onSuccess,
            onFailure = onFailure,
            onCancellation = {
                updateState { copy(isSaving = false) }
            },
        ).invokeOnCompletion {
            saveOperationClaimed.set(false)
        }
    }

    private fun enterDemoMode() {
        executeIo(
            before = {
                updateState {
                    copy(
                        showDemoEnterDialog = false,
                        isSaving = true,
                        failure = null,
                        demoScenarioFailure = null,
                        savedFeedback = false,
                    )
                }
            },
            operation = { enterDemoModeUseCase() },
            onSuccess = {
                // La nueva emisión de configuración recarga el negocio activo (el demo).
                updateState { copy(isSaving = false) }
            },
            onFailure = {
                updateState {
                    copy(isSaving = false, failure = SettingsContract.Failure.SAVE_FAILED)
                }
            },
        )
    }

    private fun startDemoScenario() {
        if (demoScenarioStartRequested || !uiState.value.canStartDemoScenario) return
        // El estado Compose se publica en el siguiente turno del dispatcher; esta compuerta
        // síncrona absorbe dos eventos de toque recibidos antes de que ese snapshot cambie.
        demoScenarioStartRequested = true
        executeIo(
            before = {
                updateState {
                    copy(
                        isStartingDemoScenario = true,
                        demoScenarioFailure = null,
                        savedFeedback = false,
                    )
                }
            },
            operation = { startDemoInvoiceScenarioUseCase() },
            onSuccess = { result ->
                demoScenarioStartRequested = false
                updateState { copy(isStartingDemoScenario = false) }
                when (result) {
                    is StartDemoInvoiceScenarioResult.Ready -> emitEffect(
                        SettingsContract.Effect.OpenDemoCapture(
                            draftId = result.draftId,
                            captureId = result.captureId,
                        ),
                    )

                    is StartDemoInvoiceScenarioResult.AlreadyCompleted -> emitEffect(
                        SettingsContract.Effect.OpenDemoPurchase(result.purchaseId),
                    )

                    StartDemoInvoiceScenarioResult.NotInDemoMode -> updateState {
                        copy(
                            demoScenarioFailure =
                                SettingsContract.DemoScenarioFailure.NOT_IN_DEMO_MODE,
                        )
                    }

                    StartDemoInvoiceScenarioResult.Conflict -> updateState {
                        copy(demoScenarioFailure = SettingsContract.DemoScenarioFailure.CONFLICT)
                    }
                }
            },
            onFailure = {
                demoScenarioStartRequested = false
                updateState {
                    copy(
                        isStartingDemoScenario = false,
                        demoScenarioFailure = SettingsContract.DemoScenarioFailure.START_FAILED,
                    )
                }
            },
        )
    }

    private fun exitDemoMode() {
        executeIo(
            before = {
                updateState {
                    copy(
                        showDemoExitDialog = false,
                        isSaving = true,
                        failure = null,
                        demoScenarioFailure = null,
                        savedFeedback = false,
                    )
                }
            },
            operation = { exitDemoModeUseCase() },
            onSuccess = {
                updateState { copy(isSaving = false) }
            },
            onFailure = {
                updateState {
                    copy(isSaving = false, failure = SettingsContract.Failure.SAVE_FAILED)
                }
            },
        )
    }

    private fun updateField(
        key: String,
        value: String,
        transform: SettingsContract.State.() -> SettingsContract.State,
    ) {
        executeMain {
            savedStateHandle[key] = value
            updateState(transform)
        }
    }

    private companion object {
        const val LEGAL_NAME_KEY = "settings.legalName"
        const val TRADE_NAME_KEY = "settings.tradeName"
        const val RUC_KEY = "settings.ruc"
        const val TAX_RATE_KEY = "settings.taxRatePercent"
        const val COST_POLICY_KEY = "settings.costPolicy"
        const val LOADED_BUSINESS_ID_KEY = "settings.loadedBusinessId"
        const val BUSINESS_FIELDS_DIRTY_KEY = "settings.businessFieldsDirty"
        const val TAX_FIELDS_DIRTY_KEY = "settings.taxFieldsDirty"
    }
}

private fun restoredSettingsState(savedStateHandle: SavedStateHandle): SettingsContract.State {
    val costPolicy = savedStateHandle.get<String>("settings.costPolicy")
        ?.let { raw -> runCatching { CostPolicy.valueOf(raw) }.getOrNull() }
        ?: CostPolicy.NET
    return SettingsContract.State(
        legalName = savedStateHandle.get<String>("settings.legalName").orEmpty()
            .take(SettingsContract.BUSINESS_NAME_MAX_LENGTH),
        tradeName = savedStateHandle.get<String>("settings.tradeName").orEmpty()
            .take(SettingsContract.BUSINESS_NAME_MAX_LENGTH),
        ruc = savedStateHandle.get<String>("settings.ruc").orEmpty(),
        taxRatePercent = savedStateHandle.get<String>("settings.taxRatePercent") ?: "18",
        costPolicy = costPolicy,
    )
}
