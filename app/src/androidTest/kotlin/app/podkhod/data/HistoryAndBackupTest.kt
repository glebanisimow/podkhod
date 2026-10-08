package app.podkhod.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.podkhod.data.backup.BackupException
import app.podkhod.data.backup.BackupFormat
import app.podkhod.data.backup.BackupRead
import app.podkhod.data.backup.BackupRepository
import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.data.program.ProgramRepository
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.stats.ProgressRepository
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.plan.Baseline
import app.podkhod.domain.plan.BaselineLift
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.stats.ProgressMetric
import app.podkhod.domain.workout.SupersetRestMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.mp.KoinPlatform
import app.podkhod.platform.AppDirs
import kotlinx.io.files.Path
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryAndBackupTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1_000_000L
    private val files = File(context.cacheDir, "stage4-test")

    private lateinit var db: AppDatabase
    private lateinit var programs: ProgramRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var plans: PlanRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var backups: BackupRepository
    private lateinit var progress: ProgressRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        files.deleteRecursively()
        files.mkdirs()
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB).build()
        val assets = AssetReader()
        val catalog = CatalogRepository(db.catalogDao(), db.appMetaDao(), assets)
        // The app's own settings store: a second DataStore on the same file is not allowed.
        val settings = KoinPlatform.getKoin().get<SettingsRepository>()
        val configs = GeneratorConfigStore(AppDirs(Path(File(files, "config").apply { mkdirs() }.path)), catalog)
        programs = ProgramRepository(db, assets) { clock }
        profiles = ProfileRepository(db) { clock }
        plans = PlanRepository(db, catalog, profiles, configs, settings, { clock }, { 1 })
        workouts = WorkoutRepository(db, catalog, programs, plans) { clock }
        backups = BackupRepository(db, settings, configs, catalog, AppDirs(Path(files.path)), "test") { clock }
        progress = ProgressRepository(db, catalog, configs)
        runBlocking {
            catalog.seedIfNeeded()
            programs.ensureDefaultProgram()
            val profile = Profile(daysPerWeek = 3, sessionMinutes = 45, baselines = mapOf(BaselineLift.SQUAT to Baseline(100.0, 5)))
            profiles.save(profile)
            plans.apply(plans.preview(profile), profile)
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB)
        files.deleteRecursively()
    }

    private suspend fun squatDay() = programs.activeProgram.first()!!.days.first { d -> d.exercises.any { it.exerciseId == SQUAT } }

    private suspend fun squatTarget(): Double = squatDay().exercises.first { it.exerciseId == SQUAT }.targetWeightKg!!

    /** Logs every squat set at [reps] with the planned weight and finishes; returns the session id. */
    private suspend fun squatSession(reps: (Int) -> Int): Long {
        val sessionId = workouts.startFromProgramDay(squatDay().day.id)
        val slot = workouts.observeDetail(sessionId).first()!!.slots.first { it.exercise.id == SQUAT }
        val weight = slot.slot.targetWeightKg!!
        repeat(slot.slot.sets) { i ->
            clock += 120_000
            workouts.logSet(sessionId, slot.slot.id, SetKind.WORK, weight, reps(slot.slot.repMax), null, SupersetRestMode.AFTER_ROUND)
        }
        clock += 600_000
        workouts.finish(sessionId)
        clock += 86_400_000
        return sessionId
    }

    private suspend fun squatState() = db.profileDao().progressionStates(listOf(SQUAT)).singleOrNull()

    @Test
    fun replayWithoutEditsReproducesIncrementalProgression() = runBlocking {
        squatSession { it }
        squatSession { it }
        val incremental = squatState()!!
        val target = squatTarget()
        plans.recomputeProgression(setOf(SQUAT), 0)
        val replayed = squatState()!!
        assertEquals(incremental.workingWeightKg, replayed.workingWeightKg)
        assertEquals(incremental.missesInRow, replayed.missesInRow)
        assertEquals(incremental.e1rmHistoryJson, replayed.e1rmHistoryJson)
        assertEquals(target, squatTarget(), 1e-9)
    }

    @Test
    fun editingAPastSetReplaysProgressionAndDeletingRemovesIt() = runBlocking {
        val start = squatTarget()
        val sessionId = squatSession { it }
        assertTrue(squatTarget() > start)

        // The last set was really two reps short: no step up after all.
        val slot = workouts.observeDetail(sessionId).first()!!.slots.first { it.exercise.id == SQUAT }
        val last = slot.sets.last()
        workouts.updateFinishedSet(last.copy(reps = slot.slot.repMin - 1))
        assertEquals(start, squatTarget(), 1e-9)
        assertEquals(start, squatState()!!.workingWeightKg!!, 1e-9)
        assertEquals(1, squatState()!!.missesInRow)
        val line = PlanRepository.decodeProgress(db.workoutDao().session(sessionId)!!.progressJson).single { it.exerciseId == SQUAT }
        assertEquals("hold", line.change)

        // Adding the set back at the top of the range restores the step up.
        workouts.deleteFinishedSet(last)
        workouts.addFinishedSet(slot.slot.id, SetKind.WORK, last.weightKg, slot.slot.repMax, null)
        assertTrue(squatTarget() > start)

        workouts.deleteFinished(sessionId)
        assertNull(db.workoutDao().session(sessionId))
        assertNull(squatState())
    }

    @Test
    fun progressShowsOnePointPerSession() = runBlocking {
        squatSession { it }
        squatSession { it - 1 }
        val p = progress.exercise(SQUAT).first()!!
        assertEquals(ProgressMetric.E1RM, p.metric)
        assertEquals(2, p.points.size)
        assertTrue(p.points.all { it.e1rmKg != null })
        val meso = progress.mesocycle.first()
        assertNotNull(meso)
        assertTrue(meso!!.weeks.first().groups.first { it.group.code == "quads" }.sets > 0)
    }

    @Test
    fun backupRestoresEverythingAndUndoReturnsThePreviousData() = runBlocking {
        squatSession { it }
        val profileBefore = profiles.current()
        val stateBefore = squatState()
        val text = BackupFormat.encode(backups.export())

        // Lose the data after the backup was made.
        workouts.deleteFinished(workouts.history.first().single().id)
        assertTrue(workouts.history.first().isEmpty())

        val read = backups.read(text) as BackupRead.Ok
        assertEquals(1, read.preview.sessions)
        assertEquals(1, backups.restore(read.file))
        assertEquals(1, workouts.history.first().size)
        assertEquals(profileBefore, profiles.current())
        assertEquals(stateBefore, squatState())
        assertEquals(PlanRepository.SOURCE_GENERATED, programs.activeProgram.first()!!.program.source)
        assertNotNull(backups.undoSnapshotAt())

        backups.undoRestore()
        assertTrue(workouts.history.first().isEmpty())
        assertNull(backups.undoSnapshotAt())
    }

    @Test
    fun restoreIsRefusedDuringAWorkoutAndActiveSessionsAreNotExported() = runBlocking {
        squatSession { it }
        workouts.startFromProgramDay(squatDay().day.id)
        val file = backups.export()
        assertEquals(1, file.tables.getValue("workout_session").size)
        try {
            backups.restore(file)
            fail("restore must not run during a workout")
        } catch (e: BackupException) {
            assertEquals(BackupRepository.ACTIVE_WORKOUT, e.message)
        }
    }

    private companion object {
        const val DB = "stage4-test.db"
        const val SQUAT = "barbell_back_squat"
    }
}
