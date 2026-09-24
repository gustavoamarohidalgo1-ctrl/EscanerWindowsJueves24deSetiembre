package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.ReportPdfRepository
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

/** Lee una instantánea coherente sin alterar ventas, deudas, pagos ni inventario. */
class RoomReportPdfRepository
    @Inject
    constructor(
        private val database: FacturaStockDatabase,
        private val dispatchers: DispatcherProvider,
    ) : ReportPdfRepository {
        override suspend fun readSnapshot(
            businessId: BusinessId,
            range: SalesReportRange,
            generatedAt: Instant,
            primaryCurrency: CurrencyCode,
            kind: ReportPdfKind,
        ): ReportPdfSnapshot? =
            withContext(dispatchers.io) {
                storageCatching {
                    database.withTransaction {
                        val business =
                            database
                                .businessDao()
                                .findById(businessId.value)
                                ?.takeIf { it.status == CatalogStatus.ACTIVE.name }
                                ?: return@withTransaction null
                        val saleDao = database.saleDao()
                        val sales =
                            if (kind == ReportPdfKind.DAILY_SALES_WITH_DEBTORS) {
                                val firstPage =
                                    saleDao.listPostedProfitPageKeys(
                                        businessId.value,
                                        range.startInclusive.toEpochMilli(),
                                        range.endExclusive.toEpochMilli(),
                                        null,
                                        null,
                                        POSTED_PROFIT_PAGE_SIZE,
                                    )
                                loadPostedSaleProfitsPaged(
                                    firstPage = firstPage,
                                    pageSize = POSTED_PROFIT_PAGE_SIZE,
                                    loadNextPage = { cursor, limit ->
                                        saleDao.listPostedProfitPageKeys(
                                            businessId.value,
                                            range.startInclusive.toEpochMilli(),
                                            range.endExclusive.toEpochMilli(),
                                            cursor.postedAt,
                                            cursor.saleId,
                                            limit,
                                        )
                                    },
                                    loadRows = { saleIds -> saleDao.listPostedProfitRowsForSales(businessId.value, saleIds) },
                                )
                            } else {
                                emptyList()
                            }
                        val debtDao = database.debtDao()
                        // Los pendientes no usan el rango de ventas: incluyen cualquier fecha y moneda.
                        val debts = debtDao.listSummaries(businessId.value, onlyOpen = true).map { it.toDomain() }
                        val debtorNames =
                            buildMap {
                                // Limita parámetros SQL y evita cargar todos los créditos pagados históricos.
                                sales.chunked(POSTED_PROFIT_PAGE_SIZE).forEach { page ->
                                    debtDao
                                        .listDebtorNamesForSales(businessId.value, page.map { it.saleId.value })
                                        .forEach { row -> put(checkNotNull(SaleId.parse(row.saleId)), row.debtorName) }
                                }
                            }
                        ReportPdfSnapshot(
                            businessId = businessId,
                            businessName = business.tradeName ?: business.legalName,
                            generatedAt = generatedAt,
                            range = range,
                            primaryCurrency = primaryCurrency,
                            sales = sales,
                            debts = debts,
                            saleDebtorNames = debtorNames,
                            debtPayments =
                                if (kind == ReportPdfKind.DAILY_SALES_WITH_DEBTORS) {
                                    debtDao
                                        .listPaymentsInRange(
                                            businessId.value,
                                            range.startInclusive.toEpochMilli(),
                                            range.endExclusive.toEpochMilli(),
                                        ).map { it.toDomain() }
                                } else {
                                    emptyList()
                                },
                        )
                    }
                }
            }
    }
