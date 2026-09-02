package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.InventoryAverageCostCalculation
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostingCalculation
import com.facturastock.app.domain.model.InventoryCostingDecisionReason
import com.facturastock.app.domain.model.InventoryCostingRequest
import com.facturastock.app.domain.model.InventoryCostingResult
import com.facturastock.app.domain.model.InventoryCostingWarning
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import java.math.BigDecimal

/**
 * Convierte una línea de compra a cantidad/costo de inventario y calcula el nuevo promedio.
 *
 * Fórmula para saldo previo positivo:
 * `promedio = (cantidadAnterior × costoAnterior + costoTotalAplicado) /
 *             (cantidadAnterior + cantidadEntrada)`.
 *
 * Si el saldo anterior es cero o negativo, su valorización histórica no representa inventario
 * disponible: se conserva la cantidad resultante pero el promedio se reinicia al costo de esta
 * entrada, incluso cuando la entrada todavía no alcanza a volver positivo el saldo. El resultado
 * lo advierte expresamente.
 */
class InventoryCostingService {
    fun calculate(request: InventoryCostingRequest): InventoryCostingResult {
        val warnings = inputWarnings(request).toMutableSet()
        val inventoryQuantity = request.purchaseQuantity.multiply(request.purchaseUnitFactor)
        val readLineTotal = request.purchaseQuantity.multiply(request.readPurchaseUnitCost)
        val discountedBase = readLineTotal.subtract(request.lineDiscount)

        val blockers = linkedSetOf<InventoryCostingDecisionReason>()
        if (request.taxTreatment == InventoryTaxTreatment.UNKNOWN) {
            blockers += InventoryCostingDecisionReason.UNKNOWN_TAX_TREATMENT
        }
        if (
            request.taxTreatment in setOf(
                InventoryTaxTreatment.INCLUDED,
                InventoryTaxTreatment.EXCLUDED,
            ) && request.taxEvidence == InventoryTaxEvidence.None
        ) {
            blockers += InventoryCostingDecisionReason.TAX_EVIDENCE_REQUIRED
        }
        if (
            request.taxTreatment == InventoryTaxTreatment.EXEMPT &&
            request.taxEvidence.hasPositiveValue()
        ) {
            blockers += InventoryCostingDecisionReason.TAX_EVIDENCE_CONFLICT
        }
        if (discountedBase.signum() < 0) {
            blockers += InventoryCostingDecisionReason.DISCOUNT_EXCEEDS_READ_TOTAL
        }
        if (
            request.taxTreatment == InventoryTaxTreatment.INCLUDED &&
            request.taxEvidence is InventoryTaxEvidence.ExplicitAmount &&
            request.taxEvidence.amount > discountedBase
        ) {
            blockers += InventoryCostingDecisionReason.INCLUDED_TAX_EXCEEDS_DISCOUNTED_TOTAL
        }
        if (blockers.isNotEmpty()) {
            return InventoryCostingResult.DecisionRequired(blockers, warnings)
        }

        val taxAmounts = try {
            calculateTaxAmounts(request, discountedBase, warnings)
        } catch (_: RoundingRequired) {
            return InventoryCostingResult.DecisionRequired(
                reasons = setOf(InventoryCostingDecisionReason.ROUNDING_REQUIRED),
                warnings = warnings,
            )
        }
        val appliedCostTotal = when (request.costPolicy) {
            CostPolicy.NET -> taxAmounts.net
            CostPolicy.GROSS -> taxAmounts.gross
        }
        val appliedUnitCost = try {
            divide(appliedCostTotal, inventoryQuantity, request, warnings)
        } catch (_: RoundingRequired) {
            return InventoryCostingResult.DecisionRequired(
                reasons = setOf(InventoryCostingDecisionReason.ROUNDING_REQUIRED),
                warnings = warnings,
            )
        }
        val averageResult = calculateAverage(
            InventoryAverageCostRequest(
                previousQuantity = request.previousQuantity,
                previousAverageUnitCost = request.previousAverageUnitCost,
                incomingInventoryQuantity = inventoryQuantity,
                incomingAppliedCostTotal = appliedCostTotal,
                roundingPolicy = request.roundingPolicy,
                limits = request.limits,
            ),
        )
        if (averageResult is InventoryAverageCostResult.DecisionRequired) {
            return InventoryCostingResult.DecisionRequired(
                reasons = averageResult.reasons,
                warnings = warnings + averageResult.warnings,
            )
        }
        val average = (averageResult as InventoryAverageCostResult.Calculated).calculation
        warnings += average.warnings
        val resultingQuantity = average.resultingQuantity
        val resultingAverage = average.resultingAverageUnitCost

        if (inventoryQuantity > request.limits.maxInventoryQuantity) {
            warnings += InventoryCostingWarning.INVENTORY_QUANTITY_EXCEEDS_LIMIT
        }
        if (appliedCostTotal > request.limits.maxAppliedCostTotal) {
            warnings += InventoryCostingWarning.APPLIED_COST_TOTAL_EXCEEDS_LIMIT
        }
        if (taxAmounts.tax > request.limits.maxTaxTotal) {
            warnings += InventoryCostingWarning.TAX_TOTAL_EXCEEDS_LIMIT
        }
        if (resultingQuantity.abs() > request.limits.maxAbsoluteResultingQuantity) {
            warnings += InventoryCostingWarning.RESULTING_QUANTITY_EXCEEDS_LIMIT
        }

        return InventoryCostingResult.Calculated(
            InventoryCostingCalculation(
                currency = request.currency,
                purchaseQuantity = request.purchaseQuantity,
                purchaseUnitFactor = request.purchaseUnitFactor,
                inventoryQuantity = inventoryQuantity,
                readPurchaseUnitCost = request.readPurchaseUnitCost,
                lineDiscount = request.lineDiscount,
                discountedBaseTotal = discountedBase,
                taxTreatment = request.taxTreatment,
                taxEvidence = request.taxEvidence,
                netCostTotal = taxAmounts.net,
                taxTotal = taxAmounts.tax,
                grossCostTotal = taxAmounts.gross,
                costPolicy = request.costPolicy,
                appliedCostTotal = appliedCostTotal,
                appliedInventoryUnitCost = appliedUnitCost,
                previousQuantity = request.previousQuantity,
                previousAverageUnitCost = request.previousAverageUnitCost,
                resultingQuantity = resultingQuantity,
                resultingAverageUnitCost = resultingAverage,
                roundingPolicy = request.roundingPolicy,
                warnings = warnings,
            ),
        )
    }

    /**
     * Calcula un promedio único a partir del agregado exacto de todas las líneas dirigidas al
     * mismo producto/almacén. Así el resultado no depende del orden ni acumula redondeos por línea.
     */
    fun calculateAverage(request: InventoryAverageCostRequest): InventoryAverageCostResult {
        val warnings = mutableSetOf<InventoryCostingWarning>()
        if (request.previousQuantity.abs() > request.limits.maxAbsolutePreviousQuantity) {
            warnings += InventoryCostingWarning.PREVIOUS_QUANTITY_EXCEEDS_LIMIT
        }
        if (request.previousAverageUnitCost > request.limits.maxPreviousAverageUnitCost) {
            warnings += InventoryCostingWarning.PREVIOUS_AVERAGE_COST_EXCEEDS_LIMIT
        }
        if (request.incomingInventoryQuantity > request.limits.maxInventoryQuantity) {
            warnings += InventoryCostingWarning.INVENTORY_QUANTITY_EXCEEDS_LIMIT
        }
        if (request.incomingAppliedCostTotal > request.limits.maxAppliedCostTotal) {
            warnings += InventoryCostingWarning.APPLIED_COST_TOTAL_EXCEEDS_LIMIT
        }

        val resultingQuantity = request.previousQuantity.add(request.incomingInventoryQuantity)
        if (resultingQuantity.abs() > request.limits.maxAbsoluteResultingQuantity) {
            warnings += InventoryCostingWarning.RESULTING_QUANTITY_EXCEEDS_LIMIT
        }
        val numerator: BigDecimal
        val denominator: BigDecimal
        if (request.previousQuantity.signum() <= 0) {
            warnings += InventoryCostingWarning.PREVIOUS_NON_POSITIVE_BALANCE_REBASED
            numerator = request.incomingAppliedCostTotal
            denominator = request.incomingInventoryQuantity
        } else {
            numerator = request.previousQuantity.multiply(request.previousAverageUnitCost)
                .add(request.incomingAppliedCostTotal)
            denominator = resultingQuantity
        }
        val average = try {
            divide(
                numerator = numerator,
                denominator = denominator,
                scale = request.roundingPolicy.scale,
                mode = request.roundingPolicy.mode,
                warnings = warnings,
            )
        } catch (_: RoundingRequired) {
            return InventoryAverageCostResult.DecisionRequired(
                reasons = setOf(InventoryCostingDecisionReason.ROUNDING_REQUIRED),
                warnings = warnings,
            )
        }
        return InventoryAverageCostResult.Calculated(
            InventoryAverageCostCalculation(
                resultingQuantity = resultingQuantity,
                resultingAverageUnitCost = average,
                warnings = warnings,
            ),
        )
    }

    private fun calculateTaxAmounts(
        request: InventoryCostingRequest,
        discountedBase: BigDecimal,
        warnings: MutableSet<InventoryCostingWarning>,
    ): TaxAmounts = when (request.taxTreatment) {
        InventoryTaxTreatment.INCLUDED -> when (val evidence = request.taxEvidence) {
            is InventoryTaxEvidence.ExplicitAmount -> TaxAmounts(
                net = discountedBase.subtract(evidence.amount),
                tax = evidence.amount,
                gross = discountedBase,
            )
            is InventoryTaxEvidence.ExplicitRate -> {
                val divisor = BigDecimal.ONE.add(evidence.percent.movePointLeft(2))
                val net = divide(discountedBase, divisor, request, warnings)
                TaxAmounts(net = net, tax = discountedBase.subtract(net), gross = discountedBase)
            }
            InventoryTaxEvidence.None -> error("La evidencia incluida fue validada antes")
        }
        InventoryTaxTreatment.EXCLUDED -> when (val evidence = request.taxEvidence) {
            is InventoryTaxEvidence.ExplicitAmount -> TaxAmounts(
                net = discountedBase,
                tax = evidence.amount,
                gross = discountedBase.add(evidence.amount),
            )
            is InventoryTaxEvidence.ExplicitRate -> {
                val exactTax = discountedBase.multiply(evidence.percent.movePointLeft(2))
                val tax = round(exactTax, request, warnings)
                TaxAmounts(net = discountedBase, tax = tax, gross = discountedBase.add(tax))
            }
            InventoryTaxEvidence.None -> error("La evidencia excluida fue validada antes")
        }
        InventoryTaxTreatment.EXEMPT -> TaxAmounts(
            net = discountedBase,
            tax = BigDecimal.ZERO,
            gross = discountedBase,
        )
        InventoryTaxTreatment.UNKNOWN -> error("El tratamiento desconocido fue bloqueado antes")
    }

    private fun divide(
        numerator: BigDecimal,
        denominator: BigDecimal,
        request: InventoryCostingRequest,
        warnings: MutableSet<InventoryCostingWarning>,
    ): BigDecimal {
        return divide(
            numerator = numerator,
            denominator = denominator,
            scale = request.roundingPolicy.scale,
            mode = request.roundingPolicy.mode,
            warnings = warnings,
        )
    }

    private fun divide(
        numerator: BigDecimal,
        denominator: BigDecimal,
        scale: Int,
        mode: java.math.RoundingMode,
        warnings: MutableSet<InventoryCostingWarning>,
    ): BigDecimal {
        val result = try {
            numerator.divide(denominator, scale, mode)
        } catch (exception: ArithmeticException) {
            throw RoundingRequired(exception)
        }
        if (result.multiply(denominator).compareTo(numerator) != 0) {
            warnings += InventoryCostingWarning.CALCULATION_ROUNDED
        }
        return result
    }

    private fun round(
        value: BigDecimal,
        request: InventoryCostingRequest,
        warnings: MutableSet<InventoryCostingWarning>,
    ): BigDecimal {
        val result = try {
            value.setScale(request.roundingPolicy.scale, request.roundingPolicy.mode)
        } catch (exception: ArithmeticException) {
            throw RoundingRequired(exception)
        }
        if (result.compareTo(value) != 0) {
            warnings += InventoryCostingWarning.CALCULATION_ROUNDED
        }
        return result
    }

    private fun inputWarnings(request: InventoryCostingRequest): Set<InventoryCostingWarning> =
        buildSet {
            if (request.purchaseQuantity > request.limits.maxPurchaseQuantity) {
                add(InventoryCostingWarning.PURCHASE_QUANTITY_EXCEEDS_LIMIT)
            }
            if (request.purchaseUnitFactor > request.limits.maxPurchaseUnitFactor) {
                add(InventoryCostingWarning.PURCHASE_FACTOR_EXCEEDS_LIMIT)
            }
            if (request.readPurchaseUnitCost > request.limits.maxReadPurchaseUnitCost) {
                add(InventoryCostingWarning.READ_UNIT_COST_EXCEEDS_LIMIT)
            }
            if (request.lineDiscount > request.limits.maxLineDiscount) {
                add(InventoryCostingWarning.DISCOUNT_EXCEEDS_LIMIT)
            }
            if (request.previousQuantity.abs() > request.limits.maxAbsolutePreviousQuantity) {
                add(InventoryCostingWarning.PREVIOUS_QUANTITY_EXCEEDS_LIMIT)
            }
            if (request.previousAverageUnitCost > request.limits.maxPreviousAverageUnitCost) {
                add(InventoryCostingWarning.PREVIOUS_AVERAGE_COST_EXCEEDS_LIMIT)
            }
        }

    private fun InventoryTaxEvidence.hasPositiveValue(): Boolean = when (this) {
        InventoryTaxEvidence.None -> false
        is InventoryTaxEvidence.ExplicitAmount -> amount.signum() > 0
        is InventoryTaxEvidence.ExplicitRate -> percent.signum() > 0
    }

    private data class TaxAmounts(
        val net: BigDecimal,
        val tax: BigDecimal,
        val gross: BigDecimal,
    )

    private class RoundingRequired(cause: ArithmeticException) : RuntimeException(cause)
}
