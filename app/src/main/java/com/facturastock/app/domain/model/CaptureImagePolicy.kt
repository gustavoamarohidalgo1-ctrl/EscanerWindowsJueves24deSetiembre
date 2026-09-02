package com.facturastock.app.domain.model

import java.util.Locale

/**
 * Política de aceptación de imágenes de factura, aplicada tanto a la captura con cámara como
 * a la importación desde galería. Es pura: la misma regla se evalúa en la app y en las
 * pruebas de JVM sin tocar Android.
 *
 * Límites: [MAX_FILE_SIZE_BYTES] acota el tamaño del archivo copiado y [MAX_DIMENSION_PX]
 * acota cada lado de la imagen decodificada; ambos protegen el almacenamiento privado y la
 * memoria frente a imágenes patológicas. Los MIME aceptados ([ALLOWED_MIME_TYPES]) son los
 * que el decodificador de la plataforma garantiza leer **y** cuya limpieza de metadatos es
 * garantizable: HEIC/HEIF quedan fuera porque su contenedor ISO-BMFF no permite la
 * reescritura de EXIF con las herramientas de la plataforma, y aceptarlos rompería la
 * promesa de privacidad de la copia de trabajo (sin geolocalización ni EXIF residual).
 */
object CaptureImagePolicy {
    /** MIME de imagen aceptados, siempre en minúsculas. */
    val ALLOWED_MIME_TYPES: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
    )

    /** Tamaño máximo de archivo admitido: 15 MiB. */
    const val MAX_FILE_SIZE_BYTES: Long = 15L * 1024L * 1024L

    /** Dimensión máxima admitida por lado, en píxeles. */
    const val MAX_DIMENSION_PX: Int = 8_000

    /**
     * Acepta el MIME normalizándolo (sin espacios laterales, en minúsculas); null o vacío se
     * rechazan porque sin tipo no se puede garantizar una decodificación segura.
     */
    fun isMimeTypeAllowed(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase(Locale.ROOT) in ALLOWED_MIME_TYPES

    /** Acepta tamaños entre 0 y [MAX_FILE_SIZE_BYTES], ambos incluidos. */
    fun isSizeAllowed(fileSizeBytes: Long): Boolean =
        fileSizeBytes in 0..MAX_FILE_SIZE_BYTES

    /** Acepta dimensiones positivas de hasta [MAX_DIMENSION_PX] por lado, incluido. */
    fun areDimensionsAllowed(widthPx: Int, heightPx: Int): Boolean =
        widthPx in 1..MAX_DIMENSION_PX && heightPx in 1..MAX_DIMENSION_PX
}
