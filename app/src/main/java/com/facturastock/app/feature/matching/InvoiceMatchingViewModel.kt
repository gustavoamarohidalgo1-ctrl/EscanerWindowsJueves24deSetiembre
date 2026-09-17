package com.facturastock.app.feature.matching

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceMatchUnitChoice
import com.facturastock.app.domain.model.MatchStatus
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.usecase.ConfirmInvoiceMatchingResult
import com.facturastock.app.domain.usecase.ConfirmInvoiceMatchingUseCase
import com.facturastock.app.domain.usecase.MatchScannedInvoiceLinesUseCase
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.feature.catalogs.productDecimalOrNull
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Action
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Effect
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Failure
import com.facturastock.app.feature.matching.InvoiceMatchingContract.State
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class InvoiceMatchingViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val appConfigurationRepository: AppConfigurationRepository,
        private val productRepository: ProductRepository,
        private val unitRepository: UnitRepository,
        private val locationRepository: InventoryLocationRepository,
        private val matchScannedInvoiceLinesUseCase: MatchScannedInvoiceLinesUseCase,
        private val productMatchingUseCase: ProductMatchingUseCase,
        private val confirmInvoiceMatchingUseCase: ConfirmInvoiceMatchingUseCase,
        private val uuidGenerator: UuidGenerator,
        private val appClock: AppClock,
        dispatcherProvider: DispatcherProvider,
    ) : UdfViewModel<State, Action, Effect>(
            initialState =
                State(
                    draftId = savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID)?.let(DraftId::parse),
                ),
            dispatcherProvider = dispatcherProvider,
        ) {
        private var activeBusinessId: BusinessId? = null
        private var activeCurrency: CurrencyCode = CurrencyCode.of("PEN")
        private var searchJob: Job? = null
        private var searchGeneration = 0L
        private var loadJob: Job? = null
        private var baselineItems: List<ScannedItemMatch> = emptyList()

        init {
            loadLines()
        }

        override fun onAction(action: Action) {
            if (uiState.value.isSaving) return
            when (action) {
                is Action.OpenLineEditor -> openLineEditor(action.lineIndex)
                is Action.EditQuantityChanged -> persistReview { copy(editQuantity = action.value.take(40), editError = null) }
                is Action.EditCostChanged -> persistReview { copy(editCost = action.value.take(40), editError = null) }
                is Action.EditCurrencyChanged -> persistReview { copy(editCurrency = action.value.take(3).uppercase(), editError = null) }
                is Action.EditUnitChoiceChanged -> persistReview { copy(editUnitChoice = action.value, editError = null) }
                Action.SubmitLineEdit -> submitLineEdit()
                Action.CloseLineEditor -> persistReview { copy(editingItemIndex = null, editError = null) }
                Action.Start -> loadLines()
                is Action.OpenLinkingDialog -> openLinkingDialog(action.lineIndex)
                Action.CloseLinkingDialog -> closeLinkingDialog()
                Action.RetrySearch -> enqueueSearch(uiState.value.searchQuery)
                is Action.SearchQueryChanged -> onSearchQueryChanged(action.query)
                is Action.ProductSelected -> linkProduct(action.lineIndex, action.product)
                is Action.QuickSuggestionSelected -> linkProduct(action.lineIndex, action.product)
                is Action.UnlinkItem -> unlinkItem(action.lineIndex)
                is Action.OpenCreateDialog -> openCreateDialog(action.lineIndex)
                Action.OpenAddNewProductDialog -> openAddNewProductDialog()
                Action.CloseCreateDialog -> persistReview { copy(creatingItemIndex = null, createError = null) }
                is Action.CreateNameChanged -> persistReview { copy(createName = action.value.take(201), createError = null) }
                is Action.CreateBarcodeChanged -> persistReview { copy(createBarcode = action.value.take(129), createError = null) }
                is Action.CreatePriceChanged -> persistReview { copy(createPrice = action.value.take(40), createError = null) }
                is Action.CreateQuantityChanged -> persistReview { copy(createQuantity = action.value.take(40), createError = null) }
                Action.SubmitCreateProduct -> submitCreateProduct()
                Action.SaveToWarehouse -> saveToWarehouse()
                Action.BackSelected -> executeMain { emitEffect(Effect.Back) }
            }
        }

        private fun loadLines() {
            if (loadJob?.isActive == true) return
            val draftId = uiState.value.draftId
            if (draftId == null) {
                executeMain {
                    updateState {
                        copy(isLoading = false, failure = Failure.INVALID_DRAFT_ID, saveFailure = null)
                    }
                }
                return
            }
            val saved = savedStateHandle.get<ByteArray>(MatchingSavedDraft.KEY)
            loadJob =
                executeIo(
                    before = { updateState { copy(isLoading = true, failure = null) } },
                    operation = {
                        val config = appConfigurationRepository.current()
                        val business = config.activeBusinessId
                        val appliedCount = confirmInvoiceMatchingUseCase.appliedCount(draftId)
                        if (appliedCount != null) {
                            return@executeIo LoadedReview(
                                business,
                                config.currency,
                                emptyList(),
                                emptyList(),
                                null,
                                null,
                                appliedCount,
                            )
                        }
                        val originals = if (business == null) emptyList() else matchScannedInvoiceLinesUseCase(draftId)
                        val snapshot = saved?.let(MatchingSavedDraft::decode)
                        val canRestore =
                            business != null && snapshot != null && snapshot.businessId == business &&
                                snapshot.sourceFingerprint == MatchingSavedDraft.fingerprint(originals)
                        val restored = if (canRestore) restoreDecisions(originals, requireNotNull(snapshot), requireNotNull(business)) else originals
                        LoadedReview(
                            business,
                            config.currency,
                            originals,
                            restored,
                            snapshot.takeIf { canRestore },
                            if (saved != null && !canRestore) "La factura cambió. Revisa de nuevo las líneas antes de guardar." else null,
                        )
                    },
                    onSuccess = { loaded ->
                        loadJob = null
                        activeBusinessId = loaded.businessId
                        activeCurrency = loaded.currency
                        baselineItems = loaded.originals
                        if (loaded.appliedCount != null) {
                            savedStateHandle.remove<ByteArray>(MatchingSavedDraft.KEY)
                            emitEffect(Effect.MatchingConfirmed(loaded.appliedCount))
                            return@executeIo
                        }
                        val restored = loaded.snapshot
                        updateState {
                            copy(
                                isLoading = false,
                                items = loaded.items,
                                businessCurrency = loaded.currency,
                                failure = if (loaded.businessId == null) Failure.NO_ACTIVE_BUSINESS else null,
                                recoveryMessage = loaded.message,
                                creatingItemIndex = restored?.creatingIndex,
                                createName = restored?.name.orEmpty(),
                                createBarcode = restored?.barcode.orEmpty(),
                                createPrice = restored?.price.orEmpty(),
                                createQuantity = restored?.quantity.orEmpty(),
                                editingItemIndex = restored?.editingIndex?.takeIf { index -> loaded.items.any { it.lineIndex == index } },
                                editQuantity = restored?.editQuantity.orEmpty(),
                                editCost = restored?.editCost.orEmpty(),
                                editCurrency = restored?.editCurrency.orEmpty(),
                                editUnitChoice = restored?.editUnitChoice,
                            )
                        }
                        if (loaded.message != null) savedStateHandle.remove<ByteArray>(MatchingSavedDraft.KEY)
                    },
                    onFailure = {
                        loadJob = null
                        updateState { copy(isLoading = false, failure = Failure.LOAD_FAILED) }
                    },
                )
        }

        private fun openLinkingDialog(lineIndex: Int) {
            if (uiState.value.items.none { it.lineIndex == lineIndex }) return
            updateState {
                copy(
                    linkingItemIndex = lineIndex,
                    searchQuery = "",
                    searchResults = emptyList(),
                )
            }
            enqueueSearch("")
        }

        private fun onSearchQueryChanged(query: String) {
            if (uiState.value.linkingItemIndex == null) return
            if (query == uiState.value.searchQuery) return
            updateState { copy(searchQuery = query) }
            enqueueSearch(query)
        }

        private fun enqueueSearch(query: String) {
            if (uiState.value.linkingItemIndex == null) return
            searchJob?.cancel()
            searchGeneration += 1L
            val generation = searchGeneration
            updateState { copy(searchResults = emptyList(), isSearching = true, searchFailed = false) }
            searchJob =
                executeMain {
                    delay(SEARCH_DEBOUNCE_MILLIS)
                    runProductSearch(query, generation)
                }
        }

        private fun closeLinkingDialog() {
            searchJob?.cancel()
            searchGeneration += 1L
            updateState {
                copy(
                    linkingItemIndex = null,
                    searchQuery = "",
                    searchResults = emptyList(),
                    isSearching = false,
                    searchFailed = false,
                )
            }
        }

        private suspend fun runProductSearch(
            query: String,
            generation: Long,
        ) {
            val businessId = activeBusinessId ?: return
            try {
                val results =
                    withContext(dispatcherProvider.io) {
                        if (query.isBlank()) {
                            productRepository.searchActiveByName(businessId, "", limit = 20)
                        } else {
                            productMatchingUseCase.searchByName(businessId, query).map { it.product }
                        }
                    }
                if (generation == searchGeneration && uiState.value.linkingItemIndex != null) {
                    updateState { copy(searchResults = results, isSearching = false, searchFailed = false) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == searchGeneration && uiState.value.linkingItemIndex != null) {
                    updateState { copy(searchResults = emptyList(), isSearching = false, searchFailed = true) }
                }
            }
        }

        private fun linkProduct(
            lineIndex: Int,
            product: Product,
        ) {
            closeLinkingDialog()
            updateState { copy(isSaving = true) }
            executeIo(
                operation = {
                    val current =
                        productRepository
                            .findById(product.productId)
                            ?.takeIf { it.businessId == activeBusinessId && it.status == CatalogStatus.ACTIVE }
                            ?: error("Producto no disponible")
                    Triple(
                        current,
                        unitRepository.findById(current.unitId)?.code,
                        current.purchaseUnitId?.let { unitRepository.findById(it)?.code },
                    )
                },
                onSuccess = { (current, inventoryCode, purchaseCode) ->
                    persistReview {
                        val updated =
                            items.map { item ->
                                if (item.lineIndex == lineIndex) {
                                    item.copy(
                                        matchedProduct = current,
                                        status = MatchStatus.MANUAL_LINKED,
                                        matchReasonLabel = "Vinculado a mano",
                                        inventoryUnitCode = inventoryCode,
                                        purchaseUnitCode = purchaseCode,
                                        unitChoice = null,
                                    )
                                } else {
                                    item
                                }
                            }
                        copy(
                            items = updated,
                            linkingItemIndex = null,
                            searchQuery = "",
                            searchResults = emptyList(),
                            isSaving = false,
                        )
                    }
                },
                onFailure = { updateState { copy(isSaving = false, saveFailure = Failure.REVIEW_REQUIRED) } },
            )
        }

        private fun unlinkItem(lineIndex: Int) {
            persistReview {
                val updated =
                    items.map { item ->
                        if (item.lineIndex == lineIndex) {
                            item.copy(
                                matchedProduct = null,
                                status = MatchStatus.UNMATCHED,
                                matchReasonLabel = null,
                            )
                        } else {
                            item
                        }
                    }
                copy(items = updated)
            }
        }

        private fun openAddNewProductDialog() {
            val nextIndex = (uiState.value.items.maxOfOrNull { it.lineIndex } ?: -1) + 1
            persistReview {
                copy(
                    creatingItemIndex = nextIndex,
                    createName = "",
                    createBarcode = "",
                    createPrice = "",
                    createQuantity = "",
                    createError = null,
                )
            }
        }

        private fun openCreateDialog(lineIndex: Int) {
            val item = uiState.value.items.firstOrNull { it.lineIndex == lineIndex } ?: return
            persistReview {
                copy(
                    creatingItemIndex = lineIndex,
                    createName = item.rawDescription,
                    createBarcode = "",
                    createPrice = "",
                    createQuantity =
                        item.quantity
                            ?.stripTrailingZeros()
                            ?.toPlainString()
                            .orEmpty(),
                    createError = null,
                )
            }
        }

        private fun submitCreateProduct() {
            val snapshot = uiState.value
            val lineIndex = snapshot.creatingItemIndex ?: return
            val name = snapshot.createName.trim()
            if (name.length !in 1..200) {
                updateState { copy(createError = "El nombre del producto debe tener entre 1 y 200 caracteres") }
                return
            }
            val salePriceMoney =
                if (snapshot.createPrice.isBlank()) {
                    null
                } else {
                    val decimal = snapshot.createPrice.productDecimalOrNull()
                    val price = decimal?.let { runCatching { Money.fromMajor(it, activeCurrency) }.getOrNull() }
                    if (price == null || !ProductSalePricePolicy.supports(price)) {
                        updateState { copy(createError = "Escribe un precio válido mayor que cero (por ejemplo, 12,50)") }
                        return
                    }
                    price
                }
            val quantity =
                if (snapshot.createQuantity.isBlank()) {
                    null
                } else {
                    val parsed = snapshot.createQuantity.productDecimalOrNull()
                    if (parsed == null || parsed.signum() <= 0) {
                        updateState { copy(createError = "Escribe una cantidad válida mayor que cero (por ejemplo, 1,5)") }
                        return
                    }
                    parsed
                }
            val businessId = activeBusinessId ?: return
            val now = appClock.now()

            updateState { copy(isSaving = true, createError = null) }
            executeIo<CreatedProductResult>(
                operation = {
                    val unit =
                        unitRepository
                            .search(businessId, CatalogSearch(status = CatalogStatus.ACTIVE, limit = 1))
                            .items
                            .firstOrNull { it.status == CatalogStatus.ACTIVE }
                            ?: throw IllegalStateException("No hay unidades disponibles")
                    val locationId =
                        locationRepository
                            .search(businessId, CatalogSearch(status = CatalogStatus.ACTIVE, limit = 1))
                            .items
                            .firstOrNull { it.status == CatalogStatus.ACTIVE }
                            ?.locationId

                    val barcodeRaw = snapshot.createBarcode.trim()
                    val barcodeVal =
                        if (barcodeRaw.isEmpty()) {
                            null
                        } else {
                            BarcodeValue.parse(barcodeRaw)?.value
                                ?: throw IllegalArgumentException("Este código de barras no es válido.")
                        }

                    if (barcodeVal != null) {
                        val existing = productRepository.findByBarcode(businessId, barcodeVal)
                        if (existing != null || snapshot.items.any { it.matchedProduct?.barcode == barcodeVal && it.lineIndex != lineIndex }) {
                            throw IllegalArgumentException("Este código de barras ya pertenece a otro producto.")
                        }
                    }

                    val printedSku = CatalogCanonicalizer.sku(snapshot.items.firstOrNull { it.lineIndex == lineIndex }?.printedCode)
                    val sku =
                        printedSku?.takeUnless { candidate ->
                            productRepository.findBySku(businessId, candidate) != null ||
                                snapshot.items.any { other ->
                                    other.lineIndex != lineIndex &&
                                        other.matchedProduct?.sku?.equals(candidate, ignoreCase = true) == true
                                }
                        }

                    val staged =
                        Product(
                            productId = ProductId.from(uuidGenerator.newUuid()),
                            businessId = businessId,
                            unitId = unit.unitId,
                            name = name,
                            locationId = locationId,
                            sku = sku,
                            barcode = barcodeVal,
                            salePrice = salePriceMoney,
                            status = CatalogStatus.ACTIVE,
                            createdAt = now,
                            updatedAt = now,
                        )
                    CreatedProductResult(staged, quantity, unit.code)
                },
                onSuccess = { result ->
                    persistReview {
                        val currentItems = items
                        val existingItem = currentItems.firstOrNull { it.lineIndex == lineIndex }
                        val updatedItems =
                            if (existingItem != null) {
                                currentItems.map { item ->
                                    if (item.lineIndex == lineIndex) {
                                        item.copy(
                                            matchedProduct = result.product,
                                            quantity = result.quantity ?: item.quantity,
                                            status = MatchStatus.CREATED_NEW,
                                            matchReasonLabel = "Creado nuevo",
                                            inventoryUnitCode = result.unitCode,
                                            purchaseUnitCode = null,
                                            unitChoice = null,
                                        )
                                    } else {
                                        item
                                    }
                                }
                            } else {
                                currentItems +
                                    ScannedItemMatch(
                                        lineIndex = lineIndex,
                                        manualLineId = LineId.from(uuidGenerator.newUuid()),
                                        rawDescription = result.product.name,
                                        quantity = result.quantity,
                                        barcode = result.product.barcode,
                                        matchedProduct = result.product,
                                        status = MatchStatus.CREATED_NEW,
                                        matchReasonLabel = "Creado nuevo",
                                        inventoryUnitCode = result.unitCode,
                                        sourceCurrency = activeCurrency,
                                        unitChoice = InvoiceMatchUnitChoice.INVENTORY,
                                    )
                            }
                        copy(
                            items = updatedItems,
                            creatingItemIndex = null,
                            createError = null,
                            isSaving = false,
                        )
                    }
                },
                onFailure = { error ->
                    updateState {
                        copy(
                            isSaving = false,
                            createError = error.message ?: "No se pudo guardar el producto",
                        )
                    }
                },
            )
        }

        private fun saveToWarehouse() {
            val snapshot = uiState.value
            if (snapshot.isSaving || !snapshot.canSaveToWarehouse) return
            val draftId = snapshot.draftId ?: return
            updateState { copy(isSaving = true, saveFailure = null) }
            executeIo(
                operation = {
                    confirmInvoiceMatchingUseCase(draftId, snapshot.items)
                },
                onSuccess = { result ->
                    updateState { copy(isSaving = false) }
                    when (result) {
                        is ConfirmInvoiceMatchingResult.Applied -> {
                            savedStateHandle.remove<ByteArray>(MatchingSavedDraft.KEY)
                            emitEffect(Effect.MatchingConfirmed(result.updatedCount))
                        }

                        is ConfirmInvoiceMatchingResult.AlreadyApplied -> {
                            savedStateHandle.remove<ByteArray>(MatchingSavedDraft.KEY)
                            emitEffect(Effect.MatchingConfirmed(result.updatedCount))
                        }

                        is ConfirmInvoiceMatchingResult.ReviewRequired -> {
                            updateState { copy(saveFailure = Failure.REVIEW_REQUIRED) }
                            result.issues.keys
                                .firstOrNull()
                                ?.let(::openLineEditor)
                        }

                        ConfirmInvoiceMatchingResult.DraftChanged -> {
                            updateState { copy(saveFailure = Failure.DRAFT_CHANGED) }
                        }

                        ConfirmInvoiceMatchingResult.LegacyConflict -> {
                            updateState { copy(saveFailure = Failure.LEGACY_CONFLICT) }
                        }

                        ConfirmInvoiceMatchingResult.NoActiveBusiness -> {
                            updateState { copy(failure = Failure.NO_ACTIVE_BUSINESS) }
                        }

                        ConfirmInvoiceMatchingResult.CloudBound -> {
                            updateState { copy(saveFailure = Failure.CLOUD_BOUND) }
                        }

                        ConfirmInvoiceMatchingResult.UnresolvedLines -> {
                            updateState { copy(saveFailure = Failure.UNRESOLVED_LINES) }
                        }

                        ConfirmInvoiceMatchingResult.NothingToApply -> {
                            updateState { copy(saveFailure = Failure.NOTHING_TO_APPLY) }
                        }

                        ConfirmInvoiceMatchingResult.MissingLocation -> {
                            updateState { copy(saveFailure = Failure.MISSING_LOCATION) }
                        }
                    }
                },
                onFailure = {
                    updateState { copy(isSaving = false, saveFailure = Failure.SAVE_FAILED) }
                },
            )
        }

        private fun openLineEditor(lineIndex: Int) {
            val item = uiState.value.items.firstOrNull { it.lineIndex == lineIndex } ?: return
            persistReview {
                copy(
                    editingItemIndex = lineIndex,
                    editQuantity =
                        item.quantity
                            ?.stripTrailingZeros()
                            ?.toPlainString()
                            .orEmpty(),
                    editCost =
                        item.unitCost
                            ?.stripTrailingZeros()
                            ?.toPlainString()
                            .orEmpty(),
                    editCurrency = item.sourceCurrency?.value.orEmpty(),
                    editUnitChoice =
                        item.unitChoice ?: when {
                            !item.sourceUnitCode.isNullOrBlank() && item.sourceUnitCode.equals(item.inventoryUnitCode, true) -> InvoiceMatchUnitChoice.INVENTORY
                            !item.sourceUnitCode.isNullOrBlank() && item.sourceUnitCode.equals(item.purchaseUnitCode, true) -> InvoiceMatchUnitChoice.PURCHASE
                            else -> null
                        },
                    editError = null,
                )
            }
        }

        private fun submitLineEdit() {
            val state = uiState.value
            val index = state.editingItemIndex ?: return
            val quantity = state.editQuantity.productDecimalOrNull()
            val cost = state.editCost.productDecimalOrNull()
            val currency = runCatching { CurrencyCode.of(state.editCurrency.trim().uppercase()) }.getOrNull()
            val product = state.items.firstOrNull { it.lineIndex == index }?.matchedProduct
            val error =
                when {
                    quantity == null || quantity.signum() <= 0 -> "Escribe una cantidad mayor que cero."
                    cost == null || cost.signum() < 0 -> "Escribe el costo unitario; usa 0 solo si el producto fue gratuito."
                    currency != activeCurrency -> "El costo debe expresarse en ${activeCurrency.value}. Si la factura usa otra moneda, convierte el costo antes de declararlo."
                    state.editUnitChoice == null -> "Selecciona la unidad de la cantidad y del costo."
                    state.editUnitChoice == InvoiceMatchUnitChoice.PURCHASE && product?.purchaseUnitId == null -> "Este producto no tiene una unidad de compra configurada."
                    else -> null
                }
            if (error != null) {
                updateState { copy(editError = error) }
                return
            }
            persistReview {
                copy(
                    items =
                        items.map { item ->
                            if (item.lineIndex != index) {
                                item
                            } else {
                                item.copy(
                                    quantity = quantity,
                                    unitCost = cost,
                                    sourceCurrency = currency,
                                    unitChoice = state.editUnitChoice,
                                )
                            }
                        },
                    editingItemIndex = null,
                    editError = null,
                    saveFailure = null,
                )
            }
        }

        /** Persist before publishing UI state, so every accepted decision survives recreation. */
        private fun persistReview(transform: State.() -> State) {
            val business = activeBusinessId ?: return
            val next = uiState.value.transform()
            val bytes = runCatching { MatchingSavedDraft.encode(next, baselineItems, business) }.getOrNull()
            if (bytes == null) {
                updateState { copy(isSaving = false, recoveryMessage = "La revisión alcanzó su límite de datos. La última edición no se aplicó; guarda esta factura antes de añadir más líneas.") }
                return
            }
            savedStateHandle[MatchingSavedDraft.KEY] = bytes
            updateState { next }
        }

        private suspend fun restoreDecisions(
            originals: List<ScannedItemMatch>,
            snapshot: MatchingSavedDraft.Snapshot,
            businessId: BusinessId,
        ): List<ScannedItemMatch> {
            val restored = originals.toMutableList()
            for (decision in snapshot.decisions) {
                val position = restored.indexOfFirst { MatchingSavedDraft.sourceKey(it) == decision.sourceKey }
                val original =
                    restored.getOrNull(position)
                        ?: ScannedItemMatch(decision.lineIndex, decision.description)
                val persisted = decision.productId?.let { productRepository.findById(it) }
                val candidate = persisted ?: decision.staged
                val product =
                    candidate?.takeIf {
                        it.businessId == businessId && it.status == CatalogStatus.ACTIVE &&
                            (persisted == null || it.version == decision.productVersion) &&
                            unitRepository.findById(it.unitId)?.let { unit -> unit.businessId == businessId && unit.status == CatalogStatus.ACTIVE } == true
                    }
                val item =
                    original.copy(
                        manualLineId = decision.manualLineId,
                        matchedProduct = product,
                        quantity = decision.quantity,
                        unitCost = decision.cost,
                        sourceCurrency = decision.currency,
                        unitChoice = decision.unitChoice,
                        inventoryUnitCode = product?.let { unitRepository.findById(it.unitId)?.code },
                        purchaseUnitCode = product?.purchaseUnitId?.let { unitRepository.findById(it)?.code },
                        status =
                            if (product == null) {
                                MatchStatus.UNMATCHED
                            } else if (persisted != null && decision.status == MatchStatus.CREATED_NEW) {
                                MatchStatus.MANUAL_LINKED
                            } else {
                                decision.status
                            },
                        matchReasonLabel = if (product != null) "Revisión recuperada" else null,
                    )
                if (position >= 0) restored[position] = item else restored += item
            }
            return restored
        }

        private companion object {
            const val SEARCH_DEBOUNCE_MILLIS = 250L
        }
    }

private data class CreatedProductResult(
    val product: Product,
    val quantity: BigDecimal?,
    val unitCode: String,
)

private data class LoadedReview(
    val businessId: BusinessId?,
    val currency: CurrencyCode,
    val originals: List<ScannedItemMatch>,
    val items: List<ScannedItemMatch>,
    val snapshot: MatchingSavedDraft.Snapshot?,
    val message: String?,
    val appliedCount: Int? = null,
)
