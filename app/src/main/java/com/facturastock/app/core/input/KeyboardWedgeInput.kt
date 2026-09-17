package com.facturastock.app.core.input

/** Acción mínima, independiente de Android, necesaria para ensamblar una lectura HID. */
enum class KeyboardWedgeKeyAction {
    DOWN,
    UP,
}

/** Teclas que cierran una lectura de un escáner configurado como keyboard wedge. */
enum class KeyboardWedgeTerminator {
    ENTER,
    NUMPAD_ENTER,
    TAB,
}

/** Evento neutral de plataforma para que el ensamblador pueda probarse en la JVM. */
data class KeyboardWedgeKeyEvent(
    val action: KeyboardWedgeKeyAction,
    val eventTimeMillis: Long,
    val deviceId: Int,
    val keyCode: Int,
    val downTimeMillis: Long = eventTimeMillis,
    val repeatCount: Int = 0,
    val printableCharacter: Char? = null,
    val terminator: KeyboardWedgeTerminator? = null,
    val sourceSequence: Int = 0,
    /** ACTION_MULTIPLE representa pulsaciones completas, no un DOWN pendiente de su UP. */
    val isFromCharacterBatch: Boolean = false,
)

enum class KeyboardWedgeReadError { INCOMPLETE, TOO_LONG, INVALID_CHARACTER }

sealed interface KeyboardWedgeAssemblyResult {
    /** El evento no pertenece a una lectura y puede seguir propagándose. */
    data object NotHandled : KeyboardWedgeAssemblyResult

    /** El evento pertenece a una lectura, pero todavía no la completa. */
    data object Consumed : KeyboardWedgeAssemblyResult

    /** Lectura válida, ya separada del estado interno del ensamblador. */
    data class Completed(val value: String) : KeyboardWedgeAssemblyResult

    /** La trama completa se rechaza: nunca entregar un prefijo o una cola como otro producto. */
    data class Rejected(val reason: KeyboardWedgeReadError) : KeyboardWedgeAssemblyResult
}

/**
 * Ensambla una ráfaga de teclado físico sin depender de Android ni conservar lecturas completas.
 *
 * Una secuencia que excede [maxLength] se descarta hasta su terminador; nunca se entrega truncada.
 */
class KeyboardWedgeAssembler(
    private val maxLength: Int = MAX_INPUT_LENGTH,
    private val timeoutMillis: Long = DEFAULT_INTER_KEY_TIMEOUT_MILLIS,
) {
    private val buffer = StringBuilder()
    private var activeDeviceId: Int? = null
    private var lastEventTimeMillis: Long? = null
    private var lastAcceptedDown: EventFingerprint? = null
    private val consumedDownKeys = mutableSetOf<KeyIdentity>()
    private var rejected: KeyboardWedgeReadError? = null
    private var lastTerminatorTimeMillis: Long? = null
    private var lastTerminatorDeviceId: Int? = null

    /** Vista de la lectura todavía sin confirmar, para lectores configurados sin sufijo. */
    val pendingInput: String
        get() = buffer.toString()

    val pendingError: KeyboardWedgeReadError?
        get() = rejected

    init {
        require(maxLength in 1..MAX_INPUT_LENGTH)
        require(timeoutMillis > 0L)
    }

    fun accept(event: KeyboardWedgeKeyEvent): KeyboardWedgeAssemblyResult {
        val keyIdentity = KeyIdentity(
            downTimeMillis = event.downTimeMillis,
            deviceId = event.deviceId,
            keyCode = event.keyCode,
        )
        if (consumeTrailingEvent(event)) return KeyboardWedgeAssemblyResult.Consumed
        val printableCharacter = event.printableCharacter
        if (printableCharacter == null && event.terminator == null) {
            return KeyboardWedgeAssemblyResult.NotHandled
        }
        // Solo se consume el UP/repeat de un DOWN que el ensamblador realmente tomó. Así Tab o
        // Enter con el buffer vacío siguen disponibles para navegación por teclado y accesibilidad.
        if (event.action == KeyboardWedgeKeyAction.UP) {
            return KeyboardWedgeAssemblyResult.NotHandled
        }
        if (event.repeatCount > 0) {
            val repeatsAcceptedDown = lastAcceptedDown?.let { accepted ->
                accepted.downTimeMillis == keyIdentity.downTimeMillis &&
                    accepted.deviceId == keyIdentity.deviceId &&
                    accepted.keyCode == keyIdentity.keyCode
            } == true
            return if (keyIdentity in consumedDownKeys || repeatsAcceptedDown) {
                KeyboardWedgeAssemblyResult.Consumed
            } else {
                KeyboardWedgeAssemblyResult.NotHandled
            }
        }

        val fingerprint = EventFingerprint(
            eventTimeMillis = event.eventTimeMillis,
            downTimeMillis = event.downTimeMillis,
            deviceId = event.deviceId,
            keyCode = event.keyCode,
            printableCharacter = printableCharacter,
            terminator = event.terminator,
            sourceSequence = event.sourceSequence,
        )
        // Un carácter de ACTION_MULTIPLE ya es texto completo. Dos bloques de un carácter
        // pueden compartir hora, tecla e índice cero sin ser un DOWN físico reentregado.
        if (!event.isFromCharacterBatch && fingerprint == lastAcceptedDown) {
            rememberConsumed(keyIdentity)
            return KeyboardWedgeAssemblyResult.Consumed
        }

        val hadPendingInput = hasPendingInput()
        if (event.terminator != null && !hadPendingInput) {
            return KeyboardWedgeAssemblyResult.NotHandled
        }
        lastAcceptedDown = fingerprint.takeUnless { event.isFromCharacterBatch }
        if (hadPendingInput && mustResetBefore(event)) {
            reject(KeyboardWedgeReadError.INCOMPLETE)
        }
        activeDeviceId = event.deviceId
        lastEventTimeMillis = event.eventTimeMillis

        if (event.terminator != null) {
            val completedValue = buffer.toString()
            val failure = rejected
            clearPendingInput()
            lastTerminatorTimeMillis = event.eventTimeMillis
            lastTerminatorDeviceId = event.deviceId
            rememberConsumed(keyIdentity)
            return if (failure != null) KeyboardWedgeAssemblyResult.Rejected(failure)
            else KeyboardWedgeAssemblyResult.Completed(completedValue)
        }

        lastTerminatorTimeMillis = null
        lastTerminatorDeviceId = null
        if (rejected != null) {
            rememberConsumed(keyIdentity)
            return KeyboardWedgeAssemblyResult.Consumed
        }
        if (printableCharacter == null || printableCharacter !in ' '..'~') {
            reject(KeyboardWedgeReadError.INVALID_CHARACTER)
            rememberConsumed(keyIdentity)
            return KeyboardWedgeAssemblyResult.Consumed
        }
        if (buffer.length == maxLength) {
            reject(KeyboardWedgeReadError.TOO_LONG)
            rememberConsumed(keyIdentity)
            return KeyboardWedgeAssemblyResult.Consumed
        }
        buffer.append(printableCharacter)
        rememberConsumed(keyIdentity)
        return KeyboardWedgeAssemblyResult.Consumed
    }

    /** Elimina una lectura parcial y también la huella usada para deduplicar eventos. */
    fun reset(preserveTrailingEvents: Boolean = false) {
        clearPendingInput()
        lastAcceptedDown = null
        if (!preserveTrailingEvents) {
            consumedDownKeys.clear()
            lastTerminatorTimeMillis = null
            lastTerminatorDeviceId = null
        }
    }

    /** Consume releases y sufijos CR/LF duplicados incluso si el destino acaba de cerrarse. */
    fun consumeTrailingEvent(event: KeyboardWedgeKeyEvent): Boolean {
        val key = KeyIdentity(event.downTimeMillis, event.deviceId, event.keyCode)
        if (event.action == KeyboardWedgeKeyAction.UP) {
            // Con teclas superpuestas Android puede asignar al UP el downTime de otra
            // tecla. Su propio eventTime sí permite evitar que cierre un DOWN posterior.
            val consumed = consumedDownKeys.removeAll { accepted ->
                accepted.deviceId == key.deviceId && accepted.keyCode == key.keyCode &&
                    accepted.downTimeMillis <= event.eventTimeMillis
            }
            if (consumed && lastAcceptedDown?.let { accepted ->
                    accepted.deviceId == key.deviceId && accepted.keyCode == key.keyCode &&
                        accepted.eventTimeMillis <= event.eventTimeMillis
                } == true
            ) {
                // Dos pulsaciones físicas completas pueden compartir el mismo milisegundo.
                // El UP cierra la identidad de la pulsación, aunque la próxima huella sea igual.
                lastAcceptedDown = null
            }
            return consumed
        }
        if (event.repeatCount > 0 && key in consumedDownKeys) return true
        val lastTime = lastTerminatorTimeMillis ?: return false
        if (
            event.terminator != null && !hasPendingInput() && event.deviceId == lastTerminatorDeviceId &&
            event.eventTimeMillis - lastTime in 0..TRAILING_TERMINATOR_WINDOW_MILLIS
        ) {
            rememberConsumed(key)
            return true
        }
        return false
    }

    private fun reject(reason: KeyboardWedgeReadError) {
        buffer.clear()
        if (rejected == null) rejected = reason
    }

    private fun rememberConsumed(key: KeyIdentity) {
        // Algunos drivers omiten UP; no crecer indefinidamente con esos lectores.
        if (consumedDownKeys.size >= MAX_INPUT_LENGTH * 2) consumedDownKeys.remove(consumedDownKeys.first())
        consumedDownKeys += key
    }

    private fun mustResetBefore(event: KeyboardWedgeKeyEvent): Boolean {
        if (activeDeviceId != event.deviceId) return true
        val previousTime = lastEventTimeMillis ?: return false
        return event.eventTimeMillis < previousTime ||
            event.eventTimeMillis - previousTime > timeoutMillis
    }

    private fun hasPendingInput(): Boolean = buffer.isNotEmpty() || rejected != null

    private fun clearPendingInput() {
        buffer.clear()
        activeDeviceId = null
        lastEventTimeMillis = null
        rejected = null
    }

    private data class EventFingerprint(
        val eventTimeMillis: Long,
        val downTimeMillis: Long,
        val deviceId: Int,
        val keyCode: Int,
        val printableCharacter: Char?,
        val terminator: KeyboardWedgeTerminator?,
        val sourceSequence: Int,
    )

    private data class KeyIdentity(
        val downTimeMillis: Long,
        val deviceId: Int,
        val keyCode: Int,
    )

    companion object {
        const val MAX_INPUT_LENGTH = 128
        const val DEFAULT_INTER_KEY_TIMEOUT_MILLIS = 1_000L
        const val TRAILING_TERMINATOR_WINDOW_MILLIS = 250L
    }
}

/** Registro de un único destino de lectura. Cerrar un registro obsoleto no afecta al nuevo. */
class KeyboardWedgeRegistration internal constructor(
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeAction()
    }
}

/**
 * Frontera singleton entre la Activity y la pantalla que opta por recibir lecturas.
 *
 * Está inactiva por defecto, mantiene como máximo una suscripción y no almacena el último valor.
 */
object KeyboardWedgeRouter {
    private val lock = Any()
    private val assembler = KeyboardWedgeAssembler()
    private var generation = 0L
    private var consumer: ((String) -> Unit)? = null
    private var errorConsumer: ((KeyboardWedgeReadError) -> Unit)? = null
    private var inputConsumer: ((String) -> Unit)? = null

    fun activate(
        onScan: (String) -> Unit,
        onReadError: (KeyboardWedgeReadError) -> Unit = {},
        onInputChanged: (String) -> Unit = {},
    ): KeyboardWedgeRegistration {
        val registrationGeneration = synchronized(lock) {
            generation += 1L
            assembler.reset(preserveTrailingEvents = true)
            consumer = onScan
            errorConsumer = onReadError
            inputConsumer = onInputChanged
            generation
        }
        onInputChanged("")
        return KeyboardWedgeRegistration {
            deactivate(registrationGeneration)
        }
    }

    fun deactivate() {
        val onInputChanged = synchronized(lock) {
            generation += 1L
            consumer = null
            errorConsumer = null
            assembler.reset()
            inputConsumer.also { inputConsumer = null }
        }
        onInputChanged?.invoke("")
    }

    /** Descarta una lectura parcial sin desactivar el destino registrado. */
    fun reset() {
        val onInputChanged = synchronized(lock) {
            assembler.reset()
            inputConsumer
        }
        onInputChanged?.invoke("")
    }

    /** Para ventanas de diálogo: sólo suprime el cierre sobrante, nunca captura su texto. */
    fun consumeTrailingEvent(event: KeyboardWedgeKeyEvent): Boolean = synchronized(lock) {
        assembler.consumeTrailingEvent(event)
    }

    /** Devuelve `true` solo cuando una suscripción activa consume el evento. */
    fun route(event: KeyboardWedgeKeyEvent): Boolean {
        val delivery = synchronized(lock) {
            val activeConsumer = consumer ?: return assembler.consumeTrailingEvent(event)
            val previousInput = assembler.pendingInput
            val previousError = assembler.pendingError
            val result = assembler.accept(event)
            val delivery = when (result) {
                KeyboardWedgeAssemblyResult.NotHandled -> null
                KeyboardWedgeAssemblyResult.Consumed -> Delivery(
                    consumed = true,
                    error = assembler.pendingError.takeIf { it != previousError },
                    errorConsumer = errorConsumer,
                )
                is KeyboardWedgeAssemblyResult.Completed -> Delivery(
                    consumed = true,
                    value = result.value,
                    consumer = activeConsumer,
                )
                is KeyboardWedgeAssemblyResult.Rejected -> Delivery(
                    consumed = true,
                    // El rechazo ya se mostró al detectarlo, aunque el lector no envíe sufijo.
                    error = result.reason.takeIf { previousError == null },
                    errorConsumer = errorConsumer,
                )
            }
            if (assembler.pendingInput != previousInput) {
                delivery?.copy(input = assembler.pendingInput, inputConsumer = inputConsumer)
            } else delivery
        }
        delivery?.input?.let { input -> delivery.inputConsumer?.invoke(input) }
        delivery?.value?.let { value -> delivery.consumer?.invoke(value) }
        delivery?.error?.let { error -> delivery.errorConsumer?.invoke(error) }
        return delivery?.consumed == true
    }

    private fun deactivate(registrationGeneration: Long) {
        val onInputChanged = synchronized(lock) {
            if (generation != registrationGeneration) return
            generation += 1L
            consumer = null
            errorConsumer = null
            assembler.reset(preserveTrailingEvents = true)
            inputConsumer.also { inputConsumer = null }
        }
        onInputChanged?.invoke("")
    }

    private data class Delivery(
        val consumed: Boolean,
        val value: String? = null,
        val consumer: ((String) -> Unit)? = null,
        val error: KeyboardWedgeReadError? = null,
        val errorConsumer: ((KeyboardWedgeReadError) -> Unit)? = null,
        val input: String? = null,
        val inputConsumer: ((String) -> Unit)? = null,
    )
}
