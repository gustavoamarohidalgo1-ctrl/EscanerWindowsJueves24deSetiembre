package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.repository.BusinessRepository

/**
 * Actualiza el perfil del negocio desde Ajustes. El RUC, si está presente, debe estar bien
 * formado; la app nunca modifica el valor ingresado. Devuelve false si el negocio no existe.
 */
class UpdateBusinessProfileUseCase(
    private val businessRepository: BusinessRepository,
) {
    suspend operator fun invoke(business: Business): Boolean {
        val ruc = business.ruc
        if (ruc != null && !RucValidator.isWellFormed(ruc)) {
            throw DomainRuleViolation(ValidationError.InvalidRuc(ruc))
        }
        return businessRepository.update(business)
    }
}
