package com.facturastock.app.feature.inventory

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object InventoryContract {
    enum class ListSection { STOCK, ESTIMATED_PROFIT }

    /** Evita que el lector HID consuma la escritura de un teclado físico en Buscar. */
    enum class InputMode { SEARCH, SCANNER }

    @Immutable
    data class SalePriceEditor(
        val productId: ProductId,
        val productName: String,
        /** Versión capturada al abrir; un conflicto nunca se reintenta con otra versión. */
        val expectedVersion: Long,
        val currency: CurrencyCode = AppConfiguration.DEFAULT_CURRENCY,
        val value: String = "",
        val submitAttempted: Boolean = false,
    ) {
        val parsedPrice: Money?
            get() {
                val canonical = value.trim().replace(',', '.')
                if (canonical.isEmpty()) return null
                return try {
                    Money.fromMajor(canonical, currency).takeIf(ProductSalePricePolicy::supports)
                } catch (_: DomainRuleViolation) {
                    null
                }
            }

        val isValid: Boolean
            get() = parsedPrice != null
    }

    @Immutable
    data class State(
        val productId: ProductId? = null,
        val isLoading: Boolean = false,
        val isDiagnosing: Boolean = false,
        val allItems: List<InventoryReadItem> = emptyList(),
        val items: List<InventoryReadItem> = emptyList(),
        val section: ListSection = ListSection.STOCK,
        val inputMode: InputMode = InputMode.SEARCH,
        val scannerActive: Boolean = false,
        val isBarcodeLookupRunning: Boolean = false,
        val scannerFailure: ScannerFailure? = null,
        val allProfits: List<ProductProfit> = emptyList(),
        val profits: List<ProductProfit> = emptyList(),
        val isProfitLoading: Boolean = false,
        val salePriceEditor: SalePriceEditor? = null,
        val isSavingSalePrice: Boolean = false,
        val profitFailure: ProfitFailure? = null,
        val detail: InventoryProductDetail? = null,
        val query: String = "",
        val diagnosticReport: InventoryDiagnosticReport? = null,
        val failure: Failure? = null,
    ) : UiState {
        val canRouteScannerInput: Boolean
            get() = productId == null && section == ListSection.STOCK &&
                inputMode == InputMode.SCANNER && !isLoading && !isDiagnosing &&
                !isBarcodeLookupRunning && salePriceEditor == null && !isSavingSalePrice &&
                (failure == null || failure == Failure.DIAGNOSTIC_FAILED)
    }

    enum class ScannerFailure {
        INVALID_BARCODE,
        BARCODE_NOT_FOUND,
        NO_ACTIVE_BUSINESS,
        LOOKUP_FAILED,
    }

    enum class Failure {
        INVALID_PRODUCT_ID,
        LOAD_FAILED,
        PRODUCT_NOT_FOUND,
        DIAGNOSTIC_FAILED,
    }

    enum class ProfitFailure {
        LOAD_FAILED,
        INVALID_PRICE,
        STALE_PRICE,
        CURRENCY_MISMATCH,
        PRODUCT_UNAVAILABLE,
        SAVE_FAILED,
    }

    sealed interface Action : UiAction {
        data object Load : Action
        data object Retry : Action
        data class SearchChanged(val query: String) : Action
        data class SectionChanged(val section: ListSection) : Action
        data class InputModeChanged(val mode: InputMode) : Action
        data class ScannerAvailabilityChanged(val active: Boolean) : Action
        data class BarcodeScanned(val value: String) : Action
        data class EditSalePrice(val productId: ProductId) : Action
        data class SalePriceChanged(val value: String) : Action
        data object SaveSalePrice : Action
        data object DismissSalePrice : Action
        data class ProductSelected(val productId: ProductId) : Action
        data class OriginPurchaseSelected(val purchaseId: PurchaseId) : Action
        data object RunDiagnostic : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenProduct(val productId: ProductId) : Effect
        data class OpenPurchase(val purchaseId: PurchaseId) : Effect
        data object Back : Effect
        data object CloseInvalidRoute : Effect
    }
}
