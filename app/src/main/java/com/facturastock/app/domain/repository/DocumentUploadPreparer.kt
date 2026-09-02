package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.ImageId

/** Metadatos Room del original; la ruta nunca sale del dispositivo. */
data class DocumentUploadSource(
    val imageId: String,
    val sourceSha256: String,
    val relativeFilePath: String,
    val mimeType: String,
    val rotationDegrees: Int,
)

/** JPEG derivado y acotado que sí puede enviarse al callable documental. */
data class PreparedDocumentUpload(
    val content: ByteArray,
    val sha256: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(content.isNotEmpty() && content.size <= MAX_UPLOAD_BYTES)
        require(SHA256.matches(sha256))
        require(widthPx in 1..MAX_DIMENSION && heightPx in 1..MAX_DIMENSION)
        require(widthPx.toLong() * heightPx.toLong() <= MAX_PIXELS)
    }

    companion object {
        const val MAX_UPLOAD_BYTES: Int = 2 * 1024 * 1024
        const val MAX_DIMENSION: Int = 4096
        const val MAX_PIXELS: Long = 16_000_000L
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Genera un JPEG privado sin modificar el original. La implementación persiste únicamente el
 * derivado cifrado para que un ACK perdido repita exactamente los mismos bytes.
 */
interface DocumentUploadPreparer {
    suspend fun prepare(source: DocumentUploadSource): PreparedDocumentUpload?

    /** Limpieza idempotente y verificable del derivado después de ACK, cancelación o purga. */
    suspend fun discard(imageId: String): PrivateImageDeletionResult

    /**
     * Retira `.fse` que ya no corresponden a un upload abierto. Los IDs de [retainedImageIds]
     * vienen de Room; ningún nombre de archivo se interpreta como autoridad de negocio.
     */
    suspend fun sweepOrphans(
        retainedImageIds: Set<ImageId>,
    ): DocumentUploadArtifactSweepReport = DocumentUploadArtifactSweepReport()
}

/** Resultado cerrado del barrido local de derivados documentales cifrados. */
data class DocumentUploadArtifactSweepReport(
    val attempted: Int = 0,
    val deleted: Int = 0,
    val alreadyAbsent: Int = 0,
    val failed: Int = 0,
) {
    init {
        require(attempted >= 0 && deleted >= 0 && alreadyAbsent >= 0 && failed >= 0)
        require(attempted == deleted + alreadyAbsent + failed)
    }
}

/** Error cerrado: nunca conserva el OOM ni contenido/ruta como causa o mensaje. */
class DocumentUploadPreparationException(
    val error: DocumentUploadPreparationError,
) : RuntimeException(error.wireCode)

enum class DocumentUploadPreparationError(val wireCode: String) {
    RESOURCE_LIMIT_EXCEEDED("DOCUMENT_PREPARATION_RESOURCE_LIMIT"),
}

/** Convierte agotamiento de memoria en un error cerrado, sin conservar el `Error` como causa. */
internal suspend fun <T> guardDocumentUploadPreparation(
    block: suspend () -> T,
): T = try {
    block()
} catch (_: OutOfMemoryError) {
    throw DocumentUploadPreparationException(
        DocumentUploadPreparationError.RESOURCE_LIMIT_EXCEEDED,
    )
}

/**
 * Límite verificable anterior a cualquier decode de bitmap. Android respeta `inSampleSize`
 * potencia de dos; aun una fuente válida de 8.000×8.000 se decodifica a como máximo cuatro
 * millones de píxeles (aprox. 16 MiB en ARGB_8888), no a su buffer completo de ~256 MiB.
 */
internal object DocumentUploadDecodePolicy {
    const val MAX_SOURCE_BYTES: Int = 15 * 1024 * 1024
    const val MAX_SOURCE_DIMENSION: Int = 8_000
    const val MAX_DECODED_PIXELS: Long = 4_000_000L
    private const val MAX_SAMPLE_SIZE: Int = 32

    fun acceptsSourceDimensions(width: Int, height: Int): Boolean =
        width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION

    fun sampleSize(width: Int, height: Int): Int {
        require(acceptsSourceDimensions(width, height))
        var sample = 1
        while (sampledPixelCount(width, height, sample) > MAX_DECODED_PIXELS) {
            if (sample >= MAX_SAMPLE_SIZE) return MAX_SAMPLE_SIZE
            sample *= 2
        }
        return sample
    }

    fun sampledPixelCount(width: Int, height: Int, sampleSize: Int): Long {
        require(width > 0 && height > 0 && sampleSize > 0)
        return ceilDiv(width, sampleSize) * ceilDiv(height, sampleSize)
    }

    private fun ceilDiv(value: Int, divisor: Int): Long =
        (value.toLong() + divisor.toLong() - 1L) / divisor.toLong()
}

/** Default seguro para construcciones aisladas: nunca prepara y no inventa archivos existentes. */
object DisabledDocumentUploadPreparer : DocumentUploadPreparer {
    override suspend fun prepare(source: DocumentUploadSource): PreparedDocumentUpload? = null

    override suspend fun discard(imageId: String): PrivateImageDeletionResult =
        PrivateImageDeletionResult.ALREADY_ABSENT
}
