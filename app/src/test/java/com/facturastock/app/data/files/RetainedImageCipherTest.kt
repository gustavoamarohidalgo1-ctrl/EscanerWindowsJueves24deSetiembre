package com.facturastock.app.data.files

import com.facturastock.app.core.platform.AppDirectories
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cifrado en reposo con la clave de archivo de escritorio (`no_backup/keys`): ida y vuelta,
 * idempotencia, detección de formato y rechazo de contenido manipulado (tag GCM).
 */
class RetainedImageCipherTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directories: AppDirectories
    private lateinit var cipher: RetainedImageCipher
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        // Cada prueba usa su propia raíz: la clave generada desaparece con TemporaryFolder.
        directories = AppDirectories(tempFolder.newFolder("app").canonicalFile)
        cipher = RetainedImageCipher(directories)
        sandbox = File(directories.cacheDir, "cipher-test").apply { mkdirs() }
    }

    private val keyFile: File
        get() = File(directories.noBackupFilesDir, "keys/retained-image.key")

    @Test
    fun encryptThenDecryptRoundTripsTheOriginalBytes() {
        val file = writeFile("invoice.jpg", CONTENT)

        assertTrue(cipher.encrypt(file))
        assertTrue(cipher.isEncrypted(file))
        assertEquals(
            RetainedImageCipher.MigrationState.ENVELOPED,
            cipher.migrationState(file),
        )
        // El contenido en disco ya no es el original.
        assertFalse(file.readBytes().contentEquals(CONTENT))
        assertArrayEquals(CONTENT, cipher.decrypt(file))
    }

    @Test
    fun encryptBytesPublishesOnlyCiphertextWithoutPlaintextTemp() {
        val destination = File(sandbox, "prepared-upload.fse")

        assertTrue(cipher.encryptBytesToFile(CONTENT.copyOf(), destination))

        assertEquals(listOf(destination.name), sandbox.listFiles().orEmpty().map(File::getName))
        assertFalse(destination.readBytes().contentEquals(CONTENT))
        assertArrayEquals(CONTENT, cipher.decrypt(destination))
    }

    @Test
    fun encryptIsIdempotentAndKeepsTheSameCiphertext() {
        val file = writeFile("invoice.jpg", CONTENT)

        assertTrue(cipher.encrypt(file))
        val firstCiphertext = file.readBytes()
        assertTrue(cipher.encrypt(file))

        assertArrayEquals(firstCiphertext, file.readBytes())
        assertArrayEquals(CONTENT, cipher.decrypt(file))
    }

    @Test
    fun retryAfterPublishedFileSyncFailureAuthenticatesAndReplaysDurability() {
        assertPublishedEnvelopeRetry(PublicationFailurePoint.FILE)
    }

    @Test
    fun retryAfterPublishedParentSyncFailureAuthenticatesAndReplaysDurability() {
        assertPublishedEnvelopeRetry(PublicationFailurePoint.PARENT)
    }

    @Test
    fun concurrentFirstUseCreatesOneKeyAndNeverDoubleEncrypts() = runBlocking {
        val file = writeFile("concurrent.jpg", ByteArray(512 * 1024) { (it % 251).toByte() })

        val results = List(8) {
            async(Dispatchers.Default) { cipher.encrypt(file) }
        }.awaitAll()

        assertTrue(results.all { it })
        assertTrue(cipher.isEncrypted(file))
        assertArrayEquals(ByteArray(512 * 1024) { (it % 251).toByte() }, cipher.decrypt(file))
        // Una sola clave publicada y ningún temporal de clave residual.
        assertEquals(32L, keyFile.length())
        assertEquals(
            listOf(keyFile.name),
            keyFile.parentFile.listFiles().orEmpty().map(File::getName),
        )
    }

    /**
     * Sustituto de escritorio del AndroidKeyStore: la clave es un archivo privado de 256 bits
     * que se reutiliza entre instancias y, donde hay POSIX, solo lo lee el propietario.
     */
    @Test
    fun keyFileIsPrivateAndSharedByEveryCipherOfTheSameRoot() {
        val file = writeFile("shared-key.jpg", CONTENT)
        assertTrue(cipher.encrypt(file))

        assertEquals(32L, keyFile.length())
        assertArrayEquals(CONTENT, RetainedImageCipher(directories).decrypt(file))
        val otherRoot = AppDirectories(tempFolder.newFolder("other-app").canonicalFile)
        assertNull(RetainedImageCipher(otherRoot).decrypt(file))

        val posix = runCatching {
            Files.getPosixFilePermissions(keyFile.toPath())
        }.getOrNull()
        assumeTrue("El sistema de archivos no expone permisos POSIX", posix != null)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            posix,
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(keyFile.parentFile.toPath()),
        )
    }

    @Test
    fun lostKeyFileMakesPreviousEnvelopesUnreadableWithoutReusingStaleKeys() {
        val file = writeFile("lost-key.jpg", CONTENT)
        assertTrue(cipher.encrypt(file))
        val envelope = file.readBytes()

        assertTrue(keyFile.delete())

        // Igual que al perder la entrada del Keystore: el envelope sigue siendo estructural,
        // pero ya no autentica y jamás se sobrescribe.
        assertEquals(RetainedImageCipher.EnvelopeState.CORRUPT, cipher.inspect(file))
        assertNull(cipher.decrypt(file))
        assertFalse(cipher.encrypt(file))
        assertArrayEquals(envelope, file.readBytes())
    }

    @Test
    fun plaintextFilesAreNotMarkedAsEncrypted() {
        val file = writeFile("plain.jpg", CONTENT)

        assertFalse(cipher.isEncrypted(file))
        assertEquals(RetainedImageCipher.EnvelopeState.PLAINTEXT, cipher.inspect(file))
        assertEquals(
            RetainedImageCipher.MigrationState.PLAINTEXT,
            cipher.migrationState(file),
        )
        // Descifrar texto en claro devuelve null, nunca basura.
        assertNull(cipher.decrypt(file))
    }

    @Test
    fun tamperedCiphertextFailsAuthenticationAndReturnsNull() {
        val file = writeFile("invoice.jpg", CONTENT)
        assertTrue(cipher.encrypt(file))
        val bytes = file.readBytes()
        // Corrompe un byte del ciphertext (tras magic + IV).
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertFalse(cipher.isEncrypted(file))
        assertEquals(RetainedImageCipher.EnvelopeState.CORRUPT, cipher.inspect(file))
        // El sondeo de migración es deliberadamente estructural; la lectura sí autenticó y
        // rechazó el tag. Así el worker no vuelve a descifrar el mismo histórico cada día.
        assertEquals(
            RetainedImageCipher.MigrationState.ENVELOPED,
            cipher.migrationState(file),
        )
        assertFalse(cipher.encrypt(file))
        assertArrayEquals(bytes, file.readBytes())
        assertNull(cipher.decrypt(file))
    }

    @Test
    fun durableDecryptionAuthenticatesOnceAndNeverSyncsTamperedCiphertext() {
        val file = writeFile("durable.fse", CONTENT)
        assertTrue(cipher.encrypt(file))
        val durability = CountingPublicationDurability()
        val durableCipher = RetainedImageCipher(directories, durability)

        val authenticated = durableCipher.decryptAndSync(file, CONTENT.size)

        assertTrue(authenticated is RetainedImageCipher.DurableDecryption.Success)
        authenticated as RetainedImageCipher.DurableDecryption.Success
        assertArrayEquals(CONTENT, authenticated.plaintext)
        authenticated.plaintext.fill(0)
        assertEquals(1, durability.fileSyncAttempts)
        assertEquals(1, durability.parentSyncAttempts)

        RandomAccessFile(file, "rw").use { changed ->
            changed.seek(file.length() - 1L)
            val original = changed.readByte().toInt()
            changed.seek(file.length() - 1L)
            changed.writeByte(original xor 0x01)
        }

        assertEquals(
            RetainedImageCipher.DurableDecryption.AuthenticationFailed,
            durableCipher.decryptAndSync(file, CONTENT.size),
        )
        assertEquals(1, durability.fileSyncAttempts)
        assertEquals(1, durability.parentSyncAttempts)
    }

    @Test
    fun malformedEnvelopeIsCorruptAndEncryptDoesNotOverwriteIt() {
        val malformed = byteArrayOf(0x46, 0x53, 0x45, 0x31, 1, 2, 3)
        val file = writeFile("truncated.fse", malformed)

        assertEquals(RetainedImageCipher.EnvelopeState.CORRUPT, cipher.inspect(file))
        assertEquals(
            RetainedImageCipher.MigrationState.CORRUPT,
            cipher.migrationState(file),
        )
        assertFalse(cipher.isEncrypted(file))
        assertFalse(cipher.encrypt(file))
        assertArrayEquals(malformed, file.readBytes())
    }

    @Test
    fun missingFilesAreSafeNoOps() {
        val missing = File(sandbox, "no-existe.jpg")

        assertFalse(cipher.isEncrypted(missing))
        assertEquals(RetainedImageCipher.EnvelopeState.UNAVAILABLE, cipher.inspect(missing))
        assertEquals(
            RetainedImageCipher.MigrationState.ABSENT,
            cipher.migrationState(missing),
        )
        assertFalse(cipher.encrypt(missing))
        assertNull(cipher.decrypt(missing))
    }

    @Test
    fun emptyPlaintextIsRejectedWithoutPublishingAnEnvelope() {
        val empty = writeFile("empty.jpg", byteArrayOf())
        val destination = File(sandbox, "empty.fse")

        assertFalse(cipher.encrypt(empty))
        assertFalse(cipher.encryptBytesToFile(byteArrayOf(), destination))
        assertEquals(0L, empty.length())
        assertFalse(destination.exists())
    }

    @Test
    fun oversizedEnvelopeIsRejectedFromLengthBeforeAllocatingItsPayload() {
        val oversized = writeFile("oversized.fse", byteArrayOf(0x46, 0x53, 0x45, 0x31))
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(1_024L + RetainedImageCipher.ENVELOPE_OVERHEAD_BYTES + 1L)
        }

        assertEquals(RetainedImageCipher.MigrationState.ENVELOPED, cipher.migrationState(oversized))
        assertNull(cipher.decrypt(oversized, maxPlaintextBytes = 1_024))
    }

    private fun writeFile(name: String, content: ByteArray): File =
        File(sandbox, name).apply { writeBytes(content) }

    private fun assertPublishedEnvelopeRetry(failurePoint: PublicationFailurePoint) {
        val durability = FailOncePublicationDurability(failurePoint)
        val retryingCipher = RetainedImageCipher(directories, durability)
        val file = writeFile("retry-${failurePoint.name.lowercase()}.jpg", CONTENT)

        assertFalse(retryingCipher.encrypt(file))
        assertEquals(
            RetainedImageCipher.MigrationState.ENVELOPED,
            retryingCipher.migrationState(file),
        )
        assertArrayEquals(CONTENT, retryingCipher.decrypt(file))
        val publishedEnvelope = file.readBytes()

        assertTrue(retryingCipher.encrypt(file))

        assertArrayEquals(publishedEnvelope, file.readBytes())
        assertArrayEquals(CONTENT, retryingCipher.decrypt(file))
        assertEquals(2, durability.fileSyncAttempts)
        assertEquals(
            if (failurePoint == PublicationFailurePoint.FILE) 1 else 2,
            durability.parentSyncAttempts,
        )
    }

    private enum class PublicationFailurePoint { FILE, PARENT }

    private class CountingPublicationDurability : PrivatePublicationDurability() {
        var fileSyncAttempts: Int = 0
            private set
        var parentSyncAttempts: Int = 0
            private set

        override fun syncFile(file: File) {
            fileSyncAttempts++
            super.syncFile(file)
        }

        override fun syncParentAfterRename(destination: File) {
            parentSyncAttempts++
            super.syncParentAfterRename(destination)
        }
    }

    private class FailOncePublicationDurability(
        private val failurePoint: PublicationFailurePoint,
    ) : PrivatePublicationDurability() {
        var fileSyncAttempts: Int = 0
            private set
        var parentSyncAttempts: Int = 0
            private set

        override fun syncFile(file: File) {
            fileSyncAttempts += 1
            if (failurePoint == PublicationFailurePoint.FILE && fileSyncAttempts == 1) {
                throw IOException("injected file sync failure")
            }
            super.syncFile(file)
        }

        override fun syncParentAfterRename(destination: File) {
            parentSyncAttempts += 1
            if (failurePoint == PublicationFailurePoint.PARENT && parentSyncAttempts == 1) {
                throw IOException("injected parent sync failure")
            }
            super.syncParentAfterRename(destination)
        }
    }

    private companion object {
        val CONTENT: ByteArray = ByteArray(2_048) { index -> (index % 251).toByte() }
    }
}
