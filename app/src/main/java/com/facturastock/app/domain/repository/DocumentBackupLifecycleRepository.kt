package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant

/** Intenciones locales de privacidad; siempre se vuelven durables antes de borrar archivos. */
interface DocumentBackupLifecycleRepository {
    /**
     * `true` solo si Room conserva un ACK COMPLETED de subida fijado a tenant y no existe una
     * intención de purga para la misma imagen. Es la única habilitación local de una lectura
     * remota; nunca se infiere desde la mera existencia de la compra o del archivo.
     */
    suspend fun isBackedUp(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        imageId: ImageId,
    ): Boolean = false

    suspend fun ensurePurge(
        businessId: BusinessId,
        image: RetainedImageRef,
        requestedAt: Instant,
    ): DocumentPurgeIntentResult

    /**
     * Crea candidatos faltantes para fotos de compras anteriores al opt-in. Con corte no nulo
     * solo son elegibles compras posteriores a ese instante (el borde exacto ya venció).
     */
    suspend fun ensureRetainedUploads(
        businessId: BusinessId,
        postedAfterExclusive: Instant?,
        requestedAt: Instant,
    ): Int

    /**
     * Cancela candidatos abiertos y encadena tombstones para cualquier intento posible, incluso
     * si una compuerta de consentimiento acaba de marcar el upload como suprimido.
     */
    suspend fun withdrawOpenUploads(businessId: BusinessId, requestedAt: Instant): Int

    /**
     * Aplica el opt-out global a todas las identidades locales dentro de una sola transacción.
     * No depende del negocio visible: una cuenta puede conservar uploads de negocios históricos.
     * El commit de tombstones no se revierte ni se oculta por un fallo posterior al barrer `.fse`;
     * ese trabajo local se reintenta por el mantenimiento de privacidad.
     */
    suspend fun withdrawAllOpenUploads(requestedAt: Instant): Int = 0

    /**
     * Barre derivados `.fse` que ya no respaldan un upload abierto. Es local, idempotente y no
     * espera conectividad ni el ACK de una purga remota.
     */
    suspend fun sweepOrphanedPreparedArtifacts(): DocumentUploadArtifactSweepReport =
        DocumentUploadArtifactSweepReport()
}

enum class DocumentPurgeIntentResult {
    /** La fila PURGE existe en Room antes de que el caller pueda borrar el archivo local. */
    DURABLE,

    /**
     * La fila PURGE ya es durable, pero no pudo confirmarse el borrado del JPEG derivado cifrado.
     * El caller puede retirar el original y despertar la purga remota, pero debe mostrar trabajo
     * local pendiente; el sweep/reinicio volverá a intentar el `.fse` idempotentemente.
     */
    DURABLE_ARTIFACT_RETRY_REQUIRED,

    /** Nunca hubo candidato de subida, por lo que ningún objeto remoto pudo originarse aquí. */
    NOT_REQUIRED,

    /**
     * Una operación legacy pudo salir antes de que Room fijara su tenant cloud. No es seguro
     * adivinar el destino ni borrar la fuente local; requiere revisión humana asistida.
     */
    LEGACY_DESTINATION_UNKNOWN,
}

object DisabledDocumentBackupLifecycleRepository : DocumentBackupLifecycleRepository {
    override suspend fun ensurePurge(
        businessId: BusinessId,
        image: RetainedImageRef,
        requestedAt: Instant,
    ): DocumentPurgeIntentResult = DocumentPurgeIntentResult.NOT_REQUIRED

    override suspend fun ensureRetainedUploads(
        businessId: BusinessId,
        postedAfterExclusive: Instant?,
        requestedAt: Instant,
    ): Int = 0

    override suspend fun withdrawOpenUploads(
        businessId: BusinessId,
        requestedAt: Instant,
    ): Int = 0

    override suspend fun withdrawAllOpenUploads(requestedAt: Instant): Int = 0
}
