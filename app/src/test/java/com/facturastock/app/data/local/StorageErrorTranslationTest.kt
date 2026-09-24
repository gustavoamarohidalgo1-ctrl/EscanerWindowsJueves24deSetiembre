package com.facturastock.app.data.local

import com.facturastock.app.data.local.sqlite.SQLiteException
import com.facturastock.app.data.local.sqlite.SQLiteFullException
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class StorageErrorTranslationTest {
    @Test
    fun sqliteFullIsReportedAsInsufficientSpaceWithoutLosingTheCause() = runBlocking {
        val cause = SQLiteFullException("database or disk is full")

        val translated = capture { storageCatching<Unit> { throw cause } }

        assertEquals(StorageError.InsufficientSpace, translated.error)
        assertSame(cause, translated.cause)
    }

    @Test
    fun wrappedSqliteFullAndEnospcIoAreReportedAsInsufficientSpace() = runBlocking {
        val wrapped = capture {
            storageCatching<Unit> {
                throw SQLiteException("write failed", IOException("ENOSPC"))
            }
        }
        val io = capture {
            storageCatching<Unit> { throw IOException("No space left on device") }
        }

        assertEquals(StorageError.InsufficientSpace, wrapped.error)
        assertEquals(StorageError.InsufficientSpace, io.error)
    }

    private suspend fun capture(block: suspend () -> Unit): StorageException {
        try {
            block()
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            return expected
        }
        error("inalcanzable")
    }
}
