package com.facturastock.app.navigation

import org.jetbrains.compose.resources.DrawableResource

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import com.facturastock.app.domain.model.id.BusinessId

data class RouteDefinition(
    val pattern: String,
    val argumentNames: Set<String> = emptySet(),
    val titleRes: StringResource,
    val topLevel: Boolean = false,
    val protectsDraftOnExit: Boolean = false,
)

object AppRoutes {
    private val ROUTE_ARGUMENT = Regex("""\{([A-Za-z][A-Za-z0-9]*)\}""")

    const val DRAFT_ID = "draftId"
    const val CAPTURE_ID = "captureId"
    const val LINE_ID = "lineId"
    const val PURCHASE_ID = "purchaseId"
    const val PRODUCT_ID = "productId"
    const val DEBT_ID = "debtId"
    const val REPLACE_ID = "replaceId"
    const val SCAN_RETAKE = "scanRetake"
    const val EXPECTED_PREPARED_HASH = "expectedPreparedHash"
    const val PREFILL_BARCODE = "barcode"
    const val EDIT_PRODUCT_ID = "editProductId"
    const val SPECIAL_PRODUCT = "specialProduct"
    const val MANUAL_PRODUCT = "manualProduct"
    const val REGISTRATION_REQUEST_ID = "requestId"
    const val REGISTRATION_BUSINESS_ID = "businessId"

    /** Alias de compatibilidad para pilas guardadas antes de retirar Inicio del menú. */
    const val HOME = "home"
    const val SALES = "sales"
    const val SALES_PRODUCT_REGISTRATION =
        "sales/register?barcode={$PREFILL_BARCODE}&requestId={$REGISTRATION_REQUEST_ID}&businessId={$REGISTRATION_BUSINESS_ID}"
    const val DEBTORS = "debtors"
    /** Alias de compatibilidad de la sección Facturas retirada. */
    const val INVOICES = "invoices"
    const val REPORTS = "reports"
    const val PRODUCTS = "products"

    /** Destino real del catálogo: acepta el código precargado para el alta del producto. */
    const val PRODUCTS_PATTERN = "products?barcode={$PREFILL_BARCODE}&editProductId={$EDIT_PRODUCT_ID}&specialProduct={$SPECIAL_PRODUCT}&manualProduct={$MANUAL_PRODUCT}"
    const val PURCHASES = "purchases"
    const val INVENTORY = "inventory"
    const val INVENTORY_REGISTER = "inventory/register"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"

    const val NEW_PURCHASE = "purchase/new"
    const val NEW_DEBT = "debtors/new"
    const val PURCHASE_SOURCE =
        "purchase/draft/{$DRAFT_ID}/source?replace={$REPLACE_ID}"
    const val CAMERA =
        "purchase/draft/{$DRAFT_ID}/camera?replace={$REPLACE_ID}&scanRetake={$SCAN_RETAKE}"
    const val IMAGE_PREVIEW =
        "purchase/draft/{$DRAFT_ID}/preview/{$CAPTURE_ID}"
    const val PROCESSING = "purchase/draft/{$DRAFT_ID}/processing"
    const val INVOICE_HEADER = "purchase/draft/{$DRAFT_ID}/header"
    const val INVOICE_LINES = "purchase/draft/{$DRAFT_ID}/lines"
    const val INVOICE_MATCHING = "purchase/draft/{$DRAFT_ID}/matching"
    const val PRODUCT_LINKING =
        "purchase/draft/{$DRAFT_ID}/linking/{$LINE_ID}"
    const val PURCHASE_SUMMARY = "purchase/draft/{$DRAFT_ID}/summary"
    const val PURCHASE_CONFIRMATION =
        "purchase/draft/{$DRAFT_ID}/confirmation/{$EXPECTED_PREPARED_HASH}"
    const val PURCHASE_SUCCESS = "purchase/success/{$PURCHASE_ID}"
    const val PURCHASE_DETAIL = "purchases/{$PURCHASE_ID}"
    const val PURCHASE_VOID = "purchases/{$PURCHASE_ID}/void"
    const val INVENTORY_DETAIL = "inventory/{$PRODUCT_ID}"
    const val DEBT_DETAIL = "debtors/{$DEBT_ID}"

    val all: List<RouteDefinition> = listOf(
        RouteDefinition(HOME, titleRes = Res.string.sales_title),
        RouteDefinition(SALES, titleRes = Res.string.sales_title, topLevel = true),
        RouteDefinition(
            SALES_PRODUCT_REGISTRATION,
            argumentNames = setOf(PREFILL_BARCODE, REGISTRATION_REQUEST_ID, REGISTRATION_BUSINESS_ID),
            titleRes = Res.string.inventory_register_products,
        ),
        RouteDefinition(DEBTORS, titleRes = Res.string.debtors_title),
        RouteDefinition(INVOICES, titleRes = Res.string.sales_title),
        RouteDefinition(INVENTORY, titleRes = Res.string.navigation_inventory, topLevel = true),
        RouteDefinition(INVENTORY_REGISTER, titleRes = Res.string.inventory_register_products),
        RouteDefinition(REPORTS, titleRes = Res.string.navigation_reports, topLevel = true),
        RouteDefinition(
            PRODUCTS_PATTERN,
            argumentNames = setOf(PREFILL_BARCODE, EDIT_PRODUCT_ID, SPECIAL_PRODUCT, MANUAL_PRODUCT),
            titleRes = Res.string.navigation_products,
        ),
        RouteDefinition(PURCHASES, titleRes = Res.string.navigation_purchase_history),
        RouteDefinition(SETTINGS, titleRes = Res.string.navigation_settings),
        RouteDefinition(ONBOARDING, titleRes = Res.string.onboarding_title),
        RouteDefinition(NEW_PURCHASE, titleRes = Res.string.purchase_new_title),
        RouteDefinition(NEW_DEBT, titleRes = Res.string.debt_entry_title),
        draftRoute(PURCHASE_SOURCE, Res.string.purchase_source_title, REPLACE_ID),
        draftRoute(CAMERA, Res.string.purchase_camera_title, REPLACE_ID, SCAN_RETAKE),
        draftRoute(
            IMAGE_PREVIEW,
            Res.string.purchase_preview_title,
            CAPTURE_ID,
        ),
        draftRoute(PROCESSING, Res.string.purchase_processing_title),
        draftRoute(INVOICE_HEADER, Res.string.purchase_header_title),
        draftRoute(INVOICE_LINES, Res.string.purchase_lines_title),
        draftRoute(INVOICE_MATCHING, Res.string.matching_title),
        draftRoute(
            PRODUCT_LINKING,
            Res.string.purchase_linking_title,
            LINE_ID,
        ),
        draftRoute(PURCHASE_SUMMARY, Res.string.purchase_summary_title),
        draftRoute(
            PURCHASE_CONFIRMATION,
            Res.string.purchase_confirmation_title,
            EXPECTED_PREPARED_HASH,
        ),
        RouteDefinition(
            PURCHASE_SUCCESS,
            argumentNames = setOf(PURCHASE_ID),
            titleRes = Res.string.purchase_success_title,
        ),
        RouteDefinition(
            PURCHASE_DETAIL,
            argumentNames = setOf(PURCHASE_ID),
            titleRes = Res.string.purchase_detail_title,
        ),
        RouteDefinition(
            PURCHASE_VOID,
            argumentNames = setOf(PURCHASE_ID),
            titleRes = Res.string.purchase_void_title,
        ),
        RouteDefinition(
            INVENTORY_DETAIL,
            argumentNames = setOf(PRODUCT_ID),
            titleRes = Res.string.inventory_detail_title,
        ),
        RouteDefinition(
            DEBT_DETAIL,
            argumentNames = setOf(DEBT_ID),
            titleRes = Res.string.debt_detail_title,
        ),
    ).also { definitions ->
        require(definitions.map(RouteDefinition::pattern).distinct().size == definitions.size)
        require(
            definitions.all { definition ->
                ROUTE_ARGUMENT.findAll(definition.pattern)
                    .map { it.groupValues[1] }
                    .toSet() == definition.argumentNames
            },
        )
    }

    val topLevel: List<RouteDefinition> = all.filter(RouteDefinition::topLevel)
    private val definitionsByPattern: Map<String, RouteDefinition> =
        all.associateBy(RouteDefinition::pattern)

    /** Destinos que no deben sobrevivir detrás de una instantánea READY_TO_POST. */
    val editableDraftPatterns: Set<String> = setOf(
        PURCHASE_SOURCE,
        CAMERA,
        IMAGE_PREVIEW,
        PROCESSING,
        INVOICE_HEADER,
        INVOICE_LINES,
        INVOICE_MATCHING,
        PRODUCT_LINKING,
    )

    fun specialProductRegistration(): String = "products?specialProduct=true"

    fun definitionFor(pattern: String?): RouteDefinition? =
        if (pattern == null) null else definitionsByPattern[pattern]

    fun source(draftId: DraftId, replaceId: ImageId? = null): String =
        "purchase/draft/${draftId.value}/source" + replaceQuery(replaceId)

    fun camera(
        draftId: DraftId,
        replaceId: ImageId? = null,
        replaceSoleInvoiceScan: Boolean = false,
    ): String {
        require(replaceId == null || !replaceSoleInvoiceScan) {
            "El reintento de escaneo no acepta un replaceId multipágina"
        }
        val query = when {
            replaceId != null -> "?replace=${replaceId.value}"
            replaceSoleInvoiceScan -> "?scanRetake=true"
            else -> ""
        }
        return "purchase/draft/${draftId.value}/camera$query"
    }

    /** Cámara directa para sustituir la única foto que produjo un OCR fallido. */
    fun invoiceScanRetakeCamera(draftId: DraftId): String =
        camera(draftId = draftId, replaceSoleInvoiceScan = true)

    /** Sufijo de consulta opcional con el ID de la página a reemplazar ("Repetir"). */
    private fun replaceQuery(replaceId: ImageId?): String =
        replaceId?.let { "?replace=${it.value}" }.orEmpty()

    fun imagePreview(draftId: DraftId, captureId: CaptureId): String =
        "purchase/draft/${draftId.value}/preview/${captureId.value}"

    fun processing(draftId: DraftId): String =
        "purchase/draft/${draftId.value}/processing"

    fun invoiceHeader(draftId: DraftId): String =
        "purchase/draft/${draftId.value}/header"

    /** El fallback OCR no crea un destino paralelo: abre la misma cabecera editable. */
    fun manualInvoiceReview(draftId: DraftId): String = invoiceHeader(draftId)

    fun invoiceLines(draftId: DraftId): String =
        "purchase/draft/${draftId.value}/lines"

    fun invoiceMatching(draftId: DraftId): String =
        "purchase/draft/${draftId.value}/matching"

    fun productLinking(draftId: DraftId, lineId: LineId): String =
        "purchase/draft/${draftId.value}/linking/${lineId.value}"

    fun purchaseSummary(draftId: DraftId): String =
        "purchase/draft/${draftId.value}/summary"

    fun purchaseConfirmation(draftId: DraftId, expectedPreparedLogicalHash: String): String {
        require(PREPARED_HASH.matches(expectedPreparedLogicalHash)) {
            "expectedPreparedLogicalHash debe ser SHA-256 hex en minúsculas"
        }
        return "purchase/draft/${draftId.value}/confirmation/$expectedPreparedLogicalHash"
    }

    fun purchaseSuccess(purchaseId: PurchaseId): String =
        "purchase/success/${purchaseId.value}"

    fun purchaseDetail(purchaseId: PurchaseId): String =
        "purchases/${purchaseId.value}"

    fun purchaseVoid(purchaseId: PurchaseId): String =
        "purchases/${purchaseId.value}/void"

    fun inventoryDetail(productId: ProductId): String =
        "inventory/${productId.value}"

    fun debtDetail(debtId: DebtId): String =
        "debtors/${debtId.value}"

    /**
     * Catálogo con el formulario de producto abierto. Con [barcode] vacío abre el formulario
     * en blanco (alta manual); con código, precargado desde el lector físico. La ausencia del
     * parámetro abre la lista de productos sin formulario.
     */
    fun productsWithBarcode(barcode: String): String =
        "products?barcode=" + encodeRouteComponent(barcode)

    /** Alta directa desde Inventario, sin requerir código de barras ni abrir los catálogos. */
    fun manualProductRegistration(): String = "products?manualProduct=true"

    fun salesProductRegistration(barcode: String, requestId: String, businessId: BusinessId): String =
        "sales/register?barcode=${encodeRouteComponent(barcode)}&requestId=${encodeRouteComponent(requestId)}&businessId=${businessId.value}"

    /** Edición por identidad estable, también para productos sin código de barras. */
    fun editInventoryProduct(productId: ProductId): String =
        "products?editProductId=${productId.value}"

    private fun draftRoute(
        pattern: String,
        titleRes: StringResource,
        vararg additionalIds: String,
    ): RouteDefinition = RouteDefinition(
        pattern = pattern,
        argumentNames = setOf(DRAFT_ID, *additionalIds),
        titleRes = titleRes,
        protectsDraftOnExit = true,
    )

    private val PREPARED_HASH = Regex("[0-9a-f]{64}")
}

enum class TopLevelDestination(
    val route: String,
    val labelRes: StringResource,
    val iconRes: DrawableResource,
) {
    SALES(
        route = AppRoutes.SALES,
        labelRes = Res.string.navigation_sales,
        iconRes = Res.drawable.ic_sale,
    ),
    INVENTORY(
        route = AppRoutes.INVENTORY,
        labelRes = Res.string.navigation_inventory,
        iconRes = Res.drawable.ic_inventory,
    ),
    REPORTS(
        route = AppRoutes.REPORTS,
        labelRes = Res.string.navigation_reports,
        iconRes = Res.drawable.ic_reports,
    ),
}

/** Codificación porcentual equivalente a `android.net.Uri.encode` (espacio como %20). */
internal fun encodeRouteComponent(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
