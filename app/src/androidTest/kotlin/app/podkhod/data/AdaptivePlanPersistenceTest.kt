package app.podkhod.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.data.program.ProgramRepository
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.plan.AdaptivePlanner
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.plan.Workload
import app.podkhod.domain.workout.SupersetRestMode
import app.podkhod.platform.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Room transactions, archived program revisions, settings and immutable workout snapshots. */
@RunWith(AndroidJUnit4::class)
class AdaptivePlanPersistenceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = File(context.cacheDir, "adaptive-plan-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var catalog: CatalogRepository
    private lateinit var configs: GeneratorConfigStore
    private lateinit var plans: PlanRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var programs: ProgramRepository
    private val profile = Profile(sessionMinutes = 60)

    @Before fun setup() = runBlocking<Unit> {
        files.mkdirs()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val assets = AssetReader()
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(files, "${System.nanoTime()}.preferences_pb") })
        catalog = CatalogRepository(db.catalogDao(), db.appMetaDao(), assets)
        configs = GeneratorConfigStore(AppDirs(Path(files.path)), catalog)
        val profiles = ProfileRepository(db) { now }
        programs = ProgramRepository(db, assets) { now }
        plans = PlanRepository(db, catalog, profiles, configs, settings, { now }, { 1 })
        workouts = WorkoutRepository(db, catalog, programs, plans) { now }
        catalog.seedIfNeeded()
        profiles.save(profile)
        plans.apply(plans.preview(profile), profile)
    }

    @After fun cleanup() {
        scope.cancel()
        db.close()
        files.deleteRecursively()
    }

    private suspend fun active() = db.programDao().active()!!
    private suspend fun days() = db.programDao().days(active().id).sortedBy { it.day.seqIndex }
    private suspend fun explanation() = PlanRepository.decodeExplanation(active().explanationJson)!!

    private suspend fun start(): Long = workouts.startFromProgramDay(days()[active().nextDayIndex].day.id)

    private suspend fun finishAll(id: Long) {
        val detail = workouts.observeDetail(id).first()!!
        detail.slots.forEach { s ->
            repeat(s.slot.sets) {
                workouts.logSet(id, s.slot.id, SetKind.WORK, s.slot.targetWeightKg ?: 20.0, s.slot.repMax, null, SupersetRestMode.AFTER_ROUND)
            }
        }
        now += 3_600_000
        workouts.finish(id)
    }

    @Test fun legacyPlanIsRebuiltOnceAndArchivedHistoryStillContributes() = runBlocking<Unit> {
        val session = start()
        finishAll(session)
        val previous = active()
        val historyBefore = db.workoutDao().slots(session)
        db.programDao().setExplanation(previous.id, """{"notes":["legacy"],"minutes":[45,45,45]}""")
        plans.applyMinSets()
        val rebuilt = active()
        assertNotEquals(previous.id, rebuilt.id)
        assertEquals(previous.weekIndex, rebuilt.weekIndex)
        assertEquals(previous.nextDayIndex, rebuilt.nextDayIndex)
        assertEquals(historyBefore, db.workoutDao().slots(session))
        assertEquals("archived", db.programDao().program(previous.id)!!.status)
        assertEquals(AdaptivePlanner.VERSION, explanation().plannerVersion)
        assertEquals(1, explanation().historyCount)
        val snapshot = days()
        val reason = explanation()
        repeat(3) { plans.refreshAdaptivePlan() }
        assertEquals(rebuilt.id, active().id)
        assertEquals(snapshot, days())
        assertEquals(reason, explanation())
        assertForecast()
    }

    @Test fun settingsWaitForActiveWorkoutAndThenRebalanceTogether() = runBlocking<Unit> {
        val id = start()
        val previousProgram = active()
        val snapshot = db.workoutDao().slots(id)
        settings.setMinSets(4)
        plans.applyMinSets()
        assertEquals(previousProgram, active())
        assertEquals(snapshot, db.workoutDao().slots(id))
        finishAll(id)
        assertNotEquals(previousProgram.id, active().id)
        assertEquals(snapshot.map { it.slot }, db.workoutDao().slots(id).map { it.slot })
        assertTrue(days().flatMap { it.exercises }.all { it.sets >= 4 })
        assertEquals(1, active().nextDayIndex)
        assertForecast()
    }

    @Test fun historyEditsDeleteAndWarmupsAreReflectedWithoutChangingLoggedWork() = runBlocking<Unit> {
        val id = start()
        val slot = db.workoutDao().slots(id).first().slot
        workouts.logSet(id, slot.id, SetKind.WARMUP, 20.0, 10, null, SupersetRestMode.AFTER_ROUND)
        val workId = workouts.logSet(id, slot.id, SetKind.WORK, 40.0, slot.repMin, null, SupersetRestMode.AFTER_ROUND)
        workouts.finish(id)
        assertForecast()
        val before = db.workoutDao().slots(id)
        workouts.updateFinishedSet(db.workoutDao().set(workId)!!.copy(kind = SetKind.WARMUP.code))
        assertEquals(before.sumOf { it.sets.size }, db.workoutDao().slots(id).sumOf { it.sets.size })
        assertForecast()
        workouts.deleteFinished(id)
        assertEquals(0, explanation().historyCount)
        assertEquals(1, active().nextDayIndex) // Editing history does not rewind program rotation.
        assertForecast()
    }

    @Test fun manualSetsAndLocksSurviveReplayAndPlanningSettingChanges() = runBlocking<Unit> {
        val row = days().first().exercises.first()
        plans.changeSets(row.id, -1)
        val manualSets = db.programDao().planned(row.id)!!.sets
        plans.refreshAdaptivePlan()
        assertEquals(manualSets, db.programDao().planned(row.id)!!.sets)
        settings.setSupersetRestAfterEach(true)
        plans.refreshAdaptivePlan()
        val kept = days().first().exercises.first { it.exerciseId == row.exerciseId }
        assertEquals(manualSets, kept.sets)
        assertTrue(kept.locked)
        assertForecast()
    }

    @Test fun skippingLastDayAdvancesWeekWithoutFabricatingAWorkout() = runBlocking<Unit> {
        repeat(profile.daysPerWeek) { programs.skipNextDay(); plans.refreshAdaptivePlan() }
        assertEquals(1, active().weekIndex)
        assertEquals(0, active().nextDayIndex)
        assertEquals(0, db.workoutDao().finishedCount())
        assertEquals(0, explanation().historyCount)
        assertForecast()
    }

    private suspend fun assertForecast() {
        val program = active()
        val prefs = settings.current()
        val metrics = Workload(configs.config().withMinSets(prefs.minSets), profile, catalog.exerciseInfos(), prefs.accessoryWarmupSets, prefs.supersetRestMode)
        val history = db.workoutDao().finishedInMesocycle(program.mesocycleIndex).filter { it.weekIndex == program.weekIndex }
        val actual = history.flatMap { db.workoutDao().slots(it.id) }.groupBy { it.slot.exerciseId }
            .mapValues { (_, slots) -> slots.sumOf { s -> s.sets.count { SetKind.fromCode(it.kind).countsAsWork } } }
        val upcoming = days().filter { it.day.seqIndex >= program.nextDayIndex && it.day.seqIndex !in history.map { s -> s.dayIndex } }
            .flatMap { it.exercises }.groupBy { it.exerciseId }.mapValues { (_, rows) -> rows.sumOf { it.sets } }
        val actualVolume = metrics.volume(actual)
        val upcomingVolume = metrics.volume(upcoming)
        explanation().forecast.forEach { v ->
            val group = app.podkhod.domain.plan.MuscleGroup.fromCode(v.group)!!
            assertEquals(v.group, actualVolume.getValue(group) + upcomingVolume.getValue(group), v.sets, 1e-9)
        }
    }
}
