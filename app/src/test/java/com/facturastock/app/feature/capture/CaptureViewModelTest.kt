package com.facturastock.app.feature.capture

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeDraftImageImporter
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.RecordingProductionObservability
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = AppClock { Instant.parse("2026-08-01T12:00:00Z") }
    private val appConfig = FakeAppConfigurationRepository()
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val importer = FakeDraftImageImporter()
    private val observability = RecordingProductionObservability()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `an invalid route draft id fails the state and Start closes the route`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(mapOf("draftId" to "no-es-un-uuid")),
            )
            assertEquals(
                CaptureContract.Failure.INVALID_DRAFT_ID,
                viewModel.uiState.value.failure,
            )

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.Start)
                runCurrent()
                assertEquals(CaptureContract.Effect.CloseInvalidRoute, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a Start with a valid draft id emits nothing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraft(DraftStatus.CREATED)
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a canonical camera route whose draft is absent closes from Room exactly once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.Start)
                runCurrent()
                assertEquals(CaptureContract.Effect.CloseInvalidRoute, awaitItem())
                viewModel.onAction(CaptureContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a fresh add-page camera route over CAPTURED data does not bounce to processing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness(materializeDraft = false)
            seedDraft(DraftStatus.CREATED)
            useCaseForTests()(DRAFT_ID, JPEG_BYTES, ROTATION_DEGREES)
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a camera ViewModel restored after commit opens processing exactly once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness(materializeDraft = false)
            seedDraft(DraftStatus.CREATED)
            val savedState = SavedStateHandle(mapOf("draftId" to DRAFT_ID.value))
            val first = createViewModel(savedState)

            first.effects.test {
                first.onAction(CaptureContract.Action.Start)
                runCurrent()
                expectNoEvents()
                first.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                first.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())
                first.onAction(
                    CaptureContract.Action.CaptureSucceeded(JPEG_BYTES, ROTATION_DEGREES),
                )
                runCurrent()
                assertEquals(
                    CaptureContract.Effect.OpenProcessing(DRAFT_ID),
                    awaitItem(),
                )
                // El Flow de Room del commit propio queda suprimido por la marca local/terminal.
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, importer.bytesCalls.size)

            val restored = createViewModel(
                savedStateHandle = savedState,
                viewModelUuidGenerator = UuidGenerator { SECOND_UUID },
            )
            restored.effects.test {
                restored.onAction(CaptureContract.Action.Start)
                runCurrent()
                assertEquals(
                    CaptureContract.Effect.OpenProcessing(DRAFT_ID),
                    awaitItem(),
                )
                restored.onAction(CaptureContract.Action.Start)
                runCurrent()
                expectNoEvents()
                restored.onAction(CaptureContract.Action.ProcessingNavigationHandled)
                runCurrent()
                // Al volver desde Procesamiento la misma instancia vuelve a capturar con otro ID.
                restored.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                restored.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())
                restored.onAction(
                    CaptureContract.Action.CaptureSucceeded(JPEG_BYTES, ROTATION_DEGREES),
                )
                runCurrent()
                assertEquals(
                    CaptureContract.Effect.OpenProcessing(DRAFT_ID),
                    awaitItem(),
                )
                expectNoEvents()
                restored.onAction(CaptureContract.Action.ProcessingNavigationHandled)
                runCurrent()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, importer.bytesCalls.size)

            val fresh = createViewModel(savedState)
            fresh.effects.test {
                fresh.onAction(CaptureContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a double tap on the shutter emits a single CaptureNow`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())
                assertTrue(viewModel.uiState.value.isCapturing)

                // Segundo toque con la captura en curso: se ignora por completo.
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `back invalidates a late CameraX callback before it can persist`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())

                viewModel.onAction(CaptureContract.Action.BackSelected)
                runCurrent()
                assertEquals(CaptureContract.Effect.Back, awaitItem())

                viewModel.onAction(
                    CaptureContract.Action.CaptureSucceeded(JPEG_BYTES, ROTATION_DEGREES),
                )
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(importer.bytesCalls.isEmpty())
            // Salir de Cámara invalida el callback, pero el flujo padre conserva el borrador
            // vacío para permitir abrir la cámara otra vez.
            assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
            assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
        }

    @Test
    fun `a shutter tap before CameraX is ready is ignored`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                expectNoEvents()
                assertFalse(viewModel.uiState.value.isCapturing)

                viewModel.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                assertTrue(viewModel.uiState.value.isCameraReady)
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a capture without a valid draft id emits nothing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(mapOf("draftId" to "no-es-un-uuid")),
            )

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertFalse(viewModel.uiState.value.isCapturing)
        }

    @Test
    fun `a succeeded capture imports the bytes and opens processing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())

                viewModel.onAction(
                    CaptureContract.Action.CaptureSucceeded(JPEG_BYTES, ROTATION_DEGREES),
                )
                runCurrent()

                assertEquals(
                    CaptureContract.Effect.OpenProcessing(DRAFT_ID),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }

            val state = viewModel.uiState.value
            assertFalse(state.isCapturing)
            assertNull(state.cameraError)

            // El borrador preexistente avanzó a CAPTURED con la rotación de la captura.
            val draft = drafts.findDraft(DRAFT_ID)
            assertEquals(DraftStatus.CAPTURED, draft?.status)
            val call = importer.bytesCalls.single()
            assertEquals(DRAFT_ID, call.draftId)
            assertTrue(JPEG_BYTES.contentEquals(call.jpegBytes))
            assertEquals(ROTATION_DEGREES, call.rotationDegrees)
            assertTrue(importer.calls.isEmpty())
            assertEquals(
                ROTATION_DEGREES,
                drafts.observeImages(DRAFT_ID).first().single().rotationDegrees,
            )
            val event = observability.records.single().event
            assertEquals(OperationalAction.INVOICE_CAPTURE, event.action)
            assertEquals(OperationalOutcome.SUCCEEDED, event.outcome)
            assertEquals(BUSINESS_ID, event.identifiers.businessId)
            assertEquals(DRAFT_ID, event.identifiers.draftId)
            assertEquals(GENERATED_UUID.toString(), event.identifiers.imageId?.value)
        }

    @Test
    fun `a file failure during import frees the shutter and persists nothing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())

                importer.nextException = FileException(FileError.InsufficientSpace)
                viewModel.onAction(
                    CaptureContract.Action.CaptureSucceeded(JPEG_BYTES, ROTATION_DEGREES),
                )
                runCurrent()
                expectNoEvents()

                val state = viewModel.uiState.value
                assertFalse(state.isCapturing)
                assertEquals(
                    CaptureContract.CameraErrorKind.STORAGE_FULL,
                    state.cameraError,
                )

                // El obturador vuelve a responder tras el fallo.
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            // La reserva CREATED queda recuperable, sin publicar ninguna imagen.
            assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
        }

    @Test
    fun `a failed takePicture surfaces the error and retrying re-enables the shutter`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())

                viewModel.onAction(CaptureContract.Action.CaptureFailed)
                runCurrent()
                expectNoEvents()
                assertFalse(viewModel.uiState.value.isCapturing)
                assertEquals(
                    CaptureContract.CameraErrorKind.CAPTURE_FAILED,
                    viewModel.uiState.value.cameraError,
                )
                assertTrue(importer.bytesCalls.isEmpty())
                val event = observability.records.single().event
                assertEquals(OperationalAction.INVOICE_CAPTURE, event.action)
                assertEquals(OperationalOutcome.FAILED, event.outcome)
                assertEquals(OperationalErrorCode.CAMERA_CAPTURE_FAILED, event.errorCode)
                assertEquals(DRAFT_ID, event.identifiers.draftId)
                assertEquals(GENERATED_UUID.toString(), event.identifiers.imageId?.value)

                // Reintentar vuelve a emitir CaptureNow y limpia el error.
                viewModel.onAction(CaptureContract.Action.RetryCameraSelected)
                runCurrent()
                assertNull(viewModel.uiState.value.cameraError)

                viewModel.onAction(CaptureContract.Action.CameraReady)
                runCurrent()
                viewModel.onAction(CaptureContract.Action.CaptureClicked)
                runCurrent()
                assertEquals(CaptureContract.Effect.CaptureNow, awaitItem())
                assertTrue(viewModel.uiState.value.isCapturing)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a camera bind failure surfaces the error and retrying clears it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.onAction(CaptureContract.Action.CameraBindFailed)
            runCurrent()
            assertEquals(
                CaptureContract.CameraErrorKind.BIND_FAILED,
                viewModel.uiState.value.cameraError,
            )

            viewModel.onAction(CaptureContract.Action.RetryCameraSelected)
            runCurrent()
            assertNull(viewModel.uiState.value.cameraError)
        }

    @Test
    fun `the flash cycles auto on off and restores from the saved state handle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            assertEquals(CaptureFlashMode.AUTO, viewModel.uiState.value.flashMode)

            viewModel.onAction(CaptureContract.Action.FlashToggled)
            runCurrent()
            assertEquals(CaptureFlashMode.ON, viewModel.uiState.value.flashMode)

            viewModel.onAction(CaptureContract.Action.FlashToggled)
            runCurrent()
            assertEquals(CaptureFlashMode.OFF, viewModel.uiState.value.flashMode)

            viewModel.onAction(CaptureContract.Action.FlashToggled)
            runCurrent()
            assertEquals(CaptureFlashMode.AUTO, viewModel.uiState.value.flashMode)

            // El modo elegido sobrevive a la recreación del ViewModel.
            val restored = createViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        "draftId" to DRAFT_ID.value,
                        "capture.flashMode" to CaptureFlashMode.OFF.name,
                    ),
                ),
            )
            assertEquals(CaptureFlashMode.OFF, restored.uiState.value.flashMode)
        }

    @Test
    fun `a denied permission surfaces the state and granting it later clears it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(
                    CaptureContract.Action.CameraPermissionResult(granted = false),
                )
                runCurrent()
                expectNoEvents()
                assertTrue(viewModel.uiState.value.cameraPermissionDenied)
                val permissionAudit = observability.records.single().event
                assertEquals(OperationalAction.INVOICE_CAPTURE, permissionAudit.action)
                assertEquals(OperationalOutcome.FAILED, permissionAudit.outcome)
                assertEquals(
                    OperationalErrorCode.CAMERA_PERMISSION_DENIED,
                    permissionAudit.errorCode,
                )
                assertEquals(DRAFT_ID, permissionAudit.identifiers.draftId)
                assertNull(permissionAudit.identifiers.imageId)
                assertNull(permissionAudit.identifiers.businessId)
                assertNull(permissionAudit.identifiers.purchaseId)
                assertNull(permissionAudit.identifiers.operationId)

                // "Conceder permiso" pide el diálogo del sistema.
                viewModel.onAction(CaptureContract.Action.RequestPermissionSelected)
                runCurrent()
                assertEquals(CaptureContract.Effect.RequestCameraPermission, awaitItem())

                // Concedido en el diálogo: el estado de denegado se limpia.
                viewModel.onAction(
                    CaptureContract.Action.CameraPermissionResult(granted = true),
                )
                runCurrent()
                assertFalse(viewModel.uiState.value.cameraPermissionDenied)
                assertEquals(1, observability.records.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the denied permission is restored from the saved state handle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        "draftId" to DRAFT_ID.value,
                        "capture.cameraPermissionDenied" to true,
                        "capture.cameraPermissionPermanentlyDenied" to true,
                    ),
                ),
            )

            assertTrue(viewModel.uiState.value.cameraPermissionDenied)
            assertTrue(viewModel.uiState.value.cameraPermissionPermanentlyDenied)
            assertNull(viewModel.uiState.value.failure)
        }

    @Test
    fun `initial denied snapshot preserves restored permanent denial until a real result`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        "draftId" to DRAFT_ID.value,
                        "capture.cameraPermissionDenied" to true,
                        "capture.cameraPermissionPermanentlyDenied" to true,
                    ),
                ),
            )

            viewModel.onAction(CaptureContract.Action.CameraPermissionSnapshot(granted = false))
            runCurrent()

            assertTrue(viewModel.uiState.value.cameraPermissionDenied)
            assertTrue(viewModel.uiState.value.cameraPermissionPermanentlyDenied)

            viewModel.onAction(CaptureContract.Action.CameraPermissionSnapshot(granted = true))
            runCurrent()
            assertFalse(viewModel.uiState.value.cameraPermissionDenied)
            assertFalse(viewModel.uiState.value.cameraPermissionPermanentlyDenied)
        }

    @Test
    fun `a permanently denied permission routes its action to app settings`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(
                    CaptureContract.Action.CameraPermissionResult(
                        granted = false,
                        permanentlyDenied = true,
                    ),
                )
                runCurrent()
                assertTrue(viewModel.uiState.value.cameraPermissionPermanentlyDenied)
                expectNoEvents()

                viewModel.onAction(CaptureContract.Action.RequestPermissionSelected)
                runCurrent()
                assertEquals(CaptureContract.Effect.OpenAppSettings, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `import selection goes back to the source step where the picker lives`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.ImportSelected)
                runCurrent()
                assertEquals(CaptureContract.Effect.Back, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `back selection emits the back effect`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(CaptureContract.Action.BackSelected)
                runCurrent()
                assertEquals(CaptureContract.Effect.Back, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf("draftId" to DRAFT_ID.value),
        ),
        viewModelUuidGenerator: UuidGenerator = UuidGenerator { GENERATED_UUID },
    ): CaptureViewModel = CaptureViewModel(
        savedStateHandle = savedStateHandle,
        importDraftImageUseCase = useCaseForTests(),
        observeInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(drafts),
        observeDraftImagesUseCase = ObserveDraftImagesUseCase(drafts),
        uuidGenerator = viewModelUuidGenerator,
        dispatcherProvider = dispatchers,
        observability = observability,
    )

    private fun useCaseForTests(): ImportDraftImageUseCase = ImportDraftImageUseCase(
        appConfigurationRepository = appConfig,
        invoiceDraftRepository = drafts,
        draftImageImporter = importer,
        draftFileStore = FakeDraftFileStore(),
        uuidGenerator = UuidGenerator { GENERATED_UUID },
        observability = observability,
    )

    private suspend fun seedDraft(status: DraftStatus) {
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = status,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun activateBusiness(materializeDraft: Boolean = true) {
        appConfig.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        if (materializeDraft) seedDraft(DraftStatus.CREATED)
    }

    private companion object {
        val GENERATED_UUID: UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa")
        val SECOND_UUID: UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000d1"),
        )
        const val ROTATION_DEGREES = 90
        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42)
    }
}
