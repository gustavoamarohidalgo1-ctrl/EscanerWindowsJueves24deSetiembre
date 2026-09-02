package com.facturastock.app.feature.summary

object PurchaseSummaryTestTags {
    const val SCREEN = "purchase_summary_screen"
    const val LIST = "purchase_summary_list"
    const val BLOCKERS = "purchase_summary_blockers"
    const val FINANCE = "purchase_summary_finance"
    const val ROUNDING = "purchase_summary_rounding"
    const val ADJUSTMENT_CONFIRMATION = "purchase_summary_adjustment_confirmation"
    const val ADJUSTMENT_REASON = "purchase_summary_adjustment_reason"
    const val PREPARE = "purchase_summary_prepare"
    const val PREPARED_CARD = "purchase_summary_prepared_card"
    const val PREPARED_LINES = "purchase_summary_prepared_lines"
    const val PREPARED_TOTALS = "purchase_summary_prepared_totals"
    const val WARNINGS = "purchase_summary_warnings"
    const val HASH = "purchase_summary_hash"
    const val REOPEN = "purchase_summary_reopen"
    const val REGISTER = "purchase_summary_register"
    const val FAILURE_BANNER = "purchase_summary_failure_banner"

    fun blocker(index: Int): String = "purchase_summary_blocker_$index"

    fun preparedLine(position: Int): String = "purchase_summary_line_$position"
}
