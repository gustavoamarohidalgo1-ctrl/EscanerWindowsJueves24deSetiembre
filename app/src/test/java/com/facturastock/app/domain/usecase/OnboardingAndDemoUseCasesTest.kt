package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeOnboardingProvisioningRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeUnitRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAndDemoUseCasesTest {
    private val clock = AppClock { Instant.parse("2026-08-08T12:00:00Z") }

    private var uuidCounter = 0
    private val uuidGenerator = UuidGenerator {
        UUID.fromString("00000000-0000-0000-0000-%012d".format(++uuidCounter))
    }

    private lateinit var businesses: FakeBusinessRepository
    private lateinit var suppliers: FakeSupplierRepository
    private lateinit var units: FakeUnitRepository
    private lateinit var locations: FakeInventoryLocationRepository
    private lateinit var products: FakeProductRepository
    private lateinit var appConfig: FakeAppConfigurationRepository

    private lateinit var drafts: FakeInvoiceDraftRepository

    private fun setUpFakes() {
        suppliers = FakeSupplierRepository(clock)
        units = FakeUnitRepository(clock)
        locations = FakeInventoryLocationRepository(clock)
        products = FakeProductRepository(clock)
        drafts = FakeInvoiceDraftRepository(clock)
        businesses = FakeBusinessRepository(
            clock = clock,
            cascadeChildren = listOf(
                suppliers::cascadeDeleteForBusiness,
                units::cascadeDeleteForBusiness,
                locations::cascadeDeleteForBusiness,
                products::cascadeDeleteForBusiness,
                drafts::cascadeDeleteForBusiness,
            ),
        )
        appConfig = FakeAppConfigurationRepository()
    }

    @Test
    fun `CompleteOnboarding crea negocio almacen unidad base y marca la configuracion`() = runTest {
        setUpFakes()
        val useCase = completeOnboardingUseCase()

        val business = useCase(
            businessName = "  Bodega Lola  ",
            ruc = "20123456786",
            warehouseName = "Almacén central",
            taxRate = TaxRate(BigDecimal("18")),
            costPolicy = CostPolicy.GROSS,
        )

        assertEquals("Bodega Lola", business.legalName)
        assertEquals("20123456786", business.ruc)
        assertEquals(clock.now(), business.createdAt)
        assertEquals(business, businesses.findById(business.businessId))

        val warehouses = locations.observeForBusiness(business.businessId).first()
        assertEquals(listOf("Almacén central"), warehouses.map { it.name })
        val createdUnits = units.observeForBusiness(business.businessId).first()
        assertEquals(listOf("NIU"), createdUnits.map { it.code })
        assertEquals(listOf("Unidad"), createdUnits.map { it.name })

        val config = appConfig.current()
        assertTrue(config.onboardingCompleted)
        assertEquals(business.businessId, config.businessId)
        assertEquals(TaxRate(BigDecimal("18")), config.taxRate)
        assertEquals(CostPolicy.GROSS, config.costPolicy)
    }

    @Test
    fun `CompleteOnboarding admite RUC ausente y lo guarda como nulo`() = runTest {
        setUpFakes()
        val business = completeOnboardingUseCase()(
            businessName = "Bodega Lola",
            ruc = "   ",
            warehouseName = "Almacén central",
            taxRate = TaxRate(BigDecimal("18")),
            costPolicy = CostPolicy.NET,
        )

        assertNull(business.ruc)
        assertNull(businesses.findById(business.businessId)?.ruc)
        assertTrue(appConfig.current().onboardingCompleted)
    }

    @Test
    fun `CompleteOnboarding reintenta finalizacion sin duplicar el grafo Room`() = runTest {
        setUpFakes()
        val useCase = completeOnboardingUseCase()
        appConfig.nextOnboardingFinalizationFailure = StorageError.Unavailable

        val firstFailure = runCatching {
            useCase(
                businessName = "Bodega Lola",
                ruc = "20123456786",
                warehouseName = "Almacén central",
                taxRate = TaxRate(BigDecimal("18")),
                costPolicy = CostPolicy.GROSS,
            )
        }.exceptionOrNull()

        assertTrue(firstFailure is StorageException)
        assertEquals(StorageError.Unavailable, (firstFailure as StorageException).error)
        val checkpoint = requireNotNull(appConfig.pendingOnboardingProvision)
        assertFalse(appConfig.current().onboardingCompleted)
        assertEquals(1, businesses.observeBusinesses().first().size)
        assertEquals(1, locations.observeForBusiness(checkpoint.businessId).first().size)
        assertEquals(1, units.observeForBusiness(checkpoint.businessId).first().size)

        val retried = useCase(
            businessName = "Bodega Lola",
            ruc = "20123456786",
            warehouseName = "Almacén central",
            taxRate = TaxRate(BigDecimal("18")),
            costPolicy = CostPolicy.GROSS,
        )
        val repeatedAfterCompletion = useCase(
            businessName = "Este texto ya no debe crear otro negocio",
            ruc = null,
            warehouseName = "Otro almacén",
            taxRate = TaxRate(BigDecimal("10")),
            costPolicy = CostPolicy.NET,
        )

        assertEquals(checkpoint.businessId, retried.businessId)
        assertEquals(retried, repeatedAfterCompletion)
        assertNull(appConfig.pendingOnboardingProvision)
        assertTrue(appConfig.current().onboardingCompleted)
        assertEquals(1, businesses.observeBusinesses().first().size)
        assertEquals(1, locations.observeForBusiness(checkpoint.businessId).first().size)
        assertEquals(1, units.observeForBusiness(checkpoint.businessId).first().size)
    }

    @Test
    fun `CompleteOnboarding rechaza RUC mal formado sin persistir nada`() = runTest {
        setUpFakes()
        val exception = assertThrows(DomainRuleViolation::class.java) {
            kotlinx.coroutines.runBlocking {
                completeOnboardingUseCase()(
                    businessName = "Bodega Lola",
                    ruc = "2012345678",
                    warehouseName = "Almacén central",
                    taxRate = TaxRate(BigDecimal("18")),
                    costPolicy = CostPolicy.NET,
                )
            }
        }

        assertEquals(ValidationError.InvalidRuc("2012345678"), exception.error)
        assertTrue(businesses.observeBusinesses().first().isEmpty())
        assertFalse(appConfig.current().onboardingCompleted)
    }

    @Test
    fun `EnterDemoMode siembra datos sinteticos y activa el modo demo`() = runTest {
        setUpFakes()
        val demoId = enterDemoModeUseCase()()

        val demoBusiness = businesses.findById(demoId)
        assertEquals("[DEMO] Bodega de demostración", demoBusiness?.legalName)
        assertNull(demoBusiness?.ruc)

        assertEquals(
            listOf("KGM", "LTR", "NIU"),
            units.observeForBusiness(demoId).first().map { it.code },
        )
        assertEquals(
            listOf("[DEMO] Almacén principal"),
            locations.observeForBusiness(demoId).first().map { it.name },
        )
        assertEquals(3, suppliers.observeForBusiness(demoId).first().size)
        assertEquals(
            6,
            products.observeForBusiness(demoId).first()
                .count { it.name.startsWith("[DEMO] ") },
        )

        val config = appConfig.current()
        assertTrue(config.isDemoMode)
        assertEquals(demoId, config.activeBusinessId)
    }

    @Test
    fun `EnterDemoMode admite reentrar sin conflicto gracias al RUC nulo`() = runTest {
        setUpFakes()
        val useCase = enterDemoModeUseCase()

        val firstDemo = useCase()
        val secondDemo = useCase()

        assertTrue(firstDemo != secondDemo)
        assertEquals(secondDemo, appConfig.current().activeBusinessId)
        assertEquals(2, businesses.observeBusinesses().first().size)
    }

    @Test
    fun `ExitDemoMode sale del demo y elimina sus datos en cascada`() = runTest {
        setUpFakes()
        val demoId = enterDemoModeUseCase()()
        val scheduler = FakePurchaseBackupScheduler()
        assertTrue(appConfig.current().isDemoMode)

        ExitDemoModeUseCase(
            appConfigurationRepository = appConfig,
            businessRepository = businesses,
            purchaseBackupScheduler = scheduler,
        )()

        val config = appConfig.current()
        assertFalse(config.isDemoMode)
        assertNull(config.activeBusinessId)
        assertNull(businesses.findById(demoId))
        assertTrue(products.observeForBusiness(demoId).first().isEmpty())
        assertTrue(suppliers.observeForBusiness(demoId).first().isEmpty())
        assertTrue(units.observeForBusiness(demoId).first().isEmpty())
        assertEquals(1, scheduler.enqueueCount)
        assertEquals(1, scheduler.privacyEnqueueCount)
    }

    private fun completeOnboardingUseCase() = CompleteOnboardingUseCase(
        onboardingProvisioningRepository = FakeOnboardingProvisioningRepository(
            businesses = businesses,
            locations = locations,
            units = units,
        ),
        appConfigurationRepository = appConfig,
        uuidGenerator = uuidGenerator,
        appClock = clock,
    )

    private fun enterDemoModeUseCase() = EnterDemoModeUseCase(
        businessRepository = businesses,
        supplierRepository = suppliers,
        unitRepository = units,
        inventoryLocationRepository = locations,
        productRepository = products,
        appConfigurationRepository = appConfig,
        uuidGenerator = uuidGenerator,
        appClock = clock,
    )
}
