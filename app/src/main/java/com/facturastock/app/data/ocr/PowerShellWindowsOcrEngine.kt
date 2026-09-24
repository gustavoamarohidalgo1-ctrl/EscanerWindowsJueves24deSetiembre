package com.facturastock.app.data.ocr

import com.facturastock.app.domain.error.OcrError
import com.facturastock.app.domain.error.OcrException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Motor OCR crudo: recibe imágenes ya validadas y devuelve el JSON de [WindowsOcrScript]. */
internal fun interface WindowsOcrEngine {
    /** Falla rápido, antes de leer imágenes, si el motor no puede existir en este equipo. */
    fun ensureAvailable() = Unit

    /**
     * @param images rutas absolutas, en orden de página.
     * @param workDirectory carpeta temporal exclusiva de esta ejecución (la borra el llamador).
     * @throws OcrException con [OcrError.ModelUnavailable] si el motor no existe en el equipo.
     */
    suspend fun recognize(images: List<File>, workDirectory: File): String
}

/**
 * Ejecuta [WindowsOcrScript] con Windows PowerShell 5.1. Un solo proceso atiende todo el lote
 * (arrancar PowerShell y cargar WinRT cuesta más que reconocer una página). Si la coroutine se
 * cancela o vence el plazo, se destruye el proceso (y sus hijos) de inmediato.
 */
internal class PowerShellWindowsOcrEngine(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val powerShell: () -> String = ::defaultPowerShellExecutable,
) : WindowsOcrEngine {
    override fun ensureAvailable() {
        if (!osName.startsWith("Windows", ignoreCase = true)) {
            throw OcrException(OcrError.ModelUnavailable)
        }
    }

    override suspend fun recognize(images: List<File>, workDirectory: File): String {
        ensureAvailable()
        val script = File(workDirectory, WindowsOcrScript.FILE_NAME)
        val request = File(workDirectory, "request.txt")
        val result = File(workDirectory, "result.json")
        val stdout = File(workDirectory, "stdout.txt")
        val stderr = File(workDirectory, "stderr.txt")
        script.writeText(WindowsOcrScript.CONTENT, Charsets.US_ASCII)
        request.writeText(images.joinToString("\n", postfix = "\n") { it.absolutePath }, Charsets.UTF_8)

        val command = listOf(
            powerShell(),
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.absolutePath,
            "-RequestPath",
            request.absolutePath,
            "-OutputPath",
            result.absolutePath,
        )
        val process = try {
            ProcessBuilder(command)
                .directory(workDirectory)
                // Salidas a archivo: una tubería sin leer podría bloquear al proceso hijo.
                .redirectOutput(stdout)
                .redirectError(stderr)
                .start()
        } catch (failure: IOException) {
            // powershell.exe no existe o no se puede ejecutar en este equipo.
            throw OcrException(OcrError.ModelUnavailable, failure)
        }
        try {
            // El script no lee stdin; cerrarlo evita que PowerShell espere entrada.
            process.outputStream.close()
            val exitCode = withTimeoutOrNull(timeoutMillis(images.size)) { process.awaitExit() }
                ?: throw OcrException(OcrError.RecognitionFailed, IOException("Windows OCR excedió el tiempo límite"))
            when (exitCode) {
                WindowsOcrScript.EXIT_OK -> Unit
                WindowsOcrScript.EXIT_NO_OCR_LANGUAGE,
                WindowsOcrScript.EXIT_WINRT_UNAVAILABLE,
                -> throw OcrException(OcrError.ModelUnavailable, IOException("Windows OCR no disponible ($exitCode)"))
                // Sin detalles de stderr: podría contener rutas de la factura.
                else -> throw OcrException(OcrError.RecognitionFailed, IOException("Windows OCR terminó con código $exitCode"))
            }
            if (!result.isFile) {
                throw OcrException(OcrError.RecognitionFailed, IOException("Windows OCR no produjo resultado"))
            }
            return result.readText(Charsets.UTF_8)
        } finally {
            destroy(process)
        }
    }

    private suspend fun Process.awaitExit(): Int = suspendCancellableCoroutine { continuation ->
        val exit = onExit()
        exit.whenComplete { finished, failure ->
            if (failure != null) {
                continuation.resumeWithException(failure)
            } else {
                continuation.resume(finished.exitValue())
            }
        }
        continuation.invokeOnCancellation {
            exit.cancel(false)
            // Sin esperas aquí: el manejador corre en el hilo que cancela. El `finally` de
            // [recognize] espera a que el proceso termine.
            destroyNow(this)
        }
    }

    private fun timeoutMillis(pages: Int): Long = BASE_TIMEOUT_MILLIS + PER_PAGE_TIMEOUT_MILLIS * pages

    private companion object {
        const val BASE_TIMEOUT_MILLIS = 60_000L
        const val PER_PAGE_TIMEOUT_MILLIS = 30_000L
        const val DESTROY_WAIT_SECONDS = 5L

        fun destroyNow(process: Process) {
            if (!process.isAlive) return
            process.descendants().forEach { child -> child.destroyForcibly() }
            process.destroyForcibly()
        }

        fun destroy(process: Process) {
            if (!process.isAlive) return
            destroyNow(process)
            // Windows mantiene bloqueados los archivos del proceso: esperar un poco antes de
            // que el llamador borre la carpeta de trabajo.
            try {
                process.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /** Ruta absoluta de Windows PowerShell 5.1 (evita que un `pwsh` o un alias en PATH la tape). */
        fun defaultPowerShellExecutable(): String {
            val systemRoot = System.getenv("SystemRoot")?.takeIf(String::isNotBlank)
                ?: System.getenv("WINDIR")?.takeIf(String::isNotBlank)
                ?: "C:\\Windows"
            val candidate = File(systemRoot, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
            return if (candidate.isFile) candidate.absolutePath else "powershell.exe"
        }
    }
}
