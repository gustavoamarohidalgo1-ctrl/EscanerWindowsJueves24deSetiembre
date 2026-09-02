package com.facturastock.app.ui.preview

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview

@Preview(
    name = "Claro",
    group = "Tema",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    uiMode = UI_MODE_NIGHT_NO,
)
@Preview(
    name = "Oscuro",
    group = "Tema",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    uiMode = UI_MODE_NIGHT_YES,
)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ThemePreviews

@Preview(
    name = "Fuente 200 %",
    group = "Accesibilidad",
    widthDp = 360,
    heightDp = 800,
    fontScale = 2f,
    showBackground = true,
)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class LargeFontPreview

@Preview(
    name = "Pantalla pequeña · fuente 200 %",
    group = "Accesibilidad",
    widthDp = 320,
    heightDp = 480,
    fontScale = 2f,
    showBackground = true,
)
@Preview(
    name = "Horizontal compacta · fuente 200 %",
    group = "Accesibilidad",
    widthDp = 640,
    heightDp = 320,
    fontScale = 2f,
    showBackground = true,
)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CompactAccessibilityPreviews
