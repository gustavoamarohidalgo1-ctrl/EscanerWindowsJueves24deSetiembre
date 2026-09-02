package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.DraftId

/**
 * Puerto del almacenamiento de archivos de borradores (imágenes capturadas). Las rutas son
 * relativas al almacenamiento privado de la app, las mismas que persisten las entidades de
 * imagen. La eliminación es de mejor esfuerzo: tolera archivos inexistentes y jamás toca
 * rutas fuera del almacenamiento privado.
 */
interface DraftFileStore {
    /**
     * Elimina los archivos indicados en mejor esfuerzo y devuelve un resultado por ruta, en el
     * mismo orden. Un fallo puntual no detiene el resto del lote.
     */
    suspend fun deleteFiles(relativePaths: List<String>): List<PrivateImageDeletionResult>

    /** Elimina todo el árbol privado de un borrador, incluidos derivados regenerables. */
    suspend fun deleteDraftTree(draftId: DraftId)

    /**
     * Toma el lock exclusivo del árbol y evalúa [shouldDelete] ya dentro de él. Solo si devuelve
     * true elimina el árbol antes de soltar el lock. El callback no debe llamar otra operación
     * de este almacén para evitar reentrancia; está pensado para revalidar la ausencia en Room.
     * Devuelve false cuando se omitió o no pudo completarse el borrado.
     */
    suspend fun deleteDraftTreeIf(
        draftId: DraftId,
        /** Revalida aliases legacy de cada hoja después de adquirir el lock del árbol. */
        pathIsReferencedAnywhere: suspend (String) -> Boolean = { false },
        shouldDelete: suspend () -> Boolean,
    ): Boolean

    /** Invalida las versiones OCR sin tocar las imágenes originales. */
    suspend fun deleteOcrVersions(draftId: DraftId)
}
