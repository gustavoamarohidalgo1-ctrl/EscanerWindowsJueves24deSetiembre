package com.facturastock.app.feature.common

import java.awt.Desktop
import java.net.URI

/**
 * En Windows la app no controla la cámara directamente (no hay CameraX): el permiso se considera
 * no disponible de forma permanente y la foto de la factura se elige como archivo de imagen.
 */
fun isCameraPermissionPermanentlyDenied(): Boolean = true

/** Abre la página de privacidad de cámara de Configuración de Windows. */
fun openAppPermissionSettings() {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI("ms-settings:privacy-webcam"))
        }
    }
}
