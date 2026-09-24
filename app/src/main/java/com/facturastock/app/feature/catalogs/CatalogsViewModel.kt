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
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductEditingRepository
import com.facturastock.app.domain.repository.ProductEditingResult
import com.facturastock.app.domain.repository.ProductEditingSnapshot
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductRegistrationRepository
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.usecase.ArchiveInventoryLocationCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveSupplierCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveUnitCatalogUseCase
import com.facturastock.app.domain.usecase.LoadProductCatalogDetailUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.RestoreInventoryLocationCatalogUseCase
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
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map

/**
 * Catálogos completamente locales. La búsqueda siempre cruza la frontera paginada de Room:
 * nunca materializa los 5.000 productos para filtrarlos en Compose. Los cambios de consulta se
 * cancelan con `collectLatest` y esperan 250 ms para no saturar SQLite mientras se escribe.
 */
class CatalogsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeAppConfiguration: ObserveAppConfigurationUseCase,
    private val products: ProductRepository,
    private val suppliers: SupplierRepository,
    private val units: UnitRepository,
    private val locations: InventoryLocationRepository,
    aliases: SupplierProductAliasRepository,
    private val productInventory: ProductInventoryRepository,
    private val uuidGenerator: UuidGenerator,
    private val clock: AppClock,
    dispatcherProvider: DispatcherProvider,
    private val productRegistration: ProductRegistrationRepository,
    private val productEditing: ProductEditingRepository,
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
    private var activeCurrency: CurrencyCode = CurrencyCode.of("PEN")
    private var loadMoreJob: Job? = null
    private var searchVersion: Long = 0L
    private var saveInFlight = false
    private var formClosePending = false
    private var scannerLookupJob: Job? = null
    private var scannerLookupVersion = 0L
    private var directLookupJob: Job? = null
    private var directLookupVersion = 0L
    private var inventoryLookupJob: Job? = null
    private var inventoryLookupVersion = 0L
    private val isSalesRegistration = savedStateHandle.contains(RouteArgumentKeys.REGISTRATION_REQUEST_ID) ||
        savedStateHandle.contains(RouteArgumentKeys.REGISTRATION_BUSINESS_ID)
    private val salesRegistrationRequestId = savedStateHandle.get<String>(RouteArgumentKeys.REGISTRATION_REQUEST_ID)
        ?.takeIf { it.isNotBlank() && it.length <= 128 }
    private val salesRegistrationBusinessId = BusinessId.parse(
        savedStateHandle.get<String>(RouteArgumentKeys.REGISTRATION_BUSINESS_ID),
    )
    private var salesRegistrationEffectSent = false
    private var manualCompletionEffectSent = false

    init {
        executeMain {
            searchRequests.collectLatest { request ->
                // Cancelar también al salir del catálogo: null no puede dejar viva una
                // observación anterior detrás de un editor o de otro negocio.
                if (request != null) {
                    delay(SEARCH_DEBOUNCE_MILLIS)
                    runFirstPageSearch(request)
                }
            }
        }
        executeMain {
            observeAppConfiguration().collectLatest { configuration ->
                if (savedStateHandle.get<Boolean>(MANUAL_COMPLETED_KEY) == true) {
                    emitRegistrationBack()
                    return@collectLatest
                }
                if (isSalesRegistration) {
                    if (salesRegistrationRequestId == null ||
                        salesRegistrationBusinessId == null ||
                        configuration.activeBusinessId != salesRegistrationBusinessId
                    ) {
                        cancelInventoryLookup()
                        cancelScannerLookup()
                        clearInventoryForm()
                        clearScannedForm()
                        loadMoreJob?.cancel()
                        searchRequests.value = null
                        activeBusinessId = null
                        updateState {
                            copy(form = null, detail = null, rows = emptyList(), unitOptions = emptyList(),
                                locationOptions = emptyList(), isLoading = false, isSaving = false,
                                failure = Failure.NO_ACTIVE_BUSINESS)
                        }
                        savedStateHandle[SALES_REGISTRATION_RESULT_KEY] = arrayListOf("", "")
                        completeSalesRegistration()
                        return@collectLatest
                    }
                    if (savedStateHandle.contains(SALES_REGISTRATION_RESULT_KEY)) {
                        emitSalesRegistrationCompletion()
                        return@collectLatest
                    }
                }
                activeCurrency = configuration.currency
                updateState { copy(currency = configuration.currency) }
                val nextBusinessId = configuration.activeBusinessId
                val directBusiness = directEntryKind()?.let { savedStateHandle.get<ArrayList<String>>(it.formKey)?.firstOrNull() }
                if (hasDirectEntry() && (nextBusinessId == null ||
                        (activeBusinessId != null && activeBusinessId != nextBusinessId) ||
                        (directBusiness != null && directBusiness != nextBusinessId.value))) {
                    clearDirectForm()
                    updateState { copy(form = null, isSaving = false) }
                    emitRegistrationBack()
                }
                if (nextBusinessId == null) {
                    val leavingInventoryEditor = activeBusinessId != null && hasInventoryEntry()
                    cancelInventoryLookup()
                    if (leavingInventoryEditor) clearInventoryForm()
                    cancelScannerLookup()
                    clearScannedForm()
                    savedStateHandle[RouteArgumentKeys.PREFILL_BARCODE] = null
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
                    if (leavingInventoryEditor) emitRegistrationBack()
                    else if (hasInventoryEntry()) updateState {
                        copy(isInventoryEntryPending = true, inventoryEntryFailure = Failure.NO_ACTIVE_BUSINESS)
                    }
                } else if (nextBusinessId != activeBusinessId) {
                    val leavingInventoryEditor = activeBusinessId != null && hasInventoryEntry()
                    cancelInventoryLookup()
                    if (leavingInventoryEditor) clearInventoryForm()
                    cancelScannerLookup()
                    if (activeBusinessId != null) {
                        clearScannedForm()
                        savedStateHandle[RouteArgumentKeys.PREFILL_BARCODE] = null
                    }
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
                    when {
                        hasDirectEntry() -> applyDirectRegistration()
                        hasInventoryEntry() -> applyInventoryEdit()
                        else -> applyBarcodePrefill()
                    }
                    if (leavingInventoryEditor) emitRegistrationBack()
                    requestFirstPage()
                    loadOptions(nextBusinessId)
                }
            }
        }
    }

    override fun onAction(action: Action) {
        if (savedStateHandle.get<Boolean>(MANUAL_COMPLETED_KEY) == true) {
            if (action == Action.CloseForm) executeMain { emitRegistrationBack() }
            return
        }
        if (formClosePending && (action == Action.SaveForm ||
                action == Action.AcceptRucChecksumAndSave || action == Action.CloseForm)) return
        if (isSalesRegistration && (savedStateHandle.contains(SALES_REGISTRATION_RESULT_KEY) ||
                salesRegistrationRequestId == null || activeBusinessId != salesRegistrationBusinessId)) {
            if (action == Action.CloseForm) executeMain { completeSalesRegistration() }
            return
        }
        // Esta ruta edita metadatos del producto resuelto; no puede abrir altas ni
        // cambiar la identidad primaria o las unidades por eventos rezagados.
        if (hasInventoryEntry() && when (action) {
            is Action.ProductNameChanged, is Action.ProductPriceChanged,
            is Action.ProductSkuChanged, is Action.ProductBarcodeChanged,
            is Action.ProductQuantityChanged, is Action.ProductPurchasePriceChanged,
            is Action.ProductLocationSelected,
            Action.SaveForm, Action.CloseForm, Action.Retry -> false
            else -> true
        }) return
        if (hasDirectEntry() && when (action) {
            is Action.ProductNameChanged, is Action.ProductPriceChanged,
            is Action.ProductQuantityChanged, is Action.ProductPurchasePriceChanged,
            is Action.ProductLocationSelected, Action.SaveForm, Action.CloseForm, Action.Retry -> false
            else -> true
        }) return
        if ((uiState.value.isSpecialEntryPending || uiState.value.isManualEntryPending) && action != Action.Retry && action != Action.CloseForm) return
        if (uiState.value.isInventoryEntryPending && action != Action.Retry && action != Action.CloseForm) return
        if (uiState.value.isScannerEntryPending && action != Action.Retry && action != Action.CloseForm) return
        when (action) {
            is Action.SectionSelected -> changeSearch(section = action.section)
            is Action.QueryChanged -> changeSearch(query = action.value.take(MAX_QUERY_LENGTH))
            is Action.StatusFilterSelected -> if (uiState.value.section != Section.PRODUCTS) {
                changeSearch(statusFilter = action.filter)
            }
            Action.LoadMore -> loadMore()
            Action.Retry -> executeMain {
                if (uiState.value.isSpecialEntryPending || uiState.value.isManualEntryPending) {
                    applyDirectRegistration()
                    return@executeMain
                }
                if (uiState.value.isInventoryEntryPending) {
                    applyInventoryEdit()
                    return@executeMain
                }
                if (uiState.value.isScannerEntryPending) {
                    applyBarcodePrefill()
                    return@executeMain
                }
                updateState { copy(failure = null, isLoading = true) }
                requestFirstPage()
            }

            Action.AddSelected -> openCreateForm()
            is Action.RowSelected -> openDetail(action.row)
            Action.CloseDetail -> executeMain { updateState { copy(detail = null, failure = null) } }
            Action.EditSelected -> openEditForm()
            Action.CloseForm -> {
                if (saveInFlight) return
                // La cancelación gana a cualquier Guardar rezagado aunque Main aún no haya
                // ejecutado la limpieza del formulario. Un guardado ya aceptado sí conserva su turno.
                formClosePending = true
                executeMain {
                    try {
                        val returnToDirectEntry = hasDirectEntry()
                        clearDirectForm()
                        val returnToInventory = hasInventoryEntry()
                        cancelInventoryLookup()
                        clearInventoryForm()
                        val returnToScanner = uiState.value.isScannerEntryPending ||
                            (uiState.value.form as? Form.ProductForm)?.isScannerOrigin == true
                        cancelScannerLookup()
                        clearScannedForm()
                        savedStateHandle[RouteArgumentKeys.PREFILL_BARCODE] = null
                        updateState {
                            copy(
                                form = null,
                                failure = null,
                                showRucChecksumWarning = false,
                            )
                        }
                        if (isSalesRegistration) completeSalesRegistration()
                        else if (returnToScanner || returnToInventory || returnToDirectEntry) emitRegistrationBack()
                    } finally {
                        formClosePending = false
                    }
                }
            }

            Action.SaveForm -> saveForm(checksumAccepted = false)
            Action.AcceptRucChecksumAndSave -> saveForm(checksumAccepted = true)
            Action.DismissRucChecksumWarning -> executeMain {
                updateState { copy(showRucChecksumWarning = false) }
            }
            is Action.ProductNameChanged -> updateProductForm {
                copy(title = if (isInventoryOrigin) action.value.take(201) else action.value)
            }
            is Action.ProductSkuChanged -> updateProductForm {
                if (isInventoryOrigin) copy(
                    sku = action.value.take(65),
                    isSkuInputTooLong = action.value.length > 65,
                ) else copy(sku = action.value.take(64))
            }
            is Action.ProductBarcodeChanged -> updateProductForm {
                // El borrador de Inventario está acotado. Un exceso queda marcado inválido:
                // jamás se guarda un identificador distinto por recortar el texto pegado.
                when {
                    isScannerOrigin -> this
                    isInventoryOrigin -> copy(
                        barcode = action.value.take(BarcodeValue.MAX_LENGTH + 1),
                        isBarcodeInputTooLong = action.value.length > BarcodeValue.MAX_LENGTH + 1,
                    )
                    else -> copy(barcode = action.value)
                }
            }
            is Action.ProductPriceChanged -> updateProductForm {
                // El carácter 25 conserva el exceso: el parser lo rechaza antes de trim.
                copy(salePrice = if (isInventoryOrigin) action.value.take(25) else action.value)
            }
            is Action.ProductPurchasePriceChanged -> updateProductForm {
                if (isInventoryOrigin && (inventorySnapshot?.inventoryEditable != true || locationId == null)) this
                else copy(purchasePrice = if (isInventoryOrigin) action.value.take(129) else action.value)
            }
            is Action.ProductQuantityChanged -> updateProductForm {
                if (isInventoryOrigin && (inventorySnapshot?.inventoryEditable != true || locationId == null)) this
                else copy(quantity = if (isInventoryOrigin) action.value.take(129) else action.value)
            }
            is Action.ProductUnitSelected -> updateProductForm { copy(unitId = action.unitId) }
            is Action.ProductLocationSelected -> updateProductForm {
                if (isInventoryOrigin) selectInventoryLocation(action.locationId)
                else copy(locationId = action.locationId)
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
            clearScannedForm()
            loadMoreJob?.cancel()
            updateState {
                copy(
                    section = section ?: this.section,
                    query = query ?: this.query,
                    statusFilter = if ((section ?: this.section) == Section.PRODUCTS) {
                        CatalogsContract.StatusFilter.ACTIVE
                    } else statusFilter ?: this.statusFilter,
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
        if (hasDirectEntry() || hasInventoryEntry() || uiState.value.isScannerEntryPending) {
            searchRequests.value = null
            updateState { copy(isLoading = false, isLoadingMore = false) }
            return
        }
        if (uiState.value.section == Section.PRODUCTS) {
            updateState { copy(statusFilter = CatalogsContract.StatusFilter.ACTIVE) }
            savedStateHandle[STATUS_KEY] = CatalogsContract.StatusFilter.ACTIVE.name
        }
        val state = uiState.value
        searchRequests.value = SearchRequest(
            businessId = businessId,
            section = state.section,
            query = state.query,
            status = if (state.section == Section.PRODUCTS) CatalogStatus.ACTIVE else state.statusFilter.status,
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
            status = if (state.section == Section.PRODUCTS) CatalogStatus.ACTIVE else state.statusFilter.status,
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
            clearScannedForm()
            val defaultUnitId = uiState.value.unitOptions.firstOrNull { it.status == CatalogStatus.ACTIVE }?.unitId
            val defaultLocationId = uiState.value.locationOptions.firstOrNull { it.status == CatalogStatus.ACTIVE }?.locationId
            val form = when (uiState.value.section) {
                Section.PRODUCTS -> Form.ProductForm(unitId = defaultUnitId, locationId = defaultLocationId)
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

    private fun hasInventoryEntry(): Boolean =
        savedStateHandle.get<String>(RouteArgumentKeys.EDIT_PRODUCT_ID) != null ||
            savedStateHandle.get<ArrayList<String>>(INVENTORY_FORM_KEY) != null

    /** El ID de navegación es la única identidad; también admite productos sin código de barras. */
    private fun applyInventoryEdit() {
        val businessId = activeBusinessId ?: return
        val fields = savedStateHandle.get<ArrayList<String>>(INVENTORY_FORM_KEY)
        val rawId = savedStateHandle.get<String>(RouteArgumentKeys.EDIT_PRODUCT_ID)
        if (fields == null && rawId == null) return
        cancelInventoryLookup()
        cancelScannerLookup()
        searchRequests.value = null
        loadMoreJob?.cancel()
        loadMoreJob = null
        clearScannedForm()
        savedStateHandle[RouteArgumentKeys.PREFILL_BARCODE] = null
        val requestVersion = inventoryLookupVersion
        val currency = activeCurrency
        updateState {
            copy(section = Section.PRODUCTS, form = null, detail = null, failure = null,
                isLoading = false, isLoadingMore = false,
                isInventoryEntryPending = true, inventoryEntryFailure = null,
                isInventoryBalanceLoading = true, inventoryBalanceFailure = null,
                inventoryBalances = emptyList())
        }
        savedStateHandle[SECTION_KEY] = Section.PRODUCTS.name
        inventoryLookupJob = executeIo(
            operation = {
                val draft = fields?.let {
                    restoreInventoryProductForm(it) ?: throw InventoryEntryException(Failure.INVALID_FIELDS)
                }
                val productId = ProductId.parse(rawId ?: draft?.productId?.value.orEmpty())
                    ?: throw InventoryEntryException(Failure.NOT_FOUND)
                if (draft != null && (draft.original?.businessId != businessId || draft.productId != productId)) {
                    throw InventoryEntryException(Failure.NOT_FOUND)
                }
                val loaded = productEditing.load(businessId, productId, currency)
                    ?.takeIf { it.product.businessId == businessId && it.product.productId == productId }
                    ?: throw InventoryEntryException(Failure.NOT_FOUND)
                // Releer verifica existencia/capacidad, sin renovar versiones producto/saldos.
                if (draft?.inventorySnapshot != null) {
                    draft.copy(inventorySnapshot = draft.inventorySnapshot.copy(inventoryEditable = loaded.inventoryEditable))
                } else {
                    val base = draft ?: inventoryProductForm(loaded.product, currency)
                    base.withInventorySnapshot(loaded.copy(product = requireNotNull(base.original)))
                }
            },
            onSuccess = { form ->
                if (businessId != activeBusinessId || requestVersion != inventoryLookupVersion) return@executeIo
                inventoryLookupJob = null
                updateState {
                    copy(form = form, isInventoryEntryPending = false, inventoryEntryFailure = null,
                        isInventoryBalanceLoading = false, inventoryBalanceFailure = null,
                        inventoryBalances = requireNotNull(form.inventorySnapshot).toBalanceOptions(),
                        isInventoryStockEditable = form.inventorySnapshot.inventoryEditable)
                }
                persistInventoryForm()
            },
            onFailure = { error ->
                if (businessId != activeBusinessId || requestVersion != inventoryLookupVersion) return@executeIo
                inventoryLookupJob = null
                updateState {
                    val failure = (error as? InventoryEntryException)?.failure ?: Failure.LOAD_FAILED
                    copy(inventoryEntryFailure = failure, isInventoryBalanceLoading = false, inventoryBalanceFailure = failure)
                }
            },
        )
    }

    private fun cancelInventoryLookup() {
        inventoryLookupVersion++
        inventoryLookupJob?.cancel()
        inventoryLookupJob = null
        updateState {
            copy(isInventoryEntryPending = false, inventoryEntryFailure = null,
                isInventoryBalanceLoading = false, inventoryBalanceFailure = null, inventoryBalances = emptyList())
        }
    }

    private fun persistInventoryForm() {
        val form = uiState.value.form as? Form.ProductForm ?: return
        if (!form.isInventoryOrigin || form.original?.businessId != activeBusinessId) return
        savedStateHandle[INVENTORY_FORM_KEY] = form.inventoryDraftFields()
    }

    private fun clearInventoryForm() {
        savedStateHandle.remove<ArrayList<String>>(INVENTORY_FORM_KEY)
        savedStateHandle[RouteArgumentKeys.EDIT_PRODUCT_ID] = null
    }

    private class InventoryEntryException(val failure: Failure) : RuntimeException()

    private enum class DirectEntryKind(
        val routeKey: String,
        val formKey: String,
        val unitCodes: List<String>,
        val unitName: String,
        val unitSymbol: String,
    ) {
        SPECIAL(SPECIAL_ROUTE_KEY, SPECIAL_FORM_KEY, listOf("KGM", "KG"), "Kilogramo", "kg"),
        MANUAL(MANUAL_ROUTE_KEY, MANUAL_FORM_KEY, listOf("NIU", "UND"), "Unidad", "und"),
    }

    private fun directEntryKind(): DirectEntryKind? = DirectEntryKind.entries.firstOrNull { kind ->
        savedStateHandle.get<String>(kind.routeKey) == "true" || savedStateHandle.contains(kind.formKey)
    }

    private fun hasDirectEntry(): Boolean = directEntryKind() != null

    private fun clearDirectForm() {
        if (directEntryKind() == DirectEntryKind.MANUAL) savedStateHandle[MANUAL_COMPLETED_KEY] = true
        directLookupVersion += 1
        directLookupJob?.cancel()
        directLookupJob = null
        DirectEntryKind.entries.forEach { kind ->
            savedStateHandle.remove<ArrayList<String>>(kind.formKey)
            savedStateHandle[kind.routeKey] = null
        }
        updateState {
            copy(isSpecialEntryPending = false, specialEntryFailure = null,
                isManualEntryPending = false, manualEntryFailure = null)
        }
    }

    /** Cierre durable: si el proceso muere antes de navegar, la siguiente VM vuelve a salir. */
    private suspend fun emitRegistrationBack() {
        if (savedStateHandle.get<Boolean>(MANUAL_COMPLETED_KEY) == true) {
            if (manualCompletionEffectSent) return
            manualCompletionEffectSent = true
        }
        emitEffect(CatalogsContract.Effect.Back)
    }

    private fun Form.ProductForm.directEntryKind(): DirectEntryKind? = when {
        isManualRegistration -> DirectEntryKind.MANUAL
        isSpecialRegistration -> DirectEntryKind.SPECIAL
        else -> null
    }

    private val Form.ProductForm.isDirectRegistration: Boolean
        get() = isSpecialRegistration || isManualRegistration

    private fun persistDirectForm() {
        val form = uiState.value.form as? Form.ProductForm ?: return
        val kind = form.directEntryKind() ?: return
        val businessId = activeBusinessId ?: return
        val productId = form.registrationProductId ?: return
        savedStateHandle[kind.formKey] = arrayListOf(
            businessId.value, productId.value, form.title, form.quantity, form.purchasePrice,
            form.salePrice, form.unitId?.value.orEmpty(), form.locationId?.value.orEmpty(),
        )
    }

    /** Reserva la identidad antes de suspender; manual y especial comparten la defensa anti-repetición. */
    private fun applyDirectRegistration() {
        val businessId = activeBusinessId ?: return
        val kind = directEntryKind() ?: return
        val fields = savedStateHandle.get<ArrayList<String>>(kind.formKey)
        val restoredId = fields?.getOrNull(1)?.let(ProductId::parse)
        if (fields != null && (fields.size != 8 || fields[0] != businessId.value || restoredId == null)) {
            clearDirectForm()
            executeMain { emitRegistrationBack() }
            return
        }
        val productId = restoredId ?: ProductId.from(uuidGenerator.newUuid())
        val draft = Form.ProductForm(
            isSpecialRegistration = kind == DirectEntryKind.SPECIAL,
            isManualRegistration = kind == DirectEntryKind.MANUAL,
            registrationProductId = productId,
            title = fields?.getOrNull(2).orEmpty().take(201),
            quantity = fields?.getOrNull(3).orEmpty().take(25),
            purchasePrice = fields?.getOrNull(4).orEmpty().take(25),
            salePrice = fields?.getOrNull(5).orEmpty().take(25),
            locationId = fields?.getOrNull(7)?.takeIf(String::isNotBlank)?.let(LocationId::parse),
        )
        savedStateHandle[kind.formKey] = arrayListOf(
            businessId.value, productId.value, draft.title, draft.quantity, draft.purchasePrice,
            draft.salePrice, "", draft.locationId?.value.orEmpty(),
        )
        directLookupJob?.cancel()
        val generation = ++directLookupVersion
        updateState {
            copy(form = null, detail = null,
                isSpecialEntryPending = kind == DirectEntryKind.SPECIAL, specialEntryFailure = null,
                isManualEntryPending = kind == DirectEntryKind.MANUAL, manualEntryFailure = null)
        }
        directLookupJob = executeIo(
            operation = {
                val existing = products.findById(productId)
                val unit = findActiveRegistrationUnit(businessId, kind)
                val locationId = draft.locationId
                    ?: locations.search(businessId, CatalogSearch(status = CatalogStatus.ACTIVE, limit = 1)).items.firstOrNull()?.locationId
                existing to draft.copy(unitId = unit?.unitId, locationId = locationId)
            },
            onSuccess = { (existing, form) ->
                if (activeBusinessId != businessId || generation != directLookupVersion) return@executeIo
                directLookupJob = null
                if (existing != null) {
                    // El registro ya confirmado nunca vuelve a sumar las existencias iniciales.
                    clearDirectForm()
                    emitRegistrationBack()
                } else {
                    updateState {
                        copy(form = form, isSpecialEntryPending = false, specialEntryFailure = null,
                            isManualEntryPending = false, manualEntryFailure = null, failure = null)
                    }
                    persistDirectForm()
                }
            },
            onFailure = {
                if (activeBusinessId == businessId && generation == directLookupVersion) {
                    updateState {
                        if (kind == DirectEntryKind.MANUAL) copy(manualEntryFailure = Failure.LOAD_FAILED)
                        else copy(specialEntryFailure = Failure.LOAD_FAILED)
                    }
                }
            },
        )
    }

    private suspend fun findActiveRegistrationUnit(businessId: BusinessId, kind: DirectEntryKind): UnitOfMeasure? {
        for (code in kind.unitCodes) {
            val unit = units.findByCode(businessId, code)
            if (unit?.businessId == businessId && unit.status == CatalogStatus.ACTIVE) return unit
        }
        return null
    }

    private suspend fun resolveDirectRegistration(form: Form.ProductForm, businessId: BusinessId): Form.ProductForm {
        val kind = form.directEntryKind() ?: throw InventoryEntryException(Failure.INVALID_FIELDS)
        if (activeBusinessId != businessId || directEntryKind() != kind) throw InventoryEntryException(Failure.STALE)
        val warehouse = form.locationId?.let { locations.findById(it) }
            ?.takeIf { it.businessId == businessId && it.status == CatalogStatus.ACTIVE }
            ?: throw InventoryEntryException(Failure.INVALID_FIELDS)
        var unit = findActiveRegistrationUnit(businessId, kind)
        if (unit == null) {
            // Manual crea la unidad normal NIU; UND sólo es un alias ya existente. Nunca recupera
            // unidades archivadas ni usa el primer resultado alfabético (que podría ser KGM).
            val codesToCreate = if (kind == DirectEntryKind.MANUAL) listOf("NIU") else kind.unitCodes
            val code = codesToCreate.firstOrNull { units.findByCode(businessId, it) == null }
                ?: throw InventoryEntryException(Failure.INVALID_FIELDS)
            if (activeBusinessId != businessId || directEntryKind() != kind) throw InventoryEntryException(Failure.STALE)
            val now = clock.now()
            val candidate = UnitOfMeasure(UnitId.from(uuidGenerator.newUuid()), businessId, code, kind.unitName, kind.unitSymbol,
                createdAt = now, updatedAt = now)
            when (val result = saveUnit(candidate)) {
                is CatalogMutationResult.Saved -> unit = result.value
                is CatalogMutationResult.Duplicate -> unit = findActiveRegistrationUnit(businessId, kind)
                else -> throw InventoryEntryException(Failure.INVALID_FIELDS)
            }
        }
        return form.copy(unitId = unit?.unitId ?: throw InventoryEntryException(Failure.INVALID_FIELDS),
            locationId = warehouse.locationId, barcode = "", sku = "", purchaseUnitId = null, purchaseFactor = "")
    }

    private fun applyBarcodePrefill() {
        val businessId = activeBusinessId ?: return
        val fields = savedStateHandle.get<ArrayList<String>>(SCANNED_FORM_KEY)
        val raw = savedStateHandle.get<String>(RouteArgumentKeys.PREFILL_BARCODE)
        if (fields == null && raw == null) return
        val barcode = BarcodeValue.parse(fields?.getOrNull(1) ?: raw.orEmpty())?.value
        if (fields != null && (fields.size !in setOf(8, SCANNED_FORM_FIELD_COUNT) ||
                fields.firstOrNull() != businessId.value || barcode == null || barcode != fields[1])) {
            clearScannedForm()
            savedStateHandle[RouteArgumentKeys.PREFILL_BARCODE] = null
            return
        }
        // La ruta con argumento vacío conserva el alta manual normal.
        if (fields == null && raw.isNullOrBlank()) {
            openCreateForm()
            savedStateHandle[RouteArgumentKeys.PREFILL_BARCODE] = null
            return
        }
        cancelScannerLookup()
        val requestVersion = scannerLookupVersion
        updateState {
            copy(section = Section.PRODUCTS, form = null, detail = null,
                isScannerEntryPending = true, scannerEntryFailure = null)
        }
        savedStateHandle[SECTION_KEY] = Section.PRODUCTS.name
        scannerLookupJob = executeIo(
            operation = {
                if (barcode == null) throw ScannerEntryException(Failure.INVALID_FIELDS)
                val existingId = fields?.getOrNull(8)?.takeIf(String::isNotBlank)
                val product = if (existingId != null) {
                    val id = ProductId.parse(existingId)
                        ?: throw ScannerEntryException(Failure.NOT_FOUND)
                    products.findById(id)?.takeIf { it.businessId == businessId && it.barcode == barcode }
                        ?: throw ScannerEntryException(Failure.NOT_FOUND)
                } else {
                    products.findByBarcode(businessId, barcode)
                }
                val base = if (product != null) {
                    val expectedVersion = fields?.getOrNull(9)?.toLongOrNull() ?: product.version
                    scannerEditForm(product.copy(version = expectedVersion))
                } else {
                    Form.ProductForm(barcode = barcode, isScannedRegistration = true)
                }
                // Si un alta pendiente ya existe al restaurar, se abre su ficha sin aplicar la
                // cantidad del alta como existencias totales ni registrar un segundo movimiento.
                if (fields == null || (product != null && existingId == null)) base else base.copy(
                    title = fields[2], quantity = fields[3], purchasePrice = fields[4],
                    salePrice = fields[5], unitId = UnitId.parse(fields[6]),
                    locationId = LocationId.parse(fields[7]),
                    sku = fields.getOrNull(10) ?: base.sku,
                    purchaseUnitId = if (fields.size == SCANNED_FORM_FIELD_COUNT) UnitId.parse(fields[11]) else base.purchaseUnitId,
                    purchaseFactor = fields.getOrNull(12) ?: base.purchaseFactor,
                )
            },
            onSuccess = { resolved ->
                if (businessId != activeBusinessId || requestVersion != scannerLookupVersion) return@executeIo
                scannerLookupJob = null
                if (resolved.isEditing) {
                    // El escaneo de un producto existente usa el mismo editor de Inventario.
                    // Conserva metadatos y CAS del borrador anterior; cantidad/costo se releen
                    // como existencias actuales, nunca se trasladan desde un alta pendiente.
                    val draft = inventoryProductForm(requireNotNull(resolved.original), activeCurrency).copy(
                        title = resolved.title,
                        sku = resolved.sku,
                        salePrice = resolved.salePrice,
                    )
                    savedStateHandle[INVENTORY_FORM_KEY] = draft.inventoryDraftFields()
                    savedStateHandle[RouteArgumentKeys.EDIT_PRODUCT_ID] = requireNotNull(draft.productId).value
                    applyInventoryEdit()
                    return@executeIo
                }
                val form = resolved.copy(
                    unitId = resolved.unitId ?: uiState.value.unitOptions.firstOrNull { it.status == CatalogStatus.ACTIVE }?.unitId,
                    locationId = resolved.locationId ?: uiState.value.locationOptions.firstOrNull { it.status == CatalogStatus.ACTIVE }?.locationId,
                )
                updateState { copy(form = form, isScannerEntryPending = false, scannerEntryFailure = null, failure = null) }
                persistScannedForm()
                savedStateHandle[RouteArgumentKeys.PREFILL_BARCODE] = null
            },
            onFailure = { error ->
                if (businessId != activeBusinessId || requestVersion != scannerLookupVersion) return@executeIo
                scannerLookupJob = null
                updateState { copy(scannerEntryFailure = (error as? ScannerEntryException)?.failure ?: Failure.LOAD_FAILED) }
            },
        )
    }

    private fun scannerEditForm(product: Product) = Form.ProductForm(
        productId = product.productId, title = product.name, sku = product.sku.orEmpty(),
        barcode = product.barcode.orEmpty(), unitId = product.unitId, locationId = product.locationId,
        purchaseUnitId = product.purchaseUnitId,
        purchaseFactor = product.purchaseFactor?.toPlainString().orEmpty(),
        salePrice = product.salePrice?.toMajor()?.toPlainString().orEmpty(),
        // No precargar existencias: guardar solo metadatos no debe generar un ajuste de stock.
        quantity = "", original = product, isScannerOrigin = true,
    )

    private fun cancelScannerLookup() {
        scannerLookupVersion++
        scannerLookupJob?.cancel()
        scannerLookupJob = null
        updateState { copy(isScannerEntryPending = false, scannerEntryFailure = null) }
    }

    private class ScannerEntryException(val failure: Failure) : RuntimeException()

    /** Un solo valor compatible con SavedState conserva campos, identidad y versión CAS. */
    private fun persistScannedForm() {
        if (uiState.value.isScannerEntryPending) return
        val businessId = activeBusinessId ?: return
        val form = uiState.value.form as? Form.ProductForm
        if (form == null || !form.isScannerOrigin) {
            clearScannedForm()
            return
        }
        savedStateHandle[SCANNED_FORM_KEY] = arrayListOf(
            businessId.value, form.barcode, form.title, form.quantity, form.purchasePrice,
            form.salePrice, form.unitId?.value.orEmpty(), form.locationId?.value.orEmpty(),
            form.productId?.value.orEmpty(), form.original?.version?.toString().orEmpty(),
            form.sku, form.purchaseUnitId?.value.orEmpty(), form.purchaseFactor,
        )
    }

    private fun clearScannedForm() {
        savedStateHandle.remove<ArrayList<String>>(SCANNED_FORM_KEY)
    }

    private fun openEditForm() {
        executeMain {
            val row = uiState.value.detail?.row ?: return@executeMain
            clearScannedForm()
            val form = when (row) {
                is Row.ProductRow -> row.value.let { product ->
                    val productDetail = uiState.value.detail as? Detail.ProductDetail
                    val preferredStock = productDetail?.catalogDetail?.inventory?.positions
                        ?.firstOrNull { position -> position.locationId == product.locationId }
                        ?.quantityOnHand
                        ?.toPlainString()
                    val currentStock = preferredStock
                        ?: productDetail?.positions?.firstOrNull()?.quantity.orEmpty()
                    Form.ProductForm(
                        productId = product.productId,
                        title = product.name,
                        sku = product.sku.orEmpty(),
                        barcode = product.barcode.orEmpty(),
                        unitId = product.unitId,
                        locationId = product.locationId,
                        purchaseUnitId = product.purchaseUnitId,
                        purchaseFactor = product.purchaseFactor?.toPlainString().orEmpty(),
                        salePrice = product.salePrice?.toMajor()?.toPlainString().orEmpty(),
                        quantity = currentStock,
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
        if (saveInFlight || formClosePending) return
        val form = uiState.value.form ?: return
        val businessId = activeBusinessId ?: return
        if (isSalesRegistration && (salesRegistrationRequestId == null ||
                businessId != salesRegistrationBusinessId || savedStateHandle.contains(SALES_REGISTRATION_RESULT_KEY))) return
        if (form is Form.ProductForm && form.isInventoryOrigin &&
            (uiState.value.isInventoryBalanceLoading || uiState.value.inventoryBalanceFailure != null)
        ) return
        val currency = (form as? Form.ProductForm)?.saleCurrency ?: activeCurrency
        if (form is Form.ProductForm && form.isInventoryOrigin &&
            (form.original?.businessId != businessId || form.productId != form.original.productId)
        ) {
            executeMain { updateState { copy(failure = Failure.NOT_FOUND) } }
            return
        }
        if (form is Form.ProductForm && !form.hasValidProductFields(currency)) {
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
        saveInFlight = true
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
                val resolvedForm = if (form is Form.ProductForm && form.isDirectRegistration) {
                    resolveDirectRegistration(form, businessId)
                } else if (form is Form.ProductForm && !form.isInventoryOrigin &&
                    (form.unitId == null || (form.locationId == null &&
                        (!form.isScannerOrigin || !form.isEditing || form.quantity.isNotBlank())))
                ) {
                    val defaultUnit = form.unitId ?: units.search(businessId, CatalogSearch(limit = 10)).items
                        .firstOrNull { it.status == CatalogStatus.ACTIVE }?.unitId
                    val defaultLocation = form.locationId ?: locations.search(businessId, CatalogSearch(limit = 10)).items
                        .firstOrNull { it.status == CatalogStatus.ACTIVE }?.locationId
                    form.copy(unitId = defaultUnit, locationId = defaultLocation)
                } else {
                    form
                }
                val candidate = buildCandidate(resolvedForm, businessId, currency)
                    ?: throw IllegalArgumentException("Campos inválidos en formulario")
                when (candidate) {
                    is Product -> {
                        if (resolvedForm is Form.ProductForm && (resolvedForm.isScannedRegistration || resolvedForm.isDirectRegistration)) {
                            if (resolvedForm.isDirectRegistration) {
                                if (activeBusinessId != businessId || !hasDirectEntry()) throw InventoryEntryException(Failure.STALE)
                                val existing = products.findById(candidate.productId)
                                if (existing != null) {
                                    if (existing.businessId != businessId || existing.barcode != null || existing.unitId != candidate.unitId ||
                                        (resolvedForm.isManualRegistration && existing.sku != null)
                                    ) {
                                        return@executeIo CatalogMutationResult.Stale
                                    }
                                    return@executeIo CatalogMutationResult.Saved(existing)
                                }
                            }
                            if (resolvedForm.isDirectRegistration && (activeBusinessId != businessId || !hasDirectEntry())) {
                                throw InventoryEntryException(Failure.STALE)
                            }
                            return@executeIo productRegistration.register(
                                product = candidate,
                                quantity = requireNotNull(resolvedForm.quantity.productDecimalOrNull()),
                                unitCost = UnitCost.of(
                                    requireNotNull(resolvedForm.purchasePrice.productDecimalOrNull()),
                                    currency,
                                ),
                            )
                        }
                        val result = if ((resolvedForm as? Form.ProductForm)?.isInventoryOrigin == true) {
                            productEditing.save(
                                expected = requireNotNull(resolvedForm.inventorySnapshot),
                                candidate = candidate,
                                stockEdits = requireNotNull(resolvedForm.inventoryStockEditsOrNull()),
                            ).toCatalogResult()
                        } else saveProduct(candidate)
                        val productForm = resolvedForm as? Form.ProductForm
                        if (result is CatalogMutationResult.Saved && productForm != null && !productForm.isInventoryOrigin && productForm.quantity.isNotBlank()) {
                            val qty = productForm.quantity.productDecimalOrNull()
                            val locId = candidate.locationId
                            if (qty != null && locId != null) {
                                productInventory.setStock(
                                    businessId = businessId,
                                    productId = result.value.productId,
                                    locationId = locId,
                                    quantity = qty,
                                    currency = currency,
                                )
                            }
                        }
                        result
                    }
                    is Supplier -> saveSupplier(candidate)
                    is UnitOfMeasure -> saveUnit(candidate)
                    is InventoryLocation -> saveLocation(candidate)
                    else -> error("Tipo de catálogo no soportado")
                }
            },
            onSuccess = { result ->
                saveInFlight = false
                if (activeBusinessId == businessId) {
                    val returnToReader = form is Form.ProductForm && (form.isScannerOrigin || form.isInventoryOrigin || form.isDirectRegistration)
                    handleMutation(result, refreshCatalog = !returnToReader)
                    if (result is CatalogMutationResult.Saved<*> && returnToReader) {
                        // El catálogo no recibe lecturas: volver al registro reactiva el lector.
                        if (isSalesRegistration) {
                            val product = result.value as? Product
                            if (product != null && product.businessId == salesRegistrationBusinessId) {
                                completeSalesRegistration(product)
                            }
                        } else emitRegistrationBack()
                    }
                }
            },
            onFailure = { error ->
                saveInFlight = false
                if (activeBusinessId != businessId) return@executeIo
                updateState { copy(isSaving = false, failure = (error as? InventoryEntryException)?.failure ?: Failure.SAVE_FAILED) }
            },
            onCancellation = {
                saveInFlight = false
                if (activeBusinessId == businessId) updateState { copy(isSaving = false) }
            },
        )
    }

    private fun buildCandidate(form: Form, businessId: BusinessId, currency: CurrencyCode): Any? = runCatching {
        val now = clock.now()
        when (form) {
            is Form.ProductForm -> {
                val name = form.title.trim().takeIf { it.isNotEmpty() && it.length <= 200 }
                    ?: return null
                val unitId = form.unitId ?: return null
                val purchaseFactor = form.purchaseFactor.trim().ifEmpty { null }?.let(::BigDecimal)
                if ((form.purchaseUnitId == null) != (purchaseFactor == null)) return null
                if (purchaseFactor != null && purchaseFactor.signum() <= 0) return null
                val salePriceMoney = form.salePrice.trim().takeIf { it.isNotEmpty() }?.let { raw ->
                    val decimal = raw.productDecimalOrNull()
                    if (decimal != null && decimal.signum() > 0) {
                        Money.fromMajor(decimal, currency, RoundingMode.UNNECESSARY)
                    } else null
                }
                if (form.isInventoryOrigin) {
                    val original = form.original?.takeIf { it.productId == form.productId && it.businessId == businessId } ?: return null
                    return original.copy(
                        name = name,
                        sku = if (form.sku == original.sku.orEmpty()) original.sku else form.sku.trim().ifEmpty { null },
                        barcode = if (form.barcode == original.barcode.orEmpty()) original.barcode else BarcodeValue.optionalOf(form.barcode)?.value,
                        salePrice = salePriceMoney,
                    )
                }
                Product(
                    productId = form.productId ?: form.registrationProductId ?: ProductId.from(uuidGenerator.newUuid()),
                    businessId = businessId,
                    unitId = unitId,
                    name = name,
                    locationId = form.locationId,
                    sku = form.sku.trim().ifEmpty { null },
                    barcode = BarcodeValue.optionalOf(form.barcode)?.value,
                    purchaseUnitId = form.purchaseUnitId,
                    purchaseFactor = purchaseFactor,
                    salePrice = salePriceMoney,
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

    private suspend fun handleMutation(result: CatalogMutationResult<*>, refreshCatalog: Boolean = true) {
        when (result) {
            is CatalogMutationResult.Saved<*> -> {
                clearDirectForm()
                clearInventoryForm()
                clearScannedForm()
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
                if (refreshCatalog) {
                    requestFirstPage()
                    activeBusinessId?.let(::loadOptions)
                }
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

    /** Conserva el resultado hasta que la ruta vuelva a Ventas, incluso al recrear el formulario. */
    private suspend fun completeSalesRegistration(product: Product? = null) {
        if (!savedStateHandle.contains(SALES_REGISTRATION_RESULT_KEY)) {
            savedStateHandle[SALES_REGISTRATION_RESULT_KEY] = arrayListOf(
                product?.productId?.value.orEmpty(),
                product?.businessId?.value.orEmpty(),
            )
        }
        emitSalesRegistrationCompletion()
    }

    private suspend fun emitSalesRegistrationCompletion() {
        if (salesRegistrationEffectSent) return
        val result = savedStateHandle.get<ArrayList<String>>(SALES_REGISTRATION_RESULT_KEY) ?: return
        salesRegistrationEffectSent = true
        val productId = ProductId.parse(result.getOrNull(0))
        val businessId = BusinessId.parse(result.getOrNull(1))
        val requestId = salesRegistrationRequestId
        if (requestId != null && productId != null && businessId == salesRegistrationBusinessId && businessId != null) {
            emitEffect(CatalogsContract.Effect.ProductSaved(requestId, productId, businessId))
        } else {
            emitRegistrationBack()
        }
    }

    private fun requestStatusChange() {
        executeMain {
            val row = uiState.value.detail?.row ?: return@executeMain
            if (row is Row.ProductRow) {
                updateState { copy(pendingStatusChange = null) }
                return@executeMain
            }
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
        if (pending.row is Row.ProductRow) {
            executeMain { updateState { copy(pendingStatusChange = null) } }
            return
        }
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
                    is Row.ProductRow -> CatalogMutationResult.NotFound
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
                    val currentForm = form
                    val updatedForm = if (currentForm is Form.ProductForm &&
                        !(currentForm.isInventoryOrigin || (currentForm.isScannerOrigin && currentForm.isEditing))
                    ) {
                        currentForm.copy(
                            unitId = currentForm.unitId ?: if (currentForm.isSpecialRegistration) {
                                loadedUnits.firstOrNull { it.code == "KGM" && it.status == CatalogStatus.ACTIVE }?.unitId
                                    ?: loadedUnits.firstOrNull { it.code == "KG" && it.status == CatalogStatus.ACTIVE }?.unitId
                            } else if (currentForm.isManualRegistration) {
                                loadedUnits.firstOrNull { it.code == "NIU" && it.status == CatalogStatus.ACTIVE }?.unitId
                                    ?: loadedUnits.firstOrNull { it.code == "UND" && it.status == CatalogStatus.ACTIVE }?.unitId
                            } else loadedUnits.firstOrNull { it.status == CatalogStatus.ACTIVE }?.unitId,
                            locationId = currentForm.locationId ?: loadedLocations.firstOrNull { it.status == CatalogStatus.ACTIVE }?.locationId,
                        )
                    } else {
                        currentForm
                    }
                    copy(
                        form = updatedForm,
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
                persistScannedForm()
                persistDirectForm()
            },
            onFailure = { /* La lista principal sigue siendo utilizable sin abrir producto. */ },
        )
    }

    private fun updateProductForm(transform: Form.ProductForm.() -> Form.ProductForm) = executeMain {
        if (saveInFlight) return@executeMain
        val form = uiState.value.form as? Form.ProductForm ?: return@executeMain
        val transformed = form.transform()
        val changed = if (form.isDirectRegistration) transformed.copy(
            title = transformed.title.take(201), quantity = transformed.quantity.take(25),
            purchasePrice = transformed.purchasePrice.take(25), salePrice = transformed.salePrice.take(25),
        ) else transformed
        val updated = if (changed.isInventoryOrigin && changed.locationId != null) {
            changed.copy(inventoryBalanceDrafts = changed.inventoryBalanceDrafts.map { draft ->
                if (draft.locationId == changed.locationId) draft.copy(quantity = changed.quantity, purchasePrice = changed.purchasePrice)
                else draft
            })
        } else changed
        updateState { copy(form = updated, failure = null) }
        persistScannedForm()
        persistInventoryForm()
        persistDirectForm()
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
        const val INVENTORY_FORM_KEY = "catalogs.inventoryProductEditor"
        const val SCANNED_FORM_KEY = "catalogs.scannedRegistration"
        const val SPECIAL_FORM_KEY = "catalogs.specialRegistration"
        const val SPECIAL_ROUTE_KEY = "specialProduct"
        const val MANUAL_FORM_KEY = "catalogs.manualRegistration"
        const val MANUAL_ROUTE_KEY = "manualProduct"
        const val MANUAL_COMPLETED_KEY = "catalogs.manualRegistrationCompleted"
        const val SALES_REGISTRATION_RESULT_KEY = "catalogs.salesRegistrationResult"
        const val SCANNED_FORM_FIELD_COUNT = 13
        val UNIT_CODE = Regex("^[A-Z0-9]{1,16}$")
    }
}

private fun restoredCatalogsState(savedStateHandle: SavedStateHandle): State {
    val section = savedStateHandle.get<String>("catalogs.section")
        ?.let { runCatching { Section.valueOf(it) }.getOrNull() }
        ?: Section.PRODUCTS
    val status = if (section == Section.PRODUCTS) {
        CatalogsContract.StatusFilter.ACTIVE
    } else {
        savedStateHandle.get<String>("catalogs.status")
            ?.let { runCatching { CatalogsContract.StatusFilter.valueOf(it) }.getOrNull() }
            ?: CatalogsContract.StatusFilter.ALL
    }
    return State(
        section = section,
        query = savedStateHandle.get<String>("catalogs.query").orEmpty(),
        statusFilter = status,
    )
}

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


private fun ProductEditingSnapshot.toBalanceOptions(): List<CatalogsContract.InventoryBalanceOption> = positions.map { position ->
    CatalogsContract.InventoryBalanceOption(
        locationId = position.locationId, locationName = position.locationName,
        quantity = position.displayQuantity(), unitCost = position.displayUnitCost(),
        currency = position.currency,
    )
}

private fun ProductEditingResult.toCatalogResult(): CatalogMutationResult<Product> = when (this) {
    is ProductEditingResult.Saved -> CatalogMutationResult.Saved(snapshot.product)
    ProductEditingResult.Stale -> CatalogMutationResult.Stale
    ProductEditingResult.NotFound -> CatalogMutationResult.NotFound
    is ProductEditingResult.Duplicate -> CatalogMutationResult.Duplicate(field)
    is ProductEditingResult.Invalid -> CatalogMutationResult.Invalid(com.facturastock.app.domain.model.CatalogInvalidField.NAME)
}
