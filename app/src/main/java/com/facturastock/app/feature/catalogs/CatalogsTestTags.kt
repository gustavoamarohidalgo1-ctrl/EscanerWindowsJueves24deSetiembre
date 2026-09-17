package com.facturastock.app.feature.catalogs

object CatalogsTestTags {
    const val LIST = "catalogs:list"
    const val SEARCH = "catalogs:search"
    const val FORM = "catalogs:form"
    const val DETAIL = "catalogs:detail"
    const val ADD = "catalogs:add"
    const val STATUS = "catalogs:status"
    const val PRODUCT_SKU = "catalogs:product_sku"
    const val PRODUCT_BARCODE = "catalogs:product_barcode"
    const val PRODUCT_NAME = "catalogs:product_name"
    const val PRODUCT_QUANTITY = "catalogs:product_quantity"
    const val PRODUCT_PURCHASE_PRICE = "catalogs:product_purchase_price"
    const val PRODUCT_SALE_PRICE = "catalogs:product_sale_price"
    const val SAVE_FORM = "catalogs:save_form"

    fun tab(section: CatalogsContract.Section): String = "catalogs:tab:${section.name}"
    fun row(id: String): String = "catalogs:row:$id"
}
