package app.podkhod.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/*
 * Schema migrations, written out by hand. They used to be @AutoMigration entries on [AppDatabase], but
 * Kotlin/Native's release linker crashes on AutoMigration annotation arguments, so the SQL Room generated
 * for them lives here verbatim. Room still validates the migrated schema against the exported JSON.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `exercise` (`id` TEXT NOT NULL, `name_ru` TEXT NOT NULL, `name_en` TEXT NOT NULL, `pattern` TEXT NOT NULL, `kind` TEXT NOT NULL, `measure` TEXT NOT NULL, `load` TEXT NOT NULL, `rep_min` INTEGER NOT NULL, `rep_max` INTEGER NOT NULL, `rest_sec` INTEGER NOT NULL, `step_kg` REAL NOT NULL, `occupancy_risk` TEXT NOT NULL, `difficulty` INTEGER NOT NULL, `fatigue` INTEGER NOT NULL, `technique_json` TEXT, `media_path` TEXT, `strava_category` TEXT, `reviewed` INTEGER NOT NULL, `catalog_version` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `exercise_equipment` (`exercise_id` TEXT NOT NULL, `equipment` TEXT NOT NULL, PRIMARY KEY(`exercise_id`, `equipment`), FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `exercise_muscle` (`exercise_id` TEXT NOT NULL, `muscle` TEXT NOT NULL, `role` TEXT NOT NULL, PRIMARY KEY(`exercise_id`, `muscle`), FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_muscle_muscle` ON `exercise_muscle` (`muscle`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `exercise_tag` (`exercise_id` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`exercise_id`, `tag`), FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `exercise_substitute` (`exercise_id` TEXT NOT NULL, `substitute_id` TEXT NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`exercise_id`, `substitute_id`), FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_substitute_substitute_id` ON `exercise_substitute` (`substitute_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `program` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `source` TEXT NOT NULL, `template_id` TEXT, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `next_day_index` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `program_day` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `program_id` INTEGER NOT NULL, `seq_index` INTEGER NOT NULL, `title` TEXT NOT NULL, FOREIGN KEY(`program_id`) REFERENCES `program`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_program_day_program_id` ON `program_day` (`program_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `planned_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `program_day_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `exercise_id` TEXT NOT NULL, `superset_group` TEXT, `sets` INTEGER NOT NULL, `rep_min` INTEGER NOT NULL, `rep_max` INTEGER NOT NULL, `rir_min` INTEGER, `rir_max` INTEGER, `rest_sec` INTEGER NOT NULL, `target_weight_kg` REAL, `note` TEXT, `locked` INTEGER NOT NULL, FOREIGN KEY(`program_day_id`) REFERENCES `program_day`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_exercise_program_day_id` ON `planned_exercise` (`program_day_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_exercise_exercise_id` ON `planned_exercise` (`exercise_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `program_id` INTEGER, `program_day_id` INTEGER, `day_index` INTEGER, `title` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `finished_at` INTEGER, `status` TEXT NOT NULL, `current_position` INTEGER NOT NULL, `rest_started_at` INTEGER, `rest_ends_at` INTEGER, `feeling` INTEGER, `note` TEXT)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_session_status` ON `workout_session` (`status`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_session_started_at` ON `workout_session` (`started_at`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `session_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `exercise_id` TEXT NOT NULL, `planned_exercise_id` INTEGER, `superset_group` TEXT, `sets` INTEGER NOT NULL, `rep_min` INTEGER NOT NULL, `rep_max` INTEGER NOT NULL, `rir_min` INTEGER, `rir_max` INTEGER, `rest_sec` INTEGER NOT NULL, `target_weight_kg` REAL, `note` TEXT, FOREIGN KEY(`session_id`) REFERENCES `workout_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_session_exercise_session_id` ON `session_exercise` (`session_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_session_exercise_exercise_id` ON `session_exercise` (`exercise_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `set_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `kind` TEXT NOT NULL, `weight_kg` REAL, `reps` INTEGER, `duration_sec` INTEGER, `rir` INTEGER, `completed_at` INTEGER NOT NULL, FOREIGN KEY(`session_exercise_id`) REFERENCES `session_exercise`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_set_log_session_exercise_id` ON `set_log` (`session_exercise_id`)")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `busy_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_id` INTEGER, `exercise_id` TEXT NOT NULL, `action` TEXT NOT NULL, `replacement_id` TEXT, `created_at` INTEGER NOT NULL, FOREIGN KEY(`session_id`) REFERENCES `workout_session`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_busy_event_session_id` ON `busy_event` (`session_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_busy_event_exercise_id` ON `busy_event` (`exercise_id`)")
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `program` ADD COLUMN `weeks` INTEGER NOT NULL DEFAULT 6")
        connection.execSQL("ALTER TABLE `program` ADD COLUMN `week_index` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `program` ADD COLUMN `mesocycle_index` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `program` ADD COLUMN `style` TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE `program` ADD COLUMN `explanation_json` TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE `program` ADD COLUMN `deload_offer` TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE `program` ADD COLUMN `deload_sessions_left` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `planned_exercise` ADD COLUMN `role` TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE `planned_exercise` ADD COLUMN `marker` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `planned_exercise` ADD COLUMN `explanation_json` TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `week_index` INTEGER DEFAULT NULL")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `deload` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `checkin_sleep` INTEGER DEFAULT NULL")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `checkin_legs` INTEGER DEFAULT NULL")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `checkin_ready` INTEGER DEFAULT NULL")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `checkin_long` INTEGER DEFAULT NULL")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `plan_note` TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE `workout_session` ADD COLUMN `progress_json` TEXT DEFAULT NULL")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER NOT NULL, `goal` TEXT NOT NULL, `priorities` TEXT NOT NULL, `days_per_week` INTEGER NOT NULL, `session_minutes` INTEGER NOT NULL, `preferred_days` TEXT NOT NULL, `long_effort_days` TEXT NOT NULL, `cardio_days` TEXT NOT NULL, `style` TEXT NOT NULL, `mesocycle_weeks` INTEGER NOT NULL, `bar_kg` REAL NOT NULL, `equipment` TEXT NOT NULL, `restriction_tags` TEXT NOT NULL, `restriction_note` TEXT NOT NULL, `body_mass_kg` REAL, `excluded_exercises` TEXT NOT NULL, `units` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `plate` (`weight_kg` REAL NOT NULL, `pairs` INTEGER NOT NULL, `color` INTEGER, PRIMARY KEY(`weight_kg`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `kettlebell` (`weight_kg` REAL NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`weight_kg`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `dumbbell` (`weight_kg` REAL NOT NULL, `pairs` INTEGER NOT NULL, PRIMARY KEY(`weight_kg`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `baseline_lift` (`movement` TEXT NOT NULL, `weight_kg` REAL, `reps` INTEGER, `e1rm_override_kg` REAL, `skipped` INTEGER NOT NULL, PRIMARY KEY(`movement`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `progression_state` (`exercise_id` TEXT NOT NULL, `working_weight_kg` REAL, `misses_in_row` INTEGER NOT NULL, `e1rm_history_json` TEXT NOT NULL, `note` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`exercise_id`))")
    }
}
