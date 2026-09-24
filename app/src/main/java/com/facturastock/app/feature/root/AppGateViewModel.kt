package com.facturastock.app.feature.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Compuerta de primer inicio: mientras no exista una configuración completada, la app muestra
 * el onboarding en lugar del grafo principal. Un fallo de configuración mantiene el grafo cerrado
 * y permite reiniciar explícitamente la observación sin recrear la Activity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGateViewModel @Inject constructor(
    private val observeAppConfigurationUseCase: ObserveAppConfigurationUseCase,
) : ViewModel() {
    private val retryGeneration = MutableStateFlow(0L)

    val uiState: StateFlow<GateState> = retryGeneration
        .flatMapLatest {
            observeAppConfigurationUseCase()
                .map(AppConfiguration::toGateState)
                .onStart { emit(GateState.Loading) }
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    emit(GateState.Unavailable)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GateState.Loading,
        )

    fun retry() {
        retryGeneration.update { generation -> generation + 1L }
    }
}

sealed interface GateState {
    data object Loading : GateState
    data object Unavailable : GateState
    data object Incomplete : GateState
    data object Complete : GateState
}

internal fun AppConfiguration.toGateState(): GateState = when {
    !onboardingCompleted -> GateState.Incomplete
    businessId == null -> GateState.Unavailable
    else -> GateState.Complete
}
