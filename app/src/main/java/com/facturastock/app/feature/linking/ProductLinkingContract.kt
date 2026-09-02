package com.facturastock.app.feature.linking

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.ProductMatchCandidate
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.math.BigDecimal

/**
 * Contrato de presentación de la vinculación línea↔producto. La pantalla nunca decide
 * coincidencias: la cascada exacta vive en el dominio y toda persistencia de alias exige una
 * confirmación humana previa.
 */
object ProductLinkingContract {
    /** Estado de resolución de una línea; AUTO_LINKED y CONFIRMED ya están persistidas. */
    enum class LinkStatus {
        AUTO_LINKED,
        CONFIRMED,
        NEEDS_CHOICE,
        NO_MATCH,
        SKIPPED,
    }

    @Immutable
    data class LineLinking(
        val lineId: LineId,
        /** Posición correlativa dentro de las líneas activas del borrador. */
        val position: Int,
        val description: String,
        val code: String?,
        val status: LinkStatus,
        /** Nombre del producto enlazado cuando se conoce sin costo extra; puede ser null. */
        val linkedProductName: String?,
        val linkReason: ProductMatchReason?,
        /** Un enlace legacy no puede omitirse hasta fijar un precio o reemplazar el enlace. */
        val requiresSalePrice: Boolean = false,
    )

    /**
     * Formulario de creación sin abandonar la revisión. La unidad de compra y su factor se
     * definen juntos o no se definen; [purchaseFactor] es texto parcial hasta que valida.
     */
    @Immutable
    data class CreateForm(
        val name: String = "",
        val unitId: UnitId? = null,
        val sku: String = "",
        val barcode: String = "",
        /** Precio por una unidad de inventario; obligatorio para todo producto nuevo. */
        val salePrice: String = "",
        val salePriceCurrency: CurrencyCode = AppConfiguration.DEFAULT_CURRENCY,
        val purchaseUnitId: UnitId? = null,
        val purchaseFactor: String = "",
        /** Producto existente que chocó con el último envío; ofrece vincularlo. */
        val duplicate: Product? = null,
        /** Tras un envío inválido el diálogo muestra los errores de validación. */
        val submitAttempted: Boolean = false,
    ) {
        /** Factor parseado con coma o punto decimal; null cuando el texto no es un decimal. */
        val parsedPurchaseFactor: BigDecimal?
            get() = purchaseFactor
                .trim()
                .replace(',', '.')
                .takeIf { it.isNotEmpty() }
                ?.let { text -> runCatching { BigDecimal(text) }.getOrNull() }

        val isFactorValid: Boolean
            get() = if (purchaseUnitId == null) {
                purchaseFactor.isBlank()
            } else {
                parsedPurchaseFactor?.let { factor ->
                    factor.signum() > 0 && ExactDecimalPolicy.supportsValue(factor)
                } == true
            }

        val isBarcodeValid: Boolean
            get() = BarcodeValue.isValidOptional(barcode)

        val parsedSalePrice: Money?
            get() = salePrice.toPositiveMoneyOrNull(salePriceCurrency)

        val isSalePriceValid: Boolean
            get() = parsedSalePrice != null

        val isValid: Boolean
            get() = name.isNotBlank() && unitId != null && isFactorValid && isBarcodeValid &&
                isSalePriceValid

        /**
         * Equivalencia exacta de una unidad de compra en unidades de inventario (la misma
         * operación que `Product.inventoryUnitsFor` con cantidad 1); null si no es válida.
         */
        val purchaseEquivalence: String?
            get() {
                val factor = parsedPurchaseFactor ?: return null
                if (purchaseUnitId == null || !isFactorValid) return null
                return runCatching { Quantity.of("1").times(factor).toString() }.getOrNull()
            }
    }

    /** Confirmación explícita para completar un producto legacy sin precio antes de vincularlo. */
    @Immutable
    data class SalePriceForm(
        val product: Product,
        val lineId: LineId,
        val confidencePermille: Int,
        val reason: ProductMatchReason?,
        val currency: CurrencyCode = AppConfiguration.DEFAULT_CURRENCY,
        val value: String = "",
        val submitAttempted: Boolean = false,
    ) {
        val productId: ProductId
            get() = product.productId

        val productName: String
            get() = product.name

        val expectedVersion: Long
            get() = product.version

        val parsedPrice: Money?
            get() = value.toPositiveMoneyOrNull(currency)

        val isValid: Boolean
            get() = parsedPrice != null
    }

    enum class Failure {
        INVALID_ROUTE,
        DRAFT_NOT_EDITABLE,
        SAVE_CONFLICT,
        SAVE_FAILED,
    }

    /** La validez de [State.candidates] siempre corresponde a la consulta visible. */
    enum class SearchStatus {
        IDLE,
        SEARCHING,
        FAILED,
    }

    enum class SalePriceFailure {
        INVALID_PRICE,
        STALE,
        CURRENCY_MISMATCH,
        PRODUCT_UNAVAILABLE,
        CONFIGURATION_CHANGED,
        SAVE_FAILED,
    }

    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val initialLineId: LineId? = null,
        val isLoading: Boolean = true,
        val lines: List<LineLinking> = emptyList(),
        val currentLineId: LineId? = null,
        /** Candidatos de la línea actual (cascada o búsqueda manual); nunca más de 5. */
        val candidates: List<ProductMatchCandidate> = emptyList(),
        val searchQuery: String = "",
        val searchStatus: SearchStatus = SearchStatus.IDLE,
        val units: List<UnitOfMeasure> = emptyList(),
        val currency: CurrencyCode = AppConfiguration.DEFAULT_CURRENCY,
        val createForm: CreateForm? = null,
        val salePriceForm: SalePriceForm? = null,
        val salePriceFailure: SalePriceFailure? = null,
        val supplierId: SupplierId? = null,
        val isBusy: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        init {
            require(lines.map(LineLinking::lineId).distinct().size == lines.size) {
                "Las claves de línea deben ser estables y únicas"
            }
            require(lines.map(LineLinking::position).distinct().size == lines.size) {
                "Las posiciones de línea deben ser únicas"
            }
            require(candidates.size <= ProductMatchingUseCase.MAX_CANDIDATES) {
                "La pantalla nunca muestra más de ${ProductMatchingUseCase.MAX_CANDIDATES} candidatos"
            }
            require(candidates.map { it.product.productId }.distinct().size == candidates.size) {
                "Un producto no puede proponerse dos veces en la misma línea"
            }
            require(searchStatus == SearchStatus.IDLE || candidates.isEmpty()) {
                "Una búsqueda pendiente o fallida no puede exponer candidatos anteriores"
            }
        }

        val currentLine: LineLinking?
            get() = lines.firstOrNull { it.lineId == currentLineId }

        /** Índice 0-based de la línea actual dentro de [lines]; -1 si no hay selección. */
        val currentLineIndex: Int
            get() = lines.indexOfFirst { it.lineId == currentLineId }

        val resolvedCount: Int
            get() = lines.count { line -> line.status in RESOLVED_STATUSES }

        val allResolved: Boolean
            get() = lines.isNotEmpty() && lines.all { line ->
                line.status in RESOLVED_STATUSES && !line.requiresSalePrice
            }

        val canSelectCandidate: Boolean
            get() = !isLoading && !isBusy && searchStatus == SearchStatus.IDLE

        val canContinue: Boolean
            get() = draftId != null && !isLoading && !isBusy && createForm == null &&
                salePriceForm == null && allResolved
    }

    sealed interface Action : UiAction {
        data object Start : Action

        data class LineSelected(val lineId: LineId) : Action

        data class SearchChanged(val query: String) : Action

        data object RetrySearch : Action

        /** Confirmación humana de un candidato: persiste el enlace y el alias del proveedor. */
        data class CandidateConfirmed(val productId: ProductId) : Action

        /** Reabre una línea ya vinculada para elegir otro producto. */
        data class ChangeLink(val lineId: LineId) : Action

        data object SkipLine : Action

        data object OpenCreateForm : Action

        data class CreateFormChanged(val form: CreateForm) : Action

        data object CreateSubmitted : Action

        data object CreateDuplicateLinkExisting : Action

        data object CreateFormDismissed : Action

        data class SalePriceChanged(val value: String) : Action

        data object SalePriceSubmitted : Action

        data object SalePriceDismissed : Action

        data object ContinueSelected : Action

        data object BackSelected : Action

        data object Retry : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenSummary(val draftId: DraftId) : Effect

        data object Back : Effect

        data object CloseInvalidRoute : Effect
    }

    private val RESOLVED_STATUSES = setOf(
        LinkStatus.AUTO_LINKED,
        LinkStatus.CONFIRMED,
        LinkStatus.SKIPPED,
    )
}

private fun String.toPositiveMoneyOrNull(currency: CurrencyCode): Money? {
    val canonical = trim().replace(',', '.')
    if (canonical.isEmpty()) return null
    return try {
        Money.fromMajor(canonical, currency).takeIf(ProductSalePricePolicy::supports)
    } catch (_: DomainRuleViolation) {
        null
    }
}
