package com.facturastock.app.feature.sales

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.WeightSaleCalculator
import com.facturastock.app.domain.model.normalizeDebtorName
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.math.BigDecimal

object SalesContract {
    enum class EntryKind { CASH, CREDIT }

    enum class EntryMode { SCANNER, MANUAL }

    enum class EntryStep { SELECT_KIND, SELECT_MODE, SELL }

    enum class NameMatchKind { EXACT, SIMILAR }

    enum class BarcodeSelectionReason { AMBIGUOUS, CATALOG_CHANGED }

    enum class WeightEntryMode { AMOUNT, QUANTITY }

    enum class WeightSaleFailure { PRODUCT_CHANGED, PRODUCT_UNAVAILABLE, STALE_CART, SAVE_FAILED }

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
        val isWeightProduct: Boolean = false,
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
        val isWeightProduct: Boolean = false,
    )

    @Immutable
    data class WeightSaleEditor(
        val product: ProductOption,
        val cartId: String,
        val cartVersion: Long,
        val businessId: BusinessId,
        val lineId: String? = null,
        val mode: WeightEntryMode = WeightEntryMode.AMOUNT,
        val amountInput: String = "",
        val quantityInput: String = "",
        val submitAttempted: Boolean = false,
        val failure: WeightSaleFailure? = null,
        /** Conserva los kilos elegidos si su importe redondeado no cambió al editar/toggle. */
        val preservedQuantity: Quantity? = null,
    ) {
        val pricePerKg: Money? get() = product.suggestedSalePrice

        private val enteredAmount: Money?
            get() = pricePerKg?.let { price ->
                runCatching { Money.fromMajor(amountInput.trim().replace(',', '.'), price.currency) }
                    .getOrNull()?.takeIf { it.minorUnits > 0L }
            }

        val quantity: Quantity?
            get() = when (mode) {
                WeightEntryMode.AMOUNT -> enteredAmount?.let { amount ->
                    pricePerKg?.let { price ->
                        preservedQuantity?.takeIf { WeightSaleCalculator.fromQuantity(it, price) == amount }
                            ?: WeightSaleCalculator.fromAmount(amount, price)
                    }
                }
                WeightEntryMode.QUANTITY ->
                    runCatching { Quantity.of(quantityInput.trim().replace(',', '.')) }.getOrNull()
            }

        val total: Money?
            get() = quantity?.let { amount ->
                pricePerKg?.let { WeightSaleCalculator.fromQuantity(amount, it) }
            }

        val exceedsStock: Boolean
            get() = quantity?.value?.let { it > product.availableQuantity } == true

        val isValid: Boolean
            get() = quantity != null && total != null && !exceedsStock &&
                failure != WeightSaleFailure.PRODUCT_UNAVAILABLE && failure != WeightSaleFailure.STALE_CART
    }

    /** Una lectura parecida sólo ofrece una elección; nunca cambia el código del producto. */
    @Immutable
    data class BarcodeSuggestion(
        val product: ProductOption,
        val missingDigits: Int,
    )

    /** Confirmación de una lectura ya guardada; nunca anticipa el resultado de una escritura. */
    @Immutable
    data class LastScanAdded(
        val productId: ProductId,
        val locationId: LocationId,
        val productName: String,
        val locationName: String,
        val quantity: BigDecimal,
        val unitCode: String,
        val sequence: Long,
        val alreadyInCart: Boolean = false,
        /** Lectura original cuando el producto se recuperó sin coincidencia exacta. */
        val recoveredFromBarcode: String? = null,
    )

    @Immutable
    data class ProductRegistrationRequest(
        val requestId: String,
        val barcode: String,
        val businessId: BusinessId,
        val saleId: SaleId,
    )

    @Immutable
    data class ProductRegistrationResult(
        val requestId: String,
        val productId: ProductId? = null,
        val businessId: BusinessId? = null,
    )

    @Immutable
    data class BarcodeReplacement(
        val product: ProductOption,
        val existingBarcode: String?,
        val newBarcode: String,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        /** Operación exclusiva: alta, retiro, asociación o registro de venta. */
        val isMutating: Boolean = false,
        val isCheckoutPending: Boolean = false,
        /** Mantiene el lector registrado mientras se guarda la lectura anterior. */
        val isProcessingBarcode: Boolean = false,
        val pendingBarcodeCount: Int = 0,
        val lastScanAdded: LastScanAdded? = null,
        val isTextInputFocused: Boolean = false,
        val scannerFailure: ScannerFailure? = null,
        /** Persistencia serializada de inputs; no bloquea seguir editando las líneas. */
        val isSavingLineEdits: Boolean = false,
        val entryKind: EntryKind = EntryKind.CASH,
        val entryStep: EntryStep = EntryStep.SELECT_KIND,
        /** Una sola entrada para nombres y códigos; conserva el motor del lector. */
        val unifiedInput: Boolean = false,
        val debtorNameInput: String = "",
        val mode: EntryMode = EntryMode.SCANNER,
        val scannerActive: Boolean = false,
        val cartId: String? = null,
        val currencyCode: String = "PEN",
        val cartVersion: Long = 0L,
        val cartContentHash: String? = null,
        val cartLines: List<CartLine> = emptyList(),
        val availableProducts: List<ProductOption> = emptyList(),
        /** Hay una edición local aún no confirmada por Room. */
        val hasPendingEdits: Boolean = false,
        val total: Money? = null,
        val query: String = "",
        val weightSaleEditor: WeightSaleEditor? = null,
        val isNameSearchRunning: Boolean = false,
        val productOptions: List<ProductOption> = emptyList(),
        val pendingAssociationBarcode: String? = null,
        val productRegistration: ProductRegistrationRequest? = null,
        val productRegisteredWithoutCartAdd: Boolean = false,
        val barcodeSuggestions: List<BarcodeSuggestion> = emptyList(),
        /** Motivo por el que la lectura necesita confirmar el producto, incluso con una sola opción vendible. */
        val barcodeSelectionReason: BarcodeSelectionReason? = null,
        /** La asociación se guardó, pero el alta posterior de la línea no se completó. */
        val barcodeAssociatedWithoutCartAdd: Boolean = false,
        val pendingLocations: List<ProductOption> = emptyList(),
        /** Lectura recuperada cuya identidad se conserva mientras se elige el almacén. */
        val pendingRecoveredBarcode: String? = null,
        val pendingReplacement: BarcodeReplacement? = null,
        /** El catálogo/stock visible puede estar desactualizado hasta una nueva emisión sana. */
        val catalogLoadFailed: Boolean = false,
        /** El carrito visible se conserva, pero no debe editarse ni confirmarse hasta reobservarlo. */
        val cartLoadFailed: Boolean = false,
        /** La consulta visible falló; no invalida ni bloquea el carrito ya guardado. */
        val searchFailed: Boolean = false,
        /** Confirmación explícita; jamás se descartan inputs inválidos solo por pulsar Back. */
        val discardEditsReview: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val isAssociating: Boolean
            get() = pendingAssociationBarcode != null

        val canRegisterProduct: Boolean
            get() = weightSaleEditor == null && entryStep == EntryStep.SELL && mode == EntryMode.SCANNER &&
                pendingAssociationBarcode != null && productRegistration == null &&
                !isLoading && !isMutating && !isSavingLineEdits && !hasPendingEdits &&
                pendingBarcodeCount == 0 && !isCheckoutPending &&
                !catalogLoadFailed && !cartLoadFailed && cartId != null &&
                pendingLocations.isEmpty() && pendingReplacement == null &&
                !discardEditsReview

        val canonicalDebtorName: String?
            get() = runCatching { normalizeDebtorName(debtorNameInput) }.getOrNull()

        val isDebtorNameValid: Boolean
            get() = entryKind == EntryKind.CASH || canonicalDebtorName != null

        val canCheckout: Boolean
            get() = weightSaleEditor == null && ((isCheckoutPending && entryStep == EntryStep.SELL && !isLoading &&
                !isMutating && !isSavingLineEdits && productRegistration == null && !cartLoadFailed && cartId != null &&
                cartContentHash != null && total != null) ||
                entryStep == EntryStep.SELL &&
                !isLoading && !isMutating && !isSavingLineEdits && !hasPendingEdits &&
                pendingBarcodeCount == 0 &&
                pendingAssociationBarcode == null && pendingLocations.isEmpty() &&
                productRegistration == null &&
                pendingReplacement == null && scannerFailure == null &&
                cartLines.isNotEmpty() &&
                cartLines.all { it.quantityValid && it.priceValid && it.lineTotal != null } &&
                total != null &&
                cartId != null && cartContentHash != null && cartVersion >= 0L && failure == null &&
                isDebtorNameValid &&
                !catalogLoadFailed && !cartLoadFailed &&
                !discardEditsReview)

        /**
         * Una lectura del escáner en curso no deshabilita las líneas del carrito: cada escaneo las
         * repintaría dos veces. Sus ediciones pasan por el mismo mutex y se aplican después.
         */
        val isBlockingMutation: Boolean
            get() = isMutating && !isProcessingBarcode

        val canRouteScannerInput: Boolean
            get() = weightSaleEditor == null && entryStep == EntryStep.SELL && mode == EntryMode.SCANNER && !isLoading &&
                productRegistration == null &&
                !isCheckoutPending &&
                (!isMutating || isProcessingBarcode) && !isTextInputFocused &&
                // Una lectura desconocida permite reescanear; los demás campos de edición lo pausan.
                !isSavingLineEdits && !hasPendingEdits &&
                cartId != null && cartContentHash != null &&
                !catalogLoadFailed && !cartLoadFailed &&
                pendingLocations.isEmpty() && pendingReplacement == null &&
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

    enum class ScannerFailure { INCOMPLETE, TOO_LONG, INVALID_CHARACTER, QUEUE_FULL }

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
        data class RegisterProductRequested(val scannedBarcode: String) : Action
        data class ProductRegistrationFinished(val result: ProductRegistrationResult) : Action
        data object Retry : Action
        data object RetryCatalog : Action
        data object RetryCart : Action
        data object RetrySearch : Action
        data class InitializeEntry(
            val kind: EntryKind,
            val allowEntryKindSelection: Boolean,
            val unifiedInput: Boolean = false,
        ) : Action
        data class EntryKindChanged(val kind: EntryKind) : Action
        data class DebtorNameChanged(val value: String) : Action
        data class ModeChanged(val mode: EntryMode) : Action
        data class ScannerAvailabilityChanged(val active: Boolean) : Action
        data class ScannerReadFailed(val failure: ScannerFailure) : Action
        /** Limpia el aviso del lector; conserva las lecturas aceptadas y el carrito. */
        data object ScannerReadReset : Action
        data class TextInputFocusChanged(val fieldId: String, val focused: Boolean) : Action
        data object ScannerSessionStopped : Action
        data class BarcodeScanned(val value: String) : Action
        data class BarcodeSuggestionSelected(
            val productId: ProductId,
            val locationId: LocationId,
            val scannedBarcode: String,
        ) : Action
        data class SearchChanged(val value: String) : Action
        data class ProductSelected(val productId: ProductId, val locationId: LocationId) : Action
        data class EditWeightSale(val lineId: String) : Action
        data class WeightEntryModeChanged(val mode: WeightEntryMode) : Action
        data class WeightAmountChanged(val value: String) : Action
        data class WeightQuantityChanged(val value: String) : Action
        data object WeightSaleConfirmed : Action
        data object WeightSaleDismissed : Action
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
        data object DiscardEditsConfirmed : Action
        data object DiscardEditsDismissed : Action
        data object OpenDebtorsSelected : Action
        data object StepBackSelected : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class RegisterProduct(val request: ProductRegistrationRequest) : Effect
        data object OpenDebtors : Effect
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
