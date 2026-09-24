package com.facturastock.app.core.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Cerrojo de sistema operativo que impide abrir dos ventanas sobre la misma base de datos. */
class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) {
    fun release() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(directory: File): SingleInstanceLock? {
            directory.mkdirs()
            val channel = RandomAccessFile(directory.resolve(".facturastock.lock"), "rw").channel
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return SingleInstanceLock(channel, lock)
        }
    }
}
