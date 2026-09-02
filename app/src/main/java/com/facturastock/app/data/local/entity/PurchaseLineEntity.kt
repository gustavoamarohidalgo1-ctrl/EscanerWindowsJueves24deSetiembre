package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireConfidenceOrNull
import com.facturastock.app.data.local.requireCostingDecimalTextOrNull
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireDecimalText
import com.facturastock.app.data.local.requireDecimalTextOrNull
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireText
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.InventoryCostingWarning
import com.facturastock.app.domain.model.InventoryTaxEvidenceType
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.PurchaseProductProvenance
import java.math.RoundingMode

/**
 * Línea exacta y trazable de una compra; [rawText] conserva el origen revisado.
 *
 * [readUnitCost] mantiene literalmente el costo unitario revisado en la unidad del comprobante.
 * [appliedUnitCost] es el costo que realmente se aplicó a una unidad de inventario después de
 * conversión, descuento, tratamiento tributario y política. Las columnas de [costingAuditFields]
 * son todas nulas únicamente para historia creada antes del esquema v13; una publicación nueva
 * debe completarlas y el coordinador transaccional rechaza cualquier decisión parcial.
 */
@Entity(
    tableName = "purchase_lines",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["purchaseId"],
            childColumns = ["purchaseId"],
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
    ],
    indices = [
        Index(value = ["purchaseId", "position"], unique = true),
        Index(value = ["productId"]),
        Index(value = ["unitId"]),
    ],
)
data class PurchaseLineEntity(
    @PrimaryKey val purchaseLineId: String,
    val purchaseId: String,
    val productId: String,
    val unitId: String,
    /** Nombre del producto congelado al publicar; no cambia si luego se edita el catálogo. */
    val productNameSnapshot: String? = null,
    /** Código de unidad congelado al publicar; no cambia si luego se edita el catálogo. */
    val unitCodeSnapshot: String? = null,
    val position: Int,
    val rawText: String,
    val description: String,
    val quantity: String,
    @ColumnInfo(name = "unitCost") val readUnitCost: String,
    val currencyCode: String,
    val taxMinorUnits: Long,
    val totalMinorUnits: Long,
    val confidence: Int? = null,
    val appliedUnitCost: String? = null,
    val purchaseUnitFactor: String? = null,
    val inventoryQuantity: String? = null,
    val discount: String? = null,
    val taxTreatment: String? = null,
    val costPolicy: String? = null,
    val appliedCostTotal: String? = null,
    val taxEvidenceType: String? = null,
    val taxEvidenceValue: String? = null,
    val roundingScale: Int? = null,
    val roundingMode: String? = null,
    /** Códigos ordenados separados por coma; cadena vacía significa cálculo sin advertencias. */
    val costingWarnings: String? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN_LEGACY'")
    val productProvenance: String = PurchaseProductProvenance.UNKNOWN_LEGACY.name,
) {
    private val requiredCostingAuditFields: List<Any?>
        get() = listOf(
            appliedUnitCost,
            purchaseUnitFactor,
            inventoryQuantity,
            discount,
            taxTreatment,
            costPolicy,
            appliedCostTotal,
            taxEvidenceType,
            roundingScale,
            roundingMode,
            costingWarnings,
        )

    init {
        requireCanonicalUuid(purchaseLineId, "purchaseLineId")
        requireCanonicalUuid(purchaseId, "purchaseId")
        requireCanonicalUuid(productId, "productId")
        requireCanonicalUuid(unitId, "unitId")
        productNameSnapshot?.let { requireText(it, "productNameSnapshot", 200) }
        unitCodeSnapshot?.let { requireText(it, "unitCodeSnapshot", 16) }
        require(position >= 0) { "position no puede ser negativa: $position" }
        requireText(rawText, "rawText", 64_000)
        requireText(description, "description", 64_000)
        requireDecimalText(quantity, "quantity", allowZero = false)
        requireDecimalText(readUnitCost, "readUnitCost", allowZero = true)
        requireCurrencyCode(currencyCode, "currencyCode")
        requireConfidenceOrNull(confidence, "confidence")
        requireCostingDecimalTextOrNull(appliedUnitCost, "appliedUnitCost", allowZero = true)
        requireDecimalTextOrNull(purchaseUnitFactor, "purchaseUnitFactor", allowZero = false)
        requireCostingDecimalTextOrNull(inventoryQuantity, "inventoryQuantity", allowZero = false)
        requireDecimalTextOrNull(discount, "discount", allowZero = true)
        requireCostingDecimalTextOrNull(appliedCostTotal, "appliedCostTotal", allowZero = true)
        taxTreatment?.let { treatment ->
            requireEnumName<InventoryTaxTreatment>(treatment, "taxTreatment")
        }
        requireEnumName<PurchaseProductProvenance>(productProvenance, "productProvenance")
        costPolicy?.let { requireEnumName<CostPolicy>(it, "costPolicy") }
        val hasCostingAudit = requiredCostingAuditFields.none { it == null }
        require(requiredCostingAuditFields.all { it == null } || hasCostingAudit) {
            "Los campos de auditoría de costo deben estar todos presentes o todos ausentes"
        }
        if (!hasCostingAudit) {
            require(taxEvidenceValue == null) {
                "taxEvidenceValue no puede existir sin auditoría de costo"
            }
        } else {
            requireEnumName<InventoryTaxEvidenceType>(
                requireNotNull(taxEvidenceType),
                "taxEvidenceType",
            )
            requireDecimalTextOrNull(
                taxEvidenceValue,
                "taxEvidenceValue",
                allowZero = true,
            )
            when (InventoryTaxEvidenceType.valueOf(requireNotNull(taxEvidenceType))) {
                InventoryTaxEvidenceType.NONE -> require(taxEvidenceValue == null) {
                    "La evidencia NONE no admite valor"
                }
                InventoryTaxEvidenceType.EXPLICIT_AMOUNT,
                InventoryTaxEvidenceType.EXPLICIT_RATE,
                -> require(taxEvidenceValue != null) {
                    "$taxEvidenceType exige valor explícito"
                }
            }
            require(roundingScale in 0..18) { "roundingScale debe estar entre 0 y 18" }
            require(roundingMode in RoundingMode.entries.map(RoundingMode::name)) {
                "roundingMode desconocido: $roundingMode"
            }
            require(requireNotNull(costingWarnings).length <= 4_096) {
                "costingWarnings excede 4096 caracteres"
            }
            val warningCodes = costingWarnings
                .takeUnless(String::isEmpty)
                ?.split(',')
                .orEmpty()
            require(warningCodes.distinct() == warningCodes && warningCodes == warningCodes.sorted()) {
                "costingWarnings debe estar ordenado y no repetir códigos"
            }
            warningCodes.forEach { code ->
                requireEnumName<InventoryCostingWarning>(code, "costingWarning")
            }
        }
    }
}
