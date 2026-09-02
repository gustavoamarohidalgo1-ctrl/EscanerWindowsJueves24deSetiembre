package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.id.BusinessId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountListPaginationTest {

    @Test
    fun `drena mas de cien paginas y entrega cada cursor exactamente una vez`() = runTest {
        val requestedCursors = mutableListOf<String?>()
        val expectedValues = mutableListOf<Int>()
        var pageIndex = 0
        val pageCount = 151

        val result = drainAccountPages<Int> { cursor ->
            requestedCursors += cursor
            val expectedCursor = if (pageIndex == 0) null else cursorFor(pageIndex - 1)
            assertEquals(expectedCursor, cursor)
            val hasMore = pageIndex < pageCount - 1
            val pageSize = if (hasMore) 100 else 37
            val values = List(pageSize) { offset -> pageIndex * 100 + offset }
            expectedValues += values
            DomainResult.Success(
                AccountListPage(
                    values = values,
                    nextCursor = if (hasMore) cursorFor(pageIndex) else null,
                    hasMore = hasMore,
                ),
            ).also { pageIndex++ }
        }

        assertEquals(expectedValues, (result as DomainResult.Success).value)
        assertEquals(15_037, expectedValues.size)
        assertEquals(pageCount, requestedCursors.size)
        assertEquals(null, requestedCursors.first())
        assertEquals(cursorFor(pageCount - 2), requestedCursors.last())
    }

    @Test
    fun `concatena paginas sin reordenar deduplicar ni perder una pagina vacia intermedia`() =
        runTest {
            val pages = ArrayDeque(
                listOf(
                    AccountListPage(values = listOf("b", "a"), nextCursor = "c1", hasMore = true),
                    // listBusinessInvitations puede filtrar expiradas después de paginar.
                    AccountListPage(values = emptyList(), nextCursor = "c2", hasMore = true),
                    AccountListPage(values = listOf("a", "z"), nextCursor = null, hasMore = false),
                ),
            )
            val cursors = mutableListOf<String?>()

            val result = drainAccountPages<String> { cursor ->
                cursors += cursor
                DomainResult.Success(pages.removeFirst())
            }

            assertEquals(listOf("b", "a", "a", "z"), (result as DomainResult.Success).value)
            assertEquals(listOf(null, "c1", "c2"), cursors)
            assertTrue(pages.isEmpty())
        }

    @Test
    fun `cursor repetido inmediato falla cerrado y no solicita otra pagina`() = runTest {
        var calls = 0
        val result = drainAccountPages<Int> { cursor ->
            calls++
            DomainResult.Success(
                AccountListPage(
                    values = listOf(calls),
                    nextCursor = "same-cursor",
                    hasMore = true,
                ),
            )
        }

        assertEquals(DomainResult.Failure(AccountError.Unexpected), result)
        assertEquals(2, calls)
    }

    @Test
    fun `ciclo de cursores no entra en loop`() = runTest {
        val requested = mutableListOf<String?>()
        val result = drainAccountPages<Int> { cursor ->
            requested += cursor
            val next = when (cursor) {
                null -> "cursor-a"
                "cursor-a" -> "cursor-b"
                else -> "cursor-a"
            }
            DomainResult.Success(
                AccountListPage(values = listOf(requested.size), nextCursor = next, hasMore = true),
            )
        }

        assertEquals(DomainResult.Failure(AccountError.Unexpected), result)
        assertEquals(listOf(null, "cursor-a", "cursor-b"), requested)
    }

    @Test
    fun `propaga el error de una pagina y detiene el drenaje`() = runTest {
        val requested = mutableListOf<String?>()
        val result = drainAccountPages<Int> { cursor ->
            requested += cursor
            if (cursor == null) {
                DomainResult.Success(
                    AccountListPage(values = listOf(1), nextCursor = "next", hasMore = true),
                )
            } else {
                DomainResult.Failure(AccountError.NetworkUnavailable)
            }
        }

        assertEquals(DomainResult.Failure(AccountError.NetworkUnavailable), result)
        assertEquals(listOf(null, "next"), requested)
    }

    @Test
    fun `mappers de miembros e invitaciones exigen metadata de cursor coherente`() {
        val memberPage = AccountMappers.memberPage(
            mapOf(
                "members" to listOf(
                    mapOf("uid" to "uid-1", "email" to null, "role" to "OWNER"),
                ),
                "nextCursor" to "uid-1",
                "hasMore" to true,
            ),
        )
        assertEquals(
            listOf(CloudMember("uid-1", null, BusinessRole.OWNER)),
            memberPage.values,
        )
        assertEquals("uid-1", memberPage.nextCursor)
        assertTrue(memberPage.hasMore)

        val businessId = BusinessId.from(UUID.randomUUID())
        val emptyInvitationPage = AccountMappers.invitationPage(
            mapOf(
                "invitations" to emptyList<Any>(),
                "nextCursor" to "opaque-document-id",
                "hasMore" to true,
            ),
            businessId,
        )
        assertTrue(emptyInvitationPage.values.isEmpty())
        assertEquals("opaque-document-id", emptyInvitationPage.nextCursor)

        listOf(
            mapOf("members" to emptyList<Any>(), "nextCursor" to null, "hasMore" to true),
            mapOf("members" to emptyList<Any>(), "nextCursor" to "extra", "hasMore" to false),
            mapOf("members" to emptyList<Any>(), "nextCursor" to "", "hasMore" to true),
            mapOf("members" to emptyList<Any>(), "nextCursor" to "bad/cursor", "hasMore" to true),
            mapOf(
                "members" to emptyList<Any>(),
                "nextCursor" to "x".repeat(129),
                "hasMore" to true,
            ),
            mapOf("members" to emptyList<Any>(), "nextCursor" to 7, "hasMore" to true),
            mapOf("members" to emptyList<Any>(), "nextCursor" to null),
            mapOf("members" to emptyList<Any>(), "hasMore" to false),
            mapOf(
                "members" to List(101) {
                    mapOf("uid" to "uid-$it", "email" to null, "role" to "READER")
                },
                "nextCursor" to null,
                "hasMore" to false,
            ),
            mapOf(
                "members" to listOf(
                    mapOf("uid" to "uid-last", "email" to null, "role" to "READER"),
                ),
                "nextCursor" to "another-uid",
                "hasMore" to true,
            ),
        ).forEach { malformed ->
            assertUnexpected { AccountMappers.memberPage(malformed) }
        }
    }

    private fun assertUnexpected(block: () -> Unit) {
        try {
            block()
            fail("Se esperaba AccountException")
        } catch (error: AccountException) {
            assertEquals(AccountError.Unexpected, error.error)
        }
    }

    private fun cursorFor(index: Int): String = "cursor-${index.toString().padStart(3, '0')}"
}
