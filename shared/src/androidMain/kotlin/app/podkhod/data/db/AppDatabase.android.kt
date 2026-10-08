package app.podkhod.data.db

import androidx.room.withTransaction as roomTransaction

actual suspend fun <R> AppDatabase.withTransaction(block: suspend () -> R): R = roomTransaction(block)
