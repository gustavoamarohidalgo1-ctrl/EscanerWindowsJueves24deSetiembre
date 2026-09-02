package com.facturastock.app.feature.debtors

object DebtorsTestTags {
    const val LIST_SCREEN = "debtors_list_screen"
    const val NEW_DEBT = "debtors_new_debt"
    const val SEARCH = "debtors_search"
    const val FILTER_OPEN = "debtors_filter_open"
    const val FILTER_PAID = "debtors_filter_paid"
    const val FILTER_ALL = "debtors_filter_all"
    const val EMPTY = "debtors_empty"
    const val TOTAL_BALANCE = "debtors_total_balance"
    const val DETAIL_SCREEN = "debt_detail_screen"
    const val PAYMENT = "debt_detail_payment"
    const val PAYMENT_DIALOG = "debt_payment_dialog"
    const val PAYMENT_AMOUNT = "debt_payment_amount"
    const val PAYMENT_NOTE = "debt_payment_note"
    const val PAYMENT_REFERENCE = "debt_payment_reference"

    fun debt(debtId: String) = "debtors_debt_$debtId"
    fun paymentMethod(method: String) = "debt_payment_method_$method"
}
