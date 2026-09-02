package com.facturastock.app.feature.reports

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object ReportsContract {
    @Immutable
    data class State(
        val selectedPeriod: SalesReportPeriod = SalesReportPeriod.DAY,
        val report: SalesReport? = null,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val failure: Failure? = null,
    ) : UiState

    enum class Failure {
        LOAD_FAILED,
    }

    sealed interface Action : UiAction {
        data class PeriodSelected(val period: SalesReportPeriod) : Action
        data object Retry : Action
        data object Resumed : Action
    }

    /** Esta pantalla no produce navegación ni mensajes transitorios. */
    sealed interface Effect : UiEffect
}
