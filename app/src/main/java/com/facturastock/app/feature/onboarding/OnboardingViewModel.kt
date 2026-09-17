package com.facturastock.app.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.usecase.CompleteOnboardingUseCase
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val completeOnboardingUseCase: CompleteOnboardingUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<OnboardingContract.State, OnboardingContract.Action, OnboardingContract.Effect>(
    initialState = restoredOnboardingState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    /** Guardia sincrónica: dos eventos Save en la misma vuelta del main loop no se encolan. */
    private var submitRequested = false

    override fun onAction(action: OnboardingContract.Action) {
        when (action) {
            is OnboardingContract.Action.BusinessNameChanged -> executeMain {
                val value = action.value.take(OnboardingContract.BUSINESS_NAME_MAX_LENGTH)
                savedStateHandle[BUSINESS_NAME_KEY] = value
                updateState { copy(businessName = value) }
            }

            is OnboardingContract.Action.RucChanged -> executeMain {
                savedStateHandle[RUC_KEY] = action.value
                updateState { copy(ruc = action.value, rucChecksumWarning = false) }
            }

            is OnboardingContract.Action.TaxRateChanged -> executeMain {
                savedStateHandle[TAX_RATE_KEY] = action.value
                updateState { copy(taxRatePercent = action.value) }
            }

            is OnboardingContract.Action.CostPolicySelected -> executeMain {
                savedStateHandle[COST_POLICY_KEY] = action.value.name
                updateState { copy(costPolicy = action.value) }
            }

            is OnboardingContract.Action.WarehouseNameChanged -> executeMain {
                val value = action.value.take(OnboardingContract.WAREHOUSE_NAME_MAX_LENGTH)
                savedStateHandle[WAREHOUSE_NAME_KEY] = value
                updateState { copy(warehouseName = value) }
            }

            OnboardingContract.Action.Save -> save()
        }
    }

    private fun save() {
        if (submitRequested) return
        submitRequested = true
        executeMain {
            // Se lee dentro de Main y después de los cambios de texto ya encolados.
            val snapshot = uiState.value
            if (!snapshot.canSave) {
                submitRequested = false
                return@executeMain
            }
            val trimmedRuc = snapshot.ruc.trim()
            // Doble seguro: un RUC mal formado nunca se guarda.
            if (trimmedRuc.isNotEmpty() && !RucValidator.isWellFormed(trimmedRuc)) {
                submitRequested = false
                return@executeMain
            }

            // Advertencia no bloqueante: el primer guardado con checksum inválido muestra el
            // aviso; un segundo guardado con el mismo RUC confirma de forma explícita. La app
            // jamás modifica el RUC ingresado.
            if (
                trimmedRuc.isNotEmpty() &&
                !RucValidator.hasValidChecksum(trimmedRuc) &&
                !snapshot.rucChecksumWarning
            ) {
                updateState { copy(rucChecksumWarning = true) }
                submitRequested = false
                return@executeMain
            }

            executeIo(
                before = {
                    updateState { copy(isSaving = true, failure = null) }
                },
                operation = {
                    completeOnboardingUseCase(
                        businessName = snapshot.businessName,
                        ruc = trimmedRuc.ifEmpty { null },
                        warehouseName = snapshot.warehouseName,
                        taxRate = TaxRate(BigDecimal(snapshot.taxRatePercent.trim())),
                        costPolicy = snapshot.costPolicy,
                    )
                },
                onSuccess = {
                    updateState { copy(isSaving = false) }
                    // Se conserva submitRequested=true hasta retirar la ruta: impide un segundo
                    // efecto terminal entre el commit y la navegación.
                    emitEffect(OnboardingContract.Effect.OnboardingCompleted)
                },
                onFailure = {
                    submitRequested = false
                    updateState {
                        copy(isSaving = false, failure = OnboardingContract.Failure.SAVE_FAILED)
                    }
                },
            )
        }
    }

    private companion object {
        const val BUSINESS_NAME_KEY = "onboarding.businessName"
        const val RUC_KEY = "onboarding.ruc"
        const val TAX_RATE_KEY = "onboarding.taxRatePercent"
        const val COST_POLICY_KEY = "onboarding.costPolicy"
        const val WAREHOUSE_NAME_KEY = "onboarding.warehouseName"
    }
}

private fun restoredOnboardingState(savedStateHandle: SavedStateHandle): OnboardingContract.State {
    val costPolicy = savedStateHandle.get<String>("onboarding.costPolicy")
        ?.let { raw -> runCatching { CostPolicy.valueOf(raw) }.getOrNull() }
        ?: CostPolicy.NET
    return OnboardingContract.State(
        businessName = savedStateHandle.get<String>("onboarding.businessName").orEmpty()
            .take(OnboardingContract.BUSINESS_NAME_MAX_LENGTH),
        ruc = savedStateHandle.get<String>("onboarding.ruc").orEmpty(),
        taxRatePercent = savedStateHandle.get<String>("onboarding.taxRatePercent") ?: "18",
        costPolicy = costPolicy,
        warehouseName = savedStateHandle.get<String>("onboarding.warehouseName")
            ?.take(OnboardingContract.WAREHOUSE_NAME_MAX_LENGTH)
            ?.ifBlank { OnboardingContract.DEFAULT_WAREHOUSE_NAME }
            ?: OnboardingContract.DEFAULT_WAREHOUSE_NAME,
    )
}
