package com.facturastock.app.feature.sales

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.facturastock.app.R
import com.facturastock.app.feature.sales.SalesContract.WeightEntryMode
import com.facturastock.app.feature.sales.SalesContract.WeightSaleEditor
import com.facturastock.app.feature.sales.SalesContract.WeightSaleFailure
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.format.currencyLabelForDisplay
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun WeightSaleDialog(
    editor: WeightSaleEditor,
    isMutating: Boolean,
    onAction: (SalesContract.Action) -> Unit,
) {
    val amountMode = editor.mode == WeightEntryMode.AMOUNT
    val input = if (amountMode) editor.amountInput else editor.quantityInput
    val errorRes = editor.errorRes(input)
    val price = editor.pricePerKg
    FacturaStockDialog(
        title = editor.product.productName,
        message = stringResource(R.string.weight_sale_help),
        confirmLabel =
            stringResource(
                if (editor.lineId == null) R.string.weight_sale_add else R.string.weight_sale_save,
            ),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = {
            if (editor.isValid && !isMutating) onAction(SalesContract.Action.WeightSaleConfirmed)
        },
        onDismiss = {
            if (!isMutating) onAction(SalesContract.Action.WeightSaleDismissed)
        },
        confirmEnabled = editor.isValid && !isMutating,
        dismissEnabled = !isMutating,
        modifier = Modifier.testTag(WeightSaleTestTags.DIALOG),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
            Text(
                text =
                    stringResource(
                        R.string.weight_sale_price_per_kg,
                        price?.formatForDisplay() ?: stringResource(R.string.sales_total_pending),
                    ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(WeightSaleTestTags.PRICE),
            )
            Text(
                text =
                    stringResource(
                        R.string.weight_sale_stock,
                        formatWeightForDisplay(editor.product.availableQuantity),
                        editor.product.locationName,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(WeightSaleTestTags.STOCK),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
                FilterChip(
                    selected = amountMode,
                    onClick = { onAction(SalesContract.Action.WeightEntryModeChanged(WeightEntryMode.AMOUNT)) },
                    label = { Text(stringResource(R.string.weight_sale_by_amount)) },
                    enabled = !isMutating,
                    modifier = Modifier.weight(1f).testTag(WeightSaleTestTags.AMOUNT_MODE),
                )
                FilterChip(
                    selected = !amountMode,
                    onClick = { onAction(SalesContract.Action.WeightEntryModeChanged(WeightEntryMode.QUANTITY)) },
                    label = { Text(stringResource(R.string.weight_sale_by_quantity)) },
                    enabled = !isMutating,
                    modifier = Modifier.weight(1f).testTag(WeightSaleTestTags.QUANTITY_MODE),
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    onAction(
                        if (amountMode) {
                            SalesContract.Action.WeightAmountChanged(value)
                        } else {
                            SalesContract.Action.WeightQuantityChanged(value)
                        },
                    )
                },
                label = {
                    Text(
                        if (amountMode) {
                            stringResource(
                                R.string.weight_sale_amount_label,
                                price
                                    ?.currency
                                    ?.value
                                    ?.let(::currencyLabelForDisplay)
                                    .orEmpty(),
                            )
                        } else {
                            stringResource(R.string.weight_sale_quantity_label)
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !isMutating,
                isError = errorRes != null,
                modifier = Modifier.fillMaxWidth().testTag(WeightSaleTestTags.INPUT),
            )
            errorRes?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(WeightSaleTestTags.ERROR),
                )
            }
            Column(
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.xs),
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.weight_sale_total,
                            editor.total?.formatForDisplay() ?: stringResource(R.string.weight_sale_pending_value),
                        ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag(WeightSaleTestTags.TOTAL),
                )
                Text(
                    text =
                        stringResource(
                            R.string.weight_sale_calculated_quantity,
                            editor.quantity?.value?.let(::formatWeightForDisplay)
                                ?: stringResource(R.string.weight_sale_pending_value),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(WeightSaleTestTags.QUANTITY),
                )
            }
        }
    }
}

@StringRes
private fun WeightSaleEditor.errorRes(input: String): Int? =
    when {
        failure != null -> {
            when (failure) {
                WeightSaleFailure.PRODUCT_CHANGED -> R.string.weight_sale_product_changed
                WeightSaleFailure.PRODUCT_UNAVAILABLE -> R.string.weight_sale_product_unavailable
                WeightSaleFailure.STALE_CART -> R.string.weight_sale_stale_cart
                WeightSaleFailure.SAVE_FAILED -> R.string.weight_sale_save_failed
            }
        }

        (pricePerKg?.minorUnits ?: 0L) <= 0L -> {
            R.string.weight_sale_missing_price
        }

        exceedsStock -> {
            R.string.weight_sale_exceeds_stock
        }

        !isValid && (submitAttempted || input.isNotBlank()) -> {
            if (mode == WeightEntryMode.AMOUNT) {
                R.string.weight_sale_invalid_amount
            } else {
                R.string.weight_sale_invalid_quantity
            }
        }

        else -> {
            null
        }
    }

/** Sólo redondea la presentación; la cantidad precisa del editor se conserva para guardar. */
internal fun formatWeightForDisplay(value: BigDecimal): String {
    val rounded = value.setScale(minOf(value.scale().coerceAtLeast(0), 6), RoundingMode.HALF_UP)
    if (rounded.signum() == 0 && value.signum() > 0) return "< 0,000001"
    val text = rounded.stripTrailingZeros().toPlainString().replace('.', ',')
    return if (rounded.compareTo(value) == 0) text else "≈ $text"
}

object WeightSaleTestTags {
    const val DIALOG = "weight_sale_dialog"
    const val AMOUNT_MODE = "weight_sale_amount_mode"
    const val QUANTITY_MODE = "weight_sale_quantity_mode"
    const val INPUT = "weight_sale_input"
    const val PRICE = "weight_sale_price"
    const val STOCK = "weight_sale_stock"
    const val QUANTITY = "weight_sale_quantity"
    const val TOTAL = "weight_sale_total"
    const val ERROR = "weight_sale_error"

    fun edit(lineId: String) = "weight_sale_edit_$lineId"

    fun summary(lineId: String) = "weight_sale_summary_$lineId"
}
