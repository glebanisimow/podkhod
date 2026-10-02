package app.podkhod.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

class BackupException(message: String) : Exception(message)

/**
 * Export and restore of all user data. Restore replaces everything in one transaction: a file that
 * breaks a constraint halfway leaves the current data untouched. Before replacing, the current data is
 * written to an internal snapshot so the last restore can be undone.
 */
class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val configs: GeneratorConfigStore,
    private val catalog: CatalogRepository,
    filesDir: File,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val snapshotFile = File(File(filesDir, DIR), SNAPSHOT)

    suspend fun export(): BackupFile = withContext(Dispatchers.IO) {
        val sdb = db.openHelper.readableDatabase
        val tables = BackupFormat.TABLES.associateWith { dump(sdb, it) }
        val s = settings.current()
        BackupFile(
            format = BackupFormat.FORMAT,
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = sdb.version,
            appVersion = appVersion,
            createdAt = clock(),
            tables = BackupFormat.withoutActiveSessions(tables),
            settings = SettingsBackup(
                keepScreenOn = s.keepScreenOn,
                oneHandMode = s.oneHandMode,
                restSound = s.restSound,
                restVibration = s.restVibration,
                supersetRestAfterEach = s.supersetRestAfterEach,
                animations = s.animations,
                accessoryWarmupSets = s.accessoryWarmupSets,
            ),
            generatorConfig = if (configs.isCustom) configs.export() else null,
        )
    }

    suspend fun read(text: String): BackupRead =
        BackupFormat.read(text, db.openHelper.readableDatabase.version, catalog.ids())

    suspend fun currentSessions(): Int = db.workoutDao().finishedCount()

    /** Replaces all user data with [file]; returns the number of restored sessions. */
    suspend fun restore(file: BackupFile): Int {
        if (db.workoutDao().active() != null) throw BackupException(ACTIVE_WORKOUT)
        val before = export()
        withContext(Dispatchers.IO) {
            snapshotFile.parentFile?.mkdirs()
            snapshotFile.writeText(BackupFormat.encode(before), Charsets.UTF_8)
        }
        replace(file)
        return file.tables["workout_session"].orEmpty().size
    }

    /** When the snapshot taken before the last restore was made, or null when there is none. */
    suspend fun undoSnapshotAt(): Long? = withContext(Dispatchers.IO) {
        if (!snapshotFile.exists()) return@withContext null
        (BackupFormat.read(snapshotFile.readText(Charsets.UTF_8), Int.MAX_VALUE, catalog.ids()) as? BackupRead.Ok)?.file?.createdAt
    }

    suspend fun undoRestore() {
        if (db.workoutDao().active() != null) throw BackupException(ACTIVE_WORKOUT)
        val text = withContext(Dispatchers.IO) { snapshotFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) } ?: return
        when (val r = read(text)) {
            is BackupRead.Ok -> replace(r.file)
            is BackupRead.Invalid -> throw BackupException(r.problems.first())
        }
        withContext(Dispatchers.IO) { snapshotFile.delete() }
    }

    private suspend fun replace(file: BackupFile) {
        try {
            db.withTransaction {
                val sdb = db.openHelper.writableDatabase
                BackupFormat.TABLES.asReversed().forEach { sdb.delete(it, null, null) }
                BackupFormat.TABLES.forEach { table ->
                    val columns = columns(sdb, table)
                    file.tables[table].orEmpty().forEach { row ->
                        sdb.insert(table, SQLiteDatabase.CONFLICT_ABORT, row.toValues(columns))
                    }
                }
            }
        } catch (e: android.database.SQLException) {
            throw BackupException("Данные в файле противоречат друг другу: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
        file.settings?.let { s ->
            settings.restore(
                keepScreenOn = s.keepScreenOn,
                oneHandMode = s.oneHandMode,
                restSound = s.restSound,
                restVibration = s.restVibration,
                supersetRestAfterEach = s.supersetRestAfterEach,
                animations = s.animations,
                accessoryWarmupSets = s.accessoryWarmupSets,
            )
        }
        // A config the installed version rejects falls back to the bundled one rather than failing the restore.
        configs.reset()
        file.generatorConfig?.let { configs.import(it) }
    }

    private fun dump(sdb: SupportSQLiteDatabase, table: String): List<JsonObject> =
        sdb.query("SELECT * FROM `$table`").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(JsonObject((0 until c.columnCount).associate { i -> c.getColumnName(i) to c.value(i) }))
                }
            }
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

    /** Only columns the current table has; older files simply lack the newer ones, which have defaults. */
    private fun JsonObject.toValues(columns: Set<String>) = ContentValues().apply {
        this@toValues.forEach { (key, value) ->
            if (key !in columns) return@forEach
            val p = value as JsonPrimitive
            when {
                p is JsonNull -> putNull(key)
                p.isString -> put(key, p.content)
                p.longOrNull != null -> put(key, p.longOrNull)
                p.doubleOrNull != null -> put(key, p.doubleOrNull)
                p.booleanOrNull != null -> put(key, if (p.booleanOrNull == true) 1L else 0L)
                else -> put(key, p.content)
            }
        }
    }

    companion object {
        private const val DIR = "backup"
        private const val SNAPSHOT = "before_restore.json"
        const val ACTIVE_WORKOUT = "active_workout"
    }
}
