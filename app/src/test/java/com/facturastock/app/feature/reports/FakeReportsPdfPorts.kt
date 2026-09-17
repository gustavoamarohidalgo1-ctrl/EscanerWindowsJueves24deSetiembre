package com.facturastock.app.feature.reports

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.ReportPdfRepository
import com.facturastock.app.domain.repository.ReportPdfWriter
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

internal class FakeReportsPdfPorts :
    ReportPdfRepository,
    ReportPdfWriter {
    var preparationGate: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null
    var preparationFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var missingBusiness = false
    var businessName = "Negocio antes del selector"
    var writeStatus = ReportPdfWriteStatus.WRITTEN
    val reads = mutableListOf<Pair<ReportPdfKind, ReportPdfSnapshot>>()
    val writes = mutableListOf<Pair<String, PreparedReportPdf>>()

    override suspend fun readSnapshot(
        businessId: BusinessId,
        range: SalesReportRange,
        generatedAt: Instant,
        primaryCurrency: CurrencyCode,
        kind: ReportPdfKind,
    ): ReportPdfSnapshot? {
        val snapshot =
            ReportPdfSnapshot(
                businessId,
                businessName,
                generatedAt,
                range,
                primaryCurrency,
                emptyList(),
                emptyList(),
                emptyMap(),
            )
        reads += kind to snapshot
        preparationGate?.await()
        preparationFailure?.let { throw it }
        return snapshot.takeUnless { missingBusiness }
    }

    override suspend fun write(
        documentUri: String,
        prepared: PreparedReportPdf,
    ): ReportPdfWriteStatus {
        writes += documentUri to prepared
        writeGate?.await()
        writeFailure?.let { throw it }
        return writeStatus
    }
}
