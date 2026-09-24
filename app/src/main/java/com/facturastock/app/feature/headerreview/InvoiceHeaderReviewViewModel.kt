package com.facturastock.app.feature.headerreview

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldKind
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldTrace
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import com.facturastock.app.domain.usecase.InvoiceHeaderReviewSnapshot
import com.facturastock.app.domain.usecase.InvoiceHeaderReviewValidator
import com.facturastock.app.domain.usecase.InvoiceHeaderValidationErrorCode
import com.facturastock.app.domain.usecase.InvoiceHeaderValidation
import com.facturastock.app.domain.usecase.InvoiceHeaderValidationWarningCode
import com.facturastock.app.domain.usecase.LoadInvoiceHeaderReviewUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceHeaderEditUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Action
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Confidence
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Effect
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Evidence
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Field
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldError
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldId
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Page
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.ReviewWarning
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.ReviewWarningCode
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.State
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
class InvoiceHeaderReviewViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val loadInvoiceHeaderReviewUseCase: LoadInvoiceHeaderReviewUseCase,
    private val saveInvoiceHeaderEditUseCase: SaveInvoiceHeaderEditUseCase,
    private val validator: InvoiceHeaderReviewValidator,
    private val appClock: AppClock,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<State, Action, Effect>(
    initialState = initialHeaderReviewState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private data class SaveRequest(
        val edit: InvoiceHeaderEdit,
        val advanceToken: Long? = null,
        val backAfterSave: Boolean = false,
    )

    // Solo la última fotografía completa del formulario importa; el escritor activo termina su
    // commit y luego salta directamente a la revisión más nueva, sin acumular una tecla por fila.
    private val saveRequests = Channel<SaveRequest>(capacity = Channel.CONFLATED)
    private var currentEdit: InvoiceHeaderEdit? = null
    private var persistedEditRevision: Long? = null
    private var loadJob: Job? = null
    private var advanceToken: Long = 0L
    private var advanceInFlight = false
    private var backInFlight = false
    private val focusedFields = mutableSetOf<FieldId>()
    /** Delta local respecto de la última fotografía Room; permite rebasar sin pisar otros campos. */
    private val locallyChangedFields = mutableSetOf<InvoiceHeaderEditField>()
    private var retryRequiresRebase = false

    init {
        startWriter()
        if (uiState.value.draftId != null) load()
    }

    override fun onAction(action: Action) {
        when (action) {
            Action.Start -> {
                if (uiState.value.draftId == null) {
                    executeMain { emitEffect(Effect.CloseInvalidRoute) }
                }
            }
            is Action.FieldChanged -> changeField(action.field, action.value)
            is Action.FieldFocusChanged -> onFocusChanged(action.field, action.focused)
            is Action.DocumentTypeSelected -> selectDocumentType(action.value)
            is Action.FieldConfirmed -> confirmField(action.field)
            is Action.ShowEvidence -> executeMain {
                if (uiState.value.field(action.field).evidence != null) {
                    updateState { copy(evidenceField = action.field) }
                }
            }
            Action.DismissEvidence -> executeMain { updateState { copy(evidenceField = null) } }
            is Action.ShowExpandedPage -> executeMain {
                if (action.index in uiState.value.pages.indices) {
                    updateState { copy(expandedPageIndex = action.index) }
                }
            }
            Action.DismissExpandedPage -> executeMain {
                updateState { copy(expandedPageIndex = null) }
            }
            Action.ReviewProducts -> reviewProducts()
            Action.RetryLoad -> load()
            Action.RetrySave -> retrySave()
            Action.BackSelected -> saveBeforeBack()
        }
    }

    private fun load() {
        val draftId = uiState.value.draftId ?: return
        loadJob?.cancel()
        loadJob = executeMain {
            updateState { copy(isLoading = currentEdit == null, failure = null) }
            try {
                loadInvoiceHeaderReviewUseCase.observe(draftId).collect { snapshot ->
                    if (snapshot == null) {
                        val mustReport = uiState.value.failure !=
                            InvoiceHeaderReviewContract.Failure.INVALID_ROUTE
                        updateState {
                            copy(
                                isLoading = false,
                                failure = InvoiceHeaderReviewContract.Failure.INVALID_ROUTE,
                            )
                        }
                        if (mustReport) emitEffect(Effect.CloseInvalidRoute)
                    } else {
                        acceptObservedSnapshot(snapshot)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState {
                    copy(
                        isLoading = false,
                        failure = InvoiceHeaderReviewContract.Failure.LOAD_FAILED,
                    )
                }
            }
        }
    }

    /**
     * Room puede reemitir mientras este editor conserva un cambio optimista. Una fotografía
     * externa solo reemplaza la UI cuando no existe delta local; en caso contrario se conserva
     * lo escrito y el retry usa el rebase CAS ya existente.
     */
    private fun acceptObservedSnapshot(snapshot: InvoiceHeaderReviewSnapshot) {
        val local = currentEdit
        if (local == null) {
            initializeFromSnapshot(snapshot)
            return
        }
        persistedEditRevision = snapshot.persistedEditRevision
        val persisted = snapshot.edit
        when {
            persisted.revision < local.revision -> updateObservedPages(snapshot)
            persisted.revision == local.revision && persisted != local -> {
                retryRequiresRebase = true
                updateObservedPages(snapshot)
            }
            persisted.revision > local.revision && locallyChangedFields.isNotEmpty() -> {
                retryRequiresRebase = true
                updateObservedPages(snapshot)
            }
            persisted.revision > local.revision -> {
                retryRequiresRebase = false
                locallyChangedFields.clear()
                showSnapshot(snapshot)
                persistSavedEdit(persisted)
            }
            else -> updateObservedPages(snapshot)
        }
    }

    private fun initializeFromSnapshot(snapshot: InvoiceHeaderReviewSnapshot) {
        persistedEditRevision = snapshot.persistedEditRevision
        val restored = restoreSavedEdit(snapshot.edit.draftId)
        val savedDelta = restoreSavedLocalChanges()
        val restoredDifferences = restored
            ?.changedFieldsComparedWith(snapshot.edit)
            .orEmpty()
        val inferredDelta = if (restored != null && restored.revision > snapshot.edit.revision) {
            restoredDifferences
        } else {
            emptySet()
        }
        locallyChangedFields.clear()
        locallyChangedFields += if (savedDelta.isNotEmpty()) {
            savedDelta intersect restoredDifferences
        } else {
            inferredDelta
        }
        val selected = if (restored != null && locallyChangedFields.isNotEmpty()) {
            snapshot.edit
                .mergeFieldsFrom(restored, locallyChangedFields)
                .copy(
                    revision = if (restored.revision > snapshot.edit.revision) {
                        restored.revision
                    } else {
                        Math.incrementExact(snapshot.edit.revision)
                    },
                    updatedAt = maxOf(restored.updatedAt, snapshot.edit.updatedAt),
                )
        } else {
            locallyChangedFields.clear()
            snapshot.edit
        }
        showSnapshot(snapshot.copy(edit = selected))
        persistSavedEdit(selected)
        if (locallyChangedFields.isNotEmpty()) saveRequests.trySend(SaveRequest(selected))
    }

    private fun updateObservedPages(snapshot: InvoiceHeaderReviewSnapshot) {
        updateState {
            copy(
                isLoading = false,
                pages = snapshot.pages.map { image ->
                    Page(
                        imageId = image.imageId,
                        relativePath = image.filePath,
                        pageIndex = image.pageIndex,
                        rotationDegrees = image.rotationDegrees,
                    )
                },
                failure = null,
            )
        }
    }

    private fun showSnapshot(snapshot: InvoiceHeaderReviewSnapshot) {
        currentEdit = snapshot.edit
        val validation = validator.validate(snapshot.edit)
        val traces = snapshot.audit
        updateState {
            copy(
                isLoading = false,
                pages = snapshot.pages.map { image ->
                    Page(
                        imageId = image.imageId,
                        relativePath = image.filePath,
                        pageIndex = image.pageIndex,
                        rotationDegrees = image.rotationDegrees,
                    )
                },
                fields = FieldId.entries.map { id ->
                    id.toField(
                        edit = snapshot.edit,
                        audit = traces,
                        error = validation.errorFor(id.toEditField())?.code.toUiError(),
                    )
                },
                warnings = validation.toUiWarnings(),
                saveFailure = false,
                isSaving = false,
                failure = null,
                continueAttempted = false,
            )
        }
    }

    private fun changeField(id: FieldId, rawValue: String) {
        val current = currentEdit ?: return
        val value = sanitize(id, rawValue)
        if (id.valueFrom(current).orEmpty() == value) return
        cancelAdvance()
        backInFlight = false
        val next = current.withField(
            id = id,
            value = value,
            revision = current.revision + 1L,
            touchedFields = current.touchedFields + id.toEditField(),
            updatedAt = maxOf(appClock.now(), current.updatedAt),
        )
        locallyChangedFields += id.toEditField()
        currentEdit = next
        persistSavedEdit(next)
        renderEdit(next, changedField = id, persisting = true)
        saveRequests.trySend(SaveRequest(next))
    }

    private fun selectDocumentType(value: PurchaseDocumentType) {
        changeField(FieldId.DOCUMENT_TYPE, value.name)
    }

    private fun confirmField(id: FieldId) {
        val current = currentEdit ?: return
        if (!uiState.value.field(id).canExplicitlyConfirm) return
        val domainField = id.toEditField()
        if (domainField in current.touchedFields) return
        cancelAdvance()
        backInFlight = false
        val next = current.withField(
            id = id,
            value = id.valueFrom(current),
            revision = current.revision + 1L,
            touchedFields = current.touchedFields + domainField,
            updatedAt = maxOf(appClock.now(), current.updatedAt),
        )
        locallyChangedFields += domainField
        currentEdit = next
        persistSavedEdit(next)
        renderEdit(next, changedField = id, persisting = true)
        saveRequests.trySend(SaveRequest(next))
    }

    private fun onFocusChanged(id: FieldId, focused: Boolean) {
        if (focused) {
            focusedFields += id
            return
        }
        // Compose notifica un estado inicial no enfocado al adjuntar cada nodo. Solo un campo
        // que estuvo realmente enfocado cuenta como interacción y revela su validación.
        if (!focusedFields.remove(id)) return
        executeMain {
            updateState {
                copy(fields = fields.replace(id) { field -> field.copy(touched = true) })
            }
        }
    }

    private fun renderEdit(
        edit: InvoiceHeaderEdit,
        changedField: FieldId,
        persisting: Boolean,
    ) {
        val validation = validator.validate(edit)
        executeMain {
            updateState {
                copy(
                    fields = fields.map { field ->
                        val id = field.id
                        field.copy(
                            value = id.valueFrom(edit).orEmpty(),
                            touched = field.touched || id == changedField,
                            error = validation.errorFor(id.toEditField())?.code.toUiError(),
                            isPersisting = if (id == changedField) persisting else field.isPersisting,
                            confirmedByUser = id.toEditField() in edit.touchedFields,
                        )
                    },
                    warnings = validation.toUiWarnings(),
                    saveFailure = false,
                    isSaving = false,
                )
            }
        }
    }

    private fun retrySave() {
        val edit = currentEdit ?: return
        if (retryRequiresRebase) {
            rebaseAndRetry(edit)
            return
        }
        executeMain {
            updateState {
                copy(
                    saveFailure = false,
                    fields = fields.map { field -> field.copy(isPersisting = true) },
                )
            }
        }
        saveRequests.trySend(SaveRequest(edit))
    }

    private fun rebaseAndRetry(local: InvoiceHeaderEdit) {
        val draftId = local.draftId
        executeIo(
            before = {
                updateState {
                    copy(
                        saveFailure = false,
                        fields = fields.map { field -> field.copy(isPersisting = true) },
                    )
                }
            },
            operation = { loadInvoiceHeaderReviewUseCase(draftId) },
            onSuccess = { snapshot ->
                if (snapshot == null) {
                    emitEffect(Effect.CloseInvalidRoute)
                    return@executeIo
                }
                val rebased = snapshot.edit
                    .mergeFieldsFrom(local, locallyChangedFields)
                    .copy(
                        revision = Math.incrementExact(
                            maxOf(local.revision, snapshot.edit.revision),
                        ),
                        touchedFields = local.touchedFields + snapshot.edit.touchedFields,
                        updatedAt = maxOf(appClock.now(), local.updatedAt, snapshot.edit.updatedAt),
                    )
                retryRequiresRebase = false
                persistedEditRevision = snapshot.persistedEditRevision
                currentEdit = rebased
                persistSavedEdit(rebased)
                val validation = validator.validate(rebased)
                updateState {
                    copy(
                        fields = fields.map { field ->
                            field.copy(
                                value = field.id.valueFrom(rebased).orEmpty(),
                                error = validation.errorFor(field.id.toEditField())?.code.toUiError(),
                                isPersisting = true,
                                confirmedByUser = field.id.toEditField() in rebased.touchedFields,
                            )
                        },
                        warnings = validation.toUiWarnings(),
                    )
                }
                saveRequests.trySend(SaveRequest(rebased))
            },
            onFailure = { error ->
                updateState {
                    copy(
                        saveFailure = true,
                        fields = fields.map { field -> field.copy(isPersisting = false) },
                        failure = if (error.isInsufficientSpace()) {
                            InvoiceHeaderReviewContract.Failure.STORAGE_FULL
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }

    private fun reviewProducts() {
        if (advanceInFlight) return
        val edit = currentEdit ?: return
        val validation = validator.validate(edit)
        executeMain {
            updateState {
                copy(
                    continueAttempted = true,
                    fields = fields.map { field ->
                        field.copy(error = validation.errorFor(field.id.toEditField())?.code.toUiError())
                    },
                )
            }
            val firstBlocking = uiState.value.blockingFields.firstOrNull()
            if (firstBlocking != null) {
                emitEffect(Effect.FocusField(firstBlocking))
                return@executeMain
            }
            advanceInFlight = true
            val token = ++advanceToken
            updateState { copy(isSaving = true, saveFailure = false) }
            saveRequests.send(
                SaveRequest(
                    edit = edit,
                    advanceToken = token,
                ),
            )
        }
    }

    private fun saveBeforeBack() {
        if (backInFlight) return
        val edit = currentEdit
        if (edit == null) {
            executeMain { emitEffect(Effect.Back) }
            return
        }
        cancelAdvance()
        backInFlight = true
        executeMain { updateState { copy(isSaving = true, saveFailure = false) } }
        saveRequests.trySend(
            SaveRequest(
                edit = edit,
                backAfterSave = true,
            ),
        )
    }

    private fun cancelAdvance() {
        if (!advanceInFlight) return
        advanceInFlight = false
        advanceToken += 1L
    }

    private fun startWriter() {
        executeMain {
            for (request in saveRequests) {
                // Una escritura anterior de este mismo actor pudo completar mientras esta
                // fotografía estaba conflada en el canal. Usa la base durable más reciente al
                // comenzar el commit; Room aún valida el CAS frente a cualquier editor externo.
                val expectedRevision = persistedEditRevision
                var storageFull = false
                val result = try {
                    withContext(dispatcherProvider.io) {
                        saveInvoiceHeaderEditUseCase(
                            edit = request.edit,
                            expectedRevision = expectedRevision,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    storageFull = error.isInsufficientSpace()
                    null
                }
                handleSaveResult(request, result, storageFull)
            }
        }
    }

    private suspend fun handleSaveResult(
        request: SaveRequest,
        result: SaveInvoiceHeaderEditResult?,
        storageFull: Boolean,
    ) {
        val success = result == SaveInvoiceHeaderEditResult.SAVED ||
            result == SaveInvoiceHeaderEditResult.ALREADY_SAVED
        val isCurrent = currentEdit?.revision == request.edit.revision
        if (success) {
            persistedEditRevision = request.edit.revision
        }
        if (success && isCurrent) {
            retryRequiresRebase = false
            locallyChangedFields.clear()
            persistSavedEdit(request.edit)
            updateState {
                copy(
                    saveFailure = false,
                    failure = null,
                    fields = fields.map { field -> field.copy(isPersisting = false) },
                )
            }
        } else if (!success && isCurrent) {
            retryRequiresRebase = result == SaveInvoiceHeaderEditResult.STALE_REVISION ||
                result == SaveInvoiceHeaderEditResult.CONFLICT
            updateState {
                copy(
                    isSaving = false,
                    saveFailure = true,
                    failure = if (storageFull) {
                        InvoiceHeaderReviewContract.Failure.STORAGE_FULL
                    } else {
                        failure?.takeUnless {
                            it == InvoiceHeaderReviewContract.Failure.STORAGE_FULL
                        }
                    },
                    fields = fields.map { field -> field.copy(isPersisting = false) },
                )
            }
        }

        if (request.backAfterSave) {
            if (backInFlight) {
                backInFlight = false
                updateState { copy(isSaving = false) }
                if (success && isCurrent) {
                    emitEffect(Effect.Back)
                } else if (result == SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE) {
                    emitEffect(Effect.CloseInvalidRoute)
                }
            }
            return
        }

        val token = request.advanceToken ?: return
        if (!advanceInFlight || token != advanceToken || !isCurrent) return
        advanceInFlight = false
        updateState { copy(isSaving = false) }
        if (success) {
            emitEffect(Effect.OpenProductsReview(request.edit.draftId))
        } else if (result == SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE) {
            emitEffect(Effect.CloseInvalidRoute)
        }

    }

    /** SavedState cubre una recreación ocurrida mientras el último commit Room aún está en vuelo. */
    private fun persistSavedEdit(edit: InvoiceHeaderEdit) {
        savedStateHandle[SAVED_REVISION] = edit.revision
        savedStateHandle[SAVED_UPDATED_AT] = edit.updatedAt.toEpochMilli()
        savedStateHandle[SAVED_TOUCHED] = ArrayList(edit.touchedFields.map(InvoiceHeaderEditField::name))
        savedStateHandle[SAVED_LOCAL_CHANGES] =
            ArrayList(locallyChangedFields.map(InvoiceHeaderEditField::name))
        FieldId.entries.forEach { id ->
            savedStateHandle[savedValueKey(id)] = id.valueFrom(edit)
        }
    }

    private fun restoreSavedEdit(draftId: DraftId): InvoiceHeaderEdit? {
        val revision = savedStateHandle.get<Long>(SAVED_REVISION) ?: return null
        val updatedAt = savedStateHandle.get<Long>(SAVED_UPDATED_AT)?.let(java.time.Instant::ofEpochMilli)
            ?: return null
        val touched = savedStateHandle.get<ArrayList<String>>(SAVED_TOUCHED)
            .orEmpty()
            .mapNotNull { name -> InvoiceHeaderEditField.entries.firstOrNull { it.name == name } }
            .toSet()
        return InvoiceHeaderEdit(
            draftId = draftId,
            supplierRuc = savedStateHandle[savedValueKey(FieldId.RUC)],
            supplierLegalName = savedStateHandle[savedValueKey(FieldId.SUPPLIER)],
            documentType = savedStateHandle[savedValueKey(FieldId.DOCUMENT_TYPE)],
            documentSeries = savedStateHandle[savedValueKey(FieldId.SERIES)],
            documentNumber = savedStateHandle[savedValueKey(FieldId.NUMBER)],
            issueDate = savedStateHandle[savedValueKey(FieldId.ISSUE_DATE)],
            currency = savedStateHandle[savedValueKey(FieldId.CURRENCY)],
            subtotal = savedStateHandle[savedValueKey(FieldId.SUBTOTAL)],
            igv = savedStateHandle[savedValueKey(FieldId.IGV)],
            otherCharges = savedStateHandle[savedValueKey(FieldId.OTHER_CHARGES)],
            total = savedStateHandle[savedValueKey(FieldId.TOTAL)],
            revision = revision,
            touchedFields = touched,
            updatedAt = updatedAt,
        )
    }

    private fun restoreSavedLocalChanges(): Set<InvoiceHeaderEditField> =
        savedStateHandle.get<ArrayList<String>>(SAVED_LOCAL_CHANGES)
            .orEmpty()
            .mapNotNull { name -> InvoiceHeaderEditField.entries.firstOrNull { it.name == name } }
            .toSet()
}

private const val SAVED_REVISION = "header_review.saved_revision"
private const val SAVED_UPDATED_AT = "header_review.saved_updated_at"
private const val SAVED_TOUCHED = "header_review.saved_touched"
private const val SAVED_LOCAL_CHANGES = "header_review.saved_local_changes"

private fun savedValueKey(id: FieldId): String = "header_review.saved_value.${id.name}"

private fun initialHeaderReviewState(savedStateHandle: SavedStateHandle): State {
    val draftId = DraftId.parse(savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID))
    return State(
        draftId = draftId,
        isLoading = draftId != null,
        failure = if (draftId == null) {
            InvoiceHeaderReviewContract.Failure.INVALID_ROUTE
        } else {
            null
        },
    )
}

private fun FieldId.toField(
    edit: InvoiceHeaderEdit,
    audit: ParsedInvoiceAudit?,
    error: FieldError?,
): Field {
    val traces = audit?.fields.orEmpty().filter { trace -> trace.kind in traceKinds() }
    val confidence = traces
        .map(ParsedInvoiceFieldTrace::confidence)
        .minByOrNull(ParsedInvoiceConfidence::reliabilityRank)
        .toUiConfidence()
    return Field(
        id = this,
        value = valueFrom(edit).orEmpty(),
        confidence = confidence,
        requiresReview = traces.any(ParsedInvoiceFieldTrace::requiresReview),
        touched = toEditField() in edit.touchedFields,
        error = error,
        evidence = traces.toEvidence(),
        confirmedByUser = toEditField() in edit.touchedFields,
    )
}

private fun List<ParsedInvoiceFieldTrace>.toEvidence(): Evidence? {
    val selected = mapNotNull { trace ->
        trace.selectedCandidateIndex?.let(trace.candidates::getOrNull)
    }
    val evidenceCandidates = selected.ifEmpty { flatMap(ParsedInvoiceFieldTrace::candidates) }
    if (evidenceCandidates.isEmpty()) return null
    val evidenceItems = evidenceCandidates.flatMap { candidate -> candidate.evidence }
    val raw = evidenceCandidates.mapNotNull { candidate -> candidate.rawText }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(separator = "\n")
        .ifBlank {
            evidenceItems.map { it.rawText }.filter(String::isNotBlank).distinct().joinToString("\n")
        }
    if (raw.isBlank()) return null
    val representedRawValues = evidenceCandidates
        .mapNotNull { candidate -> candidate.rawText ?: candidate.canonicalValue }
        .toSet()
    val alternatives = flatMap { trace ->
        trace.candidates.mapIndexedNotNull { index, candidate ->
            if (index == trace.selectedCandidateIndex) {
                null
            } else {
                candidate.rawText ?: candidate.canonicalValue
            }
        }
    }.filter { it.isNotBlank() && it !in representedRawValues && it != raw }.distinct()
    val pageIndexes = evidenceItems.mapNotNull { item -> item.pageIndex }.distinct()
    val imageIds = evidenceItems.mapNotNull { item -> item.sourceImageId }.distinct()
    return Evidence(
        rawText = raw,
        pageIndex = pageIndexes.singleOrNull(),
        sourceImageId = imageIds.singleOrNull(),
        alternatives = alternatives,
    )
}

private fun FieldId.traceKinds(): Set<ParsedInvoiceFieldKind> = when (this) {
    FieldId.RUC -> setOf(ParsedInvoiceFieldKind.ISSUER_RUC)
    FieldId.SUPPLIER -> setOf(ParsedInvoiceFieldKind.ISSUER_LEGAL_NAME)
    FieldId.DOCUMENT_TYPE -> setOf(ParsedInvoiceFieldKind.DOCUMENT_TYPE)
    FieldId.SERIES,
    FieldId.NUMBER,
    -> setOf(ParsedInvoiceFieldKind.DOCUMENT_NUMBER)
    FieldId.ISSUE_DATE -> setOf(ParsedInvoiceFieldKind.ISSUE_DATE)
    FieldId.CURRENCY -> setOf(ParsedInvoiceFieldKind.CURRENCY)
    FieldId.SUBTOTAL -> setOf(ParsedInvoiceFieldKind.SUBTOTAL)
    FieldId.IGV -> setOf(ParsedInvoiceFieldKind.IGV)
    FieldId.OTHER_CHARGES -> setOf(ParsedInvoiceFieldKind.OTHER_CHARGE)
    FieldId.TOTAL -> setOf(ParsedInvoiceFieldKind.DOCUMENT_TOTAL)
}

private fun FieldId.toEditField(): InvoiceHeaderEditField = when (this) {
    FieldId.RUC -> InvoiceHeaderEditField.SUPPLIER_RUC
    FieldId.SUPPLIER -> InvoiceHeaderEditField.SUPPLIER_LEGAL_NAME
    FieldId.DOCUMENT_TYPE -> InvoiceHeaderEditField.DOCUMENT_TYPE
    FieldId.SERIES -> InvoiceHeaderEditField.DOCUMENT_SERIES
    FieldId.NUMBER -> InvoiceHeaderEditField.DOCUMENT_NUMBER
    FieldId.ISSUE_DATE -> InvoiceHeaderEditField.ISSUE_DATE
    FieldId.CURRENCY -> InvoiceHeaderEditField.CURRENCY
    FieldId.SUBTOTAL -> InvoiceHeaderEditField.SUBTOTAL
    FieldId.IGV -> InvoiceHeaderEditField.IGV
    FieldId.OTHER_CHARGES -> InvoiceHeaderEditField.OTHER_CHARGES
    FieldId.TOTAL -> InvoiceHeaderEditField.TOTAL
}

private fun FieldId.valueFrom(edit: InvoiceHeaderEdit): String? = when (this) {
    FieldId.RUC -> edit.supplierRuc
    FieldId.SUPPLIER -> edit.supplierLegalName
    FieldId.DOCUMENT_TYPE -> edit.documentType
    FieldId.SERIES -> edit.documentSeries
    FieldId.NUMBER -> edit.documentNumber
    FieldId.ISSUE_DATE -> edit.issueDate
    FieldId.CURRENCY -> edit.currency
    FieldId.SUBTOTAL -> edit.subtotal
    FieldId.IGV -> edit.igv
    FieldId.OTHER_CHARGES -> edit.otherCharges
    FieldId.TOTAL -> edit.total
}

private fun InvoiceHeaderEdit.withField(
    id: FieldId,
    value: String?,
    revision: Long,
    touchedFields: Set<InvoiceHeaderEditField>,
    updatedAt: java.time.Instant,
): InvoiceHeaderEdit = when (id) {
    FieldId.RUC -> copy(supplierRuc = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.SUPPLIER -> copy(supplierLegalName = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.DOCUMENT_TYPE -> copy(documentType = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.SERIES -> copy(documentSeries = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.NUMBER -> copy(documentNumber = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.ISSUE_DATE -> copy(issueDate = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.CURRENCY -> copy(currency = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.SUBTOTAL -> copy(subtotal = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.IGV -> copy(igv = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.OTHER_CHARGES -> copy(otherCharges = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
    FieldId.TOTAL -> copy(total = value, revision = revision, touchedFields = touchedFields, updatedAt = updatedAt)
}

/**
 * Aplica únicamente el delta de este editor sobre la última fila Room. Los campos que otra
 * instancia cambió mientras tanto permanecen intactos.
 */
private fun InvoiceHeaderEdit.mergeFieldsFrom(
    local: InvoiceHeaderEdit,
    changedFields: Set<InvoiceHeaderEditField>,
): InvoiceHeaderEdit = copy(
    supplierRuc = local.supplierRuc.takeWhenChanged(
        InvoiceHeaderEditField.SUPPLIER_RUC,
        changedFields,
        supplierRuc,
    ),
    supplierLegalName = local.supplierLegalName.takeWhenChanged(
        InvoiceHeaderEditField.SUPPLIER_LEGAL_NAME,
        changedFields,
        supplierLegalName,
    ),
    documentType = local.documentType.takeWhenChanged(
        InvoiceHeaderEditField.DOCUMENT_TYPE,
        changedFields,
        documentType,
    ),
    documentSeries = local.documentSeries.takeWhenChanged(
        InvoiceHeaderEditField.DOCUMENT_SERIES,
        changedFields,
        documentSeries,
    ),
    documentNumber = local.documentNumber.takeWhenChanged(
        InvoiceHeaderEditField.DOCUMENT_NUMBER,
        changedFields,
        documentNumber,
    ),
    issueDate = local.issueDate.takeWhenChanged(
        InvoiceHeaderEditField.ISSUE_DATE,
        changedFields,
        issueDate,
    ),
    currency = local.currency.takeWhenChanged(
        InvoiceHeaderEditField.CURRENCY,
        changedFields,
        currency,
    ),
    subtotal = local.subtotal.takeWhenChanged(
        InvoiceHeaderEditField.SUBTOTAL,
        changedFields,
        subtotal,
    ),
    igv = local.igv.takeWhenChanged(InvoiceHeaderEditField.IGV, changedFields, igv),
    otherCharges = local.otherCharges.takeWhenChanged(
        InvoiceHeaderEditField.OTHER_CHARGES,
        changedFields,
        otherCharges,
    ),
    total = local.total.takeWhenChanged(InvoiceHeaderEditField.TOTAL, changedFields, total),
    touchedFields = touchedFields + local.touchedFields.filter { it in changedFields },
)

private fun InvoiceHeaderEdit.changedFieldsComparedWith(
    other: InvoiceHeaderEdit,
): Set<InvoiceHeaderEditField> = buildSet {
    fun compare(field: InvoiceHeaderEditField, local: String?, persisted: String?) {
        if (local != persisted || (field in touchedFields) != (field in other.touchedFields)) {
            add(field)
        }
    }
    compare(InvoiceHeaderEditField.SUPPLIER_RUC, supplierRuc, other.supplierRuc)
    compare(
        InvoiceHeaderEditField.SUPPLIER_LEGAL_NAME,
        supplierLegalName,
        other.supplierLegalName,
    )
    compare(InvoiceHeaderEditField.DOCUMENT_TYPE, documentType, other.documentType)
    compare(InvoiceHeaderEditField.DOCUMENT_SERIES, documentSeries, other.documentSeries)
    compare(InvoiceHeaderEditField.DOCUMENT_NUMBER, documentNumber, other.documentNumber)
    compare(InvoiceHeaderEditField.ISSUE_DATE, issueDate, other.issueDate)
    compare(InvoiceHeaderEditField.CURRENCY, currency, other.currency)
    compare(InvoiceHeaderEditField.SUBTOTAL, subtotal, other.subtotal)
    compare(InvoiceHeaderEditField.IGV, igv, other.igv)
    compare(InvoiceHeaderEditField.OTHER_CHARGES, otherCharges, other.otherCharges)
    compare(InvoiceHeaderEditField.TOTAL, total, other.total)
}

private fun <T> T.takeWhenChanged(
    field: InvoiceHeaderEditField,
    changedFields: Set<InvoiceHeaderEditField>,
    persisted: T,
): T = if (field in changedFields) this else persisted

private fun Throwable.isInsufficientSpace(): Boolean =
    this is StorageException && error == StorageError.InsufficientSpace

private fun InvoiceHeaderValidation.toUiWarnings(): List<ReviewWarning> = warnings.map { warning ->
    when (warning.code) {
        InvoiceHeaderValidationWarningCode.RUC_CHECKSUM_MISMATCH -> ReviewWarning(
            code = ReviewWarningCode.RUC_CHECKSUM_MISMATCH,
        )
        InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE -> ReviewWarning(
            code = ReviewWarningCode.TOTAL_DIFFERENCE,
            difference = warning.difference?.toMajor()?.toPlainString(),
            currency = warning.difference?.currency?.value,
        )
    }
}

private fun InvoiceHeaderValidationErrorCode?.toUiError(): FieldError? = when (this) {
    null -> null
    InvoiceHeaderValidationErrorCode.REQUIRED -> FieldError.REQUIRED
    InvoiceHeaderValidationErrorCode.INVALID_RUC -> FieldError.INVALID_RUC
    InvoiceHeaderValidationErrorCode.INVALID_DOCUMENT_TYPE -> FieldError.INVALID_DOCUMENT_TYPE
    InvoiceHeaderValidationErrorCode.INVALID_SERIES -> FieldError.INVALID_SERIES
    InvoiceHeaderValidationErrorCode.INVALID_NUMBER -> FieldError.INVALID_NUMBER
    InvoiceHeaderValidationErrorCode.INVALID_DATE -> FieldError.INVALID_DATE
    InvoiceHeaderValidationErrorCode.INVALID_CURRENCY -> FieldError.INVALID_CURRENCY
    InvoiceHeaderValidationErrorCode.INVALID_AMOUNT -> FieldError.INVALID_AMOUNT
}

private fun ParsedInvoiceConfidence?.toUiConfidence(): Confidence = when (this) {
    ParsedInvoiceConfidence.HIGH -> Confidence.HIGH
    ParsedInvoiceConfidence.MEDIUM -> Confidence.MEDIUM
    ParsedInvoiceConfidence.LOW -> Confidence.LOW
    ParsedInvoiceConfidence.UNKNOWN,
    null,
    -> Confidence.UNKNOWN
}

private fun List<Field>.replace(id: FieldId, transform: (Field) -> Field): List<Field> =
    map { field -> if (field.id == id) transform(field) else field }

private fun sanitize(id: FieldId, raw: String): String = when (id) {
    FieldId.RUC -> raw.filter { it in '0'..'9' }.take(11)
    FieldId.SUPPLIER -> raw.take(512)
    FieldId.DOCUMENT_TYPE -> raw.take(64)
    FieldId.SERIES -> raw.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).take(4)
    FieldId.NUMBER -> raw.filter { it in '0'..'9' }.take(12)
    FieldId.ISSUE_DATE -> formatDateInput(raw)
    FieldId.CURRENCY -> raw.filter(Char::isLetter).uppercase(Locale.ROOT).take(3)
    FieldId.SUBTOTAL,
    FieldId.IGV,
    FieldId.OTHER_CHARGES,
    FieldId.TOTAL,
    -> raw.filterIndexed { index, character ->
        character.isDigit() || character == '.' || character == ',' ||
            ((character == '-' || character == '+') && index == 0)
    }.take(64)
}

private fun formatDateInput(raw: String): String {
    val digits = raw.filter { it in '0'..'9' }.take(8)
    return buildString {
        append(digits.take(2))
        if (digits.length > 2 || (digits.length == 2 && raw.endsWith('/'))) append('/')
        if (digits.length > 2) append(digits.substring(2, minOf(4, digits.length)))
        if (digits.length > 4 || (digits.length == 4 && raw.endsWith('/'))) append('/')
        if (digits.length > 4) append(digits.substring(4))
    }
}
