package com.facturastock.app.domain.error

import com.facturastock.app.domain.model.CurrencyCode

sealed interface FacturaStockError

sealed interface CameraError : FacturaStockError {
    data object PermissionDenied : CameraError
    data object Unavailable : CameraError
    data object CaptureFailed : CameraError
}

sealed interface FileError : FacturaStockError {
    data object NotFound : FileError
    data object Corrupt : FileError
    data object InsufficientSpace : FileError
    data object UnsupportedFormat : FileError

    /** El archivo supera el tamaño máximo o la imagen excede las dimensiones admitidas. */
    data object TooLarge : FileError
}

sealed interface OcrError : FacturaStockError {
    data object ModelUnavailable : OcrError
    data object NoTextDetected : OcrError
    data object RecognitionFailed : OcrError
    data object DraftNotOpen : OcrError
}

sealed interface ParsingError : FacturaStockError {
    data object EmptyInput : ParsingError
    data class UnsupportedValue(val field: String, val source: String) : ParsingError
    data object DraftNotFound : ParsingError
    data object DraftNotReady : ParsingError
    data object SnapshotMissing : ParsingError
    data object StaleSnapshot : ParsingError
    data object PublicationConflict : ParsingError
}

sealed interface ValidationError : FacturaStockError {
    data class InvalidCurrencyCode(val input: String) : ValidationError
    data class CurrencyMismatch(
        val expected: CurrencyCode,
        val actual: CurrencyCode,
    ) : ValidationError

    data class InvalidMoneyAmount(val input: String) : ValidationError
    data class InvalidQuantity(val input: String) : ValidationError
    data class InvalidUnitCost(val input: String) : ValidationError
    data class InvalidRuc(val input: String) : ValidationError
    data object InvalidBarcode : ValidationError
    data class InvalidTaxRate(val input: String) : ValidationError
    data class InvalidLocale(val input: String) : ValidationError
    data class DecimalScaleLoss(
        val value: String,
        val targetScale: Int,
    ) : ValidationError

    data class ArithmeticOverflow(val operation: String) : ValidationError
}

sealed interface StorageError : FacturaStockError {
    data object Unavailable : StorageError

    /** SQLite o el filesystem privado no pudieron completar la escritura por falta de espacio. */
    data object InsufficientSpace : StorageError

    data class ConstraintConflict(val detail: String) : StorageError
}

/**
 * Errores cerrados de cuenta, membresía e invitaciones en la nube. Nunca transportan
 * tokens, correos ajenos ni mensajes crudos del backend.
 */
sealed interface AccountError : FacturaStockError {
    /** Flavor local o Firebase sin configurar: la nube no está disponible aquí. */
    data object Unavailable : AccountError

    data object NotAuthenticated : AccountError
    data object InvalidCredentials : AccountError
    data object InvalidEmail : AccountError
    data object EmailInUse : AccountError
    data object WeakPassword : AccountError
    data object EmailNotVerified : AccountError

    /** La sesión expiró o fue revocada; hay que reingresar. */
    data object SessionExpired : AccountError

    /** Rol insuficiente o membresía inexistente en el negocio. */
    data object PermissionDenied : AccountError

    /** Negocio, miembro o invitación inexistente. */
    data object NotFound : AccountError

    /** Estado incompatible (invitación ya gestionada, negocio ya existente, etc.). */
    data object Conflict : AccountError

    data object InvitationExpired : AccountError

    /** El último OWNER no puede ser degradado ni eliminado. */
    data object LastOwnerRequired : AccountError

    /** El cierre atómico abarca más negocios de los admitidos; requiere soporte. */
    data object DeletionScopeTooLarge : AccountError

    /** El negocio local ya tiene un tenant cloud inmutable; se debe usar otro negocio local. */
    data object CloudBusinessAlreadyBound : AccountError

    /** Historia v19 pudo salir a un tenant no demostrable; requiere migración asistida. */
    data object LegacySyncDestinationUnknown : AccountError

    /** Fallo de conectividad contra el backend. */
    data object NetworkUnavailable : AccountError

    /** Cualquier otro fallo no clasificado; sin detalle sensible. */
    data object Unexpected : AccountError
}

/** Error controlado de los puertos de cuenta; nunca contiene tokens ni datos personales. */
class AccountException(
    val error: AccountError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

data class UnexpectedError(val diagnosticId: String) : FacturaStockError

sealed interface DomainResult<out T> {
    data class Success<T>(val value: T) : DomainResult<T>
    data class Failure(val error: FacturaStockError) : DomainResult<Nothing>
}

class DomainRuleViolation(
    val error: ValidationError,
    cause: Throwable? = null,
) : IllegalArgumentException(error.toString(), cause)

class StorageException(
    val error: StorageError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

class FileException(
    val error: FileError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

/** Error controlado del puerto OCR; nunca contiene texto reconocido ni rutas de factura. */
class OcrException(
    val error: OcrError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

/** Error controlado del parseo; no incluye texto OCR, RUC ni valores financieros. */
class ParsingException(
    val error: ParsingError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)
