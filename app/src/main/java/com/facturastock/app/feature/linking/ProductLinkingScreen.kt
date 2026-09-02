package com.facturastock.app.feature.linking

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.facturastock.app.R
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.ProductMatchCandidate
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.feature.linking.ProductLinkingContract.Action
import com.facturastock.app.feature.linking.ProductLinkingContract.CreateForm
import com.facturastock.app.feature.linking.ProductLinkingContract.Failure
import com.facturastock.app.feature.linking.ProductLinkingContract.LineLinking
import com.facturastock.app.feature.linking.ProductLinkingContract.LinkStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.SearchStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.State
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign
import com.facturastock.app.ui.format.formatForDisplay

/**
 * Vinculación stateless: la línea actual con sus candidatos arriba, la lista completa con el
 * estado de cada línea debajo, y la barra de avance fija al final. Crear un producto abre un
 * diálogo que nunca abandona la revisión.
 */
@Composable
fun ProductLinkingScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(ProductLinkingTestTags.SCREEN),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(ProductLinkingTestTags.LIST),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "linking_progress", contentType = "header") {
                LinkingProgressHeader(state = state)
            }
            if (
                state.failure == Failure.SAVE_CONFLICT ||
                state.failure == Failure.SAVE_FAILED
            ) {
                item(key = "linking_failure", contentType = "failure") {
                    RecoverableError(
                        title = stringResource(
                            if (state.failure == Failure.SAVE_CONFLICT) {
                                R.string.linking_conflict_title
                            } else {
                                R.string.save_error_title
                            },
                        ),
                        message = stringResource(
                            if (state.failure == Failure.SAVE_CONFLICT) {
                                R.string.linking_conflict_message
                            } else {
                                R.string.save_error_message
                            },
                        ),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { onAction(Action.Retry) },
                        modifier = Modifier.testTag(ProductLinkingTestTags.FAILURE_BANNER),
                    )
                }
            }
            item(key = "linking_current", contentType = "current_line") {
                CurrentLineCard(state = state, onAction = onAction)
            }
            item(key = "linking_lines_header", contentType = "header") {
                Text(
                    text = stringResource(R.string.linking_lines_section),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(
                items = state.lines,
                key = { line -> line.lineId.value },
                contentType = { "linking_line_row" },
            ) { line ->
                LinkingLineRow(
                    line = line,
                    isCurrent = line.lineId == state.currentLineId,
                    onAction = onAction,
                )
            }
        }

        ContinueBar(state = state, onAction = onAction)
    }

    state.createForm?.let { form ->
        CreateProductDialog(
            form = form,
            units = state.units,
            isBusy = state.isBusy,
            salePriceFailure = state.salePriceFailure,
            onAction = onAction,
        )
    }
    state.salePriceForm?.let { form ->
        SalePriceDialog(
            form = form,
            isBusy = state.isBusy,
            failure = state.salePriceFailure,
            onAction = onAction,
        )
    }
}

@Composable
private fun LinkingProgressHeader(state: State) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProductLinkingTestTags.PROGRESS)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = stringResource(
                R.string.linking_line_progress,
                state.currentLineIndex + 1,
                state.lines.size,
            ),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(
                R.string.linking_resolved_count,
                state.resolvedCount,
                state.lines.size,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CurrentLineCard(
    state: State,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val line = state.currentLine
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProductLinkingTestTags.CURRENT_LINE),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (line == null) {
                Text(
                    text = stringResource(R.string.linking_empty_lines),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }
            Text(
                text = stringResource(R.string.linking_current_section),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = line.description.ifBlank {
                    stringResource(R.string.linking_description_pending)
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            line.code?.let { code ->
                Text(
                    text = stringResource(R.string.linking_line_code, code),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (
                line.linkedProductName != null &&
                (line.status == LinkStatus.CONFIRMED || line.status == LinkStatus.AUTO_LINKED)
            ) {
                Text(
                    text = stringResource(R.string.linking_linked_to, line.linkedProductName),
                    color = FacturaStockDesign.semanticColors.success,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (line.requiresSalePrice) {
                Text(
                    text = stringResource(R.string.linking_sale_price_required_legacy),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { query -> onAction(Action.SearchChanged(query)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProductLinkingTestTags.SEARCH),
                label = { Text(stringResource(R.string.linking_search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
            )

            Text(
                text = stringResource(R.string.linking_candidates_section),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            when (state.searchStatus) {
                SearchStatus.SEARCHING -> Text(
                    text = stringResource(R.string.linking_searching),
                    modifier = Modifier
                        .testTag(ProductLinkingTestTags.SEARCH_PROGRESS)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )

                SearchStatus.FAILED -> RecoverableError(
                    title = stringResource(R.string.linking_search_error_title),
                    message = stringResource(R.string.linking_search_error_message),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(Action.RetrySearch) },
                    modifier = Modifier.testTag(ProductLinkingTestTags.SEARCH_FAILURE),
                )

                SearchStatus.IDLE -> if (state.candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.linking_no_candidates),
                        modifier = Modifier.testTag(ProductLinkingTestTags.NO_CANDIDATES),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        state.candidates.forEach { candidate ->
                            CandidateRow(
                                candidate = candidate,
                                configuredCurrency = state.currency,
                                enabled = state.canSelectCandidate,
                                onConfirm = {
                                    onAction(Action.CandidateConfirmed(candidate.product.productId))
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.linking_action_create),
                    onClick = { onAction(Action.OpenCreateForm) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ProductLinkingTestTags.CREATE),
                    enabled = !state.isBusy,
                )
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.linking_action_skip),
                    onClick = { onAction(Action.SkipLine) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ProductLinkingTestTags.SKIP),
                    enabled = !state.isBusy &&
                        !line.requiresSalePrice &&
                        (
                            line.status == LinkStatus.NEEDS_CHOICE ||
                                line.status == LinkStatus.NO_MATCH
                            ),
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: ProductMatchCandidate,
    configuredCurrency: com.facturastock.app.domain.model.CurrencyCode,
    enabled: Boolean,
    onConfirm: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val secondary = listOfNotNull(
        candidate.product.sku?.let { sku -> stringResource(R.string.linking_candidate_sku, sku) },
        candidate.product.barcode?.let { barcode ->
            stringResource(R.string.linking_candidate_barcode, barcode)
        },
    ).joinToString(separator = " · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProductLinkingTestTags.candidate(candidate.product.productId)),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.product.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (secondary.isNotEmpty()) {
                        Text(
                            text = secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = candidate.product.salePrice
                            ?.takeIf { it.currency == configuredCurrency }
                            ?.let { price ->
                                stringResource(
                                    R.string.linking_candidate_sale_price,
                                    price.formatForDisplay(),
                                )
                            }
                            ?: stringResource(R.string.linking_candidate_sale_price_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.width(spacing.sm))
                ReasonChip(reason = candidate.reason)
            }
            Spacer(modifier = Modifier.height(spacing.xs))
            FacturaStockPrimaryButton(
                text = stringResource(
                    if (candidate.product.salePrice?.currency == configuredCurrency) {
                        R.string.linking_action_link
                    } else {
                        R.string.linking_action_set_price_and_link
                    },
                ),
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProductLinkingTestTags.candidateLink(candidate.product.productId)),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ReasonChip(reason: ProductMatchReason) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = stringResource(reasonLabelRes(reason)),
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LinkingLineRow(
    line: LineLinking,
    isCurrent: Boolean,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        onClick = { onAction(Action.LineSelected(line.lineId)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProductLinkingTestTags.lineRow(line.lineId)),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isCurrent) {
            BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.linking_line_number, line.position + 1),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = line.description.ifBlank {
                        stringResource(R.string.linking_description_pending)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                line.linkedProductName?.let { productName ->
                    Text(
                        text = stringResource(R.string.linking_linked_to, productName),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(status = line.status)
                if (
                    line.status == LinkStatus.CONFIRMED || line.status == LinkStatus.AUTO_LINKED
                ) {
                    TextButton(
                        onClick = { onAction(Action.ChangeLink(line.lineId)) },
                        modifier = Modifier.testTag(ProductLinkingTestTags.changeLink(line.lineId)),
                    ) {
                        Text(stringResource(R.string.linking_action_change))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: LinkStatus) {
    val spacing = FacturaStockDesign.spacing
    val semantic = FacturaStockDesign.semanticColors
    val (container, content) = when (status) {
        LinkStatus.AUTO_LINKED,
        LinkStatus.CONFIRMED,
        -> semantic.successContainer to semantic.onSuccessContainer

        LinkStatus.NEEDS_CHOICE -> semantic.warningContainer to semantic.onWarningContainer

        LinkStatus.NO_MATCH ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer

        LinkStatus.SKIPPED ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = stringResource(statusLabelRes(status)),
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ContinueBar(
    state: State,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = spacing.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(
                    R.string.linking_resolved_count,
                    state.resolvedCount,
                    state.lines.size,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            FacturaStockPrimaryButton(
                text = stringResource(R.string.action_continue),
                onClick = { onAction(Action.ContinueSelected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProductLinkingTestTags.CONTINUE),
                enabled = state.canContinue,
            )
        }
    }
}

/** Diálogo de creación: valida en la propia pantalla y nunca abandona la revisión. */
@Composable
private fun CreateProductDialog(
    form: CreateForm,
    units: List<UnitOfMeasure>,
    isBusy: Boolean,
    salePriceFailure: ProductLinkingContract.SalePriceFailure?,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val title = stringResource(R.string.linking_create_title)

    Dialog(
        onDismissRequest = { onAction(Action.CreateFormDismissed) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .imePadding()
                .testTag(ProductLinkingTestTags.CREATE_DIALOG)
                .semantics {
                    paneTitle = title
                    isTraversalGroup = true
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TextButton(
                        onClick = { onAction(Action.CreateFormDismissed) },
                        modifier = Modifier.testTag(ProductLinkingTestTags.CREATE_DISMISS),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = spacing.lg,
                        end = spacing.lg,
                        bottom = spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    form.duplicate?.let { duplicate ->
                        item(key = "create_duplicate", contentType = "duplicate") {
                            DuplicateBanner(
                                productName = duplicate.name,
                                enabled = !isBusy,
                                onLinkExisting = {
                                    onAction(Action.CreateDuplicateLinkExisting)
                                },
                            )
                        }
                    }
                    item(key = "create_name", contentType = "field") {
                        OutlinedTextField(
                            value = form.name,
                            onValueChange = { value ->
                                onAction(Action.CreateFormChanged(form.copy(name = value)))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ProductLinkingTestTags.CREATE_NAME),
                            label = { Text(stringResource(R.string.linking_create_name_label)) },
                            singleLine = true,
                            isError = form.submitAttempted && form.name.isBlank(),
                            supportingText = if (form.submitAttempted && form.name.isBlank()) {
                                { Text(stringResource(R.string.linking_create_error_name)) }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next,
                            ),
                        )
                    }
                    item(key = "create_unit", contentType = "field") {
                        UnitDropdown(
                            labelRes = R.string.linking_create_unit_label,
                            selectedUnitId = form.unitId,
                            units = units,
                            includeEmptyOption = false,
                            isError = form.submitAttempted && form.unitId == null,
                            errorRes = R.string.linking_create_error_unit,
                            testTag = ProductLinkingTestTags.CREATE_UNIT,
                            onSelected = { unitId ->
                                onAction(Action.CreateFormChanged(form.copy(unitId = unitId)))
                            },
                        )
                    }
                    item(key = "create_sale_price", contentType = "field") {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            OutlinedTextField(
                                value = form.salePrice,
                                onValueChange = { value ->
                                    onAction(Action.CreateFormChanged(form.copy(salePrice = value)))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(ProductLinkingTestTags.CREATE_SALE_PRICE),
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.linking_create_sale_price_label,
                                            form.salePriceCurrency.value,
                                        ),
                                    )
                                },
                                singleLine = true,
                                isError = form.submitAttempted && !form.isSalePriceValid,
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (form.submitAttempted && !form.isSalePriceValid) {
                                                R.string.linking_sale_price_invalid
                                            } else {
                                                R.string.linking_sale_price_help
                                            },
                                        ),
                                    )
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Next,
                                ),
                            )
                            salePriceFailure?.let { error ->
                                Text(
                                    text = stringResource(error.messageRes()),
                                    modifier = Modifier
                                        .testTag(ProductLinkingTestTags.CREATE_SALE_PRICE_ERROR)
                                        .semantics { liveRegion = LiveRegionMode.Assertive },
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    item(key = "create_sku", contentType = "field") {
                        OutlinedTextField(
                            value = form.sku,
                            onValueChange = { value ->
                                onAction(Action.CreateFormChanged(form.copy(sku = value)))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ProductLinkingTestTags.CREATE_SKU),
                            label = { Text(stringResource(R.string.linking_create_sku_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next,
                            ),
                        )
                    }
                    item(key = "create_barcode", contentType = "field") {
                        OutlinedTextField(
                            value = form.barcode,
                            onValueChange = { value ->
                                onAction(Action.CreateFormChanged(form.copy(barcode = value)))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ProductLinkingTestTags.CREATE_BARCODE),
                            label = {
                                Text(stringResource(R.string.linking_create_barcode_label))
                            },
                            isError = form.submitAttempted && !form.isBarcodeValid,
                            supportingText = if (form.submitAttempted && !form.isBarcodeValid) {
                                { Text(stringResource(R.string.linking_create_error_barcode)) }
                            } else {
                                null
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                        )
                    }
                    item(key = "create_purchase_unit", contentType = "field") {
                        UnitDropdown(
                            labelRes = R.string.linking_create_purchase_unit_label,
                            selectedUnitId = form.purchaseUnitId,
                            units = units,
                            includeEmptyOption = true,
                            isError = false,
                            errorRes = null,
                            testTag = ProductLinkingTestTags.CREATE_PURCHASE_UNIT,
                            onSelected = { unitId ->
                                onAction(
                                    Action.CreateFormChanged(
                                        form.copy(
                                            purchaseUnitId = unitId,
                                            purchaseFactor = if (unitId == null) {
                                                ""
                                            } else {
                                                form.purchaseFactor
                                            },
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    if (form.purchaseUnitId != null) {
                        item(key = "create_factor", contentType = "field") {
                            OutlinedTextField(
                                value = form.purchaseFactor,
                                onValueChange = { value ->
                                    onAction(
                                        Action.CreateFormChanged(
                                            form.copy(purchaseFactor = value),
                                        ),
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(ProductLinkingTestTags.CREATE_FACTOR),
                                label = {
                                    Text(stringResource(R.string.linking_create_factor_label))
                                },
                                singleLine = true,
                                isError = form.submitAttempted && !form.isFactorValid,
                                supportingText = if (form.submitAttempted && !form.isFactorValid) {
                                    { Text(stringResource(R.string.linking_create_error_factor)) }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done,
                                ),
                            )
                        }
                    }
                    form.purchaseEquivalence?.let { equivalence ->
                        item(key = "create_equivalence", contentType = "equivalence") {
                            EquivalenceText(
                                form = form,
                                units = units,
                                equivalence = equivalence,
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = spacing.xs,
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = spacing.lg,
                            vertical = spacing.sm,
                        ),
                    ) {
                        FacturaStockPrimaryButton(
                            text = stringResource(R.string.linking_create_submit),
                            onClick = { onAction(Action.CreateSubmitted) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ProductLinkingTestTags.CREATE_SUBMIT),
                            enabled = !isBusy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SalePriceDialog(
    form: ProductLinkingContract.SalePriceForm,
    isBusy: Boolean,
    failure: ProductLinkingContract.SalePriceFailure?,
    onAction: (Action) -> Unit,
) {
    FacturaStockDialog(
        title = stringResource(R.string.linking_sale_price_title),
        message = stringResource(
            R.string.linking_sale_price_message,
            form.productName,
            form.currency.value,
        ),
        confirmLabel = if (isBusy) {
            stringResource(R.string.linking_sale_price_saving)
        } else {
            stringResource(R.string.linking_sale_price_confirm)
        },
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = { onAction(Action.SalePriceSubmitted) },
        onDismiss = { onAction(Action.SalePriceDismissed) },
        confirmEnabled = !isBusy,
        dismissEnabled = !isBusy,
        modifier = Modifier.testTag(ProductLinkingTestTags.SALE_PRICE_DIALOG),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
            OutlinedTextField(
                value = form.value,
                onValueChange = { onAction(Action.SalePriceChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProductLinkingTestTags.SALE_PRICE_FIELD),
                label = {
                    Text(
                        stringResource(
                            R.string.linking_create_sale_price_label,
                            form.currency.value,
                        ),
                    )
                },
                singleLine = true,
                isError = form.submitAttempted && !form.isValid,
                supportingText = {
                    Text(
                        stringResource(
                            if (form.submitAttempted && !form.isValid) {
                                R.string.linking_sale_price_invalid
                            } else {
                                R.string.linking_sale_price_help
                            },
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )
            failure?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    modifier = Modifier
                        .testTag(ProductLinkingTestTags.SALE_PRICE_ERROR)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            }
        },
    )
}

@Composable
private fun DuplicateBanner(
    productName: String,
    enabled: Boolean,
    onLinkExisting: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProductLinkingTestTags.CREATE_DUPLICATE),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = FacturaStockDesign.semanticColors.warningContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = stringResource(R.string.linking_create_duplicate_title),
                modifier = Modifier.semantics { heading() },
                color = FacturaStockDesign.semanticColors.onWarningContainer,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = stringResource(R.string.linking_create_duplicate_message, productName),
                color = FacturaStockDesign.semanticColors.onWarningContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            FacturaStockSecondaryButton(
                text = stringResource(R.string.linking_create_duplicate_link),
                onClick = onLinkExisting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProductLinkingTestTags.CREATE_DUPLICATE_LINK),
                enabled = enabled,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(
    @StringRes labelRes: Int,
    selectedUnitId: UnitId?,
    units: List<UnitOfMeasure>,
    includeEmptyOption: Boolean,
    isError: Boolean,
    @StringRes errorRes: Int?,
    testTag: String,
    onSelected: (UnitId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = units.firstOrNull { unit -> unit.unitId == selectedUnitId }
    val shownValue = when {
        selected != null -> stringResource(
            R.string.linking_unit_option,
            selected.name,
            selected.code,
        )

        includeEmptyOption -> stringResource(R.string.linking_create_no_purchase_unit)

        else -> ""
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = shownValue,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(labelRes)) },
            isError = isError,
            supportingText = errorRes?.takeIf { isError }?.let { res ->
                { Text(stringResource(res)) }
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .testTag(testTag),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (includeEmptyOption) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.linking_create_no_purchase_unit)) },
                    onClick = {
                        expanded = false
                        onSelected(null)
                    },
                )
            }
            units.forEach { unit ->
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.linking_unit_option, unit.name, unit.code))
                    },
                    onClick = {
                        expanded = false
                        onSelected(unit.unitId)
                    },
                )
            }
        }
    }
}

@Composable
private fun EquivalenceText(
    form: CreateForm,
    units: List<UnitOfMeasure>,
    equivalence: String,
) {
    val purchaseUnitName = units.firstOrNull { unit -> unit.unitId == form.purchaseUnitId }?.name
    val inventoryUnitName = units.firstOrNull { unit -> unit.unitId == form.unitId }?.name
    Text(
        text = if (purchaseUnitName != null && inventoryUnitName != null) {
            stringResource(
                R.string.linking_equivalence_full,
                "1",
                purchaseUnitName,
                equivalence,
                inventoryUnitName,
            )
        } else {
            stringResource(R.string.linking_equivalence_plain, equivalence)
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProductLinkingTestTags.CREATE_EQUIVALENCE),
        color = FacturaStockDesign.semanticColors.success,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@StringRes
private fun reasonLabelRes(reason: ProductMatchReason): Int = when (reason) {
    ProductMatchReason.BARCODE -> R.string.linking_reason_barcode
    ProductMatchReason.SUPPLIER_CODE -> R.string.linking_reason_supplier_code
    ProductMatchReason.CONFIRMED_ALIAS -> R.string.linking_reason_confirmed_alias
    ProductMatchReason.SKU -> R.string.linking_reason_sku
    ProductMatchReason.EXACT_NAME -> R.string.linking_reason_exact_name
    ProductMatchReason.SIMILAR_NAME -> R.string.linking_reason_similar_name
}

@StringRes
private fun statusLabelRes(status: LinkStatus): Int = when (status) {
    LinkStatus.AUTO_LINKED -> R.string.linking_status_auto_linked
    LinkStatus.CONFIRMED -> R.string.linking_status_confirmed
    LinkStatus.NEEDS_CHOICE -> R.string.linking_status_needs_choice
    LinkStatus.NO_MATCH -> R.string.linking_status_no_match
    LinkStatus.SKIPPED -> R.string.linking_status_skipped
}

@StringRes
private fun ProductLinkingContract.SalePriceFailure.messageRes(): Int = when (this) {
    ProductLinkingContract.SalePriceFailure.INVALID_PRICE -> R.string.linking_sale_price_invalid
    ProductLinkingContract.SalePriceFailure.STALE -> R.string.linking_sale_price_stale
    ProductLinkingContract.SalePriceFailure.CURRENCY_MISMATCH ->
        R.string.linking_sale_price_currency
    ProductLinkingContract.SalePriceFailure.PRODUCT_UNAVAILABLE ->
        R.string.linking_sale_price_unavailable
    ProductLinkingContract.SalePriceFailure.CONFIGURATION_CHANGED ->
        R.string.linking_sale_price_configuration_changed
    ProductLinkingContract.SalePriceFailure.SAVE_FAILED ->
        R.string.linking_sale_price_save_failed
}
