package com.facturastock.app.feature.sales

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
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class SalesViewModel @Inject constructor(
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
) : UdfViewModel<SalesContract.State, SalesContract.Action, SalesContract.Effect>(
    initialState = SalesContract.State(),
    dispatcherProvider = dispatcherProvider,
) {
    private data class CatalogInventorySnapshot(
        val businessId: BusinessId? = null,
        val currency: CurrencyCode = CurrencyCode.of("PEN"),
        val productsById: Map<ProductId, Product> = emptyMap(),
        val inventoryById: Map<ProductId, InventoryReadItem> = emptyMap(),
        val availableProductOptionsByProduct:
            Map<ProductId, List<SalesContract.ProductOption>> = emptyMap(),
    )

    private data class CatalogContext(
        val businessId: BusinessId?,
        val currency: CurrencyCode,
    )

    private data class CatalogInventoryRows(
        val context: CatalogContext,
        val products: List<Product>,
        val inventory: List<InventoryReadItem>,
    )

    private data class NameSearchSnapshot(
        val businessId: BusinessId,
        val query: String,
        val candidates: List<ProductMatchCandidate>,
    )

    private val operationMutex = Mutex()
    private var oneShotMutationPending = false
    private var backRequested = false
    private val lineEditJobs = mutableMapOf<String, Job>()
    private val quantityInputs = mutableMapOf<String, String>()
    private val priceInputs = mutableMapOf<String, String>()
    private var cartObservation: Job? = null
    private var cartRecovery: Job? = null
    private var catalogObservation: Job? = null
    private var nameSearchJob: Job? = null
    private var nameSearchSnapshot: NameSearchSnapshot? = null
    private var deferredContextSnapshot: CatalogInventorySnapshot? = null
    private var activeBusinessId: BusinessId? = null
    private var configuredCurrency: CurrencyCode = CurrencyCode.of("PEN")
    private var domainCart: SaleCart? = null
    private var productsById: Map<ProductId, Product> = emptyMap()
    private var inventoryById: Map<ProductId, InventoryReadItem> = emptyMap()
    private var availableProductOptionsByProduct:
        Map<ProductId, List<SalesContract.ProductOption>> = emptyMap()

    init {
        observeCatalogAndInventory()
        openCart()
    }

    override fun onAction(action: SalesContract.Action) {
        if (backRequested && action !is SalesContract.Action.ScannerAvailabilityChanged) return
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
            action !is SalesContract.Action.BackSelected &&
            action !is SalesContract.Action.DiscardEditsConfirmed &&
            action !is SalesContract.Action.DiscardEditsDismissed &&
            action !is SalesContract.Action.ScannerAvailabilityChanged
        ) {
            return
        }
        if (
            uiState.value.checkoutReview != null &&
            action !is SalesContract.Action.CheckoutConfirmed &&
            action !is SalesContract.Action.CheckoutDismissed &&
            action !is SalesContract.Action.ScannerAvailabilityChanged &&
            action !is SalesContract.Action.BackSelected
        ) {
            return
        }
        if (
            uiState.value.discardEditsReview &&
            action !is SalesContract.Action.DiscardEditsConfirmed &&
            action !is SalesContract.Action.DiscardEditsDismissed &&
            action !is SalesContract.Action.ScannerAvailabilityChanged
        ) {
            return
        }
        when (action) {
            SalesContract.Action.Retry -> retryFailedOperation()
            SalesContract.Action.RetryCatalog -> observeCatalogAndInventory()
            SalesContract.Action.RetryCart -> retryCartObservation()
            SalesContract.Action.RetrySearch -> retrySearch()
            is SalesContract.Action.EntryKindChanged -> executeMain {
                updateState {
                    copy(
                        entryKind = action.kind,
                        debtorNameInput = if (action.kind == SalesContract.EntryKind.CASH) {
                            ""
                        } else {
                            debtorNameInput
                        },
                        failure = if (failure == SalesContract.Failure.INVALID_CREDIT_TERMS) {
                            null
                        } else {
                            failure
                        },
                    )
                }
            }
            is SalesContract.Action.DebtorNameChanged -> executeMain {
                updateState {
                    copy(
                        debtorNameInput = action.value.take(
                            SalesContract.MAX_DEBTOR_NAME_LENGTH + 1,
                        ),
                    )
                }
            }
            is SalesContract.Action.ModeChanged -> executeMain {
                updateState {
                    copy(
                        mode = action.mode,
                        pendingLocations = emptyList(),
                        failure = null,
                    )
                }
            }
            is SalesContract.Action.ScannerAvailabilityChanged -> executeMain {
                updateState { copy(scannerActive = action.active) }
            }
            is SalesContract.Action.BarcodeScanned -> handleBarcode(action.value)
            is SalesContract.Action.SearchChanged -> updateQuery(action.value)
            is SalesContract.Action.ProductSelected ->
                selectProduct(action.productId, action.locationId)
            is SalesContract.Action.LocationSelected ->
                selectPendingLocation(action.productId, action.locationId)
            SalesContract.Action.LocationSelectionDismissed -> executeMain {
                updateState { copy(pendingLocations = emptyList()) }
            }
            SalesContract.Action.AssociationDismissed -> executeMain {
                updateState {
                    copy(
                        pendingAssociationBarcode = null,
                        pendingReplacement = null,
                        query = "",
                        isNameSearchRunning = false,
                        searchFailed = false,
                        failure = null,
                    ).withFilteredOptions()
                }
            }
            SalesContract.Action.BarcodeReplacementConfirmed -> confirmBarcodeReplacement()
            SalesContract.Action.BarcodeReplacementDismissed -> executeMain {
                updateState { copy(pendingReplacement = null) }
            }
            is SalesContract.Action.QuantityChanged ->
                updateLineInput(action.lineId, quantity = action.value)
            is SalesContract.Action.UnitPriceChanged ->
                updateLineInput(action.lineId, price = action.value)
            is SalesContract.Action.QuantityIncremented -> adjustQuantity(action.lineId, BigDecimal.ONE)
            is SalesContract.Action.QuantityDecremented -> adjustQuantity(action.lineId, BigDecimal.ONE.negate())
            is SalesContract.Action.LineRemoved -> removeCartLine(action.lineId)
            SalesContract.Action.CheckoutRequested -> executeMain {
                val state = uiState.value
                val cart = domainCart
                if (
                    state.canCheckout && cart != null && state.total != null &&
                    state.cartId == cart.saleId.value && state.cartVersion == cart.version &&
                    state.cartContentHash == cart.contentHash
                ) {
                    updateState {
                        copy(
                            checkoutReview = SalesContract.CheckoutReview(
                                cartId = cart.saleId.value,
                                version = cart.version,
                                contentHash = cart.contentHash,
                                total = state.total,
                                debtorName = state.canonicalDebtorName,
                            ),
                            failure = null,
                        )
                    }
                }
            }
            SalesContract.Action.CheckoutConfirmed -> confirmCheckout()
            SalesContract.Action.CheckoutDismissed -> executeMain {
                if (!uiState.value.isMutating) {
                    updateState { copy(checkoutReview = null) }
                }
            }
            SalesContract.Action.DiscardEditsConfirmed -> discardPendingEditsAndGoBack()
            SalesContract.Action.DiscardEditsDismissed -> executeMain {
                updateState { copy(discardEditsReview = false) }
            }
            SalesContract.Action.BackSelected -> savePendingEditsAndGoBack()
        }
    }

    private fun savePendingEditsAndGoBack() {
        if (backRequested) return
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
        catalogObservation = executeMain {
            try {
                configuration.observe()
                // Cambios de preferencias ajenos a Ventas no deben reiniciar dos consultas Room
                // ni reconstruir la proyeccion completa del selector de productos.
                .map { current -> CatalogContext(current.activeBusinessId, current.currency) }
                .distinctUntilChanged()
                .flatMapLatest { context ->
                    if (context.businessId == null) {
                        flowOf(CatalogInventorySnapshot(currency = context.currency))
                    } else {
                        combine(
                            products.observeForBusiness(context.businessId),
                            inventoryReads.observeInventory(context.businessId),
                        ) { productRows, inventoryRows ->
                            CatalogInventoryRows(
                                context = context,
                                products = productRows,
                                inventory = inventoryRows,
                            )
                        }
                            .distinctUntilChanged()
                            // La igualdad profunda de listas también debe quedar fuera de Main.
                            .flowOn(dispatcherProvider.default)
                            // associateBy/sort/group puede ser costoso con catalogos grandes. El
                            // collector mantiene las mutaciones de UI en Main, pero prepara cada
                            // snapshot cancelable en Default.
                            .mapLatest { rows ->
                                withContext(dispatcherProvider.default) {
                                    prepareCatalogInventorySnapshot(rows)
                                }
                            }
                    }
                }
                    .collect(::handleCatalogAndInventorySnapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateState { copy(isLoading = false, catalogLoadFailed = true) }
            }
        }
    }

    private suspend fun handleCatalogAndInventorySnapshot(snapshot: CatalogInventorySnapshot) {
        updateState { copy(catalogLoadFailed = false) }
        val cartContextChanged = domainCart?.let { cart ->
            cart.businessId != snapshot.businessId || cart.currency != snapshot.currency
        } == true
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
        val nameSearchToRefresh = uiState.value.query.takeIf { query ->
            snapshot.businessId != null && query.isNotBlank() &&
                (businessChanged || catalogChanged)
        }
        val cartContextChanged = domainCart?.let { cart ->
            cart.businessId != snapshot.businessId || cart.currency != snapshot.currency
        } == true
        if (businessChanged || catalogChanged) {
            nameSearchJob?.cancel()
            nameSearchJob = null
            nameSearchSnapshot = null
        }
        activeBusinessId = snapshot.businessId
        configuredCurrency = snapshot.currency
        productsById = snapshot.productsById
        inventoryById = snapshot.inventoryById
        availableProductOptionsByProduct = snapshot.availableProductOptionsByProduct
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
                        pendingReplacement = null,
                        pendingLocations = emptyList(),
                        isNameSearchRunning = false,
                        failure = null,
                    )
                }
                openCart()
            }
            domainCart == null && !uiState.value.isLoading -> openCart()
            // Una invalidacion de catalogo/inventario refresca cantidades y opciones, pero no
            // constituye por si sola la recuperacion de un fallo de guardado/checkout visible.
            else -> applyCart(domainCart, preserveFailure = true)
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
                    val result = withContext(dispatcherProvider.io) {
                        val current = configuration.current()
                        current.activeBusinessId?.let { businessId ->
                            createCart.forBusiness(businessId, current.currency)
                        } ?: CreateSaleCartResult.NoActiveBusiness
                    }
                    when (result) {
                        is CreateSaleCartResult.Created -> attachCart(result.cart)
                        is CreateSaleCartResult.Resumed -> attachCart(result.cart)
                        CreateSaleCartResult.NoActiveBusiness -> updateState {
                            copy(
                                isLoading = false,
                                failure = SalesContract.Failure.NO_ACTIVE_BUSINESS,
                            )
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
        cartObservation = executeMain {
            try {
                observeCart(cart.saleId).collect { observed ->
                    if (observed != null && observed.status == SaleStatus.DRAFT) {
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
        cartRecovery = executeMain {
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
                        checkoutReview = null,
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
                    checkoutReview = null,
                    failure = null,
                )
            }
            openCart()
        }
    }

    private fun handleBarcode(rawValue: String, oneShot: Boolean = false) {
        if (dataActionsBlocked() || hasPendingLineEdits() || deferredContextSnapshot != null) return
        val barcode = BarcodeValue.parse(rawValue)
        if (barcode == null) {
            executeMain { updateState { copy(failure = SalesContract.Failure.INVALID_BARCODE) } }
            return
        }
        launchMutation(oneShot = oneShot) {
            val state = uiState.value
            if (
                hasPendingLineEdits() || deferredContextSnapshot != null ||
                state.isAssociating || state.pendingLocations.isNotEmpty() ||
                state.pendingReplacement != null || state.checkoutReview != null
            ) {
                return@launchMutation
            }
            val businessId = activeBusinessId
            if (businessId == null) {
                updateState { copy(failure = SalesContract.Failure.NO_ACTIVE_BUSINESS) }
                return@launchMutation
            }
            val product = withContext(dispatcherProvider.io) {
                products.findByBarcode(businessId, barcode.value)
            }
            if (product == null) {
                updateState {
                    copy(
                        pendingAssociationBarcode = barcode.value,
                        pendingReplacement = null,
                        pendingLocations = emptyList(),
                        query = "",
                        isNameSearchRunning = false,
                        searchFailed = false,
                        failure = null,
                    ).withFilteredOptions()
                }
                return@launchMutation
            }
            if (product.status != CatalogStatus.ACTIVE) {
                updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                return@launchMutation
            }
            val locations = availableProductOptionsByProduct[product.productId].orEmpty()
            when (locations.size) {
                0 -> updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                1 -> addOptionToCart(locations.single())
                else -> updateState {
                    copy(
                        pendingLocations = locations,
                        failure = null,
                    )
                }
            }
        }
    }

    private fun selectProduct(productId: ProductId, locationId: LocationId) {
        if (dataActionsBlocked()) return
        val option = availableProductOptionsByProduct[productId]?.firstOrNull {
            it.productId == productId && it.locationId == locationId
        } ?: run {
            executeMain { updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) } }
            return
        }
        if (uiState.value.pendingAssociationBarcode != null) {
            associateBarcode(option, allowReplacement = false)
        } else {
            launchMutation(oneShot = true) { addOptionToCart(option) }
        }
    }

    private fun selectPendingLocation(productId: ProductId, locationId: LocationId) {
        if (dataActionsBlocked()) return
        val option = uiState.value.pendingLocations.firstOrNull {
            it.productId == productId && it.locationId == locationId
        } ?: return
        launchMutation(oneShot = true) {
            updateState { copy(pendingLocations = emptyList()) }
            addOptionToCart(option)
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
                        pendingReplacement = SalesContract.BarcodeReplacement(
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
            val result = if (product.barcode == barcode) {
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
                    updateState {
                        copy(
                            pendingAssociationBarcode = null,
                            pendingReplacement = null,
                            query = "",
                            isNameSearchRunning = false,
                            searchFailed = false,
                            failure = null,
                        ).withFilteredOptions()
                    }
                    val added = try {
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
                is CatalogMutationResult.Duplicate -> updateState {
                    copy(
                        failure = if (result.field == CatalogDuplicateField.BARCODE) {
                            SalesContract.Failure.BARCODE_CONFLICT
                        } else {
                            SalesContract.Failure.SAVE_FAILED
                        },
                    )
                }
                CatalogMutationResult.Stale -> updateState {
                    copy(failure = SalesContract.Failure.STALE_CART)
                }
                is CatalogMutationResult.Invalid,
                CatalogMutationResult.NotFound,
                -> updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
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
    ): Boolean {
        val cart = domainCart ?: run {
            updateState { copy(failure = SalesContract.Failure.LOAD_FAILED) }
            return false
        }
        val existing = cart.lines.firstOrNull {
            it.productId == option.productId && it.locationId == option.locationId
        }
        val quantity = existing?.quantity?.value?.add(BigDecimal.ONE)
            ?: BigDecimal.ONE.min(option.availableQuantity)
        if (quantity > option.availableQuantity) {
            updateState { copy(failure = SalesContract.Failure.INSUFFICIENT_STOCK) }
            return false
        }
        val newLinePrice = option.suggestedSalePrice
            ?.takeIf { it.currency == cart.currency }
        val unitPrice = existing?.unitPrice ?: newLinePrice
        val result = withContext(dispatcherProvider.io) {
            saveLine.forBusiness(
                cart.businessId,
                SaveSaleCartLineCommand(
                    saleId = cart.saleId,
                    expectedVersion = cart.version,
                    saleLineId = existing?.saleLineId,
                    productId = option.productId,
                    locationId = option.locationId,
                    quantity = Quantity.of(quantity),
                    unitPrice = unitPrice,
                    discount = if (existing != null) {
                        existing.discount.takeIf { existing.unitPrice != null }
                    } else {
                        newLinePrice?.let { Money.zero(cart.currency) }
                    },
                    tax = if (existing != null) {
                        existing.tax.takeIf { existing.unitPrice != null }
                    } else {
                        newLinePrice?.let { Money.zero(cart.currency) }
                    },
                ),
            )
        }
        handleMutationResult(result, successMessage)
        return result is SaleCartMutationResult.Saved
    }

    private fun updateQuery(
        value: String,
        preserveFailure: Boolean = false,
        onlyIfQueryUnchanged: Boolean = false,
    ) {
        nameSearchJob?.cancel()
        nameSearchSnapshot = null
        val query = value.take(MAX_QUERY_LENGTH)
        nameSearchJob = executeMain {
            // Un refresco automatico puede quedar encolado mientras otra accion (por ejemplo, una
            // asociacion ya confirmada) limpia el campo. No debe resucitar una consulta obsoleta.
            if (onlyIfQueryUnchanged && uiState.value.query != query) return@executeMain
            updateState {
                copy(
                    query = query,
                    isNameSearchRunning = query.isNotBlank(),
                    searchFailed = false,
                    failure = failure.takeIf { preserveFailure },
                ).withFilteredOptions()
            }
            if (query.isBlank()) return@executeMain
            val businessId = activeBusinessId ?: run {
                updateState { copy(isNameSearchRunning = false) }
                return@executeMain
            }
            delay(NAME_SEARCH_DEBOUNCE_MILLIS)
            try {
                val candidates = withContext(dispatcherProvider.io) {
                    productMatching.searchByName(businessId, query)
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

    private fun updateLineInput(lineId: String, quantity: String? = null, price: String? = null) {
        if (dataActionsBlocked()) return
        executeMain {
            if (uiState.value.cartLines.none { it.lineId == lineId }) return@executeMain
            quantity?.let { quantityInputs[lineId] = it.take(MAX_DECIMAL_LENGTH) }
            price?.let { priceInputs[lineId] = it.take(MAX_DECIMAL_LENGTH) }
            applyCart(domainCart)
            lineEditJobs.remove(lineId)?.cancel()
            lineEditJobs[lineId] = executeMain {
                delay(EDIT_DEBOUNCE_MILLIS)
                // Desde aquí ya es un guardado activo: Back/Retry cancelan solo debounces que
                // todavía no comenzaron y esperan este trabajo mediante operationMutex.
                lineEditJobs.remove(lineId)
                persistLineEdit(lineId)
            }
        }
    }

    private fun adjustQuantity(lineId: String, delta: BigDecimal) {
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
                val result = withContext(dispatcherProvider.io) {
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
        val cart = domainCart ?: run {
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

    private fun hasPendingLineEdits(): Boolean =
        quantityInputs.isNotEmpty() || priceInputs.isNotEmpty()

    private fun dataActionsBlocked(): Boolean = uiState.value.let { state ->
        state.catalogLoadFailed || state.cartLoadFailed
    }

    private fun removeCartLine(rawLineId: String) {
        if (dataActionsBlocked()) return
        val lineId = SaleLineId.parse(rawLineId) ?: return
        lineEditJobs.remove(rawLineId)?.cancel()
        launchMutation(oneShot = true) {
            val cart = domainCart ?: return@launchMutation
            val result = withContext(dispatcherProvider.io) {
                removeLine(cart.saleId, lineId, cart.version)
            }
            if (result is SaleCartMutationResult.Saved) {
                quantityInputs.remove(rawLineId)
                priceInputs.remove(rawLineId)
            }
            handleMutationResult(result)
        }
    }

    private fun confirmCheckout() {
        if (dataActionsBlocked()) return
        launchMutation(oneShot = true) {
            val review = uiState.value.checkoutReview ?: return@launchMutation
            updateState { copy(checkoutReview = null) }
            val cart = domainCart ?: return@launchMutation
            if (
                review.cartId != cart.saleId.value || review.version != cart.version ||
                review.contentHash != cart.contentHash
            ) {
                updateState { copy(failure = SalesContract.Failure.STALE_CART) }
                return@launchMutation
            }
            val result = withContext(dispatcherProvider.io) {
                checkout(
                    CheckoutSaleCommand(
                        saleId = cart.saleId,
                        expectedVersion = review.version,
                        expectedContentHash = review.contentHash,
                        debtorName = review.debtorName,
                    ),
                )
            }
            when (result) {
                is CheckoutSaleResult.Posted,
                is CheckoutSaleResult.AlreadyPosted,
                -> {
                    if (review.debtorName != null) {
                        emitEffect(SalesContract.Effect.CreditSalePosted)
                        return@launchMutation
                    }
                    emitEffect(SalesContract.Effect.ShowMessage(SalesContract.Message.SALE_POSTED))
                    quantityInputs.clear()
                    priceInputs.clear()
                    val next = withContext(dispatcherProvider.io) {
                        val current = configuration.current()
                        current.activeBusinessId?.let { businessId ->
                            createCart.forBusiness(businessId, current.currency)
                        } ?: CreateSaleCartResult.NoActiveBusiness
                    }
                    when (next) {
                        is CreateSaleCartResult.Created -> attachCart(next.cart)
                        is CreateSaleCartResult.Resumed -> attachCart(next.cart)
                        CreateSaleCartResult.NoActiveBusiness -> updateState {
                            copy(failure = SalesContract.Failure.NO_ACTIVE_BUSINESS)
                        }
                    }
                }
                CheckoutSaleResult.Stale,
                CheckoutSaleResult.CartChanged,
                CheckoutSaleResult.RetryableConflict,
                -> updateState { copy(failure = SalesContract.Failure.STALE_CART) }
                is CheckoutSaleResult.IncompleteLine -> updateState {
                    copy(failure = SalesContract.Failure.INCOMPLETE_LINE)
                }
                is CheckoutSaleResult.InsufficientStock -> updateState {
                    copy(failure = SalesContract.Failure.INSUFFICIENT_STOCK)
                }
                CheckoutSaleResult.OnlineRequired -> updateState {
                    copy(failure = SalesContract.Failure.ONLINE_REQUIRED)
                }
                CheckoutSaleResult.InventoryMigrationRequired -> updateState {
                    copy(failure = SalesContract.Failure.INVENTORY_MIGRATION_REQUIRED)
                }
                CheckoutSaleResult.RemoteRejected -> updateState {
                    copy(failure = SalesContract.Failure.CHECKOUT_FAILED)
                }
                CheckoutSaleResult.InvalidTotals -> updateState {
                    copy(failure = SalesContract.Failure.CHECKOUT_FAILED)
                }
                CheckoutSaleResult.InvalidCreditTerms -> updateState {
                    copy(failure = SalesContract.Failure.INVALID_CREDIT_TERMS)
                }
                CheckoutSaleResult.EmptyCart -> updateState {
                    copy(failure = SalesContract.Failure.INCOMPLETE_LINE)
                }
                CheckoutSaleResult.ProductUnavailable,
                CheckoutSaleResult.LocationUnavailable,
                -> updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
                CheckoutSaleResult.NoActiveBusiness -> updateState {
                    copy(failure = SalesContract.Failure.NO_ACTIVE_BUSINESS)
                }
                CheckoutSaleResult.NotFound -> updateState {
                    copy(failure = SalesContract.Failure.CHECKOUT_FAILED)
                }
            }
        }
    }

    private fun launchMutation(
        oneShot: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (
            oneShot &&
            (
                oneShotMutationPending || uiState.value.isMutating ||
                    uiState.value.isSavingLineEdits || operationMutex.isLocked
                )
        ) {
            return
        }
        if (oneShot) oneShotMutationPending = true
        executeMain {
            try {
                operationMutex.withLock {
                    updateState {
                        copy(
                            isMutating = true,
                            failure = null,
                            barcodeAssociatedWithoutCartAdd = false,
                        )
                    }
                    try {
                        block()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        updateState { copy(failure = SalesContract.Failure.SAVE_FAILED) }
                    } finally {
                        updateState { copy(isMutating = false) }
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
            SaleCartMutationResult.NoActiveBusiness -> updateState {
                copy(failure = SalesContract.Failure.NO_ACTIVE_BUSINESS)
            }
            SaleCartMutationResult.ProductUnavailable,
            SaleCartMutationResult.LocationUnavailable,
            -> updateState { copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
            SaleCartMutationResult.Stale -> updateState {
                copy(failure = SalesContract.Failure.STALE_CART)
            }
            SaleCartMutationResult.InvalidTotals -> updateState {
                copy(failure = SalesContract.Failure.INVALID_PRICE)
            }
            SaleCartMutationResult.CurrencyMismatch,
            SaleCartMutationResult.DuplicateProductLocation,
            SaleCartMutationResult.LineNotFound,
            SaleCartMutationResult.NotDraft,
            SaleCartMutationResult.NotFound,
            -> updateState { copy(failure = SalesContract.Failure.SAVE_FAILED) }
        }
    }

    private fun applyCart(cart: SaleCart?, preserveFailure: Boolean = false) {
        val current = cart ?: return
        val cartLines = current.lines.map { line -> line.toUiLine() }
        val displayTotal = cartLines
            .mapNotNull(SalesContract.CartLine::lineTotal)
            .takeIf { it.size == cartLines.size }
            ?.fold(Money.zero(current.currency), Money::plus)
        updateState {
            copy(
                isLoading = false,
                cartId = current.saleId.value,
                currencyCode = current.currency.value,
                cartVersion = current.version,
                cartContentHash = current.contentHash,
                cartLines = cartLines,
                hasPendingEdits = quantityInputs.isNotEmpty() || priceInputs.isNotEmpty(),
                total = displayTotal,
                productOptions = productOptionsFor(query),
                failure = if (preserveFailure) {
                    failure
                } else {
                    failure.takeIf {
                        it == SalesContract.Failure.NO_ACTIVE_BUSINESS && activeBusinessId == null
                    }
                },
            )
        }
    }

    private fun SaleCartLine.toUiLine(): SalesContract.CartLine {
        val id = saleLineId.value
        val quantityInput = quantityInputs[id]
            ?: quantity.value.stripTrailingZeros().toPlainString()
        val priceInput = priceInputs[id] ?: unitPrice?.toMajor()?.toPlainString().orEmpty()
        val parsedQuantity = parseQuantity(quantityInput)
        val parsedPrice = parsePrice(priceInput, domainCart?.currency ?: configuredCurrency)
        val localTotal = if (parsedQuantity != null && parsedPrice != null) {
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
        val available = inventoryById[productId]?.positions
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
        )
    }

    private fun SalesContract.State.withFilteredOptions(): SalesContract.State =
        copy(productOptions = productOptionsFor(query))

    private fun productOptionsFor(query: String): List<SalesContract.ProductOption> {
        if (query.isBlank()) return emptyList()
        val businessId = activeBusinessId ?: return emptyList()
        val snapshot = nameSearchSnapshot?.takeIf {
            it.businessId == businessId && it.query == query
        } ?: return emptyList()
        return snapshot.candidates.flatMap { candidate ->
            val matchKind = when (candidate.reason) {
                ProductMatchReason.EXACT_NAME -> SalesContract.NameMatchKind.EXACT
                ProductMatchReason.SIMILAR_NAME -> SalesContract.NameMatchKind.SIMILAR
                else -> return@flatMap emptyList()
            }
            availableProductOptionsByProduct[candidate.product.productId].orEmpty().map { option ->
                option.copy(nameMatchKind = matchKind)
            }
        }
    }

    private fun prepareCatalogInventorySnapshot(
        rows: CatalogInventoryRows,
    ): CatalogInventorySnapshot {
        val productsById = rows.products.associateBy(Product::productId)
        val inventoryById = rows.inventory.associateBy(InventoryReadItem::productId)
        val optionsByProduct = buildAvailableProductOptionsByProduct(productsById, inventoryById)
        return CatalogInventorySnapshot(
            businessId = rows.context.businessId,
            currency = rows.context.currency,
            productsById = productsById,
            inventoryById = inventoryById,
            availableProductOptionsByProduct = optionsByProduct,
        )
    }

    private fun buildAvailableProductOptionsByProduct(
        productsById: Map<ProductId, Product>,
        inventoryById: Map<ProductId, InventoryReadItem>,
    ): Map<ProductId, List<SalesContract.ProductOption>> {
        val byProduct = inventoryById.values
            .asSequence()
            .mapNotNull { item ->
                val product = productsById[item.productId]
                    ?.takeIf { it.status == CatalogStatus.ACTIVE }
                    ?: return@mapNotNull null
                product to item
            }
            .flatMap { (product, item) ->
                item.positions.asSequence()
                    .filter { position ->
                        position.quantityOnHand.signum() > 0 &&
                            InventoryDataAlert.ARCHIVED_LOCATION !in position.alerts
                    }
                    .map { position ->
                        SalesContract.ProductOption(
                            productId = product.productId,
                            productName = product.name,
                            locationId = position.locationId,
                            locationName = position.locationName,
                            unitCode = item.unitSymbol?.takeIf(String::isNotBlank) ?: item.unitCode,
                            availableQuantity = position.quantityOnHand,
                            sku = product.sku,
                            barcode = product.barcode,
                            suggestedSalePrice = product.salePrice,
                        )
                    }
            }
            .toList()
            .groupBy(SalesContract.ProductOption::productId)
        return byProduct.mapValues { (_, options) ->
            options.sortedWith(
                compareBy<SalesContract.ProductOption> {
                    it.locationName.lowercase(Locale.ROOT)
                }.thenBy { it.locationId.value },
            )
        }
    }

    private fun replaceProjectedProduct(product: Product) {
        val replacements = availableProductOptionsByProduct[product.productId]
            .orEmpty()
            .map { option ->
                option.copy(
                    productName = product.name,
                    sku = product.sku,
                    barcode = product.barcode,
                    suggestedSalePrice = product.salePrice,
                )
            }
        if (replacements.isEmpty()) return
        availableProductOptionsByProduct =
            availableProductOptionsByProduct + (product.productId to replacements)
    }

    private fun parseQuantity(input: String): Quantity? = try {
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
        const val MAX_QUERY_LENGTH = ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH
        const val MAX_DECIMAL_LENGTH = 64
        const val NAME_SEARCH_DEBOUNCE_MILLIS = 250L
        const val EDIT_DEBOUNCE_MILLIS = 350L
    }
}
