package com.facturastock.app.feature.debtors

import com.facturastock.app.resources.*
import com.facturastock.app.ui.navigation.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.facturastock.app.di.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.FeatureLoadContent
import com.facturastock.app.ui.components.RecoverableError
import kotlinx.coroutines.launch

@Composable
fun DebtorsRoute(
    onOpenDebt: (DebtId) -> Unit,
    onNewDebt: () -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DebtorsViewModel = appViewModel(),
    onDebtDeleted: () -> Unit = onCloseInvalidRoute,
    onFullPaymentSaved: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val paymentSaved = stringResource(Res.string.debt_payment_saved)

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            DebtorsContract.Effect.OpenNewDebt -> onNewDebt()
            is DebtorsContract.Effect.OpenDebt -> onOpenDebt(effect.debtId)
            DebtorsContract.Effect.PaymentSaved -> scope.launch {
                snackbarHostState.showSnackbar(paymentSaved)
            }
            DebtorsContract.Effect.FullPaymentSaved -> if (viewModel.claimFullPaymentNavigation()) onFullPaymentSaved()
            DebtorsContract.Effect.DebtDeleted -> onDebtDeleted()
            DebtorsContract.Effect.Back -> onBack()
            DebtorsContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    BackHandler(enabled = state.paymentEditor != null || state.deleteTarget != null || state.isDeletingDebt || state.isSavingPayment) {
        viewModel.onAction(DebtorsContract.Action.BackSelected)
    }

    if (state.failure == DebtorsContract.Failure.DEBT_NOT_FOUND && state.detail == null) {
        RecoverableError(
            title = stringResource(Res.string.debt_not_found_title),
            message = stringResource(Res.string.debt_not_found_message),
            actionLabel = stringResource(Res.string.action_return_debtors),
            onAction = onCloseInvalidRoute,
            modifier = modifier,
        )
        return
    }

    val loadFailure = state.failure == DebtorsContract.Failure.LOAD_FAILED
    Box(modifier = modifier.fillMaxSize()) {
        FeatureLoadContent(
            isLoading = state.isLoading,
            hasContent = if (state.debtId == null) {
                state.allDebts.isNotEmpty()
            } else {
                state.detail != null
            },
            hasFailure = loadFailure,
            onRetry = { viewModel.onAction(DebtorsContract.Action.Retry) },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.debtId == null) {
                DebtorsListScreen(state = state, onAction = viewModel::onAction)
            } else {
                DebtDetailScreen(state = state, onAction = viewModel::onAction)
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
