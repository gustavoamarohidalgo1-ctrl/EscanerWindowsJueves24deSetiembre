package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.DisabledPurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import com.facturastock.app.domain.repository.enqueuePrivacyPurgeBestEffort

/**
 * Sale del modo demostración: quita la referencia al negocio demo de la configuración y luego
 * elimina el negocio demo (la cascada de Room limpia su catálogo y borradores). Si el modo
 * demo no está activo no hace nada.
 */
class ExitDemoModeUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val businessRepository: BusinessRepository,
    private val purchaseBackupScheduler: PurchaseBackupScheduler =
        DisabledPurchaseBackupScheduler,
) {
    suspend operator fun invoke() {
        val demoBusinessId = appConfigurationRepository.current().demoBusinessId ?: return
        appConfigurationRepository.exitDemoMode()
        try {
            businessRepository.deleteById(demoBusinessId)
        } finally {
            // DataStore ya volvió al negocio principal. Despierta ambos canales porque una
            // purga durable de ese tenant pudo quedar bloqueada mientras demo era el activo.
            purchaseBackupScheduler.enqueueBestEffort()
            purchaseBackupScheduler.enqueuePrivacyPurgeBestEffort()
        }
    }
}
