package com.facturastock.app.data.local.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Reproduce los layouts históricos sin exponer escritores legacy en producción. */
internal object LegacyCodecPayloadFixtures {
    fun preparedV4(currentPayload: ByteArray): ByteArray = currentPayload.copyOf().also { payload ->
        val header = ByteBuffer.wrap(payload)
        header.int // MAGIC; el decoder productivo valida su valor.
        check(header.int == PreparedPurchaseCodec.VERSION)
        header.putInt(Int.SIZE_BYTES, 4)
    }

    fun preparedV5(currentPayload: ByteArray): ByteArray = rewrite(currentPayload) { input, output ->
        output.writeInt(input.readInt())
        check(input.readInt() == PreparedPurchaseCodec.VERSION)
        output.writeInt(5)
        input.copyString(output)
        input.copyString(output)
        input.copyNullableString(output)
        input.copyString(output)
        input.copyNullableString(output)
        input.copyNullableString(output)
        input.copyString(output)
        output.writeLong(input.readLong())
        input.copyString(output)
        val lineCount = input.readInt().also(output::writeInt)
        repeat(lineCount) {
            input.copyString(output)
            output.writeInt(input.readInt())
            repeat(5) { input.copyString(output) }
            input.copyNullableString(output)
            repeat(3) { input.copyNullableMoney(output) }
            input.copyNullableInt(output)
            input.copyString(output)
            input.copyString(output)
            input.copyNullableString(output)
            input.copyString(output)
            input.copyStagedProductWithoutSalePrice(output)
        }
        repeat(3) { input.copyNullableMoney(output) }
        input.copyMoney(output)
        val warningCount = input.readInt().also(output::writeInt)
        repeat(warningCount) { input.copyString(output) }
        input.copyNullableReconciliation(output)
        input.copyString(output)
        output.writeLong(input.readLong())
    }

    fun invoiceLinesV2(currentPayload: ByteArray): ByteArray = rewrite(currentPayload) { input, output ->
        output.writeInt(input.readInt())
        check(input.readInt() == InvoiceLinesEditCodec.VERSION)
        output.writeInt(2)
        input.copyString(output)
        output.writeLong(input.readLong())
        val lineCount = input.readInt().also(output::writeInt)
        repeat(lineCount) {
            input.copyString(output)
            output.writeInt(input.readInt())
            input.copyString(output)
            input.copyNullableInt(output)
            input.copyNullableString(output)
            input.copyNullableString(output)
            input.copyNullableString(output)
            input.copyNullableInt(output)
            input.skipString() // taxTreatment (v3)
            input.skipString() // productProvenance (v3)
            input.skipStagedProduct()
            repeat(8 * 5) { input.copyNullableString(output) }
            input.copyNullableInt(output)
            val confidenceCount = input.readInt().also(output::writeInt)
            repeat(confidenceCount) {
                input.copyString(output)
                output.writeInt(input.readInt())
            }
            output.writeByte(input.readUnsignedByte())
            output.writeByte(input.readUnsignedByte())
            repeat(2) {
                val fieldCount = input.readInt().also(output::writeInt)
                repeat(fieldCount) { input.copyString(output) }
            }
            input.copyNullableLong(output)
            output.writeLong(input.readLong())
            output.writeLong(input.readLong())
        }
        output.writeLong(input.readLong())
    }

    fun invoiceLinesV3(currentPayload: ByteArray): ByteArray = rewrite(currentPayload) { input, output ->
        output.writeInt(input.readInt())
        check(input.readInt() == InvoiceLinesEditCodec.VERSION)
        output.writeInt(3)
        input.copyString(output)
        output.writeLong(input.readLong())
        val lineCount = input.readInt().also(output::writeInt)
        repeat(lineCount) {
            input.copyString(output)
            output.writeInt(input.readInt())
            input.copyString(output)
            input.copyNullableInt(output)
            input.copyNullableString(output)
            input.copyNullableString(output)
            input.copyNullableString(output)
            input.copyNullableInt(output)
            input.copyString(output)
            input.copyString(output)
            input.copyStagedProductWithoutSalePrice(output)
            repeat(8 * 5) { input.copyNullableString(output) }
            input.copyNullableInt(output)
            val confidenceCount = input.readInt().also(output::writeInt)
            repeat(confidenceCount) {
                input.copyString(output)
                output.writeInt(input.readInt())
            }
            output.writeByte(input.readUnsignedByte())
            output.writeByte(input.readUnsignedByte())
            repeat(2) {
                val fieldCount = input.readInt().also(output::writeInt)
                repeat(fieldCount) { input.copyString(output) }
            }
            input.copyNullableLong(output)
            output.writeLong(input.readLong())
            output.writeLong(input.readLong())
        }
        output.writeLong(input.readLong())
    }

    fun preparedLegacy(currentPayload: ByteArray, targetVersion: Int): ByteArray {
        require(targetVersion in 2..3)
        return rewrite(currentPayload) { input, output ->
            output.writeInt(input.readInt())
            check(input.readInt() == PreparedPurchaseCodec.VERSION)
            output.writeInt(targetVersion)
            input.copyString(output)
            input.copyString(output)
            input.copyNullableString(output)
            input.copyString(output)
            input.copyNullableString(output)
            input.copyNullableString(output)
            input.copyString(output)
            output.writeLong(input.readLong())
            input.copyString(output)
            val lineCount = input.readInt().also(output::writeInt)
            repeat(lineCount) {
                input.copyString(output)
                output.writeInt(input.readInt())
                repeat(5) { input.copyString(output) }
                input.copyNullableString(output)
                repeat(3) { input.copyNullableMoney(output) }
                input.copyNullableInt(output)
                input.skipString() // taxTreatment (v4)
                input.skipString() // taxEvidenceType (v4)
                input.skipNullableString() // taxEvidenceValue (v4)
                input.skipString() // productProvenance (v4)
                input.skipStagedProduct()
            }
            repeat(3) { input.copyNullableMoney(output) }
            input.copyMoney(output)
            val warningCount = input.readInt().also(output::writeInt)
            repeat(warningCount) { input.copyString(output) }
            if (targetVersion >= 3) {
                input.copyNullableReconciliation(output)
            } else {
                input.skipNullableReconciliation()
            }
            input.copyString(output)
            output.writeLong(input.readLong())
        }
    }

    private inline fun rewrite(
        payload: ByteArray,
        block: (DataInputStream, DataOutputStream) -> Unit,
    ): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output -> block(input, output) }
        check(input.available() == 0) { "El fixture no consumió todo el payload actual" }
        return buffer.toByteArray()
    }

    private fun DataInputStream.copyString(output: DataOutputStream) {
        val size = readInt()
        output.writeInt(size)
        output.write(ByteArray(size).also(::readFully))
    }

    private fun DataInputStream.skipString() {
        val size = readInt()
        skipExact(size)
    }

    private fun DataInputStream.copyNullableString(output: DataOutputStream) {
        when (val marker = readUnsignedByte()) {
            0 -> output.writeByte(marker)
            1 -> {
                output.writeByte(marker)
                copyString(output)
            }
            else -> error("Marcador nullable inválido: $marker")
        }
    }

    private fun DataInputStream.skipNullableString() {
        when (val marker = readUnsignedByte()) {
            0 -> Unit
            1 -> skipString()
            else -> error("Marcador nullable inválido: $marker")
        }
    }

    private fun DataInputStream.copyNullableInt(output: DataOutputStream) {
        when (val marker = readUnsignedByte()) {
            0 -> output.writeByte(marker)
            1 -> {
                output.writeByte(marker)
                output.writeInt(readInt())
            }
            else -> error("Marcador nullable inválido: $marker")
        }
    }

    private fun DataInputStream.copyNullableLong(output: DataOutputStream) {
        when (val marker = readUnsignedByte()) {
            0 -> output.writeByte(marker)
            1 -> {
                output.writeByte(marker)
                output.writeLong(readLong())
            }
            else -> error("Marcador nullable inválido: $marker")
        }
    }

    private fun DataInputStream.copyMoney(output: DataOutputStream) {
        output.writeLong(readLong())
        copyString(output)
    }

    private fun DataInputStream.copyNullableMoney(output: DataOutputStream) {
        when (val marker = readUnsignedByte()) {
            0 -> output.writeByte(marker)
            1 -> {
                output.writeByte(marker)
                copyMoney(output)
            }
            else -> error("Marcador de dinero inválido: $marker")
        }
    }

    private fun DataInputStream.copyNullableReconciliation(output: DataOutputStream) {
        when (val marker = readUnsignedByte()) {
            0 -> output.writeByte(marker)
            1 -> {
                output.writeByte(marker)
                copyMoney(output)
                copyString(output)
            }
            else -> error("Marcador de conciliación inválido: $marker")
        }
    }

    private fun DataInputStream.skipNullableReconciliation() {
        when (val marker = readUnsignedByte()) {
            0 -> Unit
            1 -> {
                readLong()
                skipString()
                skipString()
            }
            else -> error("Marcador de conciliación inválido: $marker")
        }
    }

    private fun DataInputStream.skipStagedProduct() {
        when (val marker = readUnsignedByte()) {
            0 -> Unit
            1 -> {
                repeat(4) { skipString() }
                repeat(4) { skipNullableString() }
                skipNullableMoney()
            }
            else -> error("Marcador staged inválido: $marker")
        }
    }

    private fun DataInputStream.copyStagedProductWithoutSalePrice(output: DataOutputStream) {
        when (val marker = readUnsignedByte()) {
            0 -> output.writeByte(marker)
            1 -> {
                output.writeByte(marker)
                repeat(4) { copyString(output) }
                repeat(4) { copyNullableString(output) }
                skipNullableMoney()
            }
            else -> error("Marcador staged inválido: $marker")
        }
    }

    private fun DataInputStream.skipNullableMoney() {
        when (val marker = readUnsignedByte()) {
            0 -> Unit
            1 -> {
                readLong()
                skipString()
            }
            else -> error("Marcador de dinero inválido: $marker")
        }
    }

    private fun DataInputStream.skipExact(byteCount: Int) {
        require(byteCount >= 0)
        val bytes = ByteArray(byteCount)
        readFully(bytes)
    }
}
