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
)

sealed interface KeyboardWedgeAssemblyResult {
    /** El evento no pertenece a una lectura y puede seguir propagándose. */
    data object NotHandled : KeyboardWedgeAssemblyResult

    /** El evento pertenece a una lectura, pero todavía no la completa. */
    data object Consumed : KeyboardWedgeAssemblyResult

    /** Lectura válida, ya separada del estado interno del ensamblador. */
    data class Completed(val value: String) : KeyboardWedgeAssemblyResult
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
    private var overflowed = false

    init {
        require(maxLength in 1..MAX_INPUT_LENGTH)
        require(timeoutMillis > 0L)
    }

    fun accept(event: KeyboardWedgeKeyEvent): KeyboardWedgeAssemblyResult {
        val printableCharacter = event.printableCharacter?.takeIf(::isPrintable)
        if (printableCharacter == null && event.terminator == null) {
            return KeyboardWedgeAssemblyResult.NotHandled
        }

        val keyIdentity = KeyIdentity(
            downTimeMillis = event.downTimeMillis,
            deviceId = event.deviceId,
            keyCode = event.keyCode,
        )
        // Solo se consume el UP/repeat de un DOWN que el ensamblador realmente tomó. Así Tab o
        // Enter con el buffer vacío siguen disponibles para navegación por teclado y accesibilidad.
        if (event.action == KeyboardWedgeKeyAction.UP) {
            return if (consumedDownKeys.remove(keyIdentity)) {
                KeyboardWedgeAssemblyResult.Consumed
            } else {
                KeyboardWedgeAssemblyResult.NotHandled
            }
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
        )
        if (fingerprint == lastAcceptedDown) {
            consumedDownKeys += keyIdentity
            return KeyboardWedgeAssemblyResult.Consumed
        }

        val hadPendingInput = hasPendingInput()
        if (event.terminator != null && !hadPendingInput) {
            return KeyboardWedgeAssemblyResult.NotHandled
        }
        lastAcceptedDown = fingerprint
        if (hadPendingInput && mustResetBefore(event)) {
            clearPendingInput()
        }
        activeDeviceId = event.deviceId
        lastEventTimeMillis = event.eventTimeMillis

        if (event.terminator != null) {
            if (buffer.isEmpty() || overflowed) {
                clearPendingInput()
                consumedDownKeys += keyIdentity
                return KeyboardWedgeAssemblyResult.Consumed
            }
            val completedValue = buffer.toString()
            clearPendingInput()
            consumedDownKeys += keyIdentity
            return KeyboardWedgeAssemblyResult.Completed(completedValue)
        }

        if (overflowed) {
            consumedDownKeys += keyIdentity
            return KeyboardWedgeAssemblyResult.Consumed
        }
        if (buffer.length == maxLength) {
            buffer.clear()
            overflowed = true
            consumedDownKeys += keyIdentity
            return KeyboardWedgeAssemblyResult.Consumed
        }
        buffer.append(printableCharacter)
        consumedDownKeys += keyIdentity
        return KeyboardWedgeAssemblyResult.Consumed
    }

    /** Elimina una lectura parcial y también la huella usada para deduplicar eventos. */
    fun reset() {
        clearPendingInput()
        lastAcceptedDown = null
    }

    private fun mustResetBefore(event: KeyboardWedgeKeyEvent): Boolean {
        if (activeDeviceId != event.deviceId) return true
        val previousTime = lastEventTimeMillis ?: return false
        return event.eventTimeMillis < previousTime ||
            event.eventTimeMillis - previousTime > timeoutMillis
    }

    private fun hasPendingInput(): Boolean = buffer.isNotEmpty() || overflowed

    private fun clearPendingInput() {
        buffer.clear()
        activeDeviceId = null
        lastEventTimeMillis = null
        consumedDownKeys.clear()
        overflowed = false
    }

    private fun isPrintable(character: Char): Boolean = when (Character.getType(character)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        -> false

        else -> true
    }

    private data class EventFingerprint(
        val eventTimeMillis: Long,
        val downTimeMillis: Long,
        val deviceId: Int,
        val keyCode: Int,
        val printableCharacter: Char?,
        val terminator: KeyboardWedgeTerminator?,
    )

    private data class KeyIdentity(
        val downTimeMillis: Long,
        val deviceId: Int,
        val keyCode: Int,
    )

    companion object {
        const val MAX_INPUT_LENGTH = 128
        const val DEFAULT_INTER_KEY_TIMEOUT_MILLIS = 1_000L
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

    fun activate(onScan: (String) -> Unit): KeyboardWedgeRegistration {
        val registrationGeneration = synchronized(lock) {
            generation += 1L
            assembler.reset()
            consumer = onScan
            generation
        }
        return KeyboardWedgeRegistration {
            deactivate(registrationGeneration)
        }
    }

    fun deactivate() {
        synchronized(lock) {
            generation += 1L
            consumer = null
            assembler.reset()
        }
    }

    /** Descarta una lectura parcial sin desactivar el destino registrado. */
    fun reset() {
        synchronized(lock) {
            assembler.reset()
        }
    }

    /** Devuelve `true` solo cuando una suscripción activa consume el evento. */
    fun route(event: KeyboardWedgeKeyEvent): Boolean {
        val delivery = synchronized(lock) {
            val activeConsumer = consumer ?: return false
            when (val result = assembler.accept(event)) {
                KeyboardWedgeAssemblyResult.NotHandled -> null
                KeyboardWedgeAssemblyResult.Consumed -> Delivery(consumed = true)
                is KeyboardWedgeAssemblyResult.Completed -> Delivery(
                    consumed = true,
                    value = result.value,
                    consumer = activeConsumer,
                )
            }
        }
        delivery?.value?.let { value -> delivery.consumer?.invoke(value) }
        return delivery?.consumed == true
    }

    private fun deactivate(registrationGeneration: Long) {
        synchronized(lock) {
            if (generation != registrationGeneration) return
            generation += 1L
            consumer = null
            assembler.reset()
        }
    }

    private data class Delivery(
        val consumed: Boolean,
        val value: String? = null,
        val consumer: ((String) -> Unit)? = null,
    )
}
