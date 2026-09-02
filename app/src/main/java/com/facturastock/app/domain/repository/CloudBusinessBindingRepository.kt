package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.id.BusinessId
import java.time.Instant

/** Resultado cerrado de fijar el tenant remoto de un negocio local. */
sealed interface CloudBusinessBindingResult {
    /** Se creó el vínculo y cualquier operación offline nunca intentada quedó fijada a él. */
    data object Bound : CloudBusinessBindingResult

    /** El mismo vínculo ya existía; no se modificó nada. */
    data object AlreadyBound : CloudBusinessBindingResult

    /** El negocio local ya pertenece a otro tenant cloud y no se puede redirigir. */
    data object LocalBusinessAlreadyBound : CloudBusinessBindingResult

    /** Ese tenant cloud ya está fijado a otro negocio local de este dispositivo. */
    data object CloudBusinessAlreadyBound : CloudBusinessBindingResult

    /** Hay historia v19 que pudo haber salido a un destino desconocido; requiere migración manual. */
    data object LegacyDestinationUnknown : CloudBusinessBindingResult

    /** Dos almacenes locales representan la misma identidad compartida; hay que renombrarlos. */
    data object AmbiguousInventoryLocations : CloudBusinessBindingResult
}

/**
 * Autoridad Room del enlace local ↔ cloud. DataStore conserva sesión/rol, pero nunca decide el
 * destino de un libro: este vínculo se crea una sola vez y no se borra al cerrar sesión.
 */
interface CloudBusinessBindingRepository {
    suspend fun bindOnce(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        boundAt: Instant,
    ): CloudBusinessBindingResult

    suspend fun targetFor(localBusinessId: BusinessId): BusinessId?

    suspend fun matches(localBusinessId: BusinessId, cloudBusinessId: BusinessId): Boolean
}
