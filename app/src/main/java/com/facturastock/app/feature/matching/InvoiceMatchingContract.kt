package com.facturastock.app.feature.matching

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceMatchUnitChoice
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object InvoiceMatchingContract {
    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val isLoading: Boolean = true,
        val items: List<ScannedItemMatch> = emptyList(),
        val isSaving: Boolean = false,
        val linkingItemIndex: Int? = null,
        val searchQuery: String = "",
        val searchResults: List<Product> = emptyList(),
        val isSearching: Boolean = false,
        val searchFailed: Boolean = false,
        val creatingItemIndex: Int? = null,
        val createName: String = "",
        val createBarcode: String = "",
        val createPrice: String = "",
        val createQuantity: String = "",
        val createError: String? = null,
        val editingItemIndex: Int? = null,
        val editQuantity: String = "",
        val editCost: String = "",
        val editCurrency: String = "",
        val editUnitChoice: InvoiceMatchUnitChoice? = null,
        val editError: String? = null,
        val businessCurrency: CurrencyCode = CurrencyCode.of("PEN"),
        val recoveryMessage: String? = null,
        val failure: Failure? = null,
        val saveFailure: Failure? = null,
    ) : UiState {
        val readyCount: Int get() = items.count { it.isReadyForInventory(businessCurrency) }
        val canSaveToWarehouse: Boolean
            get() =
                !isLoading &&
                    !isSaving &&
                    failure == null &&
                    items.isNotEmpty() &&
                    items.all { it.isReadyForInventory(businessCurrency) }
        val blocksList: Boolean
            get() =
                failure == Failure.LOAD_FAILED ||
                    failure == Failure.NO_ACTIVE_BUSINESS ||
                    failure == Failure.INVALID_DRAFT_ID
    }

    enum class Failure {
        LOAD_FAILED,
        SAVE_FAILED,
        NO_ACTIVE_BUSINESS,
        INVALID_DRAFT_ID,
        CLOUD_BOUND,
        UNRESOLVED_LINES,
        NOTHING_TO_APPLY,
        MISSING_LOCATION,
        REVIEW_REQUIRED,
        DRAFT_CHANGED,
        LEGACY_CONFLICT,
    }

    sealed interface Action : UiAction {
        data class OpenLineEditor(
            val lineIndex: Int,
        ) : Action

        data class EditQuantityChanged(
            val value: String,
        ) : Action

        data class EditCostChanged(
            val value: String,
        ) : Action

        data class EditCurrencyChanged(
            val value: String,
        ) : Action

        data class EditUnitChoiceChanged(
            val value: InvoiceMatchUnitChoice,
        ) : Action

        data object SubmitLineEdit : Action

        data object CloseLineEditor : Action

        data object Start : Action

        data class OpenLinkingDialog(
            val lineIndex: Int,
        ) : Action

        data object CloseLinkingDialog : Action

        data object RetrySearch : Action

        data class SearchQueryChanged(
            val query: String,
        ) : Action

        data class ProductSelected(
            val lineIndex: Int,
            val product: Product,
        ) : Action

        data class QuickSuggestionSelected(
            val lineIndex: Int,
            val product: Product,
        ) : Action

        data class UnlinkItem(
            val lineIndex: Int,
        ) : Action

        data class OpenCreateDialog(
            val lineIndex: Int,
        ) : Action

        data object OpenAddNewProductDialog : Action

        data object CloseCreateDialog : Action

        data class CreateNameChanged(
            val value: String,
        ) : Action

        data class CreateBarcodeChanged(
            val value: String,
        ) : Action

        data class CreatePriceChanged(
            val value: String,
        ) : Action

        data class CreateQuantityChanged(
            val value: String,
        ) : Action

        data object SubmitCreateProduct : Action

        data object SaveToWarehouse : Action

        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class MatchingConfirmed(
            val updatedCount: Int,
        ) : Effect

        data object Back : Effect
    }
}

/** Every visible line must be resolved before the source document can be removed. */
internal fun ScannedItemMatch.isReadyForInventory(currency: CurrencyCode): Boolean {
    val product = matchedProduct ?: return false
    if (!hasStockableQuantity || unitCost == null || unitCost.signum() < 0 || sourceCurrency != currency) return false
    return when (unitChoice) {
        InvoiceMatchUnitChoice.INVENTORY -> {
            true
        }

        InvoiceMatchUnitChoice.PURCHASE -> {
            product.purchaseUnitId != null && product.purchaseFactor != null
        }

        null -> {
            !sourceUnitCode.isNullOrBlank() && (
                sourceUnitCode.equals(inventoryUnitCode, ignoreCase = true) ||
                    (product.purchaseUnitId != null && sourceUnitCode.equals(purchaseUnitCode, ignoreCase = true))
            )
        }
    }
}
