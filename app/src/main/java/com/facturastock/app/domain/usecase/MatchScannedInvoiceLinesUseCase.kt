package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.MatchStatus
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.UnitRepository
import kotlinx.coroutines.flow.first

/**
 * Resuelve las líneas persistidas de un borrador contra el catálogo. Reutiliza
 * [ProductMatchingUseCase] (no auto-vincula ambigüedades ni sugerencias difusas) y cachea
 * consultas idénticas para no repetir SQL en facturas con filas repetidas.
 *
 * El código impreso de la factura entra como código de proveedor/SKU, nunca como código de
 * barras: ese campo solo vale si la persona lo escanea o lo escribe.
 */
class MatchScannedInvoiceLinesUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val productMatchingUseCase: ProductMatchingUseCase,
    private val unitRepository: UnitRepository? = null,
) {
    suspend operator fun invoke(draftId: DraftId): List<ScannedItemMatch> {
        val businessId = appConfigurationRepository.current().activeBusinessId ?: return emptyList()
        val draft = invoiceDraftRepository.findDraft(draftId) ?: return emptyList()
        if (draft.businessId != businessId) return emptyList()
        val lines = invoiceDraftRepository.observeLines(draftId).first()
        val outcomes = LinkedHashMap<MatchKey, ProductMatchOutcome>()
        return lines.mapIndexed { index, line ->
            val item = toMatch(businessId, draft.supplierId, index, line, outcomes)
            val product = item.matchedProduct
            item.copy(
                sourceLineId = line.lineId,
                sourceUnitCode = line.unitCodeNormalized ?: line.unitRaw,
                sourceCurrency =
                    line.unitCost?.currency ?: line.lineTotal?.currency
                        ?: line.discount?.currency ?: line.tax?.currency ?: draft.currency,
                inventoryUnitCode = product?.let { unitRepository?.findById(it.unitId)?.code },
                purchaseUnitCode =
                    product?.purchaseUnitId?.let {
                        unitRepository?.findById(it)?.code
                    },
            )
        }
    }

    private suspend fun toMatch(
        businessId: BusinessId,
        supplierId: SupplierId?,
        index: Int,
        line: InvoiceLine,
        outcomes: MutableMap<MatchKey, ProductMatchOutcome>,
    ): ScannedItemMatch {
        val description = line.descriptionRaw.trim()
        val quantity = line.quantity?.value
        val unitCost = line.unitCost?.amount
        val printedCode = line.codeRaw?.trim()?.takeIf { it.isNotBlank() }
        val key =
            MatchKey(
                supplierCode = printedCode,
                description = description.takeIf { it.isNotEmpty() },
            )
        if (key.supplierCode == null && key.description == null) {
            return ScannedItemMatch(
                lineIndex = index,
                rawDescription = description,
                quantity = quantity,
                unitCost = unitCost,
            )
        }
        val outcome =
            outcomes.getOrPut(key) {
                productMatchingUseCase(
                    ProductMatchQuery(
                        businessId = businessId,
                        supplierId = supplierId,
                        supplierCode = key.supplierCode,
                        description = key.description,
                    ),
                )
            }
        return when (outcome) {
            is ProductMatchOutcome.AutoLinked -> {
                ScannedItemMatch(
                    lineIndex = index,
                    rawDescription = description,
                    quantity = quantity,
                    unitCost = unitCost,
                    printedCode = printedCode,
                    matchedProduct = outcome.candidate.product,
                    status = MatchStatus.AUTO_LINKED,
                    matchReasonLabel = reasonLabel(outcome.candidate.reason),
                    suggestedProducts = outcome.alternatives.map { it.product }.take(3),
                )
            }

            is ProductMatchOutcome.Ambiguous -> {
                ScannedItemMatch(
                    lineIndex = index,
                    rawDescription = description,
                    quantity = quantity,
                    unitCost = unitCost,
                    printedCode = printedCode,
                    status = MatchStatus.UNMATCHED,
                    suggestedProducts = outcome.candidates.map { it.product }.take(3),
                )
            }

            is ProductMatchOutcome.Suggestions -> {
                ScannedItemMatch(
                    lineIndex = index,
                    rawDescription = description,
                    quantity = quantity,
                    unitCost = unitCost,
                    printedCode = printedCode,
                    status = MatchStatus.UNMATCHED,
                    suggestedProducts = outcome.candidates.map { it.product }.take(3),
                )
            }

            ProductMatchOutcome.NoMatch -> {
                ScannedItemMatch(
                    lineIndex = index,
                    rawDescription = description,
                    quantity = quantity,
                    unitCost = unitCost,
                    printedCode = printedCode,
                )
            }
        }
    }

    private fun reasonLabel(reason: ProductMatchReason): String =
        when (reason) {
            ProductMatchReason.BARCODE -> "Código de barras"
            ProductMatchReason.SUPPLIER_CODE -> "Código de proveedor"
            ProductMatchReason.CONFIRMED_ALIAS -> "Alias memorizado"
            ProductMatchReason.SKU -> "SKU"
            ProductMatchReason.EXACT_NAME -> "Nombre exacto"
            ProductMatchReason.SIMILAR_NAME -> "Nombre similar"
        }

    private data class MatchKey(
        val supplierCode: String?,
        val description: String?,
    )
}
