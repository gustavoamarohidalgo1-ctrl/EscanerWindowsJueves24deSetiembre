package com.facturastock.app.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.facturastock.app.R
import com.facturastock.app.ui.components.ConfidenceChip
import com.facturastock.app.ui.components.EmptyState
import com.facturastock.app.ui.components.FacturaStockBottomItem
import com.facturastock.app.ui.components.FacturaStockBottomNavigation
import com.facturastock.app.ui.components.FacturaStockDialogContent
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.FacturaStockTopBar
import com.facturastock.app.ui.components.FixedMoneySummary
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign
import com.facturastock.app.ui.theme.FacturaStockTheme

@ThemePreviews
@CompactAccessibilityPreviews
@Composable
private fun NavigationAndActionsPreview() {
    FacturaStockTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                FacturaStockTopBar(
                    title = stringResource(R.string.app_name),
                    actionIconRes = R.drawable.ic_info,
                    actionContentDescription = stringResource(
                        R.string.action_more_information,
                    ),
                    onActionClick = {},
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(FacturaStockDesign.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(
                        FacturaStockDesign.spacing.md,
                    ),
                ) {
                    FacturaStockPrimaryButton(
                        text = stringResource(R.string.action_new_purchase),
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        leadingIconRes = R.drawable.ic_add_document,
                    )
                    FacturaStockSecondaryButton(
                        text = stringResource(R.string.action_view_purchases),
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        leadingIconRes = R.drawable.ic_receipt,
                    )
                }
                FacturaStockBottomNavigation(
                    items = previewNavigationItems(),
                    selectedIndex = 0,
                    onItemSelected = {},
                )
            }
        }
    }
}

@ThemePreviews
@LargeFontPreview
@CompactAccessibilityPreviews
@Composable
private fun FeedbackComponentsPreview() {
    FacturaStockTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(FacturaStockDesign.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(
                    FacturaStockDesign.spacing.md,
                ),
            ) {
                item {
                    StatusCard(
                        statusLabel = stringResource(R.string.preview_status_label),
                        title = stringResource(R.string.preview_status_title),
                        message = stringResource(R.string.preview_status_message),
                        tone = StatusTone.SUCCESS,
                        iconRes = R.drawable.ic_check_circle,
                        supportingContent = {
                            ConfidenceChip(
                                label = stringResource(R.string.preview_confidence_label),
                                percentage = 96,
                                tone = StatusTone.SUCCESS,
                            )
                        },
                    )
                }
                item {
                    RecoverableError(
                        title = stringResource(R.string.preview_error_title),
                        message = stringResource(R.string.preview_error_message),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = {},
                    )
                }
                item {
                    LoadingState(
                        message = stringResource(R.string.preview_loading_message),
                    )
                }
                item {
                    EmptyState(
                        title = stringResource(R.string.preview_empty_title),
                        message = stringResource(R.string.preview_empty_message),
                        actionLabel = stringResource(R.string.action_create_first),
                        onAction = {},
                    )
                }
            }
        }
    }
}

@ThemePreviews
@LargeFontPreview
@CompactAccessibilityPreviews
@Composable
private fun DialogAndSummaryPreview() {
    FacturaStockTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(FacturaStockDesign.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(
                    FacturaStockDesign.spacing.lg,
                ),
            ) {
                item {
                    FacturaStockDialogContent(
                        title = stringResource(R.string.preview_dialog_title),
                        message = stringResource(R.string.preview_dialog_message),
                        confirmLabel = stringResource(R.string.action_confirm_purchase),
                        dismissLabel = stringResource(R.string.action_cancel),
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
                item {
                    FixedMoneySummary(
                        label = stringResource(R.string.preview_total_label),
                        amount = stringResource(R.string.preview_total_amount),
                        spokenAmount = stringResource(R.string.preview_total_description),
                        actionLabel = stringResource(R.string.action_continue),
                        onAction = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun previewNavigationItems(): List<FacturaStockBottomItem> = listOf(
    FacturaStockBottomItem(
        label = stringResource(R.string.navigation_home),
        iconRes = R.drawable.ic_home,
    ),
    FacturaStockBottomItem(
        label = stringResource(R.string.navigation_products),
        iconRes = R.drawable.ic_products,
    ),
    FacturaStockBottomItem(
        label = stringResource(R.string.navigation_purchases),
        iconRes = R.drawable.ic_receipt,
    ),
)
