package com.facturastock.app.feature.reports

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.feature.common.CollectUiEffects

@Composable
fun ReportsRoute(
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = hiltViewModel(),
    onOpenDebtors: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var launchedPdfRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val requestId = launchedPdfRequestId
        launchedPdfRequestId = null
        viewModel.onAction(ReportsContract.Action.PdfDestinationSelected(requestId, uri?.toString()))
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            ReportsContract.Effect.OpenDebtors -> onOpenDebtors()
            is ReportsContract.Effect.OpenPdf -> {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(effect.documentUri), "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.onAction(ReportsContract.Action.PdfViewerUnavailable)
                } catch (_: SecurityException) {
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
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAction(ReportsContract.Action.Resumed)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ReportsScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}
