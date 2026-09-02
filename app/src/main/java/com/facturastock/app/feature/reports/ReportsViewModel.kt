package com.facturastock.app.feature.reports

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.usecase.ObserveSalesReportUseCase
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val observeSalesReport: ObserveSalesReportUseCase,
    private val clock: AppClock,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<ReportsContract.State, ReportsContract.Action, ReportsContract.Effect>(
    initialState = ReportsContract.State(),
    dispatcherProvider = dispatcherProvider,
) {
    private var observation: Job? = null
    private var rangeBoundaryRefresh: Job? = null

    init {
        observeSelectedPeriod(keepCurrentContent = false)
    }

    override fun onAction(action: ReportsContract.Action) {
        when (action) {
            is ReportsContract.Action.PeriodSelected -> selectPeriod(action.period)
            ReportsContract.Action.Retry -> observeSelectedPeriod(
                keepCurrentContent = uiState.value.report != null,
            )
            ReportsContract.Action.Resumed -> observeSelectedPeriod(
                keepCurrentContent = uiState.value.report != null,
            )
        }
    }

    private fun selectPeriod(period: SalesReportPeriod) {
        if (period == uiState.value.selectedPeriod) return
        executeMain {
            updateState {
                copy(
                    selectedPeriod = period,
                    report = null,
                    isLoading = true,
                    isRefreshing = false,
                    failure = null,
                )
            }
            observeSelectedPeriod(keepCurrentContent = false)
        }
    }

    /**
     * Reabre la consulta para recalcular límites de calendario con la hora y zona actuales.
     * La venta ya está persistida al confirmarse; no existe un lote artificial de 24 horas.
     */
    private fun observeSelectedPeriod(keepCurrentContent: Boolean) {
        val period = uiState.value.selectedPeriod
        observation?.cancel()
        rangeBoundaryRefresh?.cancel()
        observation = executeMain {
            updateState {
                copy(
                    report = report?.takeIf {
                        keepCurrentContent && it.range.period == period
                    },
                    isLoading = !keepCurrentContent,
                    isRefreshing = keepCurrentContent,
                    failure = null,
                )
            }
            try {
                observeSalesReport(period)
                    // El mapeo Room conserva su flowOn(IO); la ordenación y los totales del caso
                    // de uso quedan en Default, y solo la actualización de estado vuelve a Main.
                    .flowOn(dispatcherProvider.default)
                    .collect { freshReport ->
                    if (uiState.value.selectedPeriod != period) return@collect
                    updateState {
                        copy(
                            report = freshReport,
                            isLoading = false,
                            isRefreshing = false,
                            failure = null,
                        )
                    }
                    scheduleRefreshAtRangeBoundary(freshReport)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState {
                    copy(
                        isLoading = false,
                        isRefreshing = false,
                        failure = ReportsContract.Failure.LOAD_FAILED,
                    )
                }
            }
        }
    }

    private fun scheduleRefreshAtRangeBoundary(report: SalesReport) {
        rangeBoundaryRefresh?.cancel()
        val remainingMillis = Duration.between(clock.now(), report.range.endExclusive).toMillis()
        // Un reloj de prueba estático o una emisión atrasada no debe crear un bucle inmediato.
        if (remainingMillis <= 0L) return
        rangeBoundaryRefresh = executeMain {
            delay(remainingMillis + BOUNDARY_GRACE_MILLIS)
            observeSelectedPeriod(keepCurrentContent = false)
        }
    }

    private companion object {
        const val BOUNDARY_GRACE_MILLIS = 100L
    }
}
