package com.facturastock.app.core.platform

import java.io.File

/**
 * Carpetas privadas de la aplicación en el equipo. En Windows viven bajo
 * `%LOCALAPPDATA%\FacturaStock`; equivalen a `filesDir`, `noBackupFilesDir` y `cacheDir` de la
 * versión Android, con la misma estructura interna.
 */
class AppDirectories(val root: File) {
    val filesDir: File get() = root.resolve("files").also(File::mkdirs)
    val noBackupFilesDir: File get() = root.resolve("no_backup").also(File::mkdirs)
    val cacheDir: File get() = root.resolve("cache").also(File::mkdirs)
    val databasesDir: File get() = root.resolve("databases").also(File::mkdirs)
    val dataStoreDir: File get() = root.resolve("datastore").also(File::mkdirs)
    val exportsDir: File get() = root.resolve("exports").also(File::mkdirs)

    fun databaseFile(name: String): File = databasesDir.resolve(name)

    fun preferencesDataStoreFile(name: String): File = dataStoreDir.resolve("$name.preferences_pb")

    companion object {
        const val APPLICATION_FOLDER = "FacturaStock"

        /** Ubicación estándar por sistema operativo; `FACTURASTOCK_HOME` la reemplaza en pruebas. */
        fun default(): AppDirectories {
            System.getenv("FACTURASTOCK_HOME")?.takeIf(String::isNotBlank)?.let { override ->
                return AppDirectories(File(override).also(File::mkdirs))
            }
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val base = when {
                os.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?: File(System.getProperty("user.home"), "AppData/Local")
                os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support")
                else -> System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let(::File)
                    ?: File(System.getProperty("user.home"), ".local/share")
            }
            return AppDirectories(base.resolve(APPLICATION_FOLDER).also(File::mkdirs))
        }
    }
}
