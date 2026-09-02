package com.facturastock.app.domain.model

import com.facturastock.app.domain.config.CostPolicy
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Política técnica para valores derivados que se persisten como texto. Los operandos capturados
 * siguen limitados a 38/18, pero cantidad×factor y cantidad×costo pueden alcanzar escala 36; se
 * admite holgura de precisión para volver a usar saldos resultantes sin truncarlos.
 */
object InventoryCostingDecimalPolicy {
    const val MAX_PERSISTED_PRECISION: Int = 128
    const val MAX_PERSISTED_SCALE: Int = 36

    fun supportsPersisted(value: BigDecimal): Boolean =
        value.precision() <= MAX_PERSISTED_PRECISION &&
            value.scale() in 0..MAX_PERSISTED_SCALE
}

/** Tratamiento tributario confirmado para una línea; UNKNOWN nunca autoriza un cálculo. */
enum class InventoryTaxTreatment {
    INCLUDED,
    EXCLUDED,
    EXEMPT,
    UNKNOWN,
}

/** Nombre estable que se persiste junto con el valor de [InventoryTaxEvidence]. */
enum class InventoryTaxEvidenceType {
    NONE,
    EXPLICIT_AMOUNT,
    EXPLICIT_RATE,
}

/**
 * Evidencia tributaria escogida explícitamente. Una tasa usa porcentaje humano (18 significa
 * 18 %, no 0.18); un importe es el impuesto total de la línea, no un costo unitario.
 */
sealed interface InventoryTaxEvidence {
    val type: InventoryTaxEvidenceType
    val value: BigDecimal?

    data object None : InventoryTaxEvidence {
        override val type: InventoryTaxEvidenceType = InventoryTaxEvidenceType.NONE
        override val value: BigDecimal? = null
    }

    data class ExplicitAmount(val amount: BigDecimal) : InventoryTaxEvidence {
        init {
            require(amount.signum() >= 0 && ExactDecimalPolicy.supportsValue(amount)) {
                "El importe tributario explícito debe ser no negativo y exacto"
            }
        }

        override val type: InventoryTaxEvidenceType = InventoryTaxEvidenceType.EXPLICIT_AMOUNT
        override val value: BigDecimal = amount
    }

    data class ExplicitRate(val percent: BigDecimal) : InventoryTaxEvidence {
        init {
            require(
                percent.signum() >= 0 && percent <= ONE_HUNDRED &&
                    ExactDecimalPolicy.supportsValue(percent),
            ) { "La tasa tributaria explícita debe estar entre 0 y 100" }
        }

        override val type: InventoryTaxEvidenceType = InventoryTaxEvidenceType.EXPLICIT_RATE
        override val value: BigDecimal = percent
    }

    private companion object {
        val ONE_HUNDRED = BigDecimal("100")
    }
}

/** Toda división monetaria exige escala y modo; nunca se usa el redondeo por defecto. */
data class InventoryCostRoundingPolicy(
    val scale: Int,
    val mode: RoundingMode,
) {
    init {
        require(scale in 0..ExactDecimalPolicy.MAX_VALUE_SCALE) {
            "La escala de costos debe estar entre 0 y ${ExactDecimalPolicy.MAX_VALUE_SCALE}"
        }
    }
}

/**
 * Umbrales operativos que solo generan advertencias. No son topes: los valores se conservan y
 * calculan completos para no alterar silenciosamente una compra legítima de gran volumen.
 */
data class InventoryCostingLimits(
    val maxPurchaseQuantity: BigDecimal = BigDecimal("1000000"),
    val maxPurchaseUnitFactor: BigDecimal = BigDecimal("100000"),
    val maxReadPurchaseUnitCost: BigDecimal = BigDecimal("1000000000"),
    val maxLineDiscount: BigDecimal = BigDecimal("1000000000000"),
    val maxTaxTotal: BigDecimal = BigDecimal("1000000000000"),
    val maxInventoryQuantity: BigDecimal = BigDecimal("1000000000000"),
    val maxAppliedCostTotal: BigDecimal = BigDecimal("1000000000000000"),
    val maxAbsolutePreviousQuantity: BigDecimal = BigDecimal("1000000000000000"),
    val maxPreviousAverageUnitCost: BigDecimal = BigDecimal("1000000000000"),
    val maxAbsoluteResultingQuantity: BigDecimal = BigDecimal("1000000000000000"),
) {
    init {
        require(
            listOf(
                maxPurchaseQuantity,
                maxPurchaseUnitFactor,
                maxReadPurchaseUnitCost,
                maxLineDiscount,
                maxTaxTotal,
                maxInventoryQuantity,
                maxAppliedCostTotal,
                maxAbsolutePreviousQuantity,
                maxPreviousAverageUnitCost,
                maxAbsoluteResultingQuantity,
            ).all { it.signum() > 0 && ExactDecimalPolicy.supportsValue(it) },
        ) { "Todos los límites de costos deben ser decimales positivos y exactos" }
    }
}

/**
 * Datos confirmados para costear una entrada. [purchaseQuantity] siempre es positiva: una nota
 * de crédito o anulación debe revertir el movimiento publicado, no disfrazarse como entrada
 * negativa. [previousQuantity] sí admite cero o negativo para reparar saldos heredados.
 */
data class InventoryCostingRequest(
    val currency: CurrencyCode,
    val purchaseQuantity: BigDecimal,
    val purchaseUnitFactor: BigDecimal,
    val readPurchaseUnitCost: BigDecimal,
    val lineDiscount: BigDecimal,
    val taxTreatment: InventoryTaxTreatment,
    val taxEvidence: InventoryTaxEvidence,
    val costPolicy: CostPolicy,
    val previousQuantity: BigDecimal,
    val previousAverageUnitCost: BigDecimal,
    val roundingPolicy: InventoryCostRoundingPolicy,
    val limits: InventoryCostingLimits = InventoryCostingLimits(),
) {
    init {
        requireInput(purchaseQuantity, positive = true, field = "purchaseQuantity")
        requireInput(purchaseUnitFactor, positive = true, field = "purchaseUnitFactor")
        requireInput(readPurchaseUnitCost, positive = false, field = "readPurchaseUnitCost")
        requireInput(lineDiscount, positive = false, field = "lineDiscount")
        requireSignedInput(previousQuantity, "previousQuantity")
        require(
            previousAverageUnitCost.signum() >= 0 &&
                InventoryCostingDecimalPolicy.supportsPersisted(previousAverageUnitCost),
        ) { "previousAverageUnitCost no cumple la política decimal persistida" }
    }

    private fun requireInput(value: BigDecimal, positive: Boolean, field: String) {
        require(
            (if (positive) value.signum() > 0 else value.signum() >= 0) &&
                ExactDecimalPolicy.supportsValue(value),
        ) { "$field no cumple la política decimal del dominio" }
    }

    private fun requireSignedInput(value: BigDecimal, field: String) {
        require(InventoryCostingDecimalPolicy.supportsPersisted(value)) {
            "$field no cumple la política decimal del dominio"
        }
    }
}

/** Entrada agregada para un stock key; evita que varias líneas dependan del orden de cálculo. */
data class InventoryAverageCostRequest(
    val previousQuantity: BigDecimal,
    val previousAverageUnitCost: BigDecimal,
    val incomingInventoryQuantity: BigDecimal,
    val incomingAppliedCostTotal: BigDecimal,
    val roundingPolicy: InventoryCostRoundingPolicy,
    val limits: InventoryCostingLimits = InventoryCostingLimits(),
) {
    init {
        require(InventoryCostingDecimalPolicy.supportsPersisted(previousQuantity)) {
            "previousQuantity excede la política decimal persistida"
        }
        require(
            previousAverageUnitCost.signum() >= 0 &&
                InventoryCostingDecimalPolicy.supportsPersisted(previousAverageUnitCost),
        ) { "previousAverageUnitCost debe ser no negativo y persistible" }
        require(
            incomingInventoryQuantity.signum() > 0 &&
                InventoryCostingDecimalPolicy.supportsPersisted(incomingInventoryQuantity),
        ) { "incomingInventoryQuantity debe ser positivo y persistible" }
        require(
            incomingAppliedCostTotal.signum() >= 0 &&
                InventoryCostingDecimalPolicy.supportsPersisted(incomingAppliedCostTotal),
        ) { "incomingAppliedCostTotal debe ser no negativo y persistible" }
    }
}

sealed interface InventoryAverageCostResult {
    data class Calculated(val calculation: InventoryAverageCostCalculation) : InventoryAverageCostResult

    data class DecisionRequired(
        val reasons: Set<InventoryCostingDecisionReason>,
        val warnings: Set<InventoryCostingWarning> = emptySet(),
    ) : InventoryAverageCostResult {
        init {
            require(reasons.isNotEmpty()) { "DecisionRequired necesita al menos un motivo" }
        }
    }
}

data class InventoryAverageCostCalculation(
    val resultingQuantity: BigDecimal,
    val resultingAverageUnitCost: BigDecimal,
    val warnings: Set<InventoryCostingWarning>,
)

enum class InventoryCostingDecisionReason {
    UNKNOWN_TAX_TREATMENT,
    TAX_EVIDENCE_REQUIRED,
    TAX_EVIDENCE_CONFLICT,
    DISCOUNT_EXCEEDS_READ_TOTAL,
    INCLUDED_TAX_EXCEEDS_DISCOUNTED_TOTAL,
    ROUNDING_REQUIRED,
}

enum class InventoryCostingWarning {
    PURCHASE_QUANTITY_EXCEEDS_LIMIT,
    PURCHASE_FACTOR_EXCEEDS_LIMIT,
    READ_UNIT_COST_EXCEEDS_LIMIT,
    DISCOUNT_EXCEEDS_LIMIT,
    TAX_TOTAL_EXCEEDS_LIMIT,
    INVENTORY_QUANTITY_EXCEEDS_LIMIT,
    APPLIED_COST_TOTAL_EXCEEDS_LIMIT,
    PREVIOUS_QUANTITY_EXCEEDS_LIMIT,
    PREVIOUS_AVERAGE_COST_EXCEEDS_LIMIT,
    RESULTING_QUANTITY_EXCEEDS_LIMIT,
    PREVIOUS_NON_POSITIVE_BALANCE_REBASED,
    CALCULATION_ROUNDED,
}

sealed interface InventoryCostingResult {
    data class Calculated(val calculation: InventoryCostingCalculation) : InventoryCostingResult

    /** Bloqueo explicable: el llamador debe obtener o corregir una decisión humana. */
    data class DecisionRequired(
        val reasons: Set<InventoryCostingDecisionReason>,
        val warnings: Set<InventoryCostingWarning> = emptySet(),
    ) : InventoryCostingResult {
        init {
            require(reasons.isNotEmpty()) { "DecisionRequired necesita al menos un motivo" }
        }
    }
}

/**
 * Resultado auditable. Los totales son exactos salvo las divisiones inevitables declaradas en
 * [roundingPolicy]. El promedio usa [appliedCostTotal], no el costo unitario redondeado.
 */
data class InventoryCostingCalculation(
    val currency: CurrencyCode,
    val purchaseQuantity: BigDecimal,
    val purchaseUnitFactor: BigDecimal,
    val inventoryQuantity: BigDecimal,
    val readPurchaseUnitCost: BigDecimal,
    val lineDiscount: BigDecimal,
    val discountedBaseTotal: BigDecimal,
    val taxTreatment: InventoryTaxTreatment,
    val taxEvidence: InventoryTaxEvidence,
    val netCostTotal: BigDecimal,
    val taxTotal: BigDecimal,
    val grossCostTotal: BigDecimal,
    val costPolicy: CostPolicy,
    val appliedCostTotal: BigDecimal,
    val appliedInventoryUnitCost: BigDecimal,
    val previousQuantity: BigDecimal,
    val previousAverageUnitCost: BigDecimal,
    val resultingQuantity: BigDecimal,
    val resultingAverageUnitCost: BigDecimal,
    val roundingPolicy: InventoryCostRoundingPolicy,
    val warnings: Set<InventoryCostingWarning>,
)
