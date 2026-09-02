package com.facturastock.app.feature.sync

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.AmbiguousRemotePurchase
import com.facturastock.app.domain.model.AmbiguousRemoteProduct
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.ReconciliationReport
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.CatalogConflictComparison
import com.facturastock.app.domain.repository.CatalogConflictSide
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reconciliationShowsAmbiguitySeparatelyFromMatchedAndRemoteOnly() {
        val remote = remotePurchase()
        val report = ReconciliationReport(
            businessId = LOCAL_BUSINESS_ID,
            latestSeq = remote.seq,
            matched = emptyList(),
            ambiguous = listOf(
                AmbiguousRemotePurchase(
                    remote = remote,
                    localPurchaseIds = listOf(purchaseId(1), purchaseId(2)),
                ),
            ),
            remoteOnly = emptyList(),
            balanceDifferences = emptyList(),
            unlinkedRemoteProducts = emptyList(),
            comparedProductCount = 0,
            generatedAt = NOW,
            ambiguousRemoteProducts = listOf(
                AmbiguousRemoteProduct(
                    remoteProductIds = listOf("remote-product-shared"),
                    productName = "ARROZ EXTRA",
                    remoteNet = java.math.BigDecimal("5"),
                    localProductIds = listOf(productId(3), productId(4)),
                ),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SyncScreen(
                    state = SyncContract.State(
                        session = AccountSession.Active(
                            uid = "uid-test",
                            email = "sync@example.com",
                            link = CloudBusinessLink(
                                localBusinessId = LOCAL_BUSINESS_ID,
                                cloudBusinessId = CLOUD_BUSINESS_ID,
                                role = BusinessRole.OWNER,
                            ),
                        ),
                        activeBusinessId = LOCAL_BUSINESS_ID,
                        backupEnabled = true,
                        remoteLedgerAvailable = true,
                        report = report,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Coincidencias ambiguas: 1")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Coinciden local y nube: 0").assertIsDisplayed()
        composeRule.onNodeWithText("Solo en la nube: 0").assertIsDisplayed()
        composeRule.onNodeWithTag(SyncTestTags.ambiguity(remote.purchaseId))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Factura F001-000123")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("2 compras locales", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Productos ambiguos: 1")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(
            SyncTestTags.productAmbiguity("remote-product-shared"),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Registros remotos: 1", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("candidatos locales: 2", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun backupDisabledExplainsThatCloudIoIsPausedWhileLocalDataRemains() {
        composeRule.setContent {
            FacturaStockTheme {
                SyncScreen(
                    state = linkedState(backupEnabled = false),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("El respaldo está pausado.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("guardados en este celular", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun catalogConflictShowsFriendlyComparisonLastAttemptAndSanitizedError() {
        val operation = OutboxOperationView(
            operationId = uuid(201).toString(),
            operationType = "SYNC_PRODUCT",
            purchaseId = null,
            status = OutboxOperationStatus.CONFLICT,
            attemptCount = 2,
            lastError = "HTTP_500_SECRET_DETAIL",
            nextAttemptAt = null,
            updatedAt = NOW,
            conflictRemotePurchaseId = null,
            conflictReceiptId = null,
            entityType = "PRODUCT",
            entityId = uuid(202).toString(),
            entityVersion = 2,
            businessId = LOCAL_BUSINESS_ID,
            catalogConflictComparison = CatalogConflictComparison(
                local = CatalogConflictSide(
                    displayName = "Arroz local",
                    semanticIdentifier = "SKU-ARROZ",
                    status = "ACTIVE",
                    version = 2,
                    changedAt = NOW.minusSeconds(60),
                    origin = "LOCAL",
                ),
                remote = CatalogConflictSide(
                    displayName = "Arroz nube",
                    semanticIdentifier = "SKU-ARROZ",
                    status = "ACTIVE",
                    version = 1,
                    changedAt = NOW.minusSeconds(120),
                    origin = "CLOUD",
                ),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SyncScreen(
                    state = linkedState(backupEnabled = true).copy(outbox = listOf(operation)),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Cambio de producto").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Último intento:", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("problema temporal", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("HTTP_500_SECRET_DETAIL").assertDoesNotExist()
        composeRule.onNodeWithText("Arroz local").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Arroz nube").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Origen: este celular").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Origen: nube").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Conservar este celular y reenviar")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun documentSectionShowsLatestRoomStatePerImageWithoutIdsPathsOrRawErrors() {
        val imageOne = uuid(301).toString()
        val imageTwo = uuid(302).toString()
        val imageThree = uuid(303).toString()
        val operations = listOf(
            documentOperation(
                seed = 311,
                imageId = imageOne,
                operationType = "SYNC_DOCUMENT_UPLOAD",
                entityVersion = 1,
                status = OutboxOperationStatus.COMPLETED,
                updatedAt = NOW.minusSeconds(30),
            ),
            documentOperation(
                seed = 312,
                imageId = imageOne,
                operationType = "SYNC_DOCUMENT_PURGE",
                entityVersion = 2,
                status = OutboxOperationStatus.PENDING,
                updatedAt = NOW.minusSeconds(20),
            ),
            documentOperation(
                seed = 313,
                imageId = imageTwo,
                operationType = "SYNC_DOCUMENT_UPLOAD",
                entityVersion = 1,
                status = OutboxOperationStatus.COMPLETED,
                updatedAt = NOW.minusSeconds(10),
            ),
            documentOperation(
                seed = 314,
                imageId = imageThree,
                operationType = "SYNC_DOCUMENT_UPLOAD",
                entityVersion = 1,
                status = OutboxOperationStatus.FAILED,
                updatedAt = NOW,
                lastError = "content://private/invoice.jpg bearer-secret",
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SyncScreen(
                    state = linkedState(backupEnabled = true).copy(
                        documentBackupEnabled = true,
                        outbox = operations,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SyncTestTags.DOCUMENT_SECTION)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Imágenes del comprobante").assertIsDisplayed()
        composeRule.onNodeWithText("Purga de imagen").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Eliminación de la nube pendiente", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Respaldada", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Error; requiere acción", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("No se pudo completar el respaldo.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("content://private/invoice.jpg bearer-secret")
            .assertDoesNotExist()
        listOf(imageOne, imageTwo, imageThree).forEach { imageId ->
            composeRule.onNodeWithText(imageId).assertDoesNotExist()
        }
    }

    private fun linkedState(backupEnabled: Boolean) = SyncContract.State(
        session = AccountSession.Active(
            uid = "uid-test",
            email = "sync@example.com",
            link = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                role = BusinessRole.OWNER,
            ),
        ),
        activeBusinessId = LOCAL_BUSINESS_ID,
        backupEnabled = backupEnabled,
        remoteLedgerAvailable = true,
    )

    private fun remotePurchase() = RemotePurchaseChange(
        seq = 7,
        purchaseId = "33333333-3333-4333-8333-333333333333",
        status = PurchaseStatus.POSTED,
        documentType = "INVOICE",
        documentSeries = "F001",
        documentNumber = "000123",
        issueDate = "2026-08-15",
        currency = "PEN",
        supplierRuc = "20123456789",
        supplierLegalName = "Proveedor Demo SAC",
        totalMinorUnits = 1_180,
        movementSummary = emptyList(),
        receiptId = "rcpt-test",
        syncedAtMillis = NOW.toEpochMilli(),
        syncedBy = "uid-test",
    )

    private fun documentOperation(
        seed: Int,
        imageId: String,
        operationType: String,
        entityVersion: Long,
        status: OutboxOperationStatus,
        updatedAt: Instant,
        lastError: String? = null,
    ) = OutboxOperationView(
        operationId = uuid(seed).toString(),
        operationType = operationType,
        purchaseId = purchaseId(1),
        status = status,
        attemptCount = 1,
        lastError = lastError,
        nextAttemptAt = null,
        updatedAt = updatedAt,
        conflictRemotePurchaseId = null,
        conflictReceiptId = null,
        entityType = "DOCUMENT",
        entityId = imageId,
        entityVersion = entityVersion,
        businessId = LOCAL_BUSINESS_ID,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-21T12:00:00Z")
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(uuid(100))
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(uuid(101))

        fun purchaseId(seed: Int): PurchaseId = PurchaseId.from(uuid(seed))

        fun productId(seed: Int): ProductId = ProductId.from(uuid(seed))

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-4000-8000-%012d".format(seed))
    }
}
