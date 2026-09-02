package com.facturastock.app.feature.catalogs

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.usecase.ArchiveInventoryLocationCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveProductCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveSupplierCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveUnitCatalogUseCase
import com.facturastock.app.domain.usecase.LoadProductCatalogDetailUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.RestoreInventoryLocationCatalogUseCase
import com.facturastock.app.domain.usecase.RestoreProductCatalogUseCase
import com.facturastock.app.domain.usecase.RestoreSupplierCatalogUseCase
import com.facturastock.app.domain.usecase.RestoreUnitCatalogUseCase
import com.facturastock.app.domain.usecase.SaveInventoryLocationCatalogUseCase
import com.facturastock.app.domain.usecase.SaveProductCatalogUseCase
import com.facturastock.app.domain.usecase.SaveSupplierCatalogUseCase
import com.facturastock.app.domain.usecase.SaveUnitCatalogUseCase
import com.facturastock.app.feature.catalogs.CatalogsContract.Action
import com.facturastock.app.feature.catalogs.CatalogsContract.Detail
import com.facturastock.app.feature.catalogs.CatalogsContract.Failure
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import com.facturastock.app.feature.catalogs.CatalogsContract.Row
import com.facturastock.app.feature.catalogs.CatalogsContract.Section
import com.facturastock.app.feature.catalogs.CatalogsContract.State
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Catálogos completamente locales. La búsqueda siempre cruza la frontera paginada de Room:
 * nunca materializa los 5.000 productos para filtrarlos en Compose. Los cambios de consulta se
 * cancelan con `collectLatest` y esperan 250 ms para no saturar SQLite mientras se escribe.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class CatalogsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeAppConfiguration: ObserveAppConfigurationUseCase,
    private val products: ProductRepository,
    private val suppliers: SupplierRepository,
    private val units: UnitRepository,
    private val locations: InventoryLocationRepository,
    aliases: SupplierProductAliasRepository,
    productInventory: ProductInventoryRepository,
    private val uuidGenerator: UuidGenerator,
    private val clock: AppClock,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<State, Action, CatalogsContract.Effect>(
    initialState = restoredCatalogsState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private data class SearchRequest(
        val businessId: BusinessId,
        val section: Section,
        val query: String,
        val status: CatalogStatus?,
        val version: Long,
    )

    private val saveSupplier = SaveSupplierCatalogUseCase(suppliers)
    private val saveProduct = SaveProductCatalogUseCase(products, units, locations)
    private val saveUnit = SaveUnitCatalogUseCase(units)
    private val saveLocation = SaveInventoryLocationCatalogUseCase(locations)
    private val archiveSupplier = ArchiveSupplierCatalogUseCase(suppliers)
    private val restoreSupplier = RestoreSupplierCatalogUseCase(suppliers)
    private val archiveProduct = ArchiveProductCatalogUseCase(products)
    private val restoreProduct = RestoreProductCatalogUseCase(products)
    private val archiveUnit = ArchiveUnitCatalogUseCase(units)
    private val restoreUnit = RestoreUnitCatalogUseCase(units)
    private val archiveLocation = ArchiveInventoryLocationCatalogUseCase(locations)
    private val restoreLocation = RestoreInventoryLocationCatalogUseCase(locations)
    private val loadProductDetail = LoadProductCatalogDetailUseCase(
        productRepository = products,
        unitRepository = units,
        supplierProductAliasRepository = aliases,
        productInventoryRepository = productInventory,
    )
    private val searchRequests = MutableStateFlow<SearchRequest?>(null)
    private var activeBusinessId: BusinessId? = null
    private var loadMoreJob: Job? = null
    private var searchVersion: Long = 0L

    init {
        executeMain {
            searchRequests
                .filterNotNull()
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .collectLatest(::runFirstPageSearch)
        }
        executeMain {
            observeAppConfiguration().collectLatest { configuration ->
                val nextBusinessId = configuration.activeBusinessId
                if (nextBusinessId == null) {
                    loadMoreJob?.cancel()
                    searchRequests.value = null
                    activeBusinessId = null
                    updateState {
                        copy(
                            isLoading = false,
                            isLoadingMore = false,
                            isSaving = false,
                            rows = emptyList(),
                            total = 0,
                            hasMore = false,
                            failure = Failure.NO_ACTIVE_BUSINESS,
                            detail = null,
                            form = null,
                            unitOptions = emptyList(),
                            locationOptions = emptyList(),
                            pendingStatusChange = null,
                            showRucChecksumWarning = false,
                        )
                    }
                } else if (nextBusinessId != activeBusinessId) {
                    loadMoreJob?.cancel()
                    searchRequests.value = null
                    activeBusinessId = nextBusinessId
                    updateState {
                        copy(
                            rows = emptyList(),
                            total = 0,
                            hasMore = false,
                            isLoading = true,
                            isLoadingMore = false,
                            isSaving = false,
                            failure = null,
                            detail = null,
                            form = null,
                            unitOptions = emptyList(),
                            locationOptions = emptyList(),
                            pendingStatusChange = null,
                            showRucChecksumWarning = false,
                        )
                    }
                    requestFirstPage()
                    loadOptions(nextBusinessId)
                }
            }
        }
    }

    override fun onAction(action: Action) {
        when (action) {
            is Action.SectionSelected -> changeSearch(section = action.section)
            is Action.QueryChanged -> changeSearch(query = action.value.take(MAX_QUERY_LENGTH))
            is Action.StatusFilterSelected -> changeSearch(statusFilter = action.filter)
            Action.LoadMore -> loadMore()
            Action.Retry -> executeMain {
                updateState { copy(failure = null, isLoading = true) }
                requestFirstPage()
            }

            Action.AddSelected -> openCreateForm()
            is Action.RowSelected -> openDetail(action.row)
            Action.CloseDetail -> executeMain { updateState { copy(detail = null, failure = null) } }
            Action.EditSelected -> openEditForm()
            Action.CloseForm -> executeMain {
                updateState {
                    copy(
                        form = null,
                        failure = null,
                        showRucChecksumWarning = false,
                    )
                }
            }

            Action.SaveForm -> saveForm(checksumAccepted = false)
            Action.AcceptRucChecksumAndSave -> saveForm(checksumAccepted = true)
            Action.DismissRucChecksumWarning -> executeMain {
                updateState { copy(showRucChecksumWarning = false) }
            }
            is Action.ProductNameChanged -> updateProductForm { copy(title = action.value) }
            is Action.ProductSkuChanged -> updateProductForm { copy(sku = action.value.take(64)) }
            is Action.ProductBarcodeChanged -> updateProductForm {
                // No truncar: guardar otro identificador distinto al pegado sería una asociación
                // silenciosa. El contrato BarcodeValue lo mostrará inválido al enviar.
                copy(barcode = action.value)
            }
            is Action.ProductUnitSelected -> updateProductForm { copy(unitId = action.unitId) }
            is Action.ProductLocationSelected -> updateProductForm {
                copy(locationId = action.locationId)
            }
            is Action.ProductPurchaseUnitSelected -> updateProductForm {
                copy(
                    purchaseUnitId = action.unitId,
                    purchaseFactor = if (action.unitId == null) "" else purchaseFactor,
                )
            }
            is Action.ProductPurchaseFactorChanged -> updateProductForm {
                copy(purchaseFactor = action.value.take(MAX_DECIMAL_LENGTH))
            }
            is Action.SupplierLegalNameChanged -> updateSupplierForm {
                copy(title = action.value)
            }
            is Action.SupplierTradeNameChanged -> updateSupplierForm {
                copy(tradeName = action.value)
            }
            is Action.SupplierRucChanged -> updateSupplierForm {
                copy(
                    ruc = action.value.filter { it in '0'..'9' }.take(11),
                    checksumAccepted = false,
                )
            }
            is Action.UnitNameChanged -> updateUnitForm { copy(title = action.value) }
            is Action.UnitCodeChanged -> updateUnitForm {
                copy(
                    code = action.value
                        .filter(Char::isLetterOrDigit)
                        .uppercase(Locale.ROOT)
                        .take(16),
                )
            }
            is Action.UnitSymbolChanged -> updateUnitForm { copy(symbol = action.value.take(16)) }
            is Action.LocationNameChanged -> updateLocationForm { copy(title = action.value) }
            Action.RequestStatusChange -> requestStatusChange()
            Action.ConfirmStatusChange -> confirmStatusChange()
            Action.DismissStatusChange -> executeMain {
                updateState { copy(pendingStatusChange = null) }
            }
        }
    }

    private fun changeSearch(
        section: Section? = null,
        query: String? = null,
        statusFilter: CatalogsContract.StatusFilter? = null,
    ) {
        executeMain {
            loadMoreJob?.cancel()
            updateState {
                copy(
                    section = section ?: this.section,
                    query = query ?: this.query,
                    statusFilter = statusFilter ?: this.statusFilter,
                    rows = emptyList(),
                    total = 0,
                    hasMore = false,
                    isLoading = true,
                    isLoadingMore = false,
                    failure = null,
                    savedFeedback = false,
                    detail = null,
                    form = null,
                )
            }
            savedStateHandle[SECTION_KEY] = uiState.value.section.name
            savedStateHandle[QUERY_KEY] = uiState.value.query
            savedStateHandle[STATUS_KEY] = uiState.value.statusFilter.name
            requestFirstPage()
        }
    }

    private fun requestFirstPage() {
        val businessId = activeBusinessId ?: return
        val state = uiState.value
        searchRequests.value = SearchRequest(
            businessId = businessId,
            section = state.section,
            query = state.query,
            status = state.statusFilter.status,
            version = ++searchVersion,
        )
    }

    private suspend fun runFirstPageSearch(request: SearchRequest) {
        try {
            observeSearch(request, offset = 0).collectLatest { page ->
                if (request != searchRequests.value || request.businessId != activeBusinessId) {
                    return@collectLatest
                }
                updateState {
                    copy(
                        rows = page.items,
                        total = page.total,
                        hasMore = page.hasMore,
                        isLoading = false,
                        isLoadingMore = false,
                        failure = null,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (request != searchRequests.value || request.businessId != activeBusinessId) return
            updateState {
                copy(isLoading = false, isLoadingMore = false, failure = Failure.LOAD_FAILED)
            }
        }
    }

    private fun loadMore() {
        val state = uiState.value
        val businessId = activeBusinessId ?: return
        if (!state.hasMore || state.isLoading || state.isLoadingMore || loadMoreJob?.isActive == true) {
            return
        }
        val request = SearchRequest(
            businessId = businessId,
            section = state.section,
            query = state.query,
            status = state.statusFilter.status,
            version = searchRequests.value?.version ?: searchVersion,
        )
        loadMoreJob = executeIo(
            before = { updateState { copy(isLoadingMore = true, failure = null) } },
            operation = { search(request, offset = state.rows.size) },
            onSuccess = { page ->
                loadMoreJob = null
                if (request != searchRequests.value || request.businessId != activeBusinessId) {
                    return@executeIo
                }
                updateState {
                    copy(
                        rows = rows + page.items,
                        total = page.total,
                        hasMore = page.hasMore,
                        isLoadingMore = false,
                    )
                }
            },
            onFailure = {
                loadMoreJob = null
                if (request != searchRequests.value || request.businessId != activeBusinessId) {
                    return@executeIo
                }
                updateState { copy(isLoadingMore = false, failure = Failure.LOAD_FAILED) }
            },
        )
    }

    private suspend fun search(request: SearchRequest, offset: Int): CatalogPage<Row> {
        val search = CatalogSearch(
            query = request.query,
            status = request.status,
            offset = offset,
            limit = PAGE_SIZE,
        )
        return when (request.section) {
            Section.PRODUCTS -> products.search(request.businessId, search).map(Row::ProductRow)
            Section.SUPPLIERS -> suppliers.search(request.businessId, search).map(Row::SupplierRow)
            Section.UNITS -> units.search(request.businessId, search).map(Row::UnitRow)
            Section.LOCATIONS -> locations.search(request.businessId, search).map(Row::LocationRow)
        }
    }

    /** La página visible se invalida desde Room; ninguna respuesta remota alimenta la UI. */
    private fun observeSearch(request: SearchRequest, offset: Int): Flow<CatalogPage<Row>> {
        val search = CatalogSearch(
            query = request.query,
            status = request.status,
            offset = offset,
            limit = PAGE_SIZE,
        )
        return when (request.section) {
            Section.PRODUCTS -> products.observeSearch(request.businessId, search)
                .mapPage(Row::ProductRow)
            Section.SUPPLIERS -> suppliers.observeSearch(request.businessId, search)
                .mapPage(Row::SupplierRow)
            Section.UNITS -> units.observeSearch(request.businessId, search)
                .mapPage(Row::UnitRow)
            Section.LOCATIONS -> locations.observeSearch(request.businessId, search)
                .mapPage(Row::LocationRow)
        }
    }

    private fun openCreateForm() {
        val businessId = activeBusinessId ?: return
        executeMain {
            val form = when (uiState.value.section) {
                Section.PRODUCTS -> Form.ProductForm()
                Section.SUPPLIERS -> Form.SupplierForm()
                Section.UNITS -> Form.UnitForm()
                Section.LOCATIONS -> Form.LocationForm()
            }
            updateState {
                copy(form = form, detail = null, failure = null, savedFeedback = false)
            }
            if (form is Form.ProductForm) loadOptions(businessId)
        }
    }

    private fun openEditForm() {
        executeMain {
            val row = uiState.value.detail?.row ?: return@executeMain
            val form = when (row) {
                is Row.ProductRow -> row.value.let { product ->
                    Form.ProductForm(
                        productId = product.productId,
                        title = product.name,
                        sku = product.sku.orEmpty(),
                        barcode = product.barcode.orEmpty(),
                        unitId = product.unitId,
                        locationId = product.locationId,
                        purchaseUnitId = product.purchaseUnitId,
                        purchaseFactor = product.purchaseFactor?.toPlainString().orEmpty(),
                        original = product,
                    )
                }
                is Row.SupplierRow -> row.value.let { supplier ->
                    Form.SupplierForm(
                        supplierId = supplier.supplierId,
                        title = supplier.legalName,
                        tradeName = supplier.tradeName.orEmpty(),
                        ruc = supplier.ruc.orEmpty(),
                        original = supplier,
                    )
                }
                is Row.UnitRow -> row.value.let { unit ->
                    Form.UnitForm(
                        unitId = unit.unitId,
                        title = unit.name,
                        code = unit.code,
                        symbol = unit.symbol.orEmpty(),
                        original = unit,
                    )
                }
                is Row.LocationRow -> row.value.let { location ->
                    Form.LocationForm(
                        locationId = location.locationId,
                        title = location.name,
                        original = location,
                    )
                }
            }
            updateState {
                copy(form = form, detail = null, failure = null, savedFeedback = false)
            }
        }
    }

    private fun openDetail(row: Row) {
        when (row) {
            is Row.ProductRow -> {
                executeMain {
                    updateState {
                        copy(
                            detail = Detail.ProductDetail(row),
                            failure = null,
                            savedFeedback = false,
                        )
                    }
                }
                executeIo(
                    operation = {
                        val detail = loadProductDetail(row.value.productId)
                        val positions = detail?.inventory?.positions.orEmpty().map { position ->
                            val locationName = locations.findById(position.locationId)?.name.orEmpty()
                            CatalogsContract.ProductPosition(
                                locationName = locationName,
                                quantity = position.quantityOnHand.toPlainString(),
                                averageCost = position.averageUnitCost.toCatalogCostText(),
                            )
                        }
                        detail to positions
                    },
                    onSuccess = { (detail, positions) ->
                        if (uiState.value.detail?.row?.stableId != row.stableId) return@executeIo
                        updateState {
                            copy(
                                detail = Detail.ProductDetail(
                                    row = row,
                                    catalogDetail = detail,
                                    positions = positions,
                                    isLoading = false,
                                    loadFailed = detail == null,
                                ),
                            )
                        }
                    },
                    onFailure = {
                        if (uiState.value.detail?.row?.stableId != row.stableId) return@executeIo
                        updateState {
                            copy(
                                detail = Detail.ProductDetail(
                                    row = row,
                                    isLoading = false,
                                    loadFailed = true,
                                ),
                            )
                        }
                    },
                )
            }
            is Row.SupplierRow -> executeMain {
                updateState { copy(detail = Detail.SupplierDetail(row), failure = null) }
            }
            is Row.UnitRow -> executeMain {
                updateState { copy(detail = Detail.UnitDetail(row), failure = null) }
            }
            is Row.LocationRow -> executeMain {
                updateState { copy(detail = Detail.LocationDetail(row), failure = null) }
            }
        }
    }

    private fun saveForm(checksumAccepted: Boolean) {
        val form = uiState.value.form ?: return
        val businessId = activeBusinessId ?: return
        if (form is Form.ProductForm && !BarcodeValue.isValidOptional(form.barcode)) {
            executeMain { updateState { copy(failure = Failure.INVALID_FIELDS) } }
            return
        }
        val supplierForm = form as? Form.SupplierForm
        val ruc = supplierForm?.ruc?.trim().orEmpty()
        if (supplierForm != null && ruc.isNotEmpty()) {
            if (!RucValidator.isWellFormed(ruc)) {
                executeMain { updateState { copy(failure = Failure.INVALID_RUC) } }
                return
            }
            if (!RucValidator.hasValidChecksum(ruc) && !checksumAccepted && !supplierForm.checksumAccepted) {
                executeMain { updateState { copy(showRucChecksumWarning = true) } }
                return
            }
        }
        val candidate = buildCandidate(form, businessId)
        if (candidate == null) {
            executeMain { updateState { copy(failure = Failure.INVALID_FIELDS) } }
            return
        }
        executeIo(
            before = {
                updateState {
                    copy(
                        isSaving = true,
                        failure = null,
                        showRucChecksumWarning = false,
                    )
                }
            },
            operation = {
                when (candidate) {
                    is Product -> saveProduct(candidate)
                    is Supplier -> saveSupplier(candidate)
                    is UnitOfMeasure -> saveUnit(candidate)
                    is InventoryLocation -> saveLocation(candidate)
                    else -> error("Tipo de catálogo no soportado")
                }
            },
            onSuccess = { result ->
                if (activeBusinessId == businessId) handleMutation(result)
            },
            onFailure = {
                if (activeBusinessId != businessId) return@executeIo
                updateState { copy(isSaving = false, failure = Failure.SAVE_FAILED) }
            },
        )
    }

    private fun buildCandidate(form: Form, businessId: BusinessId): Any? = runCatching {
        val now = clock.now()
        when (form) {
            is Form.ProductForm -> {
                val name = form.title.trim().takeIf { it.isNotEmpty() && it.length <= 200 }
                    ?: return null
                val unitId = form.unitId ?: return null
                val purchaseFactor = form.purchaseFactor.trim().ifEmpty { null }?.let(::BigDecimal)
                if ((form.purchaseUnitId == null) != (purchaseFactor == null)) return null
                if (purchaseFactor != null && purchaseFactor.signum() <= 0) return null
                Product(
                    productId = form.productId ?: ProductId.from(uuidGenerator.newUuid()),
                    businessId = businessId,
                    unitId = unitId,
                    name = name,
                    locationId = form.locationId,
                    sku = form.sku.trim().ifEmpty { null },
                    barcode = BarcodeValue.optionalOf(form.barcode)?.value,
                    purchaseUnitId = form.purchaseUnitId,
                    purchaseFactor = purchaseFactor,
                    status = form.original?.status ?: CatalogStatus.ACTIVE,
                    createdAt = form.original?.createdAt ?: now,
                    updatedAt = form.original?.updatedAt ?: now,
                    // La versión leída al abrir el formulario es el CAS del guardado.
                    version = form.original?.version ?: 1,
                )
            }
            is Form.SupplierForm -> Supplier(
                supplierId = form.supplierId ?: SupplierId.from(uuidGenerator.newUuid()),
                businessId = businessId,
                legalName = form.title.trim().takeIf { it.isNotEmpty() && it.length <= 200 }
                    ?: return null,
                ruc = form.ruc.trim().ifEmpty { null },
                tradeName = form.tradeName.trim().takeIf { it.isNotEmpty() },
                status = form.original?.status ?: CatalogStatus.ACTIVE,
                createdAt = form.original?.createdAt ?: now,
                updatedAt = form.original?.updatedAt ?: now,
                // La versión leída al abrir el formulario es el CAS del guardado.
                version = form.original?.version ?: 1,
            ).takeIf { it.tradeName == null || it.tradeName.length <= 200 }
            is Form.UnitForm -> UnitOfMeasure(
                unitId = form.unitId ?: UnitId.from(uuidGenerator.newUuid()),
                businessId = businessId,
                code = form.code.trim().uppercase(Locale.ROOT).takeIf {
                    UNIT_CODE.matches(it)
                } ?: return null,
                name = form.title.trim().takeIf { it.isNotEmpty() && it.length <= 100 }
                    ?: return null,
                symbol = form.symbol.trim().takeIf { it.isNotEmpty() },
                status = form.original?.status ?: CatalogStatus.ACTIVE,
                createdAt = form.original?.createdAt ?: now,
                updatedAt = form.original?.updatedAt ?: now,
            ).takeIf { it.symbol == null || it.symbol.length <= 16 }
            is Form.LocationForm -> InventoryLocation(
                locationId = form.locationId ?: LocationId.from(uuidGenerator.newUuid()),
                businessId = businessId,
                name = form.title.trim().takeIf { it.isNotEmpty() && it.length <= 100 }
                    ?: return null,
                status = form.original?.status ?: CatalogStatus.ACTIVE,
                createdAt = form.original?.createdAt ?: now,
                updatedAt = form.original?.updatedAt ?: now,
            )
        }
    }.getOrNull()

    private suspend fun handleMutation(result: CatalogMutationResult<*>) {
        when (result) {
            is CatalogMutationResult.Saved<*> -> {
                updateState {
                    copy(
                        isSaving = false,
                        form = null,
                        detail = null,
                        pendingStatusChange = null,
                        savedFeedback = true,
                        failure = null,
                    )
                }
                requestFirstPage()
                activeBusinessId?.let(::loadOptions)
            }
            is CatalogMutationResult.Duplicate -> updateState {
                copy(isSaving = false, failure = result.field.toFailure())
            }
            is CatalogMutationResult.Invalid -> updateState {
                copy(isSaving = false, failure = Failure.INVALID_FIELDS)
            }
            CatalogMutationResult.NotFound -> updateState {
                copy(isSaving = false, failure = Failure.NOT_FOUND)
            }
            CatalogMutationResult.Stale -> updateState {
                copy(isSaving = false, failure = Failure.STALE)
            }
        }
    }

    private fun requestStatusChange() {
        executeMain {
            val row = uiState.value.detail?.row ?: return@executeMain
            val target = if (row.status == CatalogStatus.ACTIVE) {
                CatalogStatus.ARCHIVED
            } else {
                CatalogStatus.ACTIVE
            }
            updateState {
                copy(pendingStatusChange = CatalogsContract.PendingStatusChange(row, target))
            }
        }
    }

    private fun confirmStatusChange() {
        val pending = uiState.value.pendingStatusChange ?: return
        val businessId = activeBusinessId ?: return
        if (pending.row.businessId() != businessId) {
            executeMain { updateState { copy(pendingStatusChange = null) } }
            return
        }
        executeIo(
            before = { updateState { copy(isSaving = true, failure = null) } },
            operation = {
                val archive = pending.target == CatalogStatus.ARCHIVED
                when (val row = pending.row) {
                    is Row.ProductRow -> if (archive) archiveProduct(row.value.productId)
                    else restoreProduct(row.value.productId)
                    is Row.SupplierRow -> if (archive) archiveSupplier(row.value.supplierId)
                    else restoreSupplier(row.value.supplierId)
                    is Row.UnitRow -> if (archive) archiveUnit(row.value.unitId)
                    else restoreUnit(row.value.unitId)
                    is Row.LocationRow -> if (archive) archiveLocation(row.value.locationId)
                    else restoreLocation(row.value.locationId)
                }
            },
            onSuccess = { result ->
                if (activeBusinessId == businessId) handleMutation(result)
            },
            onFailure = {
                if (activeBusinessId != businessId) return@executeIo
                updateState { copy(isSaving = false, failure = Failure.SAVE_FAILED) }
            },
        )
    }

    private fun loadOptions(businessId: BusinessId) {
        executeIo(
            operation = {
                val allUnits = loadAllPages { search -> units.search(businessId, search) }
                val allLocations = loadAllPages { search -> locations.search(businessId, search) }
                allUnits to allLocations
            },
            onSuccess = { (loadedUnits, loadedLocations) ->
                if (activeBusinessId != businessId) return@executeIo
                updateState {
                    copy(
                        unitOptions = loadedUnits.map { unit ->
                            CatalogsContract.UnitOption(
                                unitId = unit.unitId,
                                code = unit.code,
                                name = unit.name,
                                status = unit.status,
                            )
                        },
                        locationOptions = loadedLocations.map { location ->
                            CatalogsContract.LocationOption(
                                locationId = location.locationId,
                                label = location.name,
                                status = location.status,
                            )
                        },
                    )
                }
            },
            onFailure = { /* La lista principal sigue siendo utilizable sin abrir producto. */ },
        )
    }

    private fun updateProductForm(transform: Form.ProductForm.() -> Form.ProductForm) = executeMain {
        val form = uiState.value.form as? Form.ProductForm ?: return@executeMain
        updateState { copy(form = form.transform(), failure = null) }
    }

    private fun updateSupplierForm(transform: Form.SupplierForm.() -> Form.SupplierForm) = executeMain {
        val form = uiState.value.form as? Form.SupplierForm ?: return@executeMain
        updateState {
            copy(
                form = form.transform(),
                failure = null,
                showRucChecksumWarning = false,
            )
        }
    }

    private fun updateUnitForm(transform: Form.UnitForm.() -> Form.UnitForm) = executeMain {
        val form = uiState.value.form as? Form.UnitForm ?: return@executeMain
        updateState { copy(form = form.transform(), failure = null) }
    }

    private fun updateLocationForm(
        transform: Form.LocationForm.() -> Form.LocationForm,
    ) = executeMain {
        val form = uiState.value.form as? Form.LocationForm ?: return@executeMain
        updateState { copy(form = form.transform(), failure = null) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val PAGE_SIZE = 50
        const val MAX_QUERY_LENGTH = 200
        const val MAX_DECIMAL_LENGTH = 128
        const val SECTION_KEY = "catalogs.section"
        const val QUERY_KEY = "catalogs.query"
        const val STATUS_KEY = "catalogs.status"
        val UNIT_CODE = Regex("^[A-Z0-9]{1,16}$")
    }
}

private fun restoredCatalogsState(savedStateHandle: SavedStateHandle): State = State(
    section = savedStateHandle.get<String>("catalogs.section")
        ?.let { runCatching { Section.valueOf(it) }.getOrNull() }
        ?: Section.PRODUCTS,
    query = savedStateHandle.get<String>("catalogs.query").orEmpty(),
    statusFilter = savedStateHandle.get<String>("catalogs.status")
        ?.let { runCatching { CatalogsContract.StatusFilter.valueOf(it) }.getOrNull() }
        ?: CatalogsContract.StatusFilter.ALL,
)

private fun <T, R> CatalogPage<T>.map(transform: (T) -> R): CatalogPage<R> = CatalogPage(
    items = items.map(transform),
    total = total,
    offset = offset,
    limit = limit,
)

private fun <T, R> Flow<CatalogPage<T>>.mapPage(
    transform: (T) -> R,
): Flow<CatalogPage<R>> = map { page -> page.map(transform) }

private suspend fun <T> loadAllPages(
    search: suspend (CatalogSearch) -> CatalogPage<T>,
): List<T> {
    val result = mutableListOf<T>()
    do {
        val page = search(CatalogSearch(offset = result.size, limit = 200))
        result += page.items
    } while (page.hasMore)
    return result
}

private fun CatalogDuplicateField.toFailure(): Failure = when (this) {
    CatalogDuplicateField.RUC -> Failure.DUPLICATE_RUC
    CatalogDuplicateField.SKU -> Failure.DUPLICATE_SKU
    CatalogDuplicateField.BARCODE -> Failure.DUPLICATE_BARCODE
    CatalogDuplicateField.CODE -> Failure.DUPLICATE_CODE
    CatalogDuplicateField.NAME -> Failure.DUPLICATE_NAME
}

private fun Row.businessId(): BusinessId = when (this) {
    is Row.ProductRow -> value.businessId
    is Row.SupplierRow -> value.businessId
    is Row.UnitRow -> value.businessId
    is Row.LocationRow -> value.businessId
}
