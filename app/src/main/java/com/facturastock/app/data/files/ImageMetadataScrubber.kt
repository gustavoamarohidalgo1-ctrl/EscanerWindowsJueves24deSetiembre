package com.facturastock.app.data.files

import androidx.exifinterface.media.ExifInterface
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import java.io.File
import java.io.IOException

/**
 * Limpieza de metadatos de la copia privada de trabajo. Reescribe el archivo SIN
 * geolocalización, EXIF, XMP ni cualquier otro metadato (vía [ImageMetadataStripper]) y
 * devuelve la rotación que el EXIF declaraba, para que viaje en los metadatos de dominio
 * (`InvoiceImage.rotationDegrees`) con la misma convención de la captura con cámara: el
 * archivo se guarda sin rotar y la vista aplica la rotación al mostrarlo.
 *
 * El resultado es cerrado ante cualquier anomalía: una lectura o escritura fallida se
 * traduce a [FileException] y la importación se rechaza — nunca se persiste una copia con
 * metadatos sin limpiar. Cuando hay reemplazo, los bytes limpios se sincronizan antes del
 * rename y el nombre nuevo se confirma con `fsync` del directorio antes de retornar.
 */
internal object ImageMetadataScrubber {

    /**
     * Limpia [file] en el lugar y devuelve su rotación EXIF en grados (0, 90, 180 o 270;
     * 0 si el archivo no declaraba orientación). [mimeType] ya fue validado contra la
     * política de captura.
     */
    fun scrub(
        file: File,
        mimeType: String,
        checkpoint: () -> Unit = {},
    ): Int {
        val rotationDegrees = readRotationDegrees(file)
        val parent = file.parentFile ?: throw FileException(FileError.Corrupt)
        val stripped = try {
            File.createTempFile(STRIPPED_TEMP_PREFIX, STRIPPED_TEMP_SUFFIX, parent)
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
        try {
            val changed = try {
                ImageMetadataStripper.stripFile(file, stripped, mimeType, checkpoint)
            } catch (failure: OutOfMemoryError) {
                throw FileException(FileError.TooLarge, failure)
            } catch (failure: IOException) {
                throw failure.toFileException()
            }
            checkpoint()
            if (changed) {
                try {
                    DurablePrivateFilePublication.syncFile(stripped)
                } catch (failure: IOException) {
                    throw failure.toFileException()
                }
                checkpoint()
                replaceAtomically(stripped, file)
                try {
                    DurablePrivateFilePublication.syncFile(file)
                    DurablePrivateFilePublication.syncParentAfterRename(file)
                } catch (failure: IOException) {
                    throw failure.toFileException()
                }
            } else {
                // El original puede provenir de una escritura aún no sincronizada. Aunque no se
                // necesite reemplazarlo, la frontera del scrub no retorna bytes volátiles.
                try {
                    DurablePrivateFilePublication.syncFile(file)
                } catch (failure: IOException) {
                    throw failure.toFileException()
                }
            }
            checkpoint()
        } finally {
            stripped.delete()
        }
        return rotationDegrees
    }

    private fun replaceAtomically(source: File, destination: File) {
        try {
            DurablePrivateFilePublication.replaceByRename(source, destination)
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
    }

    /**
     * Solo lectura: la orientación EXIF antes de la limpieza. Un archivo ilegible es
     * [FileError.Corrupt]; la orientación espejada (valores 2, 4, 5 y 7) se normaliza a su
     * rotación base porque los metadatos de dominio solo expresan rotación.
     */
    private fun readRotationDegrees(file: File): Int = try {
        exifOrientationToDegrees(
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ),
        )
    } catch (failure: IOException) {
        throw FileException(FileError.Corrupt, failure)
    }

    // Comparte el prefijo del barrido de importación: un SIGKILL durante el scrub no deja un
    // temporal permanente en la raíz privada.
    private const val STRIPPED_TEMP_PREFIX = "import-metadata-stripped-"
    private const val STRIPPED_TEMP_SUFFIX = ".tmp"
}

/** Traduce un valor de orientación EXIF (1 a 8) a grados de rotación (0, 90, 180 o 270). */
internal fun exifOrientationToDegrees(exifOrientation: Int): Int = when (exifOrientation) {
    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
    else -> 0
}
