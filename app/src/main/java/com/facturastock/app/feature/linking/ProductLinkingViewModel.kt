package com.facturastock.app.feature.linking

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.WorkflowRequest
import com.facturastock.app.domain.model.WorkflowStage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.usecase.CreateLinkedProductResult
import com.facturastock.app.domain.usecase.CreateLinkedProductUseCase
import com.facturastock.app.domain.usecase.LoadInvoiceLinesReviewUseCase
import com.facturastock.app.domain.usecase.InvoiceLinesReviewSnapshot
import com.facturastock.app.domain.usecase.NewLinkedProduct
import com.facturastock.app.domain.usecase.ProductMatchCandidate
import com.facturastock.app.domain.usecase.ProductMatchOutcome
import com.facturastock.app.domain.usecase.ProductMatchQuery
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.domain.usecase.RunDraftStageUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceLinesEditUseCase
import com.facturastock.app.domain.usecase.SaveSupplierAliasUseCase
import com.facturastock.app.domain.usecase.UpdateProductSalePriceUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import com.facturastock.app.feature.linking.ProductLinkingContract.Action
import com.facturastock.app.feature.linking.ProductLinkingContract.CreateForm
import com.facturastock.app.feature.linking.ProductLinkingContract.Effect
import com.facturastock.app.feature.linking.ProductLinkingContract.Failure
import com.facturastock.app.feature.linking.ProductLinkingContract.LineLinking
import com.facturastock.app.feature.linking.ProductLinkingContract.LinkStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.SearchStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.State
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Orquesta la vinculación de cada línea con el catálogo. La cascada de coincidencia exacta se
 * ejecuta al cargar y auto-vincula solo coincidencias únicas; las ambiguas y difusas siempre
 * esperan confirmación humana. Todo guardado pasa por el CAS de revisiones del editor: un
 * conflicto recarga el snapshot y se marca recuperable, nunca se convierte en pérdida de datos.
 *
 * El alias proveedor→producto se persiste únicamente tras una confirmación humana
 * ([Action.CandidateConfirmed]) o dentro de la creación confirmada ([Action.CreateSubmitted]);
 * ningún alias nace del OCR ni del auto-enlace.
 */
class ProductLinkingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadInvoiceLinesReviewUseCase: LoadInvoiceLinesReviewUseCase,
    private val saveInvoiceLinesEditUseCase: SaveInvoiceLinesEditUseCase,
    private val productMatchingUseCase: ProductMatchingUseCase,
    private val createLinkedProductUseCase: CreateLinkedProductUseCase,
    private val saveSupplierAliasUseCase: SaveSupplierAliasUseCase,
    private val runDraftStageUseCase: RunDraftStageUseCase,
    private val supplierRepository: SupplierRepository,
    private val unitRepository: UnitRepository,
    private val productRepository: ProductRepository,
    private val appConfigurationRepository: AppConfigurationRepository,
    private val updateProductSalePriceUseCase: UpdateProductSalePriceUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<State, Action, Effect>(
    initialState = linkingInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    /** Resultado de aplicar un enlace sobre el snapshot durable. */
    private sealed interface LinkApplication {
        data class Applied(val edit: InvoiceLinesEdit) : LinkApplication

        data object Conflict : LinkApplication

        data object NotEditable : LinkApplication

        data object Unavailable : LinkApplication
    }

    private data class LineResolution(
        val line: LineLinking,
        val candidates: List<ProductMatchCandidate> = emptyList(),
        val priceRequiredProduct: Product? = null,
    )

    private sealed interface LineMatchStep {
        data class Resolved(val resolution: LineResolution) : LineMatchStep

        data class AutoLink(
            val line: InvoiceLineEdit,
            val candidate: ProductMatchCandidate,
        ) : LineMatchStep
    }

    private data class CatalogLinkSpec(
        val lineId: LineId,
        val productId: ProductId,
        val unitId: UnitId,
        val confidencePermille: Int,
        val provenance: PurchaseProductProvenance,
        val stagedProduct: StagedPurchaseProduct?,
    )

    private sealed interface DraftResolution {
        data object NotEditable : DraftResolution

        data class Ready(
            val businessId: BusinessId,
            val supplierId: SupplierId?,
            val edit: InvoiceLinesEdit,
            val persistedRevision: Long,
            val units: List<UnitOfMeasure>,
            val currency: CurrencyCode,
            val resolutions: List<LineResolution>,
            val saveConflict: Boolean,
        ) : DraftResolution
    }

    private sealed interface CreateOutcome {
        data class Link(val productName: String, val application: LinkApplication) : CreateOutcome

        data class DuplicateFound(val existing: Product) : CreateOutcome

        data class Rejected(
            val failure: ProductLinkingContract.SalePriceFailure,
        ) : CreateOutcome
    }

    private sealed interface SalePriceOutcome {
        data class Link(
            val product: Product,
            val application: LinkApplication,
        ) : SalePriceOutcome

        data class Rejected(
            val failure: ProductLinkingContract.SalePriceFailure,
        ) : SalePriceOutcome
    }

    private var currentEdit: InvoiceLinesEdit? = null
    private var knownRevision: Long = 0L
    private var businessId: BusinessId? = null
    private var conflictPending = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var searchGeneration: Long = 0L
    private var candidateSelectionBlockedBySearch: Boolean = false
    private var candidatesByLine: Map<LineId, List<ProductMatchCandidate>> = emptyMap()
    private var priceRequiredByLine: Map<LineId, Product> = emptyMap()

    override fun onAction(action: Action) {
        when (action) {
            Action.Start -> start()
            is Action.LineSelected -> selectLine(action.lineId)
            is Action.SearchChanged -> search(action.query)
            Action.RetrySearch -> retrySearch()
            is Action.CandidateConfirmed -> confirmCandidate(action.productId)
            is Action.ChangeLink -> changeLink(action.lineId)
            Action.SkipLine -> skipLine()
            Action.OpenCreateForm -> openCreateForm()
            is Action.CreateFormChanged -> updateCreateForm(action.form)
            Action.CreateSubmitted -> submitCreate()
            Action.CreateDuplicateLinkExisting -> linkDuplicateExisting()
            Action.CreateFormDismissed -> dismissCreateForm()
            is Action.SalePriceChanged -> updateSalePrice(action.value)
            Action.SalePriceSubmitted -> submitSalePrice()
            Action.SalePriceDismissed -> dismissSalePrice()
            Action.ContinueSelected -> continueToSummary()
            Action.BackSelected -> executeMain { emitEffect(Effect.Back) }
            Action.Retry -> retry()
        }
    }

    private fun start() {
        if (uiState.value.failure == Failure.INVALID_ROUTE) {
            executeMain { emitEffect(Effect.CloseInvalidRoute) }
            return
        }
        // El estado sobrevive a la recreación de la vista; Start no recarga un snapshot sano.
        if (currentEdit != null || loadJob?.isActive == true) return
        load()
    }

    private fun retry() {
        if (uiState.value.failure == Failure.INVALID_ROUTE) {
            executeMain { emitEffect(Effect.CloseInvalidRoute) }
            return
        }
        conflictPending = false
        load()
    }

    private fun load() {
        val draftId = uiState.value.draftId ?: return
        cancelSearch()
        loadJob?.cancel()
        loadJob = executeMain {
            updateState {
                copy(
                    isLoading = currentEdit == null,
                    failure = null,
                    searchStatus = SearchStatus.IDLE,
                )
            }
            try {
                loadInvoiceLinesReviewUseCase.observe(draftId).collect { snapshot ->
                    if (snapshot == null) {
                        updateState {
                            copy(isLoading = false, failure = Failure.DRAFT_NOT_EDITABLE)
                        }
                        return@collect
                    }
                    val current = currentEdit
                    if (
                        current != null &&
                        snapshot.edit.revision <= knownRevision &&
                        snapshot.edit == current
                    ) {
                        updateState { copy(isLoading = false) }
                        return@collect
                    }
                    val resolution = withContext(dispatcherProvider.io) {
                        resolveDraft(draftId, snapshot)
                    }
                    when (resolution) {
                        DraftResolution.NotEditable -> updateState {
                            copy(isLoading = false, failure = Failure.DRAFT_NOT_EDITABLE)
                        }

                        is DraftResolution.Ready -> applyResolution(resolution)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(isLoading = false, failure = Failure.SAVE_FAILED) }
            }
        }
    }

    /**
     * Carga el snapshot, resuelve el proveedor y ejecuta la cascada por línea. Los auto-enlaces
     * exactos se persisten en un solo CAS; ante un conflicto se recarga y se reintenta la cascada
     * (los enlaces ya guardados se respetan como CONFIRMED).
     */
    private suspend fun resolveDraft(
        draftId: DraftId,
        observedSnapshot: InvoiceLinesReviewSnapshot? = null,
    ): DraftResolution {
        var snapshot = observedSnapshot
            ?: loadInvoiceLinesReviewUseCase(draftId)
            ?: return DraftResolution.NotEditable
        val currency = appConfigurationRepository.current().currency
        var supplierId = resolveSupplier(snapshot.draft)
        var edit = snapshot.edit
        var revision = snapshot.persistedEditRevision
        var resolutions: List<LineResolution>
        var conflicted: Boolean
        var reloads = 0
        do {
            conflicted = false
            val steps = ArrayList<LineMatchStep>(edit.activeLines.size)
            val matchCache = LinkedHashMap<ProductMatchQuery, ProductMatchOutcome>()
            for (line in edit.activeLines) {
                steps += if (line.linkedProductId != null) {
                    LineMatchStep.Resolved(
                        resolvePersistedLink(
                            line = line,
                            businessId = snapshot.draft.businessId,
                            currency = currency,
                        ),
                    )
                } else {
                    matchUnlinkedLine(
                        line = line,
                        businessId = snapshot.draft.businessId,
                        supplierId = supplierId,
                        currency = currency,
                        matchCache = matchCache,
                    )
                }
            }
            val pendingAutoLinks = steps.mapNotNull { step -> step as? LineMatchStep.AutoLink }
            var autoLinksApplied = pendingAutoLinks.isEmpty()
            if (pendingAutoLinks.isNotEmpty()) {
                when (
                    val application = applyCatalogLinks(
                        edit = edit,
                        expectedRevision = revision,
                        links = pendingAutoLinks.map { pending ->
                            CatalogLinkSpec(
                                lineId = pending.line.lineId,
                                productId = pending.candidate.product.productId,
                                unitId = pending.candidate.product.unitId,
                                confidencePermille = pending.candidate.confidencePermille,
                                provenance = PurchaseProductProvenance.EXISTING,
                                stagedProduct = null,
                            )
                        },
                    )
                ) {
                    is LinkApplication.Applied -> {
                        edit = application.edit
                        revision = application.edit.revision
                        autoLinksApplied = true
                    }
                    LinkApplication.Conflict -> conflicted = true
                    LinkApplication.NotEditable -> return DraftResolution.NotEditable
                    LinkApplication.Unavailable -> Unit
                }
            }
            resolutions = steps.map { step ->
                when (step) {
                    is LineMatchStep.Resolved -> step.resolution
                    is LineMatchStep.AutoLink -> if (autoLinksApplied) {
                        LineResolution(
                            line = step.line.toLinking(
                                status = LinkStatus.AUTO_LINKED,
                                linkedProductName = step.candidate.product.name,
                                linkReason = step.candidate.reason,
                            ),
                        )
                    } else {
                        LineResolution(line = step.line.toLinking(LinkStatus.NEEDS_CHOICE))
                    }
                }
            }
            if (conflicted) {
                reloads += 1
                snapshot = loadInvoiceLinesReviewUseCase(draftId)
                    ?: return DraftResolution.NotEditable
                supplierId = resolveSupplier(snapshot.draft)
                edit = snapshot.edit
                revision = snapshot.persistedEditRevision
            }
        } while (conflicted && reloads < MAX_CONFLICT_RELOADS)
        return DraftResolution.Ready(
            businessId = snapshot.draft.businessId,
            supplierId = supplierId,
            edit = edit,
            persistedRevision = revision,
            units = unitRepository.observeForBusiness(snapshot.draft.businessId).first()
                .filter { it.status == CatalogStatus.ACTIVE },
            currency = currency,
            resolutions = resolutions,
            saveConflict = conflicted,
        )
    }

    private fun applyResolution(ready: DraftResolution.Ready) {
        currentEdit = ready.edit
        knownRevision = ready.persistedRevision
        businessId = ready.businessId
        candidatesByLine = ready.resolutions
            .filter { resolution -> resolution.candidates.isNotEmpty() }
            .associate { resolution -> resolution.line.lineId to resolution.candidates }
        priceRequiredByLine = ready.resolutions.mapNotNull { resolution ->
            resolution.priceRequiredProduct?.let { product -> resolution.line.lineId to product }
        }.toMap()
        val ordered = ready.resolutions.map(LineResolution::line)
        val requested = uiState.value.currentLineId
        val initial = uiState.value.initialLineId
        val isPending: (LineLinking) -> Boolean = { line ->
            line.status == LinkStatus.NEEDS_CHOICE || line.status == LinkStatus.NO_MATCH
        }
        val requestedLine = requested?.let { id -> ordered.firstOrNull { it.lineId == id } }
        val initialLine = initial?.let { id -> ordered.firstOrNull { it.lineId == id } }
        val current = requestedLine?.takeIf(isPending)
            ?: initialLine?.takeIf(isPending)
            ?: ordered.firstOrNull(isPending)
            ?: requestedLine
            ?: initialLine
            ?: ordered.firstOrNull()
        val showConflict = ready.saveConflict || conflictPending
        conflictPending = false
        val requiredPrice = current?.let { line ->
            priceRequiredByLine[line.lineId]?.let { product ->
                ProductLinkingContract.SalePriceForm(
                    product = product,
                    lineId = line.lineId,
                    confidencePermille = ready.edit.lines
                        .firstOrNull { it.lineId == line.lineId }
                        ?.linkConfidence
                        ?: HUMAN_CONFIDENCE_PERMILLE,
                    reason = null,
                    currency = ready.currency,
                )
            }
        }
        candidateSelectionBlockedBySearch = false
        updateState {
            copy(
                isLoading = false,
                isBusy = false,
                failure = if (showConflict) Failure.SAVE_CONFLICT else null,
                lines = ordered,
                currentLineId = current?.lineId,
                candidates = current?.let { candidatesByLine[it.lineId].orEmpty() }.orEmpty(),
                searchQuery = current?.description.orEmpty(),
                searchStatus = SearchStatus.IDLE,
                units = ready.units,
                currency = ready.currency,
                supplierId = ready.supplierId,
                createForm = null,
                salePriceForm = requiredPrice,
                salePriceFailure = null,
            )
        }
    }

    private suspend fun resolveSupplier(draft: InvoiceDraft): SupplierId? =
        draft.supplierId ?: draft.supplierRucNormalized?.let { ruc ->
            supplierRepository.findByRuc(draft.businessId, ruc)?.supplierId
        }

    private suspend fun resolvePersistedLink(
        line: InvoiceLineEdit,
        businessId: BusinessId,
        currency: CurrencyCode,
    ): LineResolution = when (line.productProvenance) {
        PurchaseProductProvenance.CREATED_IN_DRAFT -> {
            val staged = line.stagedProduct
            if (
                staged != null && staged.businessId == businessId &&
                staged.salePrice?.currency == currency
            ) {
                LineResolution(
                    line = line.toLinking(
                        status = LinkStatus.CONFIRMED,
                        linkedProductName = staged.name,
                    ),
                )
            } else {
                // Un payload staged legacy sin precio debe recrearse o vincularse de nuevo;
                // todavía no existe un producto persistido que pueda actualizarse por CAS.
                LineResolution(
                    line = line.toLinking(
                        status = LinkStatus.NEEDS_CHOICE,
                        requiresSalePrice = true,
                    ),
                )
            }
        }

        PurchaseProductProvenance.EXISTING,
        PurchaseProductProvenance.UNKNOWN_LEGACY,
        -> {
            val product = line.linkedProductId
                ?.let { productRepository.findById(it) }
                ?.takeIf { it.businessId == businessId && it.status == CatalogStatus.ACTIVE }
            when {
                product == null -> LineResolution(
                    line = line.toLinking(
                        status = LinkStatus.NEEDS_CHOICE,
                        requiresSalePrice = true,
                    ),
                )
                product.salePrice?.currency == currency -> LineResolution(
                    line = line.toLinking(
                        status = LinkStatus.CONFIRMED,
                        linkedProductName = product.name,
                    ),
                )
                else -> LineResolution(
                    line = line.toLinking(
                        status = LinkStatus.NEEDS_CHOICE,
                        requiresSalePrice = true,
                    ),
                    priceRequiredProduct = product,
                )
            }
        }
    }

    private suspend fun matchUnlinkedLine(
        line: InvoiceLineEdit,
        businessId: BusinessId,
        supplierId: SupplierId?,
        currency: CurrencyCode,
        matchCache: MutableMap<ProductMatchQuery, ProductMatchOutcome>,
    ): LineMatchStep {
        val query = buildQuery(businessId, supplierId, line)
        val outcome = if (query == null) {
            ProductMatchOutcome.NoMatch
        } else {
            matchCache.getOrPut(query) { productMatchingUseCase(query) }
        }
        return when (outcome) {
            is ProductMatchOutcome.AutoLinked -> if (
                outcome.candidate.product.salePrice?.currency != currency
            ) {
                LineMatchStep.Resolved(
                    LineResolution(
                        line = line.toLinking(LinkStatus.NEEDS_CHOICE),
                        candidates = listOf(outcome.candidate),
                    ),
                )
            } else {
                LineMatchStep.AutoLink(line = line, candidate = outcome.candidate)
            }

            is ProductMatchOutcome.Ambiguous -> LineMatchStep.Resolved(
                LineResolution(
                    line = line.toLinking(LinkStatus.NEEDS_CHOICE),
                    candidates = outcome.candidates,
                ),
            )

            is ProductMatchOutcome.Suggestions -> LineMatchStep.Resolved(
                LineResolution(
                    line = line.toLinking(LinkStatus.NEEDS_CHOICE),
                    candidates = outcome.candidates,
                ),
            )

            ProductMatchOutcome.NoMatch -> LineMatchStep.Resolved(
                LineResolution(line = line.toLinking(LinkStatus.NO_MATCH)),
            )
        }
    }

    private fun buildQuery(
        businessId: BusinessId,
        supplierId: SupplierId?,
        line: InvoiceLineEdit,
    ): ProductMatchQuery? {
        val description = line.description.selectedValue?.takeIf { it.isNotBlank() }
        val code = line.code.selectedValue?.takeIf { it.isNotBlank() }
        if (description == null && code == null) return null
        return ProductMatchQuery(
            businessId = businessId,
            supplierId = supplierId,
            supplierCode = code,
            description = description,
        )
    }

    private fun selectLine(lineId: LineId) {
        if (uiState.value.isLoading) return
        val selected = uiState.value.lines.firstOrNull { it.lineId == lineId } ?: return
        cancelSearch()
        executeMain {
            candidateSelectionBlockedBySearch = false
            updateState {
                copy(
                    currentLineId = selected.lineId,
                    candidates = candidatesByLine[selected.lineId].orEmpty(),
                    searchQuery = selected.description,
                    searchStatus = SearchStatus.IDLE,
                )
            }
        }
    }

    private fun changeLink(lineId: LineId) {
        if (uiState.value.isLoading) return
        val selected = uiState.value.lines.firstOrNull { it.lineId == lineId } ?: return
        cancelSearch()
        executeMain {
            candidateSelectionBlockedBySearch = false
            updateState {
                val reopen = selected.status == LinkStatus.CONFIRMED ||
                    selected.status == LinkStatus.AUTO_LINKED
                copy(
                    lines = if (reopen) {
                        lines.map { line ->
                            if (line.lineId == lineId) {
                                // El enlace persistido se conserva hasta la nueva confirmación.
                                line.copy(status = LinkStatus.NEEDS_CHOICE)
                            } else {
                                line
                            }
                        }
                    } else {
                        lines
                    },
                    currentLineId = selected.lineId,
                    candidates = candidatesByLine[selected.lineId].orEmpty(),
                    searchQuery = selected.description,
                    searchStatus = SearchStatus.IDLE,
                )
            }
        }
    }

    private fun search(query: String) {
        val state = uiState.value
        if (state.isLoading) return
        val lineId = state.currentLineId ?: return
        val business = businessId ?: return
        val value = query.take(MAX_SEARCH_QUERY_LENGTH)
        val generation = ++searchGeneration
        candidateSelectionBlockedBySearch = true
        searchJob?.cancel()
        if (value.isBlank()) {
            executeMain {
                if (
                    searchGeneration == generation &&
                    uiState.value.currentLineId == lineId
                ) {
                    candidateSelectionBlockedBySearch = false
                    updateState {
                        copy(
                            searchQuery = value,
                            candidates = candidatesByLine[lineId].orEmpty(),
                            searchStatus = SearchStatus.IDLE,
                        )
                    }
                }
            }
            return
        }
        val supplierId = state.supplierId
        searchJob = executeIo(
            before = {
                if (
                    searchGeneration == generation &&
                    uiState.value.currentLineId == lineId
                ) {
                    candidateSelectionBlockedBySearch = false
                    updateState {
                        copy(
                            searchQuery = value,
                            candidates = emptyList(),
                            searchStatus = SearchStatus.SEARCHING,
                        )
                    }
                }
            },
            operation = { productMatchingUseCase.search(business, supplierId, value) },
            onSuccess = { found ->
                val current = uiState.value
                if (
                    searchGeneration == generation &&
                    current.currentLineId == lineId &&
                    current.searchQuery == value
                ) {
                    updateState {
                        copy(
                            candidates = found,
                            searchStatus = SearchStatus.IDLE,
                        )
                    }
                }
            },
            onFailure = {
                val current = uiState.value
                if (
                    searchGeneration == generation &&
                    current.currentLineId == lineId &&
                    current.searchQuery == value
                ) {
                    updateState {
                        copy(
                            candidates = emptyList(),
                            searchStatus = SearchStatus.FAILED,
                        )
                    }
                }
            },
        )
    }

    private fun retrySearch() {
        val state = uiState.value
        if (state.searchStatus != SearchStatus.FAILED) return
        search(state.searchQuery)
    }

    private fun cancelSearch() {
        searchGeneration += 1L
        searchJob?.cancel()
        searchJob = null
    }

    private fun confirmCandidate(productId: ProductId) {
        val state = uiState.value
        if (candidateSelectionBlockedBySearch || !state.canSelectCandidate) return
        val line = state.currentLine ?: return
        val candidate = state.candidates.firstOrNull { it.product.productId == productId } ?: return
        if (candidate.product.salePrice?.currency != state.currency) {
            executeMain {
                updateState {
                    copy(
                        salePriceForm = ProductLinkingContract.SalePriceForm(
                            product = candidate.product,
                            lineId = line.lineId,
                            confidencePermille = candidate.confidencePermille,
                            reason = candidate.reason,
                            currency = state.currency,
                        ),
                        salePriceFailure = null,
                    )
                }
            }
            return
        }
        linkCandidate(candidate, line)
    }

    private fun linkCandidate(candidate: ProductMatchCandidate, line: LineLinking) {
        val state = uiState.value
        if (state.isBusy || state.isLoading) return
        val supplierId = state.supplierId
        executeIo(
            before = { updateState { copy(isBusy = true, failure = null) } },
            operation = {
                val application = applyLinkToCurrent(
                    lineId = line.lineId,
                    product = candidate.product,
                    confidencePermille = candidate.confidencePermille,
                )
                if (application is LinkApplication.Applied && supplierId != null) {
                    saveConfirmedAliases(candidate.product, supplierId, line)
                }
                application
            },
            onSuccess = { application ->
                when (application) {
                    is LinkApplication.Applied -> updateState {
                        advanceAfterLink(
                            lineId = line.lineId,
                            productName = candidate.product.name,
                            reason = candidate.reason,
                        )
                    }

                    LinkApplication.Conflict -> reloadAfterConflict()

                    LinkApplication.NotEditable -> updateState {
                        copy(isBusy = false, failure = Failure.DRAFT_NOT_EDITABLE)
                    }

                    LinkApplication.Unavailable -> updateState { copy(isBusy = false) }
                }
            },
            onFailure = { updateState { copy(isBusy = false, failure = Failure.SAVE_FAILED) } },
        )
    }

    private fun updateSalePrice(value: String) {
        executeMain {
            val form = uiState.value.salePriceForm ?: return@executeMain
            updateState {
                copy(
                    salePriceForm = form.copy(
                        value = value.take(MAX_PRICE_LENGTH),
                        submitAttempted = false,
                    ),
                    salePriceFailure = null,
                )
            }
        }
    }

    private fun dismissSalePrice() {
        if (uiState.value.isBusy) return
        executeMain { updateState { copy(salePriceForm = null, salePriceFailure = null) } }
    }

    private fun submitSalePrice() {
        val state = uiState.value
        if (state.isBusy || state.isLoading) return
        val form = state.salePriceForm ?: return
        val price = form.parsedPrice
        if (price == null) {
            executeMain {
                updateState {
                    copy(
                        salePriceForm = form.copy(submitAttempted = true),
                        salePriceFailure = ProductLinkingContract.SalePriceFailure.INVALID_PRICE,
                    )
                }
            }
            return
        }
        val line = state.lines.firstOrNull { it.lineId == form.lineId } ?: return
        val supplierId = state.supplierId
        executeIo(
            before = {
                updateState { copy(isBusy = true, failure = null, salePriceFailure = null) }
            },
            operation = {
                when (
                    val result = updateProductSalePriceUseCase(
                        productId = form.productId,
                        expectedVersion = form.expectedVersion,
                        salePrice = price,
                    )
                ) {
                    is ProductSalePriceMutationResult.Updated,
                    is ProductSalePriceMutationResult.Unchanged,
                    -> {
                        val product = when (result) {
                            is ProductSalePriceMutationResult.Updated -> result.product
                            is ProductSalePriceMutationResult.Unchanged -> result.product
                        }
                        val application = applyLinkToCurrent(
                            lineId = form.lineId,
                            product = product,
                            confidencePermille = form.confidencePermille,
                        )
                        if (application is LinkApplication.Applied && supplierId != null) {
                            saveConfirmedAliases(product, supplierId, line)
                        }
                        SalePriceOutcome.Link(product, application)
                    }
                    ProductSalePriceMutationResult.InvalidPrice -> SalePriceOutcome.Rejected(
                        ProductLinkingContract.SalePriceFailure.INVALID_PRICE,
                    )
                    ProductSalePriceMutationResult.Stale -> SalePriceOutcome.Rejected(
                        ProductLinkingContract.SalePriceFailure.STALE,
                    )
                    is ProductSalePriceMutationResult.CurrencyMismatch -> SalePriceOutcome.Rejected(
                        ProductLinkingContract.SalePriceFailure.CURRENCY_MISMATCH,
                    )
                    ProductSalePriceMutationResult.NoActiveBusiness,
                    ProductSalePriceMutationResult.NotFound,
                    ProductSalePriceMutationResult.Inactive,
                    -> SalePriceOutcome.Rejected(
                        ProductLinkingContract.SalePriceFailure.PRODUCT_UNAVAILABLE,
                    )
                }
            },
            onSuccess = { outcome ->
                when (outcome) {
                    is SalePriceOutcome.Rejected -> updateState {
                        copy(isBusy = false, salePriceFailure = outcome.failure)
                    }
                    is SalePriceOutcome.Link -> when (outcome.application) {
                        is LinkApplication.Applied -> updateState {
                            advanceAfterLink(
                                lineId = form.lineId,
                                productName = outcome.product.name,
                                reason = form.reason,
                            )
                        }
                        LinkApplication.Conflict -> reloadAfterConflict()
                        LinkApplication.NotEditable -> updateState {
                            copy(
                                isBusy = false,
                                salePriceForm = null,
                                failure = Failure.DRAFT_NOT_EDITABLE,
                            )
                        }
                        LinkApplication.Unavailable -> updateState {
                            copy(isBusy = false, salePriceForm = null, failure = Failure.SAVE_FAILED)
                        }
                    }
                }
            },
            onFailure = {
                updateState {
                    copy(
                        isBusy = false,
                        salePriceFailure = ProductLinkingContract.SalePriceFailure.SAVE_FAILED,
                    )
                }
            },
        )
    }

    /** El alias solo nace de la confirmación humana; la descripción y el código si difieren. */
    private suspend fun saveConfirmedAliases(
        product: Product,
        supplierId: SupplierId,
        line: LineLinking,
    ) {
        val description = line.description.trim()
        if (description.isNotEmpty()) {
            saveSupplierAliasUseCase(product, supplierId, description)
        }
        val code = line.code?.trim().orEmpty()
        if (code.isNotEmpty() && !code.equals(description, ignoreCase = true)) {
            saveSupplierAliasUseCase(product, supplierId, code)
        }
    }

    private fun skipLine() {
        val state = uiState.value
        if (state.isBusy || state.isLoading) return
        val line = state.currentLine ?: return
        if (
            line.requiresSalePrice ||
            (line.status != LinkStatus.NEEDS_CHOICE && line.status != LinkStatus.NO_MATCH)
        ) {
            return
        }
        cancelSearch()
        executeMain {
            candidateSelectionBlockedBySearch = false
            updateState {
                val updatedLines = lines.map { item ->
                    if (item.lineId == line.lineId) {
                        item.copy(status = LinkStatus.SKIPPED)
                    } else {
                        item
                    }
                }
                val target = nextPendingLine(updatedLines, after = line.lineId)
                    ?: updatedLines.first { it.lineId == line.lineId }
                copy(
                    lines = updatedLines,
                    currentLineId = target.lineId,
                    candidates = candidatesByLine[target.lineId].orEmpty(),
                    searchQuery = target.description,
                    searchStatus = SearchStatus.IDLE,
                )
            }
        }
    }

    private fun openCreateForm() {
        val state = uiState.value
        if (state.isBusy || state.isLoading) return
        val line = state.currentLine ?: return
        executeMain {
            updateState {
                copy(
                    createForm = CreateForm(
                        name = line.description.trim(),
                        salePriceCurrency = state.currency,
                    ),
                    salePriceFailure = null,
                )
            }
        }
    }

    private fun updateCreateForm(form: CreateForm) {
        executeMain {
            updateState {
                // Editar cualquier campo descarta el duplicado y los errores del envío anterior.
                if (createForm == null) {
                    this
                } else {
                    copy(
                        createForm = form.copy(
                            // Un código excedido debe fallar la validación, nunca convertirse por
                            // truncamiento en otro identificador aparentemente válido.
                            barcode = form.barcode,
                            duplicate = null,
                            submitAttempted = false,
                        ),
                        salePriceFailure = null,
                    )
                }
            }
        }
    }

    private fun dismissCreateForm() {
        executeMain {
            updateState { copy(createForm = null, salePriceFailure = null) }
        }
    }

    private fun submitCreate() {
        val state = uiState.value
        if (state.isBusy || state.isLoading) return
        val form = state.createForm ?: return
        val line = state.currentLine ?: return
        val business = businessId ?: return
        if (!form.isValid) {
            executeMain { updateState { copy(createForm = form.copy(submitAttempted = true)) } }
            return
        }
        val unitId = form.unitId ?: return
        val salePrice = form.parsedSalePrice ?: return
        executeIo(
            before = { updateState { copy(isBusy = true, failure = null) } },
            operation = {
                when (
                    val result = createLinkedProductUseCase(
                        draft = NewLinkedProduct(
                            businessId = business,
                            name = form.name.trim(),
                            unitId = unitId,
                            sku = form.sku.trim().ifEmpty { null },
                            barcode = form.barcode.ifEmpty { null },
                            purchaseUnitId = form.purchaseUnitId,
                            purchaseFactor = form.parsedPurchaseFactor,
                            salePrice = salePrice,
                        ),
                    )
                ) {
                    is CreateLinkedProductResult.Staged -> CreateOutcome.Link(
                        productName = result.product.name,
                        application = applyStagedLinkToCurrent(
                            lineId = line.lineId,
                            product = result.product,
                            confidencePermille = HUMAN_CONFIDENCE_PERMILLE,
                        ),
                    )

                    is CreateLinkedProductResult.Duplicate ->
                        CreateOutcome.DuplicateFound(result.existing)

                    CreateLinkedProductResult.SalePriceRequired -> CreateOutcome.Rejected(
                        ProductLinkingContract.SalePriceFailure.INVALID_PRICE,
                    )
                    is CreateLinkedProductResult.CurrencyMismatch -> CreateOutcome.Rejected(
                        ProductLinkingContract.SalePriceFailure.CURRENCY_MISMATCH,
                    )
                    CreateLinkedProductResult.NoActiveBusiness,
                    is CreateLinkedProductResult.BusinessMismatch,
                    -> CreateOutcome.Rejected(
                        ProductLinkingContract.SalePriceFailure.CONFIGURATION_CHANGED,
                    )
                }
            },
            onSuccess = { outcome ->
                when (outcome) {
                    is CreateOutcome.Rejected -> updateState {
                        copy(isBusy = false, salePriceFailure = outcome.failure)
                    }
                    is CreateOutcome.DuplicateFound -> updateState {
                        copy(
                            isBusy = false,
                            createForm = form.copy(duplicate = outcome.existing),
                        )
                    }

                    is CreateOutcome.Link -> when (outcome.application) {
                        is LinkApplication.Applied -> updateState {
                            advanceAfterLink(
                                lineId = line.lineId,
                                productName = outcome.productName,
                                reason = null,
                            )
                        }

                        LinkApplication.Conflict -> reloadAfterConflict()

                        LinkApplication.NotEditable -> updateState {
                            copy(
                                isBusy = false,
                                createForm = null,
                                failure = Failure.DRAFT_NOT_EDITABLE,
                            )
                        }

                        LinkApplication.Unavailable -> updateState { copy(isBusy = false) }
                    }
                }
            },
            onFailure = {
                updateState {
                    copy(
                        isBusy = false,
                        salePriceFailure = ProductLinkingContract.SalePriceFailure.SAVE_FAILED,
                    )
                }
            },
        )
    }

    private fun linkDuplicateExisting() {
        val state = uiState.value
        if (state.isBusy || state.isLoading) return
        val form = state.createForm ?: return
        val existing = form.duplicate ?: return
        val line = state.currentLine ?: return
        if (existing.salePrice?.currency != state.currency) {
            executeMain {
                updateState {
                    copy(
                        createForm = null,
                        salePriceForm = ProductLinkingContract.SalePriceForm(
                            product = existing,
                            lineId = line.lineId,
                            confidencePermille = HUMAN_CONFIDENCE_PERMILLE,
                            reason = null,
                            currency = state.currency,
                            value = form.salePrice,
                        ),
                        salePriceFailure = null,
                    )
                }
            }
            return
        }
        executeIo(
            before = { updateState { copy(isBusy = true, failure = null) } },
            operation = {
                applyLinkToCurrent(
                    lineId = line.lineId,
                    product = existing,
                    confidencePermille = HUMAN_CONFIDENCE_PERMILLE,
                )
            },
            onSuccess = { application ->
                when (application) {
                    is LinkApplication.Applied -> updateState {
                        advanceAfterLink(
                            lineId = line.lineId,
                            productName = existing.name,
                            reason = null,
                        )
                    }

                    LinkApplication.Conflict -> reloadAfterConflict()

                    LinkApplication.NotEditable -> updateState {
                        copy(isBusy = false, createForm = null, failure = Failure.DRAFT_NOT_EDITABLE)
                    }

                    LinkApplication.Unavailable -> updateState { copy(isBusy = false) }
                }
            },
            onFailure = { updateState { copy(isBusy = false, failure = Failure.SAVE_FAILED) } },
        )
    }

    private fun continueToSummary() {
        val state = uiState.value
        val draftId = state.draftId ?: return
        if (!state.canContinue) return
        executeIo(
            before = { updateState { copy(isBusy = true, failure = null) } },
            operation = {
                runDraftStageUseCase(
                    WorkflowRequest(
                        stage = WorkflowStage.PRODUCTS_LINKED,
                        draftId = draftId,
                        // El adaptador del flujo exige una línea de contexto para esta etapa.
                        lineId = state.initialLineId ?: state.currentLineId,
                    ),
                )
            },
            onSuccess = { snapshot ->
                updateState { copy(isBusy = false) }
                emitEffect(Effect.OpenSummary(snapshot.draftId))
            },
            onFailure = { updateState { copy(isBusy = false, failure = Failure.SAVE_FAILED) } },
        )
    }

    /**
     * Aplica el enlace sobre [edit] y lo persiste con el CAS de revisiones. Devuelve el nuevo
     * snapshot en memoria para que el llamador decida cómo reflejarlo en el estado.
     */
    private suspend fun applyLink(
        edit: InvoiceLinesEdit,
        expectedRevision: Long,
        lineId: LineId,
        product: Product,
        confidencePermille: Int,
    ): LinkApplication = applyCatalogLink(
        edit = edit,
        expectedRevision = expectedRevision,
        lineId = lineId,
        productId = product.productId,
        unitId = product.unitId,
        confidencePermille = confidencePermille,
        provenance = PurchaseProductProvenance.EXISTING,
        stagedProduct = null,
    )

    private suspend fun applyStagedLink(
        edit: InvoiceLinesEdit,
        expectedRevision: Long,
        lineId: LineId,
        product: StagedPurchaseProduct,
        confidencePermille: Int,
    ): LinkApplication = applyCatalogLink(
        edit = edit,
        expectedRevision = expectedRevision,
        lineId = lineId,
        productId = product.productId,
        unitId = product.purchaseUnitId ?: product.unitId,
        confidencePermille = confidencePermille,
        provenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
        stagedProduct = product,
    )

    private suspend fun applyCatalogLink(
        edit: InvoiceLinesEdit,
        expectedRevision: Long,
        lineId: LineId,
        productId: ProductId,
        unitId: UnitId,
        confidencePermille: Int,
        provenance: PurchaseProductProvenance,
        stagedProduct: StagedPurchaseProduct?,
    ): LinkApplication = applyCatalogLinks(
        edit = edit,
        expectedRevision = expectedRevision,
        links = listOf(
            CatalogLinkSpec(
                lineId = lineId,
                productId = productId,
                unitId = unitId,
                confidencePermille = confidencePermille,
                provenance = provenance,
                stagedProduct = stagedProduct,
            ),
        ),
    )

    private suspend fun applyCatalogLinks(
        edit: InvoiceLinesEdit,
        expectedRevision: Long,
        links: List<CatalogLinkSpec>,
    ): LinkApplication {
        if (links.isEmpty()) return LinkApplication.Applied(edit)
        val byLineId = links.associateBy(CatalogLinkSpec::lineId)
        if (byLineId.size != links.size) return LinkApplication.Unavailable
        val knownLineIds = edit.lines.mapTo(mutableSetOf()) { it.lineId }
        if (!byLineId.keys.all { it in knownLineIds }) return LinkApplication.Unavailable
        val updatedLines = edit.lines.map { line ->
            val spec = byLineId[line.lineId] ?: return@map line
            line.copy(
                linkedProductId = spec.productId,
                linkedUnitId = spec.unitId,
                linkConfidence = spec.confidencePermille,
                productProvenance = spec.provenance,
                stagedProduct = spec.stagedProduct,
            )
        }
        val updated = edit.copy(
            lines = updatedLines,
            revision = Math.incrementExact(edit.revision),
        )
        return when (
            saveInvoiceLinesEditUseCase(
                edit = updated,
                expectedRevision = expectedRevision,
                catalogLinkLineIds = byLineId.keys,
            )
        ) {
            SaveInvoiceLinesEditResult.SAVED,
            SaveInvoiceLinesEditResult.ALREADY_SAVED,
            -> LinkApplication.Applied(updated)

            SaveInvoiceLinesEditResult.STALE_REVISION,
            SaveInvoiceLinesEditResult.CONFLICT,
            -> LinkApplication.Conflict

            SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE -> LinkApplication.NotEditable
        }
    }

    private suspend fun applyLinkToCurrent(
        lineId: LineId,
        product: Product,
        confidencePermille: Int,
    ): LinkApplication {
        val edit = currentEdit ?: return LinkApplication.Unavailable
        val application = applyLink(edit, knownRevision, lineId, product, confidencePermille)
        if (application is LinkApplication.Applied) {
            currentEdit = application.edit
            knownRevision = application.edit.revision
        }
        return application
    }

    private suspend fun applyStagedLinkToCurrent(
        lineId: LineId,
        product: StagedPurchaseProduct,
        confidencePermille: Int,
    ): LinkApplication {
        val edit = currentEdit ?: return LinkApplication.Unavailable
        val application = applyStagedLink(
            edit,
            knownRevision,
            lineId,
            product,
            confidencePermille,
        )
        if (application is LinkApplication.Applied) {
            currentEdit = application.edit
            knownRevision = application.edit.revision
        }
        return application
    }

    private fun reloadAfterConflict() {
        conflictPending = true
        updateState {
            copy(
                isBusy = false,
                createForm = null,
                salePriceForm = null,
                salePriceFailure = null,
            )
        }
        load()
    }

    /** Marca la línea como confirmada y avanza a la siguiente pendiente, sin salir de la pantalla. */
    private fun State.advanceAfterLink(
        lineId: LineId,
        productName: String?,
        reason: ProductMatchReason?,
    ): State {
        val updatedLines = lines.map { item ->
            if (item.lineId == lineId) {
                item.copy(
                    status = LinkStatus.CONFIRMED,
                    linkedProductName = productName,
                    linkReason = reason,
                    requiresSalePrice = false,
                )
            } else {
                item
            }
        }
        val target = nextPendingLine(updatedLines, after = lineId)
            ?: updatedLines.first { it.lineId == lineId }
        priceRequiredByLine = priceRequiredByLine - lineId
        val nextPriceForm = priceRequiredByLine[target.lineId]?.let { product ->
            ProductLinkingContract.SalePriceForm(
                product = product,
                lineId = target.lineId,
                confidencePermille = HUMAN_CONFIDENCE_PERMILLE,
                reason = null,
                currency = currency,
            )
        }
        candidateSelectionBlockedBySearch = false
        return copy(
            isBusy = false,
            failure = null,
            createForm = null,
            salePriceForm = nextPriceForm,
            salePriceFailure = null,
            lines = updatedLines,
            currentLineId = target.lineId,
            candidates = candidatesByLine[target.lineId].orEmpty(),
            searchQuery = target.description,
            searchStatus = SearchStatus.IDLE,
        )
    }

    private fun nextPendingLine(lines: List<LineLinking>, after: LineId): LineLinking? {
        val pending: (LineLinking) -> Boolean = { line ->
            line.status == LinkStatus.NEEDS_CHOICE || line.status == LinkStatus.NO_MATCH
        }
        val index = lines.indexOfFirst { it.lineId == after }
        return lines.drop(index + 1).firstOrNull(pending) ?: lines.firstOrNull(pending)
    }

    private fun InvoiceLineEdit.toLinking(
        status: LinkStatus,
        linkedProductName: String? = null,
        linkReason: ProductMatchReason? = null,
        requiresSalePrice: Boolean = false,
    ): LineLinking = LineLinking(
        lineId = lineId,
        position = position,
        description = description.selectedValue.orEmpty(),
        code = code.selectedValue?.takeIf { it.isNotBlank() },
        status = status,
        linkedProductName = linkedProductName,
        linkReason = linkReason,
        requiresSalePrice = requiresSalePrice,
    )

    private companion object {
        /** Reintentos de la cascada completa tras un conflicto de revisiones. */
        const val MAX_CONFLICT_RELOADS = 2

        /** Los enlaces confirmados por una persona registran confianza plena. */
        const val HUMAN_CONFIDENCE_PERMILLE = 1_000

        const val MAX_SEARCH_QUERY_LENGTH = 512
        const val MAX_PRICE_LENGTH = 64
    }
}

private fun linkingInitialState(savedStateHandle: SavedStateHandle): State {
    val draftId = DraftId.parse(savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID))
    val lineId = LineId.parse(savedStateHandle.get<String>(RouteArgumentKeys.LINE_ID))
    return State(
        draftId = draftId,
        initialLineId = lineId,
        failure = if (draftId == null || lineId == null) {
            ProductLinkingContract.Failure.INVALID_ROUTE
        } else {
            null
        },
    )
}
