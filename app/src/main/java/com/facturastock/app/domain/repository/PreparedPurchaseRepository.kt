package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.id.DraftId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Resultado de publicar una instantánea preparada. */
enum class PublishPreparedPurchaseResult {
    /** Insertada y el borrador pasó a READY_TO_POST en la misma transacción. */
    PREPARED,

    /** Ya existía una instantánea con el mismo hash lógico; no se reescribe nada. */
    ALREADY_PREPARED,

    /** El borrador o las revisiones validadas cambiaron: otro flujo ganó la carrera. */
    CONFLICT,
}

/**
 * Puerto de la instantánea de compra preparada. Hay como máximo una por borrador. Las
 * implementaciones garantizan en una única transacción: publicar = insertar payload + CAS
 * `NEEDS_REVIEW → READY_TO_POST`, verificando las revisiones esperadas; reabrir = borrar
 * payload + CAS `READY_TO_POST → NEEDS_REVIEW`.
 */
interface PreparedPurchaseRepository {
    suspend fun find(draftId: DraftId): PreparedPurchase?

    /** Instantánea individual observable; Room invalida la Flow al preparar o reabrir. */
    fun observe(draftId: DraftId): Flow<PreparedPurchase?> = flow { emit(find(draftId)) }

    suspend fun publish(
        purchase: PreparedPurchase,
        expectedDraftUpdatedAt: Instant,
        expectedHeaderRevision: Long,
        expectedLinesRevision: Long,
    ): PublishPreparedPurchaseResult

    /**
     * Invalida la preparación: borra la instantánea y devuelve el borrador a NEEDS_REVIEW.
     * Devuelve false si el borrador no estaba preparado.
     */
    suspend fun reopenForEdit(draftId: DraftId): Boolean
}
