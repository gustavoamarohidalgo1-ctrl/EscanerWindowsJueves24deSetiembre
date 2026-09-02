package com.facturastock.app.data.spark

import com.facturastock.app.data.local.entity.RemoteSyncStateEntity
import com.facturastock.app.domain.model.CurrencyCode
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SparkInventoryOnboardingPolicyTest {
    @Test
    fun `negative legacy baseline is valid bootstrap input`() {
        assertEquals(BigDecimal("-1"), parseSparkBootstrapQuantity("-1"))
    }

    @Test
    fun `void reversal may restore a negative legacy baseline`() {
        val restored = SparkInventoryBootstrapper.reversePendingEffect(
            current = bootstrapBalance(quantity = "-3"),
            effect = SparkPendingInventoryEffect(
                remoteProductId = PRODUCT_ID,
                locationName = "Almacén",
                canonicalLocationName = "almacén",
                quantityDelta = BigDecimal("-2"),
                incomingValue = BigDecimal.ZERO,
                currency = CurrencyCode.of("PEN"),
                preserveAverage = true,
            ),
        )

        assertEquals(BigDecimal("-1"), restored.quantityOnHand)
        assertEquals(BigDecimal("5"), restored.averageUnitCost)
    }

    @Test
    fun `positive purchase crossing from negative baseline requires assisted migration`() {
        assertThrows(SparkMutationFailure.InventoryMigrationRequired::class.java) {
            SparkInventoryBootstrapper.reversePendingEffect(
                current = bootstrapBalance(quantity = "1"),
                effect = SparkPendingInventoryEffect(
                    remoteProductId = PRODUCT_ID,
                    locationName = "Almacén",
                    canonicalLocationName = "almacén",
                    quantityDelta = BigDecimal("2"),
                    incomingValue = BigDecimal("10"),
                    currency = CurrencyCode.of("PEN"),
                    preserveAverage = false,
                ),
            )
        }
    }

    @Test
    fun `lost bootstrap ack and a second pending purchase keep the causal ledger identity`() {
        val firstPending = pendingPurchaseEffect(
            productId = PRODUCT_ID,
            quantity = "2",
            incomingValue = "10",
        )
        val firstRecovered = SparkInventoryBootstrapper.reversePendingEffect(
            current = bootstrapBalance(
                productId = PRODUCT_ID,
                quantity = "12",
                average = "5",
                updatedAtMillis = 100L,
            ),
            effect = firstPending,
        )
        val committedLedgerHash = sparkInventoryCausalLedgerHash(
            BUSINESS_ID,
            listOf(firstRecovered),
        )

        // El commit remoto ya ocurrio pero Room aun conserva cursor cero. Antes del retry, P2
        // vuelve a tocar la clave existente y crea por primera vez otra fila de inventario.
        val secondExistingEffect = pendingPurchaseEffect(
            productId = PRODUCT_ID,
            quantity = "3",
            incomingValue = "15",
        )
        val secondNewEffect = pendingPurchaseEffect(
            productId = OTHER_PRODUCT_ID,
            quantity = "4",
            incomingValue = "28",
        )
        val existingAfterBoth = bootstrapBalance(
            productId = PRODUCT_ID,
            quantity = "15",
            average = "5",
            updatedAtMillis = 200L,
        )
        val existingBeforeBoth = SparkInventoryBootstrapper.reversePendingEffect(
            current = SparkInventoryBootstrapper.reversePendingEffect(
                current = existingAfterBoth,
                effect = secondExistingEffect,
            ),
            effect = firstPending,
        )
        val newRowBeforeBoth = SparkInventoryBootstrapper.reversePendingEffect(
            current = bootstrapBalance(
                productId = OTHER_PRODUCT_ID,
                quantity = "4",
                average = "7",
                updatedAtMillis = 200L,
            ),
            effect = secondNewEffect,
        )
        val recoveredLedgerHash = sparkInventoryCausalLedgerHash(
            BUSINESS_ID,
            listOf(existingBeforeBoth, newRowBeforeBoth),
        )

        assertNotEquals(firstRecovered.updatedAtMillis, existingBeforeBoth.updatedAtMillis)
        assertEquals(BigDecimal.ZERO, newRowBeforeBoth.quantityOnHand)
        assertEquals(committedLedgerHash, recoveredLedgerHash)
        assertEquals(
            SparkInventoryOnboardingDecision.ACKNOWLEDGE_MATCHING_BASELINE,
            SparkInventoryOnboardingPolicy.decide(
                localInventorySeq = 0L,
                requestedSinceSeq = 0L,
                localLedgerHash = recoveredLedgerHash,
                remoteEvidence = SparkInventoryBootstrapEvidence(committedLedgerHash, 1L),
            ),
        )
    }

    @Test
    fun `causal ledger ignores presentation metadata but changes with economic state`() {
        val baseline = bootstrapBalance(
            quantity = "10",
            average = "5",
            updatedAtMillis = 100L,
        )
        val sameState = baseline.copy(
            sourceLocationId = "55555555-5555-4555-8555-555555555555",
            locationName = "  ALMACÉN  ",
            balanceDocumentId = "e".repeat(64),
            updatedAtMillis = 999L,
            minimumProductVersion = 99L,
        )

        assertEquals(
            sparkInventoryCausalLedgerHash(BUSINESS_ID, listOf(baseline)),
            sparkInventoryCausalLedgerHash(BUSINESS_ID, listOf(sameState)),
        )
        assertNotEquals(
            sparkInventoryCausalLedgerHash(BUSINESS_ID, listOf(baseline)),
            sparkInventoryCausalLedgerHash(
                BUSINESS_ID,
                listOf(baseline.copy(quantityOnHand = BigDecimal("11"))),
            ),
        )
    }

    @Test
    fun `device A acknowledges its baseline while divergent device B is rejected`() {
        val evidence = SparkInventoryBootstrapEvidence(ledgerHash = HASH_A, metadataSeq = 1L)

        assertEquals(
            SparkInventoryOnboardingDecision.ACKNOWLEDGE_MATCHING_BASELINE,
            SparkInventoryOnboardingPolicy.decide(0L, 0L, HASH_A, evidence),
        )
        assertEquals(
            SparkInventoryOnboardingDecision.REJECT,
            SparkInventoryOnboardingPolicy.decide(0L, 0L, HASH_B, evidence),
        )
    }

    @Test
    fun `effectively empty device may adopt the remote baseline`() {
        assertEquals(
            SparkInventoryOnboardingDecision.CONTINUE,
            SparkInventoryOnboardingPolicy.decide(
                localInventorySeq = 0L,
                requestedSinceSeq = 0L,
                localLedgerHash = null,
                remoteEvidence = SparkInventoryBootstrapEvidence(HASH_A, 8L),
            ),
        )
    }

    @Test
    fun `stale requested cursor is rejected before exposing a page`() {
        assertEquals(
            SparkInventoryOnboardingDecision.REJECT,
            SparkInventoryOnboardingPolicy.decide(
                localInventorySeq = 1L,
                requestedSinceSeq = 0L,
                localLedgerHash = null,
                remoteEvidence = SparkInventoryBootstrapEvidence(HASH_A, 4L),
            ),
        )
    }

    @Test
    fun `bootstrap acknowledgement preserves all other durable cursors`() {
        val state = RemoteSyncStateEntity(
            cloudBusinessId = BUSINESS_ID,
            purchaseSeq = 7L,
            purchasePulledAt = 101L,
            catalogSeq = 5L,
            catalogPulledAt = 202L,
            inventorySeq = 0L,
            inventoryPulledAt = 303L,
        )

        assertEquals(
            state.copy(inventorySeq = 1L),
            SparkInventoryOnboardingPolicy.acknowledgeBootstrap(state),
        )
    }

    @Test
    fun `bootstrap acknowledgement does not move a cursor advanced by concurrent pull`() {
        val advanced = RemoteSyncStateEntity(
            cloudBusinessId = BUSINESS_ID,
            purchaseSeq = 7L,
            catalogSeq = 5L,
            inventorySeq = 9L,
            inventoryPulledAt = 303L,
        )

        assertEquals(advanced, SparkInventoryOnboardingPolicy.acknowledgeBootstrap(advanced))
    }

    @Test
    fun `remote evidence must bind metadata bootstrap and first change`() {
        val documents = evidenceDocuments()

        val evidence = sparkInventoryBootstrapEvidence(
            metadata = documents.metadata,
            bootstrap = documents.bootstrap,
            change = documents.change,
            cloudBusinessId = BUSINESS_ID,
        )

        assertNotNull(evidence)
        assertEquals(documents.ledgerHash, evidence?.ledgerHash)
        assertNull(
            sparkInventoryBootstrapEvidence(
                metadata = documents.metadata,
                bootstrap = documents.bootstrap,
                change = documents.change + ("receiptId" to "inventory_bootstrap_${"f".repeat(32)}"),
                cloudBusinessId = BUSINESS_ID,
            ),
        )
        assertNull(
            sparkInventoryBootstrapEvidence(
                metadata = documents.metadata + ("bootstrapLedgerHash" to HASH_A),
                bootstrap = documents.bootstrap + ("ledgerHash" to HASH_A),
                change = documents.change,
                cloudBusinessId = BUSINESS_ID,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val changedProjection = (
            documents.change.getValue("balances") as List<Map<String, Any?>>
        ).map { balance -> balance + ("quantityOnHand" to "11") }
        assertNull(
            sparkInventoryBootstrapEvidence(
                metadata = documents.metadata,
                bootstrap = documents.bootstrap,
                change = documents.change + ("balances" to changedProjection),
                cloudBusinessId = BUSINESS_ID,
            ),
        )
    }

    @Test
    fun `bootstrap product must be the active resolved tenant entity`() {
        val product = productDocument()

        assertTrue(
            isCompatibleSparkBootstrapProduct(
                data = product,
                cloudBusinessId = BUSINESS_ID,
                ownerUid = OWNER_UID,
                remoteProductId = PRODUCT_ID,
                minimumVersion = 2L,
            ),
        )
        assertFalse(
            isCompatibleSparkBootstrapProduct(
                data = product,
                cloudBusinessId = BUSINESS_ID,
                ownerUid = OWNER_UID,
                remoteProductId = OTHER_PRODUCT_ID,
                minimumVersion = 2L,
            ),
        )
        assertFalse(
            isCompatibleSparkBootstrapProduct(
                data = product,
                cloudBusinessId = BUSINESS_ID,
                ownerUid = OWNER_UID,
                remoteProductId = PRODUCT_ID,
                minimumVersion = 3L,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val inactiveSnapshot = LinkedHashMap(product.getValue("snapshot") as Map<String, Any?>)
            .apply { this["status"] = "INACTIVE" }
        assertFalse(
            isCompatibleSparkBootstrapProduct(
                data = product + ("snapshot" to inactiveSnapshot),
                cloudBusinessId = BUSINESS_ID,
                ownerUid = OWNER_UID,
                remoteProductId = PRODUCT_ID,
                minimumVersion = 2L,
            ),
        )
    }

    private fun evidenceDocuments(): EvidenceDocuments {
        val operationId = SparkFirestoreSchema.inventoryBootstrapOperationId(BUSINESS_ID)
        val receiptId = SparkFirestoreSchema.inventoryBootstrapReceiptId(BUSINESS_ID)
        val requestHash = "c".repeat(64)
        val projectedBalances = listOf(
            mapOf(
                "productId" to PRODUCT_ID,
                "locationName" to "Almacén",
                "quantityOnHand" to "10",
                "averageUnitCost" to "5",
                "currency" to "PEN",
                "version" to 0L,
                "updatedAtMillis" to 100L,
                "seq" to 1L,
            ),
        )
        val ledgerHash = sparkInventoryCausalLedgerHash(
            BUSINESS_ID,
            listOf(bootstrapBalance(quantity = "10", average = "5", updatedAtMillis = 100L)),
        )
        return EvidenceDocuments(
            metadata = mapOf(
                "schemaVersion" to 1L,
                "businessId" to BUSINESS_ID,
                "seq" to 4L,
                "bootstrapComplete" to true,
                "bootstrapLedgerHash" to ledgerHash,
            ),
            bootstrap = mapOf(
                "schemaVersion" to 1L,
                "businessId" to BUSINESS_ID,
                "operationId" to operationId,
                "idempotencyKey" to operationId,
                "requestHash" to requestHash,
                "ledgerHash" to ledgerHash,
                "receiptId" to receiptId,
                "seq" to 1L,
                "balances" to projectedBalances,
            ),
            change = mapOf(
                "schemaVersion" to 1L,
                "businessId" to BUSINESS_ID,
                "operationId" to operationId,
                "kind" to "PURCHASE",
                "receiptId" to receiptId,
                "seq" to 1L,
                "bootstrap" to true,
                "balances" to projectedBalances,
            ),
            ledgerHash = ledgerHash,
        )
    }

    private fun productDocument(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1L,
        "businessId" to BUSINESS_ID,
        "ownerUid" to OWNER_UID,
        "entityId" to PRODUCT_ID,
        "entityType" to "PRODUCT",
        "mutation" to "UPSERT",
        "version" to 2L,
        "snapshot" to mapOf(
            "status" to "ACTIVE",
            "inventoryUnit" to mapOf(
                "code" to "NIU",
                "status" to "ACTIVE",
            ),
        ),
    )

    private fun bootstrapBalance(
        productId: String = PRODUCT_ID,
        quantity: String,
        average: String = "5",
        updatedAtMillis: Long = 1L,
    ): SparkBootstrapBalance = SparkBootstrapBalance(
        remoteProductId = productId,
        sourceLocationId = "44444444-4444-4444-8444-444444444444",
        locationName = "Almacén",
        balanceDocumentId = "d".repeat(64),
        quantityOnHand = BigDecimal(quantity),
        averageUnitCost = BigDecimal(average),
        currency = CurrencyCode.of("PEN"),
        updatedAtMillis = updatedAtMillis,
    )

    private fun pendingPurchaseEffect(
        productId: String,
        quantity: String,
        incomingValue: String,
    ): SparkPendingInventoryEffect = SparkPendingInventoryEffect(
        remoteProductId = productId,
        locationName = "Almacén",
        canonicalLocationName = "almacén",
        quantityDelta = BigDecimal(quantity),
        incomingValue = BigDecimal(incomingValue),
        currency = CurrencyCode.of("PEN"),
        preserveAverage = false,
    )

    private data class EvidenceDocuments(
        val metadata: Map<String, Any?>,
        val bootstrap: Map<String, Any?>,
        val change: Map<String, Any?>,
        val ledgerHash: String,
    )

    private companion object {
        const val BUSINESS_ID = "22222222-2222-4222-8222-222222222222"
        const val PRODUCT_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_PRODUCT_ID = "33333333-3333-4333-8333-333333333333"
        const val OWNER_UID = "owner-uid"
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
    }
}
