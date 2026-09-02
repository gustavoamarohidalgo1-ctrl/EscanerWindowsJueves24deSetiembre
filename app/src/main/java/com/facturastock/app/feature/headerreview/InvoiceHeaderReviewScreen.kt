package com.facturastock.app.feature.headerreview

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.facturastock.app.feature.common.sensitiveImageRequest
import com.facturastock.app.R
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Action
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.AdvanceBlockReason
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Confidence
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Evidence
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Field
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldError
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldId
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Page
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.ReviewWarning
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.ReviewWarningCode
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.State
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.io.File
import kotlinx.coroutines.yield

/**
 * Revisión móvil de cabecera. El formulario se desplaza, pero el total y la acción permanecen
 * fijos y [imePadding] los coloca por encima del teclado.
 */
@Composable
fun InvoiceHeaderReviewScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
    requestedFocusField: FieldId? = null,
    onRequestedFocusHandled: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val focusRequesters = remember {
        FieldId.entries.associateWith { FocusRequester() }
    }
    var localFocusField by remember { mutableStateOf<FieldId?>(null) }
    val focusTarget = requestedFocusField ?: localFocusField

    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        listState.animateScrollToItem(fieldLazyIndex(target))
        yield()
        focusRequesters.getValue(target).requestFocus()
        localFocusField = null
        if (requestedFocusField != null) {
            onRequestedFocusHandled()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(InvoiceHeaderReviewTestTags.SCREEN),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(InvoiceHeaderReviewTestTags.LIST),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "document", contentType = "document") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    DocumentThumbnail(
                        page = state.pages.firstOrNull(),
                        onExpand = {
                            onAction(Action.ShowExpandedPage(index = 0))
                        },
                    )
                    if (state.saveFailure) {
                        RecoverableError(
                            title = stringResource(R.string.header_review_save_error_title),
                            message = stringResource(
                                if (state.failure == InvoiceHeaderReviewContract.Failure.STORAGE_FULL) {
                                    R.string.storage_full_recoverable_message
                                } else {
                                    R.string.header_review_save_error_message
                                },
                            ),
                            actionLabel = stringResource(R.string.header_review_retry_save),
                            onAction = { onAction(Action.RetrySave) },
                        )
                    }
                    HeaderReviewWarnings(state.warnings)
                }
            }

            item(key = "supplier_heading", contentType = "section_header") {
                SectionHeading(R.string.header_review_supplier_section)
            }
            item(key = FieldId.RUC, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.RUC),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.RUC),
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Number,
                    onAction = onAction,
                    onNext = { focusRequesters.getValue(FieldId.SUPPLIER).requestFocus() },
                )
            }
            item(key = FieldId.SUPPLIER, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.SUPPLIER),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.SUPPLIER),
                    imeAction = ImeAction.Next,
                    onAction = onAction,
                    onNext = {
                        focusRequesters.getValue(FieldId.DOCUMENT_TYPE).requestFocus()
                    },
                )
            }

            item(key = "document_heading", contentType = "section_header") {
                SectionHeading(R.string.header_review_document_section)
            }
            item(key = FieldId.DOCUMENT_TYPE, contentType = "document_type_field") {
                DocumentTypeField(
                    field = state.field(FieldId.DOCUMENT_TYPE),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.DOCUMENT_TYPE),
                    onAction = onAction,
                )
            }
            item(key = FieldId.SERIES, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.SERIES),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.SERIES),
                    imeAction = ImeAction.Next,
                    capitalization = KeyboardCapitalization.Characters,
                    onAction = onAction,
                    onNext = { focusRequesters.getValue(FieldId.NUMBER).requestFocus() },
                )
            }
            item(key = FieldId.NUMBER, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.NUMBER),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.NUMBER),
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Number,
                    onAction = onAction,
                    onNext = {
                        focusRequesters.getValue(FieldId.ISSUE_DATE).requestFocus()
                    },
                )
            }
            item(key = FieldId.ISSUE_DATE, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.ISSUE_DATE),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.ISSUE_DATE),
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Number,
                    onAction = onAction,
                    onNext = { focusRequesters.getValue(FieldId.CURRENCY).requestFocus() },
                )
            }
            item(key = FieldId.CURRENCY, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.CURRENCY),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.CURRENCY),
                    imeAction = ImeAction.Next,
                    capitalization = KeyboardCapitalization.Characters,
                    onAction = onAction,
                    onNext = { focusRequesters.getValue(FieldId.SUBTOTAL).requestFocus() },
                )
            }

            item(key = "totals_heading", contentType = "section_header") {
                SectionHeading(R.string.header_review_totals_section)
            }
            item(key = FieldId.SUBTOTAL, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.SUBTOTAL),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.SUBTOTAL),
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Decimal,
                    onAction = onAction,
                    onNext = { focusRequesters.getValue(FieldId.IGV).requestFocus() },
                )
            }
            item(key = FieldId.IGV, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.IGV),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.IGV),
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Decimal,
                    onAction = onAction,
                    onNext = {
                        focusRequesters.getValue(FieldId.OTHER_CHARGES).requestFocus()
                    },
                )
            }
            item(key = FieldId.OTHER_CHARGES, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.OTHER_CHARGES),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.OTHER_CHARGES),
                    imeAction = ImeAction.Next,
                    keyboardType = KeyboardType.Decimal,
                    onAction = onAction,
                    onNext = { focusRequesters.getValue(FieldId.TOTAL).requestFocus() },
                )
            }
            item(key = FieldId.TOTAL, contentType = "text_field") {
                EditableField(
                    field = state.field(FieldId.TOTAL),
                    continueAttempted = state.continueAttempted,
                    focusRequester = focusRequesters.getValue(FieldId.TOTAL),
                    imeAction = ImeAction.Done,
                    keyboardType = KeyboardType.Decimal,
                    onAction = onAction,
                    onDone = focusManager::clearFocus,
                )
            }
            item(key = "form_end", contentType = "spacer") {
                Spacer(modifier = Modifier.height(spacing.xs))
            }
        }

        ReviewProductsBar(
            state = state,
            onReviewProducts = {
                state.blockingFields.firstOrNull()?.let { localFocusField = it }
                focusManager.clearFocus()
                onAction(Action.ReviewProducts)
            },
        )
    }

    state.expandedPageIndex
        ?.let(state.pages::getOrNull)
        ?.let { page ->
            ExpandedDocumentDialog(
                page = page,
                onDismiss = { onAction(Action.DismissExpandedPage) },
            )
        }

    state.selectedEvidence?.let { evidence ->
        EvidenceDialog(
            evidence = evidence,
            onDismiss = { onAction(Action.DismissEvidence) },
        )
    }
}

@Composable
private fun HeaderReviewWarnings(warnings: List<ReviewWarning>) {
    if (warnings.isEmpty()) return
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceHeaderReviewTestTags.WARNING_SUMMARY),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        warnings.forEach { warning ->
            val message = when (warning.code) {
                ReviewWarningCode.RUC_CHECKSUM_MISMATCH -> stringResource(
                    R.string.header_review_warning_ruc_checksum,
                )
                ReviewWarningCode.TOTAL_DIFFERENCE -> stringResource(
                    R.string.header_review_warning_total_difference,
                    warning.currency.orEmpty(),
                    warning.difference.orEmpty(),
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        stateDescription = message
                    },
                shape = MaterialTheme.shapes.medium,
                color = FacturaStockDesign.semanticColors.warningContainer,
                contentColor = FacturaStockDesign.semanticColors.onWarningContainer,
            ) {
                Row(
                    modifier = Modifier.padding(spacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        modifier = Modifier.size(spacing.icon),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun DocumentThumbnail(
    page: Page?,
    onExpand: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    if (page == null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                text = stringResource(R.string.header_review_no_document_image),
                modifier = Modifier.padding(spacing.md),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    val context = LocalContext.current
    val description = stringResource(
        R.string.header_review_expand_page_description,
        page.pageIndex + 1,
    )
    Card(
        onClick = onExpand,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = THUMBNAIL_HEIGHT)
            .testTag(InvoiceHeaderReviewTestTags.DOCUMENT_THUMBNAIL)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(THUMBNAIL_HEIGHT),
        ) {
            AsyncImage(
                model = sensitiveImageRequest(File(context.filesDir, page.relativePath)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing.xs)
                    .graphicsLayer { rotationZ = page.rotationDegrees.toFloat() },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(spacing.sm),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.header_review_expand_document),
                    modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun EditableField(
    field: Field,
    continueAttempted: Boolean,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: (Action) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    onNext: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val effectiveError = effectiveFieldError(field)
    val showError = effectiveError != null && (field.touched || continueAttempted)
    val errorText = effectiveError?.takeIf { showError }?.let { fieldErrorText(it) }
    val confidenceText = confidenceText(field)
    val spokenState = listOfNotNull(errorText, confidenceText).joinToString(separator = ". ")
    val warningColor = FacturaStockDesign.semanticColors.warning
    val fieldColors = if (field.isUncertain && !showError) {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = warningColor,
            unfocusedBorderColor = warningColor,
            focusedLabelColor = warningColor,
            unfocusedLabelColor = warningColor,
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = field.value,
            onValueChange = { value ->
                onAction(Action.FieldChanged(field.id, value))
            },
            label = { Text(stringResource(fieldLabelRes(field.id))) },
            singleLine = true,
            isError = showError,
            supportingText = errorText?.let { message ->
                { Text(message) }
            },
            trailingIcon = if (field.isUncertain) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = warningColor,
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onNext = { onNext() },
                onDone = { onDone() },
            ),
            colors = fieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    onAction(Action.FieldFocusChanged(field.id, focusState.isFocused))
                }
                .testTag(InvoiceHeaderReviewTestTags.field(field.id))
                .then(
                    if (spokenState.isNotBlank()) {
                        Modifier.semantics { stateDescription = spokenState }
                    } else {
                        Modifier
                    },
                ),
        )
        FieldReviewSupport(field = field, onAction = onAction)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentTypeField(
    field: Field,
    continueAttempted: Boolean,
    focusRequester: FocusRequester,
    onAction: (Action) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = PurchaseDocumentType.entries.firstOrNull { it.name == field.value }
    val shownValue = selected?.let { stringResource(documentTypeLabelRes(it)) }.orEmpty()
    val effectiveError = effectiveFieldError(field)
    val showError = effectiveError != null && (field.touched || continueAttempted)
    val errorText = effectiveError?.takeIf { showError }?.let { fieldErrorText(it) }
    val confidenceText = confidenceText(field)
    val spokenState = listOfNotNull(errorText, confidenceText).joinToString(separator = ". ")
    val warningColor = FacturaStockDesign.semanticColors.warning
    val fieldColors = if (field.isUncertain && !showError) {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = warningColor,
            unfocusedBorderColor = warningColor,
            focusedLabelColor = warningColor,
            unfocusedLabelColor = warningColor,
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = shownValue,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.header_review_document_type_label)) },
                isError = showError,
                supportingText = errorText?.let { message ->
                    { Text(message) }
                },
                leadingIcon = if (field.isUncertain) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = warningColor,
                        )
                    }
                } else {
                    null
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = fieldColors,
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        onAction(
                            Action.FieldFocusChanged(
                                field = FieldId.DOCUMENT_TYPE,
                                focused = focusState.isFocused,
                            ),
                        )
                    }
                    .testTag(InvoiceHeaderReviewTestTags.field(FieldId.DOCUMENT_TYPE))
                    .then(
                        if (spokenState.isNotBlank()) {
                            Modifier.semantics { stateDescription = spokenState }
                        } else {
                            Modifier
                        },
                    ),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                PurchaseDocumentType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(stringResource(documentTypeLabelRes(type))) },
                        onClick = {
                            expanded = false
                            onAction(Action.DocumentTypeSelected(type))
                        },
                    )
                }
            }
        }
        FieldReviewSupport(field = field, onAction = onAction)
    }
}

@Composable
private fun FieldReviewSupport(
    field: Field,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val confidenceText = confidenceText(field)
    if (confidenceText != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.xs)
                .testTag(InvoiceHeaderReviewTestTags.confidence(field.id))
                .semantics(mergeDescendants = true) {
                    stateDescription = confidenceText
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = FacturaStockDesign.semanticColors.warning,
                modifier = Modifier.size(spacing.iconSmall),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = confidenceText,
                color = FacturaStockDesign.semanticColors.warning,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (field.evidence != null) {
        TextButton(
            onClick = { onAction(Action.ShowEvidence(field.id)) },
            modifier = Modifier
                .heightIn(min = spacing.minimumTouchTarget)
                .testTag(InvoiceHeaderReviewTestTags.evidence(field.id)),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = null,
                modifier = Modifier.size(spacing.iconSmall),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(stringResource(R.string.header_review_view_ocr_text))
        }
    }
    if (field.canExplicitlyConfirm) {
        TextButton(
            onClick = { onAction(Action.FieldConfirmed(field.id)) },
            modifier = Modifier
                .heightIn(min = spacing.minimumTouchTarget)
                .testTag(InvoiceHeaderReviewTestTags.confirmation(field.id)),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                modifier = Modifier.size(spacing.iconSmall),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(stringResource(R.string.header_review_confirm_verified_value))
        }
    }
    if (field.isPersisting) {
        Text(
            text = stringResource(R.string.header_review_saving_field),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ReviewProductsBar(
    state: State,
    onReviewProducts: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val total = state.field(FieldId.TOTAL).value.ifBlank {
        stringResource(R.string.header_review_total_pending)
    }
    val currency = state.field(FieldId.CURRENCY).value

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = spacing.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        ) {
            if (state.continueAttempted && state.blockingFields.isNotEmpty()) {
                val firstBlocking = state.blockingFields.first()
                val blockMessage = when (state.blockingReason(firstBlocking)) {
                    AdvanceBlockReason.UNCONFIRMED_UNCERTAIN_VALUE -> stringResource(
                        R.string.header_review_unconfirmed_message,
                        stringResource(fieldLabelRes(firstBlocking)),
                    )
                    AdvanceBlockReason.MISSING_OR_INVALID,
                    null,
                    -> stringResource(
                        R.string.header_review_blocking_message,
                        stringResource(fieldLabelRes(firstBlocking)),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = spacing.sm)
                        .testTag(InvoiceHeaderReviewTestTags.BLOCKING_EXPLANATION)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        },
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(spacing.icon),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = blockMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                text = stringResource(R.string.header_review_total_summary_label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(
                    R.string.header_review_total_summary_value,
                    currency,
                    total,
                ).trim(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            FacturaStockPrimaryButton(
                text = if (state.isSaving) {
                    stringResource(R.string.header_review_saving_changes)
                } else {
                    stringResource(R.string.header_review_review_products)
                },
                onClick = onReviewProducts,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvoiceHeaderReviewTestTags.REVIEW_PRODUCTS),
                enabled = state.isReviewProductsActionEnabled,
            )
        }
    }
}

@Composable
private fun EvidenceDialog(
    evidence: Evidence,
    onDismiss: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val title = stringResource(R.string.header_review_evidence_title)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvoiceHeaderReviewTestTags.EVIDENCE_DIALOG)
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.lg),
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = stringResource(R.string.header_review_ocr_original_label),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = evidence.rawText,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        evidence.pageIndex?.let { pageIndex ->
                            Spacer(modifier = Modifier.height(spacing.md))
                            Text(
                                text = stringResource(
                                    R.string.header_review_evidence_page,
                                    pageIndex + 1,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (evidence.alternatives.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(spacing.md))
                            Text(
                                text = stringResource(R.string.header_review_alternatives_label),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            evidence.alternatives.forEach { alternative ->
                                Text(
                                    text = stringResource(
                                        R.string.header_review_alternative_value,
                                        alternative,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.lg))
                    FacturaStockPrimaryButton(
                        text = stringResource(R.string.header_review_close),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedDocumentDialog(
    page: Page,
    onDismiss: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val context = LocalContext.current
    val title = stringResource(
        R.string.header_review_expanded_page_title,
        page.pageIndex + 1,
    )
    var zoom by remember(page.imageId) { mutableFloatStateOf(MIN_ZOOM) }
    var pan by remember(page.imageId) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag(InvoiceHeaderReviewTestTags.EXPANDED_DOCUMENT)
                .semantics {
                    paneTitle = title
                    isTraversalGroup = true
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing.md),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.header_review_zoom_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(spacing.sm))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(page.imageId) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                val nextZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val maxX = (nextZoom - MIN_ZOOM) * size.width / 2f
                                val maxY = (nextZoom - MIN_ZOOM) * size.height / 2f
                                zoom = nextZoom
                                pan = if (nextZoom == MIN_ZOOM) {
                                    Offset.Zero
                                } else {
                                    Offset(
                                        x = (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                        y = (pan.y + panChange.y).coerceIn(-maxY, maxY),
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = sensitiveImageRequest(File(context.filesDir, page.relativePath)),
                        contentDescription = stringResource(
                            R.string.header_review_expanded_page_description,
                            page.pageIndex + 1,
                        ),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                                rotationZ = page.rotationDegrees.toFloat()
                            },
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FacturaStockSecondaryButton(
                        text = stringResource(R.string.header_review_zoom_out),
                        onClick = {
                            zoom = (zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
                            if (zoom == MIN_ZOOM) pan = Offset.Zero
                        },
                        modifier = Modifier.weight(1f),
                        enabled = zoom > MIN_ZOOM,
                    )
                    FacturaStockSecondaryButton(
                        text = stringResource(R.string.header_review_zoom_in),
                        onClick = {
                            zoom = (zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = zoom < MAX_ZOOM,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xs))
                FacturaStockPrimaryButton(
                    text = stringResource(R.string.header_review_close),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun confidenceText(field: Field): String? {
    if (!field.isUncertain) return null
    val confidence = when (field.confidence) {
        Confidence.LOW -> stringResource(R.string.header_review_low_confidence)
        Confidence.UNKNOWN -> stringResource(R.string.header_review_unknown_confidence)
        Confidence.MEDIUM,
        Confidence.HIGH,
        -> stringResource(R.string.header_review_manual_review_required)
    }
    return if (field.confirmedByUser) {
        stringResource(R.string.header_review_confirmed_confidence, confidence)
    } else {
        confidence
    }
}

@Composable
private fun fieldErrorText(error: FieldError): String = stringResource(
    when (error) {
        FieldError.REQUIRED -> R.string.header_review_error_required
        FieldError.INVALID_RUC -> R.string.header_review_error_ruc
        FieldError.INVALID_DOCUMENT_TYPE -> R.string.header_review_error_document_type
        FieldError.INVALID_SERIES -> R.string.header_review_error_series
        FieldError.INVALID_NUMBER -> R.string.header_review_error_number
        FieldError.INVALID_DATE -> R.string.header_review_error_date
        FieldError.INVALID_CURRENCY -> R.string.header_review_error_currency
        FieldError.INVALID_AMOUNT -> R.string.header_review_error_amount
        FieldError.NEGATIVE_AMOUNT -> R.string.header_review_error_negative_amount
    },
)

private fun effectiveFieldError(field: Field): FieldError? = field.error ?: when (field.id) {
    FieldId.RUC,
    FieldId.SERIES,
    FieldId.NUMBER,
    FieldId.ISSUE_DATE,
    FieldId.CURRENCY,
    FieldId.TOTAL,
    -> FieldError.REQUIRED.takeIf { field.value.isBlank() }

    FieldId.SUPPLIER,
    FieldId.DOCUMENT_TYPE,
    FieldId.SUBTOTAL,
    FieldId.IGV,
    FieldId.OTHER_CHARGES,
    -> null
}

@StringRes
private fun fieldLabelRes(field: FieldId): Int = when (field) {
    FieldId.RUC -> R.string.header_review_ruc_label
    FieldId.SUPPLIER -> R.string.header_review_supplier_label
    FieldId.DOCUMENT_TYPE -> R.string.header_review_document_type_label
    FieldId.SERIES -> R.string.header_review_series_label
    FieldId.NUMBER -> R.string.header_review_number_label
    FieldId.ISSUE_DATE -> R.string.header_review_issue_date_label
    FieldId.CURRENCY -> R.string.header_review_currency_label
    FieldId.SUBTOTAL -> R.string.header_review_subtotal_label
    FieldId.IGV -> R.string.header_review_igv_label
    FieldId.OTHER_CHARGES -> R.string.header_review_other_charges_label
    FieldId.TOTAL -> R.string.header_review_total_label
}

@StringRes
private fun documentTypeLabelRes(type: PurchaseDocumentType): Int = when (type) {
    PurchaseDocumentType.INVOICE -> R.string.header_review_document_type_invoice
    PurchaseDocumentType.SALES_RECEIPT -> R.string.header_review_document_type_sales_receipt
    PurchaseDocumentType.CREDIT_NOTE -> R.string.header_review_document_type_credit_note
    PurchaseDocumentType.DEBIT_NOTE -> R.string.header_review_document_type_debit_note
}

private fun fieldLazyIndex(field: FieldId): Int = when (field) {
    FieldId.RUC -> 2
    FieldId.SUPPLIER -> 3
    FieldId.DOCUMENT_TYPE -> 5
    FieldId.SERIES -> 6
    FieldId.NUMBER -> 7
    FieldId.ISSUE_DATE -> 8
    FieldId.CURRENCY -> 9
    FieldId.SUBTOTAL -> 11
    FieldId.IGV -> 12
    FieldId.OTHER_CHARGES -> 13
    FieldId.TOTAL -> 14
}

private val THUMBNAIL_HEIGHT = 176.dp
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val ZOOM_STEP = 0.5f
