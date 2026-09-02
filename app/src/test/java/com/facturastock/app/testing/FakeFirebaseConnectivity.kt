package com.facturastock.app.testing

/**
 * Interruptor determinista de conectividad para los puertos fake que representan Firebase.
 *
 * Es una dependencia compartible: una prueba puede inyectar la misma instancia en Auth, Functions
 * y Firestore, cortar la red sin temporizadores ni sockets y restaurarla conservando el guion que
 * todavía no se consumió.
 */
class FakeFirebaseConnectivity(
    initiallyConnected: Boolean = true,
) {
    var isConnected: Boolean = initiallyConnected
        private set

    fun disconnect() {
        isConnected = false
    }

    fun reconnect() {
        isConnected = true
    }
}
