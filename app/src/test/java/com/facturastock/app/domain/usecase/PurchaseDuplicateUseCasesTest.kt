package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RecordedPurchase
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.PublishPreparedPurchaseResult
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakePreparedPurchaseRepository
import com.facturastock.app.testing.FakePurchaseOverrideAuthorizationRepository
import com.facturastock.app.testing.FakePurchaseRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseDuplicateUseCasesTest {
    @Test
    fun `owner con motivo devuelve excepcion exacta recortada sin auditar`() = runTest {
        val fixture = fixture()
        val authorize = fixture.authorize()

        val result = authorize(
            draftId = DRAFT_ID,
            expectedPreparedLogicalHash = PREPARED_HASH,
            expectedPurchaseId = EXISTING_PURCHASE_ID,
            reason = "  Verificado contra el original físico  ",
        )

        val authorized = result as AuthorizeDuplicateOverrideResult.Authorized
        assertEquals("Verificado contra el original físico", authorized.override.reason)
        assertEquals(DRAFT_ID, authorized.override.draftId)
        assertEquals(BUSINESS_ID, authorized.override.businessId)
        assertEquals(EXISTING_PURCHASE_ID, authorized.override.existingPurchaseId)
        assertEquals(PurchaseOverrideRole.OWNER, authorized.override.actor.role)
        assertEquals("test-owner", authorized.override.actor.actorId)
        assertTrue(authorized.override.reasons.isNotEmpty())
        assertTrue(fixture.purchases.overrides.isEmpty())
    }

    @Test
    fun `operador no autorizado no genera auditoria`() = runTest {
        val fixture = fixture()
        fixture.authorization.actor = PurchaseOverrideActor(
            actorId = "operator-1",
            role = PurchaseOverrideRole.OPERATOR,
        )

        val result = fixture.authorize()(
            DRAFT_ID,
            PREPARED_HASH,
            EXISTING_PURCHASE_ID,
            "Documento autorizado por contabilidad",
        )

        assertEquals(AuthorizeDuplicateOverrideResult.Unauthorized, result)
        assertTrue(fixture.purchases.overrides.isEmpty())
    }

    @Test
    fun `motivo vacio o generico se rechaza antes de auditar`() = runTest {
        val fixture = fixture()

        val result = fixture.authorize()(
            DRAFT_ID,
            PREPARED_HASH,
            EXISTING_PURCHASE_ID,
            "  corto  ",
        )

        assertEquals(AuthorizeDuplicateOverrideResult.InvalidReason, result)
        assertTrue(fixture.purchases.overrides.isEmpty())
    }

    @Test
    fun `hash esperado obsoleto rechaza autorizacion aunque el duplicado siga coincidiendo`() =
        runTest {
            val fixture = fixture()

            val result = fixture.authorize()(
                draftId = DRAFT_ID,
                expectedPreparedLogicalHash = "b".repeat(64),
                expectedPurchaseId = EXISTING_PURCHASE_ID,
                reason = "Documento autorizado por contabilidad",
            )

            assertEquals(AuthorizeDuplicateOverrideResult.StaleMatch, result)
            assertTrue(fixture.purchases.overrides.isEmpty())
        }

    private suspend fun fixture(): Fixture {
        val clock = AppClock { NOW }
        val drafts = FakeInvoiceDraftRepository(clock)
        val created = drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val preparedRepository = FakePreparedPurchaseRepository(drafts, clock)
        val prepared = preparedPurchase()
        assertEquals(
            PublishPreparedPurchaseResult.PREPARED,
            preparedRepository.publish(
                purchase = prepared,
                expectedDraftUpdatedAt = created.updatedAt,
                expectedHeaderRevision = 0,
                expectedLinesRevision = 0,
            ),
        )
        val purchases = FakePurchaseRepository(listOf(existingPurchase()))
        val check = CheckPurchaseDuplicateUseCase(preparedRepository, drafts, purchases)
        return Fixture(
            purchases = purchases,
            check = check,
            authorization = FakePurchaseOverrideAuthorizationRepository(),
        )
    }

    private data class Fixture(
        val purchases: FakePurchaseRepository,
        val check: CheckPurchaseDuplicateUseCase,
        val authorization: FakePurchaseOverrideAuthorizationRepository,
    ) {
        fun authorize() = AuthorizePurchaseDuplicateOverrideUseCase(
            checkDuplicate = check,
            authorizationRepository = authorization,
        )
    }

    private fun preparedPurchase(): PreparedPurchase = PreparedPurchase(
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        supplierId = SUPPLIER_ID,
        supplierRuc = "20123456789",
        supplierLegalName = "Proveedor SAC",
        documentType = PurchaseDocumentType.INVOICE,
        documentNumber = "F001-000123",
        issueDate = DATE,
        currency = PEN,
        lines = listOf(
            PreparedPurchaseLine(
                lineId = LineId.from(uuid(8)),
                position = 0,
                productId = ProductId.from(uuid(9)),
                unitId = UnitId.from(uuid(10)),
                description = "Producto",
                quantity = Quantity.of("1"),
                lineTotal = Money.ofMinor(1_000, PEN),
            ),
        ),
        subtotal = Money.ofMinor(1_000, PEN),
        tax = Money.zero(PEN),
        otherCharges = null,
        total = Money.ofMinor(1_000, PEN),
        acceptedWarnings = emptyList(),
        logicalHash = PREPARED_HASH,
        preparedAt = NOW,
    )

    private fun existingPurchase(): RecordedPurchase = RecordedPurchase(
        purchaseId = EXISTING_PURCHASE_ID,
        businessId = BUSINESS_ID,
        sourceDraftId = OLD_DRAFT_ID,
        supplierId = SUPPLIER_ID,
        supplierRuc = "20123456789",
        supplierLegalName = "Proveedor SAC",
        documentType = PurchaseDocumentType.INVOICE,
        documentSeries = "F001",
        documentNumber = "000123",
        issueDate = DATE,
        currency = PEN,
        total = Money.ofMinor(1_000, PEN),
        status = PurchaseStatus.POSTED,
    )

    private companion object {
        const val PREPARED_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val NOW: Instant = Instant.parse("2026-08-13T12:00:00Z")
        val DATE: LocalDate = LocalDate.of(2026, 8, 12)
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DRAFT_ID: DraftId = DraftId.from(uuid(2))
        val OLD_DRAFT_ID: DraftId = DraftId.from(uuid(3))
        val SUPPLIER_ID: SupplierId = SupplierId.from(uuid(4))
        val EXISTING_PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(5))

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
