package com.facturastock.app.data.files

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cifrado en reposo de las imágenes retenidas (fotos de compras confirmadas). AES/GCM de 256
 * bits con clave no exportable generada en AndroidKeyStore (alias [KEY_ALIAS]). La clave nunca
 * sale del Keystore; que esté respaldada por hardware/TEE depende de las capacidades concretas
 * del dispositivo y no se presupone. Solo `javax.crypto` interviene, sin dependencias nuevas.
 *
 * Formato del archivo cifrado: `[4B magic "FSE1"][12B IV][ciphertext + tag GCM de 128 bits]`.
 * La cabecera identifica la versión, pero no basta para declarar un archivo protegido: la
 * inspección valida tamaño, nonce fijo y tag GCM antes de devolver [EnvelopeState.ENCRYPTED].
 *
 * Todas las operaciones son de mejor esfuerzo y devuelven false/null ante cualquier fallo (el
 * llamador reintenta en la próxima pasada de mantenimiento); el archivo original jamás queda
 * truncado: el cifrado escribe en un temporal hermano y lo mueve atómicamente sobre el destino.
 * Nunca se registra contenido ni rutas.
 */
@Singleton
class RetainedImageCipher @Inject constructor(
    private val publicationDurability: PrivatePublicationDurability,
) {
    constructor() : this(PrivatePublicationDurability())

    enum class EnvelopeState {
        PLAINTEXT,
        ENCRYPTED,
        CORRUPT,
        UNAVAILABLE,
    }

    /** Sondeo estructural O(1) para migración; no sustituye la autenticación de [inspect]. */
    enum class MigrationState {
        PLAINTEXT,
        ENVELOPED,
        ABSENT,
        CORRUPT,
        UNAVAILABLE,
    }

    /** Resultado cerrado de autenticar y repetir las barreras de una publicación previa. */
    internal sealed interface DurableDecryption {
        data class Success(val plaintext: ByteArray) : DurableDecryption
        data object AuthenticationFailed : DurableDecryption
        data object DurabilityFailed : DurableDecryption
    }

    /**
     * Lee como máximo cuatro bytes y el tamaño del archivo. Un envelope estructuralmente válido
     * se considera ya migrado, pero sus bytes solo se entregan después de [decrypt]/[inspect].
     */
    fun migrationState(file: File): MigrationState {
        if (!file.exists()) return MigrationState.ABSENT
        if (!file.isFile) return MigrationState.CORRUPT
        val length = try {
            file.length()
        } catch (_: Exception) {
            return MigrationState.UNAVAILABLE
        }
        val header = ByteArray(MAGIC.size)
        val read = try {
            file.inputStream().use { input ->
                var total = 0
                while (total < header.size) {
                    val count = input.read(header, total, header.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                total
            }
        } catch (_: Exception) {
            return MigrationState.UNAVAILABLE
        }
        return try {
            val hasFormatPrefix = read >= FORMAT_PREFIX.size &&
                header.regionMatches(FORMAT_PREFIX, FORMAT_PREFIX.size)
            when {
                !hasFormatPrefix -> MigrationState.PLAINTEXT
                read < MAGIC.size || !header.contentEquals(MAGIC) ||
                    length < MIN_ENVELOPE_BYTES -> MigrationState.CORRUPT
                else -> MigrationState.ENVELOPED
            }
        } finally {
            header.fill(0)
        }
    }

    /**
     * Inspección autenticada. Un prefijo `FSE` con versión/tamaño/tag inválido es CORRUPT y no
     * vuelve a tratarse como plaintext; así el mantenimiento nunca lo sobrescribe al migrar.
     */
    fun inspect(file: File): EnvelopeState = withFileLock(file) { inspectLocked(file) }

    private fun inspectLocked(file: File): EnvelopeState = when (migrationState(file)) {
        MigrationState.PLAINTEXT -> EnvelopeState.PLAINTEXT
        MigrationState.ABSENT,
        MigrationState.UNAVAILABLE,
        -> EnvelopeState.UNAVAILABLE
        MigrationState.CORRUPT -> EnvelopeState.CORRUPT
        MigrationState.ENVELOPED -> {
            if (!hasSupportedStoredLength(file, MAX_AUTHENTICATED_PLAINTEXT_BYTES)) {
                EnvelopeState.UNAVAILABLE
            } else {
                val plaintext = decryptLocked(file, MAX_AUTHENTICATED_PLAINTEXT_BYTES)
                if (plaintext == null) {
                    EnvelopeState.CORRUPT
                } else {
                    plaintext.fill(0)
                    EnvelopeState.ENCRYPTED
                }
            }
        }
    }

    /** true solo si el envelope completo autentica; la cabecera por sí sola no alcanza. */
    fun isEncrypted(file: File): Boolean = inspect(file) == EnvelopeState.ENCRYPTED

    /**
     * Cifra [file] en sitio. Idempotente: un archivo ya cifrado devuelve true sin reescribirse.
     * Devuelve false si el archivo no existe o el cifrado no pudo completarse; en ese caso el
     * original queda intacto.
     */
    fun encrypt(file: File): Boolean = withFileLock(file) locked@{
        if (!file.isFile || file.length() <= 0L) return@locked false
        when (migrationState(file)) {
            MigrationState.ENVELOPED -> {
                return@locked authenticateAndSyncLocked(file)
            }
            MigrationState.ABSENT,
            MigrationState.CORRUPT,
            MigrationState.UNAVAILABLE,
            -> return@locked false
            MigrationState.PLAINTEXT -> Unit
        }
        encryptToFile(file) { cipher, output ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            try {
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        cipher.update(buffer, 0, read)?.writeAndWipe(output)
                    }
                }
            } finally {
                buffer.fill(0)
            }
        }
    }

    /**
     * Cifra bytes que solo existen en memoria y publica directamente el envelope en
     * [destination]. El único temporal contiene ya ciphertext autenticado; nunca se persiste
     * una copia plaintext intermedia. Si algo falla, el destino anterior queda intacto.
     */
    fun encryptBytesToFile(plaintext: ByteArray, destination: File): Boolean {
        if (plaintext.isEmpty()) return false
        return withFileLock(destination) { encryptBytesToFileLocked(plaintext, destination) }
    }

    private fun encryptBytesToFileLocked(plaintext: ByteArray, destination: File): Boolean =
        encryptToFile(destination) { cipher, output ->
            var offset = 0
            while (offset < plaintext.size) {
                val length = minOf(STREAM_BUFFER_BYTES, plaintext.size - offset)
                cipher.update(plaintext, offset, length)?.writeAndWipe(output)
                offset += length
            }
        }

    /** Publica por temporal cifrado; en memoria solo conserva un chunk además de la fuente. */
    private inline fun encryptToFile(
        destination: File,
        feedPlaintext: (Cipher, OutputStream) -> Unit,
    ): Boolean {
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
        } catch (_: Exception) {
            return false
        }
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) return false
        val directory = destination.parentFile
        if (directory == null || !directory.isDirectory) {
            return false
        }
        val tempFile = try {
            File.createTempFile(ENCRYPT_TEMP_PREFIX, ENCRYPT_TEMP_SUFFIX, directory)
        } catch (_: Exception) {
            return false
        }
        return try {
            FileOutputStream(tempFile).use { output ->
                output.write(MAGIC)
                output.write(iv)
                feedPlaintext(cipher, output)
                cipher.doFinal().writeAndWipe(output)
                output.fd.sync()
            }
            moveReplacing(tempFile, destination)
            publicationDurability.syncFile(destination)
            publicationDurability.syncParentAfterRename(destination)
            true
        } catch (_: Exception) {
            false
        } finally {
            iv.fill(0)
            tempFile.delete()
        }
    }

    /**
     * Revalida un envelope final ya visible y repite las dos barreras posteriores al rename.
     * Esto cierra el retry de una publicación que falló después de reemplazar el nombre: ni una
     * cabecera estructural ni una autenticación aislada bastan para declarar éxito durable.
     */
    internal fun authenticateAndSync(file: File): Boolean =
        withFileLock(file) { authenticateAndSyncLocked(file) }

    private fun authenticateAndSyncLocked(file: File): Boolean {
        if (inspectLocked(file) != EnvelopeState.ENCRYPTED) return false
        return try {
            publicationDurability.syncFile(file)
            publicationDurability.syncParentAfterRename(file)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ByteArray.writeAndWipe(output: OutputStream) {
        try {
            if (isNotEmpty()) output.write(this)
        } finally {
            fill(0)
        }
    }

    /** Locks por destino, no globales: dos fotos distintas se cifran en paralelo sin ABA. */
    private fun <T> withFileLock(file: File, action: () -> T): T {
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val entry = synchronized(FILE_LOCKS_GUARD) {
            FILE_LOCKS.getOrPut(key) { FileLockEntry() }.also { it.users += 1 }
        }
        entry.lock.lock()
        return try {
            action()
        } finally {
            entry.lock.unlock()
            synchronized(FILE_LOCKS_GUARD) {
                entry.users -= 1
                if (entry.users == 0 && FILE_LOCKS[key] === entry) FILE_LOCKS.remove(key)
            }
        }
    }

    /**
     * Descifra [file] y devuelve sus bytes en claro. Devuelve null si el archivo no existe, no
     * lleva la cabecera mágica, está corrupto/manipulado (el tag GCM no verifica) o la clave ya
     * no está disponible.
     */
    fun decrypt(file: File): ByteArray? = decrypt(file, MAX_AUTHENTICATED_PLAINTEXT_BYTES)

    /** Autentica en streaming y nunca reserva más plaintext que [maxPlaintextBytes]. */
    fun decrypt(file: File, maxPlaintextBytes: Int): ByteArray? {
        if (maxPlaintextBytes <= 0) return null
        return withFileLock(file) { decryptLocked(file, maxPlaintextBytes) }
    }

    /**
     * Autentica, devuelve el plaintext y repite las barreras de publicación bajo el mismo lock.
     * El llamador que necesita ambos resultados evita así descifrar y leer el archivo dos veces.
     * Si el `fsync` falla, los bytes ya autenticados se limpian antes de devolver el fallo.
     */
    internal fun decryptAndSync(
        file: File,
        maxPlaintextBytes: Int,
    ): DurableDecryption {
        if (maxPlaintextBytes <= 0) return DurableDecryption.AuthenticationFailed
        return withFileLock(file) {
            val plaintext = decryptLocked(file, maxPlaintextBytes)
                ?: return@withFileLock DurableDecryption.AuthenticationFailed
            try {
                publicationDurability.syncFile(file)
                publicationDurability.syncParentAfterRename(file)
                DurableDecryption.Success(plaintext)
            } catch (_: Exception) {
                plaintext.fill(0)
                DurableDecryption.DurabilityFailed
            }
        }
    }

    private fun ByteArray.regionMatches(expected: ByteArray, length: Int): Boolean {
        if (size < length || expected.size < length) return false
        for (index in 0 until length) {
            if (this[index] != expected[index]) return false
        }
        return true
    }

    private fun decryptLocked(file: File, maxPlaintextBytes: Int): ByteArray? {
        if (!hasSupportedStoredLength(file, maxPlaintextBytes)) return null
        val plaintextSize = (file.length() - ENVELOPE_OVERHEAD_BYTES).toInt()
        val plaintext = ByteArray(plaintextSize)
        val header = ByteArray(MAGIC.size)
        val iv = ByteArray(IV_BYTES)
        val encryptedChunk = ByteArray(STREAM_BUFFER_BYTES)
        return try {
            FileInputStream(file).use { input ->
                if (!input.readExactly(header) || !header.contentEquals(MAGIC) ||
                    !input.readExactly(iv)
                ) {
                    return@use null
                }
                val decryptor = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                var outputOffset = 0
                while (true) {
                    val read = input.read(encryptedChunk)
                    if (read < 0) break
                    if (read == 0) continue
                    val decoded = decryptor.update(encryptedChunk, 0, read)
                    if (decoded != null) {
                        if (outputOffset + decoded.size > plaintext.size) {
                            decoded.fill(0)
                            return@use null
                        }
                        decoded.copyInto(plaintext, outputOffset)
                        outputOffset += decoded.size
                        decoded.fill(0)
                    }
                }
                val final = decryptor.doFinal()
                try {
                    if (outputOffset + final.size != plaintext.size) return@use null
                    final.copyInto(plaintext, outputOffset)
                } finally {
                    final.fill(0)
                }
                plaintext
            }
        } catch (_: Exception) {
            null
        }.also { result ->
            if (result == null) plaintext.fill(0)
            header.fill(0)
            iv.fill(0)
            encryptedChunk.fill(0)
        }
    }

    private fun hasSupportedStoredLength(file: File, maxPlaintextBytes: Int): Boolean {
        if (!file.isFile) return false
        val maximumStoredBytes = maxPlaintextBytes.toLong() + ENVELOPE_OVERHEAD_BYTES
        return runCatching { file.length() in MIN_ENVELOPE_BYTES.toLong()..maximumStoredBytes }
            .getOrDefault(false)
    }

    private fun FileInputStream.readExactly(destination: ByteArray): Boolean {
        var offset = 0
        while (offset < destination.size) {
            val read = read(destination, offset, destination.size - offset)
            if (read < 0) return false
            if (read > 0) offset += read
        }
        return true
    }

    /**
     * Clave AES-256 del AndroidKeyStore: se reutiliza la existente o se genera una nueva no
     * exportable, solo utilizable con GCM sin padding. Se busca en cada operación: si el
     * almacén pierde la entrada, la siguiente escritura crea una clave nueva sin estado
     * obsoleto en memoria.
     */
    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            return@synchronized entry.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        generator.generateKey()
    }

    /** Rename POSIX atómico seguido por las barreras que ejecuta [encryptToFile]. */
    private fun moveReplacing(source: File, destination: File) {
        DurablePrivateFilePublication.replaceByRename(source, destination)
    }

    companion object {
        const val KEY_ALIAS = "facturastock-retained-images"
        /** Bytes fijos añadidos al plaintext: magic/version, IV y tag GCM. */
        internal const val ENVELOPE_OVERHEAD_BYTES = 4 + 12 + 16
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val IV_BYTES = 12
        private const val MIN_PLAINTEXT_BYTES = 1
        private const val MIN_ENVELOPE_BYTES =
            4 + IV_BYTES + GCM_TAG_BYTES + MIN_PLAINTEXT_BYTES
        private const val STREAM_BUFFER_BYTES = 16 * 1024
        private const val MAX_AUTHENTICATED_PLAINTEXT_BYTES = 15 * 1024 * 1024
        private const val ENCRYPT_TEMP_PREFIX = "encrypt-"
        private const val ENCRYPT_TEMP_SUFFIX = ".tmp"

        /** Cabecera mágica del formato cifrado: "FSE1" en ASCII. */
        private val MAGIC = byteArrayOf(0x46, 0x53, 0x45, 0x31)
        private val FORMAT_PREFIX = byteArrayOf(0x46, 0x53, 0x45)
        private val KEY_LOCK = Any()
        private val FILE_LOCKS_GUARD = Any()
        private val FILE_LOCKS = mutableMapOf<String, FileLockEntry>()
    }

    private class FileLockEntry(
        val lock: ReentrantLock = ReentrantLock(),
        var users: Int = 0,
    )
}
