package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseVoidImpact
import com.facturastock.app.domain.model.PurchaseVoidPreview
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseVoidCommand
import com.facturastock.app.domain.repository.PurchaseVoidRepository
import com.facturastock.app.domain.repository.PurchaseVoidRequest
import com.facturastock.app.domain.repository.PurchaseVoidResult
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakePurchaseOverrideAuthorizationRepository
import com.facturastock.app.testing.RecordingProductionObservability
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VoidPurchaseUseCaseTest {
    @Test
    fun `preview resuelve negocio y actor autorizado fuera de la UI`() = runTest {
        val fixture = fixture(activeBusiness = true)

        val result = fixture.preview(PURCHASE_ID)

        assertEquals(PreviewPurchaseVoidResult.Ready(fixture.previewValue), result)
        assertEquals(BUSINESS_ID, fixture.repository.previewBusinessId)
        assertEquals(OWNER, fixture.repository.previewActor)
    }

    @Test
    fun `operador no puede previsualizar ni ejecutar una anulacion`() = runTest {
        val fixture = fixture(activeBusiness = true, actor = OPERATOR)

        assertSame(PreviewPurchaseVoidResult.Unauthorized, fixture.preview(PURCHASE_ID))
        assertSame(
            PurchaseVoidResult.Unauthorized,
            fixture.void(validRequest()),
        )
        assertNull(fixture.repository.previewBusinessId)
        assertNull(fixture.repository.command)
    }

    @Test
    fun `motivo y confirmacion se validan antes de tocar el repositorio`() = runTest {
        val fixture = fixture(activeBusiness = true)

        assertSame(
            PurchaseVoidResult.InvalidReason,
            fixture.void(validRequest().copy(reason = "corto")),
        )
        assertSame(
            PurchaseVoidResult.ConfirmationRequired,
            fixture.void(validRequest().copy(confirmed = false)),
        )
        assertNull(fixture.repository.command)
    }

    @Test
    fun `hash malformado no puede autorizar el commit`() = runTest {
        val fixture = fixture(activeBusiness = true)

        assertSame(
            PurchaseVoidResult.RetryableConflict,
            fixture.void(validRequest().copy(expectedImpactHash = "not-a-hash")),
        )
        assertNull(fixture.repository.command)
    }

    @Test
    fun `orden valida recorta motivo y conserva el hash revisado`() = runTest {
        val fixture = fixture(activeBusiness = true)
        fixture.repository.voidResult = PurchaseVoidResult.Voided(PURCHASE_ID, emptyList())

        val result = fixture.void(
            validRequest().copy(reason = "  Documento emitido por error material  "),
        )

        assertEquals(PurchaseVoidResult.Voided(PURCHASE_ID, emptyList()), result)
        val command = requireNotNull(fixture.repository.command)
        assertEquals(BUSINESS_ID, command.businessId)
        assertEquals(PURCHASE_ID, command.purchaseId)
        assertEquals("Documento emitido por error material", command.reason)
        assertEquals(OWNER, command.actor)
        assertEquals(IMPACT_HASH, command.expectedImpactHash)
        val audit = fixture.observability.records.single().event
        assertEquals(OperationalAction.PURCHASE_VOID, audit.action)
        assertEquals(OperationalOutcome.SUCCEEDED, audit.outcome)
        assertEquals(PURCHASE_ID, audit.identifiers.purchaseId)
        assertEquals(false, audit.toString().contains(command.reason))
    }

    @Test
    fun `fallo de WorkManager tras commit conserva anulacion exitosa`() = runTest {
        val scheduler = FakePurchaseBackupScheduler().apply {
            enqueueFailure = IllegalStateException("workmanager database full")
        }
        val fixture = fixture(activeBusiness = true, scheduler = scheduler)
        fixture.repository.voidResult = PurchaseVoidResult.Voided(PURCHASE_ID, emptyList())

        val result = fixture.void(validRequest())

        assertEquals(PurchaseVoidResult.Voided(PURCHASE_ID, emptyList()), result)
        assertEquals(PURCHASE_ID, fixture.repository.command?.purchaseId)
    }

    @Test
    fun `sin negocio activo no consulta autorizacion ni Room`() = runTest {
        val fixture = fixture(activeBusiness = false)

        assertSame(PreviewPurchaseVoidResult.NoActiveBusiness, fixture.preview(PURCHASE_ID))
        assertSame(PurchaseVoidResult.NoActiveBusiness, fixture.void(validRequest()))
        assertNull(fixture.repository.previewBusinessId)
        assertNull(fixture.repository.command)
    }

    private suspend fun fixture(
        activeBusiness: Boolean,
        actor: PurchaseOverrideActor? = OWNER,
        scheduler: FakePurchaseBackupScheduler = FakePurchaseBackupScheduler(),
    ): Fixture {
        val configuration = FakeAppConfigurationRepository()
        if (activeBusiness) {
            configuration.completeOnboarding(
                BUSINESS_ID,
                AppConfiguration.DEFAULT_TAX_RATE,
                AppConfiguration.DEFAULT_COST_POLICY,
            )
        }
        val authorization = FakePurchaseOverrideAuthorizationRepository(actor)
        val repository = RecordingPurchaseVoidRepository(preview())
        val observability = RecordingProductionObservability()
        return Fixture(
            preview = PreviewPurchaseVoidUseCase(configuration, authorization, repository),
            void = VoidPurchaseUseCase(
                configuration,
                authorization,
                repository,
                scheduler,
                observability,
            ),
            repository = repository,
            previewValue = repository.previewValue,
            observability = observability,
        )
    }

    private fun validRequest(): PurchaseVoidRequest = PurchaseVoidRequest(
        purchaseId = PURCHASE_ID,
        reason = "Documento emitido por error material",
        expectedImpactHash = IMPACT_HASH,
        confirmed = true,
    )

    private fun preview(): PurchaseVoidPreview = PurchaseVoidPreview(
        purchaseId = PURCHASE_ID,
        documentNumber = "F001-42",
        actor = OWNER,
        impacts = listOf(
            PurchaseVoidImpact(
                productId = ProductId.from(uuid(3)),
                productName = "Café",
                locationId = LocationId.from(uuid(4)),
                locationName = "Principal",
                unitCode = "NIU",
                currentQuantity = BigDecimal("8"),
                reversalQuantity = BigDecimal("-10"),
                resultingQuantity = BigDecimal("-2"),
                currency = CurrencyCode.of("PEN"),
            ),
        ),
        expectedImpactHash = IMPACT_HASH,
    )

    private data class Fixture(
        val preview: PreviewPurchaseVoidUseCase,
        val void: VoidPurchaseUseCase,
        val repository: RecordingPurchaseVoidRepository,
        val previewValue: PurchaseVoidPreview,
        val observability: RecordingProductionObservability,
    )

    private class RecordingPurchaseVoidRepository(
        val previewValue: PurchaseVoidPreview,
    ) : PurchaseVoidRepository {
        var previewBusinessId: BusinessId? = null
        var previewActor: PurchaseOverrideActor? = null
        var command: PurchaseVoidCommand? = null
        var voidResult: PurchaseVoidResult = PurchaseVoidResult.RetryableConflict

        override suspend fun preview(
            businessId: BusinessId,
            purchaseId: PurchaseId,
            actor: PurchaseOverrideActor,
        ): PreviewPurchaseVoidResult {
            previewBusinessId = businessId
            previewActor = actor
            return PreviewPurchaseVoidResult.Ready(previewValue)
        }

        override suspend fun void(command: PurchaseVoidCommand): PurchaseVoidResult {
            this.command = command
            return voidResult
        }
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(2))
        const val IMPACT_HASH: String =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val OWNER = PurchaseOverrideActor("owner-test", PurchaseOverrideRole.OWNER)
        val OPERATOR = PurchaseOverrideActor("operator-test", PurchaseOverrideRole.OPERATOR)

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
