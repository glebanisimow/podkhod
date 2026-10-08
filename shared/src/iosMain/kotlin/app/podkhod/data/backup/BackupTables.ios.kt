package app.podkhod.data.backup

import androidx.room.Transactor
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteException
import app.podkhod.data.db.AppDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal actual suspend fun AppDatabase.dumpTable(table: String): List<JsonObject> = useReaderConnection { conn ->
    conn.usePrepared("SELECT * FROM `$table`") { stmt ->
        buildList {
            while (stmt.step()) {
                add(
                    JsonObject(
                        (0 until stmt.getColumnCount()).associate { i ->
                            stmt.getColumnName(i) to when (stmt.getColumnType(i)) {
                                SQLITE_DATA_NULL -> JsonNull
                                SQLITE_DATA_INTEGER -> JsonPrimitive(stmt.getLong(i))
                                SQLITE_DATA_FLOAT -> JsonPrimitive(stmt.getDouble(i))
                                else -> JsonPrimitive(stmt.getText(i))
                            }
                        },
                    ),
                )
            }
        }
    }
}

internal actual suspend fun AppDatabase.schemaVersion(): Int = useReaderConnection { conn ->
    conn.usePrepared("PRAGMA user_version") { stmt -> if (stmt.step()) stmt.getLong(0).toInt() else 0 }
}

internal actual suspend fun AppDatabase.replaceTables(tables: List<String>, rows: Map<String, List<JsonObject>>): String? = try {
    useWriterConnection { conn ->
        conn.immediateTransaction {
            tables.asReversed().forEach { table -> conn.usePrepared("DELETE FROM `$table`") { it.step() } }
            tables.forEach { table ->
                val columns = columns(conn, table)
                rows[table].orEmpty().forEach { row -> insert(conn, table, row.toSqlValues(columns)) }
            }
        }
    }
    null
} catch (e: SQLiteException) {
    e.message.orEmpty()
}

private suspend fun columns(conn: Transactor, table: String): Set<String> =
    conn.usePrepared("PRAGMA table_info(`$table`)") { stmt ->
        val name = (0 until stmt.getColumnCount()).first { stmt.getColumnName(it) == "name" }
        buildSet { while (stmt.step()) add(stmt.getText(name)) }
    }

private suspend fun insert(conn: Transactor, table: String, values: Map<String, SqlValue>) {
    if (values.isEmpty()) return
    val keys = values.keys.toList()
    val sql = "INSERT INTO `$table` (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})"
    conn.usePrepared(sql) { stmt ->
        keys.forEachIndexed { i, key ->
            when (val v = values.getValue(key)) {
                SqlValue.Null -> stmt.bindNull(i + 1)
                is SqlValue.Text -> stmt.bindText(i + 1, v.value)
                is SqlValue.Integer -> stmt.bindLong(i + 1, v.value)
                is SqlValue.Real -> stmt.bindDouble(i + 1, v.value)
            }
        }
        stmt.step()
    }
}
