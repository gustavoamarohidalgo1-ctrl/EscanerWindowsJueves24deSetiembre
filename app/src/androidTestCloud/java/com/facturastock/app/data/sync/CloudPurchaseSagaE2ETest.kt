package com.facturastock.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.dao.InventoryBalanceMutation
import com.facturastock.app.data.local.dao.PurchasePostingBatch
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.InventoryCostingRequest
import com.facturastock.app.domain.model.InventoryCostingResult
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseVoidCommand
import com.facturastock.app.domain.repository.PurchaseVoidRepository
import com.facturastock.app.domain.repository.PurchaseVoidResult
import com.facturastock.app.domain.usecase.BindCloudBusinessLinkUseCase
import com.facturastock.app.domain.usecase.OutboxPassResult
import com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCase
import com.facturastock.app.domain.usecase.InventoryCostingService
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorrido real del cliente cloud contra Auth, Functions y Firestore Emulator.
 *
 * No sustituye el cliente por dobles: publica el agregado Room real, deja que la outbox lo
 * reconstruya, cruza el callable y vuelve a leer el ledger. El alta remota intermedia representa
 * un segundo dispositivo y prueba la colision de identidad documental de extremo a extremo.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CloudPurchaseSagaE2ETest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var runtime: FirebaseBackupRuntime
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var memberships: BusinessMembershipRepository
    @Inject lateinit var configuration: AppConfigurationRepository
    @Inject lateinit var database: FacturaStockDatabase
    @Inject lateinit var bindCloudBusiness: BindCloudBusinessLinkUseCase
    @Inject lateinit var processOutbox: ProcessPurchaseBackupOutboxUseCase
    @Inject lateinit var pullRemoteChanges: PullRemoteChangesUseCase
    @Inject lateinit var purchaseVoids: PurchaseVoidRepository

    private val currency = CurrencyCode.of("PEN")

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun registrationPurchasePullConflictVoidAndExpiredSession() = runBlocking {
        withTimeout(180_000L) {
            // `emulators:exec` crea un backend efimero; el cliente tambien parte sin enlace
            // heredado aunque el runner reutilice el paquete entre clases de instrumentacion.
            accounts.signOut()

            val suffix = UUID.randomUUID().toString().replace("-", "")
            val email = "e2e-$suffix@example.test"
            val password = "E2e-${suffix.take(12)}-Aa1!"
            val awaiting = accounts.register(email, password).success("register")
            assertTrue(awaiting is AccountSession.AwaitingVerification)
            val uid = (awaiting as AccountSession.AwaitingVerification).uid

            callDevControl("devVerifyCurrentUser")
            assertTrue(accounts.refreshVerification().success("verify") is AccountSession.Active)
            val active = accounts.recoverSession().success("fresh verified token")
            assertTrue(active is AccountSession.Active)

            val ids = newLocalCatalogIds()
            seedLocalCatalog(ids)
            configuration.completeOnboarding(
                businessId = ids.business,
                taxRate = AppConfiguration.DEFAULT_TAX_RATE,
                costPolicy = CostPolicy.NET,
            )

            val membership = memberships.createBusiness(uid, "Cloud E2E $suffix")
                .success("create cloud business")
            assertEquals(BusinessRole.OWNER, membership.role)
            val cloudBusinessId = membership.businessId
            bindCloudBusiness(ids.business, cloudBusinessId).success("pin Room tenant")
            memberships.setActiveCloudBusiness(
                expectedUid = uid,
                expectedLocalIdentityEpoch = memberships.currentLocalBusinessIdentityEpoch(),
                link = CloudBusinessLink(ids.business, cloudBusinessId, BusinessRole.OWNER),
            ).success("publish active link")
            val linked = accounts.observeSession().first {
                it is AccountSession.Active && it.link?.cloudBusinessId == cloudBusinessId
            } as AccountSession.Active
            assertEquals(ids.business, linked.link?.localBusinessId)

            val first = postLocalPurchase(
                ids = ids,
                documentNumber = "100001",
                cloudBusinessId = cloudBusinessId,
            )
            val firstPass = processOutbox(
                batchLimit = 10,
                targetCloudBusinessId = cloudBusinessId,
                targetAvailable = { true },
            ) as OutboxPassResult.Drained
            assertEquals(1, firstPass.completed)
            assertEquals(
                OutboxOperationStatus.COMPLETED.name,
                database.outboxOperationDao().findById(first.outboxId)?.status,
            )

            val remoteFirst = runtime.firestore()!!
                .collection("businesses").document(cloudBusinessId.value)
                .collection("purchases").document(first.purchaseId.value)
                .get().await()
            assertTrue(remoteFirst.exists())
            assertEquals(PurchaseStatus.POSTED.name, remoteFirst.getString("status"))

            val initialPull = pullRemoteChanges(cloudBusinessId).success("initial pull")
            assertEquals(1, initialPull.pulledCount)
            assertTrue(
                database.remoteSyncDao().listPurchaseChanges(cloudBusinessId.value).any {
                    it.purchaseId == first.purchaseId.value && it.status == PurchaseStatus.POSTED.name
                },
            )

            val remoteCollisionId = canonicalUuid()
            seedRemotePurchase(
                functions = requireNotNull(runtime.functions()),
                uid = uid,
                cloudBusinessId = cloudBusinessId,
                purchaseId = remoteCollisionId,
                documentNumber = "200002",
            )
            val conflicting = postLocalPurchase(
                ids = ids,
                documentNumber = "200002",
                cloudBusinessId = cloudBusinessId,
            )
            val conflictPass = processOutbox(
                batchLimit = 10,
                targetCloudBusinessId = cloudBusinessId,
                targetAvailable = { true },
            ) as OutboxPassResult.Drained
            assertEquals(1, conflictPass.conflicts)
            val conflictRow = database.outboxOperationDao().findById(conflicting.outboxId)
            assertEquals(OutboxOperationStatus.CONFLICT.name, conflictRow?.status)
            assertEquals(remoteCollisionId, conflictRow?.conflictRemotePurchaseId)
            // En compras el tenant autoritativo permanece en targetCloudBusinessId; el campo
            // conflictCloudBusinessId está reservado para snapshots completos de catálogo.
            assertEquals(cloudBusinessId.value, conflictRow?.targetCloudBusinessId)

            // actorId sella al principal local (UUID); el UID Firebase se valida por separado
            // como expectedUid/autenticación en el callable.
            val actor = PurchaseOverrideActor(canonicalUuid(), PurchaseOverrideRole.OWNER)
            val preview = purchaseVoids.preview(ids.business, first.purchaseId, actor)
            assertTrue(preview is PreviewPurchaseVoidResult.Ready)
            val readyPreview = (preview as PreviewPurchaseVoidResult.Ready).preview
            val voided = purchaseVoids.void(
                PurchaseVoidCommand(
                    businessId = ids.business,
                    purchaseId = first.purchaseId,
                    reason = "E2E verifies the complete compensating transaction",
                    actor = actor,
                    expectedImpactHash = readyPreview.expectedImpactHash,
                ),
            )
            assertTrue(voided is PurchaseVoidResult.Voided)
            val voidPass = processOutbox(
                batchLimit = 10,
                targetCloudBusinessId = cloudBusinessId,
                targetAvailable = { true },
            ) as OutboxPassResult.Drained
            assertEquals(1, voidPass.completed)
            assertEquals(
                PurchaseStatus.VOIDED.name,
                database.purchaseDao().findById(first.purchaseId.value)?.status,
            )

            val finalPull = pullRemoteChanges(cloudBusinessId)
                .success("pull after conflict and void")
            assertTrue(finalPull.pulledCount >= 2)
            assertTrue(finalPull.latestSeq > initialPull.latestSeq)
            val cachedChanges = database.remoteSyncDao()
                .listPurchaseChanges(cloudBusinessId.value)
            assertTrue(cachedChanges.any { it.purchaseId == remoteCollisionId })
            assertTrue(
                cachedChanges.any {
                    it.purchaseId == first.purchaseId.value && it.status == PurchaseStatus.VOIDED.name
                },
            )

            callDevControl("devExpireCurrentUser")
            assertEquals(
                DomainResult.Failure(AccountError.SessionExpired),
                accounts.recoverSession(),
            )
            assertTrue(
                accounts.observeSession().first { it is AccountSession.Expired } is
                    AccountSession.Expired,
            )
        }
    }

    private suspend fun callDevControl(name: String) {
        val response = requireNotNull(runtime.functions())
            .getHttpsCallable(name)
            .call(emptyMap<String, Any>())
            .await()
            .data as? Map<*, *>
        assertEquals(true, response?.get("ok"))
    }

    private suspend fun seedLocalCatalog(ids: LocalCatalogIds) {
        val now = System.currentTimeMillis()
        database.businessDao().insert(
            BusinessEntity(
                businessId = ids.business.value,
                legalName = "Negocio E2E",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = ids.supplier.value,
                businessId = ids.business.value,
                legalName = SUPPLIER_NAME,
                ruc = SUPPLIER_RUC,
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.unitDao().insert(
            UnitEntity(
                unitId = ids.unit.value,
                businessId = ids.business.value,
                code = "NIU",
                name = "Unidad",
                symbol = "und",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = ids.location,
                businessId = ids.business.value,
                name = "Almacen E2E",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.productDao().insert(
            ProductEntity(
                productId = ids.product.value,
                businessId = ids.business.value,
                unitId = ids.unit.value,
                name = PRODUCT_NAME,
                locationId = ids.location,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun postLocalPurchase(
        ids: LocalCatalogIds,
        documentNumber: String,
        cloudBusinessId: BusinessId,
    ): PostedFixture {
        val draftId = requireNotNull(DraftId.parse(canonicalUuid()))
        val preparedLineId = requireNotNull(LineId.parse(canonicalUuid()))
        val purchaseId = requireNotNull(PurchaseId.parse(canonicalUuid()))
        val purchaseLineId = canonicalUuid()
        val now = System.currentTimeMillis() - 2_000L
        val createdAt = now - 10L
        val issueDate = LocalDate.of(2026, 8, 12)
        val document = "F001-$documentNumber"

        val preparedLine = PreparedPurchaseLine(
            lineId = preparedLineId,
            position = 0,
            productId = ids.product,
            unitId = ids.unit,
            description = PRODUCT_NAME,
            rawText = "2 PRODUCTO 5.00",
            quantity = Quantity.of("2"),
            unitCost = UnitCost.of("5.00", currency),
            discount = Money.ofMinor(0L, currency),
            tax = Money.ofMinor(180L, currency),
            lineTotal = Money.ofMinor(1_180L, currency),
            linkConfidence = 1_000,
            taxTreatment = InventoryTaxTreatment.EXCLUDED,
            taxEvidence = InventoryTaxEvidence.ExplicitAmount(BigDecimal("1.80")),
            productProvenance = PurchaseProductProvenance.EXISTING,
        )
        val logicalHash = PreparedPurchase.logicalHash(
            draftId = draftId,
            businessId = ids.business,
            supplierId = ids.supplier,
            supplierRuc = SUPPLIER_RUC,
            supplierLegalName = SUPPLIER_NAME,
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = document,
            issueDate = issueDate,
            currency = currency,
            lines = listOf(preparedLine),
            subtotal = Money.ofMinor(1_000L, currency),
            tax = Money.ofMinor(180L, currency),
            otherCharges = Money.ofMinor(0L, currency),
            total = Money.ofMinor(1_180L, currency),
            acceptedWarnings = emptyList(),
        )
        val prepared = PreparedPurchase(
            draftId = draftId,
            businessId = ids.business,
            supplierId = ids.supplier,
            supplierRuc = SUPPLIER_RUC,
            supplierLegalName = SUPPLIER_NAME,
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = document,
            issueDate = issueDate,
            currency = currency,
            lines = listOf(preparedLine),
            subtotal = Money.ofMinor(1_000L, currency),
            tax = Money.ofMinor(180L, currency),
            otherCharges = Money.ofMinor(0L, currency),
            total = Money.ofMinor(1_180L, currency),
            acceptedWarnings = emptyList(),
            logicalHash = logicalHash,
            preparedAt = Instant.ofEpochMilli(now - 1L),
        )
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = draftId.value,
                businessId = ids.business.value,
                createdAt = createdAt,
                updatedAt = now - 1L,
                status = DraftStatus.READY_TO_POST.name,
                supplierId = ids.supplier.value,
                supplierRucRaw = SUPPLIER_RUC,
                supplierRucNormalized = SUPPLIER_RUC,
                supplierLegalNameRaw = SUPPLIER_NAME,
                supplierLegalNameNormalized = SUPPLIER_NAME,
                documentType = PurchaseDocumentType.INVOICE.name,
                documentNumberRaw = document,
                documentNumberNormalized = document,
                issueDateRaw = issueDate.toString(),
                issueDateNormalized = issueDate.toString(),
                currencyCode = currency.value,
                subtotalMinorUnits = 1_000L,
                taxMinorUnits = 180L,
                otherChargesMinorUnits = 0L,
                totalMinorUnits = 1_180L,
                headerConfidence = 1_000,
            ),
        )
        val encoded = PreparedPurchaseCodec.encode(prepared)
        database.preparedPurchaseDao().upsert(
            PreparedPurchaseEntity(
                draftId = draftId.value,
                logicalHash = logicalHash,
                payloadCodecVersion = PreparedPurchaseCodec.VERSION,
                payloadSha256 = PreparedPurchaseCodec.sha256(encoded),
                payload = encoded,
                preparedAt = prepared.preparedAt.toEpochMilli(),
            ),
        )

        val previous = database.inventoryDao().findBalance(
            ids.business.value,
            ids.product.value,
            ids.location,
        )
        val costing = requireNotNull(
            (InventoryCostingService().calculate(
                InventoryCostingRequest(
                    currency = currency,
                    purchaseQuantity = BigDecimal("2"),
                    purchaseUnitFactor = BigDecimal.ONE,
                    readPurchaseUnitCost = BigDecimal("5.00"),
                    lineDiscount = BigDecimal.ZERO,
                    taxTreatment = InventoryTaxTreatment.EXCLUDED,
                    taxEvidence = InventoryTaxEvidence.ExplicitAmount(BigDecimal("1.80")),
                    costPolicy = CostPolicy.NET,
                    previousQuantity = previous?.quantityOnHand?.toBigDecimal() ?: BigDecimal.ZERO,
                    previousAverageUnitCost =
                        previous?.averageUnitCost?.toBigDecimal() ?: BigDecimal.ZERO,
                    roundingPolicy = InventoryCostRoundingPolicy(18, RoundingMode.HALF_EVEN),
                ),
            ) as? InventoryCostingResult.Calculated)?.calculation,
        )
        val outboxId = canonicalUuid()
        val auditId = canonicalUuid()
        val movementId = canonicalUuid()
        val outboxKey = "sync-purchase:v1:${purchaseId.value}"
        val purchase = PurchaseEntity(
            purchaseId = purchaseId.value,
            businessId = ids.business.value,
            sourceDraftId = draftId.value,
            supplierId = ids.supplier.value,
            documentType = PurchaseDocumentType.INVOICE.name,
            documentSeries = "F001",
            documentNumber = documentNumber,
            issueDate = issueDate.toString(),
            currencyCode = currency.value,
            subtotalMinorUnits = 1_000L,
            taxMinorUnits = 180L,
            otherChargesMinorUnits = 0L,
            totalMinorUnits = 1_180L,
            status = PurchaseStatus.POSTED.name,
            idempotencyKey = "purchase-post:v1:${draftId.value}",
            createdAt = createdAt,
            updatedAt = now,
            postedAt = now,
        )
        val line = PurchaseLineEntity(
            purchaseLineId = purchaseLineId,
            purchaseId = purchaseId.value,
            productId = ids.product.value,
            unitId = ids.unit.value,
            productNameSnapshot = PRODUCT_NAME,
            unitCodeSnapshot = "NIU",
            position = 0,
            rawText = preparedLine.rawText,
            description = preparedLine.description,
            quantity = "2",
            readUnitCost = "5.00",
            currencyCode = currency.value,
            taxMinorUnits = 180L,
            totalMinorUnits = 1_180L,
            confidence = 1_000,
            appliedUnitCost = costing.appliedInventoryUnitCost.toPlainString(),
            purchaseUnitFactor = "1",
            inventoryQuantity = costing.inventoryQuantity.toPlainString(),
            discount = "0",
            taxTreatment = InventoryTaxTreatment.EXCLUDED.name,
            costPolicy = CostPolicy.NET.name,
            appliedCostTotal = costing.appliedCostTotal.toPlainString(),
            taxEvidenceType = "EXPLICIT_AMOUNT",
            taxEvidenceValue = "1.80",
            roundingScale = 18,
            roundingMode = RoundingMode.HALF_EVEN.name,
            costingWarnings = costing.warnings.map { it.name }.sorted().joinToString(","),
            productProvenance = PurchaseProductProvenance.EXISTING.name,
        )
        database.purchasePostingDao().postAtomically(
            PurchasePostingBatch(
                purchase = purchase,
                expectedPreparedLogicalHash = logicalHash,
                lines = listOf(line),
                balanceMutations = listOf(
                    InventoryBalanceMutation(
                        expectedVersion = previous?.version,
                        balance = InventoryBalanceEntity(
                            businessId = ids.business.value,
                            productId = ids.product.value,
                            locationId = ids.location,
                            quantityOnHand = costing.resultingQuantity.toPlainString(),
                            averageUnitCost = costing.resultingAverageUnitCost.toPlainString(),
                            currencyCode = currency.value,
                            version = previous?.version?.plus(1L) ?: 0L,
                            updatedAt = now,
                        ),
                    ),
                ),
                movements = listOf(
                    StockMovementEntity(
                        movementId = movementId,
                        businessId = ids.business.value,
                        purchaseId = purchaseId.value,
                        purchaseLineId = purchaseLineId,
                        productId = ids.product.value,
                        locationId = ids.location,
                        type = StockMovementType.PURCHASE.name,
                        quantityDelta = costing.inventoryQuantity.toPlainString(),
                        unitCost = costing.appliedInventoryUnitCost.toPlainString(),
                        currencyCode = currency.value,
                        idempotencyKey = "purchase-movement:v1:$purchaseLineId",
                        occurredAt = now,
                        createdAt = now,
                    ),
                ),
                auditEvents = listOf(
                    AuditEventEntity(
                        auditEventId = auditId,
                        businessId = ids.business.value,
                        purchaseId = purchaseId.value,
                        eventType = AuditEventType.PURCHASE_POSTED.name,
                        entityType = "PURCHASE",
                        entityId = purchaseId.value,
                        payload = "{\"version\":1}",
                        occurredAt = now,
                    ),
                ),
                outboxOperations = listOf(
                    OutboxOperationEntity(
                        operationId = outboxId,
                        businessId = ids.business.value,
                        purchaseId = purchaseId.value,
                        idempotencyKey = outboxKey,
                        operationType = "SYNC_PURCHASE",
                        payload = "{\"version\":3,\"purchaseId\":\"${purchaseId.value}\"}",
                        status = OutboxOperationStatus.PENDING.name,
                        createdAt = now,
                        updatedAt = now,
                        nextAttemptAt = now,
                        payloadVersion = 3,
                        entityType = "PURCHASE",
                        entityId = purchaseId.value,
                        entityVersion = 1L,
                        targetCloudBusinessId = cloudBusinessId.value,
                    ),
                ),
            ),
        )
        return PostedFixture(purchaseId, outboxId)
    }

    private suspend fun seedRemotePurchase(
        functions: FirebaseFunctions,
        uid: String,
        cloudBusinessId: BusinessId,
        purchaseId: String,
        documentNumber: String,
    ) {
        val lineId = canonicalUuid()
        val productId = canonicalUuid()
        val locationId = canonicalUuid()
        val idempotencyKey = "sync-purchase:v1:$purchaseId"
        val postedAt = System.currentTimeMillis() - 1_000L
        val document = linkedMapOf<String, Any?>(
            "version" to 2,
            "purchaseId" to purchaseId,
            "businessId" to cloudBusinessId.value,
            "status" to PurchaseStatus.POSTED.name,
            "documentType" to PurchaseDocumentType.INVOICE.name,
            "documentSeries" to "F001",
            "documentNumber" to documentNumber,
            "issueDate" to "2026-08-12",
            "currency" to "PEN",
            "supplierRuc" to SUPPLIER_RUC,
            "supplierLegalName" to SUPPLIER_NAME,
            "subtotalMinorUnits" to 1_000L,
            "taxMinorUnits" to 180L,
            "otherChargesMinorUnits" to 0L,
            "totalMinorUnits" to 1_180L,
            "adjustmentMinorUnits" to null,
            "adjustmentReason" to null,
            "preparedLogicalHash" to "a".repeat(64),
            "postedAt" to postedAt,
            "idempotencyKey" to idempotencyKey,
            "lines" to listOf(
                linkedMapOf<String, Any?>(
                    "purchaseLineId" to lineId,
                    "position" to 0,
                    "productId" to productId,
                    "productName" to "Producto dispositivo remoto",
                    "unitCode" to "NIU",
                    "description" to "Producto dispositivo remoto",
                    "quantity" to "2",
                    "readUnitCost" to "5.00",
                    "taxMinorUnits" to 180L,
                    "totalMinorUnits" to 1_180L,
                    "appliedUnitCost" to "5.00",
                    "inventoryQuantity" to "2",
                    "discount" to "0",
                    "taxTreatment" to InventoryTaxTreatment.EXCLUDED.name,
                    "taxEvidence" to mapOf("type" to "EXPLICIT_AMOUNT", "value" to "1.80"),
                    "productProvenance" to PurchaseProductProvenance.EXISTING.name,
                ),
            ),
            "movements" to listOf(
                mapOf(
                    "movementId" to canonicalUuid(),
                    "purchaseLineId" to lineId,
                    "productId" to productId,
                    "locationId" to locationId,
                    "type" to StockMovementType.PURCHASE.name,
                    "quantityDelta" to "2",
                    "unitCost" to "5.00",
                    "occurredAt" to postedAt,
                ),
            ),
            "auditEventIds" to listOf(canonicalUuid()),
            "duplicateOverride" to null,
        )
        val response = functions.getHttpsCallable("postPurchase").call(
            mapOf(
                "businessId" to cloudBusinessId.value,
                "expectedUid" to uid,
                "idempotencyKey" to idempotencyKey,
                "operationType" to "SYNC_PURCHASE",
                "payloadVersion" to 3,
                "document" to document,
            ),
        ).await().data as? Map<*, *>
        assertEquals(idempotencyKey, response?.get("idempotencyKey"))
        assertNotNull(response?.get("receiptId"))
    }

    private fun canonicalUuid(): String = UUID.randomUUID().toString()

    private fun newLocalCatalogIds(): LocalCatalogIds = LocalCatalogIds(
        business = requireNotNull(BusinessId.parse(canonicalUuid())),
        supplier = requireNotNull(SupplierId.parse(canonicalUuid())),
        unit = requireNotNull(UnitId.parse(canonicalUuid())),
        product = requireNotNull(ProductId.parse(canonicalUuid())),
        location = canonicalUuid(),
    )

    private fun <T> DomainResult<T>.success(stage: String): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error("$stage failed with closed error $error")
    }

    private data class LocalCatalogIds(
        val business: BusinessId,
        val supplier: SupplierId,
        val unit: UnitId,
        val product: ProductId,
        val location: String,
    )

    private data class PostedFixture(
        val purchaseId: PurchaseId,
        val outboxId: String,
    )

    private companion object {
        const val SUPPLIER_RUC = "20123456789"
        const val SUPPLIER_NAME = "Proveedor E2E"
        const val PRODUCT_NAME = "Producto E2E"
    }
}
