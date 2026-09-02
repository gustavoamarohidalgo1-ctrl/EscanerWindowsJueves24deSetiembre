package com.facturastock.app.feature.linereview

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Contrato de presentacion del editor movil de lineas; no conoce Room ni realiza redondeos. */
object InvoiceLineReviewContract {
    enum class FieldId {
        DESCRIPTION,
        CODE,
        QUANTITY,
        UNIT,
        UNIT_COST,
        DISCOUNT,
        IGV,
        TOTAL,
    }

    /** Procedencia del valor efectivo mostrado. Nunca se infiere una tasa de IGV. */
    enum class ValueOrigin {
        OCR,
        CALCULATED,
        WRITTEN,
        MISSING,
    }

    enum class Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN,
    }

    enum class FieldError {
        REQUIRED,
        INVALID_DECIMAL,
        INVALID_AMOUNT,
        NEGATIVE_QUANTITY,
        NEGATIVE_AMOUNT,
        TOO_LONG,
    }

    @Immutable
    data class Field(
        val id: FieldId,
        /** Valor efectivo; puede ser texto parcial mientras se autoguarda. */
        val value: String = "",
        val origin: ValueOrigin = ValueOrigin.MISSING,
        /** Lectura original comparable. No se reemplaza al escribir. */
        val ocrValue: String? = null,
        /** Calculo exacto comparable. No se convierte automaticamente en el valor efectivo. */
        val calculatedValue: String? = null,
        val confidence: Confidence = Confidence.UNKNOWN,
        val touched: Boolean = false,
        val error: FieldError? = null,
    ) {
        val hasComparableValue: Boolean
            get() = !ocrValue.isNullOrBlank() || !calculatedValue.isNullOrBlank()
    }

    @Immutable
    data class CardProjection(
        /** Textos ya seleccionados/formateados fuera de la composicion de la LazyColumn. */
        val description: String,
        val quantity: String,
        val unit: String,
        val unitCost: String,
        val igv: String,
        val total: String,
        /** Invalida la reutilizacion de importes si cambia la moneda global del borrador. */
        val currencyLabel: String,
        /** Clave sin acentos ni diferencias de mayusculas para no normalizar durante filtros. */
        val normalizedSearchText: String,
    )

    @Immutable
    data class Line(
        val lineId: LineId,
        /** Posicion persistida y correlativa dentro de todas las lineas, no solo del filtro. */
        val position: Int,
        val fields: List<Field> = defaultFields(),
        val confidence: Confidence = Confidence.UNKNOWN,
        val confidencePercent: Int? = null,
        val requiresReview: Boolean = true,
        /** Confirmacion humana separada: resolver la duda no falsea la confianza OCR. */
        val confirmedByUser: Boolean = false,
        val taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.UNKNOWN,
        val isPersisting: Boolean = false,
        /** Presente en estados del ViewModel; null mantiene compatibles previews/fixtures. */
        val cardProjection: CardProjection? = null,
    ) {
        init {
            require(position >= 0) { "Posicion de linea negativa" }
            require(fields.map(Field::id) == FieldId.entries) {
                "Los campos de linea deben ser unicos y mantener el orden estable"
            }
            require(confidencePercent == null || confidencePercent in 0..100) {
                "Confianza porcentual fuera de rango"
            }
        }

        fun field(id: FieldId): Field = fields[id.ordinal]

        val hasErrors: Boolean
            get() = fields.any { field -> field.error != null }

        val hasRequiredValues: Boolean
            get() = field(FieldId.DESCRIPTION).value.isNotBlank() &&
                field(FieldId.QUANTITY).value.isNotBlank()

        val hasUnresolvedUncertainty: Boolean
            get() = !confirmedByUser &&
                (requiresReview ||
                    confidence == Confidence.LOW ||
                    confidence == Confidence.UNKNOWN)

        val canConfirmReviewed: Boolean
            get() = hasRequiredValues && !hasErrors && hasUnresolvedUncertainty &&
                taxTreatment != InventoryTaxTreatment.UNKNOWN

        val isPending: Boolean
            get() {
                val taxRequiresIgv =
                    taxTreatment == InventoryTaxTreatment.INCLUDED ||
                        taxTreatment == InventoryTaxTreatment.EXCLUDED
                return hasErrors || !hasRequiredValues || hasUnresolvedUncertainty ||
                    taxTreatment == InventoryTaxTreatment.UNKNOWN ||
                    (taxRequiresIgv && field(FieldId.IGV).value.isBlank()) ||
                    (
                        taxTreatment == InventoryTaxTreatment.EXEMPT &&
                            field(FieldId.IGV).value.isStrictlyPositiveDecimal()
                        )
            }

        val searchableText: String
            get() = buildString {
                append(field(FieldId.DESCRIPTION).value)
                append(' ')
                append(field(FieldId.CODE).value)
            }

        internal val normalizedSearchText: String
            get() = cardProjection?.normalizedSearchText ?: normalizeForSearch(searchableText)
    }

    @Immutable
    data class Editor(
        val line: Line,
        val isNew: Boolean = false,
        val saveFailure: Boolean = false,
    )

    @Immutable
    data class DeletedLine(
        val lineId: LineId,
        val description: String,
        val formerPosition: Int,
        val isRestoring: Boolean = false,
    ) {
        init {
            require(formerPosition >= 0) { "Posicion eliminada negativa" }
        }
    }

    enum class SummaryIssue {
        NONE,
        UNRESOLVED_LINES,
        ARITHMETIC_OVERFLOW,
        NOT_COMPARABLE,
    }

    @Immutable
    data class Summary(
        /** Formatos ya resueltos por el ViewModel desde cantidades exactas. */
        val lineSum: String = "",
        val invoiceTotal: String = "",
        val exactDifference: String = "",
        val hasDifference: Boolean = false,
        val issue: SummaryIssue = SummaryIssue.NONE,
        val unresolvedLineCount: Int = 0,
        /** Compatibilidad de estado; la UI genera TalkBack desde recursos localizables. */
        val spokenDescription: String = "",
    ) {
        init {
            require(unresolvedLineCount >= 0) { "Cantidad de lineas no resueltas negativa" }
        }

        val isComparable: Boolean
            get() = issue == SummaryIssue.NONE &&
                lineSum.isNotBlank() &&
                invoiceTotal.isNotBlank() &&
                exactDifference.isNotBlank()
    }

    enum class Failure {
        INVALID_ROUTE,
        LOAD_FAILED,
        STORAGE_FULL,
    }

    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val isLoading: Boolean = true,
        val lines: List<Line> = emptyList(),
        val searchQuery: String = "",
        val pendingOnly: Boolean = false,
        /** Codigo/simbolo solo para presentar importes; nunca forma parte del texto editable. */
        val currencyLabel: String = "",
        val editor: Editor? = null,
        val pendingDeletionLineId: LineId? = null,
        val restorableDeletion: DeletedLine? = null,
        val summary: Summary = Summary(),
        val isSaving: Boolean = false,
        val saveFailure: Boolean = false,
        val failure: Failure? = null,
        val continueAttempted: Boolean = false,
    ) : UiState {
        init {
            require(lines.map(Line::lineId).distinct().size == lines.size) {
                "Las claves de linea deben ser estables y unicas"
            }
            require(lines.map(Line::position).distinct().size == lines.size) {
                "Las posiciones de linea deben ser unicas"
            }
        }

        val orderedLines: List<Line>
            get() = lines.sortedBy(Line::position)

        val visibleLines: List<Line>
            get() = filterOrderedLines(orderedLines)

        /**
         * Filtra una proyeccion ya ordenada. La UI reutiliza el mismo orden para claves/acciones,
         * evitando ordenar las cien lineas dos veces en una misma composicion.
         */
        internal fun filterOrderedLines(ordered: List<Line>): List<Line> {
            val normalizedQuery = normalizeForSearch(searchQuery.trim())
            return ordered.filter { line ->
                (!pendingOnly || line.isPending) &&
                    (
                        normalizedQuery.isEmpty() ||
                            line.normalizedSearchText.contains(normalizedQuery)
                        )
            }
        }

        val pendingCount: Int
            get() = lines.count(Line::isPending)

        val deletionCandidate: Line?
            get() = pendingDeletionLineId?.let { id -> lines.firstOrNull { it.lineId == id } }

        val isContinueActionEnabled: Boolean
            get() = draftId != null &&
                !isLoading &&
                !isSaving &&
                !saveFailure &&
                failure == null &&
                lines.isNotEmpty()

        val canAddLine: Boolean
            get() = !isLoading && failure == null && lines.size < MAX_LINES

        val canLinkProducts: Boolean
            get() = isContinueActionEnabled && pendingCount == 0
    }

    sealed interface Action : UiAction {
        data object Start : Action

        data class SearchChanged(val query: String) : Action

        data object PendingFilterToggled : Action

        data object AddLine : Action

        data class EditLine(val lineId: LineId) : Action

        data class EditorFieldChanged(
            val field: FieldId,
            val value: String,
        ) : Action

        data class EditorFieldFocusChanged(
            val field: FieldId,
            val focused: Boolean,
        ) : Action

        data class EditorTaxTreatmentChanged(
            val treatment: InventoryTaxTreatment,
        ) : Action

        data object CloseEditor : Action

        data class ConfirmLineReviewed(val lineId: LineId) : Action

        data class RequestDelete(val lineId: LineId) : Action

        data object ConfirmDelete : Action

        data object CancelDelete : Action

        data object RestoreDeletedLine : Action

        data object DismissRestore : Action

        data class MoveLineUp(val lineId: LineId) : Action

        data class MoveLineDown(val lineId: LineId) : Action

        data object LinkProducts : Action

        data object RetryLoad : Action

        data object RetrySave : Action

        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenProductLinking(
            val draftId: DraftId,
            val firstLineId: LineId,
        ) : Effect

        data class FocusLine(val lineId: LineId) : Effect

        data object Back : Effect

        data object CloseInvalidRoute : Effect
    }

    private fun defaultFields(): List<Field> = FieldId.entries.map(::Field)

    internal fun normalizeForSearch(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

    /** La descripcion editable queda intacta; solo la copia de tarjeta/TalkBack es acotada. */
    internal fun limitCardDescription(value: String): String {
        val codePointCount = value.codePointCount(0, value.length)
        if (codePointCount <= MAX_CARD_DESCRIPTION_LENGTH) return value
        val retainedEnd = value.offsetByCodePoints(0, MAX_CARD_DESCRIPTION_LENGTH - 1)
        return value.substring(0, retainedEnd) + ELLIPSIS
    }

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private const val ELLIPSIS = "…"

    const val MAX_LINES = 100
    const val MAX_CARD_DESCRIPTION_LENGTH = 256
}

/** Decide si un salto debe acercarse instantaneamente antes de animar solo el tramo final. */
internal object InvoiceLineReviewScrollPolicy {
    fun stagingIndex(
        currentIndex: Int,
        targetIndex: Int,
        lastIndex: Int,
    ): Int? {
        if (lastIndex < 0 || currentIndex !in 0..lastIndex || targetIndex !in 0..lastIndex) {
            return null
        }
        if (abs(targetIndex - currentIndex) <= DIRECT_ANIMATION_MAX_DISTANCE) return null
        val direction = if (targetIndex > currentIndex) 1 else -1
        return (targetIndex - direction * ANIMATED_TAIL_ITEMS)
            .coerceIn(0, lastIndex)
            .takeUnless { index -> index == currentIndex || index == targetIndex }
    }

    private const val DIRECT_ANIMATION_MAX_DISTANCE = 12
    private const val ANIMATED_TAIL_ITEMS = 4
}

private fun String.isStrictlyPositiveDecimal(): Boolean {
    val normalized = trim().replace(',', '.').takeIf(String::isNotEmpty) ?: return false
    return runCatching { BigDecimal(normalized).signum() > 0 }.getOrDefault(false)
}
