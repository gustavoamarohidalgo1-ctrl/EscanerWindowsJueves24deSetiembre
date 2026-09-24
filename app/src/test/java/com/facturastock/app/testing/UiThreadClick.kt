package com.facturastock.app.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Clic entregado en el hilo de UI (EDT), como llega un clic real en la ventana.
 *
 * En los tests de escritorio `performClick()` inyecta el puntero desde el hilo del test; si el
 * clic navega y el NavController desapila una entrada, `LifecycleRegistry` rechaza el cambio
 * ("must be called on the main thread") porque el hilo principal de Compose Desktop es el EDT.
 * Aquí se invoca la acción semántica `OnClick` del nodo dentro de `runOnIdle`, que corre en el EDT.
 */
fun SemanticsNodeInteraction.performClickOnUiThread(rule: ComposeTestRule): SemanticsNodeInteraction {
    val onClick = requireNotNull(fetchSemanticsNode().config[SemanticsActions.OnClick].action) {
        "El nodo no tiene acción de clic"
    }
    rule.runOnIdle { check(onClick()) { "El nodo no aceptó el clic" } }
    rule.waitForIdle()
    return this
}
