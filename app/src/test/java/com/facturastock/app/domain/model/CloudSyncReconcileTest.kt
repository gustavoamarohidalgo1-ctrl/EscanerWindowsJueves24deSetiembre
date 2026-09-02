package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncReconcileTest {
    private val businessId = BusinessId.parse("11111111-1111-4111-8111-111111111111")!!
    private val localPurchaseId = PurchaseId.parse("22222222-2222-4222-8222-222222222222")!!
    private val overridePurchaseId = PurchaseId.parse("22222222-2222-4222-8222-222222222223")!!
    private val productArroz = ProductId.parse("33333333-3333-4333-8333-333333333331")!!
    private val productAzucar = ProductId.parse("33333333-3333-4333-8333-333333333332")!!
    private val now: Instant = Instant.parse("2026-08-16T12:00:00Z")

    private fun change(
        seq: Long,
        purchaseId: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        status: PurchaseStatus = PurchaseStatus.POSTED,
        ruc: String? = "20123456789",
        series: String = "F001",
        number: String = "000123",
        movements: List<RemoteMovementSummary> = emptyList(),
    ) = RemotePurchaseChange(
        seq = seq,
        purchaseId = purchaseId,
        status = status,
        documentType = "INVOICE",
        documentSeries = series,
        documentNumber = number,
        issueDate = "2026-08-15",
        currency = "PEN",
        supplierRuc = ruc,
        supplierLegalName = "PROVEEDOR DEMO SAC",
        totalMinorUnits = 2360,
        movementSummary = movements,
        receiptId = "rcpt_test",
        syncedAtMillis = now.toEpochMilli(),
        syncedBy = "uid-remoto",
    )

    private fun movement(productId: String, name: String?, delta: String) = RemoteMovementSummary(
        productId = productId,
        productName = name,
        type = StockMovementType.PURCHASE,
        quantityDelta = BigDecimal(delta),
    )

    private fun snapshot(
        documents: Map<PurchaseDocumentIdentity, List<PurchaseId>> = emptyMap(),
        balances: List<LocalProductBalance> = emptyList(),
        products: List<ProductIdentity> = emptyList(),
    ) = LocalLedgerSnapshot(documents, balances, products)

    @Test
    fun `la identidad documental estructurada normaliza formato y conserva ceros`() {
        val identity = PurchaseDocumentIdentity.normalized(
            " 20123456789 ",
            "invoice",
            " f-001 ",
            "000123",
        )
        assertEquals(
            PurchaseDocumentIdentity.normalized("20123456789", "INVOICE", "F001", "000123"),
            identity,
        )
        assertEquals("000123", requireNotNull(identity).documentNumber)
        assertTrue(
            identity != PurchaseDocumentIdentity.normalized(
                "20123456789",
                "INVOICE",
                "F001",
                "123",
            ),
        )
        assertNull(PurchaseDocumentIdentity.normalized(null, "INVOICE", "B002", "55"))
    }

    @Test
    fun `empareja por identidad y deja como solo-en-nube lo que no existe localmente`() {
        val localIdentity = requireNotNull(
            PurchaseDocumentIdentity.normalized("20123456789", "INVOICE", "F001", "000123"),
        )
        val matched = change(seq = 1)
        val onlyRemote = change(seq = 2, purchaseId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", number = "000999")

        val report = reconcileRemoteLedger(
            businessId,
            listOf(matched, onlyRemote),
            snapshot(documents = mapOf(localIdentity to listOf(localPurchaseId))),
            now,
        )

        assertEquals(1, report.matched.size)
        assertEquals(localPurchaseId, report.matched.single().localPurchaseId)
        assertTrue(report.ambiguous.isEmpty())
        assertEquals(listOf(onlyRemote), report.remoteOnly)
    }

    @Test
    fun `prefiere purchaseId remoto exacto cuando principal y excepcion comparten identidad`() {
        val identity = requireNotNull(
            PurchaseDocumentIdentity.normalized("20123456789", "INVOICE", "F001", "000123"),
        )
        val remote = change(seq = 1, purchaseId = overridePurchaseId.value)

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(documents = mapOf(identity to listOf(localPurchaseId, overridePurchaseId))),
            now,
        )

        assertEquals(overridePurchaseId, report.matched.single().localPurchaseId)
        assertTrue(report.ambiguous.isEmpty())
        assertTrue(report.remoteOnly.isEmpty())
    }

    @Test
    fun `varios candidatos sin purchaseId exacto se reportan ambiguos y no como solo nube`() {
        val identity = requireNotNull(
            PurchaseDocumentIdentity.normalized("20123456789", "INVOICE", "F001", "000123"),
        )
        val remote = change(
            seq = 1,
            purchaseId = "44444444-4444-4444-8444-444444444444",
        )

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(documents = mapOf(identity to listOf(overridePurchaseId, localPurchaseId))),
            now,
        )

        assertTrue(report.matched.isEmpty())
        assertTrue(report.remoteOnly.isEmpty())
        val ambiguity = report.ambiguous.single()
        assertEquals(remote, ambiguity.remote)
        assertEquals(
            listOf(localPurchaseId, overridePurchaseId),
            ambiguity.localPurchaseIds,
        )
    }

    @Test
    fun `correlativos que solo difieren por ceros no son coincidencia exacta`() {
        val paddedIdentity = requireNotNull(
            PurchaseDocumentIdentity.normalized("20123456789", "INVOICE", "F001", "000123"),
        )
        val remoteWithoutPadding = change(seq = 1, number = "123")

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remoteWithoutPadding),
            snapshot(documents = mapOf(paddedIdentity to listOf(localPurchaseId))),
            now,
        )

        assertTrue(report.matched.isEmpty())
        assertTrue(report.ambiguous.isEmpty())
        assertEquals(listOf(remoteWithoutPadding), report.remoteOnly)
    }

    @Test
    fun `el estado remoto manda el mayor seq y VOIDED tiene efecto neto cero`() {
        val posted = change(seq = 1, movements = listOf(movement("rem-1", "ARROZ EXTRA", "5")))
        val voided = change(seq = 2, status = PurchaseStatus.VOIDED, movements = emptyList())

        val report = reconcileRemoteLedger(
            businessId,
            listOf(posted, voided),
            snapshot(products = listOf(ProductIdentity(productArroz, "ARROZ EXTRA"))),
            now,
        )

        assertTrue(report.balanceDifferences.isEmpty())
        assertTrue(report.unlinkedRemoteProducts.isEmpty())
        assertEquals(1, report.comparedProductCount)
    }

    @Test
    fun `saldo solo local se compara contra cero aunque el libro remoto este vacio`() {
        val report = reconcileRemoteLedger(
            businessId,
            remoteChanges = emptyList(),
            local = snapshot(
                balances = listOf(LocalProductBalance(productArroz, BigDecimal("4.5"))),
                products = listOf(ProductIdentity(productArroz, "ARROZ EXTRA")),
            ),
            generatedAt = now,
        )

        val difference = report.balanceDifferences.single()
        assertEquals(productArroz, difference.productId)
        assertEquals(BigDecimal("4.5"), difference.localOnHand)
        assertEquals(BigDecimal.ZERO, difference.remoteNet)
        assertEquals(BigDecimal("-4.5"), difference.difference)
        assertEquals(1, report.comparedProductCount)
        assertTrue(report.unlinkedRemoteProducts.isEmpty())
        assertTrue(report.ambiguousRemoteProducts.isEmpty())
    }

    @Test
    fun `anulacion remota conserva identidad y revela saldo local contra neto cero`() {
        val posted = change(
            seq = 1,
            movements = listOf(movement("rem-voided", "ARROZ EXTRA", "5")),
        )
        val voided = change(seq = 2, status = PurchaseStatus.VOIDED)

        val report = reconcileRemoteLedger(
            businessId,
            listOf(posted, voided),
            snapshot(
                balances = listOf(LocalProductBalance(productArroz, BigDecimal("2"))),
                products = listOf(ProductIdentity(productArroz, "ARROZ EXTRA")),
            ),
            now,
        )

        val difference = report.balanceDifferences.single()
        assertEquals(BigDecimal("2"), difference.localOnHand)
        assertEquals(BigDecimal.ZERO, difference.remoteNet)
        assertEquals(BigDecimal("-2"), difference.difference)
        assertEquals(1, report.comparedProductCount)
    }

    @Test
    fun `producto solo remoto permanece sin enlace incluso cuando su neto termina en cero`() {
        val posted = change(
            seq = 1,
            movements = listOf(movement("rem-only", "PRODUCTO REMOTO", "3")),
        )
        val voided = change(seq = 2, status = PurchaseStatus.VOIDED)

        val report = reconcileRemoteLedger(
            businessId,
            listOf(posted, voided),
            snapshot(),
            now,
        )

        val unlinked = report.unlinkedRemoteProducts.single()
        assertEquals("rem-only", unlinked.remoteProductId)
        assertEquals(BigDecimal.ZERO, unlinked.remoteNet)
        assertTrue(report.balanceDifferences.isEmpty())
        assertEquals(0, report.comparedProductCount)
    }

    @Test
    fun `homonimos locales son ambiguos y nunca dependen del orden de la lista`() {
        val remote = change(
            seq = 1,
            movements = listOf(movement("rem-shared", "ARROZ EXTRA", "5")),
        )

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(
                balances = listOf(
                    LocalProductBalance(productArroz, BigDecimal("2")),
                    LocalProductBalance(productAzucar, BigDecimal("3")),
                ),
                products = listOf(
                    ProductIdentity(productAzucar, "ARROZ EXTRA"),
                    ProductIdentity(productArroz, "ARROZ EXTRA"),
                ),
            ),
            now,
        )

        val ambiguity = report.ambiguousRemoteProducts.single()
        assertEquals(listOf("rem-shared"), ambiguity.remoteProductIds)
        assertEquals(BigDecimal("5"), ambiguity.remoteNet)
        assertEquals(listOf(productArroz, productAzucar), ambiguity.localProductIds)
        assertTrue(report.balanceDifferences.isEmpty())
        assertTrue(report.unlinkedRemoteProducts.isEmpty())
        assertEquals(0, report.comparedProductCount)
    }

    @Test
    fun `varios productos remotos homonimos no se suman contra un unico producto local`() {
        val remote = change(
            seq = 1,
            movements = listOf(
                movement("rem-shared-a", "ARROZ EXTRA", "2"),
                movement("rem-shared-b", "ARROZ EXTRA", "3"),
            ),
        )

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(
                balances = listOf(LocalProductBalance(productArroz, BigDecimal("5"))),
                products = listOf(ProductIdentity(productArroz, "ARROZ EXTRA")),
            ),
            now,
        )

        val ambiguity = report.ambiguousRemoteProducts.single()
        assertEquals(listOf("rem-shared-a", "rem-shared-b"), ambiguity.remoteProductIds)
        assertEquals(BigDecimal("5"), ambiguity.remoteNet)
        assertEquals(listOf(productArroz), ambiguity.localProductIds)
        assertTrue(report.balanceDifferences.isEmpty())
        assertTrue(report.unlinkedRemoteProducts.isEmpty())
        assertEquals(0, report.comparedProductCount)
    }

    @Test
    fun `diferencia positiva, la nube conoce stock que este teléfono no tiene`() {
        val remote = change(seq = 1, movements = listOf(movement("rem-1", "ARROZ EXTRA", "5")))

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(
                balances = listOf(LocalProductBalance(productArroz, BigDecimal("2"))),
                products = listOf(ProductIdentity(productArroz, "ARROZ EXTRA")),
            ),
            now,
        )

        val diff = report.balanceDifferences.single()
        assertEquals(productArroz, diff.productId)
        assertEquals(BigDecimal("2"), diff.localOnHand)
        assertEquals(BigDecimal("5"), diff.remoteNet)
        assertEquals(BigDecimal("3"), diff.difference)
        assertEquals(1, report.comparedProductCount)
    }

    @Test
    fun `producto enlazado sin fila de saldo local se compara contra cero`() {
        val remote = change(
            seq = 1,
            movements = listOf(movement("rem-zero-local", "ARROZ EXTRA", "5")),
        )

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(products = listOf(ProductIdentity(productArroz, "ARROZ EXTRA"))),
            now,
        )

        val difference = report.balanceDifferences.single()
        assertEquals(BigDecimal.ZERO, difference.localOnHand)
        assertEquals(BigDecimal("5"), difference.remoteNet)
        assertEquals(BigDecimal("5"), difference.difference)
        assertEquals(1, report.comparedProductCount)
    }

    @Test
    fun `diferencia negativa, este teléfono tiene stock que la nube no conoce`() {
        val remote = change(seq = 1, movements = listOf(movement("rem-1", "AZUCAR RUBIA", "1")))

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(
                balances = listOf(LocalProductBalance(productAzucar, BigDecimal("4"))),
                products = listOf(ProductIdentity(productAzucar, "AZUCAR RUBIA")),
            ),
            now,
        )

        assertEquals(BigDecimal("-3"), report.balanceDifferences.single().difference)
    }

    @Test
    fun `producto remoto sin nombre exacto local va a sin-enlace y nunca se compara`() {
        val remote = change(seq = 1, movements = listOf(movement("rem-9", "ARROZ  EXTRA", "7")))

        val report = reconcileRemoteLedger(
            businessId,
            listOf(remote),
            snapshot(products = listOf(ProductIdentity(productArroz, "ARROZ EXTRA"))),
            now,
        )

        // "ARROZ  EXTRA" normaliza igual que "ARROZ EXTRA": sí enlaza por nombre normalizado.
        assertEquals(1, report.comparedProductCount)
        assertTrue(report.unlinkedRemoteProducts.isEmpty())

        val sinNombre = change(
            seq = 2,
            purchaseId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            movements = listOf(movement("rem-10", null, "3")),
        )
        val report2 = reconcileRemoteLedger(businessId, listOf(sinNombre), snapshot(), now)
        assertEquals(1, report2.unlinkedRemoteProducts.size)
        assertEquals(BigDecimal("3"), report2.unlinkedRemoteProducts.single().remoteNet)
        assertEquals(0, report2.comparedProductCount)
    }
}
