package com.facturastock.app.feature.source

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
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeDraftImageImporter
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = AppClock { Instant.parse("2026-08-01T12:00:00Z") }
    private val appConfig = FakeAppConfigurationRepository()
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val importer = FakeDraftImageImporter()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `an invalid route draft id fails the state and Start closes the route`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(mapOf("draftId" to "no-es-un-uuid")),
            )
            assertEquals(SourceContract.Failure.INVALID_DRAFT_ID, viewModel.uiState.value.failure)

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.Start)
                runCurrent()
                assertEquals(SourceContract.Effect.CloseInvalidRoute, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a Start with a valid draft id emits nothing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraft(DraftStatus.CREATED)
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a canonical route whose draft is absent closes from the first Room truth`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.Start)
                runCurrent()
                assertEquals(SourceContract.Effect.CloseInvalidRoute, awaitItem())
                viewModel.onAction(SourceContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a fresh add-page route over CAPTURED Room data does not bounce to preview`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness(materializeDraft = false)
            seedDraft(DraftStatus.CREATED)
            useCaseForTests()(DRAFT_ID, PICKED_URI)
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a gallery ViewModel restored after commit opens its stable image exactly once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness(materializeDraft = false)
            seedDraft(DraftStatus.CREATED)
            val savedState = SavedStateHandle(mapOf("draftId" to DRAFT_ID.value))
            val first = createViewModel(savedState)

            first.effects.test {
                first.onAction(SourceContract.Action.Start)
                runCurrent()
                expectNoEvents()

                first.onAction(SourceContract.Action.PickImageSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.LaunchImagePicker, awaitItem())
                first.onAction(SourceContract.Action.ImagePicked(PICKED_URI))
                runCurrent()
                assertEquals(
                    SourceContract.Effect.OpenPreview(
                        DRAFT_ID,
                        CaptureId.from(GENERATED_UUID),
                    ),
                    awaitItem(),
                )
                // La emisión Room del propio commit no genera un segundo efecto.
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, importer.calls.size)

            // Simula muerte antes de que la ruta confirme la navegación: el SavedStateHandle
            // aún contiene el ImageId reservado y Room ya contiene exactamente esa página.
            val restored = createViewModel(
                savedStateHandle = savedState,
                viewModelUuidGenerator = UuidGenerator { SECOND_UUID },
            )
            restored.effects.test {
                restored.onAction(SourceContract.Action.Start)
                runCurrent()
                assertEquals(
                    SourceContract.Effect.OpenPreview(
                        DRAFT_ID,
                        CaptureId.from(GENERATED_UUID),
                    ),
                    awaitItem(),
                )
                restored.onAction(SourceContract.Action.Start)
                runCurrent()
                expectNoEvents()
                restored.onAction(SourceContract.Action.PreviewNavigationHandled)
                runCurrent()
                // Volver desde Preview reutiliza este VM; el ack debe liberar el terminal y
                // reservar otra identidad, no dejar la pantalla bloqueada.
                restored.onAction(SourceContract.Action.PickImageSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.LaunchImagePicker, awaitItem())
                restored.onAction(SourceContract.Action.ImagePicked(PICKED_URI))
                runCurrent()
                assertEquals(
                    SourceContract.Effect.OpenPreview(
                        DRAFT_ID,
                        CaptureId.from(SECOND_UUID),
                    ),
                    awaitItem(),
                )
                expectNoEvents()
                restored.onAction(SourceContract.Action.PreviewNavigationHandled)
                runCurrent()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, importer.calls.size)

            // Una vez confirmada la navegación, recrear la ruta como "Añadir página" ya
            // no reutiliza una intención obsoleta.
            val fresh = createViewModel(savedState)
            fresh.effects.test {
                fresh.onAction(SourceContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `camera selection requests permission and a granted result opens the camera`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.CameraSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.RequestCameraPermission, awaitItem())

                viewModel.onAction(SourceContract.Action.CameraPermissionResult(granted = true))
                runCurrent()
                assertEquals(SourceContract.Effect.OpenCamera(DRAFT_ID), awaitItem())
                assertFalse(viewModel.uiState.value.cameraAccessDenied)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a denied result surfaces the card and retrying asks for the permission again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.CameraSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.RequestCameraPermission, awaitItem())

                viewModel.onAction(SourceContract.Action.CameraPermissionResult(granted = false))
                runCurrent()
                expectNoEvents()
                assertTrue(viewModel.uiState.value.cameraAccessDenied)

                // El botón de reintentar redespacha CameraSelected y vuelve a pedir el permiso.
                viewModel.onAction(SourceContract.Action.CameraSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.RequestCameraPermission, awaitItem())

                // Concedido en el segundo intento: la tarjeta se limpia y se abre la cámara.
                viewModel.onAction(SourceContract.Action.CameraPermissionResult(granted = true))
                runCurrent()
                assertEquals(SourceContract.Effect.OpenCamera(DRAFT_ID), awaitItem())
                assertFalse(viewModel.uiState.value.cameraAccessDenied)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a permanently denied camera permission opens app settings`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(
                    SourceContract.Action.CameraPermissionResult(
                        granted = false,
                        permanentlyDenied = true,
                    ),
                )
                runCurrent()
                assertTrue(viewModel.uiState.value.cameraPermissionPermanentlyDenied)
                expectNoEvents()

                viewModel.onAction(SourceContract.Action.OpenCameraSettingsSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.OpenAppSettings, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pick selection launches the picker and a picked image imports and opens the preview`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.PickImageSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.LaunchImagePicker, awaitItem())

                viewModel.onAction(SourceContract.Action.ImagePicked(PICKED_URI))
                runCurrent()
                // Galería: el captureId de la vista previa ES el imageId generado.
                assertEquals(
                    SourceContract.Effect.OpenPreview(
                        DRAFT_ID,
                        CaptureId.from(GENERATED_UUID),
                    ),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }

            val state = viewModel.uiState.value
            assertFalse(state.isImporting)
            assertNull(state.invalidImage)
            assertEquals(DraftStatus.CAPTURED, drafts.findDraft(DRAFT_ID)?.status)
            assertEquals(1, importer.calls.size)
        }

    @Test
    fun `acciones encoladas durante import no cambian fuente ni borran la identidad pendiente`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            importer.beforeImport = {
                started.complete(Unit)
                release.await()
            }
            val savedState = SavedStateHandle(mapOf("draftId" to DRAFT_ID.value))
            val viewModel = createViewModel(savedState)

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.PickImageSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.LaunchImagePicker, awaitItem())
                viewModel.onAction(SourceContract.Action.ImagePicked(PICKED_URI))
                runCurrent()
                started.await()
                assertTrue(viewModel.uiState.value.isImporting)
                assertEquals(GENERATED_UUID.toString(), savedState["source.pendingImageId"])

                viewModel.onAction(SourceContract.Action.CameraSelected)
                viewModel.onAction(SourceContract.Action.PickImageSelected)
                viewModel.onAction(SourceContract.Action.PickCancelled)
                viewModel.onAction(SourceContract.Action.CameraPermissionResult(granted = true))
                viewModel.onAction(SourceContract.Action.BackSelected)
                runCurrent()
                expectNoEvents()
                assertEquals(GENERATED_UUID.toString(), savedState["source.pendingImageId"])
                assertEquals(1, importer.calls.size)

                release.complete(Unit)
                runCurrent()
                assertEquals(
                    SourceContract.Effect.OpenPreview(DRAFT_ID, CaptureId.from(GENERATED_UUID)),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `every file failure maps to its invalid image reason and dismissing clears it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val viewModel = createViewModel()
            val cases = listOf(
                FileError.UnsupportedFormat to SourceContract.InvalidImageReason.UNSUPPORTED_FORMAT,
                FileError.TooLarge to SourceContract.InvalidImageReason.TOO_LARGE,
                FileError.Corrupt to SourceContract.InvalidImageReason.CORRUPT,
                FileError.NotFound to SourceContract.InvalidImageReason.NOT_FOUND,
                FileError.InsufficientSpace to SourceContract.InvalidImageReason.STORAGE_FULL,
            )

            viewModel.effects.test {
                cases.forEach { (error, expectedReason) ->
                    importer.nextException = FileException(error)
                    viewModel.onAction(SourceContract.Action.ImagePicked(PICKED_URI))
                    runCurrent()
                    expectNoEvents()
                    assertEquals(expectedReason, viewModel.uiState.value.invalidImage)
                    assertFalse(viewModel.uiState.value.isImporting)

                    viewModel.onAction(SourceContract.Action.DismissInvalidImage)
                    runCurrent()
                    assertNull(viewModel.uiState.value.invalidImage)
                }
                cancelAndIgnoreRemainingEvents()
            }

            // La reserva recuperable impide que el sweep borre una importación en carrera.
            assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
        }

    @Test
    fun `an unexpected failure maps to unavailable`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val viewModel = createViewModel()
            importer.nextException = IllegalStateException("fallo inesperado")

            viewModel.onAction(SourceContract.Action.ImagePicked(PICKED_URI))
            runCurrent()

            assertEquals(
                SourceContract.InvalidImageReason.UNAVAILABLE,
                viewModel.uiState.value.invalidImage,
            )
            assertFalse(viewModel.uiState.value.isImporting)
            assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
        }

    @Test
    fun `cancelling the picker keeps the resting state and persists nothing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.PickCancelled)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(
                SourceContract.State(draftId = DRAFT_ID),
                viewModel.uiState.value,
            )
            assertTrue(importer.calls.isEmpty())
            assertNull(drafts.findDraft(DRAFT_ID))
        }

    @Test
    fun `denied camera and invalid image are restored from the saved state handle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        "draftId" to DRAFT_ID.value,
                        "source.cameraAccessDenied" to true,
                        "source.cameraPermissionPermanentlyDenied" to true,
                        "source.invalidImage" to "TOO_LARGE",
                    ),
                ),
            )

            val state = viewModel.uiState.value
            assertEquals(DRAFT_ID, state.draftId)
            assertTrue(state.cameraAccessDenied)
            assertTrue(state.cameraPermissionPermanentlyDenied)
            assertEquals(SourceContract.InvalidImageReason.TOO_LARGE, state.invalidImage)
            assertNull(state.failure)
        }

    @Test
    fun `back selection emits the back effect`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(SourceContract.Action.BackSelected)
                runCurrent()
                assertEquals(SourceContract.Effect.Back, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf("draftId" to DRAFT_ID.value),
        ),
        viewModelUuidGenerator: UuidGenerator = UuidGenerator { GENERATED_UUID },
    ): SourceViewModel = SourceViewModel(
        savedStateHandle = savedStateHandle,
        importDraftImageUseCase = useCaseForTests(),
        observeInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(drafts),
        observeDraftImagesUseCase = ObserveDraftImagesUseCase(drafts),
        uuidGenerator = viewModelUuidGenerator,
        dispatcherProvider = dispatchers,
    )

    private fun useCaseForTests(): ImportDraftImageUseCase = ImportDraftImageUseCase(
        appConfigurationRepository = appConfig,
        invoiceDraftRepository = drafts,
        draftImageImporter = importer,
        draftFileStore = FakeDraftFileStore(),
        uuidGenerator = UuidGenerator { GENERATED_UUID },
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
        const val PICKED_URI = "content://media/external/images/42"
    }
}
