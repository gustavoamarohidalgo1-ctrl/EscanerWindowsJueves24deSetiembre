package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Contrato público del escenario de compra demostrativo.
 *
 * Todos los textos y documentos son sintéticos. Mantenerlos centralizados permite que el
 * catálogo sembrado, el generador local de la factura y las verificaciones recorran exactamente
 * los mismos tres resultados de vinculación sin depender de datos personales ni de red.
 */
data class DemoCatalogProduct(
    val semanticKey: String,
    val name: String,
    val sku: String,
    val barcode: String,
    val unitCode: String,
)

object DemoPurchaseScenario {
    const val BUSINESS_LEGAL_NAME = "[DEMO] Bodega de demostración"

    const val PRIMARY_SUPPLIER_LEGAL_NAME = "[DEMO] Distribuidora Lima"
    const val PRIMARY_SUPPLIER_RUC = "20111111112"

    /** Nombre exacto de un único producto activo: debe producir `AutoLinked(EXACT_NAME)`. */
    const val EXISTING_PRODUCT_DESCRIPTION = "[DEMO] Leche evaporada 400 g"

    /** Nombre exacto compartido por dos productos activos: debe producir `Ambiguous`. */
    const val AMBIGUOUS_PRODUCT_DESCRIPTION = "[DEMO] Arroz extra 1 kg"

    /** Deliberadamente ausente del catálogo: no debe auto-vincularse. */
    const val NEW_PRODUCT_DESCRIPTION = "[DEMO] Quinua tricolor 500 g"

    const val AMBIGUOUS_SUPPLIER_CODE = "PROV-AMB-037"
    const val NEW_SUPPLIER_CODE = "PROV-NEW-038"
    const val DOCUMENT_NUMBER = "F035-00000038"
    const val DOCUMENT_DATE_ISO = "2026-08-14"

    const val EXISTING_LINE_COUNT = 36
    const val AMBIGUOUS_LINE_COUNT = 1
    const val NEW_LINE_COUNT = 1
    const val TOTAL_LINE_COUNT =
        EXISTING_LINE_COUNT + AMBIGUOUS_LINE_COUNT + NEW_LINE_COUNT

    /** Un borrador estable por negocio demo permite reconocer el fixture sin marcar datos reales. */
    fun draftIdFor(businessId: BusinessId): DraftId = DraftId.from(
        UUID.nameUUIDFromBytes(
            "facturastock.demo.invoice.v1:${businessId.value}"
                .toByteArray(StandardCharsets.UTF_8),
        ),
    )

    /** Marca estable de la página canónica; un reemplazo normal recibe otro ID y deja el OCR fake. */
    fun imageIdFor(businessId: BusinessId): ImageId = ImageId.from(
        UUID.nameUUIDFromBytes(
            "facturastock.demo.invoice-image.v1:${businessId.value}"
                .toByteArray(StandardCharsets.UTF_8),
        ),
    )

    /** Los 36 renglones existentes ciclan esta lista y transportan su [DemoCatalogProduct.sku]. */
    val EXISTING_PRODUCTS: List<DemoCatalogProduct> = listOf(
        DemoCatalogProduct(
            semanticKey = "product:ambiguous:a",
            name = AMBIGUOUS_PRODUCT_DESCRIPTION,
            sku = "DEMO-ARR-1-A",
            barcode = "7750100000011",
            unitCode = "NIU",
        ),
        DemoCatalogProduct(
            semanticKey = "product:ambiguous:b",
            name = AMBIGUOUS_PRODUCT_DESCRIPTION,
            sku = "DEMO-ARR-1-B",
            barcode = "7750100000028",
            unitCode = "NIU",
        ),
        DemoCatalogProduct(
            semanticKey = "product:sugar",
            name = "[DEMO] Azúcar rubia 1 kg",
            sku = "DEMO-AZU-1",
            barcode = "7750100000035",
            unitCode = "NIU",
        ),
        DemoCatalogProduct(
            semanticKey = "product:oil",
            name = "[DEMO] Aceite vegetal a granel",
            sku = "DEMO-ACE-LT",
            barcode = "7750100000042",
            unitCode = "LTR",
        ),
        DemoCatalogProduct(
            semanticKey = "product:existing",
            name = EXISTING_PRODUCT_DESCRIPTION,
            sku = "DEMO-LEC-400",
            barcode = "7750100000059",
            unitCode = "NIU",
        ),
        DemoCatalogProduct(
            semanticKey = "product:soda",
            name = "[DEMO] Gaseosa personal 500 ml",
            sku = "DEMO-GAS-500",
            barcode = "7750100000066",
            unitCode = "NIU",
        ),
    )

    init {
        require(RucValidator.hasValidChecksum(PRIMARY_SUPPLIER_RUC))
        require(TOTAL_LINE_COUNT == 38)
        require(EXISTING_PRODUCTS.size == 6)
        require(EXISTING_PRODUCTS.all { it.name.startsWith("[DEMO] ") })
        require(EXISTING_PRODUCTS.map { it.semanticKey }.distinct().size == EXISTING_PRODUCTS.size)
        require(EXISTING_PRODUCTS.map { it.sku }.distinct().size == EXISTING_PRODUCTS.size)
        require(EXISTING_PRODUCTS.map { it.barcode }.distinct().size == EXISTING_PRODUCTS.size)
        require(
            EXISTING_PRODUCTS.count { it.name == AMBIGUOUS_PRODUCT_DESCRIPTION } == 2,
        )
        require(EXISTING_PRODUCTS.none { it.name == NEW_PRODUCT_DESCRIPTION })
    }
}
