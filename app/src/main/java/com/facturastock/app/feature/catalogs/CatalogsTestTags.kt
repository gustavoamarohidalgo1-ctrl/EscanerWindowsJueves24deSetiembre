package com.facturastock.app.feature.catalogs

object CatalogsTestTags {
    const val LIST = "catalogs:list"
    const val SEARCH = "catalogs:search"
    const val FORM = "catalogs:form"
    const val DETAIL = "catalogs:detail"
    const val ADD = "catalogs:add"
    const val STATUS = "catalogs:status"

    fun tab(section: CatalogsContract.Section): String = "catalogs:tab:${section.name}"
    fun row(id: String): String = "catalogs:row:$id"
}
