package com.facturastock.app.domain.model

enum class FeatureArea {
    HOME,
    PRODUCTS,
    PURCHASES,
    INVENTORY,
    SETTINGS,
}

data class FeatureSnapshot(
    val area: FeatureArea,
    val itemCount: Long = 0L,
    val pendingCount: Long = 0L,
) {
    init {
        require(itemCount >= 0L)
        require(pendingCount >= 0L)
    }
}
