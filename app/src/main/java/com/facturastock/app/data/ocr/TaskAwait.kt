package com.facturastock.app.data.ocr

import com.google.android.gms.tasks.Task
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** El resultado tardío de una Task nunca puede reanudar una coroutine ya cancelada. */
internal suspend fun <T> Task<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(DIRECT_EXECUTOR) { task ->
        when {
            task.isCanceled -> continuation.cancel(CancellationException("OCR task cancelled"))
            task.isSuccessful -> continuation.resume(task.result)
            else -> continuation.resumeWithException(
                task.exception ?: IllegalStateException("OCR task failed without cause"),
            )
        }
    }
}

private val DIRECT_EXECUTOR = Executor(Runnable::run)
