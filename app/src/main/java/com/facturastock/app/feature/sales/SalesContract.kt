package com.facturastock.app.feature.sales

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.normalizeDebtorName
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.math.BigDecimal

object SalesContract {
    enum class EntryKind { CASH, CREDIT }

    enum class EntryMode { SCANNER, MANUAL }

    enum class NameMatchKind { EXACT, SIMILAR }

    @Immutable
    data class CartLine(
        val lineId: String,
        val productId: ProductId,
        val productName: String,
        val locationId: LocationId,
        val locationName: String,
        val unitCode: String,
        val availableQuantity: BigDecimal,
        val quantityInput: String,
        val unitPriceInput: String,
        val quantityValid: Boolean = true,
        val priceValid: Boolean = true,
        val lineTotal: Money?,
    )

    /** Una opción concreta de producto y almacén; nunca representa stock agregado ambiguo. */
    @Immutable
    data class ProductOption(
        val productId: ProductId,
        val productName: String,
        val locationId: LocationId,
        val locationName: String,
        val unitCode: String,
        val availableQuantity: BigDecimal,
        val sku: String?,
        val barcode: String?,
        /** Sugerencia para una línea nueva; nunca revaloriza una línea ya guardada. */
        val suggestedSalePrice: Money? = null,
        val nameMatchKind: NameMatchKind? = null,
    )

    @Immutable
    data class BarcodeReplacement(
        val product: ProductOption,
        val existingBarcode: String?,
        val newBarcode: String,
    )

    /** Snapshot exacto mostrado en la segunda confirmación. */
    @Immutable
    data class CheckoutReview(
        val cartId: String,
        val version: Long,
        val contentHash: String,
        val total: Money,
        /** Nombre canónico capturado junto al carrito para una venta a crédito. */
        val debtorName: String? = null,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        /** Operación exclusiva: alta, retiro, asociación o confirmación de venta. */
        val isMutating: Boolean = false,
        /** Persistencia serializada de inputs; no bloquea seguir editando las líneas. */
        val isSavingLineEdits: Boolean = false,
        val entryKind: EntryKind = EntryKind.CASH,
        val debtorNameInput: String = "",
        val mode: EntryMode = EntryMode.SCANNER,
        val scannerActive: Boolean = false,
        val cartId: String? = null,
        val currencyCode: String = "PEN",
        val cartVersion: Long = 0L,
        val cartContentHash: String? = null,
        val cartLines: List<CartLine> = emptyList(),
        /** Hay una edición local aún no confirmada por Room. */
        val hasPendingEdits: Boolean = false,
        val total: Money? = null,
        val query: String = "",
        val isNameSearchRunning: Boolean = false,
        val productOptions: List<ProductOption> = emptyList(),
        val pendingAssociationBarcode: String? = null,
        /** La asociación se guardó, pero el alta posterior de la línea no se completó. */
        val barcodeAssociatedWithoutCartAdd: Boolean = false,
        val pendingLocations: List<ProductOption> = emptyList(),
        val pendingReplacement: BarcodeReplacement? = null,
        /** El catálogo/stock visible puede estar desactualizado hasta una nueva emisión sana. */
        val catalogLoadFailed: Boolean = false,
        /** El carrito visible se conserva, pero no debe editarse ni confirmarse hasta reobservarlo. */
        val cartLoadFailed: Boolean = false,
        /** La consulta visible falló; no invalida ni bloquea el carrito ya guardado. */
        val searchFailed: Boolean = false,
        val checkoutReview: CheckoutReview? = null,
        /** Confirmación explícita; jamás se descartan inputs inválidos solo por pulsar Back. */
        val discardEditsReview: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val isAssociating: Boolean
            get() = pendingAssociationBarcode != null

        val canonicalDebtorName: String?
            get() = runCatching { normalizeDebtorName(debtorNameInput) }.getOrNull()

        val isDebtorNameValid: Boolean
            get() = entryKind == EntryKind.CASH || canonicalDebtorName != null

        val showCheckoutConfirmation: Boolean
            get() = checkoutReview != null

        val canCheckout: Boolean
            get() = !isLoading && !isMutating && !isSavingLineEdits && !hasPendingEdits &&
                cartLines.isNotEmpty() &&
                cartLines.all { it.quantityValid && it.priceValid && it.lineTotal != null } &&
                total != null &&
                cartId != null && cartContentHash != null && cartVersion >= 0L && failure == null &&
                isDebtorNameValid &&
                !catalogLoadFailed && !cartLoadFailed &&
                checkoutReview == null && !discardEditsReview

        val canRouteScannerInput: Boolean
            get() = mode == EntryMode.SCANNER && !isLoading && !isMutating &&
                !isSavingLineEdits && !isAssociating && !hasPendingEdits &&
                cartId != null && cartContentHash != null &&
                !catalogLoadFailed && !cartLoadFailed &&
                pendingLocations.isEmpty() && pendingReplacement == null && checkoutReview == null &&
                !discardEditsReview && when (failure) {
                    null,
                    Failure.INVALID_BARCODE,
                    Failure.BARCODE_NOT_FOUND,
                    Failure.PRODUCT_UNAVAILABLE,
                    Failure.INSUFFICIENT_STOCK,
                    -> true
                    else -> false
                }
    }

    enum class Failure {
        NO_ACTIVE_BUSINESS,
        LOAD_FAILED,
        INVALID_BARCODE,
        BARCODE_NOT_FOUND,
        BARCODE_CONFLICT,
        PRODUCT_UNAVAILABLE,
        INVALID_QUANTITY,
        INVALID_PRICE,
        INVALID_CREDIT_TERMS,
        INCOMPLETE_LINE,
        STALE_CART,
        INSUFFICIENT_STOCK,
        ONLINE_REQUIRED,
        INVENTORY_MIGRATION_REQUIRED,
        CHECKOUT_FAILED,
        SAVE_FAILED,
    }

    sealed interface Action : UiAction {
        data object Retry : Action
        data object RetryCatalog : Action
        data object RetryCart : Action
        data object RetrySearch : Action
        data class EntryKindChanged(val kind: EntryKind) : Action
        data class DebtorNameChanged(val value: String) : Action
        data class ModeChanged(val mode: EntryMode) : Action
        data class ScannerAvailabilityChanged(val active: Boolean) : Action
        data class BarcodeScanned(val value: String) : Action
        data class SearchChanged(val value: String) : Action
        data class ProductSelected(val productId: ProductId, val locationId: LocationId) : Action
        data class LocationSelected(val productId: ProductId, val locationId: LocationId) : Action
        data object LocationSelectionDismissed : Action
        data object AssociationDismissed : Action
        data object BarcodeReplacementConfirmed : Action
        data object BarcodeReplacementDismissed : Action
        data class QuantityChanged(val lineId: String, val value: String) : Action
        data class UnitPriceChanged(val lineId: String, val value: String) : Action
        data class QuantityIncremented(val lineId: String) : Action
        data class QuantityDecremented(val lineId: String) : Action
        data class LineRemoved(val lineId: String) : Action
        data object CheckoutRequested : Action
        data object CheckoutConfirmed : Action
        data object CheckoutDismissed : Action
        data object DiscardEditsConfirmed : Action
        data object DiscardEditsDismissed : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data object Back : Effect
        data object CreditSalePosted : Effect
        data class ShowMessage(val message: Message) : Effect
    }

    enum class Message {
        BARCODE_ASSOCIATED,
        SALE_POSTED,
    }

    const val MIN_DEBTOR_NAME_LENGTH = 2
    const val MAX_DEBTOR_NAME_LENGTH = 120

}
