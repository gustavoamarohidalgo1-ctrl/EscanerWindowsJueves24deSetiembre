package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.SaleVoidLine
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidRepository
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.testing.FakeAppConfigurationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class VoidSaleUseCaseTest {
    @Test
    fun `preview y confirm conservan el impacto revisado y los resultados del repositorio`() =
        runTest {
            val fixture = Fixture()
            assertEquals(SaleVoidPreviewResult.Ready(PREVIEW), fixture.useCase.preview(BUSINESS, SALE))
            assertEquals(BUSINESS to SALE, fixture.repository.previewRequest)
            assertSame(SaleVoidResult.Voided, fixture.useCase.confirm(PREVIEW))
            assertSame(PREVIEW, fixture.repository.confirmed)
            fixture.repository.confirmResult = SaleVoidResult.AlreadyVoided
            assertSame(SaleVoidResult.AlreadyVoided, fixture.useCase.confirm(PREVIEW))
        }

    @Test
    fun `sin negocio o con otro activo no consulta ni confirma ventas`() =
        runTest {
            val fixture = Fixture()
            for (business in listOf(null, OTHER)) {
                fixture.current = AppConfiguration.defaults().copy(businessId = business)
                assertSame(SaleVoidPreviewResult.NoActiveBusiness, fixture.useCase.preview(BUSINESS, SALE))
                assertSame(SaleVoidResult.NoActiveBusiness, fixture.useCase.confirm(PREVIEW))
            }
            assertNull(fixture.repository.previewRequest)
            assertNull(fixture.repository.confirmed)
        }

    @Test
    fun `cambiar a demo entre preview y confirm no anula el negocio anterior`() =
        runTest {
            val fixture = Fixture()
            assertTrue(fixture.useCase.preview(BUSINESS, SALE) is SaleVoidPreviewResult.Ready)
            fixture.current = fixture.current.copy(demoBusinessId = OTHER)
            assertSame(SaleVoidResult.NoActiveBusiness, fixture.useCase.confirm(PREVIEW))
            assertNull(fixture.repository.confirmed)
        }

    @Test
    fun `preview admite devolucion de stock de venta con descuento completo y cobro cero`() {
        val zero = Money.ofMinor(0L, PEN)
        assertEquals(zero, PREVIEW.copy(total = zero, refundAmount = zero).refundAmount)
    }

    @Test
    fun `cancelacion conserva la semantica de coroutines`() =
        runTest {
            val fixture = Fixture()
            fixture.repository.failure = CancellationException("cancelled")
            val failure = runCatching { fixture.useCase.confirm(PREVIEW) }.exceptionOrNull()
            assertSame(fixture.repository.failure, failure)
        }

    private class Fixture {
        var current = AppConfiguration.defaults().copy(businessId = BUSINESS)
        val repository = FakeVoidRepository()
        val useCase =
            VoidSaleUseCase(
                object : AppConfigurationRepository by FakeAppConfigurationRepository() {
                    override suspend fun current() = this@Fixture.current
                },
                repository,
            )
    }

    private class FakeVoidRepository : SaleVoidRepository {
        var previewRequest: Pair<BusinessId, SaleId>? = null
        var confirmed: SaleVoidPreview? = null
        var confirmResult: SaleVoidResult = SaleVoidResult.Voided
        var failure: Exception? = null

        override suspend fun preview(
            businessId: BusinessId,
            saleId: SaleId,
        ): SaleVoidPreviewResult {
            previewRequest = businessId to saleId
            return SaleVoidPreviewResult.Ready(PREVIEW)
        }

        override suspend fun confirm(preview: SaleVoidPreview): SaleVoidResult {
            failure?.let { throw it }
            confirmed = preview
            return confirmResult
        }
    }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(1L, 1L))
        val OTHER = BusinessId.from(UUID(2L, 2L))
        val SALE = SaleId.from(UUID(3L, 3L))
        val PEN = CurrencyCode.of("PEN")
        val PREVIEW =
            SaleVoidPreview(
                BUSINESS,
                SALE,
                Instant.EPOCH,
                Money.ofMinor(500L, PEN),
                Money.ofMinor(500L, PEN),
                null,
                listOf(SaleVoidLine("Producto", "Principal", "NIU", Quantity.of("2"))),
                "a".repeat(64),
            )
    }
}
