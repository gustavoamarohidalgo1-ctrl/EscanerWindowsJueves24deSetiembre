package com.facturastock.app.feature.top

import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.facturastock.app.ui.components.EmptyState

@Composable
fun TopLevelPlaceholderScreen(
    titleRes: StringResource,
    messageRes: StringResource,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = stringResource(titleRes),
        message = stringResource(messageRes),
        modifier = modifier.fillMaxSize(),
    )
}
