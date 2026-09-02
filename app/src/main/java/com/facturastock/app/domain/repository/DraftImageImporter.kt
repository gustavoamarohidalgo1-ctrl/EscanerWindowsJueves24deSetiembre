package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId

/**
 * Puerto de importación de imágenes de factura hacia el almacenamiento privado de la app,
 * alimentado por dos orígenes: una URI elegida fuera de la cámara (galería/documentos, vía
 * [import]) y los bytes JPEG producidos por la captura con cámara (vía [importBytes]).
 *
 * La implementación valida la imagen contra la política de captura (MIME, tamaño, dimensiones
 * y decodificabilidad), la limpia de geolocalización/EXIF/XMP y la copia al almacenamiento
 * privado de la app con nombre UUID no identificable, primero como temporal y luego con
 * movimiento atómico. Devuelve los metadatos persistidos, calculados sobre el archivo ya
 * limpio para que hash y tamaño coincidan con el contenido guardado. Las rutas son relativas
 * a ese almacenamiento, las mismas que persisten las entidades de imagen y que borra
 * `DraftFileStore`.
 *
 * La publicación nunca reemplaza un destino existente. Un reintento hacia la misma ruta lo
 * reutiliza únicamente si el SHA-256 del contenido limpio coincide; una colisión distinta
 * falla como `FileException` y preserva el archivo previo. En cualquier fallo solo se limpian
 * el temporal y cualquier destino que el intento actual haya creado.
 */
interface DraftImageImporter {
    /** Valida y copia la imagen al almacenamiento privado; devuelve los metadatos persistidos. */
    suspend fun import(draftId: DraftId, imageId: ImageId, sourceUri: String): ImportedImageFile

    /**
     * Valida y copia una captura JPEG en memoria. Los bytes deben ser un JPEG (se verifica al
     * decodificar las cabeceras, no por declaración del origen) y el archivo se guarda tal
     * cual, sin rotar: [rotationDegrees] solo acompaña a la llamada para que el caso de uso lo
     * registre en `InvoiceImage.rotationDegrees` y la vista lo aplique al mostrar la imagen.
     */
    suspend fun importBytes(
        draftId: DraftId,
        imageId: ImageId,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
    ): ImportedImageFile
}

/** Metadatos de una imagen ya validada, limpia de EXIF y copiada al almacenamiento privado. */
data class ImportedImageFile(
    val relativePath: String,
    val sha256: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
    /**
     * Rotación declarada por el EXIF antes de la limpieza (0, 90, 180 o 270; 0 si no
     * había). Solo la usa la entrada de galería; en la de cámara manda la rotación de
     * sensor que el llamador pasa al caso de uso.
     */
    val rotationDegrees: Int = 0,
)
