package app.podkhod.data.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

actual suspend fun <R> AppDatabase.withTransaction(block: suspend () -> R): R =
    useWriterConnection { it.immediateTransaction { block() } }
