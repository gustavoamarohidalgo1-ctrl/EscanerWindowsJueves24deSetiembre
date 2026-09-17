package com.facturastock.app.feature.ocr

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.InvoiceOcrStage
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState
import java.time.Instant

object OcrContract {
    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val stage: InvoiceOcrStage? = null,
        val isRunning: Boolean = false,
        /** El OCR ya terminó y solo se están guardando productos seguros del catálogo. */
        val isSavingProducts: Boolean = false,
        val isCancelling: Boolean = false,
        val isEnteringManually: Boolean = false,
        val isRecoveringInterruptedOcr: Boolean = false,
        val completedPageCount: Int = 0,
        val startedAt: Instant? = null,
        val failure: Failure? = null,
        val manualEntryFailed: Boolean = false,
    ) : UiState

    enum class Failure {
        INVALID_DRAFT_ID,
        INTERRUPTED,
        RECOGNITION_FAILED,
        PARSING_FAILED,
        NO_PRODUCTS_FOUND,
        PRODUCT_SAVE_FAILED,
    }

    sealed interface Action : UiAction {
        data object Start : Action
        data object Cancel : Action
        data object Retry : Action
        data object EnterManually : Action
        data object ContinueToMatching : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenReview(val draftId: DraftId) : Effect
        data class OpenManualReview(val draftId: DraftId) : Effect
        data class OpenMatching(val draftId: DraftId) : Effect
        data class OpenProducts(
            val createdCount: Int,
            val existingCount: Int,
            val skippedCount: Int,
        ) : Effect
        data object Cancelled : Effect
        data object Back : Effect
        data object CloseInvalidRoute : Effect
    }
}
