package com.facturastock.app.feature.reports

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.SaleVoidPreview
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
        val voidSaleId: SaleId? = null,
        val voidPreview: SaleVoidPreview? = null,
        val isLoadingVoidPreview: Boolean = false,
        val isVoiding: Boolean = false,
        val voidFailure: VoidFailure? = null,
        val voidSucceeded: Boolean = false,
        val pdfStage: PdfStage = PdfStage.IDLE,
        val pdfKind: ReportPdfKind? = null,
        val pdfFailure: PdfFailure? = null,
        val pdfSaved: Boolean = false,
        val pdfViewerUnavailable: Boolean = false,
    ) : UiState {
        val canExportPdf: Boolean
            get() = report?.businessId != null && !isLoading && !isRefreshing && failure == null &&
                !isVoiding && !isLoadingVoidPreview && voidSaleId == null && pdfStage == PdfStage.IDLE
    }

    enum class PdfStage { IDLE, PREPARING, CHOOSING_DESTINATION, WRITING }

    enum class PdfFailure {
        PREPARATION_FAILED,
        NO_ACTIVE_BUSINESS,
        CONTEXT_CHANGED,
        DESTINATION_CLEAN,
        DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
        INTERRUPTED,
    }

    enum class Failure {
        LOAD_FAILED,
    }

    enum class VoidFailure {
        LOAD_FAILED,
        OPERATION_FAILED,
        STALE,
        NO_ACTIVE_BUSINESS,
        NOT_FOUND,
        UNAUTHORIZED,
        SHARED_BUSINESS_UNSUPPORTED,
        INVALID_HISTORY,
    }

    sealed interface Action : UiAction {
        data class PeriodSelected(val period: SalesReportPeriod) : Action
        data object Retry : Action
        data object Resumed : Action
        /** La pantalla dejó de ser visible; la consulta en vivo puede pausarse hasta [Resumed]. */
        data object Stopped : Action
        data class VoidRequested(val saleId: SaleId) : Action
        data object VoidConfirmed : Action
        data object VoidDismissed : Action
        data object VoidPreviewRetry : Action
        data object VoidNoticeDismissed : Action
        data object OpenDebtors : Action
        data class ExportPdfRequested(val kind: ReportPdfKind) : Action
        data class PdfDestinationSelected(val requestId: String?, val documentUri: String?) : Action
        data class PdfDestinationLaunchFailed(val requestId: String) : Action
        data object PdfNoticeDismissed : Action
        data object OpenSavedPdf : Action
        data object PdfViewerUnavailable : Action
    }

    /** Los avisos se conservan en el estado al rotar la pantalla. */
    sealed interface Effect : UiEffect {
        data object OpenDebtors : Effect
        data class OpenPdf(val documentUri: String) : Effect
        data class CreatePdfDocument(val requestId: String, val suggestedFileName: String) : Effect
    }
}
