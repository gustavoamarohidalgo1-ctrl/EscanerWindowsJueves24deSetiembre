package com.facturastock.app.domain.observability

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.CameraError
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.error.OcrError
import com.facturastock.app.domain.error.OcrException
import com.facturastock.app.domain.error.ParsingError
import com.facturastock.app.domain.error.ParsingException
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.error.UnexpectedError
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import java.util.Locale
import java.util.UUID

/** Operaciones de negocio cuya salud puede observarse sin transportar contenido de facturas. */
enum class OperationalAction {
    INVOICE_CAPTURE,
    INVOICE_OCR,
    INVOICE_HEADER_EDIT,
    INVOICE_LINES_EDIT,
    PURCHASE_ADJUSTMENT,
    PURCHASE_CONFIRMATION,
    PURCHASE_VOID,
    BACKUP_SYNC,
    OUTBOX_HEALTH,
    ROOM_OPEN,
    PROCESS_EXIT,
}

/** Resultado cerrado de una operación; nunca contiene mensajes de SDK o del backend. */
enum class OperationalOutcome {
    SUCCEEDED,
    ALREADY_APPLIED,
    BLOCKED,
    RETRY_SCHEDULED,
    CONFLICT,
    FAILED,
    SKIPPED,
}

/** Rango deliberadamente grueso: permite detectar una cola atascada sin emitir timestamps. */
enum class OperationalAgeBucket {
    CLOCK_SKEW_OR_FUTURE,
    UNDER_15_MINUTES,
    FROM_15_MINUTES_TO_1_HOUR,
    FROM_1_TO_6_HOURS,
    FROM_6_TO_24_HOURS,
    FROM_1_TO_7_DAYS,
    OVER_7_DAYS,
}

/**
 * Catálogo cerrado de fallos operacionales. Es deliberadamente menos detallado que los errores
 * funcionales: no contiene valores rechazados, rutas, texto OCR, mensajes ni nombres de clases.
 */
enum class OperationalErrorCode {
    CAMERA_PERMISSION_DENIED,
    CAMERA_UNAVAILABLE,
    CAMERA_CAPTURE_FAILED,
    FILE_NOT_FOUND,
    FILE_CORRUPT,
    FILE_INSUFFICIENT_SPACE,
    FILE_UNSUPPORTED_FORMAT,
    FILE_TOO_LARGE,
    OCR_MODEL_UNAVAILABLE,
    OCR_NO_TEXT,
    OCR_RECOGNITION_FAILED,
    OCR_DRAFT_NOT_OPEN,
    PARSING_EMPTY_INPUT,
    PARSING_UNSUPPORTED_VALUE,
    PARSING_DRAFT_NOT_FOUND,
    PARSING_DRAFT_NOT_READY,
    PARSING_SNAPSHOT_MISSING,
    PARSING_STALE_SNAPSHOT,
    PARSING_PUBLICATION_CONFLICT,
    VALIDATION_FAILED,
    STORAGE_UNAVAILABLE,
    STORAGE_INSUFFICIENT_SPACE,
    STORAGE_CONSTRAINT_CONFLICT,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_NOT_AUTHENTICATED,
    ACCOUNT_PERMISSION_DENIED,
    ACCOUNT_SESSION_EXPIRED,
    ACCOUNT_NETWORK_UNAVAILABLE,
    ACCOUNT_CONFLICT,
    NO_ACTIVE_BUSINESS,
    NOT_EDITABLE,
    STALE_REVISION,
    NOT_FOUND,
    INVALID_REASON,
    UNAUTHORIZED,
    INTEGRITY_CONFLICT,
    TRANSPORT_UNAVAILABLE,
    BACKEND_5XX,
    BACKEND_RATE_LIMITED,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    PROCESS_EXIT_ANR,
    PROCESS_EXIT_CRASH,
    PROCESS_EXIT_LOW_MEMORY,
    PROCESS_EXIT_RESOURCE_LIMIT,
    PROCESS_EXIT_SYSTEM_FAILURE,
    UNEXPECTED,
}

/**
 * Únicos identificadores admitidos por observabilidad. Los tipos de dominio ya garantizan UUID
 * canónico; [operationId] se valida aquí porque la outbox conserva ese ID como `String` legado.
 */
data class InternalIdentifiers(
    val businessId: BusinessId? = null,
    val draftId: DraftId? = null,
    val imageId: ImageId? = null,
    val purchaseId: PurchaseId? = null,
    val operationId: String? = null,
) {
    init {
        require(operationId == null || operationId.isCanonicalInternalUuid()) {
            "operationId debe ser un UUID interno canónico"
        }
    }
}

/**
 * Señal tipada previa a consentimiento/redacción. [identifiers] solo permite correlación local
 * dentro del proceso y nunca forma parte del evento que sale del dispositivo.
 */
data class OperationalAuditEvent(
    val action: OperationalAction,
    val outcome: OperationalOutcome,
    val identifiers: InternalIdentifiers = InternalIdentifiers(),
    val errorCode: OperationalErrorCode? = null,
    val ageBucket: OperationalAgeBucket? = null,
)

/**
 * Puerto de auditoría operacional de mejor esfuerzo. [failure] solo entra a la capa común para
 * convertirse a [OperationalErrorCode]; ningún sink recibe el Throwable original.
 */
interface ProductionObservability {
    suspend fun record(event: OperationalAuditEvent, failure: Throwable? = null)

    /** Aplica al SDK una decisión ya solicitada por la persona; la persistencia vive fuera. */
    suspend fun updateConsent(enabled: Boolean)

    /**
     * Sincroniza el consentimiento leído al arrancar sin pisar una decisión hecha en esta sesión.
     * La implementación por defecto conserva compatibilidad con adaptadores puros y de prueba.
     */
    suspend fun syncInitialConsent(enabled: Boolean) = updateConsent(enabled)
}

/** Fallback explícito para tests unitarios y construcciones puras; producción siempre usa DI. */
object DisabledProductionObservability : ProductionObservability {
    override suspend fun record(event: OperationalAuditEvent, failure: Throwable?) = Unit
    override suspend fun updateConsent(enabled: Boolean) = Unit
}

/** Traducción exhaustiva a códigos cerrados; ignora message, cause y stacktrace por diseño. */
fun Throwable.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    is FileException -> error.toOperationalErrorCode()
    is OcrException -> error.toOperationalErrorCode()
    is ParsingException -> error.toOperationalErrorCode()
    is StorageException -> error.toOperationalErrorCode()
    is AccountException -> error.toOperationalErrorCode()
    is DomainRuleViolation -> error.toOperationalErrorCode()
    else -> OperationalErrorCode.UNEXPECTED
}

/** Traduce también los fallos cerrados devueltos por [DomainResult], sin texto diagnóstico. */
fun FacturaStockError.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    is CameraError -> toOperationalErrorCode()
    is FileError -> toOperationalErrorCode()
    is OcrError -> toOperationalErrorCode()
    is ParsingError -> toOperationalErrorCode()
    is StorageError -> toOperationalErrorCode()
    is AccountError -> toOperationalErrorCode()
    is ValidationError -> toOperationalErrorCode()
    is UnexpectedError -> OperationalErrorCode.UNEXPECTED
}

private fun CameraError.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    CameraError.PermissionDenied -> OperationalErrorCode.CAMERA_PERMISSION_DENIED
    CameraError.Unavailable -> OperationalErrorCode.CAMERA_UNAVAILABLE
    CameraError.CaptureFailed -> OperationalErrorCode.CAMERA_CAPTURE_FAILED
}

private fun FileError.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    FileError.NotFound -> OperationalErrorCode.FILE_NOT_FOUND
    FileError.Corrupt -> OperationalErrorCode.FILE_CORRUPT
    FileError.InsufficientSpace -> OperationalErrorCode.FILE_INSUFFICIENT_SPACE
    FileError.UnsupportedFormat -> OperationalErrorCode.FILE_UNSUPPORTED_FORMAT
    FileError.TooLarge -> OperationalErrorCode.FILE_TOO_LARGE
}

private fun OcrError.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    OcrError.ModelUnavailable -> OperationalErrorCode.OCR_MODEL_UNAVAILABLE
    OcrError.NoTextDetected -> OperationalErrorCode.OCR_NO_TEXT
    OcrError.RecognitionFailed -> OperationalErrorCode.OCR_RECOGNITION_FAILED
    OcrError.DraftNotOpen -> OperationalErrorCode.OCR_DRAFT_NOT_OPEN
}

private fun ParsingError.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    ParsingError.EmptyInput -> OperationalErrorCode.PARSING_EMPTY_INPUT
    is ParsingError.UnsupportedValue -> OperationalErrorCode.PARSING_UNSUPPORTED_VALUE
    ParsingError.DraftNotFound -> OperationalErrorCode.PARSING_DRAFT_NOT_FOUND
    ParsingError.DraftNotReady -> OperationalErrorCode.PARSING_DRAFT_NOT_READY
    ParsingError.SnapshotMissing -> OperationalErrorCode.PARSING_SNAPSHOT_MISSING
    ParsingError.StaleSnapshot -> OperationalErrorCode.PARSING_STALE_SNAPSHOT
    ParsingError.PublicationConflict -> OperationalErrorCode.PARSING_PUBLICATION_CONFLICT
}

private fun StorageError.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    StorageError.Unavailable -> OperationalErrorCode.STORAGE_UNAVAILABLE
    StorageError.InsufficientSpace -> OperationalErrorCode.STORAGE_INSUFFICIENT_SPACE
    is StorageError.ConstraintConflict -> OperationalErrorCode.STORAGE_CONSTRAINT_CONFLICT
}

private fun AccountError.toOperationalErrorCode(): OperationalErrorCode = when (this) {
    AccountError.Unavailable -> OperationalErrorCode.ACCOUNT_UNAVAILABLE
    AccountError.NotAuthenticated -> OperationalErrorCode.ACCOUNT_NOT_AUTHENTICATED
    AccountError.PermissionDenied -> OperationalErrorCode.ACCOUNT_PERMISSION_DENIED
    AccountError.SessionExpired -> OperationalErrorCode.ACCOUNT_SESSION_EXPIRED
    AccountError.NetworkUnavailable -> OperationalErrorCode.ACCOUNT_NETWORK_UNAVAILABLE
    AccountError.Conflict -> OperationalErrorCode.ACCOUNT_CONFLICT
    AccountError.CloudBusinessAlreadyBound,
    AccountError.LegacySyncDestinationUnknown,
    -> OperationalErrorCode.INTEGRITY_CONFLICT
    AccountError.NotFound -> OperationalErrorCode.NOT_FOUND
    AccountError.InvalidCredentials,
    AccountError.InvalidEmail,
    AccountError.EmailInUse,
    AccountError.WeakPassword,
    AccountError.EmailNotVerified,
    AccountError.InvitationExpired,
    AccountError.LastOwnerRequired,
    AccountError.DeletionScopeTooLarge,
    AccountError.Unexpected,
    -> OperationalErrorCode.UNEXPECTED
}

@Suppress("UNUSED_PARAMETER")
private fun ValidationError.toOperationalErrorCode(): OperationalErrorCode =
    OperationalErrorCode.VALIDATION_FAILED

private fun String.isCanonicalInternalUuid(): Boolean {
    if (length != 36 || this != lowercase(Locale.ROOT)) return false
    val parsed = runCatching(UUID::fromString).getOrNull() ?: return false
    return parsed != UUID(0L, 0L) && parsed.toString() == this
}
