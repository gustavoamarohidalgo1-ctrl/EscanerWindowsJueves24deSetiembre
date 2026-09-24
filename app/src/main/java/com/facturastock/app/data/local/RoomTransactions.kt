package com.facturastock.app.data.local

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Equivalente de escritorio de `androidx.room.withTransaction`: abre una transacción IMMEDIATE en
 * la conexión de escritura. Las llamadas DAO del bloque usan esa misma conexión, las anidadas se
 * convierten en savepoints y cualquier excepción revierte todo el bloque.
 */
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
