package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.time.Instant
import java.time.LocalDate

/**
 * Borrador recuperable de factura de compra. Conserva el texto OCR original (`*Raw`) junto al
 * valor normalizado de la cabecera y la confianza en escala 0..1000 (0.000 a 1.000).
 *
 * Los importes [subtotal], [tax], [otherCharges] y [total] son exactos y llevan su propia moneda;
 * cuando hay importes, [currency] es obligatoria y todos comparten esa moneda (lo exige el
 * mapeador de persistencia). [issueDate] es la fecha normalizada del documento.
 */
data class InvoiceDraft(
    val draftId: DraftId,
    val businessId: BusinessId,
    val status: DraftStatus = DraftStatus.CREATED,
    val supplierId: SupplierId? = null,
    val supplierRucRaw: String? = null,
    val supplierRucNormalized: String? = null,
    val supplierLegalNameRaw: String? = null,
    val supplierLegalNameNormalized: String? = null,
    val documentType: PurchaseDocumentType? = null,
    val documentNumberRaw: String? = null,
    val documentNumberNormalized: String? = null,
    /** Texto de fecha exactamente como fue leído; [issueDate] es su interpretación normalizada. */
    val issueDateRaw: String? = null,
    val issueDate: LocalDate? = null,
    val currency: CurrencyCode? = null,
    val subtotal: Money? = null,
    val tax: Money? = null,
    val otherCharges: Money? = null,
    val total: Money? = null,
    val headerConfidence: Int? = null,
    /** Token de la única ejecución OCR autorizada a publicar sobre este borrador. */
    val activeOcrRunId: OcrRunId? = null,
    val confirmedPurchaseId: PurchaseId? = null,
    val lastError: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Recorte rectangular sobre la imagen ya rotada, en coordenadas NORMALIZADAS: cada arista
 * es una fracción de la dimensión correspondiente expresada en diezmilésimas (0..10000),
 * de modo que el dominio trabaja con enteros exactos y no necesita decimales binarios.
 * Las cuatro aristas vienen juntas o no hay recorte; un rectángulo fuera de rango es
 * imposible de construir — el recorte nunca sale del archivo.
 */
data class ImageCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left in 0 until FRACTION_MAX) { "left fuera de rango: $left" }
        require(top in 0 until FRACTION_MAX) { "top fuera de rango: $top" }
        require(right in (left + 1)..FRACTION_MAX) { "right fuera de rango: $right (left=$left)" }
        require(bottom in (top + 1)..FRACTION_MAX) { "bottom fuera de rango: $bottom (top=$top)" }
    }

    /** Recorte equivalente sobre la imagen tras girarla 90° en sentido horario. */
    fun rotated90Cw(): ImageCrop = ImageCrop(
        left = FRACTION_MAX - bottom,
        top = left,
        right = FRACTION_MAX - top,
        bottom = right,
    )

    /** true si el rectángulo cubre la imagen completa (equivale a no recortar). */
    fun isFullImage(): Boolean =
        left == 0 && top == 0 && right == FRACTION_MAX && bottom == FRACTION_MAX

    companion object {
        /** Máximo de una arista normalizada: 10000 diezmilésimas = el 100 % de la dimensión. */
        const val FRACTION_MAX = 10_000
    }
}

/**
 * Página capturada de un borrador. La base de datos guarda únicamente metadatos: [filePath] es
 * una ruta relativa al almacenamiento privado de la app y el contenido nunca se persiste como
 * BLOB. `(draftId, pageIndex)` es único: recapturar una página reemplaza el registro anterior.
 */
data class InvoiceImage(
    val imageId: ImageId,
    val draftId: DraftId,
    val businessId: BusinessId,
    val pageIndex: Int,
    val filePath: String,
    val sha256: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
    val rotationDegrees: Int = 0,
    val crop: ImageCrop? = null,
    val createdAt: Instant,
)

/**
 * Proyección tipada de una línea. El snapshot de revisión conserva OCR/calculado/escrito;
 * esta vista contiene la última selección válida para consumidores posteriores. Descuento e
 * impuesto son magnitudes no negativas; [lineTotal] conserva el signo impreso.
 */
data class InvoiceLine(
    val lineId: LineId,
    val draftId: DraftId,
    val businessId: BusinessId,
    val position: Int,
    val descriptionRaw: String,
    val descriptionNormalized: String? = null,
    val codeRaw: String? = null,
    val codeNormalized: String? = null,
    val quantity: Quantity? = null,
    val unitRaw: String? = null,
    val unitCodeNormalized: String? = null,
    val unitCost: UnitCost? = null,
    val discount: Money? = null,
    val tax: Money? = null,
    val unitId: UnitId? = null,
    val productId: ProductId? = null,
    val lineTotal: Money? = null,
    val ocrConfidence: Int? = null,
    val linkConfidence: Int? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
