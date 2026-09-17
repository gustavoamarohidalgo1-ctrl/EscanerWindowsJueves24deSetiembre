package com.facturastock.app.core.input

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardWedgeAssemblerTest {
    @Test
    fun `ensambla caracteres imprimibles hasta enter`() {
        val assembler = KeyboardWedgeAssembler()

        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(down('0', 10L)))
        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(down('A', 20L)))

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("0A"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 30L)),
        )
    }

    @Test
    fun `enter numerico y tab tambien completan la lectura`() {
        KeyboardWedgeTerminator.entries.forEach { terminator ->
            val assembler = KeyboardWedgeAssembler()
            assembler.accept(down('7', 10L))

            assertEquals(
                KeyboardWedgeAssemblyResult.Completed("7"),
                assembler.accept(terminator(terminator, 20L)),
            )
        }
    }

    @Test
    fun `ignora teclas sin caracter pero rechaza codigos con controles sin concatenar partes`() {
        val assembler = KeyboardWedgeAssembler()
        assertEquals(
            KeyboardWedgeAssemblyResult.NotHandled,
            assembler.accept(down(null, 1L)),
        )
        assembler.accept(down('A', 10L))
        assembler.accept(down('\u001D', 20L))
        assembler.accept(down('B', 30L))
        assertEquals(
            KeyboardWedgeAssemblyResult.Rejected(KeyboardWedgeReadError.INVALID_CHARACTER),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 40L)),
        )
    }

    @Test
    fun `repeat y doble down mientras la tecla sigue pulsada no duplican caracteres`() {
        val assembler = KeyboardWedgeAssembler()
        val firstDown = down('A', 10L)

        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(firstDown))
        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(firstDown.copy(eventTimeMillis = 11L, repeatCount = 1)),
        )
        assertEquals(
            KeyboardWedgeAssemblyResult.NotHandled,
            assembler.accept(firstDown.copy(action = KeyboardWedgeKeyAction.UP, deviceId = 8)),
        )
        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(firstDown))
        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(firstDown.copy(action = KeyboardWedgeKeyAction.UP)),
        )
        assembler.accept(down('A', 12L))

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("AA"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 20L)),
        )
    }

    @Test
    fun `pulsaciones down up en el mismo milisegundo conservan ceros y cifras repetidas`() {
        listOf(false, true).forEach { upWithoutUnicode ->
            val assembler = KeyboardWedgeAssembler()
            "0011".forEach { digit ->
                val key = down(digit, 10L)
                assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(key))
                // Reentregar el mismo DOWN antes del UP sigue siendo un evento duplicado.
                assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(key))
                assertEquals(
                    KeyboardWedgeAssemblyResult.Consumed,
                    assembler.accept(
                        key.copy(
                            action = KeyboardWedgeKeyAction.UP,
                            printableCharacter = digit.takeUnless { upWithoutUnicode },
                        ),
                    ),
                )
            }

            assertEquals(
                KeyboardWedgeAssemblyResult.Completed("0011"),
                assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 11L)),
            )
        }
    }

    @Test
    fun `keyup con downTime de otra tecla cierra la pulsacion fisica capturada`() {
        val assembler = KeyboardWedgeAssembler()
        val firstSeven = down('7', 10L)
        assembler.accept(firstSeven)

        // Android documenta que, con teclas superpuestas, downTime puede pertenecer
        // a otra tecla presionada después; no identifica necesariamente este UP.
        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(
                firstSeven.copy(
                    action = KeyboardWedgeKeyAction.UP,
                    eventTimeMillis = 12L,
                    downTimeMillis = 11L,
                    printableCharacter = null,
                ),
            ),
        )
        assembler.accept(down('7', 13L))

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("77"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 14L)),
        )
    }

    @Test
    fun `keyup obsoleto no cierra una pulsacion posterior del mismo digito`() {
        val assembler = KeyboardWedgeAssembler()
        val firstSeven = down('7', 10L)
        val secondSeven = down('7', 20L)
        assembler.accept(firstSeven)
        assembler.accept(secondSeven)

        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(
                firstSeven.copy(
                    action = KeyboardWedgeKeyAction.UP,
                    eventTimeMillis = 15L,
                    printableCharacter = null,
                ),
            ),
        )
        // La reentrega del segundo DOWN todavía se reconoce como el mismo evento.
        assembler.accept(secondSeven)

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("77"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 21L)),
        )
    }

    @Test
    fun `timeout rechaza la lectura completa en vez de entregar otro codigo con la cola`() {
        val assembler = KeyboardWedgeAssembler(timeoutMillis = 100L)
        assembler.accept(down('A', 10L))

        assembler.accept(down('B', 111L))

        assertEquals(
            KeyboardWedgeAssemblyResult.Rejected(KeyboardWedgeReadError.INCOMPLETE),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 120L)),
        )
    }

    @Test
    fun `cambio de dispositivo rechaza trama mezclada`() {
        val assembler = KeyboardWedgeAssembler()
        assembler.accept(down('A', 10L, deviceId = 7))

        assembler.accept(down('B', 20L, deviceId = 8))

        assertEquals(
            KeyboardWedgeAssemblyResult.Rejected(KeyboardWedgeReadError.INCOMPLETE),
            assembler.accept(
                terminator(KeyboardWedgeTerminator.ENTER, 30L, deviceId = 8),
            ),
        )
    }

    @Test
    fun `acepta exactamente el maximo configurado`() {
        val assembler = KeyboardWedgeAssembler(maxLength = 3)
        "ABC".forEachIndexed { index, character ->
            assembler.accept(down(character, index.toLong()))
        }

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("ABC"),
            assembler.accept(terminator(KeyboardWedgeTerminator.TAB, 10L)),
        )
    }

    @Test
    fun `el maximo publico es 128 y no puede ampliarse`() {
        assertEquals(128, KeyboardWedgeAssembler.MAX_INPUT_LENGTH)

        val failure = runCatching {
            KeyboardWedgeAssembler(maxLength = KeyboardWedgeAssembler.MAX_INPUT_LENGTH + 1)
        }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `exceso se descarta completo y no se trunca`() {
        val assembler = KeyboardWedgeAssembler(maxLength = 3)
        "ABCD".forEachIndexed { index, character ->
            assembler.accept(down(character, index.toLong()))
        }

        assertEquals(
            KeyboardWedgeAssemblyResult.Rejected(KeyboardWedgeReadError.TOO_LONG),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 10L)),
        )
        assembler.accept(down('Z', 20L))
        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("Z"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 30L)),
        )
    }

    @Test
    fun `reset elimina una lectura parcial`() {
        val assembler = KeyboardWedgeAssembler()
        assembler.accept(down('A', 10L))

        assembler.reset()

        assertEquals(
            KeyboardWedgeAssemblyResult.NotHandled,
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 20L)),
        )
    }

    @Test
    fun `tab y enter sin lectura se propagan para navegacion por teclado`() {
        KeyboardWedgeTerminator.entries.forEach { terminator ->
            val assembler = KeyboardWedgeAssembler()
            val down = terminator(terminator, 10L)

            assertEquals(
                KeyboardWedgeAssemblyResult.NotHandled,
                assembler.accept(down),
            )
            assertEquals(
                KeyboardWedgeAssemblyResult.NotHandled,
                assembler.accept(down.copy(action = KeyboardWedgeKeyAction.UP)),
            )
        }
    }

    @Test
    fun `keyup del terminador consumido no se filtra a la interfaz`() {
        val assembler = KeyboardWedgeAssembler()
        assembler.accept(down('A', 10L))
        val enterDown = terminator(KeyboardWedgeTerminator.ENTER, 20L)

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("A"),
            assembler.accept(enterDown),
        )
        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(enterDown.copy(action = KeyboardWedgeKeyAction.UP)),
        )
    }

    @Test
    fun `overflow no se rehabilita con un timeout y conserva el error hasta enter`() {
        val assembler = KeyboardWedgeAssembler(maxLength = 3, timeoutMillis = 100L)
        "ABCD".forEachIndexed { index, c -> assembler.accept(down(c, index.toLong())) }
        assembler.accept(down('Z', 200L))
        assertEquals(
            KeyboardWedgeAssemblyResult.Rejected(KeyboardWedgeReadError.TOO_LONG),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 210L)),
        )
        assembler.accept(down('A', 220L))
        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("A"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 230L)),
        )
    }

    @Test
    fun `doble sufijo se consume sin activar controles y permite siguiente lectura inmediata`() {
        val assembler = KeyboardWedgeAssembler()
        assembler.accept(down('0', 10L))
        assertEquals(KeyboardWedgeAssemblyResult.Completed("0"), assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 20L)))
        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 21L)))
        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(terminator(KeyboardWedgeTerminator.TAB, 22L)))
        assembler.accept(down('1', 23L))
        assertEquals(KeyboardWedgeAssemblyResult.Completed("1"), assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 24L)))
        assertEquals(KeyboardWedgeAssemblyResult.NotHandled, assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 500L)))
    }

    @Test
    fun `keyup sin unicode todavia se consume para la tecla que capturo el lector`() {
        val assembler = KeyboardWedgeAssembler()
        val key = down('A', 10L)
        assembler.accept(key)
        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(key.copy(action = KeyboardWedgeKeyAction.UP, printableCharacter = null)),
        )
    }

    @Test
    fun `caracteres identicos de un paquete conservan su posicion y ceros`() {
        val assembler = KeyboardWedgeAssembler()
        "0011".forEachIndexed { index, c -> assembler.accept(down(c, 10L).copy(sourceSequence = index)) }
        assertEquals(KeyboardWedgeAssemblyResult.Completed("0011"), assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 11L)))
    }

    @Test
    fun `paquetes consecutivos de un caracter conservan digitos repetidos en el mismo milisegundo`() {
        val assembler = KeyboardWedgeAssembler()
        // Cada bloque ACTION_MULTIPLE puede contener un solo carácter. Los índices empiezan
        // de nuevo en cero y Android mide ambos eventos en el mismo milisegundo.
        "0011".forEach { digit ->
            assembler.accept(down(digit, 10L).copy(isFromCharacterBatch = true))
        }

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("0011"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 11L)),
        )
    }

    @Test
    fun `un caracter en bloque no comparte deduplicacion con el down fisico contiguo`() {
        val assembler = KeyboardWedgeAssembler()
        val physical = down('7', 10L)
        assembler.accept(physical)
        assembler.accept(physical.copy(isFromCharacterBatch = true))
        assembler.accept(physical)
        // La reentrega del último DOWN físico sí conserva la deduplicación habitual.
        assembler.accept(physical)

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("777"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 11L)),
        )
    }

    private fun down(
        character: Char?,
        eventTimeMillis: Long,
        deviceId: Int = 7,
    ) = KeyboardWedgeKeyEvent(
        action = KeyboardWedgeKeyAction.DOWN,
        eventTimeMillis = eventTimeMillis,
        deviceId = deviceId,
        keyCode = character?.code ?: 0,
        printableCharacter = character,
    )

    private fun terminator(
        terminator: KeyboardWedgeTerminator,
        eventTimeMillis: Long,
        deviceId: Int = 7,
    ) = KeyboardWedgeKeyEvent(
        action = KeyboardWedgeKeyAction.DOWN,
        eventTimeMillis = eventTimeMillis,
        deviceId = deviceId,
        keyCode = 100 + terminator.ordinal,
        terminator = terminator,
    )
}

class KeyboardWedgeRouterTest {
    @After
    fun tearDown() {
        KeyboardWedgeRouter.deactivate()
    }

    @Test
    fun `la lectura sin sufijo se muestra sin confirmar y puede limpiarse antes del siguiente codigo`() {
        val delivered = mutableListOf<String>()
        val pending = mutableListOf<String>()
        KeyboardWedgeRouter.activate(onScan = delivered::add, onInputChanged = pending::add)
        "00123".forEachIndexed { index, char ->
            KeyboardWedgeRouter.route(down(char, 10L + index))
        }
        assertEquals("00123", pending.last())
        assertTrue(delivered.isEmpty())

        KeyboardWedgeRouter.reset()
        assertEquals("", pending.last())
        KeyboardWedgeRouter.route(down('B', 30L))
        KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.ENTER, 40L))
        assertEquals(listOf("B"), delivered)
        assertEquals("", pending.last())
    }

    @Test
    fun `un cierre obsoleto no borra la lectura visible del nuevo receptor`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val obsolete = KeyboardWedgeRouter.activate(onScan = {}, onInputChanged = first::add)
        val active = KeyboardWedgeRouter.activate(onScan = {}, onInputChanged = second::add)
        KeyboardWedgeRouter.route(down('9', 10L))
        obsolete.close()
        assertEquals("9", second.last())
        active.close()
        assertEquals("", second.last())
    }

    @Test
    fun `el error sin sufijo se informa una vez y reiniciar permite otra lectura integra`() {
        val errors = mutableListOf<KeyboardWedgeReadError>()
        val delivered = mutableListOf<String>()
        KeyboardWedgeRouter.activate(onScan = delivered::add, onReadError = errors::add)
        KeyboardWedgeRouter.route(down('1', 10L))
        KeyboardWedgeRouter.route(down('2', 1_011L))
        assertEquals(listOf(KeyboardWedgeReadError.INCOMPLETE), errors)
        KeyboardWedgeRouter.route(down('3', 1_012L))
        KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.ENTER, 1_013L))
        assertEquals(1, errors.size)
        assertTrue(delivered.isEmpty())

        KeyboardWedgeRouter.route(down('A', 2_000L))
        KeyboardWedgeRouter.route(down('B', 3_001L))
        KeyboardWedgeRouter.reset()
        KeyboardWedgeRouter.route(down('0', 4_000L))
        KeyboardWedgeRouter.route(down('4', 4_010L))
        KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.ENTER, 4_020L))
        assertEquals(listOf("04"), delivered)
        assertEquals(2, errors.size)
    }

    @Test
    fun `inactivo nunca consume ni entrega`() {
        var delivered: String? = null

        assertFalse(KeyboardWedgeRouter.route(down('A', 10L)))
        assertEquals(null, delivered)
    }

    @Test
    fun `activo consume y entrega solo al terminar`() {
        val delivered = mutableListOf<String>()
        val registration = KeyboardWedgeRouter.activate(delivered::add)

        assertTrue(KeyboardWedgeRouter.route(down('A', 10L)))
        assertTrue(
            KeyboardWedgeRouter.route(
                terminator(KeyboardWedgeTerminator.NUMPAD_ENTER, 20L),
            ),
        )
        assertEquals(listOf("A"), delivered)

        registration.close()
        assertFalse(KeyboardWedgeRouter.route(down('B', 30L)))
    }

    @Test
    fun `activo no secuestra tab o enter con el buffer vacio`() {
        val delivered = mutableListOf<String>()
        KeyboardWedgeRouter.activate(delivered::add)

        assertFalse(
            KeyboardWedgeRouter.route(
                terminator(KeyboardWedgeTerminator.TAB, 10L),
            ),
        )
        assertFalse(
            KeyboardWedgeRouter.route(
                terminator(KeyboardWedgeTerminator.ENTER, 20L),
            ),
        )
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `reset descarta parcial sin desactivar el destino`() {
        val delivered = mutableListOf<String>()
        KeyboardWedgeRouter.activate(delivered::add)
        KeyboardWedgeRouter.route(down('A', 10L))

        KeyboardWedgeRouter.reset()
        KeyboardWedgeRouter.route(down('B', 20L))
        KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.ENTER, 30L))

        assertEquals(listOf("B"), delivered)
    }

    @Test
    fun `cerrar un registro obsoleto no desactiva el nuevo`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val obsoleteRegistration = KeyboardWedgeRouter.activate(first::add)
        KeyboardWedgeRouter.activate(second::add)

        obsoleteRegistration.close()
        KeyboardWedgeRouter.route(down('B', 10L))
        KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.TAB, 20L))

        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("B"), second)
    }

    @Test
    fun `cerrar destino tras escaneo no filtra el segundo enter`() {
        val delivered = mutableListOf<String>()
        val registration = KeyboardWedgeRouter.activate(delivered::add)
        KeyboardWedgeRouter.route(down('A', 10L))
        KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.ENTER, 20L))
        registration.close()
        assertTrue(KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.ENTER, 21L)))
        assertFalse(KeyboardWedgeRouter.route(down('B', 25L)))
        assertEquals(listOf("A"), delivered)
    }

    @Test
    fun `tramas rechazadas entregan error y nunca un producto distinto`() {
        val delivered = mutableListOf<String>()
        val errors = mutableListOf<KeyboardWedgeReadError>()
        KeyboardWedgeRouter.activate(delivered::add, errors::add)
        KeyboardWedgeRouter.route(down('A', 10L))
        KeyboardWedgeRouter.route(down('B', 1011L))
        KeyboardWedgeRouter.route(terminator(KeyboardWedgeTerminator.ENTER, 1020L))
        assertTrue(delivered.isEmpty())
        assertEquals(listOf(KeyboardWedgeReadError.INCOMPLETE), errors)
    }

    private fun down(character: Char, eventTimeMillis: Long) = KeyboardWedgeKeyEvent(
        action = KeyboardWedgeKeyAction.DOWN,
        eventTimeMillis = eventTimeMillis,
        deviceId = 7,
        keyCode = character.code,
        printableCharacter = character,
    )

    private fun terminator(
        terminator: KeyboardWedgeTerminator,
        eventTimeMillis: Long,
    ) = KeyboardWedgeKeyEvent(
        action = KeyboardWedgeKeyAction.DOWN,
        eventTimeMillis = eventTimeMillis,
        deviceId = 7,
        keyCode = 100 + terminator.ordinal,
        terminator = terminator,
    )
}
