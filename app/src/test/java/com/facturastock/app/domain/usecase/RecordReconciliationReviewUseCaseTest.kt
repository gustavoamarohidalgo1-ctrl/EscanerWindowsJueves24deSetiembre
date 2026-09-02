package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.AmbiguousRemotePurchase
import com.facturastock.app.domain.model.AmbiguousRemoteProduct
import com.facturastock.app.domain.model.BalanceDifference
import com.facturastock.app.domain.model.MatchedRemotePurchase
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.ReconciliationReport
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.UnlinkedRemoteProduct
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.testing.FakeAuditTrailRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordReconciliationReviewUseCaseTest {
    private var now: Instant = Instant.parse("2026-08-16T12:00:00Z")
    private val clock = AppClock { now }
    private val auditTrail = FakeAuditTrailRepository()
    private val recordReview = RecordReconciliationReviewUseCase(
        auditTrail = auditTrail,
        uuids = UuidGenerator { AUDIT_UUID },
        clock = clock,
    )

    @Test
    fun `registra la revision con los conteos del reporte incluida la ambiguedad`() = runTest {
        val report = report(
            latestSeq = 7L,
            matchedCount = 2,
            ambiguousCount = 1,
            remoteOnlyCount = 3,
            balanceDifferenceCount = 1,
            unlinkedCount = 4,
            ambiguousProductCount = 2,
        )

        recordReview(report)

        val event = auditTrail.events.single()
        assertEquals(AUDIT_UUID.toString(), event.auditEventId)
        assertEquals(BUSINESS_ID, event.businessId)
        assertNull(event.purchaseId)
        assertEquals(AuditEventType.SYNC_RECONCILED, event.eventType)
        assertEquals("business", event.entityType)
        assertEquals(BUSINESS_ID.value, event.entityId)
        assertEquals(now, event.occurredAt)
        assertEquals(
            mapOf(
                "version" to "1",
                "latestSeq" to "7",
                "matchedCount" to "2",
                "ambiguousCount" to "1",
                "remoteOnlyCount" to "3",
                "balanceDifferencesCount" to "1",
                "unlinkedProductsCount" to "4",
                "ambiguousProductsCount" to "2",
            ),
            event.payload,
        )
    }

    @Test
    fun `un fallo de la bitacora se propaga sin registrar el evento`() = runTest {
        auditTrail.nextException = StorageException(StorageError.InsufficientSpace)

        try {
            recordReview(report(0L, 0, 0, 0, 0, 0))
            fail("La excepción de la bitácora debió propagarse")
        } catch (expected: StorageException) {
            assertEquals(StorageError.InsufficientSpace, expected.error)
        }
        assertTrue(auditTrail.events.isEmpty())
    }

    private fun report(
        latestSeq: Long,
        matchedCount: Int,
        ambiguousCount: Int,
        remoteOnlyCount: Int,
        balanceDifferenceCount: Int,
        unlinkedCount: Int,
        ambiguousProductCount: Int = 0,
    ): ReconciliationReport {
        var seq = 0L
        return ReconciliationReport(
            businessId = BUSINESS_ID,
            latestSeq = latestSeq,
            matched = List(matchedCount) { MatchedRemotePurchase(change(++seq), LOCAL_PURCHASE_ID) },
            ambiguous = List(ambiguousCount) {
                AmbiguousRemotePurchase(
                    remote = change(++seq),
                    localPurchaseIds = listOf(LOCAL_PURCHASE_ID, OTHER_LOCAL_PURCHASE_ID),
                )
            },
            remoteOnly = List(remoteOnlyCount) { change(++seq) },
            balanceDifferences = List(balanceDifferenceCount) { index ->
                BalanceDifference(
                    productId = productId(index + 1),
                    productName = "PRODUCTO ${index + 1}",
                    localOnHand = BigDecimal("1"),
                    remoteNet = BigDecimal("2"),
                )
            },
            unlinkedRemoteProducts = List(unlinkedCount) { index ->
                UnlinkedRemoteProduct("rem-x-${index + 1}", "SIN ENLACE ${index + 1}", BigDecimal("3"))
            },
            comparedProductCount = balanceDifferenceCount,
            generatedAt = now,
            ambiguousRemoteProducts = List(ambiguousProductCount) { index ->
                AmbiguousRemoteProduct(
                    remoteProductIds = listOf("rem-ambiguous-${index + 1}"),
                    productName = "HOMÓNIMO ${index + 1}",
                    remoteNet = BigDecimal("3"),
                    localProductIds = listOf(productId(index + 10), productId(index + 20)),
                )
            },
        )
    }

    private fun change(seq: Long): RemotePurchaseChange = RemotePurchaseChange(
        seq = seq,
        purchaseId = "remote-%06d".format(seq),
        status = PurchaseStatus.POSTED,
        documentType = "INVOICE",
        documentSeries = "F001",
        documentNumber = "%08d".format(seq),
        issueDate = "2026-08-15",
        currency = "PEN",
        supplierRuc = "20123456789",
        supplierLegalName = "PROVEEDOR DEMO SAC",
        totalMinorUnits = 1000,
        movementSummary = emptyList(),
        receiptId = "rcpt-$seq",
        syncedAtMillis = now.toEpochMilli(),
        syncedBy = "uid-remoto",
    )

    private companion object {
        val AUDIT_UUID: UUID = UUID.fromString("99999999-9999-4999-8999-999999999999")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val LOCAL_PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("10000000-0000-0000-0000-0000000000c9"),
        )
        val OTHER_LOCAL_PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("10000000-0000-0000-0000-0000000000ca"),
        )

        fun productId(index: Int): ProductId =
            ProductId.from(UUID.fromString("33333333-3333-4333-8333-${"%012d".format(index)}"))
    }
}
