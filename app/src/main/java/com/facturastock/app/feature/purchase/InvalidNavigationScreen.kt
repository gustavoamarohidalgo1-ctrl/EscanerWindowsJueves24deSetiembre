package com.facturastock.app.feature.purchase

import com.facturastock.app.resources.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.facturastock.app.ui.components.EmptyState

@Composable
fun InvalidNavigationScreen(
    onReturnToPurchases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = stringResource(Res.string.navigation_invalid_title),
        message = stringResource(Res.string.navigation_invalid_message),
        modifier = modifier.fillMaxSize(),
        actionLabel = stringResource(Res.string.action_return_purchases),
        onAction = onReturnToPurchases,
    )
}
