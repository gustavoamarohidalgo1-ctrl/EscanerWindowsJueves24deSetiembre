package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireDecimalText
import com.facturastock.app.data.local.requireMinorUnits
import com.facturastock.app.data.local.requireMinorUnitsOrNull
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.calculateSaleLineGross
import com.facturastock.app.domain.model.calculateSaleLineTotal

/** Línea de carrito mutable solo mientras su venta es DRAFT; luego queda congelada. */
@Entity(
    tableName = "sale_lines",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["saleId"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["productId"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["unitId"],
            childColumns = ["unitId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = InventoryLocationEntity::class,
            parentColumns = ["locationId"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["saleId", "position"], unique = true),
        Index(value = ["saleId", "productId", "locationId"], unique = true),
        Index(value = ["productId"]),
        Index(value = ["unitId"]),
        Index(value = ["locationId"]),
    ],
)
data class SaleLineEntity(
    @PrimaryKey val saleLineId: String,
    val saleId: String,
    val productId: String,
    val unitId: String,
    val locationId: String,
    val position: Int,
    val productNameSnapshot: String,
    val unitCodeSnapshot: String,
    val locationNameSnapshot: String,
    val barcodeSnapshot: String? = null,
    val quantity: String,
    /** Precio comercial explícito en unidades menores; jamás contiene costo promedio. */
    val unitPriceMinorUnits: Long?,
    val discountMinorUnits: Long,
    val taxMinorUnits: Long,
    val lineTotalMinorUnits: Long?,
    val currencyCode: String,
) {
    init {
        requireCanonicalUuid(saleLineId, "saleLineId")
        requireCanonicalUuid(saleId, "saleId")
        requireCanonicalUuid(productId, "productId")
        requireCanonicalUuid(unitId, "unitId")
        requireCanonicalUuid(locationId, "locationId")
        require(position >= 0) { "position no puede ser negativa" }
        requireText(productNameSnapshot, "productNameSnapshot", 200)
        requireText(unitCodeSnapshot, "unitCodeSnapshot", 16)
        requireText(locationNameSnapshot, "locationNameSnapshot", 100)
        requireTextOrNull(barcodeSnapshot, "barcodeSnapshot", 128)
        requireDecimalText(quantity, "quantity", allowZero = false)
        requireMinorUnitsOrNull(unitPriceMinorUnits, "unitPriceMinorUnits")
        requireMinorUnits(discountMinorUnits, "discountMinorUnits")
        requireMinorUnits(taxMinorUnits, "taxMinorUnits")
        requireMinorUnitsOrNull(lineTotalMinorUnits, "lineTotalMinorUnits")
        val currency = CurrencyCode.of(requireCurrencyCode(currencyCode, "currencyCode"))
        val exactQuantity = Quantity.of(quantity)
        val discount = Money.ofMinor(discountMinorUnits, currency)
        val tax = Money.ofMinor(taxMinorUnits, currency)
        if (unitPriceMinorUnits == null) {
            require(lineTotalMinorUnits == null && discountMinorUnits == 0L && taxMinorUnits == 0L) {
                "Una línea sin precio no puede tener total, descuento ni impuesto"
            }
        } else {
            require(unitPriceMinorUnits > 0L) { "La venta gratuita no está soportada en v1" }
            val price = Money.ofMinor(unitPriceMinorUnits, currency)
            require(discountMinorUnits <= calculateSaleLineGross(price, exactQuantity).minorUnits) {
                "El descuento no puede superar el bruto"
            }
            require(
                lineTotalMinorUnits == calculateSaleLineTotal(price, exactQuantity, discount, tax).minorUnits,
            ) { "lineTotalMinorUnits no cuadra" }
        }
    }
}
