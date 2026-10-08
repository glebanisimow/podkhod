package app.podkhod.data.backup

import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.platform.AppDirs
import app.podkhod.platform.Files
import app.podkhod.platform.io
import app.podkhod.platform.nowMillis
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
    dirs: AppDirs,
    private val appVersion: String,
    private val clock: () -> Long = ::nowMillis,
) {
    private val snapshotFile = Path(dirs.files, DIR, SNAPSHOT)

    suspend fun export(): BackupFile {
        val tables = BackupFormat.TABLES.associateWith { db.dumpTable(it) }
        val s = settings.current()
        return BackupFile(
            format = BackupFormat.FORMAT,
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = db.schemaVersion(),
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
                minSets = s.minSets,
            ),
            generatorConfig = if (configs.isCustom) configs.export() else null,
        )
    }

    suspend fun read(text: String): BackupRead =
        BackupFormat.read(text, db.schemaVersion(), catalog.ids())

    suspend fun currentSessions(): Int = db.workoutDao().finishedCount()

    /** Replaces all user data with [file]; returns the number of restored sessions. */
    suspend fun restore(file: BackupFile): Int {
        if (db.workoutDao().active() != null) throw BackupException(ACTIVE_WORKOUT)
        val before = export()
        io { Files.writeText(snapshotFile, BackupFormat.encode(before)) }
        replace(file)
        return file.tables["workout_session"].orEmpty().size
    }

    /** When the snapshot taken before the last restore was made, or null when there is none. */
    suspend fun undoSnapshotAt(): Long? {
        val text = readSnapshot() ?: return null
        return (BackupFormat.read(text, Int.MAX_VALUE, catalog.ids()) as? BackupRead.Ok)?.file?.createdAt
    }

    suspend fun undoRestore() {
        if (db.workoutDao().active() != null) throw BackupException(ACTIVE_WORKOUT)
        val text = readSnapshot() ?: return
        when (val r = read(text)) {
            is BackupRead.Ok -> replace(r.file)
            is BackupRead.Invalid -> throw BackupException(r.problems.first())
        }
        io { Files.delete(snapshotFile) }
    }

    private suspend fun readSnapshot(): String? = io { snapshotFile.takeIf { Files.exists(it) }?.let { Files.readText(it) } }

    private suspend fun replace(file: BackupFile) {
        val failure = db.replaceTables(BackupFormat.TABLES, file.tables)
        if (failure != null) {
            throw BackupException("Данные в файле противоречат друг другу: ${failure.lineSequence().firstOrNull().orEmpty()}")
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
                minSets = s.minSets,
            )
        }
        // A config the installed version rejects falls back to the bundled one rather than failing the restore.
        configs.reset()
        file.generatorConfig?.let { configs.import(it) }
    }

    companion object {
        private const val DIR = "backup"
        private const val SNAPSHOT = "before_restore.json"
        const val ACTIVE_WORKOUT = "active_workout"
    }
}

/** A value of one restored column, typed the way SQLite should store it. */
sealed interface SqlValue {
    data object Null : SqlValue
    data class Text(val value: String) : SqlValue
    data class Integer(val value: Long) : SqlValue
    data class Real(val value: Double) : SqlValue
}

/** Only columns the current table has; older files simply lack the newer ones, which have defaults. */
internal fun JsonObject.toSqlValues(columns: Set<String>): Map<String, SqlValue> = buildMap {
    this@toSqlValues.forEach { (key, value) ->
        if (key !in columns) return@forEach
        val p = value as JsonPrimitive
        val long = p.longOrNull
        val double = p.doubleOrNull
        val bool = p.booleanOrNull
        put(
            key,
            when {
                p is JsonNull -> SqlValue.Null
                p.isString -> SqlValue.Text(p.content)
                long != null -> SqlValue.Integer(long)
                double != null -> SqlValue.Real(double)
                bool != null -> SqlValue.Integer(if (bool) 1L else 0L)
                else -> SqlValue.Text(p.content)
            },
        )
    }
}

/** Every row of [table] as column name to JSON value. */
internal expect suspend fun AppDatabase.dumpTable(table: String): List<JsonObject>

/** PRAGMA user_version: the Room schema version of the open database. */
internal expect suspend fun AppDatabase.schemaVersion(): Int

/**
 * In one transaction: empties [tables] (children first) and inserts [rows] in table order. Returns the
 * database's message when a row breaks a constraint; nothing is changed then.
 */
internal expect suspend fun AppDatabase.replaceTables(tables: List<String>, rows: Map<String, List<JsonObject>>): String?
