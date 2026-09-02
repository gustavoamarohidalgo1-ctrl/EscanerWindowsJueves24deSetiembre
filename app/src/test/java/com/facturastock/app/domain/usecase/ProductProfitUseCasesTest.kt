package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeProductRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductProfitUseCasesTest {
    private val configuration = FakeAppConfigurationRepository()
    private val products = FakeProductRepository(AppClock { NOW })
    private val useCase = UpdateProductSalePriceUseCase(configuration, products)

    @Test
    fun `rechaza negocio ausente precio invalido y moneda distinta antes de escribir`() = runTest {
        val product = products.create(product())

        assertEquals(
            ProductSalePriceMutationResult.NoActiveBusiness,
            useCase(product.productId, product.version, Money.ofMinor(600, PEN)),
        )

        activateBusiness()
        assertEquals(
            ProductSalePriceMutationResult.InvalidPrice,
            useCase(product.productId, product.version, Money.zero(PEN)),
        )
        assertEquals(
            ProductSalePriceMutationResult.InvalidPrice,
            useCase(
                product.productId,
                product.version,
                Money.ofMinor(ProductSalePricePolicy.MAX_MINOR_UNITS + 1L, PEN),
            ),
        )
        assertEquals(
            ProductSalePriceMutationResult.CurrencyMismatch(PEN, USD),
            useCase(product.productId, product.version, Money.ofMinor(600, USD)),
        )
        assertNull(products.findById(product.productId)?.salePrice)
    }

    @Test
    fun `actualiza por CAS y una lectura obsoleta nunca reemplaza el precio`() = runTest {
        activateBusiness()
        val product = products.create(product())

        val updated = useCase(product.productId, product.version, Money.ofMinor(600, PEN))
        val stale = useCase(product.productId, product.version, Money.ofMinor(700, PEN))
        val unchanged = useCase(product.productId, product.version + 1L, Money.ofMinor(600, PEN))

        assertTrue(updated is ProductSalePriceMutationResult.Updated)
        assertEquals(2L, (updated as ProductSalePriceMutationResult.Updated).product.version)
        assertEquals(ProductSalePriceMutationResult.Stale, stale)
        assertTrue(unchanged is ProductSalePriceMutationResult.Unchanged)
        assertEquals(Money.ofMinor(600, PEN), products.findById(product.productId)?.salePrice)
    }

    private suspend fun activateBusiness() {
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = TaxRate(BigDecimal("18")),
            costPolicy = CostPolicy.NET,
        )
    }

    private fun product() = Product(
        productId = ProductId.from(UUID.fromString(uuid(2))),
        businessId = BUSINESS_ID,
        unitId = UnitId.from(UUID.fromString(uuid(3))),
        name = "Producto",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-24T12:00:00Z")
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val USD: CurrencyCode = CurrencyCode.of("USD")
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID.fromString(uuid(1)))

        fun uuid(seed: Int): String = "00000000-0000-4000-8000-%012d".format(seed)
    }
}
