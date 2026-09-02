package com.facturastock.app.domain.usecase

import com.facturastock.app.core.coroutines.SuspendMutex
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.DemoInvoiceSource
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

sealed interface StartDemoInvoiceScenarioResult {
    data class Ready(val draftId: DraftId, val captureId: CaptureId) : StartDemoInvoiceScenarioResult
    data class AlreadyCompleted(val purchaseId: PurchaseId) : StartDemoInvoiceScenarioResult
    data object NotInDemoMode : StartDemoInvoiceScenarioResult
    data object Conflict : StartDemoInvoiceScenarioResult
}

/**
 * Simula la captura por la misma frontera usada por cámara: genera el JPEG sintético, lo importa
 * con [ImportDraftImageUseCase] y devuelve la vista previa. Materializa primero el borrador
 * `CREATED`, igual que el flujo de cámara/galería. El borrador es estable por negocio y el mutex
 * hace idempotente el doble toque dentro del proceso.
 */
class StartDemoInvoiceScenarioUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val demoInvoiceSource: DemoInvoiceSource,
    private val importDraftImageUseCase: ImportDraftImageUseCase,
    private val appClock: AppClock,
) {
    private val mutex = SuspendMutex()

    suspend operator fun invoke(): StartDemoInvoiceScenarioResult = mutex.withLock {
        val configuration = appConfigurationRepository.current()
        val businessId = configuration.demoBusinessId
            ?.takeIf { it == configuration.activeBusinessId }
            ?: return@withLock StartDemoInvoiceScenarioResult.NotInDemoMode
        val draftId = DemoPurchaseScenario.draftIdFor(businessId)
        val draft = try {
            invoiceDraftRepository.findDraft(draftId)
                ?: try {
                    val now = appClock.now()
                    invoiceDraftRepository.createDraft(
                        InvoiceDraft(
                            draftId = draftId,
                            businessId = businessId,
                            status = DraftStatus.CREATED,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Una segunda instancia/proceso pudo ganar el mismo ID estable. Releer hace
                    // idempotente esa colisión sin ocultar un fallo si la fila sigue ausente.
                    invoiceDraftRepository.findDraft(draftId)
                        ?: return@withLock StartDemoInvoiceScenarioResult.Conflict
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock StartDemoInvoiceScenarioResult.Conflict
        }
        if (draft.businessId != businessId) {
            return@withLock StartDemoInvoiceScenarioResult.Conflict
        }
        draft.confirmedPurchaseId?.let { purchaseId ->
            return@withLock StartDemoInvoiceScenarioResult.AlreadyCompleted(purchaseId)
        }
        if (draft.status == DraftStatus.COMMITTED) {
            return@withLock StartDemoInvoiceScenarioResult.Conflict
        }
        val images = invoiceDraftRepository.observeImages(draftId).first()
        if (images.size > 1) return@withLock StartDemoInvoiceScenarioResult.Conflict
        if (images.isEmpty() && draft.status != DraftStatus.CREATED) {
            return@withLock StartDemoInvoiceScenarioResult.Conflict
        }
        val image = images.singleOrNull() ?: try {
            importDraftImageUseCase(
                draftId = draftId,
                jpegBytes = demoInvoiceSource.jpegBytes(),
                rotationDegrees = 0,
                preferredImageId = DemoPurchaseScenario.imageIdFor(businessId),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock StartDemoInvoiceScenarioResult.Conflict
        }
        val captureId = CaptureId.from(UUID.fromString(image.imageId.value))
        StartDemoInvoiceScenarioResult.Ready(draftId, captureId)
    }
}
