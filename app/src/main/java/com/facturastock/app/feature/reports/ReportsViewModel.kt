package com.facturastock.app.feature.reports

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfPreparation
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.domain.usecase.ObserveSalesReportUseCase
import com.facturastock.app.domain.usecase.ExportReportPdfUseCase
import com.facturastock.app.domain.usecase.VoidSaleUseCase
import com.facturastock.app.domain.usecase.currentSalesReportRange
import com.facturastock.app.feature.common.UdfViewModel
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
class ReportsViewModel @Inject constructor(
    private val observeSalesReport: ObserveSalesReportUseCase,
    private val clock: AppClock,
    dispatcherProvider: DispatcherProvider,
    private val voidSale: VoidSaleUseCase,
    private val exportReportPdf: ExportReportPdfUseCase,
) : UdfViewModel<ReportsContract.State, ReportsContract.Action, ReportsContract.Effect>(
    initialState = ReportsContract.State(),
    dispatcherProvider = dispatcherProvider,
) {
    private var observation: Job? = null
    private var rangeBoundaryRefresh: Job? = null
    private var voidPreviewJob: Job? = null
    private var voidContext: VoidContext? = null
    private var voidRequestSequence = 0L
    // Se reserva antes de lanzar la corrutina: dos clics en el mismo frame no confirman dos veces.
    private var voidCommitPending = false
    private var voidPreviewPending = false
    // A snapshot can contain many sales/debts: keep it out of observable/saved UI state.
    private var pdfContext: PdfContext? = null
    private var preparedPdf: PreparedReportPdf? = null
    private var pdfPreparationJob: Job? = null
    private var pdfPickerRequestId: String? = null
    private var pdfWritePending = false
    private var savedPdfUri: String? = null
    private var savedPdfContext: PdfContext? = null
    private var pdfContextInvalidatedWhileWriting = false
    private val completedPdfRequests = linkedSetOf<String>()

    init {
        observeSelectedPeriod(keepCurrentContent = false)
    }

    override fun onAction(action: ReportsContract.Action) {
        when (action) {
            is ReportsContract.Action.PeriodSelected -> selectPeriod(action.period)
            ReportsContract.Action.Retry -> if (!voidCommitPending) {
                observeSelectedPeriod(keepCurrentContent = uiState.value.report != null)
            }
            ReportsContract.Action.Resumed -> resumeObservation()
            ReportsContract.Action.Stopped -> pauseObservation()
            is ReportsContract.Action.VoidRequested -> requestVoid(action.saleId)
            ReportsContract.Action.VoidConfirmed -> confirmVoid()
            ReportsContract.Action.VoidDismissed -> if (!voidCommitPending) {
                invalidateVoidContext()
                executeMain { updateState { clearVoidDialog() } }
            }
            ReportsContract.Action.VoidPreviewRetry -> {
                val context = voidContext
                if (context != null && !voidCommitPending && !voidPreviewPending) {
                    loadVoidPreview(context)
                }
            }
            ReportsContract.Action.VoidNoticeDismissed -> executeMain {
                updateState { copy(voidSucceeded = false) }
            }
            ReportsContract.Action.OpenDebtors -> {
                if (uiState.value.report?.businessId != null && !voidCommitPending) {
                    executeMain { emitEffect(ReportsContract.Effect.OpenDebtors) }
                }
            }
            is ReportsContract.Action.ExportPdfRequested -> preparePdf(action.kind)
            is ReportsContract.Action.PdfDestinationSelected -> acceptPdfDestination(action)
            is ReportsContract.Action.PdfDestinationLaunchFailed -> failPdfLauncher(action.requestId)
            ReportsContract.Action.PdfNoticeDismissed -> executeMain {
                updateState { copy(pdfFailure = null, pdfSaved = false, pdfViewerUnavailable = false) }
            }
            ReportsContract.Action.OpenSavedPdf -> {
                savedPdfUri?.takeIf { uiState.value.pdfSaved }?.let { uri ->
                    executeMain { emitEffect(ReportsContract.Effect.OpenPdf(uri)) }
                }
            }
            ReportsContract.Action.PdfViewerUnavailable -> executeMain {
                if (uiState.value.pdfSaved) updateState { copy(pdfViewerUnavailable = true) }
            }
        }
    }

    /** Called immediately before launching SAF, so a buffered stale effect cannot open it. */
    fun claimPdfDestination(requestId: String): Boolean {
        if (pdfContext?.requestId != requestId || preparedPdf == null || pdfPickerRequestId != null ||
            uiState.value.pdfStage != ReportsContract.PdfStage.CHOOSING_DESTINATION
        ) return false
        pdfPickerRequestId = requestId
        return true
    }

    private fun preparePdf(kind: ReportPdfKind) {
        val state = uiState.value
        if (pdfContext != null || pdfPickerRequestId != null || !state.canExportPdf || voidCommitPending) return
        val report = state.report ?: return
        val businessId = report.businessId ?: return
        val context = PdfContext(UUID.randomUUID().toString(), businessId, report.primaryCurrency, report.range.zoneId)
        // Reserve synchronously: rapid taps cannot schedule two snapshots or document selectors.
        pdfContext = context
        preparedPdf = null
        savedPdfUri = null
        savedPdfContext = null
        pdfContextInvalidatedWhileWriting = false
        pdfPreparationJob = executeMain {
            updateState {
                copy(pdfStage = ReportsContract.PdfStage.PREPARING, pdfKind = kind, pdfFailure = null, pdfSaved = false, pdfViewerUnavailable = false)
            }
            try {
                val result = withContext(dispatcherProvider.io) { exportReportPdf.prepare(kind) }
                if (pdfContext != context) return@executeMain
                pdfPreparationJob = null
                when (result) {
                    is ReportPdfPreparation.Ready -> {
                        val snapshot = result.prepared.snapshot
                        if (snapshot.businessId != context.businessId || snapshot.primaryCurrency != context.currency ||
                            snapshot.range.zoneId != context.zoneId
                        ) {
                            finishPdf(ReportsContract.PdfFailure.CONTEXT_CHANGED)
                        } else {
                            preparedPdf = result.prepared
                            updateState { copy(pdfStage = ReportsContract.PdfStage.CHOOSING_DESTINATION) }
                            emitEffect(ReportsContract.Effect.CreatePdfDocument(context.requestId, result.prepared.suggestedFileName))
                        }
                    }
                    ReportPdfPreparation.NoActiveBusiness -> finishPdf(ReportsContract.PdfFailure.NO_ACTIVE_BUSINESS)
                    ReportPdfPreparation.ContextChanged -> finishPdf(ReportsContract.PdfFailure.CONTEXT_CHANGED)
                    ReportPdfPreparation.Failed -> finishPdf(ReportsContract.PdfFailure.PREPARATION_FAILED)
                }
            } catch (cancellation: CancellationException) {
                if (pdfContext == context) finishPdf()
                throw cancellation
            } catch (_: Exception) {
                if (pdfContext == context) finishPdf(ReportsContract.PdfFailure.PREPARATION_FAILED)
            }
        }
    }

    private fun acceptPdfDestination(action: ReportsContract.Action.PdfDestinationSelected) {
        val requestId = action.requestId
        if (requestId != null && requestId in completedPdfRequests) return
        if (requestId == null || requestId != pdfPickerRequestId) {
            // Rotation retains the VM. Process death deliberately discards the large snapshot;
            // a restored SAF result may never create a new export from different/current data.
            if (pdfContext == null && pdfPickerRequestId == null && action.documentUri != null) {
                requestId?.let(::rememberCompletedPdfRequest)
                executeMain { finishPdf(ReportsContract.PdfFailure.INTERRUPTED) }
            }
            return
        }
        pdfPickerRequestId = null
        rememberCompletedPdfRequest(requestId)
        val context = pdfContext
        val prepared = preparedPdf
        val uri = action.documentUri
        if (uri == null) {
            executeMain { finishPdf(uiState.value.pdfFailure) }
            return
        }
        if (context == null || context.requestId != requestId || prepared == null) {
            executeMain { finishPdf(ReportsContract.PdfFailure.CONTEXT_CHANGED) }
            return
        }
        // The chosen destination commits this frozen snapshot; the use case revalidates the
        // business before opening it. Let the writer finish/clean up instead of cancelling I/O.
        pdfWritePending = true
        executeMain {
            updateState { copy(pdfStage = ReportsContract.PdfStage.WRITING, pdfFailure = null) }
            try {
                val status = withContext(dispatcherProvider.io) { exportReportPdf.write(uri, prepared) }
                if (pdfContext != context) return@executeMain
                when (status) {
                    ReportPdfWriteStatus.WRITTEN -> {
                        if (pdfContextInvalidatedWhileWriting || uiState.value.report?.let { !context.matches(it) } == true) {
                            finishPdf(ReportsContract.PdfFailure.CONTEXT_CHANGED)
                        } else {
                            savedPdfUri = uri
                            savedPdfContext = context
                            finishPdf(saved = true)
                        }
                    }
                    ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN -> finishPdf(ReportsContract.PdfFailure.DESTINATION_CLEAN)
                    ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA -> finishPdf(ReportsContract.PdfFailure.DESTINATION_MAY_CONTAIN_PARTIAL_DATA)
                    ReportPdfWriteStatus.CONTEXT_CHANGED -> finishPdf(ReportsContract.PdfFailure.CONTEXT_CHANGED)
                }
            } catch (cancellation: CancellationException) {
                if (pdfContext == context) finishPdf()
                throw cancellation
            } catch (_: Exception) {
                if (pdfContext == context) finishPdf(ReportsContract.PdfFailure.DESTINATION_MAY_CONTAIN_PARTIAL_DATA)
            }
        }
    }

    private fun failPdfLauncher(requestId: String) {
        if (pdfPickerRequestId != requestId || pdfContext?.requestId != requestId) return
        pdfPickerRequestId = null
        rememberCompletedPdfRequest(requestId)
        executeMain { finishPdf(ReportsContract.PdfFailure.DESTINATION_CLEAN) }
    }

    private fun rememberCompletedPdfRequest(requestId: String) {
        completedPdfRequests += requestId
        if (completedPdfRequests.size > 16) completedPdfRequests.remove(completedPdfRequests.first())
    }

    private fun finishPdf(failure: ReportsContract.PdfFailure? = null, saved: Boolean = false) {
        pdfContext = null
        preparedPdf = null
        pdfPreparationJob = null
        pdfWritePending = false
        pdfContextInvalidatedWhileWriting = false
        if (!saved) {
            savedPdfUri = null
            savedPdfContext = null
        }
        updateState { copy(pdfStage = ReportsContract.PdfStage.IDLE, pdfKind = null, pdfFailure = failure, pdfSaved = saved, pdfViewerUnavailable = false) }
    }

    private fun invalidatePdfForChangedContext(report: SalesReport) {
        if (savedPdfContext?.matches(report) == false) {
            savedPdfUri = null
            savedPdfContext = null
            updateState { copy(pdfSaved = false, pdfViewerUnavailable = false) }
        }
        val context = pdfContext ?: return
        if (context.matches(report)) return
        if (pdfWritePending) {
            // Finish/clean the selected destination, but do not offer its old business data
            // from the new business once the write returns.
            pdfContextInvalidatedWhileWriting = true
            return
        }
        pdfPreparationJob?.cancel()
        pdfPreparationJob = null
        pdfContext = null
        preparedPdf = null
        updateState {
            copy(
                pdfStage = if (pdfPickerRequestId == null) ReportsContract.PdfStage.IDLE else ReportsContract.PdfStage.CHOOSING_DESTINATION,
                pdfKind = null,
                pdfFailure = ReportsContract.PdfFailure.CONTEXT_CHANGED,
                pdfSaved = false,
            )
        }
    }

    private data class PdfContext(val requestId: String, val businessId: BusinessId, val currency: CurrencyCode, val zoneId: ZoneId) {
        fun matches(report: SalesReport): Boolean = report.businessId == businessId && report.primaryCurrency == currency && report.range.zoneId == zoneId
    }

    /**
     * Reportes permanece en la pila al cambiar de pestaña y su consulta observa ventas y líneas:
     * sin pausa, cada producto agregado en Ventas recalcularía el periodo completo en segundo
     * plano. Solo se pausa sin anulaciones ni PDF en curso, que dependen de las emisiones vivas;
     * [resumeObservation] reabre la consulta conservando el contenido visible.
     */
    private fun pauseObservation() {
        if (voidCommitPending || voidPreviewPending || voidContext != null ||
            pdfPreparationJob?.isActive == true || pdfPickerRequestId != null || pdfWritePending
        ) {
            return
        }
        observation?.cancel()
        observation = null
        rangeBoundaryRefresh?.cancel()
        rangeBoundaryRefresh = null
    }

    private fun resumeObservation() {
        val state = uiState.value
        val report = state.report
        // El flujo activo ya recibe cambios de ventas, negocio y zona incluso fuera de la
        // pantalla. Sólo reutilizamos contenido terminado y vigente; sin reporte todavía
        // podría haber una consulta del día anterior en vuelo.
        if (observation?.isActive == true && report != null &&
            state.failure == null && report.range.period == state.selectedPeriod &&
            currentSalesReportRange(state.selectedPeriod, clock.now(), report.range.zoneId) == report.range
        ) {
            // La hora puede cambiar durante la suspensión sin salir del mismo rango. Recalcula
            // la espera de frontera sin cancelar la observación ni emitir estados de carga.
            scheduleRefreshAtRangeBoundary(report)
            return
        }
        observeSelectedPeriod(keepCurrentContent = report != null)
    }

    private fun selectPeriod(period: SalesReportPeriod) {
        if (voidCommitPending || period == uiState.value.selectedPeriod) return
        invalidateVoidContext()
        executeMain {
            updateState {
                clearVoidDialog().copy(
                    selectedPeriod = period,
                    report = null,
                    isLoading = true,
                    isRefreshing = false,
                    failure = null,
                    voidSucceeded = false,
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
        if (!keepCurrentContent) invalidateVoidContext()
        observation = executeMain {
            updateState {
                (if (keepCurrentContent) this else clearVoidDialog()).copy(
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
                    invalidatePdfForChangedContext(freshReport)
                    val previous = uiState.value.report
                    val contextChanged = previous != null &&
                        (previous.businessId != freshReport.businessId || previous.range != freshReport.range)
                    val pending = voidContext
                    val invalidPreview = pending != null &&
                        (pending.businessId != freshReport.businessId || pending.range != freshReport.range)
                    if (contextChanged || invalidPreview) invalidateVoidContext()
                    updateState {
                        (if (contextChanged || invalidPreview) clearVoidDialog() else this).copy(
                            report = freshReport,
                            isLoading = false,
                            isRefreshing = false,
                            failure = null,
                            voidSucceeded = voidSucceeded && !contextChanged,
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

    private fun requestVoid(saleId: SaleId) {
        val state = uiState.value
        val report = state.report ?: return
        val businessId = report.businessId ?: return
        if (voidCommitPending || voidContext != null || state.isLoading || state.isRefreshing ||
            state.failure != null || report.sales.none { it.saleId == saleId }
        ) return
        val context = VoidContext(++voidRequestSequence, businessId, saleId, report.range)
        voidContext = context
        loadVoidPreview(context)
    }

    private fun loadVoidPreview(context: VoidContext, stale: Boolean = false) {
        if (!isCurrent(context)) return
        voidPreviewPending = true
        voidPreviewJob?.cancel()
        voidPreviewJob = executeMain {
            updateState {
                copy(
                    voidSaleId = context.saleId,
                    voidPreview = null,
                    isLoadingVoidPreview = true,
                    voidFailure = if (stale) ReportsContract.VoidFailure.STALE else null,
                    voidSucceeded = false,
                )
            }
            try {
                val result = withContext(dispatcherProvider.io) {
                    voidSale.preview(context.businessId, context.saleId)
                }
                if (!isCurrent(context)) return@executeMain
                when (result) {
                    is SaleVoidPreviewResult.Ready -> updateState {
                        copy(voidPreview = result.preview)
                    }
                    SaleVoidPreviewResult.AlreadyVoided -> finishVoid(context)
                    else -> updateState { copy(voidFailure = result.toFailure()) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrent(context)) updateState {
                    copy(voidFailure = ReportsContract.VoidFailure.LOAD_FAILED)
                }
            } finally {
                if (voidContext == context) {
                    voidPreviewPending = false
                    updateState { copy(isLoadingVoidPreview = false) }
                }
            }
        }
    }

    private fun confirmVoid() {
        val context = voidContext ?: return
        val preview = uiState.value.voidPreview ?: return
        if (voidCommitPending || voidPreviewPending || !isCurrent(context) ||
            preview.businessId != context.businessId || preview.saleId != context.saleId
        ) return
        voidCommitPending = true
        executeMain {
            updateState { copy(isVoiding = true, voidFailure = null) }
            try {
                val result = withContext(dispatcherProvider.io) { voidSale.confirm(preview) }
                if (!isCurrent(context)) return@executeMain
                when (result) {
                    SaleVoidResult.Voided, SaleVoidResult.AlreadyVoided -> finishVoid(context)
                    // La nueva vista previa nunca se confirma automáticamente.
                    SaleVoidResult.Stale -> loadVoidPreview(context, stale = true)
                    else -> updateState {
                        copy(voidPreview = null, voidFailure = result.toFailure())
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrent(context)) updateState {
                    copy(voidPreview = null, voidFailure = ReportsContract.VoidFailure.OPERATION_FAILED)
                }
            } finally {
                voidCommitPending = false
                updateState { copy(isVoiding = false) }
            }
        }
    }

    private fun finishVoid(context: VoidContext) {
        if (!isCurrent(context)) return
        voidContext = null
        voidPreviewPending = false
        updateState { clearVoidDialog().copy(voidSucceeded = true) }
    }

    private fun isCurrent(context: VoidContext): Boolean {
        val report = uiState.value.report ?: return false
        return voidContext == context && report.businessId == context.businessId &&
            report.range == context.range && uiState.value.selectedPeriod == context.range.period
    }

    private fun invalidateVoidContext() {
        voidContext = null
        voidPreviewPending = false
        voidPreviewJob?.cancel()
        voidPreviewJob = null
    }

    private fun ReportsContract.State.clearVoidDialog() = copy(
        voidSaleId = null,
        voidPreview = null,
        isLoadingVoidPreview = false,
        voidFailure = null,
    )

    private fun SaleVoidPreviewResult.toFailure(): ReportsContract.VoidFailure = when (this) {
        SaleVoidPreviewResult.NoActiveBusiness -> ReportsContract.VoidFailure.NO_ACTIVE_BUSINESS
        SaleVoidPreviewResult.NotFound -> ReportsContract.VoidFailure.NOT_FOUND
        SaleVoidPreviewResult.Unauthorized -> ReportsContract.VoidFailure.UNAUTHORIZED
        SaleVoidPreviewResult.SharedBusinessUnsupported -> ReportsContract.VoidFailure.SHARED_BUSINESS_UNSUPPORTED
        SaleVoidPreviewResult.InvalidHistory -> ReportsContract.VoidFailure.INVALID_HISTORY
        is SaleVoidPreviewResult.Ready, SaleVoidPreviewResult.AlreadyVoided -> error("Resultado disponible")
    }

    private fun SaleVoidResult.toFailure(): ReportsContract.VoidFailure = when (this) {
        SaleVoidResult.NoActiveBusiness -> ReportsContract.VoidFailure.NO_ACTIVE_BUSINESS
        SaleVoidResult.NotFound -> ReportsContract.VoidFailure.NOT_FOUND
        SaleVoidResult.Unauthorized -> ReportsContract.VoidFailure.UNAUTHORIZED
        SaleVoidResult.SharedBusinessUnsupported -> ReportsContract.VoidFailure.SHARED_BUSINESS_UNSUPPORTED
        SaleVoidResult.InvalidHistory -> ReportsContract.VoidFailure.INVALID_HISTORY
        SaleVoidResult.Stale -> ReportsContract.VoidFailure.STALE
        SaleVoidResult.Voided, SaleVoidResult.AlreadyVoided -> error("Resultado completado")
    }

    private data class VoidContext(
        val sequence: Long,
        val businessId: BusinessId,
        val saleId: SaleId,
        val range: SalesReportRange,
    )

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
