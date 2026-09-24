package com.facturastock.app.feature.reports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.facturastock.app.di.appViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.platform.openWithSystemViewer
import com.facturastock.app.ui.platform.rememberSaveFileLauncher

/** Acción de guardar PDF que Reportes publica para el icono de la barra superior. */
@Immutable
data class ReportsPdfTopBarAction(
    val kind: ReportPdfKind,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
fun ReportsRoute(
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = appViewModel(),
    onOpenDebtors: () -> Unit = {},
    onPdfActionAvailable: (ReportsPdfTopBarAction?) -> Unit = {},
    debtorsContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var launchedPdfRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var showingDebtors by rememberSaveable { mutableStateOf(false) }
    val pdfKind = if (showingDebtors && debtorsContent != null) {
        ReportPdfKind.DEBTORS
    } else {
        ReportPdfKind.DAILY_SALES_WITH_DEBTORS
    }
    val currentOnPdfActionAvailable by rememberUpdatedState(onPdfActionAvailable)
    val pdfAction = remember(pdfKind, state.canExportPdf, viewModel) {
        ReportsPdfTopBarAction(
            kind = pdfKind,
            enabled = state.canExportPdf,
            onClick = { viewModel.onAction(ReportsContract.Action.ExportPdfRequested(pdfKind)) },
        )
    }
    LaunchedEffect(pdfAction) { currentOnPdfActionAvailable(pdfAction) }
    DisposableEffect(Unit) {
        onDispose { currentOnPdfActionAvailable(null) }
    }
    val createPdf = rememberSaveFileLauncher(extension = "pdf") { uri ->
        val requestId = launchedPdfRequestId
        launchedPdfRequestId = null
        viewModel.onAction(ReportsContract.Action.PdfDestinationSelected(requestId, uri))
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            ReportsContract.Effect.OpenDebtors -> onOpenDebtors()
            is ReportsContract.Effect.OpenPdf -> {
                if (!openWithSystemViewer(effect.documentUri)) {
                    viewModel.onAction(ReportsContract.Action.PdfViewerUnavailable)
                }
            }
            is ReportsContract.Effect.CreatePdfDocument -> {
                if (viewModel.claimPdfDestination(effect.requestId)) {
                    launchedPdfRequestId = effect.requestId
                    try {
                        createPdf.launch(effect.suggestedFileName)
                    } catch (_: RuntimeException) {
                        launchedPdfRequestId = null
                        viewModel.onAction(ReportsContract.Action.PdfDestinationLaunchFailed(effect.requestId))
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onAction(ReportsContract.Action.Resumed)
                Lifecycle.Event.ON_STOP -> viewModel.onAction(ReportsContract.Action.Stopped)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ReportsScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        showingDebtors = showingDebtors,
        onShowingDebtorsChange = { showingDebtors = it },
        debtorsContent = debtorsContent,
    )
}
