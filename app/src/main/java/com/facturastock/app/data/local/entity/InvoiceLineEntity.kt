package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCanonicalUuidOrNull
import com.facturastock.app.data.local.requireConfidenceOrNull
import com.facturastock.app.data.local.requireCurrencyCodeOrNull
import com.facturastock.app.data.local.requireDecimalTextOrNull
import com.facturastock.app.data.local.requireMinorUnitsOrNull
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.data.local.requireTimestamps

/**
 * Línea OCR de un borrador de factura. [descriptionRaw] conserva el texto original y los
 * valores normalizados son exactos: [quantity] y [unitCost] se guardan como texto decimal
 * plano (`BigDecimal.toPlainString`), de modo que la escala declarada sobrevive al ciclo
 * persistir/leer (máx. 38 dígitos de precisión y 18 decimales, la política del dominio).
 * [lineTotalMinorUnits] es dinero firmado en unidades menores `Long` (las notas de crédito
 * conservan el signo impreso). [unitCostCurrency] es la moneda común de costo, descuento, IGV y
 * total; queda presente aunque una fila tenga total pero no costo resuelto. La línea sobrevive a la eliminación del producto o la
 * unidad: ambas referencias quedan en `NULL` (`SET_NULL`).
 */
@Entity(
    tableName = "invoice_lines",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceDraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["productId"],
            childColumns = ["productId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["unitId"],
            childColumns = ["unitId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["draftId", "position"], unique = true),
        Index(value = ["businessId"]),
        Index(value = ["productId"]),
        Index(value = ["unitId"]),
    ],
)
data class InvoiceLineEntity(
    @PrimaryKey val lineId: String,
    val draftId: String,
    val businessId: String,
    val position: Int,
    val descriptionRaw: String,
    val createdAt: Long,
    val updatedAt: Long,
    val descriptionNormalized: String? = null,
    val codeRaw: String? = null,
    val codeNormalized: String? = null,
    val quantity: String? = null,
    val unitRaw: String? = null,
    val unitCodeNormalized: String? = null,
    val unitCost: String? = null,
    val unitCostCurrency: String? = null,
    val discountMinorUnits: Long? = null,
    val taxMinorUnits: Long? = null,
    val unitId: String? = null,
    val productId: String? = null,
    val lineTotalMinorUnits: Long? = null,
    val ocrConfidence: Int? = null,
    val linkConfidence: Int? = null,
) {
    init {
        requireCanonicalUuid(lineId, "lineId")
        requireCanonicalUuid(draftId, "draftId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuidOrNull(unitId, "unitId")
        requireCanonicalUuidOrNull(productId, "productId")
        require(position >= 0) { "position no puede ser negativa: $position" }
        requireText(descriptionRaw, "descriptionRaw", 64_000)
        requireTextOrNull(descriptionNormalized, "descriptionNormalized", 64_000)
        requireTextOrNull(codeRaw, "codeRaw", 512)
        requireTextOrNull(codeNormalized, "codeNormalized", 512)
        requireDecimalTextOrNull(quantity, "quantity", allowZero = false)
        requireTextOrNull(unitRaw, "unitRaw", 512)
        requireTextOrNull(unitCodeNormalized, "unitCodeNormalized", 512)
        requireDecimalTextOrNull(unitCost, "unitCost", allowZero = true)
        requireCurrencyCodeOrNull(unitCostCurrency, "unitCostCurrency")
        requireMinorUnitsOrNull(discountMinorUnits, "discountMinorUnits")
        requireMinorUnitsOrNull(taxMinorUnits, "taxMinorUnits")
        val hasMoney = unitCost != null || discountMinorUnits != null ||
            taxMinorUnits != null || lineTotalMinorUnits != null
        require(hasMoney == (unitCostCurrency != null)) {
            "La moneda debe existir exactamente cuando la línea tiene importes"
        }
        requireConfidenceOrNull(ocrConfidence, "ocrConfidence")
        requireConfidenceOrNull(linkConfidence, "linkConfidence")
        requireTimestamps(createdAt, updatedAt)
    }
}
