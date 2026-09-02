package com.facturastock.app.data.repository

import java.util.Locale

internal fun catalogLikePattern(query: String): String {
    val normalized = query.trim().lowercase(Locale.ROOT)
    val escaped = normalized
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return "%$escaped%"
}

