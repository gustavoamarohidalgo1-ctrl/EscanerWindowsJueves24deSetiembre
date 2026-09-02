package com.facturastock.app.feature.catalogs

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.feature.catalogs.CatalogsContract.Action
import com.facturastock.app.feature.catalogs.CatalogsContract.Detail
import com.facturastock.app.feature.catalogs.CatalogsContract.Failure
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import com.facturastock.app.feature.catalogs.CatalogsContract.Row
import com.facturastock.app.feature.catalogs.CatalogsContract.Section
import com.facturastock.app.feature.catalogs.CatalogsContract.State
import com.facturastock.app.feature.catalogs.CatalogsContract.StatusFilter
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun CatalogsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            CatalogsContract.Effect.Back -> onBack()
        }
    }
    CatalogsScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}

@Composable
fun CatalogsScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CatalogTabs(state.section, onAction)
            CatalogSearchAndFilters(
                state = state,
                enabled = state.failure != Failure.LOAD_FAILED &&
                    state.failure != Failure.NO_ACTIVE_BUSINESS,
                onAction = onAction,
            )
            CatalogFeedback(state, onAction)
            CatalogList(
                state = state,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
            FacturaStockPrimaryButton(
                text = stringResource(R.string.catalog_add),
                onClick = { onAction(Action.AddSelected) },
                enabled = state.failure != Failure.NO_ACTIVE_BUSINESS &&
                    state.failure != Failure.LOAD_FAILED && !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FacturaStockDesign.spacing.md)
                    .testTag(CatalogsTestTags.ADD),
            )
        }
    }

    state.detail?.let { detail ->
        CatalogDetailDialog(detail = detail, onAction = onAction)
    }
    state.form?.let { form ->
        CatalogFormDialog(state = state, form = form, onAction = onAction)
    }
    state.pendingStatusChange?.let { pending ->
        val archive = pending.target == CatalogStatus.ARCHIVED
        FacturaStockDialog(
            title = stringResource(
                if (archive) R.string.catalog_status_dialog_archive_title
                else R.string.catalog_status_dialog_restore_title,
            ),
            message = stringResource(
                if (archive) R.string.catalog_status_dialog_archive_message
                else R.string.catalog_status_dialog_restore_message,
            ),
            confirmLabel = stringResource(
                if (archive) R.string.catalog_action_archive else R.string.catalog_action_restore,
            ),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(Action.ConfirmStatusChange) },
            onDismiss = { onAction(Action.DismissStatusChange) },
        )
    }
    if (state.showRucChecksumWarning) {
        FacturaStockDialog(
            title = stringResource(R.string.catalog_ruc_checksum_title),
            message = stringResource(R.string.catalog_ruc_checksum_message),
            confirmLabel = stringResource(R.string.action_save),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(Action.AcceptRucChecksumAndSave) },
            onDismiss = { onAction(Action.DismissRucChecksumWarning) },
        )
    }
}

@Composable
private fun CatalogTabs(selected: Section, onAction: (Action) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = Section.entries.indexOf(selected),
        edgePadding = FacturaStockDesign.spacing.xs,
    ) {
        Section.entries.forEach { section ->
            Tab(
                selected = section == selected,
                onClick = { onAction(Action.SectionSelected(section)) },
                modifier = Modifier.testTag(CatalogsTestTags.tab(section)),
                text = { Text(stringResource(section.labelRes())) },
            )
        }
    }
}

@Composable
private fun CatalogSearchAndFilters(
    state: State,
    enabled: Boolean,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(Action.QueryChanged(it)) },
            label = { Text(stringResource(R.string.catalog_search_hint)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(CatalogsTestTags.SEARCH),
        )
        LazyRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            items(
                items = StatusFilter.entries,
                key = StatusFilter::name,
                contentType = { "status_filter" },
            ) { filter ->
                FilterChip(
                    selected = state.statusFilter == filter,
                    onClick = { onAction(Action.StatusFilterSelected(filter)) },
                    enabled = enabled,
                    label = { Text(stringResource(filter.labelRes())) },
                )
            }
        }
        Text(
            text = pluralStringResource(
                R.plurals.catalog_result_count,
                state.total,
                state.total,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun CatalogFeedback(state: State, onAction: (Action) -> Unit) {
    val spacing = FacturaStockDesign.spacing
    if (state.savedFeedback) {
        Text(
            text = stringResource(R.string.catalog_saved),
            color = FacturaStockDesign.semanticColors.success,
            modifier = Modifier
                .padding(horizontal = spacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    state.failure?.messageRes()?.let { messageRes ->
        if (state.failure == Failure.LOAD_FAILED || state.failure == Failure.NO_ACTIVE_BUSINESS) {
            val showingCachedRows = state.failure == Failure.LOAD_FAILED && state.rows.isNotEmpty()
            RecoverableError(
                title = stringResource(
                    if (showingCachedRows) {
                        R.string.feature_stale_error_title
                    } else {
                        R.string.feature_load_error_title
                    },
                ),
                message = stringResource(
                    if (showingCachedRows) {
                        R.string.feature_stale_error_message
                    } else {
                        messageRes
                    },
                ),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(Action.Retry) },
                modifier = Modifier.padding(horizontal = spacing.md),
            )
        } else if (state.form == null) {
            Text(
                text = stringResource(messageRes),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = spacing.md),
            )
        }
    }
}

@Composable
private fun CatalogList(state: State, onAction: (Action) -> Unit, modifier: Modifier = Modifier) {
    val spacing = FacturaStockDesign.spacing
    when {
        state.rows.isEmpty() &&
            (state.failure == Failure.LOAD_FAILED || state.failure == Failure.NO_ACTIVE_BUSINESS) ->
            Box(modifier = modifier.fillMaxSize())

        state.isLoading -> LoadingState(
            message = stringResource(R.string.feature_loading_message),
            modifier = modifier.fillMaxSize(),
        )
        state.rows.isEmpty() -> EmptyCatalog(
            section = state.section,
            query = state.query,
            statusFilter = state.statusFilter,
            onClearFilters = {
                if (state.query.isNotBlank()) onAction(Action.QueryChanged(""))
                if (state.statusFilter != StatusFilter.ALL) {
                    onAction(Action.StatusFilterSelected(StatusFilter.ALL))
                }
            },
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxWidth().testTag(CatalogsTestTags.LIST),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(
                items = state.rows,
                key = Row::stableId,
                contentType = { "catalog_row" },
            ) { row ->
                CatalogRowCard(
                    row = row,
                    onClick = { onAction(Action.RowSelected(row)) },
                )
            }
            if (state.hasMore) {
                item(key = "catalog_load_more", contentType = "load_more") {
                    FacturaStockSecondaryButton(
                        text = stringResource(R.string.catalog_load_more),
                        onClick = { onAction(Action.LoadMore) },
                        enabled = !state.isLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCatalog(
    section: Section,
    query: String,
    statusFilter: StatusFilter,
    onClearFilters: () -> Unit,
    modifier: Modifier,
) {
    val hasActiveFilters = query.isNotBlank() || statusFilter != StatusFilter.ALL
    val (title, message) = if (hasActiveFilters) {
        R.string.catalog_empty_search_title to R.string.catalog_empty_search_message
    } else {
        when (section) {
            Section.PRODUCTS -> R.string.products_empty_title to R.string.products_empty_message
            Section.SUPPLIERS -> R.string.catalog_empty_supplier_title to R.string.catalog_empty_supplier_message
            Section.UNITS -> R.string.catalog_empty_unit_title to R.string.catalog_empty_unit_message
            Section.LOCATIONS -> R.string.catalog_empty_location_title to R.string.catalog_empty_location_message
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(FacturaStockDesign.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(FacturaStockDesign.spacing.xs))
        Text(
            text = stringResource(message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasActiveFilters) {
            Spacer(Modifier.height(FacturaStockDesign.spacing.lg))
            FacturaStockPrimaryButton(
                text = stringResource(
                    if (statusFilter == StatusFilter.ALL) {
                        R.string.action_clear_search
                    } else {
                        R.string.action_clear_filters
                    },
                ),
                onClick = onClearFilters,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CatalogRowCard(row: Row, onClick: () -> Unit) {
    val spacing = FacturaStockDesign.spacing
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CatalogsTestTags.row(row.stableId)),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.padding(spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(row.status.labelRes()),
                        color = if (row.status == CatalogStatus.ACTIVE) {
                            FacturaStockDesign.semanticColors.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                row.subtitle()?.let { (res, value) ->
                    Spacer(Modifier.height(spacing.xxs))
                    Text(
                        text = stringResource(res, value),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CatalogDetailDialog(detail: Detail, onAction: (Action) -> Unit) {
    CatalogModal(
        title = stringResource(R.string.catalog_detail),
        onDismiss = { onAction(Action.CloseDetail) },
        testTag = CatalogsTestTags.DETAIL,
    ) {
        Text(
            text = stringResource(R.string.catalog_detail),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = detail.row.title, style = MaterialTheme.typography.titleLarge)
        StatusCard(
            statusLabel = stringResource(R.string.catalog_detail),
            title = stringResource(detail.row.status.labelRes()),
            message = stringResource(
                if (detail.row.status == CatalogStatus.ACTIVE) R.string.catalog_filter_active
                else R.string.catalog_filter_archived,
            ),
            tone = if (detail.row.status == CatalogStatus.ACTIVE) StatusTone.SUCCESS else StatusTone.NEUTRAL,
            iconRes = if (detail.row.status == CatalogStatus.ACTIVE) R.drawable.ic_check_circle else R.drawable.ic_info,
        )
        when (detail) {
            is Detail.ProductDetail -> ProductDetailContent(detail)
            is Detail.SupplierDetail -> SupplierDetailContent(detail)
            is Detail.UnitDetail -> UnitDetailContent(detail)
            is Detail.LocationDetail -> Unit
        }
        FacturaStockPrimaryButton(
            text = stringResource(R.string.catalog_action_edit),
            onClick = { onAction(Action.EditSelected) },
            modifier = Modifier.fillMaxWidth(),
        )
        FacturaStockSecondaryButton(
            text = stringResource(
                if (detail.row.status == CatalogStatus.ACTIVE) R.string.catalog_action_archive
                else R.string.catalog_action_restore,
            ),
            onClick = { onAction(Action.RequestStatusChange) },
            modifier = Modifier.fillMaxWidth().testTag(CatalogsTestTags.STATUS),
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.action_back),
            onClick = { onAction(Action.CloseDetail) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProductDetailContent(detail: Detail.ProductDetail) {
    val value = detail.row.value
    value.sku?.let { Text(stringResource(R.string.catalog_product_sku, it)) }
    value.barcode?.let { Text(stringResource(R.string.catalog_product_barcode, it)) }
    when {
        detail.isLoading -> LoadingState(message = stringResource(R.string.feature_loading_message))
        detail.loadFailed -> Text(
            text = stringResource(R.string.catalog_product_detail_error),
            color = MaterialTheme.colorScheme.error,
        )
        else -> detail.catalogDetail?.let { loaded ->
            SectionTitle(R.string.catalog_product_inventory_title)
            Text(
                stringResource(
                    R.string.catalog_product_unit_value,
                    loaded.unit.symbol ?: loaded.unit.code,
                ),
            )
            Text(
                stringResource(
                    R.string.catalog_product_total_stock,
                    loaded.inventory.totalQuantityOnHand.toPlainString(),
                    loaded.unit.symbol ?: loaded.unit.code,
                ),
            )
            loaded.inventory.averageUnitCost?.let { cost ->
                Text(stringResource(R.string.catalog_product_average_cost, cost.toCatalogCostText()))
            } ?: loaded.inventory.averageUnitCostsByCurrency.forEach { (currency, cost) ->
                Text(
                    stringResource(
                        R.string.catalog_product_average_cost_currency,
                        currency.value,
                        cost.amount.toCatalogAmountText(),
                    ),
                )
            }
            if (loaded.inventory.averageUnitCostsByCurrency.isEmpty()) {
                Text(stringResource(R.string.catalog_product_average_cost_unavailable))
            }
            if (detail.positions.isEmpty()) {
                Text(stringResource(R.string.catalog_product_no_stock))
            } else {
                detail.positions.forEach { position ->
                    Text(
                        stringResource(
                            R.string.catalog_product_position,
                            position.locationName,
                            position.quantity,
                            position.averageCost,
                        ),
                    )
                }
            }
            SectionTitle(R.string.catalog_product_aliases_title)
            if (loaded.aliases.isEmpty()) {
                Text(stringResource(R.string.catalog_product_no_aliases))
            } else {
                loaded.aliases.forEach { alias -> Text(alias.alias) }
            }
        }
    }
}

@Composable
private fun SupplierDetailContent(detail: Detail.SupplierDetail) {
    val supplier = detail.row.value
    supplier.tradeName?.let {
        Text(stringResource(R.string.catalog_supplier_trade_name, it))
    }
    supplier.ruc?.let { Text(stringResource(R.string.catalog_supplier_ruc, it)) }
    Text(
        text = stringResource(R.string.catalog_ruc_local_notice),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun UnitDetailContent(detail: Detail.UnitDetail) {
    Text(stringResource(R.string.catalog_unit_code, detail.row.value.code))
    detail.row.value.symbol?.let {
        Text(stringResource(R.string.catalog_unit_symbol, it))
    }
}

@Composable
private fun CatalogFormDialog(state: State, form: Form, onAction: (Action) -> Unit) {
    CatalogModal(
        title = stringResource(form.titleRes()),
        onDismiss = { onAction(Action.CloseForm) },
        testTag = CatalogsTestTags.FORM,
    ) {
        Text(
            text = stringResource(form.titleRes()),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        when (form) {
            is Form.ProductForm -> ProductFormFields(state, form, onAction)
            is Form.SupplierForm -> SupplierFormFields(form, state.failure, onAction)
            is Form.UnitForm -> UnitFormFields(form, state.failure, onAction)
            is Form.LocationForm -> LocationFormFields(form, state.failure, onAction)
        }
        state.failure?.messageRes()?.let { messageRes ->
            StatusCard(
                statusLabel = stringResource(R.string.catalog_form_error_status),
                title = stringResource(R.string.catalog_form_error_title),
                message = stringResource(messageRes),
                tone = StatusTone.ERROR,
                iconRes = R.drawable.ic_warning,
            )
        }
        FacturaStockPrimaryButton(
            text = stringResource(
                if (state.isSaving) R.string.action_saving else R.string.action_save,
            ),
            onClick = { onAction(Action.SaveForm) },
            enabled = !state.isSaving && form.hasRequiredFields(),
            modifier = Modifier.fillMaxWidth(),
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.action_cancel),
            onClick = { onAction(Action.CloseForm) },
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProductFormFields(state: State, form: Form.ProductForm, onAction: (Action) -> Unit) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_name_label,
        required = true,
        showRequiredError = state.failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.ProductNameChanged(it))
    }
    FormTextField(form.sku, R.string.catalog_sku_label) { onAction(Action.ProductSkuChanged(it)) }
    FormTextField(form.barcode, R.string.catalog_barcode_label) {
        onAction(Action.ProductBarcodeChanged(it))
    }
    UnitSelector(
        labelRes = R.string.catalog_unit_label,
        selected = form.unitId,
        options = state.unitOptions.filter { it.status == CatalogStatus.ACTIVE || it.unitId == form.unitId },
        allowNone = false,
        onSelected = { it?.let { id -> onAction(Action.ProductUnitSelected(id)) } },
    )
    if (form.unitId == null) {
        Text(
            text = stringResource(R.string.catalog_unit_required),
            color = if (state.failure == Failure.INVALID_FIELDS) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    LocationSelector(
        selected = form.locationId,
        options = state.locationOptions.filter {
            it.status == CatalogStatus.ACTIVE || it.locationId == form.locationId
        },
        onSelected = { onAction(Action.ProductLocationSelected(it)) },
    )
    UnitSelector(
        labelRes = R.string.catalog_purchase_unit_label,
        selected = form.purchaseUnitId,
        options = state.unitOptions.filter {
            it.status == CatalogStatus.ACTIVE || it.unitId == form.purchaseUnitId
        },
        allowNone = true,
        onSelected = { onAction(Action.ProductPurchaseUnitSelected(it)) },
    )
    if (form.purchaseUnitId != null) {
        val validPurchaseFactor = form.purchaseFactor.toBigDecimalOrNull()?.signum() == 1
        OutlinedTextField(
            value = form.purchaseFactor,
            onValueChange = { value ->
                onAction(
                    Action.ProductPurchaseFactorChanged(
                        value.filter { it.isDigit() || it == '.' },
                    ),
                )
            },
            label = { Text(stringResource(R.string.catalog_purchase_factor_label)) },
            supportingText = {
                Text(
                    stringResource(
                        when {
                            form.purchaseFactor.isBlank() ->
                                R.string.catalog_purchase_factor_required
                            !validPurchaseFactor -> R.string.catalog_purchase_factor_error
                            else -> R.string.catalog_purchase_factor_help
                        },
                    ),
                )
            },
            isError = !validPurchaseFactor,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SupplierFormFields(form: Form.SupplierForm, failure: Failure?, onAction: (Action) -> Unit) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_legal_name_label,
        required = true,
        showRequiredError = failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.SupplierLegalNameChanged(it))
    }
    FormTextField(form.tradeName, R.string.catalog_trade_name_label) {
        onAction(Action.SupplierTradeNameChanged(it))
    }
    OutlinedTextField(
        value = form.ruc,
        onValueChange = { onAction(Action.SupplierRucChanged(it)) },
        label = { Text(stringResource(R.string.catalog_ruc_label)) },
        supportingText = {
            Text(
                stringResource(
                    if (failure == Failure.INVALID_RUC) R.string.catalog_ruc_invalid
                    else R.string.catalog_ruc_local_notice,
                ),
            )
        },
        isError = failure == Failure.INVALID_RUC ||
            (form.ruc.isNotEmpty() && !RucValidator.isWellFormed(form.ruc)),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UnitFormFields(form: Form.UnitForm, failure: Failure?, onAction: (Action) -> Unit) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_name_label,
        required = true,
        showRequiredError = failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.UnitNameChanged(it))
    }
    FormTextField(
        value = form.code,
        labelRes = R.string.catalog_unit_code_label,
        required = true,
        showRequiredError = failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.UnitCodeChanged(it))
    }
    FormTextField(form.symbol, R.string.catalog_unit_symbol_label) {
        onAction(Action.UnitSymbolChanged(it))
    }
}

@Composable
private fun LocationFormFields(form: Form.LocationForm, failure: Failure?, onAction: (Action) -> Unit) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_name_label,
        required = true,
        showRequiredError = failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.LocationNameChanged(it))
    }
}

@Composable
private fun FormTextField(
    value: String,
    @StringRes labelRes: Int,
    required: Boolean = false,
    showRequiredError: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val missingRequiredValue = required && value.isBlank()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        isError = missingRequiredValue && showRequiredError,
        supportingText = if (required) {
            { Text(stringResource(R.string.catalog_field_required)) }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Form.hasRequiredFields(): Boolean = when (this) {
    is Form.ProductForm -> title.isNotBlank() && unitId != null &&
        (purchaseUnitId == null || purchaseFactor.toBigDecimalOrNull()?.signum() == 1)
    is Form.SupplierForm -> title.isNotBlank()
    is Form.UnitForm -> title.isNotBlank() && code.isNotBlank()
    is Form.LocationForm -> title.isNotBlank()
}

@Composable
private fun UnitSelector(
    @StringRes labelRes: Int,
    selected: UnitId?,
    options: List<CatalogsContract.UnitOption>,
    allowNone: Boolean,
    onSelected: (UnitId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val option = options.firstOrNull { it.unitId == selected }
    Selector(
        label = stringResource(labelRes),
        value = option?.let { stringResource(R.string.catalog_unit_option, it.code, it.name) }
            ?: stringResource(if (allowNone) R.string.catalog_option_none else R.string.catalog_select_option),
        onClick = { expanded = true },
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.catalog_option_none)) },
                    onClick = { expanded = false; onSelected(null) },
                )
            }
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.catalog_unit_option, item.code, item.name)) },
                    onClick = { expanded = false; onSelected(item.unitId) },
                )
            }
        }
    }
}

@Composable
private fun LocationSelector(
    selected: LocationId?,
    options: List<CatalogsContract.LocationOption>,
    onSelected: (LocationId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Selector(
        label = stringResource(R.string.catalog_location_label),
        value = options.firstOrNull { it.locationId == selected }?.label
            ?: stringResource(R.string.catalog_option_none),
        onClick = { expanded = true },
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.catalog_option_none)) },
                onClick = { expanded = false; onSelected(null) },
            )
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = { expanded = false; onSelected(item.locationId) },
                )
            }
        }
    }
}

@Composable
private fun Selector(label: String, value: String, onClick: () -> Unit, menu: @Composable () -> Unit) {
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FacturaStockDesign.spacing.minimumTouchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = FacturaStockDesign.spacing.xs),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
        }
        menu()
    }
}

@Composable
private fun CatalogModal(
    title: String,
    onDismiss: () -> Unit,
    testTag: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .imePadding()
                .padding(FacturaStockDesign.spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = FacturaStockDesign.spacing.dialogMaxWidth)
                    .fillMaxWidth()
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    }
                    .testTag(testTag),
                shape = MaterialTheme.shapes.large,
                tonalElevation = FacturaStockDesign.spacing.xxs,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(FacturaStockDesign.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@StringRes
private fun Section.labelRes(): Int = when (this) {
    Section.PRODUCTS -> R.string.catalog_products
    Section.SUPPLIERS -> R.string.catalog_suppliers
    Section.UNITS -> R.string.catalog_units
    Section.LOCATIONS -> R.string.catalog_locations
}

@StringRes
private fun StatusFilter.labelRes(): Int = when (this) {
    StatusFilter.ALL -> R.string.catalog_filter_all
    StatusFilter.ACTIVE -> R.string.catalog_filter_active
    StatusFilter.ARCHIVED -> R.string.catalog_filter_archived
}

@StringRes
private fun CatalogStatus.labelRes(): Int = when (this) {
    CatalogStatus.ACTIVE -> R.string.catalog_status_active
    CatalogStatus.ARCHIVED -> R.string.catalog_status_archived
}

private fun Row.subtitle(): Pair<Int, String>? = when (this) {
    is Row.ProductRow -> value.sku?.let { R.string.catalog_product_sku to it }
        ?: value.barcode?.let { R.string.catalog_product_barcode to it }
    is Row.SupplierRow -> value.ruc?.let { R.string.catalog_supplier_ruc to it }
        ?: value.tradeName?.let { R.string.catalog_supplier_trade_name to it }
    is Row.UnitRow -> R.string.catalog_unit_code to value.code
    is Row.LocationRow -> null
}

@StringRes
private fun Form.titleRes(): Int = when (this) {
    is Form.ProductForm -> if (isEditing) R.string.catalog_edit_product else R.string.catalog_create_product
    is Form.SupplierForm -> if (isEditing) R.string.catalog_edit_supplier else R.string.catalog_create_supplier
    is Form.UnitForm -> if (isEditing) R.string.catalog_edit_unit else R.string.catalog_create_unit
    is Form.LocationForm -> if (isEditing) R.string.catalog_edit_location else R.string.catalog_create_location
}

@StringRes
private fun Failure.messageRes(): Int = when (this) {
    Failure.NO_ACTIVE_BUSINESS -> R.string.catalog_no_business
    Failure.LOAD_FAILED -> R.string.feature_load_error_message
    Failure.SAVE_FAILED -> R.string.catalog_error_save
    Failure.NOT_FOUND -> R.string.catalog_error_not_found
    Failure.STALE -> R.string.catalog_error_stale
    Failure.INVALID_FIELDS -> R.string.catalog_error_invalid
    Failure.INVALID_RUC -> R.string.catalog_ruc_invalid
    Failure.DUPLICATE_RUC -> R.string.catalog_duplicate_ruc
    Failure.DUPLICATE_SKU -> R.string.catalog_duplicate_sku
    Failure.DUPLICATE_BARCODE -> R.string.catalog_duplicate_barcode
    Failure.DUPLICATE_CODE -> R.string.catalog_duplicate_code
    Failure.DUPLICATE_NAME -> R.string.catalog_duplicate_name
}
