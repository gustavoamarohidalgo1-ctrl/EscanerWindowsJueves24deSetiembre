package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CatalogInvalidField
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductCatalogDetail
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.UnitRepository

class SaveSupplierCatalogUseCase(private val repository: SupplierRepository) {
    suspend operator fun invoke(supplier: Supplier): CatalogMutationResult<Supplier> {
        val canonicalRuc = CatalogCanonicalizer.ruc(supplier.ruc)
        if (canonicalRuc != null && !RucValidator.isWellFormed(canonicalRuc)) {
            throw DomainRuleViolation(ValidationError.InvalidRuc(supplier.ruc.orEmpty()))
        }
        val candidate = supplier.copy(
            legalName = supplier.legalName.trim(),
            ruc = canonicalRuc,
            tradeName = supplier.tradeName?.trim()?.takeIf(String::isNotEmpty),
        )
        if (candidate.legalName.isEmpty()) return CatalogMutationResult.Invalid(CatalogInvalidField.NAME)
        val existing = repository.findById(candidate.supplierId)
        if (existing != null && existing.businessId != candidate.businessId) {
            return CatalogMutationResult.Invalid(CatalogInvalidField.OWNERSHIP)
        }
        duplicateSupplier(repository, candidate)?.let { return CatalogMutationResult.Duplicate(it) }
        return saveWithConstraintResolution(
            save = {
                if (existing == null) repository.create(candidate)
                else if (repository.update(candidate)) requireNotNull(repository.findById(candidate.supplierId))
                // El CAS de versión perdió: otra edición se adelantó; nada se escribió.
                else return CatalogMutationResult.Stale
            },
            duplicate = { duplicateSupplier(repository, candidate) },
        )
    }
}

class SaveProductCatalogUseCase(
    private val repository: ProductRepository,
    private val unitRepository: UnitRepository,
    private val inventoryLocationRepository: InventoryLocationRepository,
) {
    suspend operator fun invoke(product: Product): CatalogMutationResult<Product> {
        val candidate = product.copy(
            name = product.name.trim(),
            sku = CatalogCanonicalizer.sku(product.sku),
            barcode = CatalogCanonicalizer.barcode(product.barcode),
        )
        if (candidate.name.isEmpty()) return CatalogMutationResult.Invalid(CatalogInvalidField.NAME)
        val existing = repository.findById(candidate.productId)
        if (existing != null && existing.businessId != candidate.businessId) {
            return CatalogMutationResult.Invalid(CatalogInvalidField.OWNERSHIP)
        }
        val unit = unitRepository.findById(candidate.unitId)
        if (
            unit == null || unit.businessId != candidate.businessId ||
            ((existing == null || existing.unitId != candidate.unitId) && unit.status != CatalogStatus.ACTIVE)
        ) {
            return CatalogMutationResult.Invalid(CatalogInvalidField.UNIT)
        }
        candidate.purchaseUnitId?.let { purchaseUnitId ->
            val purchaseUnit = unitRepository.findById(purchaseUnitId)
            if (
                purchaseUnit == null || purchaseUnit.businessId != candidate.businessId ||
                ((existing == null || existing.purchaseUnitId != purchaseUnitId) &&
                    purchaseUnit.status != CatalogStatus.ACTIVE)
            ) {
                return CatalogMutationResult.Invalid(CatalogInvalidField.PURCHASE_UNIT)
            }
        }
        candidate.locationId?.let { locationId ->
            val location = inventoryLocationRepository.findById(locationId)
            if (
                location == null || location.businessId != candidate.businessId ||
                ((existing == null || existing.locationId != locationId) &&
                    location.status != CatalogStatus.ACTIVE)
            ) {
                return CatalogMutationResult.Invalid(CatalogInvalidField.LOCATION)
            }
        }
        duplicateProduct(repository, candidate)?.let { return CatalogMutationResult.Duplicate(it) }
        return saveWithConstraintResolution(
            save = {
                if (existing == null) repository.create(candidate)
                else if (repository.update(candidate)) requireNotNull(repository.findById(candidate.productId))
                // El CAS de versión perdió: otra edición se adelantó; nada se escribió.
                else return CatalogMutationResult.Stale
            },
            duplicate = { duplicateProduct(repository, candidate) },
        )
    }
}

class SaveUnitCatalogUseCase(private val repository: UnitRepository) {
    suspend operator fun invoke(unit: UnitOfMeasure): CatalogMutationResult<UnitOfMeasure> {
        val candidate = unit.copy(code = CatalogCanonicalizer.unitCode(unit.code))
            .copy(
                name = unit.name.trim(),
                symbol = unit.symbol?.trim()?.takeIf(String::isNotEmpty),
            )
        if (candidate.name.isEmpty()) return CatalogMutationResult.Invalid(CatalogInvalidField.NAME)
        val existing = repository.findById(candidate.unitId)
        if (existing != null && existing.businessId != candidate.businessId) {
            return CatalogMutationResult.Invalid(CatalogInvalidField.OWNERSHIP)
        }
        val duplicate = repository.findByCode(candidate.businessId, candidate.code)
            ?.takeUnless { it.unitId == candidate.unitId }
        if (duplicate != null) return CatalogMutationResult.Duplicate(CatalogDuplicateField.CODE)
        return saveWithConstraintResolution(
            save = {
                if (existing == null) repository.create(candidate)
                else if (repository.update(candidate)) requireNotNull(repository.findById(candidate.unitId))
                else return CatalogMutationResult.NotFound
            },
            duplicate = {
                repository.findByCode(candidate.businessId, candidate.code)
                    ?.takeUnless { it.unitId == candidate.unitId }
                    ?.let { CatalogDuplicateField.CODE }
            },
        )
    }
}

class SaveInventoryLocationCatalogUseCase(
    private val repository: InventoryLocationRepository,
) {
    suspend operator fun invoke(location: InventoryLocation): CatalogMutationResult<InventoryLocation> {
        val candidate = location.copy(name = location.name.trim())
        if (candidate.name.isEmpty()) return CatalogMutationResult.Invalid(CatalogInvalidField.NAME)
        val existing = repository.findById(candidate.locationId)
        if (existing != null && existing.businessId != candidate.businessId) {
            return CatalogMutationResult.Invalid(CatalogInvalidField.OWNERSHIP)
        }
        val duplicate = repository.findByName(candidate.businessId, candidate.name)
            ?.takeUnless { it.locationId == candidate.locationId }
        if (duplicate != null) return CatalogMutationResult.Duplicate(CatalogDuplicateField.NAME)
        return saveWithConstraintResolution(
            save = {
                if (existing == null) repository.create(candidate)
                else if (repository.update(candidate)) requireNotNull(repository.findById(candidate.locationId))
                else return CatalogMutationResult.NotFound
            },
            duplicate = {
                repository.findByName(candidate.businessId, candidate.name)
                    ?.takeUnless { it.locationId == candidate.locationId }
                    ?.let { CatalogDuplicateField.NAME }
            },
        )
    }
}

class ArchiveSupplierCatalogUseCase(private val repository: SupplierRepository) {
    suspend operator fun invoke(id: SupplierId): CatalogMutationResult<Supplier> =
        mutateStatus(repository.findById(id), { repository.archive(id) }) { repository.findById(id) }
}

class RestoreSupplierCatalogUseCase(private val repository: SupplierRepository) {
    suspend operator fun invoke(id: SupplierId): CatalogMutationResult<Supplier> =
        mutateStatus(repository.findById(id), { repository.restore(id) }) { repository.findById(id) }
}

class ArchiveProductCatalogUseCase(private val repository: ProductRepository) {
    suspend operator fun invoke(id: ProductId): CatalogMutationResult<Product> =
        mutateStatus(repository.findById(id), { repository.archive(id) }) { repository.findById(id) }
}

class RestoreProductCatalogUseCase(private val repository: ProductRepository) {
    suspend operator fun invoke(id: ProductId): CatalogMutationResult<Product> =
        mutateStatus(repository.findById(id), { repository.restore(id) }) { repository.findById(id) }
}

class ArchiveUnitCatalogUseCase(private val repository: UnitRepository) {
    suspend operator fun invoke(id: UnitId): CatalogMutationResult<UnitOfMeasure> =
        mutateStatus(repository.findById(id), { repository.archive(id) }) { repository.findById(id) }
}

class RestoreUnitCatalogUseCase(private val repository: UnitRepository) {
    suspend operator fun invoke(id: UnitId): CatalogMutationResult<UnitOfMeasure> =
        mutateStatus(repository.findById(id), { repository.restore(id) }) { repository.findById(id) }
}

class ArchiveInventoryLocationCatalogUseCase(
    private val repository: InventoryLocationRepository,
) {
    suspend operator fun invoke(id: LocationId): CatalogMutationResult<InventoryLocation> =
        mutateStatus(repository.findById(id), { repository.archive(id) }) { repository.findById(id) }
}

class RestoreInventoryLocationCatalogUseCase(
    private val repository: InventoryLocationRepository,
) {
    suspend operator fun invoke(id: LocationId): CatalogMutationResult<InventoryLocation> =
        mutateStatus(repository.findById(id), { repository.restore(id) }) { repository.findById(id) }
}

class LoadProductCatalogDetailUseCase(
    private val productRepository: ProductRepository,
    private val unitRepository: UnitRepository,
    private val supplierProductAliasRepository: SupplierProductAliasRepository,
    private val productInventoryRepository: ProductInventoryRepository,
) {
    suspend operator fun invoke(productId: ProductId): ProductCatalogDetail? {
        val product = productRepository.findById(productId) ?: return null
        val unit = unitRepository.findById(product.unitId) ?: return null
        return ProductCatalogDetail(
            product = product,
            unit = unit,
            aliases = supplierProductAliasRepository.listForProduct(productId)
                .filter { it.businessId == product.businessId },
            inventory = productInventoryRepository.summaryForProduct(product.businessId, productId),
        )
    }
}

private suspend fun duplicateSupplier(
    repository: SupplierRepository,
    candidate: Supplier,
): CatalogDuplicateField? = candidate.ruc?.let { ruc ->
    repository.findByRuc(candidate.businessId, ruc)
        ?.takeUnless { it.supplierId == candidate.supplierId }
        ?.let { CatalogDuplicateField.RUC }
}

private suspend fun duplicateProduct(
    repository: ProductRepository,
    candidate: Product,
): CatalogDuplicateField? {
    candidate.sku?.let { sku ->
        repository.findBySku(candidate.businessId, sku)
            ?.takeUnless { it.productId == candidate.productId }
            ?.let { return CatalogDuplicateField.SKU }
    }
    candidate.barcode?.let { barcode ->
        repository.findByBarcode(candidate.businessId, barcode)
            ?.takeUnless { it.productId == candidate.productId }
            ?.let { return CatalogDuplicateField.BARCODE }
    }
    return null
}

private suspend inline fun <T> saveWithConstraintResolution(
    save: () -> T,
    duplicate: () -> CatalogDuplicateField?,
): CatalogMutationResult<T> = try {
    CatalogMutationResult.Saved(save())
} catch (exception: StorageException) {
    if (exception.error !is StorageError.ConstraintConflict) throw exception
    duplicate()?.let { CatalogMutationResult.Duplicate(it) } ?: throw exception
}

private suspend inline fun <T> mutateStatus(
    existing: T?,
    mutate: () -> Boolean,
    reload: () -> T?,
): CatalogMutationResult<T> {
    if (existing == null || !mutate()) return CatalogMutationResult.NotFound
    return reload()?.let { CatalogMutationResult.Saved(it) } ?: CatalogMutationResult.NotFound
}
