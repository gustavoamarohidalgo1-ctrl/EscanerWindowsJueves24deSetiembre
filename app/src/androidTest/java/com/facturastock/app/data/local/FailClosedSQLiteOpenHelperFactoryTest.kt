package com.facturastock.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailClosedSQLiteOpenHelperFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "fail-closed-open-helper-test.db"

    @Before
    fun deleteBefore() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun deleteAfter() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun guardedFactoryPreservesLifecycleAndCreatesUsableDatabase() {
        val delegate = RecordingCallback()
        val configuration = configuration(delegate)
        val helper = FailClosedSQLiteOpenHelperFactory().create(configuration)

        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO sample(value) VALUES ('durable')")
            database.query("SELECT value FROM sample").use { cursor ->
                cursor.moveToFirst()
                assertEquals("durable", cursor.getString(0))
            }
        } finally {
            helper.close()
        }

        assertEquals(1, delegate.configureCalls)
        assertEquals(1, delegate.createCalls)
        assertEquals(1, delegate.openCalls)
        assertFalse(delegate.corruptionCalled)
    }

    @Test
    fun corruptionNeverDelegatesToTheDeletingDefaultHandler() {
        val delegate = RecordingCallback()
        val guarded = delegate.failClosedOnCorruption()
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        val protectedFiles =
            listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
                .onEachIndexed { index, file ->
                    file.writeBytes("preserve-$index".encodeToByteArray())
                }
        val originalBytes = protectedFiles.associateWith(File::readBytes)

        val failure = assertThrows(SQLiteException::class.java) {
            guarded.onCorruption(fakeDatabase(databaseFile.path))
        }

        assertEquals(CORRUPTION_FAILURE_MESSAGE, failure.message)
        assertFalse(delegate.corruptionCalled)
        protectedFiles.forEach { file ->
            assertArrayEquals(
                "No debe borrar ${file.name}",
                originalBytes.getValue(file),
                file.readBytes(),
            )
        }
    }

    @Test
    fun factoryRejectsAnyFutureOptInToDestructiveRecovery() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(RecordingCallback())
            .allowDataLossOnRecovery(true)
            .build()

        assertThrows(IllegalStateException::class.java) {
            FailClosedSQLiteOpenHelperFactory().create(configuration)
        }
    }

    @Test
    fun corruptDatabaseFileIsPreservedForAnExplicitRecoveryPath() {
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes("not-a-sqlite-database".encodeToByteArray())
        val delegate = RecordingCallback()
        val helper = FailClosedSQLiteOpenHelperFactory().create(configuration(delegate))

        try {
            assertThrows(SQLiteException::class.java) {
                helper.writableDatabase
            }
        } finally {
            helper.close()
        }

        assertTrue("La base corrupta debe conservarse", databaseFile.exists())
        assertTrue("La base corrupta no debe truncarse", databaseFile.length() > 0L)
        assertFalse(delegate.corruptionCalled)
    }

    private fun configuration(
        callback: SupportSQLiteOpenHelper.Callback,
    ): SupportSQLiteOpenHelper.Configuration =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(callback)
            .allowDataLossOnRecovery(false)
            .build()

    private fun fakeDatabase(path: String? = null): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPath" -> path ?: ":memory:"
                "getAttachedDbs" ->
                    path?.let { listOf(android.util.Pair.create("main", it)) } ?: emptyList<Any>()
                else ->
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        else -> null
                    }
            }
        } as SupportSQLiteDatabase

    private class RecordingCallback : SupportSQLiteOpenHelper.Callback(1) {
        var configureCalls: Int = 0
        var createCalls: Int = 0
        var openCalls: Int = 0
        var corruptionCalled: Boolean = false

        override fun onConfigure(db: SupportSQLiteDatabase) {
            configureCalls += 1
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            createCalls += 1
            db.execSQL("CREATE TABLE sample(value TEXT NOT NULL)")
        }

        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit

        override fun onOpen(db: SupportSQLiteDatabase) {
            openCalls += 1
        }

        override fun onCorruption(db: SupportSQLiteDatabase) {
            corruptionCalled = true
            super.onCorruption(db)
        }
    }
}
