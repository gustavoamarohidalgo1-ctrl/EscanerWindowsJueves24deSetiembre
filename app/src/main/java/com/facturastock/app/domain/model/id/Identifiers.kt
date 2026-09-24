package com.facturastock.app.domain.model.id

import com.facturastock.app.domain.model.AsciiPatterns
import java.util.UUID

private val NilUuid = UUID(0L, 0L)

private const val NIL_UUID_TEXT = "00000000-0000-0000-0000-000000000000"

/**
 * Sólo el formato 8-4-4-4-12 en hexadecimal minúsculo sobrevive a `UUID.fromString` y
 * `toString` sin cambios, así que el texto validado ya es la forma canónica. Cada fila leída
 * crea varios identificadores: comprobarlo carácter a carácter evita el UUID intermedio.
 */
private fun canonicalUuidTextOrNull(input: String): String? =
    input.takeIf { AsciiPatterns.isLowercaseUuid(it) && it != NIL_UUID_TEXT }

@JvmInline
value class DraftId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): DraftId {
            require(uuid != NilUuid)
            return DraftId(uuid.toString())
        }

        fun parse(input: String?): DraftId? =
            input?.let(::canonicalUuidTextOrNull)?.let { DraftId(it) }
    }
}

/** Identifica una ejecución concreta de OCR; nunca se reutiliza entre reintentos. */
@JvmInline
value class OcrRunId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): OcrRunId {
            require(uuid != NilUuid)
            return OcrRunId(uuid.toString())
        }

        fun parse(input: String?): OcrRunId? =
            input?.let(::canonicalUuidTextOrNull)?.let { OcrRunId(it) }
    }
}

@JvmInline
value class CaptureId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): CaptureId {
            require(uuid != NilUuid)
            return CaptureId(uuid.toString())
        }

        fun parse(input: String?): CaptureId? =
            input?.let(::canonicalUuidTextOrNull)?.let { CaptureId(it) }
    }
}

@JvmInline
value class LineId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): LineId {
            require(uuid != NilUuid)
            return LineId(uuid.toString())
        }

        fun parse(input: String?): LineId? =
            input?.let(::canonicalUuidTextOrNull)?.let { LineId(it) }
    }
}

@JvmInline
value class PurchaseId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): PurchaseId {
            require(uuid != NilUuid)
            return PurchaseId(uuid.toString())
        }

        fun parse(input: String?): PurchaseId? =
            input?.let(::canonicalUuidTextOrNull)?.let { PurchaseId(it) }
    }
}

@JvmInline
value class SaleId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): SaleId {
            require(uuid != NilUuid)
            return SaleId(uuid.toString())
        }

        fun parse(input: String?): SaleId? =
            input?.let(::canonicalUuidTextOrNull)?.let { SaleId(it) }
    }
}

@JvmInline
value class SaleLineId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): SaleLineId {
            require(uuid != NilUuid)
            return SaleLineId(uuid.toString())
        }

        fun parse(input: String?): SaleLineId? =
            input?.let(::canonicalUuidTextOrNull)?.let { SaleLineId(it) }
    }
}

@JvmInline
value class DebtId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): DebtId {
            require(uuid != NilUuid)
            return DebtId(uuid.toString())
        }

        fun parse(input: String?): DebtId? =
            input?.let(::canonicalUuidTextOrNull)?.let { DebtId(it) }
    }
}

@JvmInline
value class DebtPaymentId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): DebtPaymentId {
            require(uuid != NilUuid)
            return DebtPaymentId(uuid.toString())
        }

        fun parse(input: String?): DebtPaymentId? =
            input?.let(::canonicalUuidTextOrNull)?.let { DebtPaymentId(it) }
    }
}

@JvmInline
value class BusinessId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): BusinessId {
            require(uuid != NilUuid)
            return BusinessId(uuid.toString())
        }

        fun parse(input: String?): BusinessId? =
            input?.let(::canonicalUuidTextOrNull)?.let { BusinessId(it) }
    }
}

@JvmInline
value class SupplierId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): SupplierId {
            require(uuid != NilUuid)
            return SupplierId(uuid.toString())
        }

        fun parse(input: String?): SupplierId? =
            input?.let(::canonicalUuidTextOrNull)?.let { SupplierId(it) }
    }
}

@JvmInline
value class UnitId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): UnitId {
            require(uuid != NilUuid)
            return UnitId(uuid.toString())
        }

        fun parse(input: String?): UnitId? =
            input?.let(::canonicalUuidTextOrNull)?.let { UnitId(it) }
    }
}

@JvmInline
value class LocationId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): LocationId {
            require(uuid != NilUuid)
            return LocationId(uuid.toString())
        }

        fun parse(input: String?): LocationId? =
            input?.let(::canonicalUuidTextOrNull)?.let { LocationId(it) }
    }
}

@JvmInline
value class ProductId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): ProductId {
            require(uuid != NilUuid)
            return ProductId(uuid.toString())
        }

        fun parse(input: String?): ProductId? =
            input?.let(::canonicalUuidTextOrNull)?.let { ProductId(it) }
    }
}

@JvmInline
value class AliasId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): AliasId {
            require(uuid != NilUuid)
            return AliasId(uuid.toString())
        }

        fun parse(input: String?): AliasId? =
            input?.let(::canonicalUuidTextOrNull)?.let { AliasId(it) }
    }
}

@JvmInline
value class ImageId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): ImageId {
            require(uuid != NilUuid)
            return ImageId(uuid.toString())
        }

        fun parse(input: String?): ImageId? =
            input?.let(::canonicalUuidTextOrNull)?.let { ImageId(it) }
    }
}
