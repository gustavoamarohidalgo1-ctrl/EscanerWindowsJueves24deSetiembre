package com.facturastock.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * Las facturas pueden contener PII. Coil decodifica la fuente solicitada, pero nunca conserva
 * otra copia en sus caches de memoria, disco o red después de que abandona la composición.
 */
@Composable
fun sensitiveImageRequest(data: Any): ImageRequest {
    val context = LocalContext.current
    return remember(context, data) {
        ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .build()
    }
}
