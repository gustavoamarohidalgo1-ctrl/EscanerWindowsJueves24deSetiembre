package com.facturastock.app.feature.purchases

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseRetainedImage
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.usecase.ObservePurchaseDetailUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseHistoryUseCase
import com.facturastock.app.domain.usecase.LoadRemotePurchaseDocumentUseCase
import com.facturastock.app.domain.usecase.ReadRetainedImageUseCase
import com.facturastock.app.domain.usecase.RetryPurchaseBackupUseCase
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDocumentDownloadResult
import com.facturastock.app.domain.repository.RemoteDocumentReference
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakeRetainedImageStore
import com.facturastock.app.testing.FakePurchaseBackupRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchasesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val configuration = FakeAppConfigurationRepository()
    private val purchaseReads = FakePurchaseReadRepository()
    private val backups = FakePurchaseBackupRepository()
    private val retainedImages = FakeRetainedImageStore()
    private val documentLifecycle = FakeDocumentLifecycle()
    private val remoteDocuments = FakeRemoteDocumentArchive()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `reactive snapshots add a purchase immediately and replace it without duplication`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val viewModel = createViewModel()
            runCurrent()
            assertEquals(emptyList<PurchaseReadSummary>(), viewModel.uiState.value.purchases)

            val posted = summary(seed = 1)
            purchaseReads.replacePurchases(BUSINESS_ID, listOf(posted))
            runCurrent()
            assertEquals(listOf(posted), viewModel.uiState.value.purchases)

            val synced = posted.copy(syncState = PurchaseSyncState.SYNCED)
            purchaseReads.replacePurchases(BUSINESS_ID, listOf(synced))
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(1, state.purchases.size)
            assertEquals(synced, state.purchases.single())
        }

    @Test
    fun `history loads thirty rows at a time and ignores a double load-more tap`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val history = (1..65).map { seed ->
                summary(seed = seed, issueDate = LocalDate.of(2026, 1, 1).plusDays(seed.toLong()))
            }
            purchaseReads.replacePurchases(BUSINESS_ID, history)
            val handle = SavedStateHandle()
            val viewModel = createViewModel(handle)
            runCurrent()

            assertEquals(history.take(30), viewModel.uiState.value.purchases)
            assertTrue(viewModel.uiState.value.hasMore)

            viewModel.onAction(PurchasesContract.Action.LoadMore)
            viewModel.onAction(PurchasesContract.Action.LoadMore)
            runCurrent()

            assertEquals(history.take(60), viewModel.uiState.value.purchases)
            assertEquals(2, viewModel.uiState.value.visiblePages)
            assertEquals(2, handle.get<Int>(VISIBLE_PAGES_KEY))
            assertTrue(viewModel.uiState.value.hasMore)

            viewModel.onAction(PurchasesContract.Action.LoadMore)
            runCurrent()

            assertEquals(history, viewModel.uiState.value.purchases)
            assertFalse(viewModel.uiState.value.hasMore)
            assertEquals(3, viewModel.uiState.value.visiblePages)
        }

    @Test
    fun `hostile restored page count is capped and a filter returns to one page`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val history = (1..65).map { seed ->
                summary(seed = seed, issueDate = LocalDate.of(2026, 1, 1).plusDays(seed.toLong()))
            }
            purchaseReads.replacePurchases(BUSINESS_ID, history)
            val handle = SavedStateHandle(mapOf(VISIBLE_PAGES_KEY to Int.MAX_VALUE))
            val viewModel = createViewModel(handle)
            runCurrent()

            assertEquals(20, viewModel.uiState.value.visiblePages)
            assertEquals(history, viewModel.uiState.value.purchases)

            viewModel.searchFor("Proveedor 1")

            assertEquals(1, viewModel.uiState.value.visiblePages)
            assertEquals(1, handle.get<Int>(VISIBLE_PAGES_KEY))
            assertTrue(viewModel.uiState.value.purchases.isNotEmpty())
            assertTrue(
                viewModel.uiState.value.purchases.all { purchase ->
                    purchase.supplierLegalName.contains("Proveedor 1")
                },
            )
        }

    @Test
    fun `search covers supplier ruc document and date and combines status and sync filters`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val andina = summary(
                seed = 1,
                supplierName = "Distribuidora Andina SAC",
                supplierRuc = "20111111111",
                series = "F001",
                number = "00000001",
                issueDate = LocalDate.of(2026, 8, 10),
                status = PurchaseStatus.POSTED,
                syncState = PurchaseSyncState.PENDING_SYNC,
            )
            val mercado = summary(
                seed = 2,
                supplierName = "Mercado Norte EIRL",
                supplierRuc = "20555555555",
                series = "B002",
                number = "00000002",
                issueDate = LocalDate.of(2026, 8, 11),
                status = PurchaseStatus.VOIDED,
                syncState = PurchaseSyncState.SYNCED,
            )
            val pacifico = summary(
                seed = 3,
                supplierName = "Comercial Pacifico SAC",
                supplierRuc = "20666666666",
                series = "F003",
                number = "00000003",
                issueDate = LocalDate.of(2026, 8, 12),
                status = PurchaseStatus.POSTED,
                syncState = PurchaseSyncState.ERROR,
            )
            purchaseReads.replacePurchases(BUSINESS_ID, listOf(andina, mercado, pacifico))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.searchFor("  ANDINA  ")
            assertEquals(listOf(andina), viewModel.uiState.value.purchases)

            viewModel.searchFor("20555555555")
            assertEquals(listOf(mercado), viewModel.uiState.value.purchases)

            viewModel.searchFor("b002-00000002")
            assertEquals(listOf(mercado), viewModel.uiState.value.purchases)

            viewModel.searchFor("2026-08-12")
            assertEquals(listOf(pacifico), viewModel.uiState.value.purchases)

            viewModel.searchFor("")
            viewModel.onAction(
                PurchasesContract.Action.StatusFilterChanged(PurchaseStatus.POSTED),
            )
            runCurrent()
            assertEquals(listOf(andina, pacifico), viewModel.uiState.value.purchases)

            viewModel.onAction(
                PurchasesContract.Action.SyncFilterChanged(PurchaseSyncState.ERROR),
            )
            runCurrent()
            assertEquals(listOf(pacifico), viewModel.uiState.value.purchases)
        }

    @Test
    fun `rapid typing keeps visible content and only executes the last debounced query`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val andina = summary(seed = 1, supplierName = "Distribuidora Andina")
            val mercado = summary(seed = 2, supplierName = "Mercado Norte")
            purchaseReads.replacePurchases(BUSINESS_ID, listOf(andina, mercado))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PurchasesContract.Action.SearchChanged("mer"))
            runCurrent()
            assertEquals(listOf(andina, mercado), viewModel.uiState.value.purchases)
            assertTrue(viewModel.uiState.value.isFiltering)
            advanceTimeBy(100L)

            viewModel.onAction(PurchasesContract.Action.SearchChanged("mercado"))
            runCurrent()
            advanceTimeBy(251L)
            runCurrent()

            assertEquals(listOf(mercado), viewModel.uiState.value.purchases)
            assertFalse(viewModel.uiState.value.isFiltering)
            assertEquals(2, purchaseReads.observedHistoryRequests.size)
            assertEquals(
                "mercado",
                purchaseReads.observedHistoryRequests.last().second.query,
            )
        }

    @Test
    fun `search keeps unicode case folding and treats percent and underscore literally`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val literal = summary(seed = 1, supplierName = "Distribuidora ÁGIL_100%")
            val wildcardLookalike = summary(seed = 2, supplierName = "Distribuidora ágilX1000")
            purchaseReads.replacePurchases(BUSINESS_ID, listOf(literal, wildcardLookalike))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.searchFor("ágil_100%")

            assertEquals(listOf(literal), viewModel.uiState.value.purchases)
        }

    @Test
    fun `query and filters survive saved state recreation and are applied to the live list`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val posted = summary(seed = 1, supplierName = "Proveedor Andino")
            val matching = summary(
                seed = 2,
                supplierName = "Mercado Norte",
                status = PurchaseStatus.VOIDED,
                syncState = PurchaseSyncState.SYNCED,
            )
            purchaseReads.replacePurchases(BUSINESS_ID, listOf(posted, matching))
            val firstHandle = SavedStateHandle()
            val firstViewModel = createViewModel(firstHandle)
            runCurrent()

            firstViewModel.searchFor("mercado")
            firstViewModel.onAction(
                PurchasesContract.Action.StatusFilterChanged(PurchaseStatus.VOIDED),
            )
            runCurrent()
            firstViewModel.onAction(
                PurchasesContract.Action.SyncFilterChanged(PurchaseSyncState.SYNCED),
            )
            runCurrent()

            assertEquals("mercado", firstHandle.get<String>(QUERY_KEY))
            assertEquals(PurchaseStatus.VOIDED.name, firstHandle.get<String>(STATUS_KEY))
            assertEquals(PurchaseSyncState.SYNCED.name, firstHandle.get<String>(SYNC_KEY))
            assertEquals(listOf(matching), firstViewModel.uiState.value.purchases)

            val recreatedHandle = SavedStateHandle(
                mapOf(
                    QUERY_KEY to firstHandle.get<String>(QUERY_KEY),
                    STATUS_KEY to firstHandle.get<String>(STATUS_KEY),
                    SYNC_KEY to firstHandle.get<String>(SYNC_KEY),
                ),
            )
            val recreated = createViewModel(recreatedHandle)
            runCurrent()

            val restoredState = recreated.uiState.value
            assertEquals("mercado", restoredState.query)
            assertEquals(PurchaseStatus.VOIDED, restoredState.statusFilter)
            assertEquals(PurchaseSyncState.SYNCED, restoredState.syncFilter)
            assertEquals(listOf(matching), restoredState.purchases)
        }

    @Test
    fun `retry backup requeues Room outbox once and keeps purchase visible`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val failed = summary(seed = 8, syncState = PurchaseSyncState.ERROR)
            purchaseReads.replacePurchases(BUSINESS_ID, listOf(failed))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PurchasesContract.Action.RetryBackup(failed.purchaseId))
            viewModel.onAction(PurchasesContract.Action.RetryBackup(failed.purchaseId))
            runCurrent()

            assertEquals(listOf(BUSINESS_ID to failed.purchaseId), backups.retryRequests)
            assertNull(viewModel.uiState.value.retryingBackupPurchaseId)
            assertNull(viewModel.uiState.value.backupRetryFailedPurchaseId)
            assertEquals(listOf(failed), viewModel.uiState.value.purchases)
        }

    @Test
    fun `foreign detail is reported as missing until that business becomes active`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val purchaseId = purchaseId(7)
            val foreignDetail = detail(summary(seed = 7, businessId = DEMO_BUSINESS_ID))
            purchaseReads.setPurchaseDetail(DEMO_BUSINESS_ID, purchaseId, foreignDetail)
            val viewModel = createViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.PURCHASE_ID to purchaseId.value)),
            )
            runCurrent()

            assertNull(viewModel.uiState.value.detail)
            assertEquals(
                PurchasesContract.Failure.PURCHASE_NOT_FOUND,
                viewModel.uiState.value.failure,
            )
            assertEquals(
                listOf(BUSINESS_ID to purchaseId),
                purchaseReads.observedDetails,
            )

            configuration.enterDemoMode(DEMO_BUSINESS_ID)
            runCurrent()

            assertEquals(foreignDetail, viewModel.uiState.value.detail)
            assertNull(viewModel.uiState.value.failure)
            assertEquals(
                listOf(BUSINESS_ID to purchaseId, DEMO_BUSINESS_ID to purchaseId),
                purchaseReads.observedDetails,
            )
        }

    @Test
    fun `detail reads retained image through the decrypted in-memory port`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            val purchaseId = purchaseId(12)
            val imageId = ImageId.from(uuid(712))
            val relativePath = "draft_images/${uuid(713)}/page-0.jpg"
            val expectedBytes = byteArrayOf(7, 1, 2, 9)
            retainedImages.putEncryptedFile(relativePath, expectedBytes)
            val detail = detail(summary(seed = 12)).copy(
                images = listOf(
                    PurchaseRetainedImage(
                        imageId = imageId,
                        pageIndex = 0,
                        relativeFilePath = relativePath,
                        mimeType = "image/jpeg",
                        widthPx = 100,
                        heightPx = 200,
                        rotationDegrees = 0,
                        cropLeftFraction = null,
                        cropTopFraction = null,
                        cropRightFraction = null,
                        cropBottomFraction = null,
                    ),
                ),
            )
            purchaseReads.setPurchaseDetail(BUSINESS_ID, purchaseId, detail)

            val viewModel = createViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.PURCHASE_ID to purchaseId.value)),
            )
            runCurrent()

            assertTrue(viewModel.uiState.value.retainedImages.isEmpty())
            viewModel.onAction(PurchasesContract.Action.TechnicalDetailsToggled)
            runCurrent()

            val available = viewModel.uiState.value.retainedImages[imageId]
                as PurchasesContract.RetainedImageContent.Available
            assertArrayEquals(expectedBytes, available.encodedBytes)
            assertFalse(viewModel.uiState.value.retainedImages.values.any {
                it is PurchasesContract.RetainedImageContent.Loading
            })

            viewModel.onAction(PurchasesContract.Action.TechnicalDetailsToggled)
            runCurrent()

            assertTrue(available.encodedBytes.all { byte -> byte == 0.toByte() })
            assertTrue(viewModel.uiState.value.retainedImages.isEmpty())
        }

    @Test
    fun `missing local image uses verified remote view without claiming a restore`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            configuration.updateBackupEnabled(true)
            configuration.updateDocumentBackupEnabled(true)
            documentLifecycle.backedUp = true
            val purchaseId = purchaseId(13)
            val imageId = ImageId.from(uuid(714))
            val expectedBytes = byteArrayOf(9, 7, 5, 3)
            remoteDocuments.nextResult =
                RemoteDocumentDownloadResult.Downloaded(expectedBytes.copyOf())
            purchaseReads.setPurchaseDetail(
                BUSINESS_ID,
                purchaseId,
                detail(summary(seed = 13)).copy(
                    images = listOf(
                        PurchaseRetainedImage(
                            imageId = imageId,
                            pageIndex = 0,
                            relativeFilePath = "draft_images/${uuid(715)}/missing.jpg",
                            mimeType = "image/jpeg",
                            widthPx = 100,
                            heightPx = 200,
                            rotationDegrees = 0,
                            cropLeftFraction = null,
                            cropTopFraction = null,
                            cropRightFraction = null,
                            cropBottomFraction = null,
                        ),
                    ),
                ),
            )

            val viewModel = createViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.PURCHASE_ID to purchaseId.value)),
            )
            runCurrent()

            assertTrue(viewModel.uiState.value.retainedImages.isEmpty())
            assertTrue(remoteDocuments.references.isEmpty())
            viewModel.onAction(PurchasesContract.Action.TechnicalDetailsToggled)
            runCurrent()

            val available = viewModel.uiState.value.retainedImages[imageId]
                as PurchasesContract.RetainedImageContent.Available
            assertArrayEquals(expectedBytes, available.encodedBytes)
            assertEquals(
                PurchasesContract.RetainedImageContent.Available.Source.REMOTE,
                available.source,
            )
            assertEquals(
                listOf(RemoteDocumentReference(BUSINESS_ID, purchaseId, imageId)),
                remoteDocuments.references,
            )
        }

    @Test
    fun `corrupt local image fails closed and never enables remote fallback`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate(BUSINESS_ID)
            configuration.updateBackupEnabled(true)
            configuration.updateDocumentBackupEnabled(true)
            documentLifecycle.backedUp = true
            val purchaseId = purchaseId(14)
            val imageId = ImageId.from(uuid(716))
            val relativePath = "draft_images/${uuid(717)}/corrupt.jpg"
            retainedImages.putCorruptFile(relativePath)
            remoteDocuments.nextResult =
                RemoteDocumentDownloadResult.Downloaded(byteArrayOf(8, 6, 4, 2))
            purchaseReads.setPurchaseDetail(
                BUSINESS_ID,
                purchaseId,
                detail(summary(seed = 14)).copy(
                    images = listOf(
                        PurchaseRetainedImage(
                            imageId = imageId,
                            pageIndex = 0,
                            relativeFilePath = relativePath,
                            mimeType = "image/jpeg",
                            widthPx = 100,
                            heightPx = 200,
                            rotationDegrees = 0,
                            cropLeftFraction = null,
                            cropTopFraction = null,
                            cropRightFraction = null,
                            cropBottomFraction = null,
                        ),
                    ),
                ),
            )

            val viewModel = createViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.PURCHASE_ID to purchaseId.value)),
            )
            runCurrent()

            assertTrue(viewModel.uiState.value.retainedImages.isEmpty())
            viewModel.onAction(PurchasesContract.Action.TechnicalDetailsToggled)
            runCurrent()

            assertEquals(
                PurchasesContract.RetainedImageContent.IntegrityRejected,
                viewModel.uiState.value.retainedImages[imageId],
            )
            assertTrue(remoteDocuments.references.isEmpty())
        }

    private fun PurchasesViewModel.searchFor(query: String) {
        onAction(PurchasesContract.Action.SearchChanged(query))
        mainDispatcherRule.scheduler.runCurrent()
        mainDispatcherRule.scheduler.advanceTimeBy(251L)
        mainDispatcherRule.scheduler.runCurrent()
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PurchasesViewModel = PurchasesViewModel(
        savedStateHandle = savedStateHandle,
        observePurchaseHistory = ObservePurchaseHistoryUseCase(configuration, purchaseReads),
        observePurchaseDetail = ObservePurchaseDetailUseCase(configuration, purchaseReads),
        readRetainedImage = ReadRetainedImageUseCase(retainedImages),
        loadRemotePurchaseDocument = LoadRemotePurchaseDocumentUseCase(
            configuration,
            documentLifecycle,
            remoteDocuments,
        ),
        retryPurchaseBackup = RetryPurchaseBackupUseCase(
            configuration,
            backups,
            FakePurchaseBackupScheduler(),
        ),
        dispatcherProvider = dispatchers,
    )

    private suspend fun activate(businessId: BusinessId) {
        configuration.completeOnboarding(
            businessId,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private class FakeDocumentLifecycle : DocumentBackupLifecycleRepository {
        var backedUp: Boolean = false

        override suspend fun isBackedUp(
            businessId: BusinessId,
            purchaseId: PurchaseId,
            imageId: ImageId,
        ): Boolean = backedUp

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
    }

    private class FakeRemoteDocumentArchive : RemoteDocumentArchive {
        var nextResult: RemoteDocumentDownloadResult = RemoteDocumentDownloadResult.NotFound
        val references = mutableListOf<RemoteDocumentReference>()

        override val configured: Boolean = true

        override suspend fun download(
            reference: RemoteDocumentReference,
        ): RemoteDocumentDownloadResult {
            references += reference
            return nextResult
        }
    }

    private fun summary(
        seed: Int,
        businessId: BusinessId = BUSINESS_ID,
        supplierName: String = "Proveedor $seed SAC",
        supplierRuc: String? = "20123456786",
        series: String = "F001",
        number: String = "%08d".format(seed),
        issueDate: LocalDate = LocalDate.of(2026, 8, seed),
        status: PurchaseStatus = PurchaseStatus.POSTED,
        syncState: PurchaseSyncState = PurchaseSyncState.PENDING_SYNC,
    ): PurchaseReadSummary = PurchaseReadSummary(
        purchaseId = purchaseId(seed),
        businessId = businessId,
        sourceDraftId = DraftId.from(uuid(100 + seed)),
        supplierRuc = supplierRuc,
        supplierLegalName = supplierName,
        documentType = PurchaseDocumentType.INVOICE,
        documentSeries = series,
        documentNumber = number,
        issueDate = issueDate,
        currency = PEN,
        total = Money.ofMinor(seed * 1_000L, PEN),
        status = status,
        syncState = syncState,
        lineCount = 0,
        productCount = 0,
        postedAt = Instant.parse("2026-08-14T12:00:00Z").plusSeconds(seed.toLong()),
    )

    private fun detail(summary: PurchaseReadSummary): PurchaseReadDetail = PurchaseReadDetail(
        summary = summary,
        supplierId = SupplierId.from(uuid(500)),
        subtotal = summary.total,
        tax = Money.zero(summary.currency),
        otherCharges = Money.zero(summary.currency),
        adjustment = null,
        lines = emptyList(),
        movements = emptyList(),
        auditEvents = emptyList(),
        images = emptyList(),
        preparedLogicalHash = "b".repeat(64),
        acceptedWarnings = emptyList(),
    )

    private companion object {
        const val QUERY_KEY = "purchases.query"
        const val STATUS_KEY = "purchases.statusFilter"
        const val SYNC_KEY = "purchases.syncFilter"
        const val VISIBLE_PAGES_KEY = "purchases.visiblePages"

        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(901))
        val DEMO_BUSINESS_ID: BusinessId = BusinessId.from(uuid(902))

        fun purchaseId(seed: Int): PurchaseId = PurchaseId.from(uuid(seed))

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
