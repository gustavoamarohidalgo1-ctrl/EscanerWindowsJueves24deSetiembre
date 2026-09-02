package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class CreateLinkedProductUseCaseTest {
    private val clock = AppClock { Instant.parse("2026-08-08T12:00:00Z") }
    private val businessId = BusinessId.from(uuid(1))
    private val unitId = UnitId.from(uuid(2))
    private val purchaseUnitId = UnitId.from(uuid(3))

    private var uuidCounter = 100
    private val uuidGenerator = UuidGenerator { uuid(++uuidCounter) }

    private lateinit var products: FakeProductRepository
    private lateinit var configuration: FakeAppConfigurationRepository
    private lateinit var useCase: CreateLinkedProductUseCase

    @Before
    fun setUp() {
        products = FakeProductRepository(clock)
        configuration = FakeAppConfigurationRepository()
        runBlocking {
            configuration.completeOnboarding(
                businessId,
                TaxRate(BigDecimal("18")),
                CostPolicy.NET,
            )
        }
        useCase = CreateLinkedProductUseCase(
            appConfigurationRepository = configuration,
            productRepository = products,
            uuidGenerator = uuidGenerator,
        )
    }

    @Test
    fun `staging conserva unidad de compra y factor sin insertar catalogo`() = runTest {
        val result = useCase(
            NewLinkedProduct(
                businessId = businessId,
                name = "Leche Gloria (caja)",
                unitId = unitId,
                purchaseUnitId = purchaseUnitId,
                purchaseFactor = BigDecimal("12"),
                salePrice = Money.ofMinor(650L, CurrencyCode.of("PEN")),
            ),
        )

        val staged = result as CreateLinkedProductResult.Staged
        assertEquals(purchaseUnitId, staged.product.purchaseUnitId)
        assertEquals(BigDecimal("12"), staged.product.purchaseFactor)
        assertEquals(Money.ofMinor(650L, CurrencyCode.of("PEN")), staged.product.salePrice)
        assertEquals(0, products.observeForBusiness(businessId).first().size)
    }

    @Test
    fun `sin unidad de compra conserva la unidad de inventario`() = runTest {
        val result = useCase(
            NewLinkedProduct(
                businessId = businessId,
                name = "Azucar 1kg",
                unitId = unitId,
                salePrice = Money.ofMinor(400L, CurrencyCode.of("PEN")),
            ),
        )

        val staged = result as CreateLinkedProductResult.Staged
        assertEquals(unitId, staged.product.unitId)
        assertEquals(null, staged.product.purchaseUnitId)
        assertEquals(0, products.observeForBusiness(businessId).first().size)
    }

    @Test
    fun `unidad de compra sin factor se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            NewLinkedProduct(
                businessId = businessId,
                name = "Atun",
                unitId = unitId,
                purchaseUnitId = purchaseUnitId,
            )
        }
    }

    @Test
    fun `detecta duplicado por barcode y devuelve el existente sin insertar`() = runTest {
        val existente = seedProduct(10, name = "Atun Florida", barcode = "7750001")

        val result = useCase(
            NewLinkedProduct(
                businessId = businessId,
                name = "Atun en lata",
                unitId = unitId,
                barcode = "7750001",
            ),
        )

        val duplicate = result as CreateLinkedProductResult.Duplicate
        assertEquals(LinkedProductDuplicateField.BARCODE, duplicate.field)
        assertEquals(existente.productId, duplicate.existing.productId)
        assertEquals(1, products.observeForBusiness(businessId).first().size)
    }

    @Test
    fun `barcode staged se canonicaliza y una entrada insegura se rechaza`() = runTest {
        val staged = useCase(
            NewLinkedProduct(
                businessId = businessId,
                name = "Producto interno",
                unitId = unitId,
                barcode = "  0012aB-Z  ",
                salePrice = Money.ofMinor(500L, CurrencyCode.of("PEN")),
            ),
        ) as CreateLinkedProductResult.Staged
        assertEquals("0012aB-Z", staged.product.barcode)

        val exception = assertThrows(DomainRuleViolation::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(
                    NewLinkedProduct(
                        businessId = businessId,
                        name = "Producto inseguro",
                        unitId = unitId,
                        barcode = "ABC\n123",
                    ),
                )
            }
        }
        assertEquals(ValidationError.InvalidBarcode, exception.error)
    }

    @Test
    fun `detecta duplicado por SKU`() = runTest {
        val existente = seedProduct(10, name = "Cloro Clorox 1L", sku = "CLX-1L")

        val result = useCase(
            NewLinkedProduct(businessId = businessId, name = "Cloro 1L", unitId = unitId, sku = "CLX-1L"),
        )

        val duplicate = result as CreateLinkedProductResult.Duplicate
        assertEquals(LinkedProductDuplicateField.SKU, duplicate.field)
        assertEquals(existente.productId, duplicate.existing.productId)
    }

    @Test
    fun `detecta duplicado por nombre normalizado sin importar mayusculas`() = runTest {
        seedProduct(10, name = "Arroz Costeno 5kg")

        val result = useCase(
            NewLinkedProduct(businessId = businessId, name = "  ARROZ COSTENO 5KG ", unitId = unitId),
        )

        val duplicate = result as CreateLinkedProductResult.Duplicate
        assertEquals(LinkedProductDuplicateField.NAME, duplicate.field)
    }

    @Test
    fun `ID staged es estable en el resultado y no reserva catalogo ni alias`() = runTest {
        val result = useCase(
            NewLinkedProduct(
                businessId = businessId,
                name = "Vinagre 500ml",
                unitId = unitId,
                salePrice = Money.ofMinor(500L, CurrencyCode.of("PEN")),
            ),
        ) as CreateLinkedProductResult.Staged

        assertEquals(ProductId.from(uuid(101)), result.product.productId)
        assertEquals("Vinagre 500ml", result.product.name)
        assertEquals(0, products.observeForBusiness(businessId).first().size)
    }

    @Test
    fun `staging exige negocio activo precio y moneda configurada sin consultar otro tenant`() = runTest {
        val noConfiguration = FakeAppConfigurationRepository()
        val guarded = CreateLinkedProductUseCase(noConfiguration, products, uuidGenerator)
        val base = NewLinkedProduct(
            businessId = businessId,
            name = "Producto protegido",
            unitId = unitId,
            salePrice = Money.ofMinor(500L, CurrencyCode.of("PEN")),
        )

        assertEquals(CreateLinkedProductResult.NoActiveBusiness, guarded(base))
        assertEquals(
            CreateLinkedProductResult.SalePriceRequired,
            useCase(base.copy(salePrice = null)),
        )
        assertEquals(
            CreateLinkedProductResult.CurrencyMismatch(
                expected = CurrencyCode.of("PEN"),
                actual = CurrencyCode.of("USD"),
            ),
            useCase(base.copy(salePrice = Money.ofMinor(500L, CurrencyCode.of("USD")))),
        )
        val foreignBusiness = BusinessId.from(uuid(99))
        assertEquals(
            CreateLinkedProductResult.BusinessMismatch(
                active = businessId,
                requested = foreignBusiness,
            ),
            useCase(base.copy(businessId = foreignBusiness)),
        )
        assertEquals(0, products.observeForBusiness(businessId).first().size)
        assertEquals(0, products.observeForBusiness(foreignBusiness).first().size)
    }

    private suspend fun seedProduct(
        seed: Int,
        name: String,
        sku: String? = null,
        barcode: String? = null,
    ): Product = products.create(
        Product(
            productId = ProductId.from(uuid(seed)),
            businessId = businessId,
            unitId = unitId,
            name = name,
            sku = sku,
            barcode = barcode,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        ),
    )

    private companion object {
        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
