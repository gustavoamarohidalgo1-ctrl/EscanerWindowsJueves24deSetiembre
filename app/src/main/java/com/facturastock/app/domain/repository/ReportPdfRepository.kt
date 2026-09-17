package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.id.BusinessId
import java.time.Instant

interface ReportPdfRepository {
    /** Null cuando el negocio no existe o no está activo. No modifica datos comerciales. */
    suspend fun readSnapshot(
        businessId: BusinessId,
        range: SalesReportRange,
        generatedAt: Instant,
        primaryCurrency: CurrencyCode,
        kind: ReportPdfKind,
    ): ReportPdfSnapshot?
}

interface ReportPdfWriter {
    suspend fun write(
        documentUri: String,
        prepared: PreparedReportPdf,
    ): ReportPdfWriteStatus
}
