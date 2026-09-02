package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import java.math.RoundingMode

/**
 * Entradas externas al texto OCR que pueden participar en la interpretación financiera.
 * Los identificadores impiden reutilizar por accidente moneda o configuración de otra sesión.
 */
data class InvoiceTotalsParseContext(
    val draftId: DraftId,
    val runId: OcrRunId,
    val referenceIgvRate: TaxRate,
    val currency: CurrencyCode? = null,
    val taxRoundingMode: RoundingMode? = null,
)

/** Signo que estaba presente en el fragmento OCR; NONE conserva que no se imprimió uno. */
enum class InvoicePrintedSign {
    NONE,
    POSITIVE,
    NEGATIVE,
    PARENTHESES_NEGATIVE,
}

/** Relación geométrica o léxica que enlazó una etiqueta financiera con su importe. */
enum class InvoiceTotalReason {
    EXPLICIT_LABEL,
    SAME_LINE_AS_LABEL,
    SAME_ROW_AS_LABEL,
    DIRECTLY_BELOW_LABEL,
    LOWER_SUMMARY_BLOCK,
    FINAL_DOCUMENT_PAGE,
}

/** Advertencias de una ocurrencia concreta del bloque financiero. */
enum class InvoiceTotalOccurrenceWarning(val requiresReview: Boolean) {
    OCR_LABEL_CORRECTION(true),
    MISSING_EXPLICIT_SIGN(true),
    NEGATIVE_CHARGE_REQUIRES_REVIEW(true),
    WEAK_GEOMETRY_MATCH(true),
    WEAK_SUMMARY_CONTEXT(true),
}

/**
 * Una lectura impresa. [candidate] conserva la interpretación del valor y [evidence] conserva
 * además la etiqueta: una etiqueta y su importe pueden proceder de cajas OCR diferentes.
 */
data class InvoiceTotalOccurrence<T : Any>(
    val candidate: Candidate<T>,
    val label: String,
    val rawText: String,
    val pageIndex: Int,
    val evidence: List<CandidateEvidence>,
    val boundingBox: InvoiceTextBoundingBox?,
    val printedSign: InvoicePrintedSign = InvoicePrintedSign.NONE,
    val reasons: List<InvoiceTotalReason> = emptyList(),
    val warnings: List<InvoiceTotalOccurrenceWarning> = emptyList(),
) {
    init {
        require(label.isNotBlank()) { "Una lectura financiera necesita etiqueta" }
        require(rawText.isNotBlank()) { "Una lectura financiera debe conservar texto OCR" }
        require(pageIndex >= 0) { "Página financiera negativa" }
        require(evidence.isNotEmpty()) { "Una lectura financiera necesita evidencia" }
        require(evidence.all { item -> item.pageIndex == null || item.pageIndex == pageIndex }) {
            "La evidencia financiera no puede mezclar páginas"
        }
        require(candidate.evidence.pageIndex == null || candidate.evidence.pageIndex == pageIndex) {
            "El valor financiero no puede pertenecer a otra página"
        }
        require(reasons.distinct().size == reasons.size) { "Razones financieras duplicadas" }
        require(warnings.distinct().size == warnings.size) {
            "Advertencias financieras duplicadas"
        }
    }

    val requiresReview: Boolean
        get() = candidate.requiresReview || warnings.any(InvoiceTotalOccurrenceWarning::requiresReview)
}

/** Selección visual inferior y las demás ocurrencias impresas que siguen disponibles. */
data class InvoiceTotalField<T : Any>(
    val selected: InvoiceTotalOccurrence<T>? = null,
    val alternatives: List<InvoiceTotalOccurrence<T>> = emptyList(),
) {
    init {
        require(alternatives.distinct().size == alternatives.size) {
            "Alternativas financieras duplicadas"
        }
        require(selected == null || selected !in alternatives) {
            "La selección financiera no debe repetirse como alternativa"
        }
    }

    val all: List<InvoiceTotalOccurrence<T>>
        get() = listOfNotNull(selected) + alternatives
}

enum class InvoiceOtherChargeKind {
    OTHER_CHARGES,
    ISC,
    ICBPER,
    PERCEPTION,
}

/** Cargo individual: nunca se pierde su etiqueta ni se agrega silenciosamente con otro cargo. */
data class InvoiceOtherCharge(
    val position: Int,
    val kind: InvoiceOtherChargeKind,
    val label: String,
    val amount: InvoiceTotalOccurrence<Money>,
) {
    init {
        require(position >= 0) { "Posición de cargo negativa" }
        require(label.isNotBlank()) { "Un cargo necesita etiqueta" }
    }
}

enum class InvoiceRoundingDirection {
    INCREASE,
    DECREASE,
    UNCHANGED,
}

enum class InvoiceRoundingReason {
    PRINTED_ROUNDING,
    PRINTED_ROUNDING_DIFFERENCE,
}

/** Ajuste explícito. [signedAmount] conserva el signo; no se infiere desde una diferencia. */
data class InvoiceRoundingAdjustment(
    val signedAmount: Money,
    val direction: InvoiceRoundingDirection,
    val reason: InvoiceRoundingReason,
) {
    init {
        val expectedDirection = when {
            signedAmount.minorUnits > 0L -> InvoiceRoundingDirection.INCREASE
            signedAmount.minorUnits < 0L -> InvoiceRoundingDirection.DECREASE
            else -> InvoiceRoundingDirection.UNCHANGED
        }
        require(direction == expectedDirection) {
            "La dirección del redondeo debe coincidir con su importe firmado"
        }
    }
}

/** Valores que realmente aparecen en el comprobante; un campo ausente permanece vacío. */
data class ReadInvoiceTotals(
    val taxableOperations: InvoiceTotalField<Money> = InvoiceTotalField(),
    val exemptOperations: InvoiceTotalField<Money> = InvoiceTotalField(),
    val unaffectedOperations: InvoiceTotalField<Money> = InvoiceTotalField(),
    val discounts: InvoiceTotalField<Money> = InvoiceTotalField(),
    val subtotal: InvoiceTotalField<Money> = InvoiceTotalField(),
    val igv: InvoiceTotalField<Money> = InvoiceTotalField(),
    val printedIgvRate: InvoiceTotalField<TaxRate> = InvoiceTotalField(),
    val otherCharges: List<InvoiceOtherCharge> = emptyList(),
    val rounding: InvoiceTotalField<InvoiceRoundingAdjustment> = InvoiceTotalField(),
    val total: InvoiceTotalField<Money> = InvoiceTotalField(),
) {
    init {
        require(otherCharges.map(InvoiceOtherCharge::position) == otherCharges.indices.toList()) {
            "Los cargos deben conservar orden correlativo"
        }
    }
}

enum class InvoiceTotalsComponent {
    TAXABLE_OPERATIONS,
    EXEMPT_OPERATIONS,
    UNAFFECTED_OPERATIONS,
    DISCOUNTS,
    READ_SUBTOTAL,
    READ_IGV,
    CALCULATED_IGV,
    OTHER_CHARGES,
    PRINTED_ROUNDING,
}

enum class InvoiceTotalsCalculationReason {
    OPERATIONS_NET_OF_DISCOUNTS,
    TAXABLE_OPERATIONS_TIMES_PRINTED_RATE,
    TAXABLE_OPERATIONS_TIMES_SUPPORTED_REFERENCE_RATE,
    PRINTED_COMPONENTS_BEFORE_ROUNDING,
    PRINTED_COMPONENTS_AFTER_ROUNDING,
}

/** Resultado aritmético exacto; no reemplaza ninguna lectura OCR. */
data class CalculatedInvoiceAmount(
    val value: Money,
    val reason: InvoiceTotalsCalculationReason,
    val components: List<InvoiceTotalsComponent>,
) {
    init {
        require(components.isNotEmpty()) { "Un cálculo financiero necesita componentes" }
        require(components.distinct().size == components.size) {
            "Componentes de cálculo duplicados"
        }
    }
}

enum class InvoiceTaxRateSource {
    PRINTED_ON_DOCUMENT,
    CONFIGURED_REFERENCE_SUPPORTED_BY_IGV_LABEL,
}

data class AppliedInvoiceTaxRate(
    val rate: TaxRate,
    val source: InvoiceTaxRateSource,
)

data class CalculatedInvoiceTotals(
    val subtotal: CalculatedInvoiceAmount? = null,
    val igv: CalculatedInvoiceAmount? = null,
    val totalBeforeRounding: CalculatedInvoiceAmount? = null,
    val total: CalculatedInvoiceAmount? = null,
    val appliedIgvRate: AppliedInvoiceTaxRate? = null,
)

/** Convención estable: cada diferencia es importe leído menos importe calculado. */
enum class InvoiceTotalsDifferenceConvention {
    READ_MINUS_CALCULATED,
}

data class InvoiceTotalsReconciliation(
    val readTotal: Money,
    val calculatedBeforeRounding: Money,
    val calculatedTotal: Money,
    val explicitRounding: InvoiceRoundingAdjustment?,
    val differenceBeforeRounding: Money,
    val difference: Money,
    val subtotalDifference: Money? = null,
    val igvDifference: Money? = null,
    val convention: InvoiceTotalsDifferenceConvention =
        InvoiceTotalsDifferenceConvention.READ_MINUS_CALCULATED,
) {
    init {
        val reconciledAmounts = listOf(
            readTotal,
            calculatedBeforeRounding,
            calculatedTotal,
            differenceBeforeRounding,
            difference,
        ) + listOfNotNull(subtotalDifference, igvDifference)
        val currencies = reconciledAmounts.map(Money::currency).toSet()
        require(currencies.size == 1) { "La reconciliación no puede mezclar monedas" }
        require(explicitRounding == null || explicitRounding.signedAmount.currency in currencies) {
            "El redondeo debe usar la moneda reconciliada"
        }
        require(explicitRounding != null || calculatedBeforeRounding == calculatedTotal) {
            "Sin redondeo explícito ambos totales calculados deben coincidir"
        }
        val expectedCalculatedTotal = explicitRounding?.let { rounding ->
            Math.addExact(calculatedBeforeRounding.minorUnits, rounding.signedAmount.minorUnits)
        } ?: calculatedBeforeRounding.minorUnits
        require(calculatedTotal.minorUnits == expectedCalculatedTotal) {
            "El total calculado debe incorporar exactamente el redondeo explícito"
        }
        require(
            differenceBeforeRounding.minorUnits ==
                Math.subtractExact(readTotal.minorUnits, calculatedBeforeRounding.minorUnits),
        ) {
            "La diferencia previa debe ser leído menos calculado antes de redondeo"
        }
        require(
            difference.minorUnits == Math.subtractExact(readTotal.minorUnits, calculatedTotal.minorUnits),
        ) {
            "La diferencia final debe ser leído menos calculado"
        }
    }
}

enum class InvoiceTotalsWarning(val requiresReview: Boolean) {
    CURRENCY_FROM_CONTEXT(false),
    CURRENCY_CONFLICT(true),
    MULTIPLE_PRINTED_VALUES(true),
    PRIOR_SUMMARY_ALTERNATIVES(true),
    UNRESOLVED_PRINTED_FIELD(true),
    MISSING_CURRENCY_FOR_PRINTED_AMOUNTS(true),
    OCR_CORRECTION_APPLIED(true),
    REFERENCE_TAX_RATE_DIFFERS_FROM_PRINTED(true),
    CALCULATION_REQUIRES_ROUNDING(true),
    CALCULATION_OVERFLOW(true),
    MISSING_IGV_FOR_TAXABLE_OPERATIONS(true),
    MISSING_TAX_TREATMENT_EVIDENCE(true),
    DISCOUNT_SEMANTICS_NOT_EVALUATED(true),
    PRINTED_SUBTOTAL_DIFFERS_FROM_CALCULATED(true),
    PRINTED_IGV_DIFFERS_FROM_CALCULATED(true),
    PRINTED_TOTAL_DIFFERS_FROM_CALCULATED(true),
}

/** Resultado puro ligado exactamente al snapshot OCR que produjo toda la evidencia. */
data class InvoiceTotalsParseResult(
    val draftId: DraftId,
    val runId: OcrRunId,
    val currency: CurrencyCode?,
    val referenceIgvRate: TaxRate,
    val taxRoundingMode: RoundingMode?,
    val read: ReadInvoiceTotals,
    val calculated: CalculatedInvoiceTotals,
    val reconciliation: InvoiceTotalsReconciliation?,
    val warnings: List<InvoiceTotalsWarning> = emptyList(),
) {
    init {
        require(warnings.distinct().size == warnings.size) {
            "Advertencias globales de totales duplicadas"
        }
    }

    val requiresReview: Boolean
        get() = warnings.any(InvoiceTotalsWarning::requiresReview) ||
            listOf(
                read.taxableOperations,
                read.exemptOperations,
                read.unaffectedOperations,
                read.discounts,
                read.subtotal,
                read.igv,
                read.total,
            ).any { field -> field.all.any(InvoiceTotalOccurrence<*>::requiresReview) } ||
            read.printedIgvRate.all.any(InvoiceTotalOccurrence<*>::requiresReview) ||
            read.rounding.all.any(InvoiceTotalOccurrence<*>::requiresReview) ||
            read.otherCharges.any { charge -> charge.amount.requiresReview }
}
