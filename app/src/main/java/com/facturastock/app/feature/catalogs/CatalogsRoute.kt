package com.facturastock.app.feature.catalogs

import androidx.activity.compose.BackHandler

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.core.input.suppressScannerTrailingKeys
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.ProductSalePricePolicy
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
    onProductSaved: (CatalogsContract.Effect.ProductSaved) -> Unit = { onBack() },
    isSalesRegistration: Boolean = false,
    isManualRegistration: Boolean = false,
    isSpecialRegistration: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = isSalesRegistration || isManualRegistration || state.isSpecialEntryPending ||
        state.isManualEntryPending || state.manualEntryFailure != null ||
        (state.form as? Form.ProductForm)?.let { it.isSpecialRegistration || it.isManualRegistration } == true) {
        if (!state.isSaving) viewModel.onAction(Action.CloseForm)
    }
    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            CatalogsContract.Effect.Back -> onBack()
            is CatalogsContract.Effect.ProductSaved -> onProductSaved(effect)
        }
    }
    CatalogsScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        isManualRegistration = isManualRegistration,
        isSpecialRegistration = isSpecialRegistration,
    )
}

@Composable
fun CatalogsScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
    isManualRegistration: Boolean = false,
    isSpecialRegistration: Boolean = false,
) {
    if (isManualRegistration || state.isManualEntryPending || state.manualEntryFailure != null ||
        (state.form as? Form.ProductForm)?.isManualRegistration == true
    ) {
        ManualProductRegistrationScreen(state, onAction, modifier)
        return
    }
    // El producto por kilo se registra sobre un fondo negro: el catálogo no se ve detrás del
    // formulario, tampoco mientras se prepara ni durante la transición de salida.
    val specialBackdrop = isSpecialRegistration || state.isSpecialEntryPending ||
        (state.form as? Form.ProductForm)?.isSpecialRegistration == true
    if (specialBackdrop) {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim))
    } else Box(modifier = modifier.fillMaxSize()) {
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
        CatalogFormDialog(state = state, form = form, onAction = onAction, opaqueBackdrop = specialBackdrop)
    }
    if (state.isScannerEntryPending || state.isInventoryEntryPending || state.isSpecialEntryPending) {
        CatalogModal(
            title = stringResource(R.string.catalog_products),
            onDismiss = { onAction(Action.CloseForm) },
            testTag = CatalogsTestTags.FORM,
            opaqueBackdrop = specialBackdrop,
        ) {
            val failure = when {
                state.isSpecialEntryPending -> state.specialEntryFailure
                state.isInventoryEntryPending -> state.inventoryEntryFailure
                else -> state.scannerEntryFailure
            }
            if (failure == null) {
                LoadingState(message = stringResource(R.string.feature_loading_message))
            } else {
                Text(stringResource(failure.messageRes()))
                FacturaStockPrimaryButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(Action.Retry) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FacturaStockSecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = { onAction(Action.CloseForm) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    state.pendingStatusChange?.takeUnless { it.row is Row.ProductRow }?.let { pending ->
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
private fun ManualProductRegistrationScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val title = stringResource(R.string.manual_product_title)
    val form = (state.form as? Form.ProductForm)?.takeIf { it.isManualRegistration }
    val isPreparing = state.isManualEntryPending || state.manualEntryFailure != null || form == null
    val failure = state.manualEntryFailure ?: state.failure
    Column(
        modifier = modifier
            .fillMaxSize()
            .suppressScannerTrailingKeys()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md)
            .semantics {
                paneTitle = title
                isTraversalGroup = true
            }
            .testTag(CatalogsTestTags.FORM),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        ManualProductFormFields(
            state = state,
            form = form ?: Form.ProductForm(isManualRegistration = true),
            enabled = !isPreparing,
            onAction = onAction,
        )
        if (isPreparing && failure == null) {
            LoadingState(message = stringResource(R.string.manual_product_loading))
        }
        failure?.let {
            StatusCard(
                statusLabel = stringResource(R.string.catalog_form_error_status),
                title = stringResource(R.string.catalog_form_error_title),
                message = stringResource(it.messageRes()),
                tone = StatusTone.ERROR,
                iconRes = R.drawable.ic_warning,
            )
            if (isPreparing || failure == Failure.LOAD_FAILED || failure == Failure.NO_ACTIVE_BUSINESS) {
                FacturaStockPrimaryButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(Action.Retry) },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FacturaStockPrimaryButton(
            text = stringResource(if (state.isSaving) R.string.action_saving else R.string.action_save),
            onClick = { onAction(Action.SaveForm) },
            enabled = !isPreparing && !state.isSaving && form?.hasRequiredFields(state) == true &&
                failure != Failure.LOAD_FAILED && failure != Failure.NO_ACTIVE_BUSINESS,
            modifier = Modifier.fillMaxWidth().testTag(CatalogsTestTags.SAVE_FORM),
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
private fun ManualProductFormFields(
    state: State,
    form: Form.ProductForm,
    enabled: Boolean,
    onAction: (Action) -> Unit,
) {
    FormTextField(
        value = form.title,
        labelRes = R.string.manual_product_name,
        required = true,
        showRequiredError = state.failure == Failure.INVALID_FIELDS,
        enabled = enabled && !state.isSaving,
        modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_NAME),
    ) { onAction(Action.ProductNameChanged(it)) }
    ProductDecimalField(
        value = form.quantity,
        labelRes = R.string.manual_product_quantity,
        required = true,
        state = state,
        enabled = enabled,
        testTag = CatalogsTestTags.PRODUCT_QUANTITY,
        onValueChange = { onAction(Action.ProductQuantityChanged(it)) },
    )
    ProductDecimalField(
        value = form.purchasePrice,
        labelRes = R.string.manual_product_purchase_price,
        required = true,
        state = state,
        enabled = enabled,
        currency = (form.inventoryCurrency ?: state.currency).value,
        testTag = CatalogsTestTags.PRODUCT_PURCHASE_PRICE,
        onValueChange = { onAction(Action.ProductPurchasePriceChanged(it)) },
    )
    ProductDecimalField(
        value = form.salePrice,
        labelRes = R.string.manual_product_sale_price,
        required = true,
        state = state,
        enabled = enabled,
        currency = (form.saleCurrency ?: state.currency).value,
        testTag = CatalogsTestTags.PRODUCT_SALE_PRICE,
        onValueChange = { onAction(Action.ProductPriceChanged(it)) },
    )
}

@Composable
private fun CatalogTabs(selected: Section, onAction: (Action) -> Unit) {
    val sections = CatalogsContract.visibleSections
    val selectedIndex = sections.indexOf(selected).coerceAtLeast(0)
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = FacturaStockDesign.spacing.xs,
    ) {
        sections.forEach { section ->
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
        if (state.section != Section.PRODUCTS) {
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
                    if (row !is Row.ProductRow) {
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
        if (detail !is Detail.ProductDetail) {
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
        }
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
        if (detail !is Detail.ProductDetail) {
            FacturaStockSecondaryButton(
                text = stringResource(
                    if (detail.row.status == CatalogStatus.ACTIVE) R.string.catalog_action_archive
                    else R.string.catalog_action_restore,
                ),
                onClick = { onAction(Action.RequestStatusChange) },
                modifier = Modifier.fillMaxWidth().testTag(CatalogsTestTags.STATUS),
            )
        }
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
                    if (cost == null) {
                        stringResource(R.string.catalog_polish_average_cost_unavailable, currency.value)
                    } else {
                        stringResource(
                            R.string.catalog_product_average_cost_currency,
                            currency.value,
                            cost.amount.toCatalogAmountText(),
                        )
                    },
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
                        if (detail.positions.size > 1) {
                            stringResource(
                                R.string.catalog_product_position,
                                position.locationName,
                                position.quantity,
                                position.averageCost,
                            )
                        } else {
                            stringResource(
                                R.string.catalog_product_position_single,
                                position.quantity,
                                position.averageCost,
                            )
                        },
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
private fun CatalogFormDialog(
    state: State,
    form: Form,
    onAction: (Action) -> Unit,
    opaqueBackdrop: Boolean = false,
) {
    if (form is Form.ProductForm && form.isInventoryOrigin) {
        InventoryProductEditorDialog(state, form, onAction)
        return
    }
    CatalogModal(
        title = stringResource(form.titleRes()),
        onDismiss = { if (!state.isSaving) onAction(Action.CloseForm) },
        testTag = CatalogsTestTags.FORM,
        opaqueBackdrop = opaqueBackdrop,
    ) {
        Text(
            text = stringResource(form.titleRes()),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        when (form) {
            is Form.ProductForm -> ProductFormFields(state, form, onAction)
            is Form.SupplierForm -> SupplierFormFields(form, state.failure, onAction, enabled = !state.isSaving)
            is Form.UnitForm -> UnitFormFields(form, state.failure, onAction, enabled = !state.isSaving)
            is Form.LocationForm -> LocationFormFields(form, state.failure, onAction, enabled = !state.isSaving)
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
            enabled = !state.isSaving && form.hasRequiredFields(state),
            modifier = Modifier.fillMaxWidth().testTag(CatalogsTestTags.SAVE_FORM),
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
private fun InventoryProductEditorDialog(state: State, form: Form.ProductForm, onAction: (Action) -> Unit) {
    val spacing = FacturaStockDesign.spacing
    val title = stringResource(R.string.catalog_edit_product)
    Dialog(
        onDismissRequest = { if (!state.isSaving) onAction(Action.CloseForm) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .suppressScannerTrailingKeys()
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .imePadding()
                .padding(spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .fillMaxSize()
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    }
                    .testTag(CatalogsTestTags.FORM),
                shape = MaterialTheme.shapes.large,
                tonalElevation = spacing.xxs,
            ) {
                Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(
                        stringResource(R.string.inventory_stock_editor_scroll_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .testTag(InventoryProductStockEditorTags.FIELDS),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        ProductFormFields(state, form, onAction)
                    }
                    state.failure?.messageRes()?.let { messageRes ->
                        Text(
                            text = stringResource(messageRes),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                    }
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        FacturaStockSecondaryButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { onAction(Action.CloseForm) },
                            enabled = !state.isSaving,
                            modifier = Modifier.weight(1f),
                        )
                        FacturaStockPrimaryButton(
                            text = stringResource(if (state.isSaving) R.string.action_saving else R.string.action_save),
                            onClick = { onAction(Action.SaveForm) },
                            enabled = !state.isSaving && !state.isInventoryBalanceLoading &&
                                state.inventoryBalanceFailure == null && form.hasRequiredFields(state),
                            modifier = Modifier.weight(1f).testTag(CatalogsTestTags.SAVE_FORM),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductFormFields(state: State, form: Form.ProductForm, onAction: (Action) -> Unit) {
    if (form.isSpecialRegistration) {
        SpecialProductFormFields(state, form, onAction)
        return
    }
    if (form.isInventoryOrigin) {
        InventoryProductFormFields(state, form, onAction)
        return
    }
    FormTextField(
        value = form.barcode,
        hasInvalidValue = form.isInventoryOrigin && (form.isBarcodeInputTooLong || !BarcodeValue.isValidOptional(form.barcode)),
        labelRes = if (form.isScannerOrigin) R.string.catalog_scanned_barcode_label else R.string.catalog_barcode_label,
        readOnly = form.isScannerOrigin,
        enabled = !state.isSaving,
        modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_BARCODE),
    ) {
        onAction(Action.ProductBarcodeChanged(it))
    }
    if (form.isInventoryOrigin) {
        FormTextField(
            value = form.sku,
            hasInvalidValue = form.isSkuInputTooLong || form.sku.trim().length > 64,
            labelRes = R.string.catalog_sku_label,
            enabled = !state.isSaving,
            modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_SKU),
        ) {
            onAction(Action.ProductSkuChanged(it))
        }
    }
    if (form.isScannedRegistration) {
        Text(
            text = stringResource(R.string.catalog_scanned_product_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_name_label,
        required = true,
        showRequiredError = state.failure == Failure.INVALID_FIELDS,
        enabled = !state.isSaving,
        modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_NAME),
    ) {
        onAction(Action.ProductNameChanged(it))
    }
    if (!form.isInventoryOrigin) {
        ProductDecimalField(
            value = form.quantity,
            labelRes = when {
                form.isScannedRegistration -> R.string.catalog_registration_quantity_label
                form.isScannerOrigin -> R.string.catalog_product_total_quantity_label
                else -> R.string.catalog_product_quantity_label
            },
            required = form.isScannedRegistration,
            state = state,
            testTag = CatalogsTestTags.PRODUCT_QUANTITY,
            onValueChange = { onAction(Action.ProductQuantityChanged(it)) },
        )
    }
    if (form.isScannedRegistration) {
        ProductDecimalField(
            value = form.purchasePrice,
            labelRes = R.string.catalog_registration_purchase_price_label,
            required = true,
            state = state,
            testTag = CatalogsTestTags.PRODUCT_PURCHASE_PRICE,
            onValueChange = { onAction(Action.ProductPurchasePriceChanged(it)) },
        )
    }
    ProductDecimalField(
        value = form.salePrice,
        labelRes = if (form.isScannedRegistration) R.string.catalog_registration_sale_price_label else R.string.catalog_product_price_label,
        required = form.isScannedRegistration,
        state = state,
        testTag = CatalogsTestTags.PRODUCT_SALE_PRICE,
        onValueChange = { onAction(Action.ProductPriceChanged(it)) },
    )
}

@Composable
private fun SpecialProductFormFields(state: State, form: Form.ProductForm, onAction: (Action) -> Unit) {
    Text(stringResource(R.string.catalog_special_product_help), style = MaterialTheme.typography.bodySmall)
    FormTextField(
        value = form.title, labelRes = R.string.catalog_name_label,
        required = true, showRequiredError = state.failure == Failure.INVALID_FIELDS,
        enabled = !state.isSaving, modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_NAME),
    ) { onAction(Action.ProductNameChanged(it)) }
    ProductDecimalField(
        value = form.quantity, labelRes = R.string.catalog_special_product_quantity,
        required = true, state = state, testTag = CatalogsTestTags.PRODUCT_QUANTITY,
        onValueChange = { onAction(Action.ProductQuantityChanged(it)) },
    )
    ProductDecimalField(
        value = form.purchasePrice, labelRes = R.string.catalog_special_product_cost_label,
        required = true, state = state, currency = "${state.currency.value}/kg",
        testTag = CatalogsTestTags.PRODUCT_PURCHASE_PRICE,
        onValueChange = { onAction(Action.ProductPurchasePriceChanged(it)) },
    )
    ProductDecimalField(
        value = form.salePrice, labelRes = R.string.catalog_special_product_price_label,
        required = true, state = state, currency = "${state.currency.value}/kg",
        testTag = CatalogsTestTags.PRODUCT_SALE_PRICE,
        onValueChange = { onAction(Action.ProductPriceChanged(it)) },
    )
    // Unidad (kg) y almacén no se muestran: la unidad es fija y el almacén es el primero activo.
    if (state.locationOptions.none { it.locationId == form.locationId && it.status == com.facturastock.app.domain.model.CatalogStatus.ACTIVE }) {
        Text(stringResource(R.string.catalog_special_product_location_required), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun InventoryProductFormFields(state: State, form: Form.ProductForm, onAction: (Action) -> Unit) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_name_label,
        required = true,
        showRequiredError = state.failure == Failure.INVALID_FIELDS,
        enabled = !state.isSaving,
        modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_NAME),
    ) { onAction(Action.ProductNameChanged(it)) }

    InventoryBalanceSelector(state, form, onAction)
    val canEditBalance = state.isInventoryStockEditable && !state.isInventoryBalanceLoading &&
        state.inventoryBalanceFailure == null && form.locationId != null
    val originalPosition = form.inventorySnapshot?.positions?.firstOrNull { it.locationId == form.locationId }
    ProductDecimalField(
        value = form.quantity,
        labelRes = R.string.inventory_stock_editor_quantity,
        required = false,
        state = state,
        enabled = canEditBalance,
        parseDecimal = String::inventoryDecimalOrNull,
        testTag = CatalogsTestTags.PRODUCT_QUANTITY,
        unchangedValue = form.quantity.replace(',', '.').toBigDecimalOrNull()?.let {
            originalPosition?.quantityOnHand?.compareTo(it) == 0
        } == true,
        onValueChange = { onAction(Action.ProductQuantityChanged(it)) },
    )
    ProductDecimalField(
        value = form.purchasePrice,
        labelRes = R.string.inventory_stock_editor_purchase_price,
        required = false,
        state = state,
        enabled = canEditBalance,
        parseDecimal = { it.inventoryDecimalOrNull()?.takeIf(ExactDecimalPolicy::supportsValue) },
        currency = form.inventoryCurrency?.value,
        testTag = CatalogsTestTags.PRODUCT_PURCHASE_PRICE,
        errorMessage = if (canEditBalance && originalPosition?.averageUnitCost != null && form.purchasePrice.isBlank()) {
            stringResource(R.string.catalog_polish_purchase_price_required)
        } else null,
        unchangedValue = form.purchasePrice.replace(',', '.').toBigDecimalOrNull()?.let {
            originalPosition?.averageUnitCost?.compareTo(it) == 0
        } == true,
        onValueChange = { onAction(Action.ProductPurchasePriceChanged(it)) },
    )
    if (canEditBalance && originalPosition?.averageUnitCost == null && form.purchasePrice.isBlank()) {
        Text(
            text = stringResource(R.string.inventory_stock_editor_missing_cost_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val saleCurrency = form.saleCurrency ?: state.currency
    val salePrice = form.salePrice.productDecimalOrNull()?.let {
        runCatching { Money.fromMajor(it, saleCurrency) }.getOrNull()
    }
    val invalidSalePrice = form.salePrice.isNotBlank() && salePrice?.let(ProductSalePricePolicy::supports) != true
    ProductDecimalField(
        value = form.salePrice,
        labelRes = R.string.inventory_stock_editor_sale_price,
        required = false,
        state = state,
        currency = saleCurrency.value,
        testTag = CatalogsTestTags.PRODUCT_SALE_PRICE,
        errorMessage = if (invalidSalePrice) {
            stringResource(
                R.string.catalog_polish_sale_price_error,
                Money.ofMinor(ProductSalePricePolicy.MAX_MINOR_UNITS, saleCurrency).toMajor().toPlainString(),
                saleCurrency.value,
                saleCurrency.defaultFractionDigits,
            )
        } else null,
        onValueChange = { onAction(Action.ProductPriceChanged(it)) },
    )
    val unit = state.unitOptions.firstOrNull { it.unitId == form.unitId }
    Text(
        text = stringResource(
            R.string.inventory_stock_editor_unit,
            unit?.code ?: stringResource(R.string.inventory_stock_editor_unknown_unit),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(
            if (state.isInventoryStockEditable) R.string.inventory_stock_editor_adjustment_help
            else R.string.inventory_stock_editor_shared_help,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider()
    FormTextField(
        value = form.sku,
        hasInvalidValue = form.isSkuInputTooLong || form.sku.trim().length > 64,
        labelRes = R.string.catalog_sku_label,
        enabled = !state.isSaving,
        modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_SKU),
    ) { onAction(Action.ProductSkuChanged(it)) }
    FormTextField(
        value = form.barcode,
        hasInvalidValue = form.isBarcodeInputTooLong ||
            (form.barcode != form.original?.barcode.orEmpty() && !BarcodeValue.isValidOptional(form.barcode)),
        labelRes = R.string.catalog_barcode_label,
        enabled = !state.isSaving,
        modifier = Modifier.testTag(CatalogsTestTags.PRODUCT_BARCODE),
    ) { onAction(Action.ProductBarcodeChanged(it)) }
}

@Composable
private fun InventoryBalanceSelector(state: State, form: Form.ProductForm, onAction: (Action) -> Unit) {
    if (state.isInventoryBalanceLoading) {
        LoadingState(message = stringResource(R.string.inventory_stock_editor_loading))
    }
    state.inventoryBalanceFailure?.let {
        RecoverableError(
            title = stringResource(R.string.inventory_stock_editor_load_failed_title),
            message = stringResource(R.string.inventory_stock_editor_load_failed),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(Action.Retry) },
        )
    }
    val selected = state.inventoryBalances.firstOrNull { it.locationId == form.locationId }
    if (state.inventoryBalances.size > 1) {
        val quantities = state.inventoryBalances.map { it.quantity.replace(',', '.').toBigDecimalOrNull() }
        if (quantities.all { it != null }) {
            val total = quantities.fold(java.math.BigDecimal.ZERO) { sum, quantity -> sum + requireNotNull(quantity) }
            Text(
                text = stringResource(R.string.inventory_stock_editor_all_locations_total, total.stripTrailingZeros().toPlainString()),
                modifier = Modifier.testTag(InventoryProductStockEditorTags.TOTAL),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = stringResource(R.string.inventory_stock_editor_total_unknown),
                modifier = Modifier.testTag(InventoryProductStockEditorTags.TOTAL),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        var expanded by remember { mutableStateOf(false) }
        Box {
            FacturaStockSecondaryButton(
                text = selected?.locationName ?: stringResource(R.string.inventory_stock_editor_choose_location),
                onClick = { expanded = true },
                enabled = !state.isSaving && !state.isInventoryBalanceLoading && state.inventoryBalanceFailure == null,
                modifier = Modifier.fillMaxWidth().testTag(InventoryProductStockEditorTags.LOCATION),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.inventoryBalances.forEach { balance ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(balance.locationName)
                                Text(
                                    stringResource(
                                        R.string.inventory_stock_editor_location_balance,
                                        balance.quantity.ifBlank { stringResource(R.string.inventory_stock_editor_unknown_quantity) },
                                        balance.currency?.value.orEmpty(),
                                        balance.unitCost.ifBlank { stringResource(R.string.inventory_stock_editor_unknown_cost) },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onAction(Action.ProductLocationSelected(balance.locationId))
                        },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.inventory_stock_editor_location_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val locationName = selected?.locationName
            ?: state.locationOptions.firstOrNull { it.locationId == form.locationId }?.label
        locationName?.let {
            Text(stringResource(R.string.inventory_stock_editor_location, it), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProductDecimalField(
    value: String,
    @StringRes labelRes: Int,
    required: Boolean,
    state: State,
    testTag: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    currency: String? = null,
    unchangedValue: Boolean = false,
    parseDecimal: (String) -> java.math.BigDecimal? = String::productDecimalOrNull,
    errorMessage: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        enabled = !state.isSaving && enabled,
        suffix = currency?.let { code -> { Text(code) } },
        isError = errorMessage != null || (!unchangedValue && value.isNotBlank() && parseDecimal(value) == null) ||
            (required && value.isBlank() && state.failure == Failure.INVALID_FIELDS),
        supportingText = if (errorMessage != null) {
            { Text(errorMessage) }
        } else if (required) {
            { Text(stringResource(R.string.catalog_field_required)) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    )
}

@Composable
private fun SupplierFormFields(form: Form.SupplierForm, failure: Failure?, onAction: (Action) -> Unit, enabled: Boolean) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_legal_name_label,
        enabled = enabled,
        required = true,
        showRequiredError = failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.SupplierLegalNameChanged(it))
    }
    FormTextField(form.tradeName, R.string.catalog_trade_name_label, enabled = enabled) {
        onAction(Action.SupplierTradeNameChanged(it))
    }
    OutlinedTextField(
        value = form.ruc,
        enabled = enabled,
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
private fun UnitFormFields(form: Form.UnitForm, failure: Failure?, onAction: (Action) -> Unit, enabled: Boolean) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_name_label,
        enabled = enabled,
        required = true,
        showRequiredError = failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.UnitNameChanged(it))
    }
    FormTextField(
        value = form.code,
        labelRes = R.string.catalog_unit_code_label,
        enabled = enabled,
        required = true,
        showRequiredError = failure == Failure.INVALID_FIELDS,
    ) {
        onAction(Action.UnitCodeChanged(it))
    }
    FormTextField(form.symbol, R.string.catalog_unit_symbol_label, enabled = enabled) {
        onAction(Action.UnitSymbolChanged(it))
    }
}

@Composable
private fun LocationFormFields(form: Form.LocationForm, failure: Failure?, onAction: (Action) -> Unit, enabled: Boolean) {
    FormTextField(
        value = form.title,
        labelRes = R.string.catalog_name_label,
        enabled = enabled,
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
    hasInvalidValue: Boolean = false,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    val missingRequiredValue = required && value.isBlank()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        enabled = enabled,
        label = { Text(stringResource(labelRes)) },
        isError = hasInvalidValue || (missingRequiredValue && showRequiredError),
        supportingText = if (hasInvalidValue) {
            { Text(stringResource(R.string.catalog_error_invalid)) }
        } else if (required) {
            { Text(stringResource(R.string.catalog_field_required)) }
        } else {
            null
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun Form.hasRequiredFields(state: State): Boolean = when (this) {
    is Form.ProductForm -> hasValidProductFields(saleCurrency ?: state.currency) &&
        (!isSpecialRegistration || state.locationOptions.any {
            it.locationId == locationId && it.status == com.facturastock.app.domain.model.CatalogStatus.ACTIVE
        })
    is Form.SupplierForm -> title.isNotBlank()
    is Form.UnitForm -> title.isNotBlank() && code.isNotBlank()
    is Form.LocationForm -> title.isNotBlank()
}

@Suppress("UnusedPrivateMember", "unused")
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

@Suppress("UnusedPrivateMember", "unused")
@Composable
private fun LocationSelector(
    selected: LocationId?,
    options: List<CatalogsContract.LocationOption>,
    onSelected: (LocationId?) -> Unit,
    enabled: Boolean = true,
    allowNone: Boolean = true,
    @StringRes labelRes: Int = R.string.catalog_location_label,
) {
    var expanded by remember { mutableStateOf(false) }
    Selector(
        label = stringResource(labelRes),
        value = options.firstOrNull { it.locationId == selected }?.label
            ?: stringResource(R.string.catalog_option_none),
        onClick = { if (enabled) expanded = true },
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) DropdownMenuItem(
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
    opaqueBackdrop: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .suppressScannerTrailingKeys()
                .fillMaxSize()
                // Negro opaco en lugar del oscurecido translúcido: tapa también la barra superior.
                .then(if (opaqueBackdrop) Modifier.background(MaterialTheme.colorScheme.scrim) else Modifier)
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
    is Form.ProductForm -> when {
        isManualRegistration -> R.string.manual_product_title
        isSpecialRegistration -> R.string.catalog_special_product_title
        isEditing -> R.string.catalog_edit_product
        else -> R.string.catalog_create_product
    }
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
    Failure.STALE -> R.string.catalog_polish_stale
    Failure.INVALID_FIELDS -> R.string.catalog_error_invalid
    Failure.INVALID_RUC -> R.string.catalog_ruc_invalid
    Failure.DUPLICATE_RUC -> R.string.catalog_duplicate_ruc
    Failure.DUPLICATE_SKU -> R.string.catalog_duplicate_sku
    Failure.DUPLICATE_BARCODE -> R.string.catalog_duplicate_barcode
    Failure.DUPLICATE_CODE -> R.string.catalog_duplicate_code
    Failure.DUPLICATE_NAME -> R.string.catalog_duplicate_name
}

object InventoryProductStockEditorTags {
    const val FIELDS = "inventory_product_editor_fields"
    const val LOCATION = "inventory_product_editor_location"
    const val TOTAL = "inventory_product_editor_total"
}
