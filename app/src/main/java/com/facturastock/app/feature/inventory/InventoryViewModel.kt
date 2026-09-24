package com.facturastock.app.feature.inventory

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.input.KeyboardWedgeReadError
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductDeletionResult
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import com.facturastock.app.domain.usecase.DiagnoseInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryProductUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveProductProfitsUseCase
import com.facturastock.app.domain.usecase.UpdateProductSalePriceUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeInventory: ObserveInventoryUseCase,
    private val observeProduct: ObserveInventoryProductUseCase,
    private val diagnoseInventory: DiagnoseInventoryUseCase,
    private val observeProductProfits: ObserveProductProfitsUseCase,
    private val updateProductSalePrice: UpdateProductSalePriceUseCase,
    private val configuration: AppConfigurationRepository,
    private val products: ProductRepository,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<InventoryContract.State, InventoryContract.Action, InventoryContract.Effect>(
    initialState = restoredInventoryState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var observation: Job? = null
    private var profitObservation: Job? = null
    private var barcodeLookup: Job? = null
    private var barcodeLookupGeneration = 0L
    private var lastBarcodeForRetry: String? = null
    private var inventorySearchIndex = InventorySearchIndex.Empty
    private val inventorySearchTermsByProduct: Map<ProductId, List<String>>
        get() = inventorySearchIndex.termsByProduct
    private var profitSearchTermsByProduct: Map<ProductId, List<String>> = emptyMap()
    private var searchJob: Job? = null
    private var searchGeneration = 0L

    init {
        load()
    }

    override fun onAction(action: InventoryContract.Action) {
        when (action) {
            InventoryContract.Action.Load -> load()
            InventoryContract.Action.Retry -> retry()

            is InventoryContract.Action.SearchChanged -> updateSearch(action.query)
            is InventoryContract.Action.SearchFocusChanged -> executeMain {
                updateState { copy(isSearchFocused = action.focused) }
            }
            is InventoryContract.Action.EditProduct -> editProduct(action.productId)
            is InventoryContract.Action.DeleteProduct -> openDeletion(action.productId)
            InventoryContract.Action.ConfirmProductDeletion -> confirmDeletion()
            InventoryContract.Action.DismissProductDeletion -> executeMain {
                if (!uiState.value.isChangingProduct) {
                    updateState { copy(pendingDeletion = null, productActionFailure = null) }
                }
            }
            InventoryContract.Action.DismissProductActionFailure -> executeMain {
                if (!uiState.value.isChangingProduct) updateState { copy(productActionFailure = null) }
            }
            is InventoryContract.Action.SectionChanged -> changeSection(action.section)
            is InventoryContract.Action.InputModeChanged -> changeInputMode(action.mode)
            is InventoryContract.Action.ScannerAvailabilityChanged -> executeMain {
                updateState { copy(scannerActive = action.active) }
            }
            is InventoryContract.Action.BarcodeScanned -> lookupBarcode(action.value)
            is InventoryContract.Action.ScannerReadFailed -> scannerReadFailed(action.error)
            InventoryContract.Action.ScannerReadReset -> executeMain {
                if (!uiState.value.canRouteScannerInput) return@executeMain
                lastBarcodeForRetry = null
                updateState { copy(scannerFailure = null, lastUnmatchedBarcode = null) }
            }
            is InventoryContract.Action.EditSalePrice -> openSalePriceEditor(action.productId)
            is InventoryContract.Action.SalePriceChanged -> updateSalePrice(action.value)
            InventoryContract.Action.SaveSalePrice -> saveSalePrice()
            InventoryContract.Action.DismissSalePrice -> dismissSalePriceEditor()
            is InventoryContract.Action.ProductSelected -> executeMain {
                if (!uiState.value.canStartProductAction) return@executeMain
                emitEffect(InventoryContract.Effect.OpenProduct(action.productId))
            }
            is InventoryContract.Action.OriginPurchaseSelected -> executeMain {
                emitEffect(InventoryContract.Effect.OpenPurchase(action.purchaseId))
            }
            InventoryContract.Action.RunDiagnostic -> diagnose()
            InventoryContract.Action.RegisterScannedBarcode -> executeMain {
                val state = uiState.value
                val barcode = state.lastUnmatchedBarcode ?: return@executeMain
                if (!state.canRouteScannerInput ||
                    state.scannerFailure != InventoryContract.ScannerFailure.BARCODE_NOT_FOUND
                ) return@executeMain
                emitEffect(InventoryContract.Effect.OpenProductCreation(barcode))
            }
            InventoryContract.Action.BeginProductRegistration -> beginProductRegistration()
            InventoryContract.Action.EndProductRegistration -> endProductRegistration()
            InventoryContract.Action.RegisterProductManual -> executeMain {
                if (uiState.value.isChangingProduct || uiState.value.pendingDeletion != null) return@executeMain
                emitEffect(InventoryContract.Effect.OpenProductCreation(barcode = null))
            }
            InventoryContract.Action.BackSelected -> executeMain {
                if (uiState.value.isChangingProduct) return@executeMain
                if (uiState.value.pendingDeletion != null) {
                    updateState { copy(pendingDeletion = null, productActionFailure = null) }
                } else {
                    emitEffect(InventoryContract.Effect.Back)
                }
            }
        }
    }

    private fun InventoryContract.State.actionItem(productId: ProductId): InventoryReadItem? =
        if (this.productId == null) {
            allItems.firstOrNull { it.productId == productId }
        } else {
            detail?.item?.takeIf { it.productId == productId }
        }

    /** Valida la identidad leída sin trasladar una intención de edición a otro negocio. */
    private suspend fun productEditFailure(
        businessId: BusinessId,
        productId: ProductId,
    ): InventoryContract.ProductActionFailure? {
        if (configuration.current().activeBusinessId != businessId) {
            return InventoryContract.ProductActionFailure.BUSINESS_CHANGED
        }
        val product = products.findById(productId)
        if (product == null || product.productId != productId || product.businessId != businessId) {
            return InventoryContract.ProductActionFailure.PRODUCT_UNAVAILABLE
        }
        if (configuration.current().activeBusinessId != businessId) {
            return InventoryContract.ProductActionFailure.BUSINESS_CHANGED
        }
        if (product.status != CatalogStatus.ACTIVE) return InventoryContract.ProductActionFailure.STALE_PRODUCT
        return null
    }

    private fun editProduct(productId: ProductId) {
        executeMain {
            val state = uiState.value
            if (!state.canStartProductAction) return@executeMain
            val item = state.actionItem(productId) ?: return@executeMain
            if (InventoryDataAlert.ARCHIVED_PRODUCT in item.allAlerts) return@executeMain
            updateState { copy(isChangingProduct = true, productActionFailure = null) }
            try {
                val failure = withContext(dispatcherProvider.io) {
                    productEditFailure(item.businessId, productId)
                }
                if (failure == null) emitEffect(InventoryContract.Effect.EditProduct(productId))
                else updateState { copy(productActionFailure = failure) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateState { copy(productActionFailure = InventoryContract.ProductActionFailure.LOAD_FAILED) }
            } finally {
                updateState { copy(isChangingProduct = false) }
            }
        }
    }

    private fun openDeletion(productId: ProductId) {
        executeMain {
            val state = uiState.value
            if (!state.canStartProductAction) return@executeMain
            val item = state.actionItem(productId) ?: return@executeMain
            cancelBarcodeLookup()
            val retired = InventoryDataAlert.ARCHIVED_PRODUCT in item.allAlerts
            if (retired) return@executeMain
            val pending = InventoryContract.PendingProductDeletion(
                productId, item.businessId, item.productName,
            )
            updateState { copy(pendingDeletion = pending, productActionFailure = null) }
            prepareDeletion(pending)
        }
    }

    /** La revisión no borra: el usuario debe confirmar la identidad y versión leídas. */
    private suspend fun prepareDeletion(pending: InventoryContract.PendingProductDeletion) {
        updateState { copy(isChangingProduct = true, productActionFailure = null) }
        try {
            withContext(dispatcherProvider.io) {
                if (configuration.current().activeBusinessId != pending.businessId) {
                    return@withContext null to InventoryContract.ProductActionFailure.BUSINESS_CHANGED
                }
                val product = products.findById(pending.productId)
                when {
                    product == null || product.productId != pending.productId || product.businessId != pending.businessId ->
                        null to InventoryContract.ProductActionFailure.PRODUCT_UNAVAILABLE
                    configuration.current().activeBusinessId != pending.businessId ->
                        null to InventoryContract.ProductActionFailure.BUSINESS_CHANGED
                    product.status != CatalogStatus.ACTIVE ->
                        null to InventoryContract.ProductActionFailure.STALE_PRODUCT
                    else -> pending.copy(productName = product.name, expectedVersion = product.version) to null
                }
            }.let { (reviewed, failure) ->
                updateState { copy(pendingDeletion = reviewed ?: pending, productActionFailure = failure) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            updateState { copy(productActionFailure = InventoryContract.ProductActionFailure.LOAD_FAILED) }
        } finally {
            updateState { copy(isChangingProduct = false) }
        }
    }

    private fun confirmDeletion() {
        executeMain {
            val state = uiState.value
            val pending = state.pendingDeletion ?: return@executeMain
            if (state.isChangingProduct || state.productActionFailure in setOf(
                    InventoryContract.ProductActionFailure.BUSINESS_CHANGED,
                    InventoryContract.ProductActionFailure.PRODUCT_UNAVAILABLE,
                    InventoryContract.ProductActionFailure.STALE_PRODUCT,
                    InventoryContract.ProductActionFailure.HAS_HISTORY,
                    InventoryContract.ProductActionFailure.HAS_STOCK,
                    InventoryContract.ProductActionFailure.SHARED_BUSINESS,
                )
            ) return@executeMain
            val version = pending.expectedVersion
            if (version == null) {
                prepareDeletion(pending)
                return@executeMain
            }
            updateState { copy(isChangingProduct = true, productActionFailure = null) }
            try {
                val failure = withContext(dispatcherProvider.io) {
                    if (configuration.current().activeBusinessId != pending.businessId) {
                        InventoryContract.ProductActionFailure.BUSINESS_CHANGED
                    } else {
                        when (products.deletePermanently(pending.businessId, pending.productId, version)) {
                            ProductDeletionResult.DELETED -> null
                            ProductDeletionResult.NOT_FOUND -> InventoryContract.ProductActionFailure.PRODUCT_UNAVAILABLE
                            ProductDeletionResult.STALE -> InventoryContract.ProductActionFailure.STALE_PRODUCT
                            ProductDeletionResult.HAS_HISTORY, ProductDeletionResult.HAS_STOCK -> {
                                // La misma confirmación quita el producto del catálogo sin destruir
                                // sus referencias históricas. El rechazo previo no consume la versión.
                                if (configuration.current().activeBusinessId != pending.businessId) {
                                    InventoryContract.ProductActionFailure.BUSINESS_CHANGED
                                } else if (products.archive(pending.businessId, pending.productId, version)) {
                                    null
                                } else {
                                    InventoryContract.ProductActionFailure.STALE_PRODUCT
                                }
                            }
                            ProductDeletionResult.SHARED_BUSINESS -> InventoryContract.ProductActionFailure.SHARED_BUSINESS
                        }
                    }
                }
                if (failure == null) {
                    updateState { copy(pendingDeletion = null, productActionFailure = null) }
                    if (state.productId != null) emitEffect(InventoryContract.Effect.Back)
                } else {
                    updateState { copy(productActionFailure = failure) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateState { copy(productActionFailure = InventoryContract.ProductActionFailure.SAVE_FAILED) }
            } finally {
                updateState { copy(isChangingProduct = false) }
            }
        }
    }

    private fun beginProductRegistration() {
        executeMain {
            val state = uiState.value
            if (state.productId != null) return@executeMain
            if (state.isChangingProduct || state.pendingDeletion != null) {
                // RESUME puede coincidir con la comprobación de Editar. Conserva la intención
                // de usar el lector, que sigue bloqueado hasta terminar esa comprobación.
                savedStateHandle[INPUT_MODE_KEY] = InventoryContract.InputMode.SCANNER.name
                updateState {
                    copy(isRegisteringProducts = true, inputMode = InventoryContract.InputMode.SCANNER)
                }
                return@executeMain
            }
            val wasRegistering = uiState.value.isRegisteringProducts
            if (wasRegistering) return@executeMain
            cancelSearch()
            cancelBarcodeLookup()
            lastBarcodeForRetry = null
            savedStateHandle[SECTION_KEY] = InventoryContract.ListSection.STOCK.name
            savedStateHandle[INPUT_MODE_KEY] = InventoryContract.InputMode.SCANNER.name
            savedStateHandle[QUERY_KEY] = ""
            clearSavedPriceEditor()
            profitObservation?.cancel()
            profitObservation = null
            updateState {
                copy(
                    isRegisteringProducts = true,
                    registrationNavigationPending = false,
                    section = InventoryContract.ListSection.STOCK,
                    inputMode = InventoryContract.InputMode.SCANNER,
                    scannerActive = false,
                    scannerFailure = null,
                    lastUnmatchedBarcode = null,
                    salePriceEditor = null,
                    isProfitLoading = false,
                    query = "",
                    items = allItems.visibleInventory(),
                )
            }
            observeInventoryList()
        }
    }

    private fun endProductRegistration() {
        executeMain {
            if (!uiState.value.isRegisteringProducts) return@executeMain
            cancelBarcodeLookup()
            lastBarcodeForRetry = null
            savedStateHandle[INPUT_MODE_KEY] = InventoryContract.InputMode.SEARCH.name
            updateState {
                copy(
                    isRegisteringProducts = false,
                    registrationNavigationPending = false,
                    scannerActive = false,
                    scannerFailure = null,
                    lastUnmatchedBarcode = null,
                    inputMode = InventoryContract.InputMode.SEARCH,
                )
            }
        }
    }

    private fun retry() {
        val state = uiState.value
        val barcode = lastBarcodeForRetry
        if (state.failure == null && state.isRegisteringProducts &&
            state.scannerFailure == InventoryContract.ScannerFailure.LOOKUP_FAILED && barcode != null
        ) {
            lookupBarcode(barcode)
        } else {
            load()
        }
    }

    private fun scannerReadFailed(error: KeyboardWedgeReadError) {
        executeMain {
            if (!uiState.value.canRouteScannerInput) return@executeMain
            lastBarcodeForRetry = null
            updateState {
                copy(
                    lastUnmatchedBarcode = null,
                    scannerFailure = when (error) {
                        KeyboardWedgeReadError.INCOMPLETE -> InventoryContract.ScannerFailure.INCOMPLETE_BARCODE
                        KeyboardWedgeReadError.TOO_LONG -> InventoryContract.ScannerFailure.BARCODE_TOO_LONG
                        KeyboardWedgeReadError.INVALID_CHARACTER -> InventoryContract.ScannerFailure.INVALID_BARCODE
                    },
                )
            }
        }
    }

    /** Una cancelación antigua no puede liberar una lectura iniciada después de ella. */
    private fun cancelBarcodeLookup() {
        barcodeLookupGeneration += 1
        barcodeLookup?.cancel()
        barcodeLookup = null
        updateState { copy(isBarcodeLookupRunning = false) }
    }

    private fun load() {
        if (uiState.value.failure == InventoryContract.Failure.INVALID_PRODUCT_ID) {
            executeMain { emitEffect(InventoryContract.Effect.CloseInvalidRoute) }
            return
        }
        val state = uiState.value
        val productId = state.productId
        if (productId != null) {
            profitObservation?.cancel()
            profitObservation = null
            observeProductDetail(productId, restart = true)
            return
        }
        when (state.section) {
            InventoryContract.ListSection.STOCK -> {
                profitObservation?.cancel()
                profitObservation = null
                observeInventoryList(restart = true)
            }
            InventoryContract.ListSection.ESTIMATED_PROFIT -> {
                observation?.cancel()
                observation = null
                observeProfits(restart = true)
            }
        }
    }

    private fun observeInventoryList(restart: Boolean = false) {
        if (!restart && observation?.isActive == true) return
        observation?.cancel()
        observation = executeMain {
            updateState {
                copy(
                    isLoading = allItems.isEmpty(),
                    failure = failure.takeIf { it == InventoryContract.Failure.DIAGNOSTIC_FAILED },
                )
            }
            try {
                observeList()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(isLoading = false, failure = InventoryContract.Failure.LOAD_FAILED) }
            }
        }
    }

    private fun observeProductDetail(productId: ProductId, restart: Boolean = false) {
        if (!restart && observation?.isActive == true) return
        observation?.cancel()
        observation = executeMain {
            updateState {
                copy(
                    isLoading = detail == null,
                    failure = failure.takeIf { it == InventoryContract.Failure.DIAGNOSTIC_FAILED },
                )
            }
            try {
                observeDetail(productId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(isLoading = false, failure = InventoryContract.Failure.LOAD_FAILED) }
            }
        }
    }

    private suspend fun observeList() {
        observeInventory().collectLatest { inventory ->
            var previousReport = uiState.value.diagnosticReport
            var prepared = prepareInventory(inventory, previousReport)
            while (true) {
                val state = uiState.value
                if (state.diagnosticReport !== previousReport) {
                    previousReport = state.diagnosticReport
                    prepared = prepareInventory(inventory, previousReport, prepared.searchIndex)
                    continue
                }
                val query = state.query
                val filtered = withContext(dispatcherProvider.default) {
                    prepared.items.filteredInventory(query, prepared.terms)
                }
                // Escribir o terminar un diagnóstico mientras Default trabaja no puede
                // restaurar resultados de otra consulta ni sustituir un informe más reciente.
                if (uiState.value.query != query ||
                    uiState.value.diagnosticReport !== previousReport
                ) continue
                cancelSearch()
                inventorySearchIndex = prepared.searchIndex
                updateState {
                    copy(
                        isLoading = false,
                        diagnosticReport = prepared.report,
                        allItems = prepared.items,
                        items = filtered,
                        failure = failure.takeIf {
                            it == InventoryContract.Failure.DIAGNOSTIC_FAILED
                        },
                    )
                }
                break
            }
        }
    }

    private data class PreparedInventory(
        val items: List<InventoryReadItem>,
        val searchIndex: InventorySearchIndex,
        val report: InventoryDiagnosticReport?,
    ) {
        val terms: Map<ProductId, List<String>> get() = searchIndex.termsByProduct
    }

    private suspend fun prepareInventory(
        inventory: List<InventoryReadItem>,
        report: InventoryDiagnosticReport?,
        previousIndex: InventorySearchIndex = inventorySearchIndex,
    ): PreparedInventory = withContext(dispatcherProvider.default) {
        val currentReport = report?.takeIf { it.matchesSnapshot(inventory) }
        val decorated = inventory.withDiagnosticAlerts(currentReport)
        PreparedInventory(
            items = decorated,
            searchIndex = previousIndex.prepare(decorated),
            report = currentReport,
        )
    }

    private suspend fun observeDetail(productId: ProductId) {
        observeProduct(productId).collect { product ->
            updateState {
                copy(
                    isLoading = false,
                    detail = product,
                    failure = if (product == null) {
                        InventoryContract.Failure.PRODUCT_NOT_FOUND
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun updateSearch(query: String) {
        val state = uiState.value
        if (state.productId != null) return
        executeMain {
            val safeQuery = query.take(MAX_QUERY_LENGTH)
            savedStateHandle[QUERY_KEY] = safeQuery
            updateState {
                copy(query = safeQuery, scannerFailure = null)
            }
            refreshSearch()
        }
    }

    private fun cancelSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
    }

    /** El texto se publica en Main; recorrer productos queda fuera del hilo de dibujo. */
    private fun refreshSearch() {
        cancelSearch()
        val generation = searchGeneration
        val snapshot = uiState.value
        val inventoryTerms = inventorySearchTermsByProduct
        val profitTerms = profitSearchTermsByProduct
        searchJob = executeMain {
            when (snapshot.section) {
                InventoryContract.ListSection.STOCK -> {
                    val filtered = withContext(dispatcherProvider.default) {
                        snapshot.allItems.filteredInventory(snapshot.query, inventoryTerms)
                    }
                    val current = uiState.value
                    if (generation == searchGeneration && current.query == snapshot.query &&
                        current.section == snapshot.section && current.allItems === snapshot.allItems
                    ) updateState { copy(items = filtered) }
                }
                InventoryContract.ListSection.ESTIMATED_PROFIT -> {
                    val filtered = withContext(dispatcherProvider.default) {
                        snapshot.allProfits.filteredProfits(snapshot.query, profitTerms)
                    }
                    val current = uiState.value
                    if (generation == searchGeneration && current.query == snapshot.query &&
                        current.section == snapshot.section && current.allProfits === snapshot.allProfits
                    ) updateState { copy(profits = filtered) }
                }
            }
        }
    }

    private fun changeSection(section: InventoryContract.ListSection) {
        if (uiState.value.productId != null) return
        executeMain {
            if (uiState.value.section == section) return@executeMain
            cancelBarcodeLookup()
            lastBarcodeForRetry = null
            savedStateHandle[SECTION_KEY] = section.name
            savedStateHandle[INPUT_MODE_KEY] = InventoryContract.InputMode.SEARCH.name
            updateState {
                when (section) {
                    InventoryContract.ListSection.STOCK -> copy(
                        section = section,
                        inputMode = InventoryContract.InputMode.SEARCH,
                        scannerActive = false,
                        scannerFailure = null,
                        isLoading = allItems.isEmpty(),
                        isProfitLoading = false,
                    )
                    InventoryContract.ListSection.ESTIMATED_PROFIT -> copy(
                        section = section,
                        inputMode = InventoryContract.InputMode.SEARCH,
                        scannerActive = false,
                        scannerFailure = null,
                        isLoading = false,
                        isProfitLoading = allProfits.isEmpty(),
                    )
                }
            }
            refreshSearch()
            when (section) {
                InventoryContract.ListSection.STOCK -> {
                    profitObservation?.cancel()
                    profitObservation = null
                    observeInventoryList()
                }
                InventoryContract.ListSection.ESTIMATED_PROFIT -> {
                    observation?.cancel()
                    observation = null
                    observeProfits()
                }
            }
        }
    }

    private fun changeInputMode(mode: InventoryContract.InputMode) {
        val state = uiState.value
        if (
            state.productId != null || state.section != InventoryContract.ListSection.STOCK ||
            state.inputMode == mode
        ) {
            return
        }
        executeMain {
            cancelBarcodeLookup()
            lastBarcodeForRetry = null
            savedStateHandle[INPUT_MODE_KEY] = mode.name
            if (mode == InventoryContract.InputMode.SCANNER) {
                savedStateHandle[QUERY_KEY] = ""
                cancelSearch()
            }
            updateState {
                copy(
                    inputMode = mode,
                    scannerActive = false,
                    isBarcodeLookupRunning = false,
                    scannerFailure = null,
                    query = if (mode == InventoryContract.InputMode.SCANNER) "" else query,
                    items = if (mode == InventoryContract.InputMode.SCANNER) {
                        allItems.visibleInventory()
                    } else items,
                )
            }
        }
    }

    private fun lookupBarcode(rawValue: String) {
        if (!uiState.value.canRouteScannerInput || barcodeLookup?.isActive == true) return
        val barcode = BarcodeValue.parse(rawValue)
        if (barcode == null) {
            executeMain {
                lastBarcodeForRetry = null
                updateState {
                    copy(
                        scannerFailure = InventoryContract.ScannerFailure.INVALID_BARCODE,
                        lastUnmatchedBarcode = null,
                    )
                }
            }
            return
        }
        val generation = ++barcodeLookupGeneration
        lastBarcodeForRetry = barcode.value
        barcodeLookup = executeIo(
            before = {
                updateState {
                    copy(isBarcodeLookupRunning = true, scannerFailure = null, lastUnmatchedBarcode = null)
                }
            },
            operation = {
                val businessId = configuration.current().activeBusinessId
                    ?: return@executeIo BarcodeLookupResult.NoActiveBusiness
                products.findByBarcode(businessId, barcode.value)
                    ?.let { BarcodeLookupResult.Found(it.productId, businessId) }
                    ?: BarcodeLookupResult.NotFound(businessId)
            },
            onSuccess = success@{ result ->
                if (generation != barcodeLookupGeneration) return@success
                val lookupBusinessId = when (result) {
                    is BarcodeLookupResult.Found -> result.businessId
                    is BarcodeLookupResult.NotFound -> result.businessId
                    BarcodeLookupResult.NoActiveBusiness -> null
                }
                val currentBusinessId = withContext(dispatcherProvider.io) {
                    configuration.current().activeBusinessId
                }
                if (generation != barcodeLookupGeneration) return@success
                barcodeLookup = null
                if (lookupBusinessId != currentBusinessId) {
                    updateState {
                        copy(
                            isBarcodeLookupRunning = false,
                            scannerFailure = if (currentBusinessId == null) {
                                InventoryContract.ScannerFailure.NO_ACTIVE_BUSINESS
                            } else InventoryContract.ScannerFailure.LOOKUP_FAILED,
                        )
                    }
                    return@success
                }
                when (result) {
                    is BarcodeLookupResult.Found -> {
                        updateState {
                            copy(
                                isBarcodeLookupRunning = false,
                                scannerFailure = null,
                                lastUnmatchedBarcode = null,
                                registrationNavigationPending = isRegisteringProducts,
                            )
                        }
                        if (uiState.value.isRegisteringProducts) {
                            // El catálogo resuelve el código al mismo producto y abre su editor.
                            emitEffect(InventoryContract.Effect.OpenProductCreation(barcode.value))
                        } else {
                            emitEffect(InventoryContract.Effect.OpenProduct(result.productId))
                        }
                    }
                    BarcodeLookupResult.NoActiveBusiness -> updateState {
                        copy(
                            isBarcodeLookupRunning = false,
                            scannerFailure = InventoryContract.ScannerFailure.NO_ACTIVE_BUSINESS,
                        )
                    }
                    is BarcodeLookupResult.NotFound -> {
                        if (uiState.value.isRegisteringProducts) {
                            updateState {
                                copy(
                                    isBarcodeLookupRunning = false,
                                    scannerFailure = null,
                                    lastUnmatchedBarcode = null,
                                    registrationNavigationPending = true,
                                )
                            }
                            emitEffect(InventoryContract.Effect.OpenProductCreation(barcode.value))
                        } else {
                            updateState {
                                copy(
                                    isBarcodeLookupRunning = false,
                                    scannerFailure = InventoryContract.ScannerFailure.BARCODE_NOT_FOUND,
                                    lastUnmatchedBarcode = barcode.value,
                                )
                            }
                        }
                    }
                }
            },
            onFailure = {
                if (generation == barcodeLookupGeneration) {
                    barcodeLookup = null
                    updateState {
                        copy(
                            isBarcodeLookupRunning = false,
                            scannerFailure = InventoryContract.ScannerFailure.LOOKUP_FAILED,
                        )
                    }
                }
            },
            onCancellation = {
                if (generation == barcodeLookupGeneration) {
                    barcodeLookup = null
                    updateState { copy(isBarcodeLookupRunning = false) }
                }
            },
        )
    }

    private fun observeProfits(restart: Boolean = false) {
        if (!restart && profitObservation?.isActive == true) return
        profitObservation?.cancel()
        profitObservation = executeMain {
            updateState { copy(isProfitLoading = allProfits.isEmpty(), profitFailure = null) }
            try {
                val currency = withContext(dispatcherProvider.io) { configuration.current().currency }
                observeProductProfits().collectLatest { reported ->
                    val terms = withContext(dispatcherProvider.default) {
                        val context = currentCoroutineContext()
                        buildMap {
                            reported.forEachIndexed { index, profit ->
                                if (index % 64 == 0) context.ensureActive()
                                put(profit.productId, profit.profitSearchTerms())
                            }
                        }
                    }
                    while (true) {
                        val query = uiState.value.query
                        val filtered = withContext(dispatcherProvider.default) {
                            reported.filteredProfits(query, terms)
                        }
                        if (uiState.value.query != query) continue
                        cancelSearch()
                        profitSearchTermsByProduct = terms
                        updateState {
                            val restoredEditor = salePriceEditor ?: restoredSalePriceEditor(
                                savedStateHandle = savedStateHandle,
                                profits = reported,
                                currency = currency,
                            )
                            copy(
                                isProfitLoading = false,
                                allProfits = reported,
                                profits = filtered,
                                salePriceEditor = restoredEditor,
                                profitFailure = profitFailure?.takeUnless {
                                    it == InventoryContract.ProfitFailure.LOAD_FAILED
                                },
                            )
                        }
                        break
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState {
                    copy(
                        isProfitLoading = false,
                        profitFailure = InventoryContract.ProfitFailure.LOAD_FAILED,
                    )
                }
            }
        }
    }

    private fun openSalePriceEditor(productId: ProductId) {
        if (!uiState.value.canStartProductAction) return
        val profit = uiState.value.allProfits.firstOrNull { it.productId == productId } ?: return
        if (uiState.value.isSavingSalePrice) return
        executeMain {
            val currency = withContext(dispatcherProvider.io) { configuration.current().currency }
            val value = profit.salePrice
                ?.takeIf { it.currency == currency }
                ?.toMajor()
                ?.stripTrailingZeros()
                ?.toPlainString()
                .orEmpty()
            savedStateHandle[PRICE_PRODUCT_KEY] = productId.value
            savedStateHandle[PRICE_VALUE_KEY] = value
            updateState {
                copy(
                    salePriceEditor = InventoryContract.SalePriceEditor(
                        productId = productId,
                        productName = profit.productName,
                        expectedVersion = profit.productVersion,
                        currency = currency,
                        value = value,
                    ),
                    profitFailure = null,
                )
            }
        }
    }

    private fun updateSalePrice(value: String) {
        executeMain {
            val editor = uiState.value.salePriceEditor ?: return@executeMain
            val safeValue = value.take(MAX_PRICE_LENGTH)
            savedStateHandle[PRICE_VALUE_KEY] = safeValue
            updateState {
                copy(
                    salePriceEditor = editor.copy(value = safeValue, submitAttempted = false),
                    profitFailure = null,
                )
            }
        }
    }

    private fun saveSalePrice() {
        val state = uiState.value
        val editor = state.salePriceEditor ?: return
        if (state.isSavingSalePrice) return
        val price = editor.parsedPrice
        if (price == null) {
            executeMain {
                updateState {
                    copy(
                        salePriceEditor = editor.copy(submitAttempted = true),
                        profitFailure = InventoryContract.ProfitFailure.INVALID_PRICE,
                    )
                }
            }
            return
        }
        executeIo(
            before = { updateState { copy(isSavingSalePrice = true, profitFailure = null) } },
            operation = {
                updateProductSalePrice(
                    productId = editor.productId,
                    expectedVersion = editor.expectedVersion,
                    salePrice = price,
                )
            },
            onSuccess = { result ->
                when (result) {
                    is ProductSalePriceMutationResult.Updated,
                    is ProductSalePriceMutationResult.Unchanged,
                    -> {
                        clearSavedPriceEditor()
                        updateState {
                            copy(
                                isSavingSalePrice = false,
                                salePriceEditor = null,
                                profitFailure = null,
                            )
                        }
                    }
                    ProductSalePriceMutationResult.InvalidPrice -> updatePriceFailure(
                        InventoryContract.ProfitFailure.INVALID_PRICE,
                    )
                    ProductSalePriceMutationResult.Stale -> updatePriceFailure(
                        InventoryContract.ProfitFailure.STALE_PRICE,
                    )
                    is ProductSalePriceMutationResult.CurrencyMismatch -> updatePriceFailure(
                        InventoryContract.ProfitFailure.CURRENCY_MISMATCH,
                    )
                    ProductSalePriceMutationResult.NoActiveBusiness,
                    ProductSalePriceMutationResult.NotFound,
                    ProductSalePriceMutationResult.Inactive,
                    -> updatePriceFailure(InventoryContract.ProfitFailure.PRODUCT_UNAVAILABLE)
                }
            },
            onFailure = {
                updatePriceFailure(InventoryContract.ProfitFailure.SAVE_FAILED)
            },
        )
    }

    private fun updatePriceFailure(failure: InventoryContract.ProfitFailure) {
        updateState { copy(isSavingSalePrice = false, profitFailure = failure) }
    }

    private fun dismissSalePriceEditor() {
        if (uiState.value.isSavingSalePrice) return
        executeMain {
            clearSavedPriceEditor()
            updateState { copy(salePriceEditor = null, profitFailure = null) }
        }
    }

    private fun clearSavedPriceEditor() {
        savedStateHandle.remove<String>(PRICE_PRODUCT_KEY)
        savedStateHandle.remove<String>(PRICE_VALUE_KEY)
    }

    private fun diagnose() {
        if (uiState.value.isDiagnosing || uiState.value.productId != null) return
        val startingSnapshot = uiState.value.allItems
        executeIo(
            before = {
                updateState { copy(isDiagnosing = true, failure = null) }
            },
            operation = diagnoseInventory::invoke,
            onSuccess = { report ->
                while (true) {
                    val snapshot = uiState.value
                    val terms = inventorySearchTermsByProduct
                    val prepared = withContext(dispatcherProvider.default) {
                        val currentReport = report.takeIf {
                            snapshot.allItems === startingSnapshot || it.matchesSnapshot(snapshot.allItems)
                        }
                        val decorated = snapshot.allItems.withDiagnosticAlerts(currentReport)
                        Triple(currentReport, decorated, decorated.filteredInventory(snapshot.query, terms))
                    }
                    if (uiState.value.allItems !== snapshot.allItems || uiState.value.query != snapshot.query
                    ) continue
                    if (uiState.value.section == InventoryContract.ListSection.STOCK) cancelSearch()
                    updateState {
                        copy(
                            isDiagnosing = false,
                            diagnosticReport = prepared.first,
                            allItems = prepared.second,
                            items = prepared.third,
                            failure = null,
                        )
                    }
                    break
                }
            },
            onFailure = {
                updateState {
                    copy(
                        isDiagnosing = false,
                        failure = InventoryContract.Failure.DIAGNOSTIC_FAILED,
                    )
                }
            },
        )
    }

    private companion object {
        const val QUERY_KEY = "inventory.query"
        const val SECTION_KEY = "inventory.section"
        const val INPUT_MODE_KEY = "inventory.inputMode"
        const val PRICE_PRODUCT_KEY = "inventory.price.product"
        const val PRICE_VALUE_KEY = "inventory.price.value"
        const val MAX_QUERY_LENGTH = 200
        const val MAX_PRICE_LENGTH = 64
    }
}

private fun restoredInventoryState(savedStateHandle: SavedStateHandle): InventoryContract.State {
    // Cada apertura vuelve al catálogo activo. Una confirmación antigua nunca se restaura
    // ni bloquea el lector; retirar o restaurar requiere una nueva revisión explícita.
    savedStateHandle.remove<Any>("inventory.productFilter")
    savedStateHandle.remove<Any>("inventory.productAction")
    val rawProductId = savedStateHandle.get<String>(RouteArgumentKeys.PRODUCT_ID)
    val productId = ProductId.parse(rawProductId)
    val section = savedStateHandle.get<String>("inventory.section")
        ?.let { value ->
            runCatching { InventoryContract.ListSection.valueOf(value) }.getOrNull()
        }
        ?: InventoryContract.ListSection.STOCK
    val restoredInputMode = savedStateHandle.get<String>("inventory.inputMode")
        ?.let { value ->
            runCatching { InventoryContract.InputMode.valueOf(value) }.getOrNull()
        }
        ?: InventoryContract.InputMode.SEARCH
    val inputMode = restoredInputMode.takeIf {
        section == InventoryContract.ListSection.STOCK
    } ?: InventoryContract.InputMode.SEARCH
    return InventoryContract.State(
        productId = productId,
        query = if (inputMode == InventoryContract.InputMode.SCANNER) {
            ""
        } else {
            savedStateHandle.get<String>("inventory.query").orEmpty().take(200)
        },
        section = section,
        inputMode = inputMode,
        failure = if (rawProductId != null && productId == null) {
            InventoryContract.Failure.INVALID_PRODUCT_ID
        } else {
            null
        },
    )
}

private sealed interface BarcodeLookupResult {
    data class Found(val productId: ProductId, val businessId: BusinessId) : BarcodeLookupResult
    data object NoActiveBusiness : BarcodeLookupResult
    data class NotFound(val businessId: BusinessId) : BarcodeLookupResult
}

private fun restoredSalePriceEditor(
    savedStateHandle: SavedStateHandle,
    profits: List<ProductProfit>,
    currency: com.facturastock.app.domain.model.CurrencyCode,
): InventoryContract.SalePriceEditor? {
    val productId = ProductId.parse(savedStateHandle.get<String>("inventory.price.product"))
        ?: return null
    val profit = profits.firstOrNull { it.productId == productId } ?: return null
    return InventoryContract.SalePriceEditor(
        productId = productId,
        productName = profit.productName,
        expectedVersion = profit.productVersion,
        currency = currency,
        value = savedStateHandle.get<String>("inventory.price.value").orEmpty().take(64),
    )
}

private suspend fun List<InventoryReadItem>.filteredInventory(
    query: String,
    searchTermsByProduct: Map<ProductId, List<String>>,
): List<InventoryReadItem> {
    val needle = query.inventorySearchKey()
    if (needle.length < 2) return visibleInventory()
    val context = currentCoroutineContext()
    return filterIndexed { index, item ->
        if (index % 64 == 0) context.ensureActive()
        InventoryDataAlert.ARCHIVED_PRODUCT !in item.allAlerts &&
            (needle.length < 2 || searchTermsByProduct[item.productId]?.any { term -> term.contains(needle) } == true)
    }
}

private fun List<InventoryReadItem>.visibleInventory(): List<InventoryReadItem> =
    if (all { InventoryDataAlert.ARCHIVED_PRODUCT !in it.allAlerts }) this
    else filter { InventoryDataAlert.ARCHIVED_PRODUCT !in it.allAlerts }

private suspend fun List<ProductProfit>.filteredProfits(
    query: String,
    searchTermsByProduct: Map<ProductId, List<String>>,
): List<ProductProfit> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return this
    val context = currentCoroutineContext()
    return filterIndexed { index, profit ->
        if (index % 64 == 0) context.ensureActive()
        searchTermsByProduct[profit.productId]?.any { term -> term.contains(needle) } == true
    }
}

private fun ProductProfit.profitSearchTerms(): List<String> = listOf(
    productName.lowercase(Locale.ROOT),
    sku.orEmpty().lowercase(Locale.ROOT),
)

private suspend fun List<InventoryReadItem>.withDiagnosticAlerts(
    report: InventoryDiagnosticReport?,
): List<InventoryReadItem> {
    val context = currentCoroutineContext()
    val divergentProducts = buildSet {
        report?.positions?.forEachIndexed { index, position ->
            if (index % 64 == 0) context.ensureActive()
            if (!position.matches) add(position.productId)
        }
    }
    var changed: MutableList<InventoryReadItem>? = null
    forEachIndexed { index, item ->
        if (index % 64 == 0) context.ensureActive()
        val divergent = item.productId in divergentProducts
        if (divergent != (InventoryDataAlert.PROJECTION_DIVERGENCE in item.alerts)) {
            val updated = changed ?: toMutableList().also { changed = it }
            updated[index] = item.copy(
                alerts = if (divergent) item.alerts + InventoryDataAlert.PROJECTION_DIVERGENCE
                else item.alerts - InventoryDataAlert.PROJECTION_DIVERGENCE,
            )
        }
    }
    // InventoryReadItem agrega cantidades y costos al construirse. Una copia sin cambios
    // repetiría esas operaciones para todos los productos en cada emisión de Room.
    return changed ?: this
}

private suspend fun InventoryDiagnosticReport.matchesSnapshot(items: List<InventoryReadItem>): Boolean {
    val context = currentCoroutineContext()
    val current = buildMap {
        items.forEachIndexed { itemIndex, item ->
            if (itemIndex % 64 == 0) context.ensureActive()
            item.positions.forEachIndexed { positionIndex, position ->
                if (positionIndex % 64 == 0) context.ensureActive()
                put(item.productId to position.locationId, position)
            }
        }
    }
    val cached = buildMap {
        positions.forEachIndexed { index, position ->
            if (index % 64 == 0) context.ensureActive()
            if (position.cachedVersion != null) put(position.productId to position.locationId, position)
        }
    }
    if (current.size != cached.size) return false
    var compared = 0
    return cached.all { (key, diagnostic) ->
        if (compared++ % 64 == 0) context.ensureActive()
        val position = current[key] ?: return@all false
        position.version == diagnostic.cachedVersion &&
            position.updatedAt == diagnostic.cachedUpdatedAt
    }
}
