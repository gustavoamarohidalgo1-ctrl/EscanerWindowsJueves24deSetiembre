package com.facturastock.app.domain.model.id

import java.util.Locale
import java.util.UUID

private val NilUuid = UUID(0L, 0L)

private fun canonicalUuidOrNull(input: String): UUID? {
    if (input.length != 36 || input != input.lowercase(Locale.ROOT)) {
        return null
    }
    val parsed = try {
        UUID.fromString(input)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return parsed.takeIf { it != NilUuid && it.toString() == input }
}

@JvmInline
value class DraftId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): DraftId {
            require(uuid != NilUuid)
            return DraftId(uuid.toString())
        }

        fun parse(input: String?): DraftId? =
            input?.let(::canonicalUuidOrNull)?.let { DraftId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { OcrRunId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { CaptureId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { LineId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { PurchaseId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { SaleId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { SaleLineId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { DebtId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { DebtPaymentId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { BusinessId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { SupplierId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { UnitId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { LocationId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { ProductId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { AliasId(it.toString()) }
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
            input?.let(::canonicalUuidOrNull)?.let { ImageId(it.toString()) }
    }
}
