package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.BusinessAuditEventRead
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseHistoryPage
import com.facturastock.app.domain.model.PurchaseHistoryRequest
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import kotlinx.coroutines.flow.Flow

/** Consultas reactivas offline-first; ninguna pantalla de compras lee directamente de red. */
interface PurchaseReadRepository {
    /**
     * Flujo completo y sin límite. Es deliberadamente independiente de la paginación visual:
     * exportación, privacidad y coordinadores de dominio deben poder recorrer toda la historia.
     */
    fun observePurchases(businessId: BusinessId): Flow<List<PurchaseReadSummary>>

    /**
     * Ventana reactiva exclusiva de la UI. La implementación Room debe avanzar con keyset estable
     * y mantener memoria acotada incluso cuando la búsqueda textual salte muchas filas.
     */
    fun observePurchaseHistory(
        businessId: BusinessId,
        request: PurchaseHistoryRequest,
    ): Flow<PurchaseHistoryPage>

    /** Devuelve null si la compra no existe o pertenece a otro negocio. */
    fun observePurchase(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): Flow<PurchaseReadDetail?>

    /**
     * Lista plana de las imágenes que siguen retenidas para las compras terminales (POSTED y
     * VOIDED comparten la misma política de retención) del negocio. Alimenta el mantenimiento
     * de privacidad: retención por antigüedad y migración de cifrado en reposo. Las filas de
     * la base de datos jamás se borran por retención; solo los archivos referenciados.
     */
    suspend fun listRetainedImagesForRetention(businessId: BusinessId): List<RetainedImageRef>

    /** Páginas de la compra terminal originada por [draftId], para cifrado post-commit inmediato. */
    suspend fun listRetainedImagesForDraft(
        businessId: BusinessId,
        draftId: DraftId,
    ): List<RetainedImageRef>

    /**
     * Variante global para la política de privacidad de la instalación y el borrado manual.
     * Cada referencia conserva su businessId para crear la purga en el tenant correcto.
     */
    suspend fun listAllRetainedImagesForRetention(): List<RetainedImageRef>

    /** Auditoría completa del negocio, incluida la que no pertenece a una compra. */
    suspend fun listAuditEvents(businessId: BusinessId): List<BusinessAuditEventRead>
}
