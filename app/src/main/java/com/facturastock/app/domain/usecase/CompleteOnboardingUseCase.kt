package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.OnboardingProvision
import com.facturastock.app.domain.repository.OnboardingProvisionIds
import com.facturastock.app.domain.repository.OnboardingProvisioningRepository
import com.facturastock.app.domain.repository.OnboardingReservation

/**
 * Completa el onboarding de primer uso: crea el negocio (la razón social es el nombre
 * comercial ingresado; el RUC se guarda solo si está bien formado), crea el almacén
 * principal, una unidad base para que el alta manual de productos esté disponible desde el
 * primer uso y marca la configuración como completada. DataStore reserva primero IDs estables;
 * Room crea el grafo en una única transacción y la finalización consume exactamente esa reserva.
 * Por ello cualquier corte entre ambos almacenes se reanuda sin duplicar ni dejar hijos parciales.
 */
class CompleteOnboardingUseCase(
    private val onboardingProvisioningRepository: OnboardingProvisioningRepository,
    private val appConfigurationRepository: AppConfigurationRepository,
    private val uuidGenerator: UuidGenerator,
    private val appClock: AppClock,
) {
    suspend operator fun invoke(
        businessName: String,
        ruc: String?,
        warehouseName: String,
        taxRate: TaxRate,
        costPolicy: CostPolicy,
    ): Business {
        val trimmedRuc = ruc?.trim()?.takeIf(String::isNotEmpty)
        if (trimmedRuc != null && !RucValidator.isWellFormed(trimmedRuc)) {
            throw DomainRuleViolation(ValidationError.InvalidRuc(ruc))
        }
        val reservation = appConfigurationRepository.reserveOnboardingProvision(
            OnboardingProvisionIds(
                businessId = BusinessId.from(uuidGenerator.newUuid()),
                warehouseId = LocationId.from(uuidGenerator.newUuid()),
                defaultUnitId = UnitId.from(uuidGenerator.newUuid()),
            ),
        )
        if (reservation is OnboardingReservation.AlreadyCompleted) {
            return onboardingProvisioningRepository.findBusiness(reservation.businessId)
                ?: throw StorageException(
                    StorageError.ConstraintConflict(
                        "La configuración completada apunta a un negocio inexistente",
                    ),
                )
        }

        val ids = (reservation as OnboardingReservation.Pending).ids
        val now = appClock.now()
        val business = onboardingProvisioningRepository.provision(
            OnboardingProvision(
                business = Business(
                    businessId = ids.businessId,
                    legalName = businessName.trim(),
                    ruc = trimmedRuc,
                    createdAt = now,
                    updatedAt = now,
                ),
                warehouse = InventoryLocation(
                    locationId = ids.warehouseId,
                    businessId = ids.businessId,
                    name = warehouseName.trim(),
                    createdAt = now,
                    updatedAt = now,
                ),
                defaultUnit = UnitOfMeasure(
                    unitId = ids.defaultUnitId,
                    businessId = ids.businessId,
                    code = DEFAULT_UNIT_CODE,
                    name = DEFAULT_UNIT_NAME,
                    symbol = DEFAULT_UNIT_SYMBOL,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        appConfigurationRepository.finalizeOnboardingProvision(
            reservation = ids,
            provisionedBusinessId = business.businessId,
            taxRate = taxRate,
            costPolicy = costPolicy,
        )
        return business
    }

    private companion object {
        const val DEFAULT_UNIT_CODE = "NIU"
        const val DEFAULT_UNIT_NAME = "Unidad"
        const val DEFAULT_UNIT_SYMBOL = "un"
    }
}
