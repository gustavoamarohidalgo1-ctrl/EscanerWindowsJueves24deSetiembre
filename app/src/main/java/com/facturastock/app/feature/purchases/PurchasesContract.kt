package com.facturastock.app.feature.purchases

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object PurchasesContract {
    @Immutable
    data class State(
        val purchaseId: PurchaseId? = null,
        val isLoading: Boolean = false,
        val isFiltering: Boolean = false,
        val isLoadingMore: Boolean = false,
        val purchases: List<PurchaseReadSummary> = emptyList(),
        val hasMore: Boolean = false,
        val visiblePages: Int = 1,
        val detail: PurchaseReadDetail? = null,
        /** Bytes descifrados solo en memoria; nunca se guardan en SavedState ni en un efecto. */
        val retainedImages: Map<ImageId, RetainedImageContent> = emptyMap(),
        val technicalDetailsVisible: Boolean = false,
        val query: String = "",
        val statusFilter: PurchaseStatus? = null,
        val syncFilter: PurchaseSyncState? = null,
        val retryingBackupPurchaseId: PurchaseId? = null,
        val backupRetryFailedPurchaseId: PurchaseId? = null,
        val failure: Failure? = null,
    ) : UiState

    sealed interface RetainedImageContent {
        data object Loading : RetainedImageContent

        data object Unavailable : RetainedImageContent

        data object RemoteUnavailable : RetainedImageContent

        data object RemoteAccessDenied : RetainedImageContent

        data object IntegrityRejected : RetainedImageContent

        class Available private constructor(
            /** Coil solo lee este arreglo; la app nunca lo persiste ni lo comparte. */
            val encodedBytes: ByteArray,
            /** REMOTE es una vista temporal en memoria, no una restauración del archivo local. */
            val source: Source,
        ) : RetainedImageContent {
            enum class Source { LOCAL, REMOTE }

            companion object {
                fun from(bytes: ByteArray, source: Source = Source.LOCAL): Available {
                    require(bytes.isNotEmpty())
                    return Available(bytes.copyOf(), source)
                }
            }
        }
    }

    enum class Failure {
        INVALID_PURCHASE_ID,
        LOAD_FAILED,
        PURCHASE_NOT_FOUND,
    }

    sealed interface Action : UiAction {
        data object Load : Action
        data object Retry : Action
        data class SearchChanged(val query: String) : Action
        data class StatusFilterChanged(val status: PurchaseStatus?) : Action
        data class SyncFilterChanged(val syncState: PurchaseSyncState?) : Action
        data object LoadMore : Action
        data class RetryBackup(val purchaseId: PurchaseId) : Action
        data object TechnicalDetailsToggled : Action
        data class PurchaseSelected(val purchaseId: PurchaseId) : Action
        data class VoidSelected(val purchaseId: PurchaseId) : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenPurchase(val purchaseId: PurchaseId) : Effect
        data class OpenVoid(val purchaseId: PurchaseId) : Effect
        data object Back : Effect
        data object CloseInvalidRoute : Effect
    }
}
