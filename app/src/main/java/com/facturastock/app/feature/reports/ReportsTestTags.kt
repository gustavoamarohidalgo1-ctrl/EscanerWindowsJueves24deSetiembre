package com.facturastock.app.feature.reports

object ReportsTestTags {
    const val SCREEN = "reports_screen"
    const val PERIOD_SELECTOR = "reports_period_selector"
    const val PERIOD_DAY = "reports_period_day"
    const val PERIOD_WEEK = "reports_period_week"
    const val PERIOD_MONTH = "reports_period_month"
    const val RANGE = "reports_range"
    const val LOADING = "reports_loading"
    const val ERROR = "reports_error"
    const val STALE_NOTICE = "reports_stale_notice"
    const val HERO = "reports_profit_hero"
    const val PROFIT_UNAVAILABLE = "reports_profit_unavailable"
    const val METRICS = "reports_metrics"
    const val OTHER_CURRENCIES = "reports_other_currencies"
    const val EMPTY = "reports_empty"
    const val SALES_LIST = "reports_sales_list"

    fun sale(saleId: String): String = "reports_sale_$saleId"
}
