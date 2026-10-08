package app.podkhod.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.podkhod.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsMetaAndCreatesWorkoutTables() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO app_meta (meta_key, meta_value) VALUES ('first_launch_at', '123')")
        }

        val migrated = helper.runMigrationsAndValidate(DB, 2, true, *AppDatabase.MIGRATIONS)

        migrated.query("SELECT meta_value FROM app_meta WHERE meta_key = 'first_launch_at'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("123", c.getString(0))
        }
        val tables = mutableSetOf<String>()
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        listOf("exercise", "program", "program_day", "planned_exercise", "workout_session", "session_exercise", "set_log")
            .forEach { assertTrue("missing table $it", it in tables) }
        migrated.close()
    }

    @Test
    fun migrate2To3KeepsWorkoutLogAndAddsBusyEvents() {
        helper.createDatabase(DB_V2, 2).use { db ->
            db.execSQL(
                "INSERT INTO workout_session (id, program_id, program_day_id, day_index, title, started_at, finished_at, status, " +
                    "current_position, rest_started_at, rest_ends_at, feeling, note) " +
                    "VALUES (7, NULL, NULL, NULL, 'Old', 1000, 2000, 'finished', 0, NULL, NULL, 2, 'note')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_V2, 3, true, *AppDatabase.MIGRATIONS)

        migrated.query("SELECT title, feeling FROM workout_session WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Old", c.getString(0))
            assertEquals(2, c.getInt(1))
        }
        migrated.execSQL(
            "INSERT INTO busy_event (session_id, exercise_id, action, replacement_id, created_at) " +
                "VALUES (7, 'barbell_back_squat', 'later', NULL, 3000)",
        )
        migrated.query("SELECT COUNT(*) FROM busy_event").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate3To4KeepsProgramAndSessionsAndAddsProfileTables() {
        helper.createDatabase(DB_V3, 3).use { db ->
            db.execSQL(
                "INSERT INTO program (id, name, source, template_id, status, created_at, next_day_index) " +
                    "VALUES (3, 'Полное тело A/B', 'template', 'fullbody_ab', 'active', 1000, 1)",
            )
            db.execSQL(
                "INSERT INTO workout_session (id, program_id, program_day_id, day_index, title, started_at, finished_at, status, " +
                    "current_position, rest_started_at, rest_ends_at, feeling, note) " +
                    "VALUES (9, 3, NULL, 0, 'Old', 1000, 2000, 'finished', 0, NULL, NULL, 3, NULL)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_V3, 4, true, *AppDatabase.MIGRATIONS)

        migrated.query("SELECT next_day_index, weeks, week_index, mesocycle_index, deload_sessions_left, deload_offer FROM program WHERE id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(6, c.getInt(1))
            assertEquals(0, c.getInt(2))
            assertEquals(0, c.getInt(3))
            assertEquals(0, c.getInt(4))
            assertTrue(c.isNull(5))
        }
        migrated.query("SELECT title, deload, checkin_sleep FROM workout_session WHERE id = 9").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Old", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.isNull(2))
        }
        val tables = mutableSetOf<String>()
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        listOf("profile", "plate", "kettlebell", "dumbbell", "baseline_lift", "progression_state")
            .forEach { assertTrue("missing table $it", it in tables) }
        migrated.close()
    }

    @Test
    fun allMigrationsFromFirstVersionOpenWithRoom() {
        helper.createDatabase(DB_ALL, 1).close()
        val db = androidx.room.Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            DB_ALL,
        ).addMigrations(*AppDatabase.MIGRATIONS).build()
        db.openHelper.writableDatabase.close()
        db.close()
    }

    private companion object {
        const val DB = "migration-test"
        const val DB_ALL = "migration-test-all"
        const val DB_V2 = "migration-test-v2"
        const val DB_V3 = "migration-test-v3"
    }
}
