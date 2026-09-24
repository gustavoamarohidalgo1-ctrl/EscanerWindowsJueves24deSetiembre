package com.facturastock.app.feature.catalogs

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.repository.ProductEditingSnapshot
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductCatalogDetail
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

/** Contrato de los cuatro catálogos locales. No expone ninguna acción de borrado físico. */
object CatalogsContract {
    enum class Section {
        PRODUCTS,
        SUPPLIERS,
        UNITS,
        LOCATIONS,
    }

    val visibleSections: List<Section> = listOf(
        Section.PRODUCTS,
        Section.SUPPLIERS,
        Section.UNITS,
    )

    enum class StatusFilter(val status: CatalogStatus?) {
        ALL(null),
        ACTIVE(CatalogStatus.ACTIVE),
        ARCHIVED(CatalogStatus.ARCHIVED),
    }

    @Immutable
    sealed interface Row {
        val stableId: String
        val title: String
        val status: CatalogStatus

        data class ProductRow(val value: Product) : Row {
            override val stableId: String = value.productId.value
            override val title: String = value.name
            override val status: CatalogStatus = value.status
        }

        data class SupplierRow(val value: Supplier) : Row {
            override val stableId: String = value.supplierId.value
            override val title: String = value.legalName
            override val status: CatalogStatus = value.status
        }

        data class UnitRow(val value: UnitOfMeasure) : Row {
            override val stableId: String = value.unitId.value
            override val title: String = value.name
            override val status: CatalogStatus = value.status
        }

        data class LocationRow(val value: InventoryLocation) : Row {
            override val stableId: String = value.locationId.value
            override val title: String = value.name
            override val status: CatalogStatus = value.status
        }
    }

    @Immutable
    data class ProductPosition(
        val locationName: String,
        val quantity: String,
        val averageCost: String,
    )

    @Immutable
    sealed interface Detail {
        val row: Row

        data class ProductDetail(
            override val row: Row.ProductRow,
            val catalogDetail: ProductCatalogDetail? = null,
            val positions: List<ProductPosition> = emptyList(),
            val isLoading: Boolean = true,
            val loadFailed: Boolean = false,
        ) : Detail

        data class SupplierDetail(override val row: Row.SupplierRow) : Detail

        data class UnitDetail(override val row: Row.UnitRow) : Detail

        data class LocationDetail(override val row: Row.LocationRow) : Detail
    }

    @Immutable
    data class UnitOption(
        val unitId: UnitId,
        val code: String,
        val name: String,
        val status: CatalogStatus,
    )

    @Immutable
    data class LocationOption(
        val locationId: LocationId,
        val label: String,
        val status: CatalogStatus,
    )

    @Immutable
    data class InventoryBalanceOption(
        val locationId: LocationId,
        val locationName: String,
        val quantity: String,
        val unitCost: String,
        val currency: CurrencyCode?,
    )

    @Immutable
    data class InventoryBalanceDraft(
        val locationId: LocationId,
        val quantity: String,
        val purchasePrice: String,
    )

    @Immutable
    sealed interface Form {
        val isEditing: Boolean
        val title: String

        data class ProductForm(
            val productId: ProductId? = null,
            override val title: String = "",
            val sku: String = "",
            val barcode: String = "",
            val unitId: UnitId? = null,
            val locationId: LocationId? = null,
            val purchaseUnitId: UnitId? = null,
            val purchaseFactor: String = "",
            val salePrice: String = "",
            val quantity: String = "",
            val purchasePrice: String = "",
            val isScannedRegistration: Boolean = false,
            val isSpecialRegistration: Boolean = false,
            val isManualRegistration: Boolean = false,
            val registrationProductId: ProductId? = null,
            val isScannerOrigin: Boolean = isScannedRegistration,
            val isInventoryOrigin: Boolean = false,
            val isSkuInputTooLong: Boolean = false,
            val isBarcodeInputTooLong: Boolean = false,
            val saleCurrency: CurrencyCode? = null,
            val inventoryCurrency: CurrencyCode? = null,
            val inventorySnapshot: ProductEditingSnapshot? = null,
            val inventoryBalanceDrafts: List<InventoryBalanceDraft> = emptyList(),
            val original: Product? = null,
        ) : Form {
            override val isEditing: Boolean = productId != null
        }

        data class SupplierForm(
            val supplierId: SupplierId? = null,
            override val title: String = "",
            val tradeName: String = "",
            val ruc: String = "",
            val original: Supplier? = null,
            val checksumAccepted: Boolean = false,
        ) : Form {
            override val isEditing: Boolean = supplierId != null
        }

        data class UnitForm(
            val unitId: UnitId? = null,
            override val title: String = "",
            val code: String = "",
            val symbol: String = "",
            val original: UnitOfMeasure? = null,
        ) : Form {
            override val isEditing: Boolean = unitId != null
        }

        data class LocationForm(
            val locationId: LocationId? = null,
            override val title: String = "",
            val original: InventoryLocation? = null,
        ) : Form {
            override val isEditing: Boolean = locationId != null
        }
    }

    enum class Failure {
        NO_ACTIVE_BUSINESS,
        LOAD_FAILED,
        SAVE_FAILED,
        NOT_FOUND,
        STALE,
        INVALID_FIELDS,
        INVALID_RUC,
        DUPLICATE_RUC,
        DUPLICATE_SKU,
        DUPLICATE_BARCODE,
        DUPLICATE_CODE,
        DUPLICATE_NAME,
    }

    @Immutable
    data class PendingStatusChange(
        val row: Row,
        val target: CatalogStatus,
    )

    @Immutable
    data class State(
        val currency: CurrencyCode = CurrencyCode.of("PEN"),
        val section: Section = Section.PRODUCTS,
        val query: String = "",
        val statusFilter: StatusFilter = if (section == Section.PRODUCTS) StatusFilter.ACTIVE else StatusFilter.ALL,
        val rows: List<Row> = emptyList(),
        val total: Int = 0,
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val isSaving: Boolean = false,
        val failure: Failure? = null,
        val savedFeedback: Boolean = false,
        val detail: Detail? = null,
        val form: Form? = null,
        val inventoryBalances: List<InventoryBalanceOption> = emptyList(),
        val isInventoryBalanceLoading: Boolean = false,
        val inventoryBalanceFailure: Failure? = null,
        val isInventoryStockEditable: Boolean = true,
        val isInventoryEntryPending: Boolean = false,
        val inventoryEntryFailure: Failure? = null,
        val isSpecialEntryPending: Boolean = false,
        val specialEntryFailure: Failure? = null,
        val isManualEntryPending: Boolean = false,
        val manualEntryFailure: Failure? = null,
        val isScannerEntryPending: Boolean = false,
        val scannerEntryFailure: Failure? = null,
        val unitOptions: List<UnitOption> = emptyList(),
        val locationOptions: List<LocationOption> = emptyList(),
        val pendingStatusChange: PendingStatusChange? = null,
        val showRucChecksumWarning: Boolean = false,
    ) : UiState

    sealed interface Action : UiAction {
        data class SectionSelected(val section: Section) : Action
        data class QueryChanged(val value: String) : Action
        data class StatusFilterSelected(val filter: StatusFilter) : Action
        data object LoadMore : Action
        data object Retry : Action
        data object AddSelected : Action
        data class RowSelected(val row: Row) : Action
        data object CloseDetail : Action
        data object EditSelected : Action
        data object CloseForm : Action
        data object SaveForm : Action
        data class ProductNameChanged(val value: String) : Action
        data class ProductSkuChanged(val value: String) : Action
        data class ProductBarcodeChanged(val value: String) : Action
        data class ProductPriceChanged(val value: String) : Action
        data class ProductPurchasePriceChanged(val value: String) : Action
        data class ProductQuantityChanged(val value: String) : Action
        data class ProductUnitSelected(val unitId: UnitId) : Action
        data class ProductLocationSelected(val locationId: LocationId?) : Action
        data class ProductPurchaseUnitSelected(val unitId: UnitId?) : Action
        data class ProductPurchaseFactorChanged(val value: String) : Action
        data class SupplierLegalNameChanged(val value: String) : Action
        data class SupplierTradeNameChanged(val value: String) : Action
        data class SupplierRucChanged(val value: String) : Action
        data object AcceptRucChecksumAndSave : Action
        data object DismissRucChecksumWarning : Action
        data class UnitNameChanged(val value: String) : Action
        data class UnitCodeChanged(val value: String) : Action
        data class UnitSymbolChanged(val value: String) : Action
        data class LocationNameChanged(val value: String) : Action
        data object RequestStatusChange : Action
        data object ConfirmStatusChange : Action
        data object DismissStatusChange : Action
    }

    sealed interface Effect : UiEffect {
        data object Back : Effect

        data class ProductSaved(
            val requestId: String,
            val productId: ProductId,
            val businessId: BusinessId,
        ) : Effect
    }
}
