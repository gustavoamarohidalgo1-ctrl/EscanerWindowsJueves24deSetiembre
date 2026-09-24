package com.facturastock.app.feature.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.R
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.theme.FacturaStockDesign

/** Captura explícita para lectores que escriben mediante IME, teclas virtuales o USB sin sufijo. */
@Composable
fun ScannerCodeInput(
    enabled: Boolean,
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    physicalInput: String = "",
    onClearPhysicalInput: () -> Unit = {},
    @StringRes submitLabelRes: Int = R.string.scanner_code_submit,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    @StringRes labelRes: Int = R.string.scanner_code_label,
    @StringRes hintRes: Int = R.string.scanner_code_hint,
    @StringRes supportingTextRes: Int? = null,
    isOtherTextInputFocused: Boolean = false,
    // Inventario muestra sólo el campo: el lector físico confirma con su Enter de fin de lectura.
    showActions: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = LocalScannerInputPermission.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPermission by rememberUpdatedState(permission)
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnSearchQueryChange by rememberUpdatedState(onSearchQueryChange)
    val currentClearPhysicalInput by rememberUpdatedState(onClearPhysicalInput)
    val input = remember(context) { ScannerCodeEditText(context) }
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var hasText by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val active = enabled && resumed && permission()

    DisposableEffect(lifecycleOwner, input) {
        val observer =
            LifecycleEventObserver { _, _ ->
                resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (!resumed) input.setCaptureEnabled(false)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            input.setCaptureEnabled(false)
            input.clearFocus()
        }
    }
    LaunchedEffect(active, input, isOtherTextInputFocused) {
        if (active && !isOtherTextInputFocused) input.requestFocus()
    }

    val colors = MaterialTheme.colorScheme
    val label = stringResource(labelRes)
    val hint = stringResource(hintRes)
    val searchEnabled = searchQuery != null && onSearchQueryChange != null
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        AndroidView(
            factory = { input },
            modifier = Modifier.fillMaxWidth().testTag(ScannerCodeInputTestTags.FIELD),
            update = { view ->
                view.captureAllowed = {
                    currentEnabled && currentPermission() &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
                view.onCode = { currentOnCode(it) }
                view.onSearchQueryChange =
                    if (searchEnabled) {
                        { currentOnSearchQueryChange?.invoke(it) }
                    } else {
                        null
                    }
                view.setSearchEnabled(searchEnabled)
                view.onClearPhysicalInput = { currentClearPhysicalInput() }
                view.onContentChanged = { hasText = it.isNotEmpty() }
                view.onInvalidChanged = { invalid = it }
                view.contentDescription = label
                view.hint = hint
                view.setTextColor(colors.onSurface.toArgb())
                view.setHintTextColor(colors.onSurfaceVariant.toArgb())
                view.backgroundTintList =
                    ColorStateList.valueOf(
                        if (invalid) colors.error.toArgb() else colors.outline.toArgb(),
                    )
                view.setCaptureEnabled(active)
                view.updatePhysicalInput(physicalInput)
                view.syncSearchQuery(searchQuery)
            },
        )
        supportingTextRes?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (invalid) {
            Text(
                text = stringResource(R.string.scanner_code_invalid),
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ScannerCodeInputTestTags.ERROR),
            )
        }
        if (showActions) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm),
        ) {
            FacturaStockPrimaryButton(
                text = stringResource(submitLabelRes),
                onClick = input::submit,
                enabled = active && hasText,
                modifier = Modifier.weight(1f).testTag(ScannerCodeInputTestTags.SUBMIT),
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.scanner_code_reset),
                onClick = input::reset,
                enabled = active,
                modifier = Modifier.weight(1f).testTag(ScannerCodeInputTestTags.RESET),
            )
        }
    }
}

/**
 * EditText mantiene una InputConnection al recibir foco sin desplegar el teclado. El equivalente
 * Compose showKeyboardOnFocus=false aplaza también la conexión hasta que se toca el campo.
 * La Activity usa un tema framework; AndroidView aplica aquí los colores y el tinte de Compose,
 * sin inflado ni sustitución de widgets AppCompat (que requerirían Theme.AppCompat).
 */
@SuppressLint("AppCompatCustomView")
internal class ScannerCodeEditText(
    context: Context,
) : EditText(context) {
    var captureAllowed: () -> Boolean = { false }
    var onCode: (String) -> Unit = {}
    var onClearPhysicalInput: () -> Unit = {}
    var onContentChanged: (String) -> Unit = {}
    var onInvalidChanged: (Boolean) -> Unit = {}
    var onSearchQueryChange: ((String) -> Unit)? = null
    private var searchEnabled = false
    private var lastSearchQuery: String? = null
    private var changingProgrammatically = false
    private var lastPhysicalInput = ""
    private var ignorePhysicalClear = false
    private var connectionGeneration = 0L
    private var changingComposition = false
    private var showingPhysicalPreview = false
    private var creatingInputConnection = false

    /** Solo tocar el campo abre el teclado; un escaneo nunca lo despliega por su cuenta. */
    private var keyboardRequestedByUser = false
    private val inputMethodManager: InputMethodManager?
        get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    init {
        tag = ScannerCodeInputTestTags.FIELD
        // El código es texto literal visible; el IME no debe tratarlo como una palabra a reconvertir.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        // No setSingleLine(): su filtro puede convertir saltos en espacios antes de validarlos.
        maxLines = 1
        setHorizontallyScrolling(true)
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        showSoftInputOnFocus = false
        isFocusableInTouchMode = true
        setOnClickListener {
            if (captureAllowed()) {
                keyboardRequestedByUser = true
                inputMethodManager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        setOnEditorActionListener { _, action, event ->
            if (event == null) handleEditorAction(action) else submit()
            true
        }
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(text: Editable?) {
                    if (changingProgrammatically) return
                    val value = text?.toString().orEmpty()
                    if (!captureAllowed()) {
                        replaceContent("")
                        return
                    }
                    // El IME puede volver a notificar el mismo espejo al preparar su conexión.
                    // Solo un cambio de contenido entrega el control de esos dígitos al editor.
                    if (showingPhysicalPreview && value == lastPhysicalInput) return
                    showingPhysicalPreview = false
                    clearPhysicalFrame()
                    onContentChanged(value)
                    onInvalidChanged(false)
                    if (value.any(::isCodeTerminator)) {
                        if (!changingComposition) submit()
                    } else if (searchEnabled) {
                        onSearchQueryChange?.invoke(value)
                    }
                }
            },
        )
    }

    fun setCaptureEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        if (!enabled) {
            connectionGeneration += 1L
            lastSearchQuery = null
            replaceContent("")
            onInvalidChanged(false)
        }
    }

    /** El modo de búsqueda es opt-in; inventario conserva su acción DONE y captura original. */
    fun setSearchEnabled(enabled: Boolean) {
        if (searchEnabled == enabled) return
        searchEnabled = enabled
        lastSearchQuery = null
        imeOptions = (if (enabled) EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_DONE) or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    /** No reescribe una composición idéntica ni sustituye los dígitos de una trama HID activa. */
    fun syncSearchQuery(query: String?) {
        if (!searchEnabled || query == null || !captureAllowed() || showingPhysicalPreview) return
        // Una recomposición sin cambio externo no puede borrar una composición con sufijo ni
        // el contenido inválido que se conserva para que la persona pueda corregirlo.
        if (lastSearchQuery == query) return
        lastSearchQuery = query
        if (text?.toString() == query) return
        replaceContent(query)
        onInvalidChanged(false)
    }

    fun updatePhysicalInput(value: String) {
        if (value == lastPhysicalInput) return
        keepKeyboardClosedUnlessRequested()
        lastPhysicalInput = value
        if (value.isEmpty() && ignorePhysicalClear) {
            ignorePhysicalClear = false
            return
        }
        ignorePhysicalClear = false
        if (!captureAllowed()) return
        if (searchEnabled) lastSearchQuery = null
        replaceContent(value, fromPhysicalScanner = value.isNotEmpty())
        onInvalidChanged(false)
    }

    fun submit() {
        if (!captureAllowed()) return
        val raw = text?.toString().orEmpty()
        clearPhysicalFrame()
        val candidate = raw.dropLastWhile(::isCodeTerminator)
        // Un CR/LF adicional no confirma otra lectura ni presenta un error vacío.
        if (candidate.isEmpty() && raw.all(::isCodeTerminator)) {
            replaceContent("")
            return
        }
        val code = BarcodeValue.parse(candidate)
        if (code == null) {
            onInvalidChanged(true)
            return
        }
        replaceContent("")
        onInvalidChanged(false)
        if (searchEnabled) onSearchQueryChange?.invoke("")
        onCode(code.value)
    }

    fun reset() {
        if (!captureAllowed()) return
        clearPhysicalFrame()
        replaceContent("")
        onInvalidChanged(false)
        if (searchEnabled) onSearchQueryChange?.invoke("")
        requestFocus()
    }

    private fun handleEditorAction(action: Int) {
        if (searchEnabled && action == EditorInfo.IME_ACTION_SEARCH) {
            if (!captureAllowed() || showingPhysicalPreview) return
            val query = text?.toString().orEmpty()
            if (query.none(::isCodeTerminator)) onSearchQueryChange?.invoke(query)
        } else {
            submit()
        }
    }

    // ACTION_MULTIPLE sigue siendo necesario para lectores que envían un bloque de texto virtual.
    @Suppress("DEPRECATION")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_MULTIPLE) {
            keepKeyboardClosedUnlessRequested()
        }
        if (event.keyCode in TERMINATOR_KEYS) {
            if ((event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) ||
                event.action == KeyEvent.ACTION_MULTIPLE
            ) {
                submit()
            }
            // También consume UP y repeticiones cuando enabled cambió durante el DOWN.
            return true
        }
        if (!captureAllowed() && (event.unicodeChar != 0 || !event.characters.isNullOrEmpty())) return true
        if (event.action == KeyEvent.ACTION_MULTIPLE && !event.characters.isNullOrEmpty()) {
            val start = selectionStart.coerceAtLeast(0)
            val end = selectionEnd.coerceAtLeast(0)
            editableText.replace(minOf(start, end), maxOf(start, end), event.characters)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun focusSearch(direction: Int): View? {
        // TextView busca vecinos al preparar los flags de navegación del IME. Un restartInput
        // desde AndroidView.update (setEnabled/setText) puede reentrar en el LazyColumn mientras
        // Compose todavía aplica cambios. Este campo usa DONE/SEARCH: no necesita esa búsqueda.
        // La navegación real por foco sigue delegándose fuera de la creación de la conexión.
        return if (creatingInputConnection) null else super.focusSearch(direction)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val physicalPreview = showingPhysicalPreview
        val previousText = text.toString()
        val composingStart = BaseInputConnection.getComposingSpanStart(editableText)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(editableText)
        val wasChangingProgrammatically = changingProgrammatically
        val wasCreatingInputConnection = creatingInputConnection
        changingProgrammatically = true
        creatingInputConnection = true
        val connection =
            try {
                super.onCreateInputConnection(outAttrs)?.also { created ->
                    if (text.toString() == previousText) {
                        if (physicalPreview) {
                            // Huawei puede notificar texto y marcar el espejo como composición al abrir.
                            // Sus dígitos ya se recibieron; se conserva la selección para la edición.
                            BaseInputConnection.removeComposingSpans(editableText)
                        } else if (composingStart >= 0 && composingEnd >= composingStart &&
                            (
                                BaseInputConnection.getComposingSpanStart(editableText) != composingStart ||
                                    BaseInputConnection.getComposingSpanEnd(editableText) != composingEnd
                            )
                        ) {
                            // Una composición real anterior conserva su rango al recrear la conexión.
                            created.setComposingRegion(composingStart, composingEnd)
                        }
                    } else {
                        showingPhysicalPreview = false
                    }
                }
            } finally {
                changingProgrammatically = wasChangingProgrammatically
                creatingInputConnection = wasCreatingInputConnection
            }
        if (connection == null) return null
        if (searchEnabled) {
            // Conserva MULTI_LINE para aceptar CR/LF literales, pero permite que el teclado
            // manual muestre Buscar en vez de forzar una tecla de salto de línea.
            outAttrs.imeOptions = (
                outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION.inv() and
                    EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
            ) or EditorInfo.IME_ACTION_SEARCH
        }
        val generation = connectionGeneration
        return object : InputConnectionWrapper(connection, false) {
            private fun canWrite() = generation == connectionGeneration && captureAllowed() && hasFocus()

            override fun commitText(
                text: CharSequence?,
                newCursorPosition: Int,
            ): Boolean {
                if (!canWrite()) return true
                return super.commitText(text, newCursorPosition)
            }

            override fun commitText(
                text: CharSequence,
                newCursorPosition: Int,
                textAttribute: TextAttribute?,
            ): Boolean = commitText(text, newCursorPosition)

            override fun setComposingText(
                text: CharSequence?,
                newCursorPosition: Int,
            ): Boolean {
                if (!canWrite()) return true
                changingComposition = true
                return try {
                    super.setComposingText(text, newCursorPosition)
                } finally {
                    changingComposition = false
                }
            }

            override fun setComposingText(
                text: CharSequence,
                newCursorPosition: Int,
                textAttribute: TextAttribute?,
            ): Boolean = setComposingText(text, newCursorPosition)

            override fun setComposingRegion(
                start: Int,
                end: Int,
            ): Boolean {
                if (!canWrite()) return true
                return super.setComposingRegion(start, end)
            }

            override fun setComposingRegion(
                start: Int,
                end: Int,
                textAttribute: TextAttribute?,
            ): Boolean = setComposingRegion(start, end)

            override fun finishComposingText(): Boolean {
                if (!canWrite()) return true
                val finished = super.finishComposingText()
                // BaseInputConnection solo quita spans: no dispara otro cambio de contenido.
                // Confirma el sufijo ahora, sin enviar borradores ni rehabilitar conexiones viejas.
                if (finished && canWrite() && this@ScannerCodeEditText.text?.any(::isCodeTerminator) == true) {
                    submit()
                }
                return finished
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = if (canWrite()) dispatchKeyEvent(event) else true

            override fun performEditorAction(editorAction: Int): Boolean {
                if (canWrite()) handleEditorAction(editorAction)
                return true
            }
        }
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            connectionGeneration += 1L
            keyboardRequestedByUser = false
        }
    }

    override fun onKeyPreIme(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        // Atrás con el teclado abierto lo cierra: el siguiente escaneo no debe reabrirlo.
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) keyboardRequestedByUser = false
        return super.onKeyPreIme(keyCode, event)
    }

    /**
     * Reescribir el texto reinicia la conexión del IME y algunos teclados se despliegan al hacerlo.
     * Mientras la persona no haya tocado el campo, cada escaneo o sincronización lo mantiene cerrado.
     */
    private fun keepKeyboardClosedUnlessRequested() {
        val imeVisible = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        // Si la persona cerró el teclado con su propio botón, deja de contar como solicitado.
        if (!imeVisible) keyboardRequestedByUser = false
        if (keyboardRequestedByUser) return
        post {
            if (!keyboardRequestedByUser && hasFocus()) {
                inputMethodManager?.hideSoftInputFromWindow(windowToken, 0)
            }
        }
    }

    private fun clearPhysicalFrame() {
        if (lastPhysicalInput.isNotEmpty()) ignorePhysicalClear = true
        onClearPhysicalInput()
    }

    private fun replaceContent(
        value: String,
        fromPhysicalScanner: Boolean = false,
    ) {
        showingPhysicalPreview = fromPhysicalScanner
        changingProgrammatically = true
        try {
            setText(value)
            setSelection(value.length)
        } finally {
            changingProgrammatically = false
        }
        onContentChanged(value)
        if (!keyboardRequestedByUser && hasFocus()) keepKeyboardClosedUnlessRequested()
    }

    private companion object {
        val TERMINATOR_KEYS = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB)
    }
}

private fun isCodeTerminator(character: Char): Boolean = character == '\r' || character == '\n' || character == '\t'

object ScannerCodeInputTestTags {
    const val FIELD = "scanner_code_input"
    const val SUBMIT = "scanner_code_submit"
    const val RESET = "scanner_code_reset"
    const val ERROR = "scanner_code_error"
}
