package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.InventoryCostingCalculation
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostingDecisionReason
import com.facturastock.app.domain.model.InventoryCostingLimits
import com.facturastock.app.domain.model.InventoryCostingRequest
import com.facturastock.app.domain.model.InventoryCostingResult
import com.facturastock.app.domain.model.InventoryCostingWarning
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxEvidenceType
import com.facturastock.app.domain.model.InventoryTaxTreatment
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryCostingServiceTest {
    private val service = InventoryCostingService()
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun `cajas por factor producen la cantidad real de inventario`() {
        val result = calculate(request(quantity = "2", factor = "12", unitCost = "120"))

        assertDecimal("24", result.inventoryQuantity)
        assertDecimal("240", result.appliedCostTotal)
        assertDecimal("10.000000", result.appliedInventoryUnitCost)
    }

    @Test
    fun `factor fraccionario convierte sin truncar la cantidad`() {
        val result = calculate(request(quantity = "4", factor = "2.5", unitCost = "25"))

        assertEquals(BigDecimal("10.0"), result.inventoryQuantity)
        assertDecimal("10", result.appliedInventoryUnitCost)
    }

    @Test
    fun `promedio coincide con la formula documentada usando el total exacto`() {
        val result = calculate(
            request(
                quantity = "2",
                unitCost = "5",
                previousQuantity = "10",
                previousCost = "4",
            ),
        )

        // (10 × 4 + 2 × 5) / (10 + 2) = 50 / 12
        assertDecimal("50", result.previousQuantity.multiply(result.previousAverageUnitCost)
            .add(result.appliedCostTotal))
        assertDecimal("4.166667", result.resultingAverageUnitCost)
        assertTrue(InventoryCostingWarning.CALCULATION_ROUNDED in result.warnings)
    }

    @Test
    fun `promedio no reconstruye el total desde el costo unitario redondeado`() {
        val result = calculate(
            request(
                quantity = "2",
                factor = "12",
                unitCost = "100",
                previousQuantity = "10",
                previousCost = "4",
                scale = 18,
            ),
        )

        assertDecimal("200", result.appliedCostTotal)
        assertDecimal("8.333333333333333333", result.appliedInventoryUnitCost)
        assertDecimal("7.058823529411764706", result.resultingAverageUnitCost)
    }

    @Test
    fun `impuesto excluido se suma una sola vez y descuento ocurre antes del impuesto`() {
        val net = calculate(
            request(
                unitCost = "100",
                discount = "10",
                treatment = InventoryTaxTreatment.EXCLUDED,
                evidence = InventoryTaxEvidence.ExplicitRate(BigDecimal("18")),
                policy = CostPolicy.NET,
            ),
        )
        val gross = calculate(
            request(
                unitCost = "100",
                discount = "10",
                treatment = InventoryTaxTreatment.EXCLUDED,
                evidence = InventoryTaxEvidence.ExplicitRate(BigDecimal("18")),
                policy = CostPolicy.GROSS,
            ),
        )

        assertDecimal("90", net.netCostTotal)
        assertDecimal("16.2", net.taxTotal)
        assertDecimal("106.2", net.grossCostTotal)
        assertDecimal("90", net.appliedCostTotal)
        assertDecimal("106.2", gross.appliedCostTotal)
        assertDecimal("106.2", gross.netCostTotal.add(gross.taxTotal))
    }

    @Test
    fun `impuesto incluido se extrae sin volver a sumar IGV en politica bruta`() {
        val net = calculate(
            request(
                unitCost = "118",
                treatment = InventoryTaxTreatment.INCLUDED,
                evidence = InventoryTaxEvidence.ExplicitRate(BigDecimal("18")),
                policy = CostPolicy.NET,
            ),
        )
        val gross = calculate(
            request(
                unitCost = "118",
                treatment = InventoryTaxTreatment.INCLUDED,
                evidence = InventoryTaxEvidence.ExplicitRate(BigDecimal("18")),
                policy = CostPolicy.GROSS,
            ),
        )

        assertDecimal("100.000000", net.netCostTotal)
        assertDecimal("18.000000", net.taxTotal)
        assertDecimal("118", net.grossCostTotal)
        assertDecimal("100.000000", net.appliedCostTotal)
        assertDecimal("118", gross.appliedCostTotal)
    }

    @Test
    fun `importe tributario leido se respeta y no se reemplaza por tasa configurada`() {
        val result = calculate(
            request(
                unitCost = "118",
                treatment = InventoryTaxTreatment.INCLUDED,
                evidence = InventoryTaxEvidence.ExplicitAmount(BigDecimal("17")),
            ),
        )

        assertEquals(InventoryTaxEvidenceType.EXPLICIT_AMOUNT, result.taxEvidence.type)
        assertDecimal("101", result.netCostTotal)
        assertDecimal("17", result.taxTotal)
        assertDecimal("118", result.grossCostTotal)
    }

    @Test
    fun `exonerado mantiene costo sin impuesto en ambas politicas`() {
        val net = calculate(request(unitCost = "37.25", policy = CostPolicy.NET))
        val gross = calculate(request(unitCost = "37.25", policy = CostPolicy.GROSS))

        assertDecimal("0", net.taxTotal)
        assertDecimal("37.25", net.appliedCostTotal)
        assertDecimal("37.25", gross.appliedCostTotal)
    }

    @Test
    fun `tratamiento desconocido bloquea aunque exista una tasa`() {
        val result = service.calculate(
            request(
                treatment = InventoryTaxTreatment.UNKNOWN,
                evidence = InventoryTaxEvidence.ExplicitRate(BigDecimal("18")),
            ),
        ) as InventoryCostingResult.DecisionRequired

        assertEquals(
            setOf(InventoryCostingDecisionReason.UNKNOWN_TAX_TREATMENT),
            result.reasons,
        )
    }

    @Test
    fun `incluido y excluido requieren evidencia tributaria explicita`() {
        val included = decision(request(treatment = InventoryTaxTreatment.INCLUDED))
        val excluded = decision(request(treatment = InventoryTaxTreatment.EXCLUDED))

        assertTrue(InventoryCostingDecisionReason.TAX_EVIDENCE_REQUIRED in included.reasons)
        assertTrue(InventoryCostingDecisionReason.TAX_EVIDENCE_REQUIRED in excluded.reasons)
    }

    @Test
    fun `exonerado rechaza evidencia positiva pero permite evidencia cero auditable`() {
        val blocked = decision(
            request(evidence = InventoryTaxEvidence.ExplicitAmount(BigDecimal("0.01"))),
        )
        val allowed = calculate(
            request(evidence = InventoryTaxEvidence.ExplicitRate(BigDecimal.ZERO)),
        )

        assertTrue(InventoryCostingDecisionReason.TAX_EVIDENCE_CONFLICT in blocked.reasons)
        assertEquals(InventoryTaxEvidenceType.EXPLICIT_RATE, allowed.taxEvidence.type)
        assertDecimal("0", allowed.taxTotal)
    }

    @Test
    fun `descuento mayor a base y tributo incluido mayor a total se bloquean juntos`() {
        val result = decision(
            request(
                unitCost = "10",
                discount = "11",
                treatment = InventoryTaxTreatment.INCLUDED,
                evidence = InventoryTaxEvidence.ExplicitAmount(BigDecimal("12")),
            ),
        )

        assertEquals(
            setOf(
                InventoryCostingDecisionReason.DISCOUNT_EXCEEDS_READ_TOTAL,
                InventoryCostingDecisionReason.INCLUDED_TAX_EXCEEDS_DISCOUNTED_TOTAL,
            ),
            result.reasons,
        )
    }

    @Test
    fun `saldo anterior cero reinicia promedio al costo de entrada y lo advierte`() {
        val result = calculate(request(unitCost = "7.125", previousQuantity = "0", previousCost = "99"))

        assertDecimal("7.125000", result.resultingAverageUnitCost)
        assertTrue(InventoryCostingWarning.PREVIOUS_NON_POSITIVE_BALANCE_REBASED in result.warnings)
    }

    @Test
    fun `saldo anterior negativo conserva cantidad resultante incluso si aun no vuelve positivo`() {
        val result = calculate(
            request(
                quantity = "2",
                unitCost = "9",
                previousQuantity = "-10",
                previousCost = "500",
            ),
        )

        assertDecimal("-8", result.resultingQuantity)
        assertDecimal("9.000000", result.resultingAverageUnitCost)
        assertTrue(InventoryCostingWarning.PREVIOUS_NON_POSITIVE_BALANCE_REBASED in result.warnings)
    }

    @Test
    fun `fracciones conservan cantidad y costo total antes de la division explicita`() {
        val result = calculate(
            request(quantity = "0.333", factor = "3", unitCost = "0.10", scale = 12),
        )

        assertEquals(BigDecimal("0.999"), result.inventoryQuantity)
        assertEquals(BigDecimal("0.03330"), result.appliedCostTotal)
        assertEquals(BigDecimal("0.033333333333"), result.appliedInventoryUnitCost)
        assertTrue(InventoryCostingWarning.CALCULATION_ROUNDED in result.warnings)
    }

    @Test
    fun `modos de redondeo producen resultados distintos y quedan en el resultado`() {
        val down = calculate(request(unitCost = "1", factor = "3", scale = 2, mode = RoundingMode.DOWN))
        val up = calculate(request(unitCost = "1", factor = "3", scale = 2, mode = RoundingMode.HALF_UP))

        assertEquals(RoundingMode.DOWN, down.roundingPolicy.mode)
        assertDecimal("0.33", down.appliedInventoryUnitCost)
        assertDecimal("0.33", up.appliedInventoryUnitCost)

        val halfEven = calculate(request(unitCost = "5", factor = "2", scale = 0, mode = RoundingMode.HALF_EVEN))
        val halfUp = calculate(request(unitCost = "5", factor = "2", scale = 0, mode = RoundingMode.HALF_UP))
        assertDecimal("2", halfEven.appliedInventoryUnitCost)
        assertDecimal("3", halfUp.appliedInventoryUnitCost)
    }

    @Test
    fun `UNNECESSARY devuelve bloqueo en vez de perder precision`() {
        val result = decision(
            request(unitCost = "1", factor = "3", scale = 2, mode = RoundingMode.UNNECESSARY),
        )

        assertEquals(setOf(InventoryCostingDecisionReason.ROUNDING_REQUIRED), result.reasons)
    }

    @Test
    fun `limites altos solo advierten y nunca recortan el valor`() {
        val result = calculate(
            request(
                quantity = "2000000",
                factor = "100001",
                unitCost = "1000000001",
                scale = 6,
            ),
        )

        assertEquals(BigDecimal("200002000000"), result.inventoryQuantity)
        assertEquals(BigDecimal("2000000002000000"), result.appliedCostTotal)
        assertTrue(InventoryCostingWarning.PURCHASE_QUANTITY_EXCEEDS_LIMIT in result.warnings)
        assertTrue(InventoryCostingWarning.PURCHASE_FACTOR_EXCEEDS_LIMIT in result.warnings)
        assertTrue(InventoryCostingWarning.READ_UNIT_COST_EXCEEDS_LIMIT in result.warnings)
        assertTrue(InventoryCostingWarning.APPLIED_COST_TOTAL_EXCEEDS_LIMIT in result.warnings)
        assertFalse(result.inventoryQuantity == BigDecimal("1000000000000"))
    }

    @Test
    fun `limites configurables mantienen exactamente el calculo`() {
        val limits = InventoryCostingLimits(
            maxPurchaseQuantity = BigDecimal.ONE,
            maxPurchaseUnitFactor = BigDecimal.ONE,
            maxReadPurchaseUnitCost = BigDecimal.ONE,
            maxLineDiscount = BigDecimal.ONE,
            maxTaxTotal = BigDecimal.ONE,
            maxInventoryQuantity = BigDecimal.ONE,
            maxAppliedCostTotal = BigDecimal.ONE,
            maxAbsolutePreviousQuantity = BigDecimal.ONE,
            maxPreviousAverageUnitCost = BigDecimal.ONE,
            maxAbsoluteResultingQuantity = BigDecimal.ONE,
        )
        val result = calculate(
            request(quantity = "2", factor = "3", unitCost = "4", limits = limits),
        )

        assertDecimal("6", result.inventoryQuantity)
        assertDecimal("8", result.appliedCostTotal)
        assertTrue(InventoryCostingWarning.INVENTORY_QUANTITY_EXCEEDS_LIMIT in result.warnings)
        assertTrue(InventoryCostingWarning.RESULTING_QUANTITY_EXCEEDS_LIMIT in result.warnings)
    }

    @Test
    fun `promedio agregado de varias lineas es exacto e independiente de su orden`() {
        val result = service.calculateAverage(
            InventoryAverageCostRequest(
                previousQuantity = BigDecimal("10"),
                previousAverageUnitCost = BigDecimal("4"),
                incomingInventoryQuantity = BigDecimal("5"),
                incomingAppliedCostTotal = BigDecimal("32"),
                roundingPolicy = InventoryCostRoundingPolicy(6, RoundingMode.HALF_EVEN),
            ),
        ) as InventoryAverageCostResult.Calculated

        assertDecimal("15", result.calculation.resultingQuantity)
        assertDecimal("4.800000", result.calculation.resultingAverageUnitCost)
    }

    @Test
    fun `saldo derivado con escala 36 puede reutilizarse sin perdida`() {
        val previous = BigDecimal("0.123456789012345678123456789012345678")
        val result = service.calculateAverage(
            InventoryAverageCostRequest(
                previousQuantity = previous,
                previousAverageUnitCost = BigDecimal("1.000000000000000000"),
                incomingInventoryQuantity = BigDecimal("0.000000000000000001000000000000000001"),
                incomingAppliedCostTotal = BigDecimal("0.000000000000000001000000000000000001"),
                roundingPolicy = InventoryCostRoundingPolicy(18, RoundingMode.HALF_EVEN),
            ),
        ) as InventoryAverageCostResult.Calculated

        assertEquals(
            previous.add(BigDecimal("0.000000000000000001000000000000000001")),
            result.calculation.resultingQuantity,
        )
    }

    private fun request(
        quantity: String = "1",
        factor: String = "1",
        unitCost: String = "10",
        discount: String = "0",
        treatment: InventoryTaxTreatment = InventoryTaxTreatment.EXEMPT,
        evidence: InventoryTaxEvidence = InventoryTaxEvidence.None,
        policy: CostPolicy = CostPolicy.NET,
        previousQuantity: String = "0",
        previousCost: String = "0",
        scale: Int = 6,
        mode: RoundingMode = RoundingMode.HALF_EVEN,
        limits: InventoryCostingLimits = InventoryCostingLimits(),
    ): InventoryCostingRequest = InventoryCostingRequest(
        currency = pen,
        purchaseQuantity = BigDecimal(quantity),
        purchaseUnitFactor = BigDecimal(factor),
        readPurchaseUnitCost = BigDecimal(unitCost),
        lineDiscount = BigDecimal(discount),
        taxTreatment = treatment,
        taxEvidence = evidence,
        costPolicy = policy,
        previousQuantity = BigDecimal(previousQuantity),
        previousAverageUnitCost = BigDecimal(previousCost),
        roundingPolicy = InventoryCostRoundingPolicy(scale, mode),
        limits = limits,
    )

    private fun calculate(request: InventoryCostingRequest): InventoryCostingCalculation =
        (service.calculate(request) as InventoryCostingResult.Calculated).calculation

    private fun decision(request: InventoryCostingRequest): InventoryCostingResult.DecisionRequired =
        service.calculate(request) as InventoryCostingResult.DecisionRequired

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

}
