package com.facturastock.app.feature.headerreview

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

/** Estado de presentación de la revisión editable de cabecera. */
object InvoiceHeaderReviewContract {
    enum class FieldId {
        RUC,
        SUPPLIER,
        DOCUMENT_TYPE,
        SERIES,
        NUMBER,
        ISSUE_DATE,
        CURRENCY,
        SUBTOTAL,
        IGV,
        OTHER_CHARGES,
        TOTAL,
    }

    enum class Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN,
    }

    enum class FieldError {
        REQUIRED,
        INVALID_RUC,
        INVALID_DOCUMENT_TYPE,
        INVALID_SERIES,
        INVALID_NUMBER,
        INVALID_DATE,
        INVALID_CURRENCY,
        INVALID_AMOUNT,
        NEGATIVE_AMOUNT,
    }

    @Immutable
    data class Page(
        val imageId: ImageId,
        /** Ruta relativa a filesDir; nunca se expone como texto visible. */
        val relativePath: String,
        val pageIndex: Int,
        val rotationDegrees: Int,
    ) {
        init {
            require(relativePath.isNotBlank()) { "La pagina necesita una ruta relativa" }
            require(pageIndex >= 0) { "Indice de pagina negativo" }
            require(rotationDegrees in setOf(0, 90, 180, 270)) {
                "Rotacion de pagina no soportada"
            }
        }
    }

    @Immutable
    data class Evidence(
        val rawText: String,
        val pageIndex: Int? = null,
        val sourceImageId: ImageId? = null,
        val alternatives: List<String> = emptyList(),
    ) {
        init {
            require(rawText.isNotBlank()) { "La evidencia debe conservar texto OCR" }
            require(pageIndex == null || pageIndex >= 0) { "Indice de evidencia negativo" }
        }
    }

    @Immutable
    data class Field(
        val id: FieldId,
        val value: String = "",
        val confidence: Confidence = Confidence.UNKNOWN,
        val requiresReview: Boolean = false,
        val touched: Boolean = false,
        val error: FieldError? = null,
        val evidence: Evidence? = null,
        val isPersisting: Boolean = false,
        /** Confirmación humana explícita; nunca se infiere solo por existir un valor OCR. */
        val confirmedByUser: Boolean = false,
    ) {
        val isUncertain: Boolean
            get() = confidence == Confidence.LOW ||
                confidence == Confidence.UNKNOWN ||
                requiresReview

        val canExplicitlyConfirm: Boolean
            get() = value.isNotBlank() &&
                error == null &&
                isUncertain &&
                !confirmedByUser

        /** Solo los datos imprescindibles dudosos frenan el avance; los opcionales no. */
        val requiresExplicitConfirmation: Boolean
            get() = id in ESSENTIAL_FIELDS && canExplicitlyConfirm

        fun shouldShowError(continueAttempted: Boolean): Boolean =
            error != null && (touched || continueAttempted)
    }

    enum class Failure {
        INVALID_ROUTE,
        LOAD_FAILED,
        STORAGE_FULL,
    }

    enum class AdvanceBlockReason {
        MISSING_OR_INVALID,
        UNCONFIRMED_UNCERTAIN_VALUE,
    }

    enum class ReviewWarningCode {
        RUC_CHECKSUM_MISMATCH,
        TOTAL_DIFFERENCE,
    }

    /** Advertencias exactas e informativas: se muestran, pero nunca bloquean el avance. */
    @Immutable
    data class ReviewWarning(
        val code: ReviewWarningCode,
        val difference: String? = null,
        val currency: String? = null,
    )

    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val isLoading: Boolean = true,
        val pages: List<Page> = emptyList(),
        val fields: List<Field> = defaultFields(),
        val expandedPageIndex: Int? = null,
        val evidenceField: FieldId? = null,
        val warnings: List<ReviewWarning> = emptyList(),
        val isSaving: Boolean = false,
        val saveFailure: Boolean = false,
        val failure: Failure? = null,
        val continueAttempted: Boolean = false,
    ) : UiState {
        init {
            require(fields.map(Field::id) == FieldId.entries) {
                "Los campos deben ser unicos y mantener el orden estable de FieldId"
            }
        }

        fun field(id: FieldId): Field = fields[id.ordinal]

        /**
         * Solo bloquean los datos imprescindibles de Prompt 22 y montos opcionales que la
         * persona haya dejado con un formato inválido. La duda OCR exige confirmación únicamente
         * en los imprescindibles; los demás siguen visibles y pueden confirmarse sin bloquear.
         */
        val blockingFields: List<FieldId>
            get() = FieldId.entries.filter { id ->
                val field = field(id)
                when (id) {
                    FieldId.RUC,
                    FieldId.SERIES,
                    FieldId.NUMBER,
                    FieldId.ISSUE_DATE,
                    FieldId.CURRENCY,
                    FieldId.TOTAL,
                    -> field.value.isBlank() ||
                        field.error != null ||
                        field.requiresExplicitConfirmation

                    FieldId.SUBTOTAL,
                    FieldId.IGV,
                    FieldId.OTHER_CHARGES,
                    -> field.error != null

                    FieldId.SUPPLIER,
                    FieldId.DOCUMENT_TYPE,
                    -> false
                }
            }

        fun blockingReason(id: FieldId): AdvanceBlockReason? {
            if (id !in blockingFields) return null
            val field = field(id)
            return if (
                field.value.isNotBlank() &&
                field.error == null &&
                field.requiresExplicitConfirmation
            ) {
                AdvanceBlockReason.UNCONFIRMED_UNCERTAIN_VALUE
            } else {
                AdvanceBlockReason.MISSING_OR_INVALID
            }
        }

        val canReviewProducts: Boolean
            get() = draftId != null &&
                !isLoading &&
                !isSaving &&
                failure == null &&
                blockingFields.isEmpty()

        /** La accion queda operable con errores para poder explicar y enfocar la correccion. */
        val isReviewProductsActionEnabled: Boolean
            get() = draftId != null && !isLoading && !isSaving && failure == null

        val selectedEvidence: Evidence?
            get() = evidenceField?.let(::field)?.evidence
    }

    sealed interface Action : UiAction {
        data object Start : Action

        data class FieldChanged(
            val field: FieldId,
            val value: String,
        ) : Action

        data class FieldFocusChanged(
            val field: FieldId,
            val focused: Boolean,
        ) : Action

        data class DocumentTypeSelected(val value: PurchaseDocumentType) : Action

        data class FieldConfirmed(val field: FieldId) : Action

        data class ShowEvidence(val field: FieldId) : Action

        data object DismissEvidence : Action

        data class ShowExpandedPage(val index: Int) : Action

        data object DismissExpandedPage : Action

        data object ReviewProducts : Action

        data object RetryLoad : Action

        data object RetrySave : Action

        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenProductsReview(val draftId: DraftId) : Effect

        data class FocusField(val field: FieldId) : Effect

        data object Back : Effect

        data object CloseInvalidRoute : Effect
    }

    private fun defaultFields(): List<Field> = FieldId.entries.map(::Field)

    private val ESSENTIAL_FIELDS = setOf(
        FieldId.RUC,
        FieldId.SERIES,
        FieldId.NUMBER,
        FieldId.ISSUE_DATE,
        FieldId.CURRENCY,
        FieldId.TOTAL,
    )
}
