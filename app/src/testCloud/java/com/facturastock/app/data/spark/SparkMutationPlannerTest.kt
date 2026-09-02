package com.facturastock.app.data.spark

import com.facturastock.app.data.repository.SaleContentIdentity
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SharedSaleCredit
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.SharedSaleLine
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.RemoteDebtPaymentDocument
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SparkMutationPlannerTest {
    @Test
    fun `balance id and sale receipt stay compatible with Functions`() {
        assertEquals(
            "377ed51e73752fb01b9343da9e3d41512455c916e9990399cc7fc0f89d2b1d88",
            SparkFirestoreSchema.inventoryBalanceDocumentId(PRODUCT_ID, LOCATION_NAME),
        )
        assertEquals(
            "sale_e9aedae1db6c95af5fb5757f6e3f6294",
            SparkFirestoreSchema.saleReceiptId(BUSINESS_ID, "sync-sale:v1:$SALE_ID"),
        )
        assertEquals(
            "inventory_bootstrap_da1d6eeb2353d3c97e5657ce01054196",
            SparkFirestoreSchema.inventoryBootstrapReceiptId(BUSINESS_ID),
        )
    }

    @Test
    fun `wire maps local product to cloud identity and authenticates credit`() {
        val document = saleDocument(credit = true)
        val outbound = SparkSaleWire.prepare(
            document = document,
            cloudBusinessId = businessId(),
            remoteProductIds = mapOf(productId() to REMOTE_PRODUCT_ID),
        )

        @Suppress("UNCHECKED_CAST")
        val wireLine = (outbound.wireDocument.getValue("lines") as List<Map<String, Any?>>).single()
        assertEquals(REMOTE_PRODUCT_ID, wireLine["productId"])
        assertNotEquals(document.contentHash, outbound.wireDocument["contentHash"])
        assertEquals("sync-sale:v1:$SALE_ID", outbound.operationId)
        assertEquals("sale_e9aedae1db6c95af5fb5757f6e3f6294", outbound.receiptId)
        assertEquals(2L, outbound.wireDocument["version"])
        assertEquals(DEBT_ID, (outbound.wireDocument["credit"] as Map<*, *>)["debtId"])
    }

    @Test
    fun `sale and debt request hashes stay compatible with Blaze JSON stringify`() {
        assertEquals(
            "b9d86fc243813e38a5141570223393bbefb9cd771efdbdd4e9e1029106b2673c",
            outbound().payloadHash,
        )
        assertEquals(
            "1bc90fb0a4ac9a9665fcd79f2911be128d581e1dbcc825bf00f256231018aff5",
            debtPaymentPayloadHash(
                businessId = businessId(),
                document = paymentDocument(amountMinorUnits = 2_000L, expectedVersion = 1L),
            ),
        )
    }

    @Test
    fun `sale plan decrements stock once and advances exact versions`() {
        val outbound = outbound()
        val target = outbound.targets.single()
        val plan = SparkSaleMutationPlanner.plan(
            outbound = outbound,
            metadataSeq = 5L,
            rawBalancesByDocumentId = mapOf(target.balanceDocumentId to balance(target, version = 3L)),
            now = Instant.ofEpochMilli(1_100L),
        )

        val result = plan.balances.single()
        assertEquals(6L, plan.seq)
        assertEquals(Instant.ofEpochMilli(1_200L), plan.postedAt)
        assertEquals("8", result.quantityOnHand)
        assertEquals(3L, result.expectedVersion)
        assertEquals(4L, result.resultingVersion)
        assertEquals("50", result.averageUnitCost)
    }

    @Test
    fun `sale plan reports exact insufficient stock without a partial plan`() {
        val outbound = outbound()
        val target = outbound.targets.single()

        val failure = assertThrows(SparkMutationFailure.InsufficientStock::class.java) {
            SparkSaleMutationPlanner.plan(
                outbound = outbound,
                metadataSeq = 1L,
                rawBalancesByDocumentId = mapOf(
                    target.balanceDocumentId to balance(target, quantity = "1"),
                ),
                now = Instant.ofEpochMilli(1_100L),
            )
        }

        assertEquals("1", failure.available)
        assertEquals(Quantity.of("2"), failure.requested)
        assertEquals(productId(), failure.target.localKey.productId)
    }

    @Test
    fun `sale plan requires inventory bootstrap when balance is absent`() {
        val outbound = outbound()

        assertThrows(SparkMutationFailure.InventoryMigrationRequired::class.java) {
            SparkSaleMutationPlanner.plan(
                outbound = outbound,
                metadataSeq = 0L,
                rawBalancesByDocumentId = emptyMap(),
                now = Instant.ofEpochMilli(1_100L),
            )
        }
    }

    @Test
    fun `purchase bootstrap reversal restores quantity and weighted average`() {
        val restored = SparkInventoryBootstrapper.reversePendingEffect(
            current = bootstrapBalance(quantity = "15", average = "60"),
            effect = pendingEffect(quantityDelta = "5", incomingValue = "400"),
        )

        assertEquals(BigDecimal("10"), restored.quantityOnHand)
        assertEquals(BigDecimal("50"), restored.averageUnitCost)
    }

    @Test
    fun `void bootstrap reversal restores quantity and preserves average`() {
        val restored = SparkInventoryBootstrapper.reversePendingEffect(
            current = bootstrapBalance(quantity = "8", average = "50"),
            effect = pendingEffect(
                quantityDelta = "-2",
                incomingValue = "0",
                preserveAverage = true,
            ),
        )

        assertEquals(BigDecimal("10"), restored.quantityOnHand)
        assertEquals(BigDecimal("50"), restored.averageUnitCost)
    }

    @Test
    fun `purchase bootstrap reversal rejects a negative opening balance`() {
        assertThrows(SparkMutationFailure.InventoryMigrationRequired::class.java) {
            SparkInventoryBootstrapper.reversePendingEffect(
                current = bootstrapBalance(quantity = "2", average = "50"),
                effect = pendingEffect(quantityDelta = "5", incomingValue = "250"),
            )
        }
    }

    @Test
    fun `debt payment uses expected version and closes exact remaining balance`() {
        val document = paymentDocument(amountMinorUnits = 2_000L, expectedVersion = 1L)
        val plan = SparkDebtPaymentMutationPlanner.plan(
            cloudBusinessId = businessId(),
            document = document,
            rawDebt = debt(balanceMinorUnits = 2_000L, version = 1L),
            metadataSeq = 6L,
            now = Instant.ofEpochMilli(1_500L),
        )

        assertEquals(7L, plan.seq)
        assertEquals(0L, plan.debt.balance.minorUnits)
        assertEquals("PAID", plan.debt.status.name)
        assertEquals(2L, plan.debt.version)
        assertEquals(Instant.ofEpochMilli(1_500L), plan.debt.paidAt)
        assertEquals(plan.debt.balance, plan.payment.balanceAfter)
        assertEquals(1L, plan.payment.expectedDebtVersion)
    }

    @Test
    fun `debt payment rejects a stale expected version`() {
        assertThrows(SparkMutationFailure.Stale::class.java) {
            SparkDebtPaymentMutationPlanner.plan(
                cloudBusinessId = businessId(),
                document = paymentDocument(amountMinorUnits = 100L, expectedVersion = 1L),
                rawDebt = debt(balanceMinorUnits = 2_000L, version = 2L),
                metadataSeq = 6L,
                now = Instant.ofEpochMilli(1_500L),
            )
        }
    }

    private fun outbound(): SparkOutboundSale = SparkSaleWire.prepare(
        document = saleDocument(credit = false),
        cloudBusinessId = businessId(),
        remoteProductIds = emptyMap(),
    )

    private fun saleDocument(credit: Boolean): SharedSaleDocument {
        val currency = CurrencyCode.of("PEN")
        val line = SharedSaleLine(
            saleLineId = requireNotNull(SaleLineId.parse(SALE_LINE_ID)),
            position = 0,
            productId = productId(),
            unitId = requireNotNull(UnitId.parse(UNIT_ID)),
            locationId = requireNotNull(LocationId.parse(LOCATION_ID)),
            productName = "Producto",
            unitCode = "UND",
            locationName = LOCATION_NAME,
            barcode = null,
            quantity = Quantity.of("2"),
            unitPrice = Money.ofMinor(1_000L, currency),
            discount = Money.ofMinor(0L, currency),
            tax = Money.ofMinor(0L, currency),
            lineTotal = Money.ofMinor(2_000L, currency),
        )
        return SharedSaleDocument(
            saleId = requireNotNull(SaleId.parse(SALE_ID)),
            currency = currency,
            subtotal = Money.ofMinor(2_000L, currency),
            discount = Money.ofMinor(0L, currency),
            tax = Money.ofMinor(0L, currency),
            total = Money.ofMinor(2_000L, currency),
            contentHash = "a".repeat(64),
            checkoutIdempotencyKey = "sale-checkout:v1:$SALE_ID:1:${"a".repeat(64)}",
            createdAt = Instant.ofEpochMilli(900L),
            updatedAt = Instant.ofEpochMilli(1_000L),
            postedAt = Instant.ofEpochMilli(1_000L),
            lines = listOf(line),
            credit = if (credit) {
                SharedSaleCredit(
                    debtId = requireNotNull(DebtId.parse(DEBT_ID)),
                    debtorNameSnapshot = "María Pérez",
                    dueAt = null,
                )
            } else {
                null
            },
        )
    }

    private fun balance(
        target: SparkSaleInventoryTarget,
        quantity: String = "10.0",
        version: Long = 1L,
    ): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 1L,
        "businessId" to BUSINESS_ID,
        "productId" to target.remoteProductId,
        "locationName" to LOCATION_NAME,
        "canonicalLocationName" to "almacén principal",
        "quantityOnHand" to quantity,
        "averageUnitCost" to "50.00",
        "currency" to "PEN",
        "version" to version,
        "lastSeq" to 5L,
        "updatedAtMillis" to 1_200L,
        "lastSourceLocationId" to LOCATION_ID,
        "lastOperationId" to "operation",
        "expectedVersion" to version - 1L,
    )

    private fun debt(balanceMinorUnits: Long, version: Long): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 1L,
        "debtId" to DEBT_ID,
        "businessId" to BUSINESS_ID,
        "saleId" to SALE_ID,
        "debtorNameSnapshot" to "María Pérez",
        "currency" to "PEN",
        "originalMinorUnits" to 2_000L,
        "balanceMinorUnits" to balanceMinorUnits,
        "status" to "OPEN",
        "dueAt" to null,
        "version" to version,
        "createdAt" to 1_000L,
        "updatedAt" to 1_200L,
        "paidAt" to null,
    )

    private fun bootstrapBalance(
        quantity: String,
        average: String,
    ): SparkBootstrapBalance = SparkBootstrapBalance(
        remoteProductId = PRODUCT_ID,
        sourceLocationId = LOCATION_ID,
        locationName = LOCATION_NAME,
        balanceDocumentId = SparkFirestoreSchema.inventoryBalanceDocumentId(
            PRODUCT_ID,
            LOCATION_NAME,
        ),
        quantityOnHand = BigDecimal(quantity),
        averageUnitCost = BigDecimal(average),
        currency = CurrencyCode.of("PEN"),
        updatedAtMillis = 1_200L,
    )

    private fun pendingEffect(
        quantityDelta: String,
        incomingValue: String,
        preserveAverage: Boolean = false,
    ): SparkPendingInventoryEffect = SparkPendingInventoryEffect(
        remoteProductId = PRODUCT_ID,
        locationName = LOCATION_NAME,
        canonicalLocationName = "almacén principal",
        quantityDelta = BigDecimal(quantityDelta),
        incomingValue = BigDecimal(incomingValue),
        currency = CurrencyCode.of("PEN"),
        preserveAverage = preserveAverage,
    )

    private fun paymentDocument(
        amountMinorUnits: Long,
        expectedVersion: Long,
    ): RemoteDebtPaymentDocument {
        val currency = CurrencyCode.of("PEN")
        val debtId = requireNotNull(DebtId.parse(DEBT_ID))
        val paymentId = requireNotNull(DebtPaymentId.parse(PAYMENT_ID))
        return RemoteDebtPaymentDocument(
            paymentId = paymentId,
            debtId = debtId,
            businessId = businessId(),
            amount = Money.ofMinor(amountMinorUnits, currency),
            method = DebtPaymentMethod.YAPE,
            note = null,
            reference = "Operación 123",
            expectedDebtVersion = expectedVersion,
            occurredAt = Instant.ofEpochMilli(1_300L),
            createdAt = Instant.ofEpochMilli(1_300L),
            idempotencyKey = "debt-payment:v1:$DEBT_ID:$PAYMENT_ID",
        )
    }

    private fun businessId(): BusinessId = requireNotNull(BusinessId.parse(BUSINESS_ID))

    private fun productId(): ProductId = requireNotNull(ProductId.parse(PRODUCT_ID))

    private companion object {
        const val BUSINESS_ID = "00000000-0000-0000-0000-000000000001"
        const val SALE_ID = "00000000-0000-0000-0000-000000000002"
        const val SALE_LINE_ID = "00000000-0000-0000-0000-000000000003"
        const val PRODUCT_ID = "00000000-0000-0000-0000-000000000004"
        const val UNIT_ID = "00000000-0000-0000-0000-000000000005"
        const val LOCATION_ID = "00000000-0000-0000-0000-000000000006"
        const val PAYMENT_ID = "00000000-0000-0000-0000-000000000007"
        val DEBT_ID: String = SaleContentIdentity.uuid("sale-debt", SALE_ID).toString()
        const val REMOTE_PRODUCT_ID = "00000000-0000-0000-0000-000000000008"
        const val LOCATION_NAME = "Almacén principal"
    }
}
