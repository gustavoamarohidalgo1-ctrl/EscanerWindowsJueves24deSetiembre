package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitCost
import java.math.BigDecimal

/** Alta local de producto y existencias iniciales, confirmadas juntas o revertidas por completo. */
interface ProductRegistrationRepository {
    /**
     * [quantity] usa la unidad de inventario del producto; [unitCost] es el costo por esa unidad.
     * Repetir un productId ya registrado devuelve Stale y nunca vuelve a sumar sus existencias.
     * Un negocio enlazado a inventario cloud devuelve Invalid(OWNERSHIP).
     */
    suspend fun register(
        product: Product,
        quantity: BigDecimal,
        unitCost: UnitCost,
    ): CatalogMutationResult<Product>
}
