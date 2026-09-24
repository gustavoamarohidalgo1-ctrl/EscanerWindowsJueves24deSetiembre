package com.facturastock.app.navigation

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.BusinessId

data class RouteDefinition(
    val pattern: String,
    val argumentNames: Set<String> = emptySet(),
    @param:StringRes val titleRes: Int,
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
    const val ONBOARDING = "onboarding"

    const val ACCOUNT = "account"
    const val ACCOUNT_MEMBERS = "account/members"
    const val ACCOUNT_INVITATIONS = "account/invitations"
    const val SYNC = "sync"

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
        RouteDefinition(HOME, titleRes = R.string.sales_title),
        RouteDefinition(SALES, titleRes = R.string.sales_title, topLevel = true),
        RouteDefinition(
            SALES_PRODUCT_REGISTRATION,
            argumentNames = setOf(PREFILL_BARCODE, REGISTRATION_REQUEST_ID, REGISTRATION_BUSINESS_ID),
            titleRes = R.string.inventory_register_products,
        ),
        RouteDefinition(DEBTORS, titleRes = R.string.debtors_title),
        RouteDefinition(INVOICES, titleRes = R.string.sales_title),
        RouteDefinition(INVENTORY, titleRes = R.string.navigation_inventory, topLevel = true),
        RouteDefinition(INVENTORY_REGISTER, titleRes = R.string.inventory_register_products),
        RouteDefinition(REPORTS, titleRes = R.string.navigation_reports, topLevel = true),
        RouteDefinition(
            PRODUCTS_PATTERN,
            argumentNames = setOf(PREFILL_BARCODE, EDIT_PRODUCT_ID, SPECIAL_PRODUCT, MANUAL_PRODUCT),
            titleRes = R.string.navigation_products,
        ),
        RouteDefinition(PURCHASES, titleRes = R.string.navigation_purchase_history),
        RouteDefinition(ONBOARDING, titleRes = R.string.onboarding_title),
        RouteDefinition(ACCOUNT, titleRes = R.string.navigation_account),
        RouteDefinition(ACCOUNT_MEMBERS, titleRes = R.string.navigation_account_members),
        RouteDefinition(ACCOUNT_INVITATIONS, titleRes = R.string.navigation_account_invitations),
        RouteDefinition(SYNC, titleRes = R.string.navigation_sync),
        RouteDefinition(NEW_PURCHASE, titleRes = R.string.purchase_new_title),
        RouteDefinition(NEW_DEBT, titleRes = R.string.debt_entry_title),
        draftRoute(PURCHASE_SOURCE, R.string.purchase_source_title, REPLACE_ID),
        draftRoute(CAMERA, R.string.purchase_camera_title, REPLACE_ID, SCAN_RETAKE),
        draftRoute(
            IMAGE_PREVIEW,
            R.string.purchase_preview_title,
            CAPTURE_ID,
        ),
        draftRoute(PROCESSING, R.string.purchase_processing_title),
        draftRoute(INVOICE_HEADER, R.string.purchase_header_title),
        draftRoute(INVOICE_LINES, R.string.purchase_lines_title),
        draftRoute(INVOICE_MATCHING, R.string.matching_title),
        draftRoute(
            PRODUCT_LINKING,
            R.string.purchase_linking_title,
            LINE_ID,
        ),
        draftRoute(PURCHASE_SUMMARY, R.string.purchase_summary_title),
        draftRoute(
            PURCHASE_CONFIRMATION,
            R.string.purchase_confirmation_title,
            EXPECTED_PREPARED_HASH,
        ),
        RouteDefinition(
            PURCHASE_SUCCESS,
            argumentNames = setOf(PURCHASE_ID),
            titleRes = R.string.purchase_success_title,
        ),
        RouteDefinition(
            PURCHASE_DETAIL,
            argumentNames = setOf(PURCHASE_ID),
            titleRes = R.string.purchase_detail_title,
        ),
        RouteDefinition(
            PURCHASE_VOID,
            argumentNames = setOf(PURCHASE_ID),
            titleRes = R.string.purchase_void_title,
        ),
        RouteDefinition(
            INVENTORY_DETAIL,
            argumentNames = setOf(PRODUCT_ID),
            titleRes = R.string.inventory_detail_title,
        ),
        RouteDefinition(
            DEBT_DETAIL,
            argumentNames = setOf(DEBT_ID),
            titleRes = R.string.debt_detail_title,
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
        "products?barcode=" + Uri.encode(barcode)

    /** Alta directa desde Inventario, sin requerir código de barras ni abrir los catálogos. */
    fun manualProductRegistration(): String = "products?manualProduct=true"

    fun salesProductRegistration(barcode: String, requestId: String, businessId: BusinessId): String =
        "sales/register?barcode=${Uri.encode(barcode)}&requestId=${Uri.encode(requestId)}&businessId=${businessId.value}"

    /** Edición por identidad estable, también para productos sin código de barras. */
    fun editInventoryProduct(productId: ProductId): String =
        "products?editProductId=${productId.value}"

    private fun draftRoute(
        pattern: String,
        @StringRes titleRes: Int,
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
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    SALES(
        route = AppRoutes.SALES,
        labelRes = R.string.navigation_sales,
        iconRes = R.drawable.ic_sale,
    ),
    INVENTORY(
        route = AppRoutes.INVENTORY,
        labelRes = R.string.navigation_inventory,
        iconRes = R.drawable.ic_inventory,
    ),
    REPORTS(
        route = AppRoutes.REPORTS,
        labelRes = R.string.navigation_reports,
        iconRes = R.drawable.ic_reports,
    ),
}
