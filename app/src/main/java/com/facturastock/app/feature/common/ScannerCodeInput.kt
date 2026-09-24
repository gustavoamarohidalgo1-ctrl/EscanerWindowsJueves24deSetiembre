package com.facturastock.app.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.resources.*
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.theme.FacturaStockDesign
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Campo de captura para el lector USB (que en Windows escribe como un teclado) y para escribir a
 * mano. Conserva el contrato de la versión Android: el lector confirma con su Enter/Tab de fin de
 * lectura; escribir a mano en modo búsqueda filtra productos y el botón confirma el código.
 *
 * En la tablet el lector se distinguía por ser un dispositivo físico frente al teclado en
 * pantalla. En un PC ambos son teclados, así que se distingue por el ritmo: un lector entrega la
 * lectura completa en una ráfaga de pocos milisegundos entre caracteres.
 */
@Composable
fun ScannerCodeInput(
    enabled: Boolean,
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    physicalInput: String = "",
    onClearPhysicalInput: () -> Unit = {},
    submitLabelRes: StringResource = Res.string.scanner_code_submit,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    labelRes: StringResource = Res.string.scanner_code_label,
    hintRes: StringResource = Res.string.scanner_code_hint,
    supportingTextRes: StringResource? = null,
    isOtherTextInputFocused: Boolean = false,
    // Inventario muestra sólo el campo: el lector físico confirma con su Enter de fin de lectura.
    showActions: Boolean = true,
    // Sin botón «Reiniciar lector» (Inventario): tras esta pausa se descarta una lectura del
    // lector que llegó sin su Enter/Tab, para que no se mezcle con la siguiente.
    unconfirmedScanResetMillis: Long? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = LocalScannerInputPermission.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPermission by rememberUpdatedState(permission)
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnSearchQueryChange by rememberUpdatedState(onSearchQueryChange)
    val currentClearPhysicalInput by rememberUpdatedState(onClearPhysicalInput)
    val focusRequester = remember { FocusRequester() }
    // STARTED y no RESUMED: cambiar a otra ventana de Windows no debe borrar lo escrito.
    var started by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val active = enabled && started && permission()
    val searchEnabled = searchQuery != null && onSearchQueryChange != null
    val field = remember { ScannerCodeFieldState() }
    field.captureAllowed = {
        currentEnabled && currentPermission() &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
    field.onCode = { currentOnCode(it) }
    field.onSearchQueryChange = if (searchEnabled) {
        { currentOnSearchQueryChange?.invoke(it) }
    } else {
        null
    }
    field.onClearPhysicalInput = { currentClearPhysicalInput() }
    field.setSearchEnabled(searchEnabled)
    field.setCaptureEnabled(active)
    field.updatePhysicalInput(physicalInput)
    field.syncSearchQuery(searchQuery)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!started) field.setCaptureEnabled(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            field.setCaptureEnabled(false)
        }
    }
    if (unconfirmedScanResetMillis != null) {
        val text = field.value.text
        LaunchedEffect(text) {
            if (!field.holdsUnconfirmedScan()) return@LaunchedEffect
            delay(unconfirmedScanResetMillis)
            if (field.value.text == text && field.holdsUnconfirmedScan()) field.reset()
        }
    }
    LaunchedEffect(active, isOtherTextInputFocused) {
        if (active && !isOtherTextInputFocused) runCatching { focusRequester.requestFocus() }
    }

    val colors = MaterialTheme.colorScheme
    val label = stringResource(labelRes)
    val hint = stringResource(hintRes)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = field.value,
            onValueChange = field::onValueChange,
            enabled = active,
            singleLine = true,
            isError = field.invalid,
            placeholder = { Text(hint) },
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = if (searchEnabled) ImeAction.Search else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { field.submit() },
                onSearch = { field.handleSearchAction() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { field.focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    // La ráfaga se mide con la hora de pulsación de AWT, no con la hora en que el
                    // hilo de UI procesa la tecla: si una recomposición retrasa la cola de eventos,
                    // una lectura del lector no debe parecer tecleo humano.
                    field.onKeyEventTime(event.awtEventOrNull?.`when`)
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent event.key == Key.Enter || event.key == Key.NumPadEnter
                    }
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            field.onTerminatorKey()
                            true
                        }
                        // Tab tras una ráfaga del lector confirma la lectura; si lo pulsa una
                        // persona sigue moviendo el foco normalmente.
                        Key.Tab -> field.onTabKey()
                        else -> false
                    }
                }
                .semantics { contentDescription = label }
                .testTag(ScannerCodeInputTestTags.FIELD),
        )
        supportingTextRes?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (field.invalid) {
            Text(
                text = stringResource(Res.string.scanner_code_invalid),
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
                onClick = {
                    field.submit()
                    // En escritorio el clic enfoca el botón; el foco vuelve al campo para la
                    // siguiente lectura o búsqueda, como en la tablet (y como hace Reiniciar).
                    runCatching { focusRequester.requestFocus() }
                },
                enabled = active && field.value.text.isNotEmpty(),
                modifier = Modifier.weight(1f).testTag(ScannerCodeInputTestTags.SUBMIT),
            )
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.scanner_code_reset),
                onClick = {
                    field.reset()
                    runCatching { focusRequester.requestFocus() }
                },
                enabled = active,
                modifier = Modifier.weight(1f).testTag(ScannerCodeInputTestTags.RESET),
            )
        }
    }
}

/**
 * Estado y reglas del campo, trasladadas del `EditText` Android: vista previa de la trama HID,
 * sincronización de la búsqueda, validación con [BarcodeValue] y limpieza al desactivarse.
 */
internal class ScannerCodeFieldState(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    var captureAllowed: () -> Boolean = { false }
    var onCode: (String) -> Unit = {}
    var onClearPhysicalInput: () -> Unit = {}
    var onSearchQueryChange: ((String) -> Unit)? = null
    var focused: Boolean = false

    var value by mutableStateOf(TextFieldValue(""))
        private set
    var invalid by mutableStateOf(false)
        private set

    private var searchEnabled = false
    private var captureEnabled = false
    private var keyEventTimeMillis: Long? = null
    private var lastSearchQuery: String? = null
    private var lastPhysicalInput = ""
    private var ignorePhysicalClear = false
    private var showingPhysicalPreview = false
    private val burst = ScannerBurstDetector()

    fun setCaptureEnabled(enabled: Boolean) {
        if (captureEnabled == enabled) return
        captureEnabled = enabled
        if (!enabled) {
            lastSearchQuery = null
            replaceContent("")
            invalid = false
        }
    }

    /** El modo de búsqueda es opt-in; inventario conserva su acción DONE y captura original. */
    fun setSearchEnabled(enabled: Boolean) {
        if (searchEnabled == enabled) return
        searchEnabled = enabled
        lastSearchQuery = null
    }

    /** No reescribe una búsqueda idéntica ni sustituye los dígitos de una trama HID activa. */
    fun syncSearchQuery(query: String?) {
        if (!searchEnabled || query == null || !captureAllowed() || showingPhysicalPreview) return
        if (lastSearchQuery == query) return
        lastSearchQuery = query
        if (value.text == query) return
        replaceContent(query)
        invalid = false
    }

    fun updatePhysicalInput(input: String) {
        if (input == lastPhysicalInput) return
        lastPhysicalInput = input
        if (input.isEmpty() && ignorePhysicalClear) {
            ignorePhysicalClear = false
            return
        }
        ignorePhysicalClear = false
        if (!captureAllowed()) return
        if (searchEnabled) lastSearchQuery = null
        replaceContent(input, fromPhysicalScanner = input.isNotEmpty())
        invalid = false
    }

    /** Hora de la tecla que el campo está procesando (null si el cambio no viene del teclado). */
    fun onKeyEventTime(timeMillis: Long?) {
        keyEventTimeMillis = timeMillis
    }

    /** Consume la hora de la tecla en curso; sin tecla (pegar, semántica) usa el reloj. */
    private fun eventTimeMillis(): Long = (keyEventTimeMillis ?: clockMillis()).also { keyEventTimeMillis = null }

    fun onValueChange(next: TextFieldValue) {
        val changeTime = eventTimeMillis()
        if (next.text == value.text) {
            value = next
            return
        }
        if (!captureAllowed()) {
            replaceContent("")
            return
        }
        burst.onTextChanged(previous = value.text, next = next.text, nowMillis = changeTime)
        value = next
        showingPhysicalPreview = false
        clearPhysicalFrame()
        invalid = false
        if (next.text.any(::isCodeTerminator)) {
            submit()
        } else if (searchEnabled) {
            onSearchQueryChange?.invoke(next.text)
        }
    }

    /** Enter/Enter numérico: igual que la tecla física en Android, confirma la lectura. */
    fun onTerminatorKey() {
        if (searchEnabled && !burst.looksLikeScanner(value.text, eventTimeMillis())) {
            handleSearchAction()
        } else {
            submit()
        }
    }

    /** El texto es entero una ráfaga del lector que todavía no se confirmó con su sufijo. */
    fun holdsUnconfirmedScan(): Boolean = value.text.isNotEmpty() && burst.isUnbrokenBurst(value.text)

    fun onTabKey(): Boolean {
        if (value.text.isEmpty() || !burst.looksLikeScanner(value.text, eventTimeMillis())) return false
        submit()
        return true
    }

    fun handleSearchAction() {
        if (!searchEnabled) {
            submit()
            return
        }
        if (!captureAllowed() || showingPhysicalPreview) return
        val query = value.text
        if (query.none(::isCodeTerminator)) onSearchQueryChange?.invoke(query)
    }

    fun submit() {
        if (!captureAllowed()) return
        val raw = value.text
        clearPhysicalFrame()
        val candidate = raw.dropLastWhile(::isCodeTerminator)
        // Un CR/LF adicional no confirma otra lectura ni presenta un error vacío.
        if (candidate.isEmpty() && raw.all(::isCodeTerminator)) {
            replaceContent("")
            return
        }
        val code = BarcodeValue.parse(candidate)
        if (code == null) {
            invalid = true
            return
        }
        replaceContent("")
        invalid = false
        if (searchEnabled) onSearchQueryChange?.invoke("")
        onCode(code.value)
    }

    fun reset() {
        if (!captureAllowed()) return
        clearPhysicalFrame()
        replaceContent("")
        invalid = false
        if (searchEnabled) onSearchQueryChange?.invoke("")
    }

    private fun clearPhysicalFrame() {
        if (lastPhysicalInput.isNotEmpty()) ignorePhysicalClear = true
        onClearPhysicalInput()
    }

    private fun replaceContent(text: String, fromPhysicalScanner: Boolean = false) {
        showingPhysicalPreview = fromPhysicalScanner
        burst.reset()
        value = TextFieldValue(text, selection = TextRange(text.length))
    }
}

/**
 * Distingue una lectura del lector (ráfaga) de lo que teclea una persona. Solo mira intervalos
 * entre caracteres agregados al final; pegar o borrar deja de considerarse ráfaga.
 */
internal class ScannerBurstDetector(
    private val maxGapMillis: Long = SCANNER_MAX_INTER_KEY_GAP_MILLIS,
    private val maxTerminatorDelayMillis: Long = SCANNER_MAX_TERMINATOR_DELAY_MILLIS,
) {
    private var lastChangeMillis: Long? = null
    private var burstLength = 0
    private var broken = false

    fun onTextChanged(previous: String, next: String, nowMillis: Long) {
        val appendedOne = next.length == previous.length + 1 && next.startsWith(previous)
        if (!appendedOne || previous.isEmpty()) {
            broken = !appendedOne && next.isNotEmpty()
            burstLength = if (appendedOne) 1 else 0
            lastChangeMillis = nowMillis
            return
        }
        val gap = nowMillis - (lastChangeMillis ?: nowMillis)
        if (gap > maxGapMillis) broken = true
        burstLength += 1
        lastChangeMillis = nowMillis
    }

    fun isUnbrokenBurst(text: String): Boolean =
        lastChangeMillis != null && !broken && burstLength >= MIN_SCANNER_LENGTH && burstLength == text.length

    fun looksLikeScanner(text: String, nowMillis: Long): Boolean {
        val last = lastChangeMillis ?: return false
        return !broken && burstLength >= MIN_SCANNER_LENGTH && burstLength == text.length &&
            nowMillis - last <= maxTerminatorDelayMillis
    }

    fun reset() {
        lastChangeMillis = null
        burstLength = 0
        broken = false
    }

    companion object {
        const val SCANNER_MAX_INTER_KEY_GAP_MILLIS = 50L
        const val SCANNER_MAX_TERMINATOR_DELAY_MILLIS = 100L
        const val MIN_SCANNER_LENGTH = 3
    }
}

private fun isCodeTerminator(character: Char): Boolean = character == '\r' || character == '\n' || character == '\t'

object ScannerCodeInputTestTags {
    const val FIELD = "scanner_code_input"
    const val SUBMIT = "scanner_code_submit"
    const val RESET = "scanner_code_reset"
    const val ERROR = "scanner_code_error"
}
