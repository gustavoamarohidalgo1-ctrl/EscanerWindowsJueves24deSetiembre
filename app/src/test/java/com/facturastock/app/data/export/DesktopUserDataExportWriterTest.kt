package com.facturastock.app.data.export

import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.domain.model.UserDataExport
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exportación JSON al archivo elegido con "Guardar como". Reemplaza las pruebas del
 * `ContentProvider` de Android: el destino es un `file:` real y los fallos de escritura y de
 * limpieza se provocan con permisos del sistema de archivos.
 */
class DesktopUserDataExportWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var writer: DesktopUserDataExportWriter
    private lateinit var destinationDirectory: File
    private lateinit var destination: File
    private val lockedFiles = mutableListOf<File>()
    private val export = UserDataExport(
        exportedAt = Instant.parse("2026-08-21T12:00:00Z"),
        business = null,
        products = emptyList(),
        suppliers = emptyList(),
        units = emptyList(),
        inventoryLocations = emptyList(),
        supplierProductAliases = emptyList(),
        purchases = emptyList(),
        inventoryBalances = emptyList(),
        stockMovements = emptyList(),
        auditEvents = emptyList(),
    )

    @Before
    fun setUp() {
        destinationDirectory = tempFolder.newFolder("destination").canonicalFile
        destination = File(destinationDirectory, "export.json")
        writer = DesktopUserDataExportWriter(dispatchers = DefaultDispatcherProvider())
    }

    @After
    fun tearDown() {
        // Restituye permisos para que TemporaryFolder pueda borrar todo.
        lockedFiles.forEach { it.setWritable(true, false) }
    }

    @Test
    fun rejectsNonFileDestinationsWithoutClaimingSuccessOrTouchingDisk() {
        runBlocking {
            for (
                invalid in listOf(
                    "content://com.facturastock.app.export-test/export.json",
                    "https://example.invalid/export.json",
                    "export.json",
                    "",
                    File(destinationDirectory, "no-existe/export.json").toURI().toString(),
                    destinationDirectory.toURI().toString(),
                )
            ) {
                assertEquals(
                    invalid,
                    UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN,
                    writer.write(invalid, export),
                )
            }
            assertEquals(emptyList<String>(), destinationDirectory.list().orEmpty().toList())
        }
    }

    @Test
    fun writesAndClosesTheExactJsonIntoTheChosenFile() {
        runBlocking {
            val written = writer.write(destination.toURI().toString(), export)

            assertEquals(UserDataExportWriteStatus.WRITTEN, written)
            assertEquals(UserDataExportJson.serialize(export), destination.readText(Charsets.UTF_8))
            // El archivo quedó cerrado: se puede borrar y renombrar en cualquier sistema.
            assertTrue(destination.delete())
        }
    }

    @Test
    fun overwritingALongerPreviousExportLeavesNoTrailingBytes() {
        runBlocking {
            destination.writeText("x".repeat(64 * 1_024))

            assertEquals(
                UserDataExportWriteStatus.WRITTEN,
                writer.write(destination.toURI().toString(), export),
            )
            assertEquals(UserDataExportJson.serialize(export), destination.readText(Charsets.UTF_8))
        }
    }

    /** No se puede abrir el destino para escribir, pero sí retirarlo: queda limpio. */
    @Test
    fun failedWriteReportsCleanOnlyAfterRemovingTheTouchedDestination() {
        runBlocking {
            destination.writeText(PARTIAL_CONTENT)
            lockFile(destination)

            val result = writer.write(destination.toURI().toString(), export)

            assertEquals(UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN, result)
            assertFalse(destination.exists())
        }
    }

    @Test
    fun doubleFailureLeavesExplicitPartialWarningInsteadOfClaimingCleanup() {
        runBlocking {
            destination.writeText(PARTIAL_CONTENT)
            lockFile(destination)
            lockDirectoryEntries(destinationDirectory)

            val result = writer.write(destination.toURI().toString(), export)

            assertEquals(
                UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
                result,
            )
            assertEquals(PARTIAL_CONTENT, destination.readText(Charsets.UTF_8))
        }
    }

    /** La cancelación previa a abrir el destino se propaga y no crea ni altera el archivo. */
    @Test
    fun callerCancellationPropagatesWithoutTouchingTheDestination() {
        runBlocking {
            destination.writeText(PARTIAL_CONTENT)
            var failure: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException("usuario cerró la pantalla"))
                failure = runCatching { writer.write(destination.toURI().toString(), export) }
                    .exceptionOrNull()
            }
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(failure is CancellationException)
            assertEquals(PARTIAL_CONTENT, destination.readText(Charsets.UTF_8))
        }
    }

    private fun lockFile(file: File) {
        lockedFiles += file
        file.setWritable(false, false)
        assumeTrue("El sistema no permite proteger el archivo contra escritura", !file.canWrite())
    }

    /** Sin permiso de escritura en la carpeta no se pueden crear, borrar ni renombrar entradas. */
    private fun lockDirectoryEntries(directory: File) {
        lockedFiles += directory
        directory.setWritable(false, false)
        val probe = File(directory, "probe-${UUID.randomUUID()}")
        val canStillCreate = runCatching { probe.createNewFile() }.getOrDefault(false)
        if (canStillCreate) probe.delete()
        assumeTrue("El sistema (o root) ignora la protección de la carpeta", !canStillCreate)
    }

    private companion object {
        const val PARTIAL_CONTENT = "{\"partial\":"
    }
}
