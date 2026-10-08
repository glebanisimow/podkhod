package app.podkhod.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.podkhod.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal actual suspend fun AppDatabase.dumpTable(table: String): List<JsonObject> = withContext(Dispatchers.IO) {
    openHelper.readableDatabase.query("SELECT * FROM `$table`").use { c ->
        buildList {
            while (c.moveToNext()) {
                add(JsonObject((0 until c.columnCount).associate { i -> c.getColumnName(i) to c.value(i) }))
            }
        }
    }
}

internal actual suspend fun AppDatabase.schemaVersion(): Int = withContext(Dispatchers.IO) { openHelper.readableDatabase.version }

internal actual suspend fun AppDatabase.replaceTables(tables: List<String>, rows: Map<String, List<JsonObject>>): String? = try {
    withTransaction {
        val sdb = openHelper.writableDatabase
        tables.asReversed().forEach { sdb.delete(it, null, null) }
        tables.forEach { table ->
            val columns = columns(sdb, table)
            rows[table].orEmpty().forEach { row ->
                sdb.insert(table, SQLiteDatabase.CONFLICT_ABORT, row.toSqlValues(columns).toContentValues())
            }
        }
    }
    null
} catch (e: SQLException) {
    e.message.orEmpty()
}

private fun Cursor.value(i: Int): JsonPrimitive = when (getType(i)) {
    Cursor.FIELD_TYPE_NULL -> JsonNull
    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(i))
    Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(i))
    else -> JsonPrimitive(getString(i))
}

private fun columns(sdb: SupportSQLiteDatabase, table: String): Set<String> =
    sdb.query("PRAGMA table_info(`$table`)").use { c ->
        val name = c.getColumnIndexOrThrow("name")
        buildSet { while (c.moveToNext()) add(c.getString(name)) }
    }

private fun Map<String, SqlValue>.toContentValues() = ContentValues().apply {
    this@toContentValues.forEach { (key, v) ->
        when (v) {
            SqlValue.Null -> putNull(key)
            is SqlValue.Text -> put(key, v.value)
            is SqlValue.Integer -> put(key, v.value)
            is SqlValue.Real -> put(key, v.value)
        }
    }
}
