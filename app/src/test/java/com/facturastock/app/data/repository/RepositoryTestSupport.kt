package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceImage
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Reloj controlable para las pruebas instrumentadas de repositorios Room. */
internal class TestClock(private var current: Instant) : AppClock {
    override fun now(): Instant = current

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}

/** Dispatchers reales de E/S para construir los repositorios Room sin Hilt. */
internal val testDispatchers = object : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}

/** Espera (máx. 5 s) la primera emisión que cumple [predicate]; Room reemite de forma asíncrona. */
internal suspend fun <T> Flow<T>.awaitMatching(predicate: (T) -> Boolean): T =
    withTimeout(5_000) { first { predicate(it) } }

/**
 * Inserta metadatos de imagen exclusivamente para preparar pruebas Room. No publica recibos ni
 * sustituye la API productiva validada de captura; deja el padre en el mismo estado que tendría
 * tras recibir al menos una página.
 */
internal suspend fun FacturaStockDatabase.seedImageFixture(
    image: InvoiceImage,
    capturedAt: Instant,
): InvoiceImage {
    val stamped = image.copy(createdAt = capturedAt)
    invoiceImageDao().insert(stamped.toFixtureEntity())
    check(
        invoiceDraftDao().resetAfterImageMutation(
            draftId = stamped.draftId.value,
            createdStatus = DraftStatus.CREATED.name,
            capturedStatus = DraftStatus.CAPTURED.name,
            errorStatus = DraftStatus.ERROR.name,
            updatedAt = capturedAt.toEpochMilli(),
        ) == 1,
    ) { "El fixture requiere un borrador mutable" }
    return stamped
}

private fun InvoiceImage.toFixtureEntity(): InvoiceImageEntity = InvoiceImageEntity(
    imageId = imageId.value,
    draftId = draftId.value,
    businessId = businessId.value,
    pageIndex = pageIndex,
    filePath = filePath,
    sha256 = sha256,
    mimeType = mimeType,
    widthPx = widthPx,
    heightPx = heightPx,
    fileSizeBytes = fileSizeBytes,
    createdAt = createdAt.toEpochMilli(),
    rotationDegrees = rotationDegrees,
    cropLeftFraction = crop?.left,
    cropTopFraction = crop?.top,
    cropRightFraction = crop?.right,
    cropBottomFraction = crop?.bottom,
)
