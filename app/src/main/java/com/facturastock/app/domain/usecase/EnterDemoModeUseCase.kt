package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.UnitRepository
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Activa el modo demostración sembrando un negocio 100 % sintético: todo registro lleva el
 * prefijo "[DEMO] " y el negocio no tiene RUC (los `NULL` no colisionan, así que reentrar al
 * demo nunca choca con datos previos). Los ids del grafo se derivan del id del negocio y una
 * clave semántica estable: el contenido de un negocio demo es reproducible sin hacer globales
 * sus claves primarias. Al final fija el negocio demo en la configuración y devuelve su id.
 * Todo se escribe a través de los puertos del dominio.
 */
class EnterDemoModeUseCase(
    private val businessRepository: BusinessRepository,
    private val supplierRepository: SupplierRepository,
    private val unitRepository: UnitRepository,
    private val inventoryLocationRepository: InventoryLocationRepository,
    private val productRepository: ProductRepository,
    private val appConfigurationRepository: AppConfigurationRepository,
    private val uuidGenerator: UuidGenerator,
    private val appClock: AppClock,
) {
    suspend operator fun invoke(): BusinessId {
        val now = appClock.now()
        val demoId = BusinessId.from(uuidGenerator.newUuid())

        businessRepository.create(
            Business(
                businessId = demoId,
                legalName = DemoPurchaseScenario.BUSINESS_LEGAL_NAME,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val units = listOf(
            Triple("NIU", "Unidad", "und"),
            Triple("KGM", "Kilogramo", "kg"),
            Triple("LTR", "Litro", "L"),
        ).associate { (code, name, symbol) ->
            val unit = unitRepository.create(
                UnitOfMeasure(
                    unitId = UnitId.from(demoUuid(demoId, "unit:$code")),
                    businessId = demoId,
                    code = code,
                    name = name,
                    symbol = symbol,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            code to unit.unitId
        }

        val location = inventoryLocationRepository.create(
            InventoryLocation(
                locationId = LocationId.from(demoUuid(demoId, "location:main")),
                businessId = demoId,
                name = "[DEMO] Almacén principal",
                createdAt = now,
                updatedAt = now,
            ),
        )

        listOf(
            Triple(
                "supplier:primary",
                DemoPurchaseScenario.PRIMARY_SUPPLIER_LEGAL_NAME,
                DemoPurchaseScenario.PRIMARY_SUPPLIER_RUC,
            ),
            Triple("supplier:south", "[DEMO] Mayorista Sur", "20222222222"),
            Triple("supplier:andean", "[DEMO] Importadora Andina", "20333333333"),
        ).forEach { (key, legalName, ruc) ->
            supplierRepository.create(
                Supplier(
                    supplierId = SupplierId.from(demoUuid(demoId, key)),
                    businessId = demoId,
                    legalName = legalName,
                    ruc = ruc,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        DemoPurchaseScenario.EXISTING_PRODUCTS.forEach { product ->
            productRepository.create(
                Product(
                    productId = ProductId.from(demoUuid(demoId, product.semanticKey)),
                    businessId = demoId,
                    unitId = units.getValue(product.unitCode),
                    name = product.name,
                    locationId = location.locationId,
                    sku = product.sku,
                    barcode = product.barcode,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        appConfigurationRepository.enterDemoMode(demoId)
        return demoId
    }

    private fun demoUuid(demoId: BusinessId, semanticKey: String): UUID =
        UUID.nameUUIDFromBytes(
            "$DEMO_ID_NAMESPACE:${demoId.value}:$semanticKey"
                .toByteArray(StandardCharsets.UTF_8),
        )

    private companion object {
        const val DEMO_ID_NAMESPACE = "facturastock.demo.catalog.v1"
    }
}
