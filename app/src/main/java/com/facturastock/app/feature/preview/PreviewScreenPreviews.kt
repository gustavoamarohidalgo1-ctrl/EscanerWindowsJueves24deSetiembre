package com.facturastock.app.feature.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID

@ThemePreviews
@Composable
private fun PreviewScreenViewingPreview() {
    FacturaStockTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PreviewScreen(
                state = fakeState(),
                onAction = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PreviewScreenCroppingPreview() {
    FacturaStockTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PreviewScreen(
                state = fakeState(
                    mode = PreviewContract.Mode.CROPPING,
                    cropDraft = ImageCrop(1_000, 2_000, 9_000, 8_000),
                ),
                onAction = {},
            )
        }
    }
}

@LargeFontPreview
@Composable
private fun PreviewScreenLargeFontPreview() {
    FacturaStockTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PreviewScreen(
                state = fakeState(),
                onAction = {},
            )
        }
    }
}

private fun fakeState(
    mode: PreviewContract.Mode = PreviewContract.Mode.VIEWING,
    cropDraft: ImageCrop? = null,
): PreviewContract.State = PreviewContract.State(
    draftId = DraftId.from(UUID.fromString("00000000-0000-4000-8000-0000000000d1")),
    pages = listOf(
        PreviewContract.Page(
            imageId = ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a1")),
            filePath = "draft_images/preview/p1.jpg",
            widthPx = 3_000,
            heightPx = 4_000,
            rotationDegrees = 0,
            crop = null,
            pageIndex = 0,
        ),
        PreviewContract.Page(
            imageId = ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a2")),
            filePath = "draft_images/preview/p2.jpg",
            widthPx = 3_000,
            heightPx = 4_000,
            rotationDegrees = 90,
            crop = ImageCrop(500, 1_000, 9_500, 9_000),
            pageIndex = 1,
        ),
    ),
    currentIndex = 0,
    mode = mode,
    cropDraft = cropDraft,
)
