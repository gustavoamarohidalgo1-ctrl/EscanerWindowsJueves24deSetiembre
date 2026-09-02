package com.facturastock.app.feature.purchases

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseHistoryRequest
import com.facturastock.app.domain.model.PurchaseRetainedImage
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.RetainedImageReadResult
import com.facturastock.app.domain.usecase.ObservePurchaseDetailUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseHistoryUseCase
import com.facturastock.app.domain.usecase.LoadRemotePurchaseDocumentUseCase
import com.facturastock.app.domain.usecase.ReadRetainedImageUseCase
import com.facturastock.app.domain.usecase.RemotePurchaseDocumentLoadResult
import com.facturastock.app.domain.usecase.RetryActivePurchaseBackupResult
import com.facturastock.app.domain.usecase.RetryPurchaseBackupUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex

@HiltViewModel
class PurchasesViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observePurchaseHistory: ObservePurchaseHistoryUseCase,
    private val observePurchaseDetail: ObservePurchaseDetailUseCase,
    private val readRetainedImage: ReadRetainedImageUseCase,
    private val loadRemotePurchaseDocument: LoadRemotePurchaseDocumentUseCase,
    private val retryPurchaseBackup: RetryPurchaseBackupUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<PurchasesContract.State, PurchasesContract.Action, PurchasesContract.Effect>(
    initialState = restoredPurchasesState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var observation: Job? = null
    private var imageLoadJob: Job? = null
    private var searchJob: Job? = null
    private var pendingQuery: String = uiState.value.query
    private var pendingStatusFilter: PurchaseStatus? = uiState.value.statusFilter
    private var pendingSyncFilter: PurchaseSyncState? = uiState.value.syncFilter
    private val backupRetryMutex = Mutex()

    init {
        load()
    }

    override fun onAction(action: PurchasesContract.Action) {
        when (action) {
            PurchasesContract.Action.Load,
            PurchasesContract.Action.Retry,
            -> load()

            is PurchasesContract.Action.SearchChanged -> updateSearch(action.query)
            is PurchasesContract.Action.StatusFilterChanged -> {
                pendingStatusFilter = action.status
                searchJob?.cancel()
                updateFilters(status = action.status, sync = pendingSyncFilter)
            }
            is PurchasesContract.Action.SyncFilterChanged -> {
                pendingSyncFilter = action.syncState
                searchJob?.cancel()
                updateFilters(status = pendingStatusFilter, sync = action.syncState)
            }
            PurchasesContract.Action.LoadMore -> loadMore()
            is PurchasesContract.Action.RetryBackup -> retryBackup(action.purchaseId)
            PurchasesContract.Action.TechnicalDetailsToggled -> toggleTechnicalDetails()

            is PurchasesContract.Action.PurchaseSelected -> executeMain {
                emitEffect(PurchasesContract.Effect.OpenPurchase(action.purchaseId))
            }
            is PurchasesContract.Action.VoidSelected -> executeMain {
                val detail = uiState.value.detail
                if (
                    detail?.summary?.purchaseId == action.purchaseId &&
                    detail.summary.status == PurchaseStatus.POSTED
                ) {
                    emitEffect(PurchasesContract.Effect.OpenVoid(action.purchaseId))
                }
            }

            PurchasesContract.Action.BackSelected -> executeMain {
                emitEffect(PurchasesContract.Effect.Back)
            }
        }
    }

    private fun retryBackup(purchaseId: PurchaseId) {
        if (
            uiState.value.retryingBackupPurchaseId != null ||
            !backupRetryMutex.tryLock()
        ) return
        val visible = uiState.value.detail?.summary
            ?.takeIf { it.purchaseId == purchaseId }
            ?: uiState.value.purchases.firstOrNull { it.purchaseId == purchaseId }
        if (visible == null || visible.syncState !in RETRYABLE_SYNC_STATES) {
            backupRetryMutex.unlock()
            return
        }
        executeIo(
            before = {
                updateState {
                    copy(
                        retryingBackupPurchaseId = purchaseId,
                        backupRetryFailedPurchaseId = null,
                    )
                }
            },
            operation = {
                try {
                    retryPurchaseBackup(purchaseId)
                } finally {
                    backupRetryMutex.unlock()
                }
            },
            onSuccess = { result ->
                updateState {
                    copy(
                        retryingBackupPurchaseId = null,
                        backupRetryFailedPurchaseId = purchaseId.takeIf {
                            result == RetryActivePurchaseBackupResult.NotFound ||
                                result == RetryActivePurchaseBackupResult.NoActiveBusiness
                        },
                    )
                }
            },
            onFailure = {
                updateState {
                    copy(
                        retryingBackupPurchaseId = null,
                        backupRetryFailedPurchaseId = purchaseId,
                    )
                }
            },
        )
    }

    private fun load() {
        if (uiState.value.failure == PurchasesContract.Failure.INVALID_PURCHASE_ID) {
            executeMain { emitEffect(PurchasesContract.Effect.CloseInvalidRoute) }
            return
        }
        observation?.cancel()
        observation = executeMain {
            updateState {
                copy(
                    isLoading = detail == null && purchases.isEmpty(),
                    failure = null,
                )
            }
            try {
                val purchaseId = uiState.value.purchaseId
                if (purchaseId == null) observeList() else observeDetail(purchaseId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState {
                    copy(
                        isLoading = false,
                        isLoadingMore = false,
                        isFiltering = false,
                        failure = PurchasesContract.Failure.LOAD_FAILED,
                    )
                }
            }
        }
    }

    private suspend fun observeList() {
        val request = uiState.value.toHistoryRequest()
        observePurchaseHistory(request).collect { page ->
            updateState {
                copy(
                    isLoading = false,
                    isFiltering = false,
                    isLoadingMore = false,
                    purchases = page.items,
                    hasMore = page.hasMore,
                    backupRetryFailedPurchaseId = backupRetryFailedPurchaseId?.takeIf { failedId ->
                        page.items.any { purchase ->
                            purchase.purchaseId == failedId &&
                                purchase.syncState in RETRYABLE_SYNC_STATES
                        }
                    },
                    failure = null,
                )
            }
        }
    }

    private suspend fun observeDetail(purchaseId: PurchaseId) {
        observePurchaseDetail(purchaseId).collectLatest { purchase ->
            imageLoadJob?.cancel()
            clearRetainedImageBytes()
            updateState {
                copy(
                    isLoading = false,
                    isFiltering = false,
                    isLoadingMore = false,
                    detail = purchase,
                    retainedImages = emptyMap(),
                    backupRetryFailedPurchaseId = backupRetryFailedPurchaseId?.takeIf { failedId ->
                        purchase?.summary?.let { summary ->
                            summary.purchaseId == failedId &&
                                summary.syncState in RETRYABLE_SYNC_STATES
                        } == true
                    },
                    failure = if (purchase == null) {
                        PurchasesContract.Failure.PURCHASE_NOT_FOUND
                    } else {
                        null
                    },
                )
            }
            if (purchase != null && uiState.value.technicalDetailsVisible) {
                loadRetainedImages(purchase)
            }
        }
    }

    private fun toggleTechnicalDetails() {
        val purchase = uiState.value.detail ?: return
        if (uiState.value.technicalDetailsVisible) {
            imageLoadJob?.cancel()
            clearRetainedImageBytes()
            executeMain {
                updateState {
                    copy(
                        technicalDetailsVisible = false,
                        retainedImages = emptyMap(),
                    )
                }
            }
        } else {
            executeMain {
                updateState { copy(technicalDetailsVisible = true) }
                loadRetainedImages(purchase)
            }
        }
    }

    private fun loadRetainedImages(purchase: PurchaseReadDetail) {
        imageLoadJob?.cancel()
        imageLoadJob = executeIo(
            before = {
                clearRetainedImageBytes()
                updateState {
                    copy(
                        retainedImages = purchase.images.associate { image ->
                            image.imageId to PurchasesContract.RetainedImageContent.Loading
                        },
                    )
                }
            },
            operation = {
                val contents = linkedMapOf<
                    ImageId,
                    PurchasesContract.RetainedImageContent,
                >()
                try {
                    purchase.images.forEach { image ->
                        contents[image.imageId] = loadImageContent(purchase, image)
                    }
                    contents
                } catch (error: Throwable) {
                    contents.values.clearAvailableImageBytes()
                    throw error
                }
            },
            onSuccess = { contents ->
                updateState {
                    if (
                        technicalDetailsVisible &&
                        detail?.summary?.purchaseId == purchase.summary.purchaseId
                    ) {
                        copy(retainedImages = contents)
                    } else {
                        contents.values
                            .filterIsInstance<
                                PurchasesContract.RetainedImageContent.Available
                            >()
                            .forEach { it.encodedBytes.fill(0) }
                        this
                    }
                }
            },
            onFailure = {
                updateState {
                    if (technicalDetailsVisible) {
                        copy(
                            retainedImages = purchase.images.associate { image ->
                                image.imageId to
                                    PurchasesContract.RetainedImageContent.RemoteUnavailable
                            },
                        )
                    } else {
                        this
                    }
                }
            },
        )
    }

    private fun clearRetainedImageBytes() {
        uiState.value.retainedImages.values.clearAvailableImageBytes()
    }

    private suspend fun loadImageContent(
        purchase: PurchaseReadDetail,
        image: PurchaseRetainedImage,
    ): PurchasesContract.RetainedImageContent {
        val local = try {
            readRetainedImage.forDisplay(image.relativeFilePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PurchasesContract.RetainedImageContent.RemoteUnavailable
        }
        when (local) {
            is RetainedImageReadResult.Available -> {
                if (local.bytes.isEmpty()) {
                    local.bytes.fill(0)
                    return PurchasesContract.RetainedImageContent.IntegrityRejected
                }
                return try {
                    PurchasesContract.RetainedImageContent.Available.from(
                        local.bytes,
                        PurchasesContract.RetainedImageContent.Available.Source.LOCAL,
                    )
                } finally {
                    local.bytes.fill(0)
                }
            }
            RetainedImageReadResult.IntegrityRejected ->
                return PurchasesContract.RetainedImageContent.IntegrityRejected
            RetainedImageReadResult.Unavailable ->
                return PurchasesContract.RetainedImageContent.RemoteUnavailable
            RetainedImageReadResult.Absent -> Unit
        }

        val remote = try {
            loadRemotePurchaseDocument(
                businessId = purchase.summary.businessId,
                purchaseId = purchase.summary.purchaseId,
                imageId = image.imageId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PurchasesContract.RetainedImageContent.RemoteUnavailable
        }
        return when (remote) {
            is RemotePurchaseDocumentLoadResult.Downloaded -> {
                if (remote.jpeg.isEmpty()) {
                    remote.jpeg.fill(0)
                    PurchasesContract.RetainedImageContent.IntegrityRejected
                } else {
                    try {
                        PurchasesContract.RetainedImageContent.Available.from(
                            remote.jpeg,
                            PurchasesContract.RetainedImageContent.Available.Source.REMOTE,
                        )
                    } finally {
                        remote.jpeg.fill(0)
                    }
                }
            }
            RemotePurchaseDocumentLoadResult.NotEligible,
            RemotePurchaseDocumentLoadResult.NotFound,
            -> PurchasesContract.RetainedImageContent.Unavailable
            RemotePurchaseDocumentLoadResult.AccessDenied ->
                PurchasesContract.RetainedImageContent.RemoteAccessDenied
            RemotePurchaseDocumentLoadResult.IntegrityRejected ->
                PurchasesContract.RetainedImageContent.IntegrityRejected
            RemotePurchaseDocumentLoadResult.Unavailable ->
                PurchasesContract.RetainedImageContent.RemoteUnavailable
        }
    }

    private fun updateFilters(
        query: String = pendingQuery,
        status: PurchaseStatus? = pendingStatusFilter,
        sync: PurchaseSyncState? = pendingSyncFilter,
    ) {
        executeMain {
            val safeQuery = query.take(MAX_QUERY_LENGTH)
            pendingQuery = safeQuery
            pendingStatusFilter = status
            pendingSyncFilter = sync
            savedStateHandle[QUERY_KEY] = safeQuery
            savedStateHandle[STATUS_FILTER_KEY] = status?.name
            savedStateHandle[SYNC_FILTER_KEY] = sync?.name
            savedStateHandle[VISIBLE_PAGES_KEY] = 1
            updateState {
                copy(
                    query = safeQuery,
                    statusFilter = status,
                    syncFilter = sync,
                    hasMore = false,
                    visiblePages = 1,
                    isFiltering = true,
                    isLoadingMore = false,
                    failure = null,
                )
            }
            load()
        }
    }

    private fun updateSearch(query: String) {
        val safeQuery = query.take(MAX_QUERY_LENGTH)
        pendingQuery = safeQuery
        searchJob?.cancel()
        searchJob = executeMain {
            observation?.cancel()
            savedStateHandle[QUERY_KEY] = safeQuery
            savedStateHandle[VISIBLE_PAGES_KEY] = 1
            updateState {
                copy(
                    query = safeQuery,
                    hasMore = false,
                    visiblePages = 1,
                    isFiltering = true,
                    isLoadingMore = false,
                    failure = null,
                )
            }
            delay(SEARCH_DEBOUNCE_MILLIS)
            load()
        }
    }

    private fun loadMore() {
        val state = uiState.value
        if (!state.hasMore || state.isLoading || state.isFiltering || state.isLoadingMore) return
        executeMain {
            val current = uiState.value
            if (
                !current.hasMore || current.isLoading || current.isFiltering ||
                current.isLoadingMore
            ) {
                return@executeMain
            }
            val nextVisiblePages = current.visiblePages + 1
            savedStateHandle[VISIBLE_PAGES_KEY] = minOf(
                nextVisiblePages,
                PURCHASE_HISTORY_MAX_RESTORED_PAGES,
            )
            updateState {
                copy(
                    visiblePages = nextVisiblePages,
                    isLoadingMore = true,
                    failure = null,
                )
            }
            load()
        }
    }

    override fun onCleared() {
        observation?.cancel()
        imageLoadJob?.cancel()
        searchJob?.cancel()
        clearRetainedImageBytes()
        super.onCleared()
    }

    private companion object {
        const val QUERY_KEY = "purchases.query"
        const val STATUS_FILTER_KEY = "purchases.statusFilter"
        const val SYNC_FILTER_KEY = "purchases.syncFilter"
        const val VISIBLE_PAGES_KEY = "purchases.visiblePages"
        const val MAX_QUERY_LENGTH = 200
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}

private fun restoredPurchasesState(savedStateHandle: SavedStateHandle): PurchasesContract.State {
    val rawPurchaseId = savedStateHandle.get<String>(RouteArgumentKeys.PURCHASE_ID)
    val purchaseId = PurchaseId.parse(rawPurchaseId)
    return PurchasesContract.State(
        purchaseId = purchaseId,
        query = savedStateHandle.get<String>("purchases.query").orEmpty().take(200),
        statusFilter = savedStateHandle.get<String>("purchases.statusFilter").toEnumOrNull(),
        syncFilter = savedStateHandle.get<String>("purchases.syncFilter").toEnumOrNull(),
        visiblePages = savedStateHandle.get<Int>("purchases.visiblePages")
            ?.coerceIn(1, PURCHASE_HISTORY_MAX_RESTORED_PAGES)
            ?: 1,
        failure = if (rawPurchaseId != null && purchaseId == null) {
            PurchasesContract.Failure.INVALID_PURCHASE_ID
        } else {
            null
        },
    )
}

private fun PurchasesContract.State.toHistoryRequest(): PurchaseHistoryRequest =
    PurchaseHistoryRequest(
        query = query,
        status = statusFilter,
        syncState = syncFilter,
        pageSize = PURCHASE_HISTORY_PAGE_SIZE,
        visiblePages = visiblePages,
    )

private const val PURCHASE_HISTORY_PAGE_SIZE = 30
private const val PURCHASE_HISTORY_MAX_RESTORED_PAGES = 20

private fun Iterable<PurchasesContract.RetainedImageContent>.clearAvailableImageBytes() {
    filterIsInstance<PurchasesContract.RetainedImageContent.Available>()
        .forEach { content -> content.encodedBytes.fill(0) }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

private val RETRYABLE_SYNC_STATES = setOf(
    PurchaseSyncState.ERROR,
    PurchaseSyncState.CONFLICT,
)
