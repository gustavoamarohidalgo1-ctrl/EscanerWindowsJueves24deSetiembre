package com.facturastock.app.domain.model.id

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogIdentifiersTest {
    private data class IdCase(
        val name: String,
        val from: (UUID) -> Any,
        val parse: (String?) -> Any?,
    )

    private val canonical = "123e4567-e89b-42d3-a456-426614174000"
    private val uuid = UUID.fromString(canonical)
    private val nilUuid = UUID(0L, 0L)

    private val cases = listOf(
        IdCase("BusinessId", BusinessId::from, BusinessId::parse),
        IdCase("SupplierId", SupplierId::from, SupplierId::parse),
        IdCase("UnitId", UnitId::from, UnitId::parse),
        IdCase("LocationId", LocationId::from, LocationId::parse),
        IdCase("ProductId", ProductId::from, ProductId::parse),
        IdCase("AliasId", AliasId::from, AliasId::parse),
        IdCase("ImageId", ImageId::from, ImageId::parse),
        IdCase("OcrRunId", OcrRunId::from, OcrRunId::parse),
    )

    @Test
    fun `from conserva el UUID canónico y parse lo recupera`() {
        cases.forEach { case ->
            val id = case.from(uuid)
            assertEquals(case.name, canonical, valueOf(id))
            assertEquals(case.name, id, case.parse(canonical))
        }
    }

    @Test
    fun `parse acepta null y devuelve null`() {
        cases.forEach { case ->
            assertNull(case.name, case.parse(null))
        }
    }

    @Test
    fun `parse rechaza mayúsculas`() {
        cases.forEach { case ->
            assertNull(case.name, case.parse(canonical.uppercase()))
        }
    }

    @Test
    fun `parse rechaza cadenas que no son UUID canónicos`() {
        cases.forEach { case ->
            assertNull(case.name, case.parse("no-es-un-uuid"))
            assertNull(case.name, case.parse(""))
            assertNull(case.name, case.parse("123e4567e89b42d3a456426614174000"))
        }
    }

    @Test
    fun `parse rechaza el UUID nulo`() {
        cases.forEach { case ->
            assertNull(case.name, case.parse("00000000-0000-0000-0000-000000000000"))
        }
    }

    @Test
    fun `from rechaza el UUID nulo`() {
        cases.forEach { case ->
            assertThrows(case.name, IllegalArgumentException::class.java) {
                case.from(nilUuid)
            }
        }
    }

    private fun valueOf(id: Any): String = when (id) {
        is BusinessId -> id.value
        is SupplierId -> id.value
        is UnitId -> id.value
        is LocationId -> id.value
        is ProductId -> id.value
        is AliasId -> id.value
        is ImageId -> id.value
        is OcrRunId -> id.value
        else -> error("tipo inesperado: $id")
    }
}
