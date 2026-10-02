package app.podkhod.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The backup file: every user table as rows of column → value, plus settings and a custom generator
 * config. Rows are stored by column name, not by entity class, so a file from an older schema still
 * restores: auto-migrations only ever add nullable columns or columns with a default, and those are
 * simply absent from older rows. A manual migration that renames or splits a column must add a
 * conversion here, keyed by [BackupFile.schemaVersion].
 */
@Serializable
data class BackupFile(
    val format: String,
    val formatVersion: Int,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val tables: Map<String, List<JsonObject>>,
    val settings: SettingsBackup? = null,
    /** The imported generator config; null when the bundled one is used. */
    val generatorConfig: String? = null,
)

/** Settings by stable names; missing keys keep their defaults, so new settings never break old files. */
@Serializable
data class SettingsBackup(
    val keepScreenOn: Boolean = true,
    val oneHandMode: Boolean = false,
    val restSound: Boolean = true,
    val restVibration: Boolean = true,
    val supersetRestAfterEach: Boolean = false,
    val animations: Boolean = true,
    val accessoryWarmupSets: Int = 1,
)

data class BackupPreview(
    val createdAt: Long,
    val appVersion: String,
    val sessions: Int,
    val sets: Int,
    val hasProfile: Boolean,
)

sealed interface BackupRead {
    data class Ok(val file: BackupFile, val preview: BackupPreview) : BackupRead
    data class Invalid(val problems: List<String>) : BackupRead
}

object BackupFormat {
    const val FORMAT = "podkhod-backup"
    const val FORMAT_VERSION = 1

    /**
     * User tables in insert order (parents before children). The catalog and app_meta are not user data:
     * they come from the bundled assets of the installed version.
     */
    val TABLES = listOf(
        "profile", "plate", "kettlebell", "dumbbell", "baseline_lift", "progression_state",
        "program", "program_day", "planned_exercise",
        "workout_session", "session_exercise", "set_log", "busy_event",
    )

    /** Columns that point into the catalog; restore refuses exercises the installed catalog lacks. */
    val EXERCISE_COLUMNS = mapOf(
        "planned_exercise" to "exercise_id",
        "session_exercise" to "exercise_id",
        "busy_event" to "exercise_id",
        "progression_state" to "exercise_id",
    )

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun read(text: String, currentSchema: Int, catalogIds: Set<String>): BackupRead {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrElse {
            return BackupRead.Invalid(listOf("Файл не похож на копию данных «Подхода»."))
        }
        val problems = buildList {
            if (file.format != FORMAT) add("Файл не похож на копию данных «Подхода».")
            if (file.formatVersion > FORMAT_VERSION) add("Копия сделана более новой версией приложения (формат ${file.formatVersion}). Обнови приложение.")
            if (file.schemaVersion > currentSchema) add("Копия сделана более новой версией приложения (${file.appVersion}). Обнови приложение.")
            (file.tables.keys - TABLES.toSet()).forEach { add("Неизвестная таблица в копии: $it.") }
            file.tables.forEach { (table, rows) ->
                if (rows.any { row -> row.values.any { it !is JsonPrimitive } }) {
                    add("Таблица $table: значения должны быть простыми.")
                }
            }
            val missing = EXERCISE_COLUMNS.flatMap { (table, column) ->
                file.tables[table].orEmpty().mapNotNull { row -> (row[column] as? JsonPrimitive)?.content }
            }.toSet() - catalogIds
            if (missing.isNotEmpty()) add("В каталоге нет упражнений из копии: ${missing.sorted().take(MAX_LISTED).joinToString()}.")
        }
        if (problems.isNotEmpty()) return BackupRead.Invalid(problems)
        return BackupRead.Ok(file, preview(file))
    }

    fun preview(file: BackupFile): BackupPreview = BackupPreview(
        createdAt = file.createdAt,
        appVersion = file.appVersion,
        sessions = file.tables["workout_session"].orEmpty().size,
        sets = file.tables["set_log"].orEmpty().size,
        hasProfile = file.tables["profile"].orEmpty().isNotEmpty(),
    )

    /**
     * Only finished sessions go into a backup: an active one would come back without its foreground
     * service and rest timer. Busy events of dropped sessions keep the station statistics but lose the link.
     */
    fun withoutActiveSessions(tables: Map<String, List<JsonObject>>): Map<String, List<JsonObject>> {
        val sessions = tables["workout_session"].orEmpty()
        val keep = sessions.filter { (it["status"] as? JsonPrimitive)?.content == FINISHED }.mapNotNull { it.id() }.toSet()
        val slots = tables["session_exercise"].orEmpty().filter { it.long("session_id") in keep }
        val slotIds = slots.mapNotNull { it.id() }.toSet()
        return tables + mapOf(
            "workout_session" to sessions.filter { it.id() in keep },
            "session_exercise" to slots,
            "set_log" to tables["set_log"].orEmpty().filter { it.long("session_exercise_id") in slotIds },
            "busy_event" to tables["busy_event"].orEmpty().map { e ->
                val sid = e.long("session_id")
                if (sid == null || sid in keep) e else JsonObject(e + ("session_id" to JsonNull))
            },
        )
    }

    private fun JsonObject.id(): Long? = long("id")
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private const val FINISHED = "finished"
    private const val MAX_LISTED = 5
}
