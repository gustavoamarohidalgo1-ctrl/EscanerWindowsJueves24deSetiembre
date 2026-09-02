package com.facturastock.app.navigation

import java.net.URI
import java.net.URISyntaxException

sealed interface InternalDeepLinkTarget {
    data class PurchaseDetail(val purchaseId: PurchaseId) : InternalDeepLinkTarget
}

object InternalDeepLinks {
    private const val MAX_URI_LENGTH = 128

    const val PURCHASE_DETAIL_PATTERN =
        "facturastock://internal/purchases/{${AppRoutes.PURCHASE_ID}}"

    fun purchaseDetail(purchaseId: PurchaseId): String =
        "facturastock://internal/purchases/${purchaseId.value}"

    fun resolve(rawValue: String): InternalDeepLinkTarget? {
        if (rawValue.length > MAX_URI_LENGTH) {
            return null
        }
        val uri = try {
            URI(rawValue)
        } catch (_: URISyntaxException) {
            return null
        }
        if (
            uri.scheme != "facturastock" ||
            uri.rawAuthority != "internal" ||
            uri.userInfo != null ||
            uri.port != -1 ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            return null
        }

        val rawPath = uri.rawPath ?: return null
        val idValue = rawPath.removePrefix(PURCHASE_PATH_PREFIX)
        if (idValue == rawPath) {
            return null
        }
        val purchaseId = PurchaseId.parse(idValue) ?: return null
        if (rawPath != "$PURCHASE_PATH_PREFIX${purchaseId.value}") {
            return null
        }
        return InternalDeepLinkTarget.PurchaseDetail(purchaseId)
    }

    private const val PURCHASE_PATH_PREFIX = "/purchases/"
}
