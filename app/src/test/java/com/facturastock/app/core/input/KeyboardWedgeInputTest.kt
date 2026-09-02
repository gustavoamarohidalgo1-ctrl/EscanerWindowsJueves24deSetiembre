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
    fun `ignora controles y teclas no representables`() {
        val assembler = KeyboardWedgeAssembler()

        assertEquals(
            KeyboardWedgeAssemblyResult.NotHandled,
            assembler.accept(down('\n', 10L)),
        )
        assertEquals(
            KeyboardWedgeAssemblyResult.NotHandled,
            assembler.accept(down(null, 20L)),
        )
        assertEquals(
            KeyboardWedgeAssemblyResult.NotHandled,
            assembler.accept(down('\u200B', 30L)),
        )
    }

    @Test
    fun `keyup repeat y doble down no duplican caracteres`() {
        val assembler = KeyboardWedgeAssembler()
        val firstDown = down('A', 10L)

        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(firstDown))
        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(firstDown.copy(action = KeyboardWedgeKeyAction.UP)),
        )
        assertEquals(
            KeyboardWedgeAssemblyResult.Consumed,
            assembler.accept(firstDown.copy(eventTimeMillis = 11L, repeatCount = 1)),
        )
        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(firstDown))
        assembler.accept(down('A', 12L))

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("AA"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 20L)),
        )
    }

    @Test
    fun `timeout descarta el prefijo incompleto`() {
        val assembler = KeyboardWedgeAssembler(timeoutMillis = 100L)
        assembler.accept(down('A', 10L))

        assembler.accept(down('B', 111L))

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("B"),
            assembler.accept(terminator(KeyboardWedgeTerminator.ENTER, 120L)),
        )
    }

    @Test
    fun `cambio de dispositivo descarta el prefijo incompleto`() {
        val assembler = KeyboardWedgeAssembler()
        assembler.accept(down('A', 10L, deviceId = 7))

        assembler.accept(down('B', 20L, deviceId = 8))

        assertEquals(
            KeyboardWedgeAssemblyResult.Completed("B"),
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
            KeyboardWedgeAssemblyResult.Consumed,
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
