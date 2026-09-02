package com.facturastock.app.feature.purchase

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.facturastock.app.R
import com.facturastock.app.ui.components.EmptyState

@Composable
fun InvalidNavigationScreen(
    onReturnToPurchases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = stringResource(R.string.navigation_invalid_title),
        message = stringResource(R.string.navigation_invalid_message),
        modifier = modifier.fillMaxSize(),
        actionLabel = stringResource(R.string.action_return_purchases),
        onAction = onReturnToPurchases,
    )
}
