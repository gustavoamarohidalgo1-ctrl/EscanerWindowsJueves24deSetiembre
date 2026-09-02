package com.facturastock.app.testing

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageQualityReport
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.MAX_CATALOG_PAGE_SIZE
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.acceptsImageMutations
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.CapturedPageWrite
import com.facturastock.app.domain.repository.CapturedPageIntent
import com.facturastock.app.domain.repository.DeletedDraftImage
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.DraftImageImporter
import com.facturastock.app.domain.repository.ImportedImageFile
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ImageQualityAnalyzer
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.OnboardingProvision
import com.facturastock.app.domain.repository.OnboardingProvisionIds
import com.facturastock.app.domain.repository.OnboardingProvisioningRepository
import com.facturastock.app.domain.repository.OnboardingReservation
import com.facturastock.app.domain.repository.OcrImageFile
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import com.facturastock.app.domain.repository.PublishedCapturedPage
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.UnitRepository
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fakes en memoria de los puertos de catálogo y borradores, para pruebas de casos de uso y
 * ViewModels sin Room. Comparten el mismo contrato observable que las implementaciones Room:
 *
 * - Cada fake guarda sus registros en un mapa y expone los `observe*` con
 *   [MutableStateFlow] por clave observada; toda mutación reemite las listas afectadas con el
 *   mismo orden que Room (catálogos por nombre/código/razón social, borradores por
 *   `updatedAt` descendente, líneas por `position`, imágenes por `pageIndex`).
 * - Las marcas de tiempo las estampa el [AppClock] inyectado con la política de los repos
 *   Room: create fija `createdAt = updatedAt = now`, update fija `updatedAt = now` y las
 *   mutaciones de imágenes/líneas tocan el `updatedAt` del borrador padre.
 * - Las búsquedas normalizan igual que los repos Room (`trim` + minúsculas, `contains`).
 * - La unicidad operativa por negocio (RUC, SKU, etc.) la impone Room; aquí solo se valida la
 *   clave primaria, las claves foráneas y la pertenencia al negocio del agregado de borradores.
 *   Para probar conflictos de catálogo se programa un fallo con el hook [StorageFailureHook].
 */

/** La próxima operación suspendida del fake lanza [StorageException] con este error. */
internal class StorageFailureHook {
    @Volatile
    var nextFailure: StorageError? = null

    fun throwIfScheduled() {
        val error = nextFailure ?: return
        nextFailure = null
        throw StorageException(error)
    }
}

class FakeBusinessRepository(
    private val clock: AppClock,
    private val cascadeChildren: List<suspend (BusinessId) -> Unit> = emptyList(),
) : BusinessRepository {
    private val failureHook = StorageFailureHook()
    private val businesses = mutableMapOf<BusinessId, Business>()
    private val businessFlow = MutableStateFlow<List<Business>>(emptyList())

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    /** Lectura programable para probar snapshots optimistas sin mutar el almacenamiento fake. */
    var findByIdHandler: (suspend (BusinessId) -> Business?)? = null

    override suspend fun create(business: Business): Business {
        failureHook.throwIfScheduled()
        requirePrimaryKeyFree(business.businessId !in businesses, "businessId duplicado")
        val now = clock.now()
        val stamped = business.copy(createdAt = now, updatedAt = now)
        businesses[stamped.businessId] = stamped
        refreshFlows()
        return stamped
    }

    override suspend fun update(business: Business): Boolean {
        failureHook.throwIfScheduled()
        if (business.businessId !in businesses) return false
        businesses[business.businessId] = business.copy(updatedAt = clock.now())
        refreshFlows()
        return true
    }

    override suspend fun findById(businessId: BusinessId): Business? {
        failureHook.throwIfScheduled()
        findByIdHandler?.let { handler -> return handler(businessId) }
        return businesses[businessId]
    }

    override fun observeById(businessId: BusinessId): Flow<Business?> =
        businessFlow.map { businesses -> businesses.firstOrNull { it.businessId == businessId } }

    override suspend fun findByRuc(ruc: String): Business? {
        failureHook.throwIfScheduled()
        return businesses.values.firstOrNull { it.ruc == ruc }
    }

    override fun observeBusinesses(): Flow<List<Business>> = businessFlow

    override suspend fun deleteById(businessId: BusinessId): Boolean {
        failureHook.throwIfScheduled()
        val removed = businesses.remove(businessId) != null
        if (removed) {
            // Misma cascada que Room: los hijos conectados caen con el negocio.
            cascadeChildren.forEach { it(businessId) }
            refreshFlows()
        }
        return removed
    }

    private fun refreshFlows() {
        businessFlow.value = businesses.values.sortedBy { it.legalName }
    }
}

/** Fake serializado del agregado inicial; comparte los catálogos visibles por los tests. */
class FakeOnboardingProvisioningRepository(
    private val businesses: FakeBusinessRepository,
    private val locations: FakeInventoryLocationRepository,
    private val units: FakeUnitRepository,
) : OnboardingProvisioningRepository {
    private val lock = Mutex()

    override suspend fun provision(provision: OnboardingProvision): Business = lock.withLock {
        val business = businesses.findById(provision.business.businessId)
            ?: provision.business.ruc?.let { businesses.findByRuc(it) }
            ?: businesses.observeBusinesses().firstOrNullMatching {
                it.ruc == null &&
                    provision.business.ruc == null &&
                    it.legalName == provision.business.legalName
            }
            ?: businesses.create(provision.business)

        if (locations.findById(provision.warehouse.locationId) == null &&
            locations.findByName(business.businessId, provision.warehouse.name) == null
        ) {
            locations.create(provision.warehouse.copy(businessId = business.businessId))
        }
        if (units.findById(provision.defaultUnit.unitId) == null &&
            units.findByCode(business.businessId, provision.defaultUnit.code) == null
        ) {
            units.create(provision.defaultUnit.copy(businessId = business.businessId))
        }
        business
    }

    override suspend fun findBusiness(businessId: BusinessId): Business? =
        businesses.findById(businessId)

    private suspend fun Flow<List<Business>>.firstOrNullMatching(
        predicate: (Business) -> Boolean,
    ): Business? = first().firstOrNull(predicate)
}

class FakeSupplierRepository(
    private val clock: AppClock,
) : SupplierRepository {
    private val failureHook = StorageFailureHook()
    private val suppliers = mutableMapOf<SupplierId, Supplier>()
    private val supplierFlows = mutableMapOf<BusinessId, MutableStateFlow<List<Supplier>>>()

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    override suspend fun create(supplier: Supplier): Supplier {
        failureHook.throwIfScheduled()
        requirePrimaryKeyFree(supplier.supplierId !in suppliers, "supplierId duplicado")
        val now = clock.now()
        val stamped = supplier.copy(
            ruc = CatalogCanonicalizer.ruc(supplier.ruc),
            createdAt = now,
            updatedAt = now,
        )
        suppliers[stamped.supplierId] = stamped
        refreshFlows()
        return stamped
    }

    /** Misma semántica CAS que Room: solo escribe si la versión leída sigue vigente. */
    override suspend fun update(supplier: Supplier): Boolean {
        failureHook.throwIfScheduled()
        val stored = suppliers[supplier.supplierId] ?: return false
        if (stored.version != supplier.version) return false
        suppliers[supplier.supplierId] = supplier.copy(
            ruc = CatalogCanonicalizer.ruc(supplier.ruc),
            updatedAt = clock.now(),
            version = stored.version + 1,
        )
        refreshFlows()
        return true
    }

    override suspend fun findById(supplierId: SupplierId): Supplier? {
        failureHook.throwIfScheduled()
        return suppliers[supplierId]
    }

    override suspend fun findByRuc(businessId: BusinessId, ruc: String): Supplier? {
        failureHook.throwIfScheduled()
        val canonical = CatalogCanonicalizer.ruc(ruc)
        return suppliers.values.firstOrNull { it.businessId == businessId && it.ruc == canonical }
    }

    override suspend fun search(businessId: BusinessId, query: String): List<Supplier> {
        failureHook.throwIfScheduled()
        val normalized = query.trim().lowercase(Locale.ROOT)
        return suppliers.values
            .filter { it.businessId == businessId }
            .filter { supplier ->
                supplier.legalName.lowercase(Locale.ROOT).contains(normalized) ||
                    supplier.tradeName?.lowercase(Locale.ROOT)?.contains(normalized) == true ||
                    supplier.ruc?.contains(normalized) == true
            }
            .sortedBy { it.legalName }
    }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<Supplier> {
        val all = search(businessId, search.query).filter { search.status == null || it.status == search.status }
        return CatalogPage(
            items = all.drop(search.offset).take(search.limit),
            total = all.size,
            offset = search.offset,
            limit = search.limit,
        )
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<Supplier>> =
        supplierFlows.getOrPut(businessId) { MutableStateFlow(sortedFor(businessId)) }

    suspend fun deleteById(supplierId: SupplierId): Boolean {
        failureHook.throwIfScheduled()
        val removed = suppliers.remove(supplierId) != null
        if (removed) refreshFlows()
        return removed
    }

    override suspend fun archive(supplierId: SupplierId): Boolean = setStatus(
        supplierId,
        CatalogStatus.ARCHIVED,
    )

    override suspend fun restore(supplierId: SupplierId): Boolean = setStatus(
        supplierId,
        CatalogStatus.ACTIVE,
    )

    private fun setStatus(supplierId: SupplierId, status: CatalogStatus): Boolean {
        failureHook.throwIfScheduled()
        val current = suppliers[supplierId] ?: return false
        suppliers[supplierId] = current.copy(status = status, updatedAt = clock.now())
        refreshFlows()
        return true
    }

    /** Simula la cascada de Room al borrar el negocio; la invoca FakeBusinessRepository. */
    suspend fun cascadeDeleteForBusiness(businessId: BusinessId) {
        suppliers.values.removeAll { it.businessId == businessId }
        refreshFlows()
    }

    private fun sortedFor(businessId: BusinessId): List<Supplier> =
        suppliers.values.filter { it.businessId == businessId }.sortedBy { it.legalName }

    private fun refreshFlows() {
        supplierFlows.forEach { (businessId, flow) -> flow.value = sortedFor(businessId) }
    }
}

class FakeUnitRepository(
    private val clock: AppClock,
    private val productRepository: FakeProductRepository? = null,
) : UnitRepository {
    private val failureHook = StorageFailureHook()
    private val units = mutableMapOf<UnitId, UnitOfMeasure>()
    private val unitFlows = mutableMapOf<BusinessId, MutableStateFlow<List<UnitOfMeasure>>>()

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    override suspend fun create(unit: UnitOfMeasure): UnitOfMeasure {
        failureHook.throwIfScheduled()
        requirePrimaryKeyFree(unit.unitId !in units, "unitId duplicado")
        val now = clock.now()
        val stamped = unit.copy(
            code = CatalogCanonicalizer.unitCode(unit.code),
            createdAt = now,
            updatedAt = now,
        )
        units[stamped.unitId] = stamped
        refreshFlows()
        return stamped
    }

    override suspend fun update(unit: UnitOfMeasure): Boolean {
        failureHook.throwIfScheduled()
        if (unit.unitId !in units) return false
        units[unit.unitId] = unit.copy(
            code = CatalogCanonicalizer.unitCode(unit.code),
            updatedAt = clock.now(),
        )
        refreshFlows()
        return true
    }

    override suspend fun findById(unitId: UnitId): UnitOfMeasure? {
        failureHook.throwIfScheduled()
        return units[unitId]
    }

    override suspend fun findByCode(businessId: BusinessId, code: String): UnitOfMeasure? {
        failureHook.throwIfScheduled()
        val normalized = CatalogCanonicalizer.unitCode(code)
        return units.values.firstOrNull { it.businessId == businessId && it.code == normalized }
    }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<UnitOfMeasure> {
        failureHook.throwIfScheduled()
        val query = search.query.trim().lowercase(Locale.ROOT)
        val all = sortedFor(businessId).filter { unit ->
            (search.status == null || unit.status == search.status) &&
                (unit.code.lowercase(Locale.ROOT).contains(query) ||
                    unit.name.lowercase(Locale.ROOT).contains(query) ||
                    unit.symbol?.lowercase(Locale.ROOT)?.contains(query) == true)
        }
        return CatalogPage(all.drop(search.offset).take(search.limit), all.size, search.offset, search.limit)
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<UnitOfMeasure>> =
        unitFlows.getOrPut(businessId) { MutableStateFlow(sortedFor(businessId)) }

    suspend fun deleteById(unitId: UnitId): Boolean {
        failureHook.throwIfScheduled()
        if (productRepository?.containsProductsForUnit(unitId) == true) {
            throw StorageException(
                StorageError.ConstraintConflict("la unidad ${unitId.value} está en uso por productos"),
            )
        }
        val removed = units.remove(unitId) != null
        if (removed) refreshFlows()
        return removed
    }

    override suspend fun archive(unitId: UnitId): Boolean = setStatus(unitId, CatalogStatus.ARCHIVED)

    override suspend fun restore(unitId: UnitId): Boolean = setStatus(unitId, CatalogStatus.ACTIVE)

    private fun setStatus(unitId: UnitId, status: CatalogStatus): Boolean {
        failureHook.throwIfScheduled()
        val current = units[unitId] ?: return false
        units[unitId] = current.copy(status = status, updatedAt = clock.now())
        refreshFlows()
        return true
    }

    /** Simula la cascada de Room al borrar el negocio; la invoca FakeBusinessRepository. */
    suspend fun cascadeDeleteForBusiness(businessId: BusinessId) {
        units.values.removeAll { it.businessId == businessId }
        refreshFlows()
    }

    private fun sortedFor(businessId: BusinessId): List<UnitOfMeasure> =
        units.values.filter { it.businessId == businessId }.sortedBy { it.code }

    private fun refreshFlows() {
        unitFlows.forEach { (businessId, flow) -> flow.value = sortedFor(businessId) }
    }
}

class FakeInventoryLocationRepository(
    private val clock: AppClock,
) : InventoryLocationRepository {
    private val failureHook = StorageFailureHook()
    private val locations = mutableMapOf<LocationId, InventoryLocation>()
    private val locationFlows = mutableMapOf<BusinessId, MutableStateFlow<List<InventoryLocation>>>()

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    override suspend fun create(location: InventoryLocation): InventoryLocation {
        failureHook.throwIfScheduled()
        requirePrimaryKeyFree(location.locationId !in locations, "locationId duplicado")
        val now = clock.now()
        val stamped = location.copy(createdAt = now, updatedAt = now)
        locations[stamped.locationId] = stamped
        refreshFlows()
        return stamped
    }

    override suspend fun update(location: InventoryLocation): Boolean {
        failureHook.throwIfScheduled()
        if (location.locationId !in locations) return false
        locations[location.locationId] = location.copy(updatedAt = clock.now())
        refreshFlows()
        return true
    }

    override suspend fun findById(locationId: LocationId): InventoryLocation? {
        failureHook.throwIfScheduled()
        return locations[locationId]
    }

    override suspend fun findByName(businessId: BusinessId, name: String): InventoryLocation? {
        failureHook.throwIfScheduled()
        return locations.values.firstOrNull { it.businessId == businessId && it.name == name }
    }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<InventoryLocation> {
        failureHook.throwIfScheduled()
        val query = search.query.trim().lowercase(Locale.ROOT)
        val all = sortedFor(businessId).filter { location ->
            (search.status == null || location.status == search.status) &&
                location.name.lowercase(Locale.ROOT).contains(query)
        }
        return CatalogPage(all.drop(search.offset).take(search.limit), all.size, search.offset, search.limit)
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<InventoryLocation>> =
        locationFlows.getOrPut(businessId) { MutableStateFlow(sortedFor(businessId)) }

    suspend fun deleteById(locationId: LocationId): Boolean {
        failureHook.throwIfScheduled()
        val removed = locations.remove(locationId) != null
        if (removed) refreshFlows()
        return removed
    }

    override suspend fun archive(locationId: LocationId): Boolean = setStatus(
        locationId,
        CatalogStatus.ARCHIVED,
    )

    override suspend fun restore(locationId: LocationId): Boolean = setStatus(
        locationId,
        CatalogStatus.ACTIVE,
    )

    private fun setStatus(locationId: LocationId, status: CatalogStatus): Boolean {
        failureHook.throwIfScheduled()
        val current = locations[locationId] ?: return false
        locations[locationId] = current.copy(status = status, updatedAt = clock.now())
        refreshFlows()
        return true
    }

    /** Simula la cascada de Room al borrar el negocio; la invoca FakeBusinessRepository. */
    suspend fun cascadeDeleteForBusiness(businessId: BusinessId) {
        locations.values.removeAll { it.businessId == businessId }
        refreshFlows()
    }

    private fun sortedFor(businessId: BusinessId): List<InventoryLocation> =
        locations.values.filter { it.businessId == businessId }.sortedBy { it.name }

    private fun refreshFlows() {
        locationFlows.forEach { (businessId, flow) -> flow.value = sortedFor(businessId) }
    }
}

class FakeProductRepository(
    private val clock: AppClock,
) : ProductRepository {
    private val failureHook = StorageFailureHook()
    private val products = mutableMapOf<ProductId, Product>()
    private val productFlows = mutableMapOf<BusinessId, MutableStateFlow<List<Product>>>()

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    override suspend fun create(product: Product): Product {
        failureHook.throwIfScheduled()
        requirePrimaryKeyFree(product.productId !in products, "productId duplicado")
        val now = clock.now()
        val stamped = product.copy(
            sku = CatalogCanonicalizer.sku(product.sku),
            barcode = CatalogCanonicalizer.barcode(product.barcode),
            createdAt = now,
            updatedAt = now,
        )
        products[stamped.productId] = stamped
        refreshFlows()
        return stamped
    }

    /** Misma semántica CAS que Room: solo escribe si la versión leída sigue vigente. */
    override suspend fun update(product: Product): Boolean {
        failureHook.throwIfScheduled()
        val stored = products[product.productId] ?: return false
        if (stored.version != product.version) return false
        products[product.productId] = product.copy(
            sku = CatalogCanonicalizer.sku(product.sku),
            barcode = CatalogCanonicalizer.barcode(product.barcode),
            updatedAt = clock.now(),
            version = stored.version + 1,
        )
        refreshFlows()
        return true
    }

    override suspend fun updateSalePrice(
        businessId: BusinessId,
        productId: ProductId,
        expectedVersion: Long,
        salePrice: Money,
    ): ProductSalePriceMutationResult {
        failureHook.throwIfScheduled()
        if (expectedVersion < 1L || !ProductSalePricePolicy.supports(salePrice)) {
            return ProductSalePriceMutationResult.InvalidPrice
        }
        val stored = products[productId] ?: return ProductSalePriceMutationResult.NotFound
        if (stored.businessId != businessId) return ProductSalePriceMutationResult.NotFound
        if (stored.status != CatalogStatus.ACTIVE) return ProductSalePriceMutationResult.Inactive
        if (stored.version != expectedVersion || stored.version == Long.MAX_VALUE) {
            return ProductSalePriceMutationResult.Stale
        }
        if (stored.salePrice == salePrice) {
            return ProductSalePriceMutationResult.Unchanged(stored)
        }
        val updated = stored.copy(
            salePrice = salePrice,
            version = stored.version + 1L,
            updatedAt = clock.now(),
        )
        products[productId] = updated
        refreshFlows()
        return ProductSalePriceMutationResult.Updated(updated)
    }

    override suspend fun findById(productId: ProductId): Product? {
        failureHook.throwIfScheduled()
        return products[productId]
    }

    override suspend fun findBySku(businessId: BusinessId, sku: String): Product? {
        failureHook.throwIfScheduled()
        val canonical = CatalogCanonicalizer.sku(sku)
        return products.values.firstOrNull { it.businessId == businessId && it.sku == canonical }
    }

    override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product? {
        failureHook.throwIfScheduled()
        val canonical = CatalogCanonicalizer.barcode(barcode)
        return products.values.firstOrNull { it.businessId == businessId && it.barcode == canonical }
    }

    override suspend fun findByNormalizedName(
        businessId: BusinessId,
        normalizedName: String,
    ): List<Product> {
        failureHook.throwIfScheduled()
        val normalized = normalizedName.trim().lowercase(Locale.ROOT)
        return products.values
            .filter { it.businessId == businessId }
            .filter { it.name.trim().lowercase(Locale.ROOT) == normalized }
            .sortedBy { it.name }
    }

    override suspend fun searchActiveByName(
        businessId: BusinessId,
        query: String,
        limit: Int,
    ): List<Product> {
        failureHook.throwIfScheduled()
        require(limit in 1..MAX_CATALOG_PAGE_SIZE)
        val normalized = query.trim().lowercase(Locale.ROOT)
        return products.values
            .asSequence()
            .filter { it.businessId == businessId && it.status == CatalogStatus.ACTIVE }
            .filter { it.name.trim().lowercase(Locale.ROOT).contains(normalized) }
            .sortedWith(compareBy<Product> { it.name }.thenBy { it.productId.value })
            .take(limit)
            .toList()
    }

    override suspend fun search(businessId: BusinessId, query: String): List<Product> {
        failureHook.throwIfScheduled()
        val normalized = query.trim().lowercase(Locale.ROOT)
        return products.values
            .filter { it.businessId == businessId }
            .filter {
                it.name.trim().lowercase(Locale.ROOT).contains(normalized) ||
                    it.sku?.lowercase(Locale.ROOT)?.contains(normalized) == true ||
                    it.barcode?.contains(normalized) == true
            }
            .sortedBy { it.name }
    }

    override suspend fun search(
        businessId: BusinessId,
        search: CatalogSearch,
    ): CatalogPage<Product> {
        val all = search(businessId, search.query).filter { search.status == null || it.status == search.status }
        return CatalogPage(
            items = all.drop(search.offset).take(search.limit),
            total = all.size,
            offset = search.offset,
            limit = search.limit,
        )
    }

    override fun observeForBusiness(businessId: BusinessId): Flow<List<Product>> =
        productFlows.getOrPut(businessId) { MutableStateFlow(sortedFor(businessId)) }

    suspend fun deleteById(productId: ProductId): Boolean {
        failureHook.throwIfScheduled()
        val removed = products.remove(productId) != null
        if (removed) refreshFlows()
        return removed
    }

    override suspend fun archive(productId: ProductId): Boolean = setStatus(
        productId,
        CatalogStatus.ARCHIVED,
    )

    override suspend fun restore(productId: ProductId): Boolean = setStatus(
        productId,
        CatalogStatus.ACTIVE,
    )

    private fun setStatus(productId: ProductId, status: CatalogStatus): Boolean {
        failureHook.throwIfScheduled()
        val current = products[productId] ?: return false
        products[productId] = current.copy(status = status, updatedAt = clock.now())
        refreshFlows()
        return true
    }

    /** Simula la cascada de Room al borrar el negocio; la invoca FakeBusinessRepository. */
    suspend fun cascadeDeleteForBusiness(businessId: BusinessId) {
        products.values.removeAll { it.businessId == businessId }
        refreshFlows()
    }

    /** Expone la regla RESTRICT unidad↔producto para que [FakeUnitRepository] la aplique. */
    fun containsProductsForUnit(unitId: UnitId): Boolean =
        products.values.any { it.unitId == unitId }

    private fun sortedFor(businessId: BusinessId): List<Product> =
        products.values.filter { it.businessId == businessId }.sortedBy { it.name }

    private fun refreshFlows() {
        productFlows.forEach { (businessId, flow) -> flow.value = sortedFor(businessId) }
    }
}

class FakeSupplierProductAliasRepository(
    private val clock: AppClock,
) : SupplierProductAliasRepository {
    private val failureHook = StorageFailureHook()
    private val aliases = mutableMapOf<AliasId, SupplierProductAlias>()
    private val aliasFlows = mutableMapOf<ProductId, MutableStateFlow<List<SupplierProductAlias>>>()

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    override suspend fun create(alias: SupplierProductAlias): SupplierProductAlias {
        failureHook.throwIfScheduled()
        requirePrimaryKeyFree(alias.aliasId !in aliases, "aliasId duplicado")
        val now = clock.now()
        val stamped = alias.copy(createdAt = now, updatedAt = now)
        aliases[stamped.aliasId] = stamped
        refreshFlows()
        return stamped
    }

    override suspend fun update(alias: SupplierProductAlias): Boolean {
        failureHook.throwIfScheduled()
        if (alias.aliasId !in aliases) return false
        aliases[alias.aliasId] = alias.copy(updatedAt = clock.now())
        refreshFlows()
        return true
    }

    override suspend fun findById(aliasId: AliasId): SupplierProductAlias? {
        failureHook.throwIfScheduled()
        return aliases[aliasId]
    }

    override suspend fun findByNormalizedAlias(
        businessId: BusinessId,
        alias: String,
    ): List<SupplierProductAlias> {
        failureHook.throwIfScheduled()
        val normalized = alias.trim().lowercase(Locale.ROOT)
        return aliases.values.filter {
            it.businessId == businessId && it.alias.trim().lowercase(Locale.ROOT) == normalized
        }
    }

    override fun observeForProduct(productId: ProductId): Flow<List<SupplierProductAlias>> =
        aliasFlows.getOrPut(productId) { MutableStateFlow(sortedFor(productId)) }

    override suspend fun listForProduct(productId: ProductId): List<SupplierProductAlias> {
        failureHook.throwIfScheduled()
        return sortedFor(productId)
    }

    override suspend fun deleteById(aliasId: AliasId): Boolean {
        failureHook.throwIfScheduled()
        val removed = aliases.remove(aliasId) != null
        if (removed) refreshFlows()
        return removed
    }

    /** Simula la cascada de Room al borrar el negocio; la invoca FakeBusinessRepository. */
    suspend fun cascadeDeleteForBusiness(businessId: BusinessId) {
        aliases.values.removeAll { it.businessId == businessId }
        refreshFlows()
    }

    private fun sortedFor(productId: ProductId): List<SupplierProductAlias> =
        aliases.values.filter { it.productId == productId }.sortedBy { it.alias }

    private fun refreshFlows() {
        aliasFlows.forEach { (productId, flow) -> flow.value = sortedFor(productId) }
    }
}

class FakeInvoiceDraftRepository(
    private val clock: AppClock,
) : InvoiceDraftRepository {
    private val failureHook = StorageFailureHook()
    private val lock = Mutex()
    private val drafts = mutableMapOf<DraftId, InvoiceDraft>()
    private val images = mutableMapOf<ImageId, InvoiceImage>()
    private val capturedPageReceipts = mutableMapOf<ImageId, CapturedPageReceipt>()
    private val lines = mutableMapOf<LineId, InvoiceLine>()
    private val publishedOcrSnapshotDraftIds = mutableSetOf<DraftId>()
    private val draftFlows = mutableMapOf<DraftId, MutableStateFlow<InvoiceDraft?>>()
    private val draftListFlows =
        mutableMapOf<Pair<BusinessId, DraftStatus?>, MutableStateFlow<List<InvoiceDraft>>>()
    private val imageFlows = mutableMapOf<DraftId, MutableStateFlow<List<InvoiceImage>>>()
    private val lineFlows = mutableMapOf<DraftId, MutableStateFlow<List<InvoiceLine>>>()
    private val recordedResetOcrCalls = mutableListOf<Pair<DraftId, OcrRunId?>>()

    var beforeResetInterruptedOcr: suspend (DraftId, OcrRunId?) -> Unit = { _, _ -> }
    val resetOcrCalls: List<Pair<DraftId, OcrRunId?>>
        get() = recordedResetOcrCalls.toList()

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    var observeImagesFailure: Throwable? = null

    override suspend fun createDraft(draft: InvoiceDraft): InvoiceDraft {
        failureHook.throwIfScheduled()
        return lock.withLock {
            requirePrimaryKeyFree(draft.draftId !in drafts, "draftId duplicado")
            val now = clock.now()
            val stamped = draft.copy(createdAt = now, updatedAt = now)
            drafts[stamped.draftId] = stamped
            refreshFlows()
            stamped
        }
    }

    override suspend fun updateDraft(draft: InvoiceDraft): Boolean {
        failureHook.throwIfScheduled()
        return lock.withLock {
            if (draft.draftId !in drafts) return@withLock false
            drafts[draft.draftId] = draft.copy(updatedAt = clock.now())
            refreshFlows()
            true
        }
    }

    override suspend fun beginOcrRun(draftId: DraftId, runId: OcrRunId): Boolean {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val current = drafts[draftId] ?: return@withLock false
            if (
                current.status !in setOf(DraftStatus.CAPTURED, DraftStatus.ERROR) ||
                current.activeOcrRunId != null ||
                current.confirmedPurchaseId != null
            ) {
                return@withLock false
            }
            drafts[draftId] = current.copy(
                status = DraftStatus.OCR_PROCESSING,
                activeOcrRunId = runId,
                lastError = null,
                updatedAt = clock.now(),
            )
            refreshFlows()
            true
        }
    }

    override suspend fun finishOcrRun(
        draftId: DraftId,
        runId: OcrRunId,
        newStatus: DraftStatus,
        lastError: String?,
    ): Boolean {
        failureHook.throwIfScheduled()
        require(
            newStatus == DraftStatus.CAPTURED ||
                newStatus == DraftStatus.OCR_READY ||
                newStatus == DraftStatus.ERROR,
        ) { "Estado final OCR inválido: $newStatus" }
        return lock.withLock {
            val current = drafts[draftId] ?: return@withLock false
            if (
                current.status != DraftStatus.OCR_PROCESSING ||
                current.activeOcrRunId != runId ||
                current.confirmedPurchaseId != null
            ) {
                return@withLock false
            }
            drafts[draftId] = current.copy(
                status = newStatus,
                activeOcrRunId = null,
                lastError = lastError,
                updatedAt = clock.now(),
            )
            refreshFlows()
            true
        }
    }

    override suspend fun resetInterruptedOcr(
        draftId: DraftId,
        expectedRunId: OcrRunId?,
    ): Boolean {
        beforeResetInterruptedOcr(draftId, expectedRunId)
        recordedResetOcrCalls += draftId to expectedRunId
        failureHook.throwIfScheduled()
        return lock.withLock {
            val current = drafts[draftId] ?: return@withLock false
            if (
                current.status != DraftStatus.OCR_PROCESSING ||
                current.activeOcrRunId != expectedRunId ||
                current.confirmedPurchaseId != null
            ) {
                return@withLock false
            }
            drafts[draftId] = current.copy(
                status = DraftStatus.CAPTURED,
                activeOcrRunId = null,
                lastError = null,
                updatedAt = clock.now(),
            )
            refreshFlows()
            true
        }
    }

    override suspend fun findDraft(draftId: DraftId): InvoiceDraft? {
        failureHook.throwIfScheduled()
        return drafts[draftId]
    }

    override fun observeDraft(draftId: DraftId): Flow<InvoiceDraft?> =
        draftFlows.getOrPut(draftId) { MutableStateFlow(drafts[draftId]) }

    override fun observeDrafts(
        businessId: BusinessId,
        status: DraftStatus?,
    ): Flow<List<InvoiceDraft>> =
        draftListFlows.getOrPut(businessId to status) {
            MutableStateFlow(sortedDraftsFor(businessId, status))
        }

    override suspend fun deleteDraft(draftId: DraftId): Boolean {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val removed = drafts.remove(draftId) != null
            if (removed) {
                // Misma cascada que Room: las imágenes y líneas caen con el borrador.
                images.values.removeAll { it.draftId == draftId }
                capturedPageReceipts.values.removeAll { it.page.draftId == draftId }
                lines.values.removeAll { it.draftId == draftId }
                refreshFlows()
            }
            removed
        }
    }

    /** Helper de fixture; no forma parte del puerto productivo de borradores. */
    suspend fun seedImage(image: InvoiceImage): InvoiceImage {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val draft = requireDraftExists(image.draftId)
            if (draft.businessId != image.businessId) {
                captureConflict("la imagen fixture no pertenece al negocio del borrador")
            }
            requirePublicationIdFree(image.imageId)
            requirePrimaryKeyFree(image.imageId !in images, "imageId duplicado")
            requirePrimaryKeyFree(
                images.values.none { it.draftId == image.draftId && it.pageIndex == image.pageIndex },
                "la página ${image.pageIndex} ya existe en el borrador",
            )
            // Es una semilla de estado histórico, no una mutación de dominio: conserva estado,
            // timestamps y snapshots del fixture. Las rutas productivas usan publishCapturedPage.
            images[image.imageId] = image
            refreshFlows()
            image
        }
    }

    /** Helper de fixture para reemplazar metadatos sin exponer un bypass en producción. */
    suspend fun replaceSeedImage(image: InvoiceImage): InvoiceImage {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val draft = requireDraftExists(image.draftId)
            if (draft.businessId != image.businessId) {
                captureConflict("la imagen fixture no pertenece al negocio del borrador")
            }
            requirePublicationIdFree(image.imageId)
            images.values.removeAll {
                it.draftId == image.draftId && it.pageIndex == image.pageIndex
            }
            images[image.imageId] = image
            refreshFlows()
            image
        }
    }

    override suspend fun publishCapturedPage(
        page: CapturedPageWrite,
        intent: CapturedPageIntent,
    ): PublishedCapturedPage {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val owningDraft = drafts[page.draftId]
                ?: captureConflict("El borrador no existe")
            if (owningDraft.businessId != page.businessId) {
                captureConflict("El borrador no pertenece al negocio de la captura")
            }
            page.validationFailure()?.let(::captureConflict)
            if (intent is CapturedPageIntent.Replace && intent.targetImageId == page.imageId) {
                captureConflict("Una captura no puede reemplazarse a sí misma")
            }
            capturedPageReceipts[page.imageId]?.let { receipt ->
                if (receipt.page != page || receipt.intent != intent) {
                    captureConflict("imageId ya fue consumido por otra captura o intención")
                }
                val existing = images[page.imageId]
                    ?: captureConflict("La publicación idempotente ya no está activa")
                return@withLock PublishedCapturedPage(existing, receipt.replacedFilePath)
            }
            if (page.imageId in images) {
                captureConflict("imageId legacy ocupado sin recibo de publicación")
            }
            requireMutableImageDraft(page.draftId)
            val currentImages = images.values
                .filter { it.draftId == page.draftId }
                .sortedBy(InvoiceImage::pageIndex)
            val replaced = when (intent) {
                CapturedPageIntent.Append -> null
                is CapturedPageIntent.Replace -> {
                    currentImages.firstOrNull { it.imageId == intent.targetImageId }
                        ?: throw StorageException(StorageError.Unavailable)
                }
            }
            val now = clock.now()
            val stamped = InvoiceImage(
                imageId = page.imageId,
                draftId = page.draftId,
                businessId = page.businessId,
                pageIndex = replaced?.pageIndex ?: currentImages.size,
                filePath = page.filePath,
                sha256 = page.sha256,
                mimeType = page.mimeType,
                widthPx = page.widthPx,
                heightPx = page.heightPx,
                fileSizeBytes = page.fileSizeBytes,
                rotationDegrees = page.rotationDegrees,
                createdAt = now,
            )
            replaced?.let { images.remove(it.imageId) }
            images[stamped.imageId] = stamped
            capturedPageReceipts[stamped.imageId] = CapturedPageReceipt(
                page = page,
                intent = intent,
                replacedFilePath = replaced?.filePath,
            )
            resetAfterImageMutation(stamped.draftId, now)
            refreshFlows()
            PublishedCapturedPage(stamped, replaced?.filePath)
        }
    }

    override suspend fun findPublishedCapturedPage(
        imageId: ImageId,
        draftId: DraftId,
        businessId: BusinessId,
        intent: CapturedPageIntent,
    ): PublishedCapturedPage? {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val receipt = capturedPageReceipts[imageId]
            if (receipt == null) {
                if (imageId in images) {
                    captureConflict("imageId legacy ocupado sin recibo de publicación")
                }
                return@withLock null
            }
            if (
                receipt.page.draftId != draftId ||
                receipt.page.businessId != businessId ||
                receipt.intent != intent
            ) {
                captureConflict("imageId ya fue consumido por otra intención")
            }
            val image = images[imageId]
                ?: captureConflict("La publicación idempotente ya no está activa")
            if (image.draftId != draftId || image.businessId != businessId) {
                captureConflict("La página publicada no pertenece al agregado esperado")
            }
            PublishedCapturedPage(image, receipt.replacedFilePath)
        }
    }

    override suspend fun rotateImage90(imageId: ImageId): InvoiceImage? {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val current = images[imageId] ?: return@withLock null
            requireMutableImageDraft(current.draftId)
            val updated = current.copy(
                rotationDegrees = (current.rotationDegrees + 90) % 360,
                crop = current.crop?.rotated90Cw(),
            )
            images[imageId] = updated
            resetAfterImageMutation(current.draftId, clock.now())
            refreshFlows()
            updated
        }
    }

    override suspend fun setImageCrop(imageId: ImageId, crop: ImageCrop?): InvoiceImage? {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val current = images[imageId] ?: return@withLock null
            requireMutableImageDraft(current.draftId)
            val updated = current.copy(crop = crop)
            images[imageId] = updated
            resetAfterImageMutation(current.draftId, clock.now())
            refreshFlows()
            updated
        }
    }

    override fun observeImages(draftId: DraftId): Flow<List<InvoiceImage>> = flow {
        observeImagesFailure?.let { throw it }
        emitAll(imageFlows.getOrPut(draftId) { MutableStateFlow(sortedImagesFor(draftId)) })
    }

    override suspend fun findImage(imageId: ImageId): InvoiceImage? {
        failureHook.throwIfScheduled()
        return images[imageId]
    }

    override suspend fun isImagePathReferenced(filePath: String): Boolean {
        failureHook.throwIfScheduled()
        return images.values.any { it.filePath == filePath }
    }

    override suspend fun countImagePathReferences(filePath: String): Int {
        failureHook.throwIfScheduled()
        return images.values.count { it.filePath == filePath }
    }

    override suspend fun listImageIdsReferencingPath(filePath: String): Set<ImageId> {
        failureHook.throwIfScheduled()
        return images.values
            .filter { it.filePath == filePath }
            .mapTo(linkedSetOf(), InvoiceImage::imageId)
    }

    override suspend fun moveImageOneStep(
        draftId: DraftId,
        imageId: ImageId,
        moveUp: Boolean,
    ): List<InvoiceImage> {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val current = images.values
                .filter { it.draftId == draftId }
                .sortedBy(InvoiceImage::pageIndex)
            val fromIndex = current.indexOfFirst { it.imageId == imageId }
            if (fromIndex < 0) throw StorageException(StorageError.Unavailable)
            val toIndex = if (moveUp) fromIndex - 1 else fromIndex + 1
            if (toIndex !in current.indices) return@withLock current

            requireMutableImageDraft(draftId)
            val moving = current[fromIndex]
            val neighbor = current[toIndex]
            images[moving.imageId] = moving.copy(pageIndex = neighbor.pageIndex)
            images[neighbor.imageId] = neighbor.copy(pageIndex = moving.pageIndex)
            resetAfterImageMutation(draftId, clock.now())
            refreshFlows()
            sortedImagesFor(draftId)
        }
    }

    override suspend fun deleteImage(imageId: ImageId): DeletedDraftImage? {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val current = images[imageId] ?: return@withLock null
            requireMutableImageDraft(current.draftId)
            images.remove(imageId)
            // Mismo reindexado que Room: las páginas posteriores bajan una posición.
            images.values
                .filter { it.draftId == current.draftId && it.pageIndex > current.pageIndex }
                .sortedBy { it.pageIndex }
                .forEach { images[it.imageId] = it.copy(pageIndex = it.pageIndex - 1) }
            val draftIsEmpty = images.values.none { it.draftId == current.draftId }
            val now = clock.now()
            resetAfterImageMutation(current.draftId, now)
            refreshFlows()
            DeletedDraftImage(image = current, draftIsEmpty = draftIsEmpty)
        }
    }

    override suspend fun addLine(line: InvoiceLine): InvoiceLine {
        failureHook.throwIfScheduled()
        return lock.withLock {
            requireDraftExists(line.draftId)
            requirePrimaryKeyFree(line.lineId !in lines, "lineId duplicado")
            requirePrimaryKeyFree(
                lines.values.none { it.draftId == line.draftId && it.position == line.position },
                "la posición ${line.position} ya existe en el borrador",
            )
            val now = clock.now()
            val stamped = line.copy(createdAt = now, updatedAt = now)
            lines[stamped.lineId] = stamped
            touchDraft(stamped.draftId, now)
            refreshFlows()
            stamped
        }
    }

    override suspend fun updateLine(line: InvoiceLine): Boolean {
        failureHook.throwIfScheduled()
        return lock.withLock {
            if (line.lineId !in lines) return@withLock false
            val now = clock.now()
            lines[line.lineId] = line.copy(updatedAt = now)
            touchDraft(line.draftId, now)
            refreshFlows()
            true
        }
    }

    override suspend fun replaceLines(draftId: DraftId, lines: List<InvoiceLine>) {
        failureHook.throwIfScheduled()
        lock.withLock {
            requireDraftExists(draftId)
            requirePrimaryKeyFree(
                lines.map { it.lineId }.toSet().size == lines.size,
                "lineId duplicado en el reemplazo de líneas",
            )
            val now = clock.now()
            this.lines.values.removeAll { it.draftId == draftId }
            lines.forEachIndexed { index, line ->
                val stamped = line.copy(draftId = draftId, position = index, updatedAt = now)
                this.lines[stamped.lineId] = stamped
            }
            touchDraft(draftId, now)
            refreshFlows()
        }
    }

    override suspend fun reorderLines(draftId: DraftId, orderedLineIds: List<LineId>) {
        failureHook.throwIfScheduled()
        lock.withLock {
            val current = lines.values.filter { it.draftId == draftId }
            val byId = current.associateBy { it.lineId }
            require(orderedLineIds.size == byId.size && orderedLineIds.all { it in byId }) {
                "orderedLineIds debe contener exactamente las líneas actuales del borrador"
            }
            val now = clock.now()
            orderedLineIds.forEachIndexed { index, lineId ->
                lines[lineId] = byId.getValue(lineId).copy(position = index, updatedAt = now)
            }
            touchDraft(draftId, now)
            refreshFlows()
        }
    }

    override fun observeLines(draftId: DraftId): Flow<List<InvoiceLine>> =
        lineFlows.getOrPut(draftId) { MutableStateFlow(sortedLinesFor(draftId)) }

    override suspend fun deleteLine(lineId: LineId): Boolean {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val current = lines[lineId] ?: return@withLock false
            lines.remove(lineId)
            touchDraft(current.draftId, clock.now())
            refreshFlows()
            true
        }
    }

    override suspend fun listAllDraftIds(): Set<DraftId> {
        failureHook.throwIfScheduled()
        return lock.withLock { drafts.keys.toSet() }
    }

    override suspend fun listOpenDraftImages(): List<InvoiceImage> {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val openDraftIds = drafts.values
                .filter { it.confirmedPurchaseId == null }
                .mapTo(mutableSetOf()) { it.draftId }
            images.values
                .filter { it.draftId in openDraftIds }
                .sortedWith(
                    compareBy<InvoiceImage> { it.businessId.value }
                        .thenBy { it.draftId.value }
                        .thenBy(InvoiceImage::pageIndex)
                        .thenBy { it.imageId.value },
                )
        }
    }

    override suspend fun listImagesWithPublishedOcr(): List<InvoiceImage> {
        failureHook.throwIfScheduled()
        return lock.withLock {
            val eligibleDraftIds = drafts.values
                .filter {
                    it.confirmedPurchaseId == null &&
                        it.draftId in publishedOcrSnapshotDraftIds &&
                        it.status in setOf(
                            DraftStatus.OCR_READY,
                            DraftStatus.NEEDS_REVIEW,
                            DraftStatus.READY_TO_POST,
                        )
                }
                .mapTo(mutableSetOf()) { it.draftId }
            images.values
                .filter { it.draftId in eligibleDraftIds }
                .sortedWith(
                    compareBy<InvoiceImage> { it.draftId.value }
                        .thenBy(InvoiceImage::pageIndex),
                )
        }
    }

    /** Simula la evidencia durable que Room obtiene por EXISTS(invoice_ocr_snapshots). */
    fun markOcrSnapshotPublished(draftId: DraftId) {
        require(draftId in drafts) { "El borrador debe existir antes de publicar su snapshot" }
        publishedOcrSnapshotDraftIds += draftId
    }

    private fun requireMutableImageDraft(draftId: DraftId): InvoiceDraft {
        val current = drafts.getValue(draftId)
        if (current.confirmedPurchaseId != null || !current.status.acceptsImageMutations) {
            throw StorageException(
                StorageError.ConstraintConflict(
                    "El estado ${current.status} no admite mutaciones de imágenes",
                ),
            )
        }
        return current
    }

    private fun resetAfterImageMutation(draftId: DraftId, now: Instant) {
        val current = requireMutableImageDraft(draftId)
        publishedOcrSnapshotDraftIds -= draftId
        lines.values.removeAll { it.draftId == draftId }
        drafts[draftId] = current.copy(
            status = if (images.values.any { it.draftId == draftId }) {
                DraftStatus.CAPTURED
            } else {
                DraftStatus.CREATED
            },
            supplierId = null,
            supplierRucRaw = null,
            supplierRucNormalized = null,
            supplierLegalNameRaw = null,
            supplierLegalNameNormalized = null,
            documentType = null,
            documentNumberRaw = null,
            documentNumberNormalized = null,
            issueDateRaw = null,
            issueDate = null,
            currency = null,
            subtotal = null,
            tax = null,
            otherCharges = null,
            total = null,
            headerConfidence = null,
            activeOcrRunId = null,
            lastError = null,
            updatedAt = maxOf(current.updatedAt, now),
        )
    }

    override suspend fun listCommittedDraftIds(): Set<DraftId> {
        failureHook.throwIfScheduled()
        return lock.withLock {
            drafts.values
                .filter { it.confirmedPurchaseId != null }
                .mapTo(mutableSetOf()) { it.draftId }
        }
    }

    /** Simula la cascada de Room al borrar el negocio; la invoca FakeBusinessRepository. */
    suspend fun cascadeDeleteForBusiness(businessId: BusinessId) {
        lock.withLock {
            val draftIds = drafts.values
                .filter { it.businessId == businessId }
                .mapTo(mutableSetOf()) { it.draftId }
            drafts.values.removeAll { it.draftId in draftIds }
            publishedOcrSnapshotDraftIds.removeAll(draftIds)
            images.values.removeAll { it.businessId == businessId }
            capturedPageReceipts.values.removeAll { it.page.businessId == businessId }
            lines.values.removeAll { it.businessId == businessId }
            refreshFlows()
        }
    }

    private fun requireDraftExists(draftId: DraftId): InvoiceDraft = drafts[draftId]
        ?: throw StorageException(
            StorageError.ConstraintConflict("el borrador ${draftId.value} no existe"),
        )

    private fun requirePublicationIdFree(imageId: ImageId) {
        if (imageId in capturedPageReceipts) {
            captureConflict("imageId ya fue consumido por una publicación capturada")
        }
    }

    private fun captureConflict(detail: String): Nothing =
        throw StorageException(StorageError.ConstraintConflict(detail))

    private fun touchDraft(draftId: DraftId, now: Instant) {
        drafts[draftId]?.let { drafts[draftId] = it.copy(updatedAt = now) }
    }

    private fun sortedDraftsFor(businessId: BusinessId, status: DraftStatus?): List<InvoiceDraft> =
        drafts.values
            .filter { it.businessId == businessId && (status == null || it.status == status) }
            .sortedByDescending { it.updatedAt }

    private fun sortedImagesFor(draftId: DraftId): List<InvoiceImage> =
        images.values.filter { it.draftId == draftId }.sortedBy { it.pageIndex }

    private fun sortedLinesFor(draftId: DraftId): List<InvoiceLine> =
        lines.values.filter { it.draftId == draftId }.sortedBy { it.position }

    private fun refreshFlows() {
        draftFlows.forEach { (draftId, flow) -> flow.value = drafts[draftId] }
        draftListFlows.forEach { (key, flow) ->
            flow.value = sortedDraftsFor(key.first, key.second)
        }
        imageFlows.forEach { (draftId, flow) -> flow.value = sortedImagesFor(draftId) }
        lineFlows.forEach { (draftId, flow) -> flow.value = sortedLinesFor(draftId) }
    }

    private data class CapturedPageReceipt(
        val page: CapturedPageWrite,
        val intent: CapturedPageIntent,
        val replacedFilePath: String?,
    )
}

class FakeAppConfigurationRepository : AppConfigurationRepository {
    private val failureHook = StorageFailureHook()
    private val configuration = MutableStateFlow(AppConfiguration.defaults())
    private var pendingOnboarding: OnboardingProvisionIds? = null
    private var completedOnboarding: OnboardingProvisionIds? = null

    var nextOnboardingFinalizationFailure: StorageError? = null

    val pendingOnboardingProvision: OnboardingProvisionIds?
        get() = pendingOnboarding

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    var observeFailure: Throwable? = null
    var forceImageDeletionRequestedAt: Instant? = null
        private set
    var forceImageDeletionRequestCount: Int = 0
        private set
    var nextForceImageDeletionFailure: Exception? = null
    var nextForceImageDeletionCancellation: CancellationException? = null

    override fun observe(): Flow<AppConfiguration> = flow {
        observeFailure?.let { throw it }
        emitAll(configuration)
    }

    override suspend fun current(): AppConfiguration {
        failureHook.throwIfScheduled()
        return configuration.value
    }

    override suspend fun reserveOnboardingProvision(
        proposed: OnboardingProvisionIds,
    ): OnboardingReservation {
        failureHook.throwIfScheduled()
        val current = configuration.value
        if (current.onboardingCompleted) {
            return OnboardingReservation.AlreadyCompleted(requireNotNull(current.businessId))
        }
        val selected = pendingOnboarding ?: proposed.also { pendingOnboarding = it }
        return OnboardingReservation.Pending(selected)
    }

    override suspend fun finalizeOnboardingProvision(
        reservation: OnboardingProvisionIds,
        provisionedBusinessId: BusinessId,
        taxRate: TaxRate,
        costPolicy: CostPolicy,
    ) {
        nextOnboardingFinalizationFailure?.let { error ->
            nextOnboardingFinalizationFailure = null
            throw StorageException(error)
        }
        val current = configuration.value
        if (current.onboardingCompleted) {
            if (
                current.businessId != provisionedBusinessId ||
                completedOnboarding != reservation
            ) {
                throw StorageException(
                    StorageError.ConstraintConflict("otra reserva de onboarding ya fue completada"),
                )
            }
            pendingOnboarding = null
            return
        }
        if (pendingOnboarding != reservation) {
            throw StorageException(
                StorageError.ConstraintConflict("la reserva de onboarding cambió"),
            )
        }
        configuration.value = current.copy(
            onboardingCompleted = true,
            businessId = provisionedBusinessId,
            taxRate = taxRate,
            costPolicy = costPolicy,
        )
        completedOnboarding = reservation
        pendingOnboarding = null
    }

    override suspend fun completeOnboarding(
        businessId: BusinessId,
        taxRate: TaxRate,
        costPolicy: CostPolicy,
    ) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(
            onboardingCompleted = true,
            businessId = businessId,
            taxRate = taxRate,
            costPolicy = costPolicy,
        )
        completedOnboarding = null
        pendingOnboarding = null
    }

    override suspend fun updateTaxRate(rate: TaxRate) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(taxRate = rate)
    }

    override suspend fun updateCostPolicy(policy: CostPolicy) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(costPolicy = policy)
    }

    override suspend fun updateImageRetentionPolicy(policy: ImageRetentionPolicy) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(imageRetentionPolicy = policy)
    }

    override suspend fun requestForceImageDeletion(requestedAt: Instant) {
        forceImageDeletionRequestCount += 1
        nextForceImageDeletionCancellation?.let { cancellation ->
            nextForceImageDeletionCancellation = null
            throw cancellation
        }
        nextForceImageDeletionFailure?.let { failure ->
            nextForceImageDeletionFailure = null
            throw failure
        }
        failureHook.throwIfScheduled()
        forceImageDeletionRequestedAt = forceImageDeletionRequestedAt
            ?.let { current -> maxOf(current, requestedAt) }
            ?: requestedAt
    }

    override suspend fun forceImageDeletionRequestedAt(): Instant? {
        failureHook.throwIfScheduled()
        return forceImageDeletionRequestedAt
    }

    override suspend fun clearForceImageDeletionRequest() {
        failureHook.throwIfScheduled()
        forceImageDeletionRequestedAt = null
    }

    override suspend fun updateBackupEnabled(enabled: Boolean) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(backupEnabled = enabled)
    }

    override suspend fun updateDocumentBackupEnabled(enabled: Boolean) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(documentBackupEnabled = enabled)
    }

    override suspend fun updateDiagnosticsEnabled(enabled: Boolean) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(diagnosticsEnabled = enabled)
    }

    override suspend fun updateBiometricLockEnabled(enabled: Boolean) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(biometricLockEnabled = enabled)
    }

    override suspend fun enterDemoMode(demoBusinessId: BusinessId) {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(demoBusinessId = demoBusinessId)
    }

    override suspend fun exitDemoMode() {
        failureHook.throwIfScheduled()
        configuration.value = configuration.value.copy(demoBusinessId = null)
    }
}

/**
 * Fake en memoria del almacén de archivos de borradores: registra los lotes de rutas que se
 * le piden eliminar y admite el mismo hook de fallos de almacenamiento que los demás fakes.
 */
class FakeDraftFileStore : DraftFileStore {
    private val failureHook = StorageFailureHook()
    private val deletedBatches = mutableListOf<List<String>>()
    private val deletedDraftTrees = mutableListOf<DraftId>()
    private val deletedOcrDrafts = mutableListOf<DraftId>()

    /** Hook de fallos: ver [StorageFailureHook]. */
    var nextFailure: StorageError?
        get() = failureHook.nextFailure
        set(value) {
            failureHook.nextFailure = value
        }

    /** Lotes de rutas pasados a [deleteFiles], en orden de llamada. */
    val deletions: List<List<String>>
        get() = deletedBatches.toList()
    val draftTreeDeletions: List<DraftId>
        get() = deletedDraftTrees.toList()
    val ocrVersionDeletions: List<DraftId>
        get() = deletedOcrDrafts.toList()

    var deletionResults: Map<String, PrivateImageDeletionResult> = emptyMap()
    var beforeConditionalDraftTreeDelete: suspend (DraftId) -> Unit = {}
    var conditionalDraftTreePaths: Map<DraftId, List<String>> = emptyMap()

    override suspend fun deleteFiles(
        relativePaths: List<String>,
    ): List<PrivateImageDeletionResult> {
        failureHook.throwIfScheduled()
        deletedBatches += relativePaths.toList()
        return relativePaths.map { relativePath ->
            deletionResults[relativePath] ?: PrivateImageDeletionResult.DELETED
        }
    }

    override suspend fun deleteDraftTree(draftId: DraftId) {
        failureHook.throwIfScheduled()
        deletedDraftTrees += draftId
    }

    override suspend fun deleteDraftTreeIf(
        draftId: DraftId,
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
        shouldDelete: suspend () -> Boolean,
    ): Boolean {
        failureHook.throwIfScheduled()
        beforeConditionalDraftTreeDelete(draftId)
        if (!shouldDelete()) return false
        if (conditionalDraftTreePaths[draftId].orEmpty().any { pathIsReferencedAnywhere(it) }) {
            return false
        }
        deletedDraftTrees += draftId
        return true
    }

    override suspend fun deleteOcrVersions(draftId: DraftId) {
        failureHook.throwIfScheduled()
        deletedOcrDrafts += draftId
    }
}

/**
 * Fake programable del importador de imágenes: registra cada llamada y devuelve el resultado
 * fijado en [nextResult] o lanza la excepción programada en [nextException] (típicamente una
 * `FileException`), sin tocar disco. Cubre ambas entradas del puerto: galería ([import]) y
 * captura de cámara ([importBytes]).
 */
class FakeDraftImageImporter : DraftImageImporter {

    /** Barrera opcional para reproducir acciones UI mientras la publicacion sigue en vuelo. */
    var beforeImport: suspend () -> Unit = {}

    /** Llamada registrada a la entrada de galería, en orden de llegada. */
    data class ImportCall(
        val draftId: DraftId,
        val imageId: ImageId,
        val sourceUri: String,
    )

    /** Llamada registrada a la entrada de cámara (bytes JPEG), en orden de llegada. */
    class ImportBytesCall(
        val draftId: DraftId,
        val imageId: ImageId,
        val jpegBytes: ByteArray,
        val rotationDegrees: Int,
    )

    /** Resultado que devolverá la próxima importación si no hay excepción programada. */
    var nextResult: ImportedImageFile = ImportedImageFile(
        relativePath = "draft_images/placeholder.jpg",
        sha256 = "b".repeat(64),
        mimeType = "image/jpeg",
        widthPx = 3_000,
        heightPx = 4_000,
        fileSizeBytes = 2_000L,
    )

    /** Excepción que lanzará la próxima importación (se consume al lanzarse). */
    var nextException: Exception? = null

    private val importCalls = mutableListOf<ImportCall>()
    private val importBytesCalls = mutableListOf<ImportBytesCall>()

    /** Llamadas recibidas por [import], en orden. */
    val calls: List<ImportCall>
        get() = importCalls.toList()

    /** Llamadas recibidas por [importBytes], en orden. */
    val bytesCalls: List<ImportBytesCall>
        get() = importBytesCalls.toList()

    override suspend fun import(
        draftId: DraftId,
        imageId: ImageId,
        sourceUri: String,
    ): ImportedImageFile {
        importCalls += ImportCall(draftId = draftId, imageId = imageId, sourceUri = sourceUri)
        beforeImport()
        return produceResult(draftId, imageId)
    }

    override suspend fun importBytes(
        draftId: DraftId,
        imageId: ImageId,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
    ): ImportedImageFile {
        importBytesCalls += ImportBytesCall(
            draftId = draftId,
            imageId = imageId,
            jpegBytes = jpegBytes,
            rotationDegrees = rotationDegrees,
        )
        beforeImport()
        return produceResult(draftId, imageId)
    }

    private fun produceResult(draftId: DraftId, imageId: ImageId): ImportedImageFile {
        nextException?.let { scheduled ->
            nextException = null
            throw scheduled
        }
        return if (nextResult.relativePath == DEFAULT_RELATIVE_PATH) {
            nextResult.copy(relativePath = "draft_images/${draftId.value}/${imageId.value}.jpg")
        } else {
            nextResult
        }
    }

    private companion object {
        const val DEFAULT_RELATIVE_PATH = "draft_images/placeholder.jpg"
    }
}

/** Analizador programable; por defecto considera aceptable cualquier página. */
class FakeImageQualityAnalyzer : ImageQualityAnalyzer {
    var nextException: Exception? = null
    var beforeAnalyze: suspend (InvoiceImage) -> Unit = {}
    var reportForImage: (InvoiceImage) -> ImageQualityReport = { image ->
        ImageQualityReport(
            imageId = image.imageId,
            effectiveWidthPx = image.widthPx,
            effectiveHeightPx = image.heightPx,
            meanLuminance = 128,
            darkPixelsPermille = 0,
            brightPixelsPermille = 0,
            sharpnessScore = 1_000,
            estimatedSkewTenths = 0,
            borderContentPermille = 0,
            warnings = emptyList(),
        )
    }

    private val recordedAnalyses = mutableListOf<ImageId>()
    val analyses: List<ImageId>
        get() = recordedAnalyses.toList()

    override suspend fun analyze(image: InvoiceImage): ImageQualityReport {
        beforeAnalyze(image)
        nextException?.let { scheduled ->
            nextException = null
            throw scheduled
        }
        recordedAnalyses += image.imageId
        return reportForImage(image)
    }
}

/** Preprocesador programable que registra copias OCR y permite suspender una fase en tests. */
class FakeInvoiceImagePreprocessor : InvoiceImagePreprocessor {
    data class PreprocessCall(val imageId: ImageId, val draftId: DraftId)

    var nextException: Exception? = null
    var beforePreprocess: suspend (InvoiceImage) -> Unit = {}
    var transformPreparedBatch: (List<OcrImageFile>) -> List<OcrImageFile> = { pages -> pages }

    private val recordedPreprocessCalls = mutableListOf<PreprocessCall>()
    private val recordedClears = mutableListOf<DraftId>()
    private val publishedByDraft = mutableMapOf<DraftId, List<OcrImageFile>>()

    val preprocessCalls: List<PreprocessCall>
        get() = recordedPreprocessCalls.toList()
    val clears: List<DraftId>
        get() = recordedClears.toList()

    override suspend fun preprocess(
        draftId: DraftId,
        images: List<InvoiceImage>,
    ): List<OcrImageFile> {
        val prepared = transformPreparedBatch(
            images.map { image ->
                beforePreprocess(image)
                nextException?.let { scheduled ->
                    nextException = null
                    throw scheduled
                }
                recordedPreprocessCalls += PreprocessCall(image.imageId, image.draftId)
                OcrImageFile(
                    sourceImageId = image.imageId,
                    relativePath = "draft_images/${image.draftId.value}/ocr/runs/fake/${image.imageId.value}.jpg",
                    mimeType = "image/jpeg",
                    widthPx = image.widthPx,
                    heightPx = image.heightPx,
                    fileSizeBytes = 1_000L,
                )
            },
        )
        publishedByDraft[draftId] = prepared
        return prepared
    }

    override suspend fun findPrepared(draftId: DraftId): List<OcrImageFile> =
        publishedByDraft[draftId].orEmpty()

    fun seedPrepared(draftId: DraftId, pages: List<OcrImageFile>) {
        publishedByDraft[draftId] = pages
    }

    override suspend fun clearOcrVersions(draftId: DraftId) {
        recordedClears += draftId
        publishedByDraft.remove(draftId)
    }
}

private fun requirePrimaryKeyFree(isFree: Boolean, detail: String) {
    if (!isFree) {
        throw StorageException(StorageError.ConstraintConflict(detail))
    }
}
