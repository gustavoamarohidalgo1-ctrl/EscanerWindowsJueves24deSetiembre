package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.CaptureImagePolicy
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import kotlinx.coroutines.flow.Flow

/**
 * Resultado durable de eliminar una página del agregado. [image] es exactamente el registro
 * retirado y [draftIsEmpty] refleja el conteo realizado dentro de la misma mutación atómica.
 */
data class DeletedDraftImage(
    val image: InvoiceImage,
    val draftIsEmpty: Boolean,
)

/**
 * Página ya validada en filesystem, todavía sin posición. La posición se resuelve dentro de la
 * misma transacción que publica la fila para que reordenados/eliminaciones concurrentes no puedan
 * convertir una intención por [ImageId] en un reemplazo por índice obsoleto.
 */
data class CapturedPageWrite(
    val imageId: ImageId,
    val draftId: DraftId,
    val businessId: BusinessId,
    val filePath: String,
    val sha256: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
    val rotationDegrees: Int = 0,
) {
    /**
     * Valida la frontera entre el archivo privado ya publicado y Room. El importador productivo
     * genera exactamente esta ruta; volver a comprobarla aquí impide que un fake, adaptador roto
     * o implementación futura persista una ruta fuera del namespace de la captura o una
     * extensión que contradiga el MIME.
     */
    fun validationFailure(): String? {
        if (!CaptureImagePolicy.isMimeTypeAllowed(mimeType) || mimeType != mimeType.lowercase()) {
            return "MIME de captura no admitido o no canónico"
        }
        if (!CaptureImagePolicy.areDimensionsAllowed(widthPx, heightPx)) {
            return "Dimensiones de captura fuera de política"
        }
        if (fileSizeBytes <= 0L || !CaptureImagePolicy.isSizeAllowed(fileSizeBytes)) {
            return "Tamaño de captura fuera de política"
        }
        if (rotationDegrees !in VALID_ROTATIONS) return "Rotación de captura inválida"
        if (!SHA_256.matches(sha256)) return "SHA-256 de captura inválido"

        val extension = when (mimeType) {
            "image/jpeg" -> "(?:jpg|jpeg)"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> return "MIME de captura no admitido"
        }
        val expected = Regex(
            "^draft_images/${Regex.escape(draftId.value)}/" +
                "${Regex.escape(imageId.value)}\\.$extension${'$'}",
        )
        if (!expected.matches(filePath)) return "Ruta de captura fuera del namespace esperado"
        return null
    }

    private companion object {
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

/** Intención durable de publicación. Nunca se representa REPLACE mediante un índice mutable. */
sealed interface CapturedPageIntent {
    data object Append : CapturedPageIntent

    data class Replace(val targetImageId: ImageId) : CapturedPageIntent

    /**
     * Repite la única foto del escaneo rápido después de que OCR ya publicó resultados.
     *
     * A diferencia de [Replace], no transporta un índice ni un ID elegido por la UI: el
     * repositorio exige y resuelve exactamente una página dentro de la misma transacción. Esto
     * permite volver a fotografiar una factura fallida sin abrir la mutación de borradores OCR a
     * las rutas multipágina legacy.
     */
    data object ReplaceSoleInvoiceScan : CapturedPageIntent
}

/**
 * Resultado exacto del commit o replay. [replacedFilePath] también vive en el recibo, de modo
 * que un reinicio entre el commit Room y la compensación de filesystem pueda reintentar ese
 * borrado sin reconstruir la fila sustituida.
 */
data class PublishedCapturedPage(
    val image: InvoiceImage,
    val replacedFilePath: String?,
)

/**
 * Puerto raíz del agregado de borrador de factura: cubre el borrador, sus imágenes y sus
 * líneas, coherente con el borrado en cascada. Las implementaciones estampan las marcas de
 * tiempo (`createdAt`/`updatedAt` en create, `updatedAt` en update) y tocan el `updatedAt`
 * del borrador padre en la misma transacción ante cualquier mutación de imágenes o líneas.
 * Si el borrador estaba `READY_TO_POST`, toda mutación efectiva del borrador o sus líneas borra
 * la compra preparada y lo devuelve a `NEEDS_REVIEW`. Cualquier mutación de páginas invalida
 * además snapshot, parser, formularios y líneas derivados, y vuelve a `CAPTURED` (`CREATED` si
 * ya no quedan páginas) en esa misma transacción. Solo CREATED/CAPTURED/ERROR admiten cambiar
 * páginas: después de publicar OCR pueden haberse eliminado los originales y el draft se cierra.
 * Los fallos de disco llegan como `StorageException`.
 */
interface InvoiceDraftRepository {
    /** Inserta el borrador estampando `createdAt`/`updatedAt`; devuelve el registro persistido. */
    suspend fun createDraft(draft: InvoiceDraft): InvoiceDraft

    /**
     * Actualiza el borrador estampando `updatedAt`; devuelve false si no existía. Un update de
     * un borrador preparado se interpreta como edición y no puede conservar `READY_TO_POST`.
     */
    suspend fun updateDraft(draft: InvoiceDraft): Boolean

    /**
     * Reclama atómicamente un borrador CAPTURED/ERROR para una ejecución OCR. Falla si el
     * borrador ya avanzó, fue confirmado o pertenece a otra ejecución.
     */
    suspend fun beginOcrRun(draftId: DraftId, runId: OcrRunId): Boolean

    /**
     * Publica o abandona una ejecución únicamente si [runId] sigue siendo el token activo.
     * [newStatus] solo puede ser CAPTURED, OCR_READY o ERROR.
     */
    suspend fun finishOcrRun(
        draftId: DraftId,
        runId: OcrRunId,
        newStatus: DraftStatus,
        lastError: String? = null,
    ): Boolean

    /**
     * Recupera una ejecución interrumpida solo si el token persistido sigue siendo exactamente
     * [expectedRunId]. El valor nulo cubre borradores OCR_PROCESSING creados antes del token.
     */
    suspend fun resetInterruptedOcr(
        draftId: DraftId,
        expectedRunId: OcrRunId?,
    ): Boolean

    suspend fun findDraft(draftId: DraftId): InvoiceDraft?

    /** Emite el borrador (o null si no existe) ante cada cambio. */
    fun observeDraft(draftId: DraftId): Flow<InvoiceDraft?>

    /**
     * Emite los borradores del negocio ordenados por `updatedAt` descendente; con [status]
     * distinto de null filtra por estado.
     */
    fun observeDrafts(businessId: BusinessId, status: DraftStatus?): Flow<List<InvoiceDraft>>

    /** Elimina el borrador con sus imágenes y líneas en cascada; devuelve false si no existía. */
    suspend fun deleteDraft(draftId: DraftId): Boolean

    /**
     * Publica una página recién capturada como una sola mutación del agregado. Con ID de
     * reemplazo localiza esa fila dentro de la transacción y conserva su posición; sin ID añade
     * al final calculado dentro del mismo snapshot Room. [CapturedPageIntent.ReplaceSoleInvoiceScan]
     * es la única excepción posterior a OCR: exige una sola página y un borrador abierto en
     * OCR_READY/NEEDS_REVIEW (o en CAPTURED/ERROR), invalida todo derivado y vuelve a
     * [DraftStatus.CAPTURED]. Las intenciones legacy siguen rechazando estados posteriores a OCR
     * y todo borrador confirmado.
     */
    suspend fun publishCapturedPage(
        page: CapturedPageWrite,
        intent: CapturedPageIntent,
    ): PublishedCapturedPage

    /**
     * Resuelve un replay antes de volver a abrir el origen. Devuelve null solo cuando el ID nunca
     * se consumió; una intención distinta, un ID legacy ya ocupado o una publicación eliminada
     * fallan de forma controlada.
     */
    suspend fun findPublishedCapturedPage(
        imageId: ImageId,
        draftId: DraftId,
        businessId: BusinessId,
        intent: CapturedPageIntent,
    ): PublishedCapturedPage?

    /**
     * Incrementa atómicamente la rotación persistida en 90° (módulo 360) y transforma el recorte
     * vigente al nuevo marco sin leer/copiar la entidad fuera de la transacción. Valida el estado
     * mutable, invalida los derivados OCR durables y toca el borrador en esa misma transacción.
     * Devuelve null si la imagen no existe.
     */
    suspend fun rotateImage90(imageId: ImageId): InvoiceImage?

    /**
     * Fija atómicamente únicamente el recorte normalizado de la página. No reescribe la rotación
     * ni ningún otro metadato; valida el estado mutable, invalida los derivados OCR durables y
     * toca el borrador dentro de la misma transacción. Devuelve null si la imagen no existe.
     */
    suspend fun setImageCrop(imageId: ImageId, crop: ImageCrop?): InvoiceImage?

    /** Emite las imágenes del borrador ordenadas por `pageIndex` ante cada cambio. */
    fun observeImages(draftId: DraftId): Flow<List<InvoiceImage>>

    /** Búsqueda puntual de una imagen por su ID; null si no existe. */
    suspend fun findImage(imageId: ImageId): InvoiceImage?

    /** Verifica por ruta exacta todas las filas, para que una compensación no borre un alias. */
    suspend fun isImagePathReferenced(filePath: String): Boolean

    /** Cuenta todas las filas que comparten la ruta exacta, incluidas referencias legacy. */
    suspend fun countImagePathReferences(filePath: String): Int

    /** Snapshot exacto de identidades que comparten una ruta, en una única lectura Room. */
    suspend fun listImageIdsReferencingPath(filePath: String): Set<ImageId>

    /**
     * Mueve [imageId] exactamente un lugar usando el orden leído dentro de la misma transacción.
     * Dos intenciones concurrentes se serializan y componen sobre el resultado confirmado de la
     * anterior; devuelve el orden final con índices 0..n-1. Un extremo es un no-op.
     */
    suspend fun moveImageOneStep(
        draftId: DraftId,
        imageId: ImageId,
        moveUp: Boolean,
    ): List<InvoiceImage>

    /**
     * Elimina la imagen, reindexa las páginas restantes a 0..n-1 conservando su orden y
     * toca el `updatedAt` e invalida el OCR anterior en la misma transacción. Si era la última
     * página devuelve el borrador a `CREATED`; en otro caso lo devuelve a `CAPTURED`.
     * Devuelve el registro eliminado y si el borrador quedó vacío, o null si no existía.
     */
    suspend fun deleteImage(imageId: ImageId): DeletedDraftImage?

    /**
     * Inserta la línea estampando `createdAt`/`updatedAt` y toca el `updatedAt` del borrador
     * en la misma transacción; devuelve el registro persistido.
     */
    suspend fun addLine(line: InvoiceLine): InvoiceLine

    /**
     * Actualiza la línea estampando `updatedAt` y toca el `updatedAt` del borrador en la misma
     * transacción; devuelve false si no existía.
     */
    suspend fun updateLine(line: InvoiceLine): Boolean

    /**
     * Reemplaza atómicamente todas las líneas del borrador: elimina las actuales e inserta
     * [lines] reasignando `position` de 0 a n-1 según el orden de la lista. Estampa
     * `updatedAt` en cada línea y toca el `updatedAt` del borrador en la misma transacción.
     */
    suspend fun replaceLines(draftId: DraftId, lines: List<InvoiceLine>)

    /**
     * Reordena atómicamente las líneas del borrador. [orderedLineIds] debe contener
     * exactamente los IDs de las líneas actuales; reasigna `position` de 0 a n-1 según ese
     * orden y toca el `updatedAt` del borrador en la misma transacción.
     */
    suspend fun reorderLines(draftId: DraftId, orderedLineIds: List<LineId>)

    /** Emite las líneas del borrador ordenadas por `position` ante cada cambio. */
    fun observeLines(draftId: DraftId): Flow<List<InvoiceLine>>

    /**
     * Elimina la línea y toca el `updatedAt` del borrador en la misma transacción; devuelve
     * false si no existía.
     */
    suspend fun deleteLine(lineId: LineId): Boolean

    /** Proyección compacta usada como prefiltro del barrido; cada ausencia se revalida puntual. */
    suspend fun listAllDraftIds(): Set<DraftId>

    /**
     * Imágenes originales de TODOS los borradores todavía abiertos, sin limitar al negocio
     * seleccionado. El borrado manual de privacidad usa esta consulta porque esas fuentes nunca
     * pudieron subir como documentos de una compra terminal y no necesitan tombstone cloud.
     */
    suspend fun listOpenDraftImages(): List<InvoiceImage>

    /**
     * Imágenes originales de borradores abiertos cuyo snapshot OCR durable cubre todas las
     * páginas activas. Permite reparar AFTER_OCR si el hook inicial falló, la política cambió
     * después o el proceso reinició. Excluye el fallback manual sin snapshot y los borradores
     * confirmados: sus imágenes se coordinan desde el registro de compra.
     */
    suspend fun listImagesWithPublishedOcr(): List<InvoiceImage>

    /** Prefiltro compacto de borradores confirmados; el estado se revalida antes de borrar OCR. */
    suspend fun listCommittedDraftIds(): Set<DraftId>

}
