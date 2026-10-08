package app.podkhod.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration

/**
 * Every schema change bumps [version], adds a [Migration] to [MIGRATIONS] (see Migrations.kt: no
 * AutoMigration, Kotlin/Native cannot link it) and commits the exported JSON from shared/schemas together
 * with a migration test. Destructive fallback is intentionally not enabled: losing the training log on update is worse
 * than a crash that reports a missing migration.
 */
@Database(
    entities = [
        AppMetaEntity::class,
        ExerciseEntity::class,
        ExerciseEquipmentEntity::class,
        ExerciseMuscleEntity::class,
        ExerciseTagEntity::class,
        ExerciseSubstituteEntity::class,
        ProgramEntity::class,
        ProgramDayEntity::class,
        PlannedExerciseEntity::class,
        WorkoutSessionEntity::class,
        SessionExerciseEntity::class,
        SetLogEntity::class,
        BusyEventEntity::class,
        ProfileEntity::class,
        PlateEntity::class,
        KettlebellEntity::class,
        DumbbellEntity::class,
        BaselineLiftEntity::class,
        ProgressionStateEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appMetaDao(): AppMetaDao
    abstract fun catalogDao(): CatalogDao
    abstract fun programDao(): ProgramDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "podkhod.db"
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}

// Room generates the actual objects for every target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Runs [block] in one write transaction; DAO calls inside it join the transaction. */
expect suspend fun <R> AppDatabase.withTransaction(block: suspend () -> R): R
