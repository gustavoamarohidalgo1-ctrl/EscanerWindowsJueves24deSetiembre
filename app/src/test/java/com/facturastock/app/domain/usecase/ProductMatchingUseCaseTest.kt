package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import java.time.Instant
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductMatchingUseCaseTest {
    private val clock = AppClock { Instant.parse("2026-08-08T12:00:00Z") }
    private val businessId = BusinessId.from(uuid(1))
    private val unitId = UnitId.from(uuid(2))

    private lateinit var products: FakeProductRepository
    private lateinit var aliases: FakeSupplierProductAliasRepository
    private lateinit var useCase: ProductMatchingUseCase

    @Before
    fun setUp() {
        products = FakeProductRepository(clock)
        aliases = FakeSupplierProductAliasRepository(clock)
        useCase = ProductMatchingUseCase(
            productRepository = products,
            supplierProductAliasRepository = aliases,
        )
    }

    @Test
    fun `barcode exacto vincula en primer lugar con confianza maxima`() = runTest {
        val porBarcode = seedProduct(10, name = "Atun en lata", barcode = "7750001")
        seedProduct(11, name = "Atun en lata")

        val outcome = useCase(query(barcode = "7750001", description = "Atun en lata"))

        val auto = outcome as ProductMatchOutcome.AutoLinked
        assertEquals(porBarcode.productId, auto.candidate.product.productId)
        assertEquals(ProductMatchReason.BARCODE, auto.candidate.reason)
        assertEquals(1_000, auto.candidate.confidencePermille)
    }

    @Test
    fun `barcode con control no se normaliza ni auto vincula`() = runTest {
        seedProduct(10, name = "Atun en lata", barcode = "7750001")

        val outcome = useCase(query(barcode = "7750001\n"))

        assertEquals(ProductMatchOutcome.NoMatch, outcome)
    }

    @Test
    fun `codigo de proveedor resuelve por alias antes que por SKU`() = runTest {
        val porAlias = seedProduct(10, name = "Detergente Opal 1L")
        seedProduct(11, name = "Otro producto", sku = "PROV-777")
        seedAlias(20, supplierSeed = 30, product = porAlias, text = "PROV-777")

        val outcome = useCase(query(supplierCode = "PROV-777"))

        val auto = outcome as ProductMatchOutcome.AutoLinked
        assertEquals(porAlias.productId, auto.candidate.product.productId)
        assertEquals(ProductMatchReason.SUPPLIER_CODE, auto.candidate.reason)
    }

    @Test
    fun `alias confirmado prefiere el proveedor de la factura entre varios`() = runTest {
        val proveedorFactura = SupplierId.from(uuid(30))
        val productoPreferido = seedProduct(10, name = "Atun Campomar")
        val productoAlternativo = seedProduct(11, name = "Atun Campomar Light")
        seedAlias(20, supplierSeed = 30, product = productoPreferido, text = "atun campomar")
        seedAlias(21, supplierSeed = 31, product = productoAlternativo, text = "atun campomar")

        val outcome = useCase(
            query(supplierId = proveedorFactura, description = "atun campomar"),
        )

        val auto = outcome as ProductMatchOutcome.AutoLinked
        assertEquals(ProductMatchReason.CONFIRMED_ALIAS, auto.candidate.reason)
        assertEquals(productoPreferido.productId, auto.candidate.product.productId)
        assertEquals(
            listOf(productoAlternativo.productId),
            auto.alternatives.map { it.product.productId },
        )
    }

    @Test
    fun `alias de varios proveedores sin preferencia exige eleccion`() = runTest {
        val primero = seedProduct(10, name = "Atun Campomar")
        val segundo = seedProduct(11, name = "Atun Campomar Light")
        seedAlias(20, supplierSeed = 30, product = primero, text = "atun campomar")
        seedAlias(21, supplierSeed = 31, product = segundo, text = "atun campomar")

        val outcome = useCase(query(description = "atun campomar"))

        val ambiguous = outcome as ProductMatchOutcome.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        assertTrue(ambiguous.candidates.all { it.reason == ProductMatchReason.CONFIRMED_ALIAS })
    }

    @Test
    fun `SKU exacto vincula cuando no hay alias`() = runTest {
        val product = seedProduct(10, name = "Cloro Clorox 1L", sku = "CLX-1L")

        val outcome = useCase(query(supplierCode = "CLX-1L", description = "Cloro generico"))

        val auto = outcome as ProductMatchOutcome.AutoLinked
        assertEquals(product.productId, auto.candidate.product.productId)
        assertEquals(ProductMatchReason.SKU, auto.candidate.reason)
    }

    @Test
    fun `nombre exacto vincula cuando no hay codigos ni alias`() = runTest {
        val product = seedProduct(10, name = "Arroz Costeno 5kg")

        val outcome = useCase(query(description = "  arroz   costeno 5kg "))

        val auto = outcome as ProductMatchOutcome.AutoLinked
        assertEquals(product.productId, auto.candidate.product.productId)
        assertEquals(ProductMatchReason.EXACT_NAME, auto.candidate.reason)
        assertEquals(900, auto.candidate.confidencePermille)
    }

    @Test
    fun `nombre exacto duplicado exige eleccion`() = runTest {
        seedProduct(10, name = "Sal Marina 1kg")
        seedProduct(11, name = "sal marina 1kg")

        val outcome = useCase(query(description = "Sal Marina 1kg"))

        val ambiguous = outcome as ProductMatchOutcome.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `producto inexistente devuelve NoMatch`() = runTest {
        seedProduct(10, name = "Aceite Primor 1L")

        val outcome = useCase(query(description = "refrigeradora side by side"))

        assertEquals(ProductMatchOutcome.NoMatch, outcome)
    }

    @Test
    fun `sugerencia difusa nunca se selecciona automaticamente`() = runTest {
        seedProduct(10, name = "Leche Gloria Entera x 400g")

        val outcome = useCase(query(description = "leche gloria entera"))

        val suggestions = outcome as ProductMatchOutcome.Suggestions
        assertEquals(1, suggestions.candidates.size)
        val candidate = suggestions.candidates.single()
        assertEquals(ProductMatchReason.SIMILAR_NAME, candidate.reason)
        assertTrue(candidate.confidencePermille >= ProductMatchingUseCase.MIN_FUZZY_SCORE_PERMILLE)
    }

    @Test
    fun `busqueda manual lista exactos primero y acota a cinco`() = runTest {
        val exacto = seedProduct(10, name = "Arroz")
        (11..16).forEach { seed -> seedProduct(seed, name = "Arroz variedad $seed") }

        val candidates = useCase.search(businessId, supplierId = null, rawQuery = "arroz")

        assertEquals(ProductMatchingUseCase.MAX_CANDIDATES, candidates.size)
        assertEquals(exacto.productId, candidates.first().product.productId)
        assertEquals(ProductMatchReason.EXACT_NAME, candidates.first().reason)
        assertTrue(candidates.drop(1).all { it.reason == ProductMatchReason.SIMILAR_NAME })
    }

    @Test
    fun `busqueda manual admite exactamente doscientos caracteres`() = runTest {
        val name = "a".repeat(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
        val product = seedProduct(10, name = name)

        val candidates = useCase.search(businessId, supplierId = null, rawQuery = name)

        assertEquals(listOf(product.productId), candidates.map { it.product.productId })
        assertEquals(ProductMatchReason.EXACT_NAME, candidates.single().reason)
    }

    @Test
    fun `busqueda por nombre no interpreta codigo ni alias y conserva sugerencias`() = runTest {
        val exact = seedProduct(10, name = "Cafe molido")
        val similar = seedProduct(11, name = "Cafe molido premium")
        val onlySku = seedProduct(12, name = "Producto sin relacion", sku = "Cafe molido")
        seedAlias(20, supplierSeed = 30, product = onlySku, text = "Cafe molido")

        val candidates = useCase.searchByName(businessId, rawName = "Cafe molido")

        assertEquals(exact.productId, candidates.first().product.productId)
        assertEquals(ProductMatchReason.EXACT_NAME, candidates.first().reason)
        assertTrue(candidates.any { it.product.productId == similar.productId })
        assertTrue(candidates.none { it.product.productId == onlySku.productId })
    }

    @Test
    fun `busqueda por nombre admite prefijos de tokens reales`() = runTest {
        val sugar = seedProduct(10, name = "Azúcar rubia")
        val milk = seedProduct(11, name = "Leche evaporada")

        assertEquals(
            listOf(sugar.productId),
            useCase.searchByName(businessId, rawName = "azuc").map { it.product.productId },
        )
        assertEquals(
            listOf(milk.productId),
            useCase.searchByName(businessId, rawName = "leche evap").map { it.product.productId },
        )
    }

    @Test
    fun `fragmento presente solo en codigos o alias no coincide por nombre`() = runTest {
        val coded = seedProduct(
            seed = 10,
            name = "Harina integral",
            sku = "AZUC-01",
            barcode = "leche evap",
        )
        seedAlias(20, supplierSeed = 30, product = coded, text = "azuc")

        assertTrue(useCase.searchByName(businessId, rawName = "azuc").isEmpty())
        assertTrue(useCase.searchByName(businessId, rawName = "leche evap").isEmpty())
    }

    @Test
    fun `busqueda por nombre no consulta puertos de codigos ni aliases`() = runTest {
        val sugar = seedProduct(10, name = "Azúcar rubia")
        val nameOnlyProducts = object : ProductRepository by products {
            override suspend fun findBySku(businessId: BusinessId, sku: String): Product? =
                error("searchByName no debe consultar SKU")

            override suspend fun findByBarcode(
                businessId: BusinessId,
                barcode: String,
            ): Product? = error("searchByName no debe consultar barcode")

            override suspend fun search(
                businessId: BusinessId,
                query: String,
            ): List<Product> = error("searchByName no debe consultar búsqueda multicolumna")

            override suspend fun search(
                businessId: BusinessId,
                search: CatalogSearch,
            ): CatalogPage<Product> = error("searchByName no debe consultar búsqueda multicolumna")
        }
        val noAliases = object : SupplierProductAliasRepository by aliases {
            override suspend fun findByNormalizedAlias(
                businessId: BusinessId,
                alias: String,
            ): List<SupplierProductAlias> = error("searchByName no debe consultar aliases")
        }
        val nameOnlyUseCase = ProductMatchingUseCase(nameOnlyProducts, noAliases)

        assertEquals(
            listOf(sugar.productId),
            nameOnlyUseCase.searchByName(businessId, rawName = "azuc")
                .map { it.product.productId },
        )
    }

    @Test
    fun `prefijo por nombre conserva maximo tenant y estado activo`() = runTest {
        (10..16).forEach { seed -> seedProduct(seed, name = "Azúcar variedad $seed") }
        seedProduct(17, name = "Azúcar archivada", status = CatalogStatus.ARCHIVED)
        seedProduct(
            seed = 18,
            name = "Azúcar de otro negocio",
            ownerBusinessId = BusinessId.from(uuid(90)),
        )

        val candidates = useCase.searchByName(businessId, rawName = "azuc")

        assertEquals(ProductMatchingUseCase.MAX_CANDIDATES, candidates.size)
        assertTrue(candidates.all { it.product.businessId == businessId })
        assertTrue(candidates.all { it.product.status == CatalogStatus.ACTIVE })
        assertTrue(candidates.all { it.reason == ProductMatchReason.SIMILAR_NAME })
    }

    @Test
    fun `busqueda por nombre aplica limite de entrada y tenant`() = runTest {
        val accepted = "a".repeat(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
        val local = seedProduct(10, name = accepted)
        seedProduct(
            seed = 11,
            name = accepted,
            ownerBusinessId = BusinessId.from(uuid(90)),
        )

        assertEquals(
            listOf(local.productId),
            useCase.searchByName(businessId, accepted).map { it.product.productId },
        )
        assertTrue(useCase.searchByName(businessId, accepted + "b").isEmpty())
    }

    @Test
    fun `texto mayor al limite no llega a busqueda difusa`() = runTest {
        val acceptedPrefix = "a".repeat(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
        seedProduct(10, name = acceptedPrefix)
        val overlong = acceptedPrefix + "b"

        assertTrue(useCase.search(businessId, supplierId = null, rawQuery = overlong).isEmpty())
        assertEquals(
            ProductMatchOutcome.NoMatch,
            useCase(query(description = overlong)),
        )
        assertEquals(0, ProductNameSimilarity.similarityPermille(overlong, acceptedPrefix))
    }

    @Test
    fun `alias corrupto no filtra producto de otro negocio`() = runTest {
        val foreignBusinessId = BusinessId.from(uuid(90))
        val foreignProduct = seedProduct(
            seed = 10,
            name = "Producto de otro negocio",
            ownerBusinessId = foreignBusinessId,
        )
        // Simula una fila importada/corrupta: el alias dice pertenecer al negocio actual pero su
        // productId referencia un producto de otro tenant.
        seedAlias(20, supplierSeed = 30, product = foreignProduct, text = "PROV-CROSS-TENANT")

        assertEquals(
            ProductMatchOutcome.NoMatch,
            useCase(query(supplierCode = "PROV-CROSS-TENANT")),
        )
        assertTrue(
            useCase.search(
                businessId,
                supplierId = null,
                rawQuery = "PROV-CROSS-TENANT",
            ).isEmpty(),
        )
    }

    @Test
    fun `producto staged no aparece en matching antes de confirmar la compra`() = runTest {
        val supplierId = SupplierId.from(uuid(30))
        val configuration = FakeAppConfigurationRepository().also { repository ->
            repository.completeOnboarding(
                businessId,
                TaxRate(BigDecimal("18")),
                CostPolicy.NET,
            )
        }
        val create = CreateLinkedProductUseCase(
            appConfigurationRepository = configuration,
            productRepository = products,
            uuidGenerator = UuidGenerator { uuid(100) },
        )
        // Primera vez: el catalogo no conoce la descripcion del proveedor.
        val primera = useCase(query(supplierId = supplierId, description = "jabon bolivar lavanda 125g"))
        assertEquals(ProductMatchOutcome.NoMatch, primera)

        val staged = create(
            NewLinkedProduct(
                businessId = businessId,
                name = "Jabón Bolívar Lavanda 125 g",
                unitId = unitId,
                salePrice = Money.ofMinor(500L, CurrencyCode.of("PEN")),
            ),
        ) as CreateLinkedProductResult.Staged

        // Hasta el commit final no hay catalogo ni alias que puedan contaminar otra factura.
        val segunda = useCase(query(supplierId = supplierId, description = "jabon bolivar lavanda 125g"))
        assertEquals(ProductMatchOutcome.NoMatch, segunda)
        assertEquals(null, products.findById(staged.product.productId))
    }

    @Test
    fun `producto archivado nunca se propone`() = runTest {
        seedProduct(10, name = "Vinagre Tinto 500ml", status = CatalogStatus.ARCHIVED)

        val outcome = useCase(query(description = "Vinagre Tinto 500ml"))

        assertEquals(ProductMatchOutcome.NoMatch, outcome)
    }

    private fun query(
        supplierId: SupplierId? = null,
        barcode: String? = null,
        supplierCode: String? = null,
        description: String? = null,
    ) = ProductMatchQuery(
        businessId = businessId,
        supplierId = supplierId,
        barcode = barcode,
        supplierCode = supplierCode,
        description = description,
    )

    private suspend fun seedProduct(
        seed: Int,
        name: String,
        sku: String? = null,
        barcode: String? = null,
        status: CatalogStatus = CatalogStatus.ACTIVE,
        ownerBusinessId: BusinessId = businessId,
    ): Product = products.create(
        Product(
            productId = ProductId.from(uuid(seed)),
            businessId = ownerBusinessId,
            unitId = unitId,
            name = name,
            sku = sku,
            barcode = barcode,
            status = status,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        ),
    )

    private suspend fun seedAlias(
        seed: Int,
        supplierSeed: Int,
        product: Product,
        text: String,
    ): SupplierProductAlias = aliases.create(
        SupplierProductAlias(
            aliasId = AliasId.from(uuid(seed)),
            businessId = businessId,
            supplierId = SupplierId.from(uuid(supplierSeed)),
            productId = product.productId,
            alias = text,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        ),
    )

    private companion object {
        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
