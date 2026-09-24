package com.facturastock.app.feature.sales

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.WeightSaleCalculator
import com.facturastock.app.domain.model.SaleCart
import com.facturastock.app.domain.model.SaleCartLine
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.calculateSaleLineTotal
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.CreateSaleCartResult
import com.facturastock.app.domain.repository.InventoryReadRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SaleBarcodeRecoveryExpectation
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.usecase.CheckoutSaleUseCase
import com.facturastock.app.domain.usecase.CreateSaleCartUseCase
import com.facturastock.app.domain.usecase.ObserveSaleCartUseCase
import com.facturastock.app.domain.usecase.ProductMatchCandidate
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.domain.usecase.RemoveSaleCartLineUseCase
import com.facturastock.app.domain.usecase.SaveProductCatalogUseCase
import com.facturastock.app.domain.usecase.SaveSaleCartLineUseCase
import com.facturastock.app.domain.usecase.BarcodeSimilarity
import com.facturastock.app.domain.usecase.findSuspiciousExactBarcodeMatches
import com.facturastock.app.domain.usecase.requiresSuspiciousExactBarcodeReview
import com.facturastock.app.domain.usecase.findAutomaticBarcodeRecovery
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.UUID
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SalesViewModel
    @Inject
    constructor(
        private val configuration: AppConfigurationRepository,
        private val products: ProductRepository,
        private val inventoryReads: InventoryReadRepository,
        private val createCart: CreateSaleCartUseCase,
        private val observeCart: ObserveSaleCartUseCase,
        private val saveLine: SaveSaleCartLineUseCase,
        private val removeLine: RemoveSaleCartLineUseCase,
        private val checkout: CheckoutSaleUseCase,
        private val saveProduct: SaveProductCatalogUseCase,
        private val productMatching: ProductMatchingUseCase,
        dispatcherProvider: DispatcherProvider,
        private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) : UdfViewModel<SalesContract.State, SalesContract.Action, SalesContract.Effect>(
            initialState = restoredSalesState(savedStateHandle),
            dispatcherProvider = dispatcherProvider,
        ) {
        private data class CatalogInventorySnapshot(
            val businessId: BusinessId? = null,
            val currency: CurrencyCode = CurrencyCode.of("PEN"),
            val productsById: Map<ProductId, Product> = emptyMap(),
            val inventoryById: Map<ProductId, InventoryReadItem> = emptyMap(),
            val availableProductOptionsByProduct: Map<ProductId, List<SalesContract.ProductOption>> = emptyMap(),
            val orderedAvailableProducts: List<SalesContract.ProductOption> = emptyList(),
        )

        private data class CatalogContext(
            val businessId: BusinessId?,
            val currency: CurrencyCode,
        )

        private data class CatalogInventoryRows(
            val context: CatalogContext,
            val products: Map<ProductId, Product>,
            val inventory: List<InventoryReadItem>,
        )

        private data class NameSearchSnapshot(
            val businessId: BusinessId,
            val query: String,
            val candidates: List<ProductMatchCandidate>,
        )

        private class NameSearchOptions(
            val snapshot: NameSearchSnapshot,
            val catalog: Map<ProductId, List<SalesContract.ProductOption>>,
            val options: List<SalesContract.ProductOption>,
        )

        private data class QueuedBarcode(
            val value: String,
            val businessId: BusinessId,
            val saleId: SaleId,
            val generation: Long,
        )

        private data class PendingBarcodeRecovery(
            val scan: QueuedBarcode,
            val productId: ProductId,
            val expectation: SaleBarcodeRecoveryExpectation,
        )

        private var pendingBarcodeRecovery: PendingBarcodeRecovery? = null
        private val operationMutex = Mutex()
        private val initialCatalogReady = CompletableDeferred<Unit>()
        private val pendingBarcodes = ArrayDeque<QueuedBarcode>()
        private var activeBarcode: QueuedBarcode? = null
        private val focusedTextInputs = mutableSetOf<String>()
        private var barcodeProcessingJob: Job? = null
        private var barcodeGeneration = 0L
        private var oneShotMutationPending = false
        private var checkoutRequestPending = false
        private var registrationCompletionJob: Job? = null
        private var backRequested = false
        private val lineEditJobs = mutableMapOf<String, Job>()
        private val quantityInputs = mutableMapOf<String, String>()
        private val priceInputs = mutableMapOf<String, String>()
        private var cartObservation: Job? = null
        private var cartRecovery: Job? = null
        private var catalogObservation: Job? = null
        private var nameSearchJob: Job? = null
        private var nameSearchSnapshot: NameSearchSnapshot? = null

        /** Opciones ya proyectadas de la última búsqueda: cada tecla no vuelve a copiar la lista. */
        private var nameSearchOptions: NameSearchOptions? = null
        private var deferredContextSnapshot: CatalogInventorySnapshot? = null
        private var activeBusinessId: BusinessId? = null
        private var configuredCurrency: CurrencyCode = CurrencyCode.of("PEN")
        private var domainCart: SaleCart? = null
        private var productsById: Map<ProductId, Product> = emptyMap()
        private var inventoryById: Map<ProductId, InventoryReadItem> = emptyMap()
        private var availableProductOptionsByProduct:
            Map<ProductId, List<SalesContract.ProductOption>> = emptyMap()
        private var orderedAvailableProducts: List<SalesContract.ProductOption> = emptyList()
        private var allowEntryKindSelection = true

        init {
            observeCatalogAndInventory()
            openCart()
            restoredRegistrationResult(savedStateHandle)?.let(::finishProductRegistration)
        }

        override fun onAction(action: SalesContract.Action) {
            // La liberación de foco/sesión también debe funcionar durante diálogos y navegación.
            when (action) {
                is SalesContract.Action.ProductRegistrationFinished -> {
                    finishProductRegistration(action.result)
                    return
                }
                is SalesContract.Action.InitializeEntry -> {
                    initializeEntry(action)
                    return
                }

                is SalesContract.Action.TextInputFocusChanged -> {
                    if (action.focused) {
                        focusedTextInputs.add(action.fieldId)
                    } else {
                        focusedTextInputs.remove(action.fieldId)
                    }
                    updateState { copy(isTextInputFocused = focusedTextInputs.isNotEmpty()) }
                    return
                }

                SalesContract.Action.ScannerSessionStopped -> {
                    stopBarcodeSession()
                    return
                }

                else -> {
                    Unit
                }
            }
            if (backRequested && action !is SalesContract.Action.ScannerAvailabilityChanged) return
            if (uiState.value.weightSaleEditor != null &&
                action !is SalesContract.Action.WeightEntryModeChanged &&
                action !is SalesContract.Action.WeightAmountChanged &&
                action !is SalesContract.Action.WeightQuantityChanged &&
                action !is SalesContract.Action.WeightSaleConfirmed &&
                action !is SalesContract.Action.WeightSaleDismissed &&
                action !is SalesContract.Action.ScannerAvailabilityChanged &&
                action !is SalesContract.Action.BackSelected &&
                action !is SalesContract.Action.StepBackSelected
            ) return
            if (uiState.value.productRegistration != null &&
                action !is SalesContract.Action.ScannerAvailabilityChanged &&
                action !is SalesContract.Action.BackSelected &&
                action !is SalesContract.Action.StepBackSelected &&
                action !is SalesContract.Action.Retry &&
                action !is SalesContract.Action.RetryCatalog &&
                action !is SalesContract.Action.RetryCart
            ) return
            if (
                deferredContextSnapshot != null &&
                action !is SalesContract.Action.QuantityChanged &&
                action !is SalesContract.Action.UnitPriceChanged &&
                action !is SalesContract.Action.QuantityIncremented &&
                action !is SalesContract.Action.QuantityDecremented &&
                action !is SalesContract.Action.Retry &&
                action !is SalesContract.Action.RetryCatalog &&
                action !is SalesContract.Action.RetryCart &&
                action !is SalesContract.Action.RetrySearch &&
                action !is SalesContract.Action.EntryKindChanged &&
                action !is SalesContract.Action.ModeChanged &&
                action !is SalesContract.Action.OpenDebtorsSelected &&
                action !is SalesContract.Action.BackSelected &&
                action !is SalesContract.Action.StepBackSelected &&
                action !is SalesContract.Action.DiscardEditsConfirmed &&
                action !is SalesContract.Action.DiscardEditsDismissed &&
                action !is SalesContract.Action.ScannerAvailabilityChanged
            ) {
                return
            }
            // El primer toque fija la intención; ni otro toque ni una edición tardía pueden
            // modificarla mientras se registra, aunque Main todavía no haya ejecutado la mutación.
            if (checkoutRequestPending &&
                action !is SalesContract.Action.ScannerAvailabilityChanged &&
                action !is SalesContract.Action.BackSelected
            ) return
            if (
                uiState.value.discardEditsReview &&
                action !is SalesContract.Action.DiscardEditsConfirmed &&
                action !is SalesContract.Action.DiscardEditsDismissed &&
                action !is SalesContract.Action.ScannerAvailabilityChanged
            ) {
                return
            }
            if (uiState.value.isCheckoutPending && action !is SalesContract.Action.CheckoutRequested &&
                action !is SalesContract.Action.BackSelected && action !is SalesContract.Action.StepBackSelected &&
                action !is SalesContract.Action.Retry && action !is SalesContract.Action.ScannerAvailabilityChanged
            ) {
                return
            }
            when (action) {
                is SalesContract.Action.RegisterProductRequested -> requestProductRegistration(action.scannedBarcode)
                is SalesContract.Action.ProductRegistrationFinished -> Unit
                SalesContract.Action.Retry -> {
                    retryFailedOperation()
                }

                SalesContract.Action.RetryCatalog -> {
                    observeCatalogAndInventory()
                }

                SalesContract.Action.RetryCart -> {
                    retryCartObservation()
                }

                SalesContract.Action.RetrySearch -> {
                    retrySearch()
                }

                is SalesContract.Action.EntryKindChanged -> {
                    executeMain {
                        clearEntryTransientState()
                        updateState {
                            copy(
                                entryKind = action.kind,
                                entryStep = if (unifiedInput) SalesContract.EntryStep.SELL else SalesContract.EntryStep.SELECT_MODE,
                                mode = if (unifiedInput) SalesContract.EntryMode.SCANNER else mode,
                                debtorNameInput =
                                    if (action.kind == SalesContract.EntryKind.CASH) {
                                        ""
                                    } else {
                                        debtorNameInput
                                    },
                                failure =
                                    if (failure == SalesContract.Failure.INVALID_CREDIT_TERMS) {
                                        null
                                    } else {
                                        failure
                                    },
                            )
                        }
                        saveEntryState()
                    }
                }

                is SalesContract.Action.DebtorNameChanged -> {
                    executeMain {
                        updateState {
                            copy(
                                debtorNameInput =
                                    action.value.take(
                                        SalesContract.MAX_DEBTOR_NAME_LENGTH + 1,
                                    ),
                            )
                        }
                    }
                }

                is SalesContract.Action.ModeChanged -> {
                    executeMain {
                        clearEntryTransientState()
                        updateState {
                            copy(
                                mode = if (unifiedInput) SalesContract.EntryMode.SCANNER else action.mode,
                                entryStep = SalesContract.EntryStep.SELL,
                                pendingLocations = emptyList(),
                                pendingRecoveredBarcode = null,
                                failure = null,
                            )
                        }
                        saveEntryState()
                    }
                }

                is SalesContract.Action.ScannerAvailabilityChanged -> {
                    executeMain {
                        updateState {
                            copy(
                                scannerActive =
                                    action.active && entryStep == SalesContract.EntryStep.SELL &&
                                        mode == SalesContract.EntryMode.SCANNER,
                            )
                        }
                    }
                }

                is SalesContract.Action.ScannerReadFailed -> {
                    executeMain {
                        updateState { copy(scannerFailure = action.failure) }
                    }
                }

                SalesContract.Action.ScannerReadReset -> {
                    executeMain {
                        if (!uiState.value.canRouteScannerInput) return@executeMain
                        updateState { copy(scannerFailure = null) }
                    }
                }

                is SalesContract.Action.InitializeEntry,
                is SalesContract.Action.TextInputFocusChanged,
                SalesContract.Action.ScannerSessionStopped,
                -> {
                    Unit
                }

                // Procesados antes de las restricciones de acciones.
                is SalesContract.Action.BarcodeScanned -> {
                    handleBarcode(action.value)
                }

                is SalesContract.Action.BarcodeSuggestionSelected -> {
                    selectBarcodeSuggestion(action)
                }

                is SalesContract.Action.SearchChanged -> {
                    updateQuery(action.value)
                }

                is SalesContract.Action.ProductSelected -> {
                    selectProduct(action.productId, action.locationId)
                }

                is SalesContract.Action.EditWeightSale -> {
                    val line = domainCart?.lines?.firstOrNull { it.saleLineId.value == action.lineId }
                    if (line != null && line.isWeightSaleLine()) openWeightSale(weightOptionForLine(line))
                }

                is SalesContract.Action.WeightEntryModeChanged -> updateWeightEditor { editor ->
                    when (action.mode) {
                        SalesContract.WeightEntryMode.AMOUNT -> editor.copy(
                            mode = action.mode,
                            amountInput = editor.total?.toMajor()?.toPlainString() ?: editor.amountInput,
                            preservedQuantity = editor.quantity,
                        )
                        SalesContract.WeightEntryMode.QUANTITY -> editor.copy(
                            mode = action.mode,
                            quantityInput = editor.quantity?.value?.toPlainString() ?: editor.quantityInput,
                        )
                    }
                }

                is SalesContract.Action.WeightAmountChanged -> updateWeightEditor {
                    it.copy(amountInput = action.value.take(MAX_DECIMAL_LENGTH))
                }

                is SalesContract.Action.WeightQuantityChanged -> updateWeightEditor {
                    it.copy(quantityInput = action.value.take(MAX_DECIMAL_LENGTH))
                }

                SalesContract.Action.WeightSaleConfirmed -> confirmWeightSale()

                SalesContract.Action.WeightSaleDismissed -> {
                    if (!uiState.value.isMutating) updateState { copy(weightSaleEditor = null) }
                }

                is SalesContract.Action.LocationSelected -> {
                    selectPendingLocation(action.productId, action.locationId)
                }

                SalesContract.Action.LocationSelectionDismissed -> {
                    executeMain {
                        pendingBarcodeRecovery = null
                        updateState { copy(pendingLocations = emptyList(), pendingRecoveredBarcode = null) }
                    }
                }

                SalesContract.Action.AssociationDismissed -> {
                    executeMain {
                        clearPendingAssociation()
                    }
                }

                SalesContract.Action.BarcodeReplacementConfirmed -> {
                    confirmBarcodeReplacement()
                }

                SalesContract.Action.BarcodeReplacementDismissed -> {
                    executeMain {
                        updateState { copy(pendingReplacement = null) }
                    }
                }

                is SalesContract.Action.QuantityChanged -> {
                    updateLineInput(action.lineId, quantity = action.value)
                }

                is SalesContract.Action.UnitPriceChanged -> {
                    updateLineInput(action.lineId, price = action.value)
                }

                is SalesContract.Action.QuantityIncremented -> {
                    adjustQuantity(action.lineId, BigDecimal.ONE)
                }

                is SalesContract.Action.QuantityDecremented -> {
                    adjustQuantity(action.lineId, BigDecimal.ONE.negate())
                }

                is SalesContract.Action.LineRemoved -> {
                    removeCartLine(action.lineId)
                }

                SalesContract.Action.CheckoutRequested -> checkoutCurrentCart()

                SalesContract.Action.DiscardEditsConfirmed -> {
                    discardPendingEditsAndGoBack()
                }

                SalesContract.Action.DiscardEditsDismissed -> {
                    executeMain {
                        updateState { copy(discardEditsReview = false) }
                    }
                }

                SalesContract.Action.OpenDebtorsSelected -> {
                    executeMain {
                        if (allowEntryKindSelection &&
                            uiState.value.entryStep == SalesContract.EntryStep.SELECT_KIND &&
                            !uiState.value.isMutating
                        ) {
                            emitEffect(SalesContract.Effect.OpenDebtors)
                        }
                    }
                }

                SalesContract.Action.StepBackSelected -> {
                    if (uiState.value.weightSaleEditor != null) {
                        if (!uiState.value.isMutating) updateState { copy(weightSaleEditor = null) }
                    } else {
                        stepBack()
                    }
                }

                SalesContract.Action.BackSelected -> {
                    savePendingEditsAndGoBack()
                }
            }
        }

        private fun initializeEntry(action: SalesContract.Action.InitializeEntry) {
            executeMain {
                allowEntryKindSelection = action.allowEntryKindSelection
                if (savedStateHandle.get<Boolean>(ENTRY_INITIALIZED_KEY) == true) {
                    if (action.unifiedInput) {
                        updateState {
                            copy(
                                unifiedInput = true,
                                mode = SalesContract.EntryMode.SCANNER,
                                // Sin selector, la ruta fija el tipo. Un estado restaurado de la versión
                                // con selector no debe dejar Vender en crédito; un cierre pendiente
                                // conserva sus términos.
                                entryKind =
                                    if (!action.allowEntryKindSelection && !isCheckoutPending) action.kind else entryKind,
                                entryStep =
                                    if (entryStep == SalesContract.EntryStep.SELECT_MODE ||
                                        (!action.allowEntryKindSelection && entryStep == SalesContract.EntryStep.SELECT_KIND)
                                    ) {
                                        SalesContract.EntryStep.SELL
                                    } else {
                                        entryStep
                                    },
                            )
                        }
                        saveEntryState()
                    }
                    return@executeMain
                }
                updateState {
                    copy(
                        entryKind = action.kind,
                        unifiedInput = action.unifiedInput,
                        entryStep =
                            if (action.allowEntryKindSelection) {
                                SalesContract.EntryStep.SELECT_KIND
                            } else if (action.unifiedInput) {
                                SalesContract.EntryStep.SELL
                            } else {
                                SalesContract.EntryStep.SELECT_MODE
                            },
                    )
                }
                saveEntryState()
            }
        }

        private fun stepBack() {
            executeMain {
                // A pending checkout owns its cart and payment terms. Its selectors cannot change
                // them, so Back leaves the route while preserving the sale for verification.
                if (uiState.value.isCheckoutPending || (uiState.value.unifiedInput && !allowEntryKindSelection)) {
                    savePendingEditsAndGoBack()
                    return@executeMain
                }
                val previousStep =
                    when (uiState.value.entryStep) {
                        SalesContract.EntryStep.SELL ->
                            if (uiState.value.unifiedInput) SalesContract.EntryStep.SELECT_KIND else SalesContract.EntryStep.SELECT_MODE
                        SalesContract.EntryStep.SELECT_MODE -> SalesContract.EntryStep.SELECT_KIND
                        SalesContract.EntryStep.SELECT_KIND -> return@executeMain
                    }
                clearEntryTransientState()
                updateState { copy(entryStep = previousStep) }
                saveEntryState()
            }
        }

        /** Cambiar de pantalla termina la captura/búsqueda, pero conserva el carrito y sus inputs. */
        private fun clearEntryTransientState() {
            stopBarcodeSession()
            clearProductRegistration()
            cancelNameSearch()
            focusedTextInputs.clear()
            updateState {
                copy(
                    scannerActive = false,
                    scannerFailure = null,
                    lastScanAdded = null,
                    isTextInputFocused = false,
                    query = "",
                    weightSaleEditor = null,
                    isNameSearchRunning = false,
                    searchFailed = false,
                    pendingAssociationBarcode = null,
                    barcodeSelectionReason = null,
                    barcodeSuggestions = emptyList(),
                    barcodeAssociatedWithoutCartAdd = false,
                    productRegisteredWithoutCartAdd = false,
                    pendingReplacement = null,
                    pendingLocations = emptyList(),
                    pendingRecoveredBarcode = null,
                ).withFilteredOptions()
            }
        }

        private fun cancelNameSearch() {
            nameSearchJob?.cancel()
            nameSearchJob = null
            nameSearchSnapshot = null
        }

        private fun clearPendingAssociation() {
            cancelNameSearch()
            updateState {
                copy(
                    pendingAssociationBarcode = null,
                    barcodeSelectionReason = null,
                    barcodeSuggestions = emptyList(),
                    pendingReplacement = null,
                    query = "",
                    isNameSearchRunning = false,
                    searchFailed = false,
                    failure = null,
                ).withFilteredOptions()
            }
        }

        private fun saveEntryState() {
            val state = uiState.value
            savedStateHandle[ENTRY_KIND_KEY] = state.entryKind.name
            savedStateHandle[ENTRY_MODE_KEY] = state.mode.name
            savedStateHandle[ENTRY_STEP_KEY] = state.entryStep.name
            savedStateHandle[ENTRY_UNIFIED_INPUT_KEY] = state.unifiedInput
            savedStateHandle[ENTRY_INITIALIZED_KEY] = true
        }

        private fun savePendingEditsAndGoBack() {
            if (backRequested) return
            updateState { copy(weightSaleEditor = null) }
            stopBarcodeSession()
            clearProductRegistration()
            backRequested = true
            executeMain {
                try {
                    if (flushPendingLineEdits()) {
                        emitEffect(SalesContract.Effect.Back)
                    } else if (hasPendingLineEdits()) {
                        // Toda edición que no pudo persistirse (validación, stock, CAS o I/O)
                        // requiere una decisión explícita; Back nunca deja al usuario atrapado.
                        updateState { copy(discardEditsReview = true) }
                    }
                } finally {
                    backRequested = false
                }
            }
        }

        private fun discardPendingEditsAndGoBack() {
            if (backRequested || !uiState.value.discardEditsReview) return
            backRequested = true
            executeMain {
                try {
                    cancelScheduledLineEdits()
                    operationMutex.withLock { }
                    quantityInputs.clear()
                    priceInputs.clear()
                    updateState { copy(hasPendingEdits = false, discardEditsReview = false) }
                    emitEffect(SalesContract.Effect.Back)
                } finally {
                    backRequested = false
                }
            }
        }

        private fun cancelScheduledLineEdits() {
            lineEditJobs.values.forEach { it.cancel() }
            lineEditJobs.clear()
        }

        private fun retryCartWithoutDiscardingEdits() {
            executeMain {
                val hadDeferredContext = deferredContextSnapshot != null
                if (!flushPendingLineEdits()) return@executeMain
                deferredContextSnapshot?.let { deferred ->
                    deferredContextSnapshot = null
                    applyCatalogAndInventorySnapshot(deferred)
                    return@executeMain
                }
                if (hadDeferredContext) return@executeMain
                openCart()
            }
        }

        private fun retryFailedOperation() {
            val state = uiState.value
            if (state.failure == SalesContract.Failure.LOAD_FAILED) {
                observeCatalogAndInventory()
                retryCartObservation()
                return
            }
            retryCartWithoutDiscardingEdits()
        }

        private fun retrySearch() {
            val state = uiState.value
            if (state.searchFailed && state.query.isNotBlank()) updateQuery(state.query)
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private fun observeCatalogAndInventory() {
            catalogObservation?.cancel()
            catalogObservation =
                executeMain {
                    try {
                        configuration
                            .observe()
                            // Cambios de preferencias ajenos a Ventas no deben reiniciar dos consultas Room
                            // ni reconstruir la proyeccion completa del selector de productos.
                            .map { current -> CatalogContext(current.activeBusinessId, current.currency) }
                            .distinctUntilChanged()
                            .flatMapLatest { context ->
                                if (context.businessId == null) {
                                    flowOf(CatalogInventorySnapshot(currency = context.currency))
                                } else {
                                    val projector = SalesCatalogProjector()
                                    combine(
                                        products.observeForBusiness(context.businessId)
                                            .distinctUntilChanged()
                                            .mapLatest(::prepareSalesProducts)
                                            .flowOn(dispatcherProvider.default),
                                        inventoryReads.observeInventory(context.businessId)
                                            .distinctUntilChanged()
                                            .flowOn(dispatcherProvider.default),
                                    ) { productRows, inventoryRows ->
                                        CatalogInventoryRows(
                                            context = context,
                                            products = productRows,
                                            inventory = inventoryRows,
                                        )
                                    }
                                        // Preparar el catálogo solo cuando cambia. Un saldo nuevo reutiliza
                                        // las filas no afectadas y el orden anterior si sus claves no cambiaron.
                                        .mapLatest { rows ->
                                            withContext(dispatcherProvider.default) {
                                                val projection = projector.project(rows.products, rows.inventory)
                                                CatalogInventorySnapshot(
                                                    businessId = rows.context.businessId,
                                                    currency = rows.context.currency,
                                                    productsById = projection.productsById,
                                                    inventoryById = projection.inventoryById,
                                                    availableProductOptionsByProduct = projection.optionsByProduct,
                                                    orderedAvailableProducts = projection.orderedOptions,
                                                )
                                            }
                                        }
                                }
                            }.collect(::handleCatalogAndInventorySnapshot)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        if (!initialCatalogReady.isCompleted) stopBarcodeSession()
                        updateState { copy(isLoading = false, catalogLoadFailed = true) }
                    }
                }
        }

        private suspend fun handleCatalogAndInventorySnapshot(snapshot: CatalogInventorySnapshot) {
            updateState { copy(catalogLoadFailed = false) }
            val cartContextChanged =
                domainCart?.let { cart ->
                    cart.businessId != snapshot.businessId || cart.currency != snapshot.currency
                } == true
            if (activeBusinessId != snapshot.businessId || cartContextChanged) updateState { copy(weightSaleEditor = null) }
            if (cartContextChanged && !flushPendingLineEdits()) {
                // Mantiene visibles y editables los valores del carrito anterior. Cuando su guardado
                // termine, handleMutationResult aplica el último contexto diferido.
                deferredContextSnapshot = snapshot
                return
            }
            deferredContextSnapshot = null
            applyCatalogAndInventorySnapshot(snapshot)
        }

        private fun applyCatalogAndInventorySnapshot(snapshot: CatalogInventorySnapshot) {
            val businessChanged = activeBusinessId != snapshot.businessId
            val catalogChanged = !businessChanged && productsById != snapshot.productsById
            val nameSearchToRefresh =
                uiState.value.query.takeIf { query ->
                    snapshot.businessId != null && query.isNotBlank() &&
                        (businessChanged || catalogChanged)
                }
            val cartContextChanged =
                domainCart?.let { cart ->
                    cart.businessId != snapshot.businessId || cart.currency != snapshot.currency
                } == true
            if (businessChanged || catalogChanged) {
                cancelNameSearch()
            }
            activeBusinessId = snapshot.businessId
            configuredCurrency = snapshot.currency
            productsById = snapshot.productsById
            inventoryById = snapshot.inventoryById
            availableProductOptionsByProduct = snapshot.availableProductOptionsByProduct
            orderedAvailableProducts = snapshot.orderedAvailableProducts
            when {
                snapshot.businessId == null -> {
                    detachCart(clearInputs = true)
                    updateState {
                        copy(
                            isLoading = false,
                            failure = SalesContract.Failure.NO_ACTIVE_BUSINESS,
                            cartId = null,
                            cartVersion = 0L,
                            cartContentHash = null,
                            cartLines = emptyList(),
                            hasPendingEdits = false,
                            total = null,
                            productOptions = emptyList(),
                            availableProducts = emptyList(),
                            pendingAssociationBarcode = null,
                            barcodeSelectionReason = null,
                            barcodeSuggestions = emptyList(),
                            lastScanAdded = null,
                            productRegisteredWithoutCartAdd = false,
                            isNameSearchRunning = false,
                        )
                    }
                }

                cartContextChanged -> {
                    detachCart(clearInputs = true)
                    updateState {
                        copy(
                            isLoading = true,
                            cartId = null,
                            cartVersion = 0L,
                            cartContentHash = null,
                            cartLines = emptyList(),
                            hasPendingEdits = false,
                            total = null,
                            pendingAssociationBarcode = null,
                            barcodeSelectionReason = null,
                            barcodeSuggestions = emptyList(),
                            lastScanAdded = null,
                            productRegisteredWithoutCartAdd = false,
                            pendingReplacement = null,
                            pendingLocations = emptyList(),
                            pendingRecoveredBarcode = null,
                            isNameSearchRunning = false,
                            failure = null,
                        )
                    }
                    openCart()
                }

                domainCart == null && !uiState.value.isLoading -> {
                    openCart()
                }

                // Una invalidacion de catalogo/inventario refresca cantidades y opciones, pero no
                // constituye por si sola la recuperacion de un fallo de guardado/checkout visible.
                else -> {
                    applyCart(domainCart, preserveFailure = true)
                }
            }
            // Los candidatos contienen la identidad y la razon de coincidencia del catalogo leido.
            // Tras un rename/archive/restore no se pueden reutilizar por id: una coincidencia exacta
            // anterior podria seguir apareciendo, o una nueva quedar ausente. Reconsulta solo cuando
            // cambia el catalogo (no por cada saldo de inventario) y conserva fallos ajenos visibles.
            nameSearchToRefresh?.let { query ->
                updateQuery(
                    value = query,
                    preserveFailure = true,
                    onlyIfQueryUnchanged = true,
                )
            }
            initialCatalogReady.complete(Unit)
        }

        private fun detachCart(clearInputs: Boolean) {
            cartObservation?.cancel()
            cartObservation = null
            cartRecovery?.cancel()
            cartRecovery = null
            domainCart = null
            cancelScheduledLineEdits()
            if (clearInputs) {
                quantityInputs.clear()
                priceInputs.clear()
            }
        }

        private fun openCart() {
            executeMain {
                operationMutex.withLock {
                    updateState { copy(isLoading = true, failure = null) }
                    try {
                        val result =
                            withContext(dispatcherProvider.io) {
                                val current = configuration.current()
                                current.activeBusinessId?.let { businessId ->
                                    createCart.forBusiness(businessId, current.currency)
                                } ?: CreateSaleCartResult.NoActiveBusiness
                            }
                        when (result) {
                            is CreateSaleCartResult.Created -> {
                                attachCart(result.cart)
                            }

                            is CreateSaleCartResult.Resumed -> {
                                attachCart(result.cart)
                            }

                            CreateSaleCartResult.NoActiveBusiness -> {
                                updateState {
                                    copy(
                                        isLoading = false,
                                        failure = SalesContract.Failure.NO_ACTIVE_BUSINESS,
                                    )
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        updateState {
                            copy(isLoading = false, failure = SalesContract.Failure.LOAD_FAILED)
                        }
                    }
                }
            }
        }

        private fun attachCart(cart: SaleCart) {
            cartRecovery?.cancel()
            cartRecovery = null
            uiState.value.productRegistration?.let { request ->
                if (request.saleId != cart.saleId || request.businessId != cart.businessId) {
                    clearProductRegistration()
                }
            }
            if (domainCart?.saleId != cart.saleId) {
                stopBarcodeSession()
                clearPendingAssociation()
                updateState {
                    copy(
                        pendingLocations = emptyList(),
                        pendingRecoveredBarcode = null,
                        lastScanAdded = null,
                        productRegisteredWithoutCartAdd = false,
                        weightSaleEditor = null,
                    )
                }
            }
            domainCart = cart
            applyCart(cart)
            observeAttachedCart(cart)
        }

        private fun retryCartObservation() {
            val cart = domainCart
            if (cart == null) {
                openCart()
            } else {
                observeAttachedCart(cart)
            }
        }

        private fun observeAttachedCart(cart: SaleCart) {
            cartObservation?.cancel()
            cartObservation =
                executeMain {
                    try {
                        observeCart(cart.saleId).collect { observed ->
                            if (observed != null && observed.status == SaleStatus.DRAFT) {
                                val current = domainCart
                                // Una emisión encolada antes del último Saved no puede hacer retroceder
                                // el carrito ni reutilizar una cantidad/versión anterior al siguiente disparo.
                                if (current != null && current.saleId == observed.saleId &&
                                    current.businessId == observed.businessId && observed.version < current.version
                                ) {
                                    return@collect
                                }
                                domainCart = observed
                                updateState { copy(cartLoadFailed = false) }
                                applyCart(observed)
                            } else {
                                requestCartRecovery(cart.saleId)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        updateState { copy(cartLoadFailed = true) }
                    }
                }
        }

        private fun requestCartRecovery(observedSaleId: SaleId) {
            if (cartRecovery?.isActive == true) return
            cartRecovery =
                executeMain {
                    // Si el POSTED/null fue la confirmación propia, ésta adjuntará el carrito nuevo antes
                    // de liberar el mutex y esta recuperación se vuelve un no-op.
                    operationMutex.withLock { }
                    if (domainCart?.saleId != observedSaleId) return@executeMain
                    // Una observación externa nunca puede borrar silenciosamente entradas locales. Intenta
                    // confirmarlas contra el borrador capturado; si Room ya lo cerró o eliminó, conserva
                    // los valores visibles hasta que la persona reintente o confirme que desea descartarlos.
                    if (!flushPendingLineEdits()) {
                        cartRecovery = null
                        updateState {
                            copy(
                                discardEditsReview = true,
                                failure = failure ?: SalesContract.Failure.STALE_CART,
                            )
                        }
                        return@executeMain
                    }
                    cartRecovery = null
                    detachCart(clearInputs = true)
                    updateState {
                        copy(
                            isLoading = true,
                            cartId = null,
                            cartVersion = 0L,
                            cartContentHash = null,
                            cartLines = emptyList(),
                            hasPendingEdits = false,
                            total = null,
                            failure = null,
                        )
                    }
                    openCart()
                }
        }

        private fun requestProductRegistration(scannedBarcode: String) {
            executeMain {
                val state = uiState.value
                val cart = domainCart ?: return@executeMain
                if (!state.canRegisterProduct || state.pendingAssociationBarcode != scannedBarcode ||
                    activeBusinessId != cart.businessId || deferredContextSnapshot != null || backRequested
                ) return@executeMain
                val barcode = BarcodeValue.parse(scannedBarcode) ?: return@executeMain
                val request = SalesContract.ProductRegistrationRequest(
                    requestId = UUID.randomUUID().toString(),
                    barcode = barcode.value,
                    businessId = cart.businessId,
                    saleId = cart.saleId,
                )
                stopBarcodeSession()
                cancelNameSearch()
                savedStateHandle[PRODUCT_REGISTRATION_KEY] = arrayListOf(
                    request.requestId, request.barcode, request.businessId.value, request.saleId.value,
                )
                savedStateHandle[PRODUCT_REGISTRATION_RESULT_KEY] = null
                updateState { copy(productRegistration = request, productRegisteredWithoutCartAdd = false) }
                saveEntryState()
                emitEffect(SalesContract.Effect.RegisterProduct(request))
            }
        }

        private fun registrationIsCurrent(request: SalesContract.ProductRegistrationRequest): Boolean =
            uiState.value.productRegistration == request && !backRequested &&
                uiState.value.entryStep == SalesContract.EntryStep.SELL &&
                uiState.value.mode == SalesContract.EntryMode.SCANNER &&
                activeBusinessId == request.businessId && domainCart?.let {
                    it.saleId == request.saleId && it.businessId == request.businessId &&
                        it.status == SaleStatus.DRAFT && it.pendingCheckout == null
                } == true

        private fun clearProductRegistration() {
            savedStateHandle[PRODUCT_REGISTRATION_KEY] = null
            savedStateHandle[PRODUCT_REGISTRATION_RESULT_KEY] = null
            updateState { copy(productRegistration = null) }
        }

        private suspend fun registrationContextStillActive(request: SalesContract.ProductRegistrationRequest): Boolean {
            // Un cambio de negocio observado puede estar esperando nuestro mutex. La configuración
            // persistida debe seguir correspondiendo al pedido después de cada lectura de Room.
            val current = withContext(dispatcherProvider.io) { configuration.current() }
            return registrationIsCurrent(request) && current.activeBusinessId == request.businessId &&
                current.currency == domainCart?.currency
        }

        private fun finishProductRegistration(result: SalesContract.ProductRegistrationResult) {
            val request = uiState.value.productRegistration ?: return
            if (result.requestId != request.requestId || registrationCompletionJob?.isActive == true) return
            // El resultado también se conserva hasta resolverlo: restaurar la Activity nunca suma
            // dos veces un alta ya guardada ni pierde la vuelta desde el formulario de Inventario.
            savedStateHandle[PRODUCT_REGISTRATION_RESULT_KEY] = arrayListOf(
                result.requestId, result.productId?.value.orEmpty(), result.businessId?.value.orEmpty(),
            )
            registrationCompletionJob = executeMain {
                try {
                    uiState.first { state ->
                        state.productRegistration != request ||
                            state.failure == SalesContract.Failure.NO_ACTIVE_BUSINESS ||
                            (!state.isLoading && !state.catalogLoadFailed && !state.cartLoadFailed &&
                                activeBusinessId != null && domainCart != null)
                    }
                    if (!registrationIsCurrent(request)) {
                        if (uiState.value.productRegistration == request) clearProductRegistration()
                        return@executeMain
                    }
                    if (result.productId == null) {
                        clearProductRegistration()
                        updateState {
                            copy(pendingAssociationBarcode = request.barcode, failure = null).withFilteredOptions()
                        }
                        return@executeMain
                    }
                    if (result.businessId != request.businessId) {
                        clearProductRegistration()
                        return@executeMain
                    }
                    launchMutation {
                        var added = false
                        var awaitingLocation = false
                        try {
                            if (!registrationIsCurrent(request)) return@launchMutation
                            val product = withContext(dispatcherProvider.io) { products.findById(result.productId) }
                            if (!registrationContextStillActive(request)) return@launchMutation
                            clearPendingAssociation()
                            if (product == null || product.businessId != request.businessId ||
                                product.status != CatalogStatus.ACTIVE
                            ) {
                                updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                                return@launchMutation
                            }
                            val cart = domainCart ?: return@launchMutation
                            if (showExistingScannedProduct(cart, product.productId)) {
                                added = true
                                return@launchMutation
                            }
                            val scan = QueuedBarcode(request.barcode, request.businessId, request.saleId, barcodeGeneration)
                            val options = resolveScannedProductOptions(scan, product, forceRefresh = true)
                                ?: return@launchMutation
                            if (!registrationContextStillActive(request)) return@launchMutation
                            val selected = options.firstOrNull { it.locationId == product.locationId }
                                ?: options.singleOrNull()
                            when {
                                selected != null -> added = addOptionToCart(selected)
                                options.isNotEmpty() -> {
                                    awaitingLocation = true
                                    updateState { copy(pendingLocations = options) }
                                }
                                else -> updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                            }
                        } finally {
                            if (uiState.value.productRegistration == request) {
                                clearProductRegistration()
                                updateState { copy(productRegisteredWithoutCartAdd = !added && !awaitingLocation) }
                            }
                        }
                    }?.join()
                } finally {
                    registrationCompletionJob = null
                }
            }
        }

        private fun handleBarcode(rawValue: String) {
            if (!uiState.value.canRouteScannerInput || deferredContextSnapshot != null) return
            val barcode = BarcodeValue.parse(rawValue)
            if (barcode == null) {
                executeMain { updateState { copy(failure = SalesContract.Failure.INVALID_BARCODE) } }
                return
            }
            val cart = domainCart ?: return
            val scan = QueuedBarcode(barcode.value, cart.businessId, cart.saleId, barcodeGeneration)
            // Agrupa solo repeticiones consecutivas, incluida la lectura que se está guardando.
            // A, desconocido, A debe conservar el último A para resolver la elección pendiente.
            if (scan == (pendingBarcodes.lastOrNull() ?: activeBarcode)) return
            if (uiState.value.pendingBarcodeCount >= MAX_PENDING_BARCODES) {
                updateState { copy(scannerFailure = SalesContract.ScannerFailure.QUEUE_FULL) }
                return
            }
            pendingBarcodes.addLast(scan)
            if (uiState.value.unifiedInput) cancelNameSearch()
            updateState {
                copy(
                    pendingBarcodeCount = pendingBarcodeCount + 1,
                    scannerFailure = null,
                    query = if (unifiedInput) "" else query,
                    isNameSearchRunning = if (unifiedInput) false else isNameSearchRunning,
                    searchFailed = if (unifiedInput) false else searchFailed,
                ).withFilteredOptions()
            }
            if (barcodeProcessingJob?.isActive == true) return
            val generation = barcodeGeneration
            barcodeProcessingJob =
                executeMain {
                    try {
                        while (pendingBarcodes.isNotEmpty() && generation == barcodeGeneration) {
                            val scan = pendingBarcodes.first()
                            // El carrito puede abrirse antes de la primera proyección del catálogo.
                            // Conserva la lectura aceptada hasta poder validar su negocio y sus opciones.
                            // Salir, cambiar de carrito o fallar esa primera carga cancela la sesión.
                            initialCatalogReady.await()
                            // Espera fuera del mutex: resolver un almacén o una edición puede necesitarlo.
                            uiState.first { state ->
                                !scan.isCurrent() ||
                                    (
                                        state.canRouteScannerInput && !state.isMutating &&
                                            !state.isSavingLineEdits && deferredContextSnapshot == null
                                    )
                            }
                            pendingBarcodes.removeFirst()
                            activeBarcode = scan
                            try {
                                if (scan.isCurrent()) {
                                    launchMutation(isBarcodeMutation = true) {
                                        if (scan.isCurrent()) processBarcode(scan)
                                    }?.join()
                                }
                            } finally {
                                if (activeBarcode == scan) activeBarcode = null
                                if (generation == barcodeGeneration) {
                                    updateState {
                                        copy(pendingBarcodeCount = (pendingBarcodeCount - 1).coerceAtLeast(0))
                                    }
                                }
                            }
                        }
                    } finally {
                        if (generation == barcodeGeneration) barcodeProcessingJob = null
                    }
                }
        }

        private fun QueuedBarcode.isCurrent(): Boolean =
            generation == barcodeGeneration && !backRequested &&
                uiState.value.entryStep == SalesContract.EntryStep.SELL &&
                uiState.value.mode == SalesContract.EntryMode.SCANNER &&
                activeBusinessId == businessId && domainCart?.let {
                    it.saleId == saleId && it.businessId == businessId && it.status == SaleStatus.DRAFT
                } == true

        private fun stopBarcodeSession() {
            if (pendingBarcodeRecovery != null) {
                updateState { copy(pendingLocations = emptyList(), pendingRecoveredBarcode = null) }
            }
            pendingBarcodeRecovery = null
            barcodeGeneration += 1
            pendingBarcodes.clear()
            activeBarcode = null
            barcodeProcessingJob?.cancel()
            barcodeProcessingJob = null
            updateState { copy(pendingBarcodeCount = 0) }
        }

        private suspend fun processBarcode(scan: QueuedBarcode) {
            val product =
                withContext(dispatcherProvider.io) {
                    // Las facturas pueden guardar el código del producto como SKU. Una coincidencia
                    // de barras tiene prioridad; resolver por SKU no cambia la asociación guardada.
                    products.findByBarcode(scan.businessId, scan.value)
                        ?: products.findBySku(scan.businessId, scan.value)
                }
            // El lookup puede terminar después de salir, cambiar de negocio o abrir otro carrito.
            if (!scan.isCurrent()) return
            // Reescanear reemplaza únicamente la elección pendiente; nunca cambia el código guardado.
            // También permite avanzar a las lecturas ya aceptadas detrás de una desconocida.
            if (uiState.value.isAssociating) clearPendingAssociation()
            pendingBarcodeRecovery = null
            if (product == null) {
                // Sin coincidencia exacta solo se intenta la recuperación automática segura. Si
                // tampoco encaja con un único producto, se pide asociar el código nuevo sin sugerir
                // productos por parecido: ignorarlo dejaba a la vista el producto de la lectura anterior.
                if (!tryAutomaticBarcodeRecovery(scan) && scan.isCurrent()) openBarcodeAssociation(scan)
                return
            }
            // Un código personalizado exacto puede ser también una lectura truncada de otro GTIN.
            // Nunca se sustituye por el código largo: ambos requieren una elección explícita.
            if (requiresSuspiciousExactBarcodeReview(scan.value)) {
                // Sólo un código guardado 1–2 dígitos más largo que contenga la lectura como
                // subsecuencia puede competir con este exacto: SQLite descarta el resto.
                val lengthCandidates = withContext(dispatcherProvider.io) {
                    products.listBarcodeSupersequences(
                        scan.businessId,
                        scan.value,
                        scan.value.length + 1,
                        scan.value.length + 2,
                    )
                }
                val competitors = withContext(dispatcherProvider.default) {
                    findSuspiciousExactBarcodeMatches(scan.value, scan.businessId, product.productId, lengthCandidates)
                }
                if (!scan.isCurrent()) return
                if (competitors.isNotEmpty()) {
                    showBarcodeChoices(
                        scan, reason = SalesContract.BarcodeSelectionReason.AMBIGUOUS,
                        preferredProductIds = listOf(product.productId) + competitors.map { it.productId },
                    )
                    return
                }
            }
            val cart = domainCart ?: return
            if (showExistingScannedProduct(cart, product.productId)) return
            if (product.status != CatalogStatus.ACTIVE) {
                updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                return
            }
            val locations = resolveScannedProductOptions(scan, product) ?: return
            when (locations.size) {
                0 -> {
                    updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                }

                1 -> {
                    addOptionToCart(locations.single())
                }

                else -> {
                    updateState {
                        copy(
                            pendingLocations = locations,
                            failure = null,
                        )
                    }
                }
            }
        }

        private suspend fun showBarcodeChoices(
            scan: QueuedBarcode,
            reason: SalesContract.BarcodeSelectionReason? = null,
            preferredProductIds: List<ProductId> = emptyList(),
        ) {
            // Sólo productos con código o SKU pueden coincidir; el resto nunca aparece como opción.
            val catalog = withContext(dispatcherProvider.io) {
                products.listScannerIdentityCandidates(scan.businessId)
            }
            if (!scan.isCurrent()) return
            val eligible = catalog.filter { it.businessId == scan.businessId && it.status == CatalogStatus.ACTIVE }
            val matches = withContext(dispatcherProvider.default) {
                val canonicalMatch = findAutomaticBarcodeRecovery(scan.value, scan.businessId, catalog)
                eligible.mapNotNull { product ->
                    val missing = when {
                        product.barcode == scan.value || product.sku == scan.value -> 0
                        canonicalMatch?.productId == product.productId -> canonicalMatch.missingDigits
                        else -> product.barcode?.let { BarcodeSimilarity.missingDigits(scan.value, it) }
                    } ?: return@mapNotNull null
                    product to missing
                }.sortedWith(
                    compareBy<Pair<Product, Int>> { if (it.first.productId in preferredProductIds) 0 else 1 }
                        .thenBy { it.second }.thenBy { it.first.barcode }.thenBy { it.first.productId.value },
                )
            }
            val suggestions = mutableListOf<SalesContract.BarcodeSuggestion>()
            var sellableProductCount = 0
            for ((product, missing) in matches) {
                val options = resolveScannedProductOptions(
                    scan, product, forceRefresh = productsById[product.productId]?.barcode != product.barcode,
                ) ?: return
                if (options.isEmpty()) continue
                suggestions += options.map { SalesContract.BarcodeSuggestion(it, missing) }
                sellableProductCount += 1
                if (sellableProductCount == 5) break
            }
            if (!scan.isCurrent()) return
            val hasMultipleIdentities = withContext(dispatcherProvider.default) {
                catalog.asSequence().filter { it.businessId == scan.businessId }.filter { product ->
                    product.barcode?.let { BarcodeSimilarity.missingDigits(scan.value, it) } != null ||
                        product.barcode == scan.value || product.sku == scan.value
                }.map { it.productId }.distinct().take(2).count() > 1
            }
            if (!scan.isCurrent()) return
            openBarcodeAssociation(
                scan,
                reason = reason ?: SalesContract.BarcodeSelectionReason.AMBIGUOUS.takeIf { hasMultipleIdentities },
                suggestions = suggestions,
            )
        }

        /**
         * Deja la lectura pendiente para asociarla a un producto elegido por nombre o registrarlo.
         * Nunca modifica el catálogo por sí misma: sólo la elección explícita guarda el código.
         */
        private fun openBarcodeAssociation(
            scan: QueuedBarcode,
            reason: SalesContract.BarcodeSelectionReason? = null,
            suggestions: List<SalesContract.BarcodeSuggestion> = emptyList(),
        ) {
            cancelNameSearch()
            pendingBarcodeRecovery = null
            updateState {
                copy(
                    pendingAssociationBarcode = scan.value,
                    barcodeSelectionReason = reason,
                    barcodeSuggestions = suggestions,
                    pendingReplacement = null,
                    pendingLocations = emptyList(),
                    pendingRecoveredBarcode = null,
                    query = "",
                    isNameSearchRunning = false,
                    searchFailed = false,
                    failure = null,
                ).withFilteredOptions()
            }
        }

        private suspend fun tryAutomaticBarcodeRecovery(scan: QueuedBarcode): Boolean {
            // La consulta fresca también cubre altas/cambios cuya proyección visual aún no emitió.
            val catalog = withContext(dispatcherProvider.io) { products.listScannerIdentityCandidates(scan.businessId) }
            val candidate = withContext(dispatcherProvider.default) {
                findAutomaticBarcodeRecovery(scan.value, scan.businessId, catalog)
            } ?: return false
            if (!scan.isCurrent()) return false
            val product = catalog.first { it.productId == candidate.productId }
            val options = resolveScannedProductOptions(scan, product, forceRefresh = true) ?: return false
            val recovery = PendingBarcodeRecovery(
                scan, product.productId,
                SaleBarcodeRecoveryExpectation(scan.value, candidate.barcode, product.version),
            )
            // Esta segunda lectura protege también el reconocimiento de un artículo ya en carrito.
            // Una nueva línea vuelve a exigir la misma prueba dentro de la transacción de Room.
            if (!isBarcodeRecoveryCurrent(recovery)) {
                if (scan.isCurrent()) showBarcodeChoices(scan, reason = SalesContract.BarcodeSelectionReason.CATALOG_CHANGED)
                return true
            }
            val cart = domainCart ?: return false
            if (showExistingScannedProduct(cart, product.productId, recoveredFromBarcode = scan.value)) return true
            when (options.size) {
                0 -> updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                1 -> addOptionToCart(options.single(), recovery = recovery)
                else -> {
                    pendingBarcodeRecovery = recovery
                    updateState { copy(pendingLocations = options, pendingRecoveredBarcode = scan.value, failure = null) }
                }
            }
            return true
        }

        private suspend fun isBarcodeRecoveryCurrent(recovery: PendingBarcodeRecovery): Boolean {
            val catalog = withContext(dispatcherProvider.io) {
                products.listScannerIdentityCandidates(recovery.scan.businessId)
            }
            val match = withContext(dispatcherProvider.default) {
                findAutomaticBarcodeRecovery(recovery.scan.value, recovery.scan.businessId, catalog)
            }
            return recovery.scan.isCurrent() && match?.productId == recovery.productId &&
                match.barcode == recovery.expectation.expectedStoredBarcode &&
                catalog.firstOrNull { it.productId == recovery.productId }?.version == recovery.expectation.expectedProductVersion
        }

        private suspend fun resolveScannedProductOptions(
            scan: QueuedBarcode,
            product: Product,
            forceRefresh: Boolean = false,
        ): List<SalesContract.ProductOption>? {
            var options = if (forceRefresh) emptyList() else availableProductOptionsByProduct[product.productId].orEmpty()
            if (options.isEmpty()) {
                // El alta puede estar confirmada en Room antes de que el catálogo combinado emita.
                // Solo en esa ventana se consulta el producto; nunca se recarga todo el inventario.
                val item =
                    withContext(dispatcherProvider.io) {
                        inventoryReads.observeProductItem(scan.businessId, product.productId).first()
                    }
                if (!scan.isCurrent()) return null
                if (
                    item != null && item.businessId == scan.businessId && item.productId == product.productId &&
                    InventoryDataAlert.ARCHIVED_PRODUCT !in item.alerts
                ) {
                    options = buildSalesProductOptions(product, item)
                    if (options.isNotEmpty()) {
                        productsById = productsById + (product.productId to product)
                        inventoryById = inventoryById + (product.productId to item)
                        availableProductOptionsByProduct =
                            availableProductOptionsByProduct + (product.productId to options)
                        // Conserva el orden existente sin volver a ordenar el catálogo completo.
                        val updated =
                            orderedAvailableProducts
                                .filterNot { it.productId == product.productId }
                                .toMutableList()
                        options.forEach { option ->
                            val index = updated.binarySearch(option, SALES_PRODUCT_OPTION_ORDER)
                            updated.add(if (index < 0) -index - 1 else index, option)
                        }
                        orderedAvailableProducts = updated
                    }
                }
            }
            // La búsqueda por código ya leyó el producto persistido: su precio puede adelantarse a la
            // emisión del catálogo. addOptionToCart conserva cualquier precio ya acordado.
            return options.map { it.copy(suggestedSalePrice = product.salePrice) }
        }

        private fun selectProduct(
            productId: ProductId,
            locationId: LocationId,
        ) {
            if (dataActionsBlocked()) return
            val option =
                availableProductOptionsByProduct[productId]?.firstOrNull {
                    it.productId == productId && it.locationId == locationId
                } ?: run {
                    executeMain { updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) } }
                    return
                }
            if (uiState.value.pendingAssociationBarcode != null) {
                associateBarcode(option, allowReplacement = false)
            } else if (option.isWeightProduct) {
                openWeightSale(option)
            } else {
                launchMutation(oneShot = true) {
                    if (addOptionToCart(option) && uiState.value.unifiedInput) {
                        cancelNameSearch()
                        updateState {
                            copy(query = "", isNameSearchRunning = false, searchFailed = false).withFilteredOptions()
                        }
                    }
                }
            }
        }

        private fun SaleCartLine.isWeightSaleLine(): Boolean =
            barcode == null && WeightSaleCalculator.isKilogramUnit(unitCode) &&
                discount.minorUnits == 0L && tax.minorUnits == 0L

        private fun weightOptionForLine(line: SaleCartLine): SalesContract.ProductOption =
            SalesContract.ProductOption(
                productId = line.productId,
                productName = line.productName,
                locationId = line.locationId,
                locationName = line.locationName,
                unitCode = "kg",
                availableQuantity = inventoryById[line.productId]?.positions
                    ?.firstOrNull { it.locationId == line.locationId }?.quantityOnHand ?: BigDecimal.ZERO,
                sku = productsById[line.productId]?.sku,
                barcode = line.barcode,
                suggestedSalePrice = line.unitPrice ?: productsById[line.productId]?.salePrice,
                isWeightProduct = line.isWeightSaleLine(),
            )

        private fun openWeightSale(option: SalesContract.ProductOption) {
            if (dataActionsBlocked() || !option.isWeightProduct) return
            val state = uiState.value
            if (state.isLoading || state.isMutating || state.isSavingLineEdits || state.hasPendingEdits ||
                state.pendingBarcodeCount != 0 || state.weightSaleEditor != null || state.isAssociating ||
                state.pendingLocations.isNotEmpty() || state.pendingReplacement != null ||
                state.discardEditsReview
            ) return
            val cart = domainCart ?: return
            val existing = cart.lines.firstOrNull { it.productId == option.productId }
            // La elección conserva el almacén y las condiciones históricas de la línea.
            if (existing != null && !existing.isWeightSaleLine()) {
                showExistingScannedProduct(cart, option.productId)
                return
            }
            val selected = existing?.let(::weightOptionForLine) ?: option
            updateState {
                copy(
                    weightSaleEditor = SalesContract.WeightSaleEditor(
                        product = selected,
                        cartId = cart.saleId.value,
                        cartVersion = cart.version,
                        businessId = cart.businessId,
                        lineId = existing?.saleLineId?.value,
                        amountInput = existing?.lineTotal?.toMajor()?.toPlainString().orEmpty(),
                        quantityInput = existing?.quantity?.value?.toPlainString().orEmpty(),
                        preservedQuantity = existing?.quantity,
                    ),
                    scannerFailure = null,
                )
            }
        }

        private fun updateWeightEditor(transform: (SalesContract.WeightSaleEditor) -> SalesContract.WeightSaleEditor) {
            if (uiState.value.isMutating) return
            updateState {
                copy(weightSaleEditor = weightSaleEditor?.let { editor ->
                    transform(editor).copy(
                        submitAttempted = false,
                        failure = editor.failure.takeIf {
                            it == SalesContract.WeightSaleFailure.PRODUCT_UNAVAILABLE || it == SalesContract.WeightSaleFailure.STALE_CART
                        },
                    )
                })
            }
        }

        private fun weightSaleIsCurrent(editor: SalesContract.WeightSaleEditor): Boolean =
            !backRequested && !dataActionsBlocked() && deferredContextSnapshot == null &&
                uiState.value.weightSaleEditor == editor && activeBusinessId == editor.businessId &&
                domainCart?.let { it.saleId.value == editor.cartId && it.businessId == editor.businessId } == true

        private fun failWeightSale(
            editor: SalesContract.WeightSaleEditor,
            reason: SalesContract.WeightSaleFailure?,
            product: SalesContract.ProductOption = editor.product,
        ) {
            updateState {
                if (weightSaleEditor == editor) {
                    copy(weightSaleEditor = editor.copy(product = product, submitAttempted = true, failure = reason))
                } else this
            }
        }

        private fun confirmWeightSale() {
            val editor = uiState.value.weightSaleEditor ?: return
            if (!editor.isValid) {
                failWeightSale(editor, editor.failure)
                return
            }
            if (!weightSaleIsCurrent(editor)) return
            launchMutation(oneShot = true) {
                try {
                    if (!weightSaleIsCurrent(editor)) return@launchMutation
                    val cart = domainCart ?: return@launchMutation
                    if (cart.version != editor.cartVersion) {
                        failWeightSale(editor, SalesContract.WeightSaleFailure.STALE_CART)
                        return@launchMutation
                    }
                    val existing = editor.lineId?.let { id -> cart.lines.firstOrNull { it.saleLineId.value == id } }
                    if (editor.lineId != null && (existing == null || !existing.isWeightSaleLine())) {
                        failWeightSale(editor, SalesContract.WeightSaleFailure.STALE_CART)
                        return@launchMutation
                    }
                    val currentProduct = withContext(dispatcherProvider.io) { products.findById(editor.product.productId) }
                    val item = withContext(dispatcherProvider.io) {
                        inventoryReads.observeProductItem(editor.businessId, editor.product.productId).first()
                    }
                    val context = withContext(dispatcherProvider.io) { configuration.current() }
                    if (!weightSaleIsCurrent(editor)) return@launchMutation
                    if (context.activeBusinessId != editor.businessId || context.currency != cart.currency) {
                        failWeightSale(editor, SalesContract.WeightSaleFailure.STALE_CART)
                        return@launchMutation
                    }
                    val position = item?.positions?.firstOrNull { it.locationId == editor.product.locationId }
                    if (currentProduct == null || currentProduct.businessId != editor.businessId ||
                        currentProduct.status != CatalogStatus.ACTIVE || item?.businessId != editor.businessId ||
                        !WeightSaleCalculator.isKilogramUnit(item.unitCode) ||
                        InventoryDataAlert.ARCHIVED_PRODUCT in item.alerts || position == null ||
                        InventoryDataAlert.ARCHIVED_LOCATION in position.alerts ||
                        (existing == null && currentProduct.barcode != null) ||
                        (existing != null && currentProduct.unitId != existing.unitId)
                    ) {
                        failWeightSale(editor, SalesContract.WeightSaleFailure.PRODUCT_UNAVAILABLE)
                        return@launchMutation
                    }
                    val price = existing?.unitPrice ?: currentProduct.salePrice
                    val latest = editor.product.copy(
                        productName = currentProduct.name,
                        availableQuantity = position.quantityOnHand,
                        suggestedSalePrice = price,
                    )
                    if (price != editor.pricePerKg || price?.currency != cart.currency) {
                        failWeightSale(editor, SalesContract.WeightSaleFailure.PRODUCT_CHANGED, latest)
                        return@launchMutation
                    }
                    val quantity = requireNotNull(editor.quantity)
                    if (quantity.value > position.quantityOnHand) {
                        failWeightSale(editor, null, latest)
                        return@launchMutation
                    }
                    val result = withContext(dispatcherProvider.io) {
                        saveLine.forBusiness(
                            editor.businessId,
                            SaveSaleCartLineCommand(
                                saleId = cart.saleId,
                                expectedVersion = editor.cartVersion,
                                saleLineId = existing?.saleLineId,
                                productId = editor.product.productId,
                                locationId = editor.product.locationId,
                                quantity = quantity,
                                unitPrice = requireNotNull(price),
                                discount = Money.zero(cart.currency),
                                tax = Money.zero(cart.currency),
                            ),
                        )
                    }
                    when (result) {
                        is SaleCartMutationResult.Saved -> {
                            handleMutationResult(result)
                            cancelNameSearch()
                            updateState {
                                copy(weightSaleEditor = null, query = "", isNameSearchRunning = false, searchFailed = false)
                                    .withFilteredOptions()
                            }
                        }
                        SaleCartMutationResult.CheckoutPending -> {
                            updateState { copy(weightSaleEditor = null) }
                            handleMutationResult(result)
                        }
                        SaleCartMutationResult.Stale -> failWeightSale(editor, SalesContract.WeightSaleFailure.STALE_CART)
                        SaleCartMutationResult.ProductUnavailable,
                        SaleCartMutationResult.LocationUnavailable,
                        -> failWeightSale(editor, SalesContract.WeightSaleFailure.PRODUCT_UNAVAILABLE)
                        else -> failWeightSale(editor, SalesContract.WeightSaleFailure.SAVE_FAILED)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failWeightSale(editor, SalesContract.WeightSaleFailure.SAVE_FAILED)
                }
            }
        }

        private fun selectBarcodeSuggestion(action: SalesContract.Action.BarcodeSuggestionSelected) {
            if (dataActionsBlocked()) return
            val state = uiState.value
            if (state.isLoading || state.isMutating || state.isSavingLineEdits || state.hasPendingEdits ||
                state.pendingReplacement != null || state.pendingLocations.isNotEmpty() ||
                state.discardEditsReview
            ) {
                return
            }
            if (state.pendingAssociationBarcode != action.scannedBarcode) return
            val suggestion =
                state.barcodeSuggestions.firstOrNull {
                    it.product.productId == action.productId && it.product.locationId == action.locationId
                } ?: return
            val cart = domainCart ?: return
            launchMutation(oneShot = true) {
                val product = withContext(dispatcherProvider.io) { products.findById(action.productId) }
                // Una selección nacida de una lectura o un catálogo anterior no puede afectar otra
                // venta. Se releen estado y existencia después de IO y antes de guardar la línea.
                if (backRequested || deferredContextSnapshot != null || dataActionsBlocked() ||
                    domainCart?.saleId != cart.saleId || activeBusinessId != cart.businessId ||
                    uiState.value.pendingAssociationBarcode != action.scannedBarcode ||
                    uiState.value.entryStep != SalesContract.EntryStep.SELL ||
                    uiState.value.mode != SalesContract.EntryMode.SCANNER ||
                    uiState.value.barcodeSuggestions.none {
                        it.product.productId == action.productId &&
                            it.product.locationId == action.locationId &&
                            it.product.barcode == suggestion.product.barcode
                    }
                ) {
                    return@launchMutation
                }
                val option =
                    availableProductOptionsByProduct[action.productId]?.firstOrNull {
                        it.locationId == action.locationId && it.availableQuantity.signum() > 0
                    }
                if (product == null || product.businessId != cart.businessId ||
                    product.status != CatalogStatus.ACTIVE ||
                    product.barcode != suggestion.product.barcode || option == null ||
                    option.barcode != suggestion.product.barcode
                ) {
                    updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                    return@launchMutation
                }
                // La confirmación elige el producto para esta venta; el barcode persistido se conserva.
                if (addOptionToCart(option.copy(suggestedSalePrice = product.salePrice))) {
                    clearPendingAssociation()
                }
            }
        }

        private fun selectPendingLocation(
            productId: ProductId,
            locationId: LocationId,
        ) {
            if (dataActionsBlocked()) return
            if (uiState.value.pendingLocations.none {
                    it.productId == productId && it.locationId == locationId
                }
            ) {
                return
            }
            val recovery = pendingBarcodeRecovery
            launchMutation(oneShot = true) {
                pendingBarcodeRecovery = null
                updateState { copy(pendingLocations = emptyList(), pendingRecoveredBarcode = null) }
                if (recovery != null && (recovery.productId != productId || !isBarcodeRecoveryCurrent(recovery))) {
                    if (recovery.scan.isCurrent()) {
                        showBarcodeChoices(recovery.scan, reason = SalesContract.BarcodeSelectionReason.CATALOG_CHANGED)
                    }
                    return@launchMutation
                }
                val option = availableProductOptionsByProduct[productId]?.firstOrNull { it.locationId == locationId }
                    ?: run {
                        updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                        return@launchMutation
                    }
                val product = withContext(dispatcherProvider.io) { products.findById(productId) }
                if (product == null || product.businessId != domainCart?.businessId || product.status != CatalogStatus.ACTIVE) {
                    updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                    return@launchMutation
                }
                if (recovery != null && !recovery.scan.isCurrent()) return@launchMutation
                addOptionToCart(option.copy(suggestedSalePrice = product.salePrice), recovery = recovery)
            }
        }

        private fun associateBarcode(
            option: SalesContract.ProductOption,
            allowReplacement: Boolean,
        ) {
            if (dataActionsBlocked()) return
            val barcode = uiState.value.pendingAssociationBarcode ?: return
            val product = productsById[option.productId] ?: return
            if (product.barcode != null && product.barcode != barcode && !allowReplacement) {
                executeMain {
                    updateState {
                        copy(
                            pendingReplacement =
                                SalesContract.BarcodeReplacement(
                                    product = option,
                                    existingBarcode = product.barcode,
                                    newBarcode = barcode,
                                ),
                        )
                    }
                }
                return
            }
            launchMutation(oneShot = true) {
                val result =
                    if (product.barcode == barcode) {
                        CatalogMutationResult.Saved(product)
                    } else {
                        withContext(dispatcherProvider.io) {
                            saveProduct(product.copy(barcode = barcode))
                        }
                    }
                when (result) {
                    is CatalogMutationResult.Saved -> {
                        productsById = productsById + (result.value.productId to result.value)
                        // Asociar un codigo no cambia orden, ubicaciones ni stock. Actualizar solo
                        // las opciones afectadas evita ordenar y agrupar todo el catalogo en Main.
                        replaceProjectedProduct(result.value)
                        cancelNameSearch()
                        updateState {
                            copy(
                                pendingAssociationBarcode = null,
                                barcodeSelectionReason = null,
                                pendingReplacement = null,
                                query = "",
                                isNameSearchRunning = false,
                                searchFailed = false,
                                failure = null,
                            ).withFilteredOptions()
                        }
                        val added =
                            try {
                                addOptionToCart(
                                    option = option.copy(barcode = barcode),
                                    successMessage = SalesContract.Message.BARCODE_ASSOCIATED,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                updateState { copy(barcodeAssociatedWithoutCartAdd = true) }
                                throw error
                            }
                        if (!added) {
                            updateState { copy(barcodeAssociatedWithoutCartAdd = true) }
                        }
                    }

                    is CatalogMutationResult.Duplicate -> {
                        updateState {
                            copy(
                                failure =
                                    if (result.field == CatalogDuplicateField.BARCODE) {
                                        SalesContract.Failure.BARCODE_CONFLICT
                                    } else {
                                        SalesContract.Failure.SAVE_FAILED
                                    },
                            )
                        }
                    }

                    CatalogMutationResult.Stale -> {
                        updateState {
                            copy(failure = SalesContract.Failure.STALE_CART)
                        }
                    }

                    is CatalogMutationResult.Invalid,
                    CatalogMutationResult.NotFound,
                    -> {
                        updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                    }
                }
            }
        }

        private fun confirmBarcodeReplacement() {
            val replacement = uiState.value.pendingReplacement ?: return
            val current = productsById[replacement.product.productId]
            if (current?.barcode != replacement.existingBarcode) {
                executeMain {
                    updateState {
                        copy(
                            pendingReplacement = null,
                            failure = SalesContract.Failure.STALE_CART,
                        )
                    }
                }
                return
            }
            associateBarcode(replacement.product, allowReplacement = true)
        }

        private suspend fun addOptionToCart(
            option: SalesContract.ProductOption,
            successMessage: SalesContract.Message? = null,
            recovery: PendingBarcodeRecovery? = null,
        ): Boolean {
            val recoveredFromBarcode = recovery?.scan?.value
            val cart =
                domainCart ?: run {
                    updateState { copy(failure = SalesContract.Failure.LOAD_FAILED) }
                    return false
                }
            // La cantidad de un producto ya presente se cambia expresamente en su línea.
            // Esta comprobación corre bajo el mutex también para sugerencias y asociaciones.
            if (showExistingScannedProduct(cart, option.productId, recoveredFromBarcode)) {
                successMessage?.let { emitEffect(SalesContract.Effect.ShowMessage(it)) }
                return true
            }
            val existing =
                cart.lines.firstOrNull {
                    it.productId == option.productId && it.locationId == option.locationId
                }
            val quantity =
                existing?.quantity?.value?.add(BigDecimal.ONE)
                    ?: BigDecimal.ONE.min(option.availableQuantity)
            if (quantity > option.availableQuantity) {
                updateState { copy(failure = SalesContract.Failure.INSUFFICIENT_STOCK) }
                return false
            }
            val newLinePrice =
                option.suggestedSalePrice
                    ?.takeIf { it.currency == cart.currency }
            val unitPrice = existing?.unitPrice ?: newLinePrice
            val result =
                withContext(dispatcherProvider.io) {
                    saveLine.forBusiness(
                        cart.businessId,
                        SaveSaleCartLineCommand(
                            saleId = cart.saleId,
                            barcodeRecovery = recovery?.expectation,
                            expectedVersion = cart.version,
                            saleLineId = existing?.saleLineId,
                            productId = option.productId,
                            locationId = option.locationId,
                            quantity = Quantity.of(quantity),
                            unitPrice = unitPrice,
                            discount =
                                if (existing != null) {
                                    existing.discount.takeIf { existing.unitPrice != null }
                                } else {
                                    newLinePrice?.let { Money.zero(cart.currency) }
                                },
                            tax =
                                if (existing != null) {
                                    existing.tax.takeIf { existing.unitPrice != null }
                                } else {
                                    newLinePrice?.let { Money.zero(cart.currency) }
                                },
                        ),
                    )
                }
            if (result == SaleCartMutationResult.BarcodeRecoveryChanged && recovery != null) {
                if (recovery.scan.isCurrent()) {
                    showBarcodeChoices(
                        recovery.scan, reason = SalesContract.BarcodeSelectionReason.CATALOG_CHANGED,
                        preferredProductIds = listOf(recovery.productId),
                    )
                }
                return false
            }
            handleMutationResult(result, successMessage)
            if (result is SaleCartMutationResult.Saved &&
                uiState.value.entryStep == SalesContract.EntryStep.SELL &&
                uiState.value.mode == SalesContract.EntryMode.SCANNER &&
                domainCart?.saleId == cart.saleId && activeBusinessId == cart.businessId
            ) {
                val savedLine =
                    result.cart.lines.firstOrNull {
                        it.productId == option.productId && it.locationId == option.locationId
                    }
                if (savedLine != null) {
                    updateState {
                        copy(
                            lastScanAdded =
                                SalesContract.LastScanAdded(
                                    productId = savedLine.productId,
                                    locationId = savedLine.locationId,
                                    productName = savedLine.productName,
                                    locationName = savedLine.locationName,
                                    quantity = savedLine.quantity.value,
                                    unitCode = savedLine.unitCode,
                                    sequence = (lastScanAdded?.sequence ?: 0L) + 1L,
                                    recoveredFromBarcode = recoveredFromBarcode,
                                ),
                        )
                    }
                }
            }
            return result is SaleCartMutationResult.Saved
        }

        private fun showExistingScannedProduct(
            cart: SaleCart,
            productId: ProductId,
            recoveredFromBarcode: String? = null,
        ): Boolean {
            if (uiState.value.entryStep != SalesContract.EntryStep.SELL ||
                uiState.value.mode != SalesContract.EntryMode.SCANNER ||
                domainCart?.saleId != cart.saleId || activeBusinessId != cart.businessId
            ) {
                return false
            }
            // Un producto sigue siendo el mismo aunque el nuevo código, SKU o candidato
            // proponga otro almacén. Se conserva íntegra la línea que ya eligió la persona.
            val existing = cart.lines.firstOrNull { it.productId == productId } ?: return false
            updateState {
                copy(
                    failure = null,
                    lastScanAdded =
                        SalesContract.LastScanAdded(
                            productId = existing.productId,
                            locationId = existing.locationId,
                            productName = existing.productName,
                            locationName = existing.locationName,
                            quantity = existing.quantity.value,
                            unitCode = existing.unitCode,
                            sequence = (lastScanAdded?.sequence ?: 0L) + 1L,
                            alreadyInCart = true,
                            recoveredFromBarcode = recoveredFromBarcode,
                        ),
                )
            }
            return true
        }

        private fun updateQuery(
            value: String,
            preserveFailure: Boolean = false,
            onlyIfQueryUnchanged: Boolean = false,
        ) {
            // A diferencia de cancelNameSearch, escribir conserva la instantánea anterior: mientras
            // corre la búsqueda de una consulta relacionada, la lista sigue visible en vez de
            // vaciarse y mostrar la carga con cada tecla. Catálogo, negocio o asociación la anulan.
            nameSearchJob?.cancel()
            nameSearchJob = null
            val query = value.take(MAX_QUERY_LENGTH)
            nameSearchJob =
                executeMain {
                    // Un refresco automatico puede quedar encolado mientras otra accion (por ejemplo, una
                    // asociacion ya confirmada) limpia el campo. No debe resucitar una consulta obsoleta.
                    if (onlyIfQueryUnchanged && uiState.value.query != query) return@executeMain
                    updateState {
                        copy(
                            query = query,
                            isNameSearchRunning = query.trim().length >= MIN_NAME_QUERY_LENGTH,
                            searchFailed = false,
                            failure = failure.takeIf { preserveFailure },
                        ).withFilteredOptions()
                    }
                    if (query.trim().length < MIN_NAME_QUERY_LENGTH) return@executeMain
                    val businessId =
                        activeBusinessId ?: run {
                            updateState { copy(isNameSearchRunning = false) }
                            return@executeMain
                        }
                    delay(NAME_SEARCH_DEBOUNCE_MILLIS)
                    try {
                        val candidates =
                            withContext(dispatcherProvider.io) {
                                productMatching.searchByName(businessId, query, minimumPrefixLength = MIN_NAME_QUERY_LENGTH)
                            }
                        if (uiState.value.query == query && activeBusinessId == businessId) {
                            nameSearchSnapshot = NameSearchSnapshot(businessId, query, candidates)
                            updateState {
                                copy(
                                    isNameSearchRunning = false,
                                    searchFailed = false,
                                ).withFilteredOptions()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        if (uiState.value.query == query && activeBusinessId == businessId) {
                            updateState {
                                copy(
                                    isNameSearchRunning = false,
                                    productOptions = emptyList(),
                                    searchFailed = true,
                                )
                            }
                        }
                    }
                }
        }

        private fun updateLineInput(
            lineId: String,
            quantity: String? = null,
            price: String? = null,
        ) {
            if (dataActionsBlocked()) return
            executeMain {
                if (uiState.value.cartLines.none { it.lineId == lineId }) return@executeMain
                quantity?.let { quantityInputs[lineId] = it.take(MAX_DECIMAL_LENGTH) }
                price?.let { priceInputs[lineId] = it.take(MAX_DECIMAL_LENGTH) }
                applyCart(domainCart)
                lineEditJobs.remove(lineId)?.cancel()
                lineEditJobs[lineId] =
                    executeMain {
                        delay(EDIT_DEBOUNCE_MILLIS)
                        // Desde aquí ya es un guardado activo: Back/Retry cancelan solo debounces que
                        // todavía no comenzaron y esperan este trabajo mediante operationMutex.
                        lineEditJobs.remove(lineId)
                        persistLineEdit(lineId)
                    }
            }
        }

        private fun adjustQuantity(
            lineId: String,
            delta: BigDecimal,
        ) {
            if (dataActionsBlocked()) return
            val current = uiState.value.cartLines.firstOrNull { it.lineId == lineId } ?: return
            val parsed = parseQuantity(current.quantityInput) ?: return
            val next = parsed.value.add(delta)
            if (next.signum() <= 0) return
            if (next > current.availableQuantity) {
                executeMain { updateState { copy(failure = SalesContract.Failure.INSUFFICIENT_STOCK) } }
                return
            }
            updateLineInput(lineId, quantity = next.stripTrailingZeros().toPlainString())
        }

        private suspend fun persistLineEdit(lineId: String) {
            operationMutex.withLock {
                val cart = domainCart ?: return@withLock
                val domainLine = cart.lines.firstOrNull { it.saleLineId.value == lineId } ?: return@withLock
                val uiLine = uiState.value.cartLines.firstOrNull { it.lineId == lineId } ?: return@withLock
                val quantity = parseQuantity(uiLine.quantityInput)
                if (quantity == null) {
                    updateState { copy(failure = SalesContract.Failure.INVALID_QUANTITY) }
                    return@withLock
                }
                val price = parsePrice(uiLine.unitPriceInput)
                if (uiLine.unitPriceInput.isNotBlank() && price == null) {
                    updateState { copy(failure = SalesContract.Failure.INVALID_PRICE) }
                    return@withLock
                }
                if (quantity.value > uiLine.availableQuantity) {
                    updateState { copy(failure = SalesContract.Failure.INSUFFICIENT_STOCK) }
                    return@withLock
                }
                val capturedQuantity = uiLine.quantityInput
                val capturedPrice = uiLine.unitPriceInput
                // El mutex conserva CAS y orden de versiones, pero este autoguardado no vuelve
                // inmóvil el formulario: una edición posterior queda en los mapas y se guarda después.
                updateState { copy(isSavingLineEdits = true, failure = null) }
                try {
                    val result =
                        withContext(dispatcherProvider.io) {
                            saveLine.forBusiness(
                                cart.businessId,
                                SaveSaleCartLineCommand(
                                    saleId = cart.saleId,
                                    expectedVersion = cart.version,
                                    saleLineId = domainLine.saleLineId,
                                    productId = domainLine.productId,
                                    locationId = domainLine.locationId,
                                    quantity = quantity,
                                    unitPrice = price,
                                    discount = price?.let { domainLine.discount },
                                    tax = price?.let { domainLine.tax },
                                ),
                            )
                        }
                    if (result is SaleCartMutationResult.Saved) {
                        if (quantityInputs[lineId] == capturedQuantity) quantityInputs.remove(lineId)
                        if (priceInputs[lineId] == capturedPrice) priceInputs.remove(lineId)
                    }
                    handleMutationResult(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    updateState { copy(failure = SalesContract.Failure.SAVE_FAILED) }
                } finally {
                    updateState { copy(isSavingLineEdits = false) }
                }
            }
        }

        private suspend fun flushPendingLineEdits(): Boolean {
            cancelScheduledLineEdits()
            // Un save que ya cruzó el debounce no se cancela. Al terminar habrá quitado únicamente
            // los inputs exactos que confirmó, dejando cualquier edición posterior en los mapas.
            operationMutex.withLock { }
            if (!hasPendingLineEdits()) return true
            val cart =
                domainCart ?: run {
                    updateState { copy(failure = SalesContract.Failure.STALE_CART) }
                    return false
                }
            val validLineIds = cart.lines.mapTo(mutableSetOf()) { it.saleLineId.value }
            if ((quantityInputs.keys + priceInputs.keys).any { it !in validLineIds }) {
                updateState { copy(failure = SalesContract.Failure.STALE_CART) }
                return false
            }
            val pendingLineIds = (quantityInputs.keys + priceInputs.keys).toSet()
            for (lineId in pendingLineIds) {
                persistLineEdit(lineId)
                if (lineId in quantityInputs || lineId in priceInputs) return false
            }
            return !hasPendingLineEdits()
        }

        private fun hasPendingLineEdits(): Boolean = quantityInputs.isNotEmpty() || priceInputs.isNotEmpty()

        private fun dataActionsBlocked(): Boolean =
            uiState.value.let { state ->
                state.entryStep != SalesContract.EntryStep.SELL || state.catalogLoadFailed || state.cartLoadFailed ||
                    state.isCheckoutPending || state.productRegistration != null
            }

        private fun removeCartLine(rawLineId: String) {
            if (dataActionsBlocked()) return
            val lineId = SaleLineId.parse(rawLineId) ?: return
            lineEditJobs.remove(rawLineId)?.cancel()
            launchMutation(oneShot = true) {
                val cart = domainCart ?: return@launchMutation
                val result =
                    withContext(dispatcherProvider.io) {
                        removeLine(cart.saleId, lineId, cart.version)
                    }
                if (result is SaleCartMutationResult.Saved) {
                    quantityInputs.remove(rawLineId)
                    priceInputs.remove(rawLineId)
                }
                handleMutationResult(result)
            }
        }

        private fun checkoutCurrentCart() {
            val state = uiState.value
            val cart = domainCart ?: return
            // Se captura en el propio callback del botón, antes de encolar trabajo en Main.
            // El usuario registra exactamente el carrito y los términos que estaba viendo.
            // Si falla el catálogo al iniciar, aún se puede verificar un cobro pendiente;
            // el caso de uso y Room comprueban el negocio actual antes de registrar.
            if (checkoutRequestPending || !state.canCheckout || backRequested || deferredContextSnapshot != null ||
                hasPendingLineEdits() || state.cartId != cart.saleId.value ||
                state.cartVersion != cart.version || state.cartContentHash != cart.contentHash ||
                state.total != cart.total || (activeBusinessId != null && activeBusinessId != cart.businessId)
            ) return
            val command = try {
                CheckoutSaleCommand(
                    saleId = cart.saleId,
                    expectedVersion = cart.version,
                    expectedContentHash = cart.contentHash,
                    debtorName = if (cart.pendingCheckout != null) {
                        cart.pendingCheckout.debtorName
                    } else {
                        state.canonicalDebtorName.takeIf { state.entryKind == SalesContract.EntryKind.CREDIT }
                    },
                    debtDueAt = cart.pendingCheckout?.debtDueAt,
                )
            } catch (_: IllegalArgumentException) {
                executeMain {
                    if (domainCart?.saleId == cart.saleId) {
                        updateState { copy(failure = SalesContract.Failure.CHECKOUT_FAILED) }
                    }
                }
                return
            }
            checkoutRequestPending = true
            val job = launchMutation(oneShot = true) {
                val current = domainCart
                if (backRequested) return@launchMutation
                if (current == null || current.saleId != cart.saleId || current.businessId != cart.businessId ||
                    current.version != command.expectedVersion || current.contentHash != command.expectedContentHash ||
                    (activeBusinessId != null && activeBusinessId != cart.businessId) || deferredContextSnapshot != null
                ) {
                    updateState { copy(failure = SalesContract.Failure.STALE_CART) }
                    return@launchMutation
                }
                val currentState = uiState.value
                if (hasPendingLineEdits() || !currentState.copy(isMutating = false).canCheckout ||
                    currentState.entryKind != state.entryKind ||
                    (cart.pendingCheckout == null && currentState.canonicalDebtorName != state.canonicalDebtorName)
                ) return@launchMutation
                // Version/hash y la transacción del repositorio siguen protegiendo la venta;
                // eliminar el diálogo no elimina sus validaciones ni la idempotencia.
                val result = withContext(dispatcherProvider.io) { checkout(command) }
                if (result !is CheckoutSaleResult.Posted && result !is CheckoutSaleResult.AlreadyPosted) {
                    // An uncertain attempt may have persisted a pending intent. Refresh its lock,
                    // but never let this secondary read replace the actual checkout result.
                    val refreshed =
                        try {
                            withContext(dispatcherProvider.io) { observeCart(cart.saleId).first() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    if (refreshed != null) {
                        domainCart = refreshed
                        applyCart(refreshed, preserveFailure = true)
                    } else {
                        // Preserve known pending terms and stop edits until a retry can read Room.
                        updateState { copy(cartLoadFailed = true) }
                    }
                }
                when (result) {
                    is CheckoutSaleResult.Posted,
                    is CheckoutSaleResult.AlreadyPosted,
                    -> {
                        if (command.debtorName != null) {
                            emitEffect(SalesContract.Effect.CreditSalePosted)
                            return@launchMutation
                        }
                        emitEffect(SalesContract.Effect.ShowMessage(SalesContract.Message.SALE_POSTED))
                        quantityInputs.clear()
                        priceInputs.clear()
                        val next =
                            withContext(dispatcherProvider.io) {
                                val current = configuration.current()
                                current.activeBusinessId?.let { businessId ->
                                    createCart.forBusiness(businessId, current.currency)
                                } ?: CreateSaleCartResult.NoActiveBusiness
                            }
                        when (next) {
                            is CreateSaleCartResult.Created -> {
                                attachCart(next.cart)
                            }

                            is CreateSaleCartResult.Resumed -> {
                                attachCart(next.cart)
                            }

                            CreateSaleCartResult.NoActiveBusiness -> {
                                updateState {
                                    copy(failure = SalesContract.Failure.NO_ACTIVE_BUSINESS)
                                }
                            }
                        }
                    }

                    CheckoutSaleResult.Stale,
                    CheckoutSaleResult.CartChanged,
                    CheckoutSaleResult.RetryableConflict,
                    -> {
                        updateState { copy(failure = SalesContract.Failure.STALE_CART) }
                    }

                    is CheckoutSaleResult.IncompleteLine -> {
                        updateState {
                            copy(failure = SalesContract.Failure.INCOMPLETE_LINE)
                        }
                    }

                    is CheckoutSaleResult.InsufficientStock -> {
                        updateState {
                            copy(failure = SalesContract.Failure.INSUFFICIENT_STOCK)
                        }
                    }

                    CheckoutSaleResult.OnlineRequired -> {
                        updateState {
                            copy(failure = SalesContract.Failure.ONLINE_REQUIRED)
                        }
                    }

                    CheckoutSaleResult.InventoryMigrationRequired -> {
                        updateState {
                            copy(failure = SalesContract.Failure.INVENTORY_MIGRATION_REQUIRED)
                        }
                    }

                    CheckoutSaleResult.RemoteRejected -> {
                        updateState {
                            copy(failure = SalesContract.Failure.CHECKOUT_FAILED)
                        }
                    }

                    CheckoutSaleResult.InvalidTotals -> {
                        updateState {
                            copy(failure = SalesContract.Failure.CHECKOUT_FAILED)
                        }
                    }

                    CheckoutSaleResult.InvalidCreditTerms -> {
                        updateState {
                            copy(failure = SalesContract.Failure.INVALID_CREDIT_TERMS)
                        }
                    }

                    CheckoutSaleResult.EmptyCart -> {
                        updateState {
                            copy(failure = SalesContract.Failure.INCOMPLETE_LINE)
                        }
                    }

                    CheckoutSaleResult.ProductUnavailable,
                    CheckoutSaleResult.LocationUnavailable,
                    -> {
                        updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                    }

                    CheckoutSaleResult.NoActiveBusiness -> {
                        updateState {
                            copy(failure = SalesContract.Failure.NO_ACTIVE_BUSINESS)
                        }
                    }

                    CheckoutSaleResult.NotFound -> {
                        updateState {
                            copy(failure = SalesContract.Failure.CHECKOUT_FAILED)
                        }
                    }
                }
            }
            if (job == null) {
                checkoutRequestPending = false
            } else {
                job.invokeOnCompletion { checkoutRequestPending = false }
            }
        }

        private fun launchMutation(
            oneShot: Boolean = false,
            isBarcodeMutation: Boolean = false,
            block: suspend () -> Unit,
        ): Job? {
            if (
                oneShot &&
                (
                    oneShotMutationPending || uiState.value.isMutating ||
                        uiState.value.isSavingLineEdits || operationMutex.isLocked
                )
            ) {
                return null
            }
            if (oneShot) oneShotMutationPending = true
            return executeMain {
                try {
                    operationMutex.withLock {
                        updateState {
                            copy(
                                isMutating = true,
                                isProcessingBarcode = isBarcodeMutation,
                                failure = null,
                                barcodeAssociatedWithoutCartAdd = false,
                                productRegisteredWithoutCartAdd = false,
                            )
                        }
                        try {
                            block()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            updateState { copy(failure = SalesContract.Failure.SAVE_FAILED) }
                        } finally {
                            updateState { copy(isMutating = false, isProcessingBarcode = false) }
                        }
                    }
                } finally {
                    if (oneShot) oneShotMutationPending = false
                }
            }
        }

        private suspend fun handleMutationResult(
            result: SaleCartMutationResult,
            successMessage: SalesContract.Message? = null,
        ) {
            when (result) {
                is SaleCartMutationResult.Saved -> {
                    domainCart = result.cart
                    applyCart(result.cart)
                    if (!backRequested && !hasPendingLineEdits()) {
                        deferredContextSnapshot?.let { deferred ->
                            deferredContextSnapshot = null
                            applyCatalogAndInventorySnapshot(deferred)
                        }
                    }
                    successMessage?.let {
                        emitEffect(SalesContract.Effect.ShowMessage(it))
                    }
                }

                SaleCartMutationResult.NoActiveBusiness -> {
                    updateState {
                        copy(failure = SalesContract.Failure.NO_ACTIVE_BUSINESS)
                    }
                }

                SaleCartMutationResult.ProductUnavailable,
                SaleCartMutationResult.LocationUnavailable,
                -> {
                    updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                }

                SaleCartMutationResult.Stale -> {
                    updateState {
                        copy(failure = SalesContract.Failure.STALE_CART)
                    }
                }

                SaleCartMutationResult.InvalidTotals -> {
                    updateState {
                        copy(failure = SalesContract.Failure.INVALID_PRICE)
                    }
                }

                SaleCartMutationResult.CheckoutPending -> {
                    updateState {
                        copy(isCheckoutPending = true, failure = SalesContract.Failure.ONLINE_REQUIRED)
                    }
                }

                SaleCartMutationResult.BarcodeRecoveryChanged,
                SaleCartMutationResult.CurrencyMismatch,
                SaleCartMutationResult.DuplicateProductLocation,
                SaleCartMutationResult.LineNotFound,
                SaleCartMutationResult.NotDraft,
                SaleCartMutationResult.NotFound,
                -> {
                    updateState { copy(failure = SalesContract.Failure.SAVE_FAILED) }
                }
            }
        }

        private fun applyCart(
            cart: SaleCart?,
            preserveFailure: Boolean = false,
        ) {
            val current = cart ?: return
            val cartLines = current.lines.map { line -> line.toUiLine() }
            val displayTotal =
                cartLines
                    .mapNotNull(SalesContract.CartLine::lineTotal)
                    .takeIf { it.size == cartLines.size }
                    ?.fold(Money.zero(current.currency), Money::plus)
            updateState {
                copy(
                    isLoading = false,
                    cartId = current.saleId.value,
                    isCheckoutPending = current.pendingCheckout != null,
                    entryStep = if (current.pendingCheckout != null) SalesContract.EntryStep.SELL else entryStep,
                    entryKind =
                        if (current.pendingCheckout != null) {
                            if (current.pendingCheckout.debtorName == null) SalesContract.EntryKind.CASH else SalesContract.EntryKind.CREDIT
                        } else {
                            entryKind
                        },
                    debtorNameInput = current.pendingCheckout?.debtorName ?: debtorNameInput,
                    currencyCode = current.currency.value,
                    cartVersion = current.version,
                    cartContentHash = current.contentHash,
                    cartLines = cartLines,
                    lastScanAdded =
                        lastScanAdded?.let { last ->
                            current.lines
                                .firstOrNull {
                                    it.productId == last.productId && it.locationId == last.locationId
                                }?.let { line -> last.copy(quantity = line.quantity.value) }
                        },
                    hasPendingEdits = quantityInputs.isNotEmpty() || priceInputs.isNotEmpty(),
                    total = displayTotal,
                    failure =
                        if (preserveFailure) {
                            failure
                        } else {
                            failure.takeIf {
                                it == SalesContract.Failure.NO_ACTIVE_BUSINESS && activeBusinessId == null
                            }
                        },
                ).withFilteredOptions()
            }
        }

        private fun SaleCartLine.toUiLine(): SalesContract.CartLine {
            val id = saleLineId.value
            val quantityInput =
                quantityInputs[id]
                    ?: quantity.value.stripTrailingZeros().toPlainString()
            val priceInput = priceInputs[id] ?: unitPrice?.toMajor()?.toPlainString().orEmpty()
            val parsedQuantity = parseQuantity(quantityInput)
            val parsedPrice = parsePrice(priceInput, domainCart?.currency ?: configuredCurrency)
            val localTotal =
                if (parsedQuantity != null && parsedPrice != null) {
                    runCatching {
                        calculateSaleLineTotal(
                            unitPrice = parsedPrice,
                            quantity = parsedQuantity,
                            discount = discount,
                            tax = tax,
                        )
                    }.getOrNull()
                } else {
                    null
                }
            val available =
                inventoryById[productId]
                    ?.positions
                    ?.firstOrNull { it.locationId == locationId }
                    ?.quantityOnHand
                    ?: BigDecimal.ZERO
            return SalesContract.CartLine(
                lineId = id,
                productId = productId,
                productName = productName,
                locationId = locationId,
                locationName = locationName,
                unitCode = unitCode,
                availableQuantity = available,
                quantityInput = quantityInput,
                unitPriceInput = priceInput,
                quantityValid = parsedQuantity != null && parsedQuantity.value <= available,
                priceValid = priceInput.isBlank() || parsedPrice != null,
                lineTotal = localTotal,
                isWeightProduct = isWeightSaleLine(),
            )
        }

        private fun SalesContract.State.withFilteredOptions(): SalesContract.State =
            copy(
                productOptions = productOptionsFor(query),
                availableProducts = orderedAvailableProducts,
                barcodeSuggestions =
                    if (pendingAssociationBarcode == null) {
                        emptyList()
                    } else {
                        barcodeSuggestions.mapNotNull { suggestion ->
                            val option =
                                availableProductOptionsByProduct[suggestion.product.productId]
                                    ?.firstOrNull { it.locationId == suggestion.product.locationId }
                                    ?.takeIf { it.barcode == suggestion.product.barcode }
                            option?.let { suggestion.copy(product = it) }
                        }
                    },
            )

        private fun productOptionsFor(query: String): List<SalesContract.ProductOption> {
            if (query.trim().length < MIN_NAME_QUERY_LENGTH) return emptyList()
            val businessId = activeBusinessId ?: return emptyList()
            val snapshot =
                nameSearchSnapshot?.takeIf {
                    it.businessId == businessId && (it.query == query || isRelatedNameQuery(it.query, query))
                } ?: return emptyList()
            val catalog = availableProductOptionsByProduct
            nameSearchOptions?.let { cached ->
                if (cached.snapshot === snapshot && cached.catalog === catalog) return cached.options
            }
            return projectNameSearchOptions(snapshot, catalog).also { options ->
                nameSearchOptions = NameSearchOptions(snapshot, catalog, options)
            }
        }

        /** Una consulta extiende o recorta la anterior: sus resultados siguen siendo pertinentes mientras llega la nueva. */
        private fun isRelatedNameQuery(
            previous: String,
            current: String,
        ): Boolean {
            val before = previous.trim().lowercase(Locale.ROOT)
            val after = current.trim().lowercase(Locale.ROOT)
            return before.length >= MIN_NAME_QUERY_LENGTH && after.length >= MIN_NAME_QUERY_LENGTH &&
                (after.startsWith(before) || before.startsWith(after))
        }

        private fun projectNameSearchOptions(
            snapshot: NameSearchSnapshot,
            catalog: Map<ProductId, List<SalesContract.ProductOption>>,
        ): List<SalesContract.ProductOption> =
            snapshot.candidates.flatMap { candidate ->
                val matchKind =
                    when (candidate.reason) {
                        ProductMatchReason.EXACT_NAME -> SalesContract.NameMatchKind.EXACT
                        ProductMatchReason.SIMILAR_NAME -> SalesContract.NameMatchKind.SIMILAR
                        else -> return@flatMap emptyList()
                    }
                catalog[candidate.product.productId].orEmpty().map { option ->
                    option.copy(nameMatchKind = matchKind)
                }
            }

        private fun replaceProjectedProduct(product: Product) {
            val previousOptions = availableProductOptionsByProduct[product.productId].orEmpty()
            if (previousOptions.isEmpty()) return
            val replacements =
                previousOptions.map { option ->
                    option.copy(
                        productName = product.name,
                        sku = product.sku,
                        barcode = product.barcode,
                        suggestedSalePrice = product.salePrice,
                        isWeightProduct = product.barcode == null &&
                            WeightSaleCalculator.isKilogramUnit(inventoryById[product.productId]?.unitCode.orEmpty()),
                    )
                }
            availableProductOptionsByProduct =
                availableProductOptionsByProduct + (product.productId to replacements)
            val replacementsByLocation = replacements.associateBy(SalesContract.ProductOption::locationId)
            val updatedProducts =
                orderedAvailableProducts.map { option ->
                    if (option.productId == product.productId) {
                        replacementsByLocation[option.locationId] ?: option
                    } else {
                        option
                    }
                }
            // El código/precio no afecta el orden. Si el repositorio devuelve también un nombre
            // actualizado, respeta ese cambio mientras llega la siguiente emisión del catálogo.
            orderedAvailableProducts =
                if (previousOptions.any { it.productName != product.name }) {
                    updatedProducts.sortedWith(SALES_PRODUCT_OPTION_ORDER)
                } else {
                    updatedProducts
                }
        }

        private fun parseQuantity(input: String): Quantity? =
            try {
                Quantity.of(input.trim().replace(',', '.'))
            } catch (_: DomainRuleViolation) {
                null
            }

        private fun parsePrice(
            input: String,
            currency: CurrencyCode = domainCart?.currency ?: configuredCurrency,
        ): Money? {
            val canonical = input.trim().replace(',', '.')
            if (canonical.isEmpty()) return null
            return try {
                Money.fromMajor(canonical, currency).takeIf { it.minorUnits > 0L }
            } catch (_: DomainRuleViolation) {
                null
            }
        }

        private companion object {
            const val MAX_PENDING_BARCODES = 32
            const val MAX_QUERY_LENGTH = ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH
            const val MAX_DECIMAL_LENGTH = 64
            const val NAME_SEARCH_DEBOUNCE_MILLIS = 250L
            const val MIN_NAME_QUERY_LENGTH = 2
            const val EDIT_DEBOUNCE_MILLIS = 350L
        }
    }

private const val ENTRY_INITIALIZED_KEY = "sales.entry.initialized"
private const val ENTRY_KIND_KEY = "sales.entry.kind"
private const val ENTRY_MODE_KEY = "sales.entry.mode"
private const val ENTRY_STEP_KEY = "sales.entry.step"
private const val ENTRY_UNIFIED_INPUT_KEY = "sales.entry.unifiedInput"
private const val PRODUCT_REGISTRATION_KEY = "sales.productRegistration.request"
private const val PRODUCT_REGISTRATION_RESULT_KEY = "sales.productRegistration.result"

private fun restoredProductRegistration(savedStateHandle: SavedStateHandle): SalesContract.ProductRegistrationRequest? {
    val fields = savedStateHandle.get<ArrayList<String>>(PRODUCT_REGISTRATION_KEY) ?: return null
    if (fields.size != 4 || runCatching { UUID.fromString(fields[0]) }.isFailure) return null
    return SalesContract.ProductRegistrationRequest(
        requestId = fields[0],
        barcode = BarcodeValue.parse(fields[1])?.value ?: return null,
        businessId = BusinessId.parse(fields[2]) ?: return null,
        saleId = SaleId.parse(fields[3]) ?: return null,
    )
}

private fun restoredRegistrationResult(savedStateHandle: SavedStateHandle): SalesContract.ProductRegistrationResult? {
    val fields = savedStateHandle.get<ArrayList<String>>(PRODUCT_REGISTRATION_RESULT_KEY) ?: return null
    if (fields.size != 3 || runCatching { UUID.fromString(fields[0]) }.isFailure) return null
    if (fields[1].isEmpty() && fields[2].isEmpty()) return SalesContract.ProductRegistrationResult(fields[0])
    return SalesContract.ProductRegistrationResult(
        requestId = fields[0],
        productId = ProductId.parse(fields[1]) ?: return null,
        businessId = BusinessId.parse(fields[2]) ?: return null,
    )
}

private fun restoredSalesState(savedStateHandle: SavedStateHandle): SalesContract.State =
    SalesContract.State(
        unifiedInput = savedStateHandle.get<Boolean>(ENTRY_UNIFIED_INPUT_KEY) == true,
        productRegistration = restoredProductRegistration(savedStateHandle),
        entryKind =
            SalesContract.EntryKind.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(ENTRY_KIND_KEY)
            } ?: SalesContract.EntryKind.CASH,
        mode =
            SalesContract.EntryMode.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(ENTRY_MODE_KEY)
            } ?: SalesContract.EntryMode.SCANNER,
        entryStep =
            SalesContract.EntryStep.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(ENTRY_STEP_KEY)
            } ?: SalesContract.EntryStep.SELECT_KIND,
    )
