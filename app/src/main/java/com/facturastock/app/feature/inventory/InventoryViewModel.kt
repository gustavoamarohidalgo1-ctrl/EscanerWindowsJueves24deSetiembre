package com.facturastock.app.feature.inventory

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ProductRepository
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
import kotlinx.coroutines.flow.collect
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
    private var inventorySearchTermsByProduct: Map<ProductId, List<String>> = emptyMap()
    private var profitSearchTermsByProduct: Map<ProductId, List<String>> = emptyMap()

    init {
        load()
    }

    override fun onAction(action: InventoryContract.Action) {
        when (action) {
            InventoryContract.Action.Load,
            InventoryContract.Action.Retry,
            -> load()

            is InventoryContract.Action.SearchChanged -> updateSearch(action.query)
            is InventoryContract.Action.SectionChanged -> changeSection(action.section)
            is InventoryContract.Action.InputModeChanged -> changeInputMode(action.mode)
            is InventoryContract.Action.ScannerAvailabilityChanged -> executeMain {
                updateState { copy(scannerActive = action.active) }
            }
            is InventoryContract.Action.BarcodeScanned -> lookupBarcode(action.value)
            is InventoryContract.Action.EditSalePrice -> openSalePriceEditor(action.productId)
            is InventoryContract.Action.SalePriceChanged -> updateSalePrice(action.value)
            InventoryContract.Action.SaveSalePrice -> saveSalePrice()
            InventoryContract.Action.DismissSalePrice -> dismissSalePriceEditor()
            is InventoryContract.Action.ProductSelected -> executeMain {
                emitEffect(InventoryContract.Effect.OpenProduct(action.productId))
            }
            is InventoryContract.Action.OriginPurchaseSelected -> executeMain {
                emitEffect(InventoryContract.Effect.OpenPurchase(action.purchaseId))
            }
            InventoryContract.Action.RunDiagnostic -> diagnose()
            InventoryContract.Action.BackSelected -> executeMain {
                emitEffect(InventoryContract.Effect.Back)
            }
        }
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
        observeInventory().collect { inventory ->
            val currentReport = uiState.value.diagnosticReport?.takeIf {
                it.matchesSnapshot(inventory)
            }
            val decorated = inventory.withDiagnosticAlerts(currentReport)
            inventorySearchTermsByProduct = decorated.associate { item ->
                item.productId to item.inventorySearchTerms()
            }
            updateState {
                copy(
                    isLoading = false,
                    diagnosticReport = currentReport,
                    allItems = decorated,
                    items = decorated.filteredInventory(query, inventorySearchTermsByProduct),
                    failure = failure.takeIf {
                        it == InventoryContract.Failure.DIAGNOSTIC_FAILED
                    },
                )
            }
        }
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
        if (
            state.productId != null ||
            (state.section == InventoryContract.ListSection.STOCK &&
                state.inputMode != InventoryContract.InputMode.SEARCH)
        ) {
            return
        }
        executeMain {
            val safeQuery = query.take(MAX_QUERY_LENGTH)
            savedStateHandle[QUERY_KEY] = safeQuery
            updateState {
                when (section) {
                    InventoryContract.ListSection.STOCK -> copy(
                        query = safeQuery,
                        scannerFailure = null,
                        items = allItems.filteredInventory(
                            safeQuery,
                            inventorySearchTermsByProduct,
                        ),
                    )
                    InventoryContract.ListSection.ESTIMATED_PROFIT -> copy(
                        query = safeQuery,
                        scannerFailure = null,
                        profits = allProfits.filteredProfits(
                            safeQuery,
                            profitSearchTermsByProduct,
                        ),
                    )
                }
            }
        }
    }

    private fun changeSection(section: InventoryContract.ListSection) {
        if (uiState.value.productId != null) return
        barcodeLookup?.cancel()
        barcodeLookup = null
        executeMain {
            if (uiState.value.section == section) return@executeMain
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
                        items = allItems.filteredInventory(query, inventorySearchTermsByProduct),
                    )
                    InventoryContract.ListSection.ESTIMATED_PROFIT -> copy(
                        section = section,
                        inputMode = InventoryContract.InputMode.SEARCH,
                        scannerActive = false,
                        scannerFailure = null,
                        isLoading = false,
                        isProfitLoading = allProfits.isEmpty(),
                        profits = allProfits.filteredProfits(query, profitSearchTermsByProduct),
                    )
                }
            }
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
        barcodeLookup?.cancel()
        barcodeLookup = null
        executeMain {
            savedStateHandle[INPUT_MODE_KEY] = mode.name
            if (mode == InventoryContract.InputMode.SCANNER) {
                savedStateHandle[QUERY_KEY] = ""
            }
            updateState {
                copy(
                    inputMode = mode,
                    scannerActive = false,
                    isBarcodeLookupRunning = false,
                    scannerFailure = null,
                    query = if (mode == InventoryContract.InputMode.SCANNER) "" else query,
                    items = if (mode == InventoryContract.InputMode.SCANNER) allItems else items,
                )
            }
        }
    }

    private fun lookupBarcode(rawValue: String) {
        if (!uiState.value.canRouteScannerInput || barcodeLookup?.isActive == true) return
        val barcode = BarcodeValue.parse(rawValue)
        if (barcode == null) {
            executeMain {
                updateState {
                    copy(scannerFailure = InventoryContract.ScannerFailure.INVALID_BARCODE)
                }
            }
            return
        }
        barcodeLookup = executeIo(
            before = {
                updateState {
                    copy(isBarcodeLookupRunning = true, scannerFailure = null)
                }
            },
            operation = {
                val businessId = configuration.current().activeBusinessId
                    ?: return@executeIo BarcodeLookupResult.NoActiveBusiness
                products.findByBarcode(businessId, barcode.value)
                    ?.let { BarcodeLookupResult.Found(it.productId) }
                    ?: BarcodeLookupResult.NotFound
            },
            onSuccess = { result ->
                barcodeLookup = null
                when (result) {
                    is BarcodeLookupResult.Found -> {
                        updateState {
                            copy(isBarcodeLookupRunning = false, scannerFailure = null)
                        }
                        emitEffect(InventoryContract.Effect.OpenProduct(result.productId))
                    }
                    BarcodeLookupResult.NoActiveBusiness -> updateState {
                        copy(
                            isBarcodeLookupRunning = false,
                            scannerFailure = InventoryContract.ScannerFailure.NO_ACTIVE_BUSINESS,
                        )
                    }
                    BarcodeLookupResult.NotFound -> updateState {
                        copy(
                            isBarcodeLookupRunning = false,
                            scannerFailure = InventoryContract.ScannerFailure.BARCODE_NOT_FOUND,
                        )
                    }
                }
            },
            onFailure = {
                barcodeLookup = null
                updateState {
                    copy(
                        isBarcodeLookupRunning = false,
                        scannerFailure = InventoryContract.ScannerFailure.LOOKUP_FAILED,
                    )
                }
            },
            onCancellation = {
                barcodeLookup = null
                updateState { copy(isBarcodeLookupRunning = false) }
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
                observeProductProfits().collect { reported ->
                    profitSearchTermsByProduct = reported.associate { profit ->
                        profit.productId to profit.profitSearchTerms()
                    }
                    updateState {
                        val restoredEditor = salePriceEditor ?: restoredSalePriceEditor(
                            savedStateHandle = savedStateHandle,
                            profits = reported,
                            currency = currency,
                        )
                        copy(
                            isProfitLoading = false,
                            allProfits = reported,
                            profits = reported.filteredProfits(query, profitSearchTermsByProduct),
                            salePriceEditor = restoredEditor,
                            profitFailure = profitFailure?.takeUnless {
                                it == InventoryContract.ProfitFailure.LOAD_FAILED
                            },
                        )
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
        if (uiState.value.isBarcodeLookupRunning) return
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
        executeIo(
            before = {
                updateState { copy(isDiagnosing = true, failure = null) }
            },
            operation = diagnoseInventory::invoke,
            onSuccess = { report ->
                updateState {
                    val decorated = allItems.withDiagnosticAlerts(report)
                    copy(
                        isDiagnosing = false,
                        diagnosticReport = report,
                        allItems = decorated,
                        items = decorated.filteredInventory(query, inventorySearchTermsByProduct),
                        failure = null,
                    )
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
    data class Found(val productId: ProductId) : BarcodeLookupResult
    data object NoActiveBusiness : BarcodeLookupResult
    data object NotFound : BarcodeLookupResult
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

private fun List<InventoryReadItem>.filteredInventory(
    query: String,
    searchTermsByProduct: Map<ProductId, List<String>>,
): List<InventoryReadItem> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return this
    return filter { item ->
        searchTermsByProduct[item.productId]?.any { term -> term.contains(needle) } == true
    }
}

private fun InventoryReadItem.inventorySearchTerms(): List<String> = buildList {
    add(productName.lowercase(Locale.ROOT))
    add(sku.orEmpty().lowercase(Locale.ROOT))
    add(unitCode.lowercase(Locale.ROOT))
    positions.forEach { position -> add(position.locationName.lowercase(Locale.ROOT)) }
}

private fun List<ProductProfit>.filteredProfits(
    query: String,
    searchTermsByProduct: Map<ProductId, List<String>>,
): List<ProductProfit> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return this
    return filter { profit ->
        searchTermsByProduct[profit.productId]?.any { term -> term.contains(needle) } == true
    }
}

private fun ProductProfit.profitSearchTerms(): List<String> = listOf(
    productName.lowercase(Locale.ROOT),
    sku.orEmpty().lowercase(Locale.ROOT),
)

private fun List<InventoryReadItem>.withDiagnosticAlerts(
    report: InventoryDiagnosticReport?,
): List<InventoryReadItem> {
    val divergentProducts = report?.positions
        ?.filterNot { it.matches }
        ?.mapTo(hashSetOf()) { it.productId }
        .orEmpty()
    return map { item ->
        item.copy(
            alerts = if (item.productId in divergentProducts) {
                item.alerts + InventoryDataAlert.PROJECTION_DIVERGENCE
            } else {
                item.alerts - InventoryDataAlert.PROJECTION_DIVERGENCE
            },
        )
    }
}

private fun InventoryDiagnosticReport.matchesSnapshot(items: List<InventoryReadItem>): Boolean {
    val current = items.flatMap { item ->
        item.positions.map { position ->
            (item.productId to position.locationId) to position
        }
    }.toMap()
    val cached = positions.filter { it.cachedVersion != null }.associateBy {
        it.productId to it.locationId
    }
    if (current.keys != cached.keys) return false
    return cached.all { (key, diagnostic) ->
        val position = current.getValue(key)
        position.version == diagnostic.cachedVersion &&
            position.updatedAt == diagnostic.cachedUpdatedAt
    }
}
