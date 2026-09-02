package com.facturastock.app.feature.linking

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.ProductMatchCandidate
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.feature.linking.ProductLinkingContract.CreateForm
import com.facturastock.app.feature.linking.ProductLinkingContract.LineLinking
import com.facturastock.app.feature.linking.ProductLinkingContract.LinkStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.SearchStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.State
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariantes del contrato de vinculación. La pantalla nunca inventa una coincidencia: propone
 * como máximo [ProductMatchingUseCase.MAX_CANDIDATES] candidatos distintos, con claves estables,
 * y solo deja continuar cuando cada línea está enlazada o explícitamente pendiente.
 */
class ProductLinkingContractTest {
    @Test
    fun `nunca se ofrecen mas de cinco candidatos ni el mismo producto dos veces`() {
        val excess = (1..ProductMatchingUseCase.MAX_CANDIDATES + 1).map { seed ->
            candidate(seed = 100 + seed)
        }

        assertThrows(IllegalArgumentException::class.java) { state(candidates = excess) }
        assertThrows(IllegalArgumentException::class.java) {
            state(candidates = listOf(candidate(seed = 101), candidate(seed = 101)))
        }
        assertEquals(
            ProductMatchingUseCase.MAX_CANDIDATES,
            state(candidates = excess.take(ProductMatchingUseCase.MAX_CANDIDATES)).candidates.size,
        )
    }

    @Test
    fun `las claves y posiciones de linea son estables y unicas`() {
        assertThrows(IllegalArgumentException::class.java) {
            state(lines = listOf(line(seed = 1, position = 0), line(seed = 1, position = 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            state(lines = listOf(line(seed = 1, position = 0), line(seed = 2, position = 0)))
        }
    }

    @Test
    fun `una consulta pendiente o fallida nunca conserva candidatos seleccionables`() {
        val previous = candidate(seed = 101)

        assertThrows(IllegalArgumentException::class.java) {
            state(candidates = listOf(previous), searchStatus = SearchStatus.SEARCHING)
        }
        assertThrows(IllegalArgumentException::class.java) {
            state(candidates = listOf(previous), searchStatus = SearchStatus.FAILED)
        }
        assertFalse(state(searchStatus = SearchStatus.SEARCHING).canSelectCandidate)
        assertFalse(state(searchStatus = SearchStatus.FAILED).canSelectCandidate)
        assertTrue(state(candidates = listOf(previous)).canSelectCandidate)
    }

    @Test
    fun `dejar pendiente resuelve la linea pero una sin coincidencia no deja continuar`() {
        val skipped = state(
            lines = listOf(
                line(seed = 1, position = 0, status = LinkStatus.AUTO_LINKED),
                line(seed = 2, position = 1, status = LinkStatus.SKIPPED),
            ),
        )
        val unmatched = state(
            lines = listOf(
                line(seed = 1, position = 0, status = LinkStatus.CONFIRMED),
                line(seed = 2, position = 1, status = LinkStatus.NO_MATCH),
            ),
        )
        val choosing = state(
            lines = listOf(line(seed = 1, position = 0, status = LinkStatus.NEEDS_CHOICE)),
        )

        assertEquals(2, skipped.resolvedCount)
        assertTrue(skipped.canContinue)
        assertEquals(1, unmatched.resolvedCount)
        assertFalse(unmatched.canContinue)
        assertFalse(choosing.canContinue)
        assertFalse(state(lines = emptyList()).canContinue)
    }

    @Test
    fun `una operacion en curso o una carga pendiente suspenden el avance`() {
        val ready = state(lines = listOf(line(seed = 1, position = 0, status = LinkStatus.CONFIRMED)))

        assertTrue(ready.canContinue)
        assertFalse(ready.copy(isBusy = true).canContinue)
        assertFalse(ready.copy(isLoading = true).canContinue)
        assertFalse(ready.copy(draftId = null).canContinue)
    }

    @Test
    fun `la linea actual se ubica por su clave estable y no por su indice`() {
        val second = line(seed = 2, position = 1)
        val current = state(
            lines = listOf(line(seed = 1, position = 0), second),
            currentLineId = second.lineId,
        )

        assertEquals(second.lineId, current.currentLine?.lineId)
        assertEquals(1, current.currentLineIndex)
        assertEquals(-1, current.copy(currentLineId = LineId.from(uuid(99))).currentLineIndex)
    }

    @Test
    fun `crear un producto exige nombre unidad y precio de venta por inventario`() {
        val unitId = UnitId.from(uuid(60))

        assertFalse(CreateForm().isValid)
        assertFalse(CreateForm(name = "Azúcar rubia").isValid)
        assertFalse(CreateForm(name = "   ", unitId = unitId, salePrice = "8.50").isValid)
        assertFalse(CreateForm(name = "Azúcar rubia", unitId = unitId).isValid)
        assertTrue(CreateForm(name = "Azúcar rubia", unitId = unitId, salePrice = "8.50").isValid)
        assertFalse(
            CreateForm(
                name = "Azúcar rubia",
                unitId = unitId,
                salePrice = "90071992547410.00",
            ).isValid,
        )
    }

    @Test
    fun `crear un producto solo admite barcode ASCII imprimible u opcional vacio`() {
        val base = CreateForm(
            name = "Azúcar rubia",
            unitId = UnitId.from(uuid(60)),
            salePrice = "8.50",
        )

        assertTrue(base.isBarcodeValid)
        assertTrue(base.copy(barcode = "  0012aB-Z  ").isBarcodeValid)
        assertFalse(base.copy(barcode = "ABC\n123").isBarcodeValid)
        assertFalse(base.copy(barcode = "ABC\u202E123").isBarcodeValid)
        assertFalse(base.copy(barcode = "A".repeat(129)).isValid)
    }

    @Test
    fun `la unidad de compra y su factor se definen juntos y en positivo`() {
        val unitId = UnitId.from(uuid(60))
        val boxId = UnitId.from(uuid(61))
        val base = CreateForm(name = "Azúcar rubia", unitId = unitId, salePrice = "8.50")

        assertFalse(base.copy(purchaseUnitId = boxId).isFactorValid)
        assertFalse(base.copy(purchaseUnitId = boxId, purchaseFactor = "0").isFactorValid)
        assertFalse(base.copy(purchaseUnitId = boxId, purchaseFactor = "-12").isFactorValid)
        assertFalse(base.copy(purchaseUnitId = boxId, purchaseFactor = "doce").isFactorValid)
        assertFalse(base.copy(purchaseFactor = "12").isFactorValid)
        assertTrue(base.copy(purchaseUnitId = boxId, purchaseFactor = "12").isValid)
    }

    @Test
    fun `una caja de doce declara doce unidades de inventario antes de guardar`() {
        val base = CreateForm(
            name = "Azúcar rubia",
            unitId = UnitId.from(uuid(60)),
            purchaseUnitId = UnitId.from(uuid(61)),
        )

        assertEquals("12", base.copy(purchaseFactor = "12").purchaseEquivalence)
        assertEquals("12.00", base.copy(purchaseFactor = "12,00").purchaseEquivalence)
        assertNull(base.copy(purchaseFactor = "doce").purchaseEquivalence)
        assertNull(CreateForm(name = "Azúcar rubia", purchaseFactor = "12").purchaseEquivalence)
    }

    @Test
    fun `un enlace legacy sin precio nunca puede resolverse dejandolo pendiente`() {
        val required = line(seed = 1, position = 0, status = LinkStatus.NEEDS_CHOICE)
            .copy(requiresSalePrice = true)

        assertFalse(state(lines = listOf(required)).canContinue)
        assertFalse(state(lines = listOf(required.copy(status = LinkStatus.SKIPPED))).canContinue)
    }

    private fun state(
        lines: List<LineLinking> = listOf(line(seed = 1, position = 0)),
        candidates: List<ProductMatchCandidate> = emptyList(),
        currentLineId: LineId? = null,
        searchStatus: SearchStatus = SearchStatus.IDLE,
    ): State = State(
        draftId = DRAFT_ID,
        isLoading = false,
        lines = lines,
        currentLineId = currentLineId,
        candidates = candidates,
        searchStatus = searchStatus,
    )

    private fun line(
        seed: Int,
        position: Int,
        status: LinkStatus = LinkStatus.NEEDS_CHOICE,
    ): LineLinking = LineLinking(
        lineId = LineId.from(uuid(seed)),
        position = position,
        description = "AZUCAR RUBIA X KG",
        code = "COD-77",
        status = status,
        linkedProductName = null,
        linkReason = null,
    )

    private fun candidate(seed: Int): ProductMatchCandidate = ProductMatchCandidate(
        product = Product(
            productId = ProductId.from(uuid(seed)),
            businessId = BUSINESS_ID,
            unitId = UnitId.from(uuid(60)),
            name = "Azúcar Rubia $seed",
            status = CatalogStatus.ACTIVE,
            createdAt = NOW,
            updatedAt = NOW,
        ),
        reason = ProductMatchReason.SIMILAR_NAME,
        confidencePermille = 853,
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
        )
    }
}
