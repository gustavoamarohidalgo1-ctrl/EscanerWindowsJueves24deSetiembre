package com.facturastock.app.data.repository

import com.facturastock.app.data.local.entity.InvoiceImageEntity
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Referencia local durable. [relativeFilePath] nunca forma parte del wire Firebase. */
internal data class DocumentUploadPayload(
    val purchaseId: String,
    val imageId: String,
    val sha256: String,
    val relativeFilePath: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
    val rotationDegrees: Int,
)

internal data class DocumentPurgePayload(
    val purchaseId: String,
    val imageId: String,
)

/** Codec canónico cerrado para las operaciones documentales locales de la outbox. */
internal object DocumentBackupPayloadCodec {
    const val PAYLOAD_VERSION: Int = 1

    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun encodeUpload(purchaseId: String, image: InvoiceImageEntity): String = encodeUpload(
        purchaseId = purchaseId,
        imageId = image.imageId,
        sha256 = image.sha256,
        relativeFilePath = image.filePath,
        mimeType = image.mimeType,
        widthPx = image.widthPx,
        heightPx = image.heightPx,
        fileSizeBytes = image.fileSizeBytes,
        rotationDegrees = image.rotationDegrees,
    )

    fun encodeUpload(
        purchaseId: String,
        imageId: String,
        sha256: String,
        relativeFilePath: String,
        mimeType: String,
        widthPx: Int,
        heightPx: Int,
        fileSizeBytes: Long,
        rotationDegrees: Int,
    ): String = buildString {
        append("{\"version\":1")
        append(",\"purchaseId\":\"").append(purchaseId).append('"')
        append(",\"imageId\":\"").append(imageId).append('"')
        append(",\"sha256\":\"").append(sha256).append('"')
        append(",\"relativeFilePath\":\"")
            .append(relativeFilePath.jsonEscapedForDocumentPayload()).append('"')
        append(",\"mimeType\":\"").append(mimeType).append('"')
        append(",\"widthPx\":").append(widthPx)
        append(",\"heightPx\":").append(heightPx)
        append(",\"fileSizeBytes\":").append(fileSizeBytes)
        append(",\"rotationDegrees\":").append(rotationDegrees)
        append('}')
    }

    fun encodePurge(purchaseId: String, imageId: String): String =
        "{\"version\":1,\"purchaseId\":\"$purchaseId\",\"imageId\":\"$imageId\"}"

    fun decodeUpload(payload: String): DocumentUploadPayload? = decodeCanonical(payload) { value ->
        if (value.keys != UPLOAD_KEYS || value.requiredInt("version") != PAYLOAD_VERSION) {
            return@decodeCanonical null
        }
        val parsed = DocumentUploadPayload(
            purchaseId = value.requiredString("purchaseId") ?: return@decodeCanonical null,
            imageId = value.requiredString("imageId") ?: return@decodeCanonical null,
            sha256 = value.requiredString("sha256") ?: return@decodeCanonical null,
            relativeFilePath = value.requiredString("relativeFilePath")
                ?: return@decodeCanonical null,
            mimeType = value.requiredString("mimeType") ?: return@decodeCanonical null,
            widthPx = value.requiredInt("widthPx") ?: return@decodeCanonical null,
            heightPx = value.requiredInt("heightPx") ?: return@decodeCanonical null,
            fileSizeBytes = value.requiredLong("fileSizeBytes") ?: return@decodeCanonical null,
            rotationDegrees = value.requiredInt("rotationDegrees") ?: return@decodeCanonical null,
        )
        if (!parsed.isValid()) return@decodeCanonical null
        parsed.takeIf { encodeUpload(it) == payload }
    }

    fun decodePurge(payload: String): DocumentPurgePayload? = decodeCanonical(payload) { value ->
        if (value.keys != PURGE_KEYS || value.requiredInt("version") != PAYLOAD_VERSION) {
            return@decodeCanonical null
        }
        val parsed = DocumentPurgePayload(
            purchaseId = value.requiredString("purchaseId") ?: return@decodeCanonical null,
            imageId = value.requiredString("imageId") ?: return@decodeCanonical null,
        )
        if (!UUID.matches(parsed.purchaseId) || !UUID.matches(parsed.imageId)) {
            return@decodeCanonical null
        }
        parsed.takeIf { encodePurge(it.purchaseId, it.imageId) == payload }
    }

    fun candidateIdempotencyKey(purchaseId: String, imageId: String, sourceSha256: String): String =
        "document-upload-candidate:v1:$purchaseId:$imageId:$sourceSha256"

    /** Mismo ID que usa el posting atómico; bootstrap y confirmación nunca crean dos filas. */
    fun candidateOperationId(purchaseId: String, imageId: String): String {
        val canonicalName = "facturastock:purchase-posting:v1:sync-document-upload-outbox" +
            "\u001f$purchaseId\u001f$imageId"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalName.toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    fun purgeIdempotencyKey(purchaseId: String, imageId: String): String =
        "document-purge:v1:$purchaseId:$imageId"

    private fun encodeUpload(payload: DocumentUploadPayload): String = buildString {
        append("{\"version\":1")
        append(",\"purchaseId\":\"").append(payload.purchaseId).append('"')
        append(",\"imageId\":\"").append(payload.imageId).append('"')
        append(",\"sha256\":\"").append(payload.sha256).append('"')
        append(",\"relativeFilePath\":\"")
            .append(payload.relativeFilePath.jsonEscapedForDocumentPayload()).append('"')
        append(",\"mimeType\":\"").append(payload.mimeType).append('"')
        append(",\"widthPx\":").append(payload.widthPx)
        append(",\"heightPx\":").append(payload.heightPx)
        append(",\"fileSizeBytes\":").append(payload.fileSizeBytes)
        append(",\"rotationDegrees\":").append(payload.rotationDegrees)
        append('}')
    }

    private inline fun <T> decodeCanonical(
        payload: String,
        parse: (JsonObject) -> T?,
    ): T? = try {
        parse(json.parseToJsonElement(payload).jsonObject)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun DocumentUploadPayload.isValid(): Boolean =
        UUID.matches(purchaseId) && UUID.matches(imageId) && SHA256.matches(sha256) &&
            relativeFilePath.isNotBlank() && relativeFilePath.length <= 512 &&
            !relativeFilePath.startsWith('/') && !relativeFilePath.contains("..") &&
            mimeType in SUPPORTED_MIME_TYPES && widthPx > 0 && heightPx > 0 &&
            fileSizeBytes > 0L && rotationDegrees in setOf(0, 90, 180, 270)

    private fun JsonObject.requiredString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.requiredInt(key: String): Int? = requiredLong(key)?.let { value ->
        value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun JsonObject.requiredLong(key: String): Long? {
        val primitive = this[key]?.jsonPrimitive ?: return null
        if (primitive.isString || !CANONICAL_LONG.matches(primitive.content)) return null
        return primitive.content.toLongOrNull()
    }

    private val UPLOAD_KEYS = setOf(
        "version",
        "purchaseId",
        "imageId",
        "sha256",
        "relativeFilePath",
        "mimeType",
        "widthPx",
        "heightPx",
        "fileSizeBytes",
        "rotationDegrees",
    )
    private val PURGE_KEYS = setOf("version", "purchaseId", "imageId")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val CANONICAL_LONG = Regex("0|[1-9][0-9]*")
    private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
}

private fun String.jsonEscapedForDocumentPayload(): String = buildString(length) {
    this@jsonEscapedForDocumentPayload.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u%04x".format(character.code))
            } else {
                append(character)
            }
        }
    }
}
