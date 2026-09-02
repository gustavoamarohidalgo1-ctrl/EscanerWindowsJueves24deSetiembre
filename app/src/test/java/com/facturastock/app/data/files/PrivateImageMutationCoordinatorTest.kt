package com.facturastock.app.data.files

import com.facturastock.app.domain.model.id.DraftId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateImageMutationCoordinatorTest {
    @Test
    fun `ruta normalizada y draft explicito comparten la misma exclusion`() = runTest {
        val coordinator = PrivateImageMutationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.withDraftLock(DRAFT_ID) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.withRelativePathLock(
                "draft_images//${DRAFT_ID.value}/ocr/../page.jpg",
            ) {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 77L))
    }
}
