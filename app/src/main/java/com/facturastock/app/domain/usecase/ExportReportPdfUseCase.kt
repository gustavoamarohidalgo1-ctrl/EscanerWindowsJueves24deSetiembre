package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfPreparation
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ReportPdfRepository
import com.facturastock.app.domain.repository.ReportPdfWriter
import kotlinx.coroutines.CancellationException

/** El día se fija al pulsar exportar; el selector de destino no cambia esa instantánea. */
class ExportReportPdfUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: ReportPdfRepository,
    private val writer: ReportPdfWriter,
    private val clock: AppClock,
) {
    suspend fun prepare(kind: ReportPdfKind): ReportPdfPreparation =
        try {
            val context = configuration.current()
            val businessId = context.activeBusinessId
            if (businessId == null) {
                ReportPdfPreparation.NoActiveBusiness
            } else {
                val generatedAt = clock.now()
                val range = currentSalesReportRange(SalesReportPeriod.DAY, generatedAt, context.zoneId)
                val snapshot = repository.readSnapshot(businessId, range, generatedAt, context.currency, kind)
                val current = configuration.current()
                when {
                    current.activeBusinessId != businessId || current.zoneId != context.zoneId || current.currency != context.currency -> {
                        ReportPdfPreparation.ContextChanged
                    }

                    snapshot == null -> {
                        ReportPdfPreparation.NoActiveBusiness
                    }

                    snapshot.businessId != businessId || snapshot.range != range || snapshot.generatedAt != generatedAt ||
                        snapshot.primaryCurrency != context.currency -> {
                        ReportPdfPreparation.Failed
                    }

                    else -> {
                        val date = range.startInclusive.atZone(range.zoneId).toLocalDate()
                        val prefix =
                            when (kind) {
                                ReportPdfKind.DAILY_SALES_WITH_DEBTORS -> "ventas-del-dia"
                                ReportPdfKind.DEBTORS -> "deudores-pendientes"
                            }
                        ReportPdfPreparation.Ready(PreparedReportPdf(kind, snapshot, "$prefix-$date.pdf"))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReportPdfPreparation.Failed
        }

    suspend fun write(
        uri: String,
        prepared: PreparedReportPdf,
    ): ReportPdfWriteStatus {
        val current =
            try {
                configuration.current()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN
            }
        if (!current.matches(prepared.snapshot)) return ReportPdfWriteStatus.CONTEXT_CHANGED
        if (uri.isBlank()) return ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN
        return try {
            writer.write(uri, prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // El adaptador pudo haber abierto el destino antes de fallar: no anunciarlo vacío.
            ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
        }
    }

    private fun AppConfiguration.matches(snapshot: ReportPdfSnapshot): Boolean = activeBusinessId == snapshot.businessId && zoneId == snapshot.range.zoneId && currency == snapshot.primaryCurrency
}
