package app.podkhod.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.db.BusyEventEntity
import app.podkhod.data.db.WorkoutSessionEntity
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.data.program.ProgramRepository
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.plan.Baseline
import app.podkhod.domain.plan.BaselineLift
import app.podkhod.domain.plan.Checkin
import app.podkhod.domain.plan.Profile
import java.io.File
import app.podkhod.domain.workout.SupersetRestMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Process death is modelled at the storage level: everything written before the database is closed must be
 * readable by a brand-new database instance, with no in-memory state carried over.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutPersistenceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1_000_000L

    @Before
    fun clean() {
        context.deleteDatabase(DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB)
    }

    private data class Repos(
        val db: AppDatabase,
        val programs: ProgramRepository,
        val workouts: WorkoutRepository,
        val plans: PlanRepository,
        val profiles: ProfileRepository,
    )

    private fun repos(): Repos {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB).addMigrations(*AppDatabase.MIGRATIONS).build()
        val assets = AssetReader(context)
        val catalog = CatalogRepository(db.catalogDao(), db.appMetaDao(), assets)
        val programs = ProgramRepository(db, assets) { clock }
        val profiles = ProfileRepository(db) { clock }
        val configs = GeneratorConfigStore(File(context.cacheDir, "generator-test").apply { mkdirs() }, catalog)
        // Monday: tomorrow is not a long-effort day in the test profile.
        val plans = PlanRepository(db, catalog, profiles, configs, SettingsRepository(context), { clock }, { 1 })
        val workouts = WorkoutRepository(db, catalog, programs, plans) { clock }
        runBlocking {
            catalog.seedIfNeeded()
            programs.ensureDefaultProgram()
        }
        return Repos(db, programs, workouts, plans, profiles)
    }

    private fun open(): Triple<AppDatabase, ProgramRepository, WorkoutRepository> = repos().let { Triple(it.db, it.programs, it.workouts) }

    @Test
    fun generatedProgramProgressesAfterAWorkoutAtTheTopOfTheRange() = runBlocking {
        val r = repos()
        val profile = Profile(daysPerWeek = 3, sessionMinutes = 45, baselines = mapOf(BaselineLift.SQUAT to Baseline(100.0, 5)))
        r.profiles.save(profile)
        assertEquals(profile, r.profiles.current())
        val preview = r.plans.preview(profile)
        assertTrue(preview.replacesProgram)
        assertTrue(preview.changes.isNotEmpty())
        r.plans.apply(preview, profile)

        val active = r.programs.activeProgram.first()!!
        assertEquals(PlanRepository.SOURCE_GENERATED, active.program.source)
        assertEquals(3, active.days.size)
        val dayIndex = active.days.indexOfFirst { d -> d.exercises.any { it.exerciseId == "barbell_back_squat" } }
        val day = active.days[dayIndex]
        val squat = day.exercises.first { it.exerciseId == "barbell_back_squat" }
        val start = squat.targetWeightKg!!

        val sessionId = r.workouts.startFromProgramDay(day.day.id, Checkin(sleep = 2))
        val detail = r.workouts.observeDetail(sessionId).first()!!
        assertEquals(2, detail.session.checkinSleep)
        assertTrue(detail.session.planNote!!.contains("Чек-ин"))
        val slot = detail.slots.first { it.exercise.id == "barbell_back_squat" }
        // One bad answer adds a rep of reserve, the sets stay.
        assertEquals(squat.rirMin!! + 1, slot.slot.rirMin)
        assertEquals(squat.sets, slot.slot.sets)
        repeat(slot.slot.sets) {
            r.workouts.logSet(sessionId, slot.slot.id, SetKind.WORK, start, slot.slot.repMax, null, SupersetRestMode.AFTER_ROUND)
        }
        r.workouts.finish(sessionId)

        val after = r.programs.activeProgram.first()!!.days[dayIndex].exercises.first { it.exerciseId == "barbell_back_squat" }
        assertTrue("squat ${after.targetWeightKg} after $start", after.targetWeightKg!! > start)
        val progress = PlanRepository.decodeProgress(r.db.workoutDao().session(sessionId)!!.progressJson)
        assertEquals("up", progress.single { it.exerciseId == "barbell_back_squat" }.change)
        r.db.close()
    }

    @Test
    fun activeWorkoutSurvivesReopenAndFinishAdvancesRotation() = runBlocking {
        val (db1, programs1, workouts1) = open()
        val program = programs1.activeProgram.first()!!
        assertEquals(0, program.nextIndex)
        val dayA = program.days[0]
        assertTrue(dayA.exercises.size >= 7)

        val sessionId = workouts1.startFromProgramDay(dayA.day.id)
        val firstSlot = workouts1.observeDetail(sessionId).first()!!.slots.first()
        workouts1.logSet(sessionId, firstSlot.slot.id, SetKind.WARMUP, 40.0, 8, null, SupersetRestMode.AFTER_ROUND)
        clock += 60_000
        workouts1.logSet(sessionId, firstSlot.slot.id, SetKind.WORK, 80.0, 6, null, SupersetRestMode.AFTER_ROUND)
        val restEnds = db1.workoutDao().session(sessionId)!!.restEndsAt
        assertNotNull(restEnds)
        db1.close()

        val (db2, programs2, workouts2) = open()
        val restored = workouts2.activeSessionNow()
        assertNotNull(restored)
        assertEquals(sessionId, restored!!.id)
        assertEquals(WorkoutSessionEntity.ACTIVE, restored.status)
        assertEquals(restEnds, restored.restEndsAt)
        val detail = workouts2.observeDetail(sessionId).first()!!
        val sets = detail.slots.first().sets
        assertEquals(listOf("warmup", "work"), sets.map { it.kind })
        assertEquals(80.0, sets[1].weightKg!!, 1e-9)
        assertEquals(1, detail.doneWorkSets)
        assertEquals(dayA.exercises.size, detail.slots.size)
        // Catalog and default program are not duplicated on the second start.
        assertEquals(1, db2.programDao().days(restored.programId!!).map { it.day.programId }.distinct().size)

        clock += 30 * 60_000
        workouts2.finish(sessionId)
        assertNull(workouts2.activeSessionNow())
        assertEquals(1, programs2.activeProgram.first()!!.nextIndex)
        val history = workouts2.history.first()
        assertEquals(1, history.size)
        assertEquals(1, history[0].workSets)
        assertEquals(480.0, history[0].volumeKg, 1e-9)
        db2.close()
    }

    @Test
    fun skipShiftsNextDayWithoutHistory() = runBlocking {
        val (db, programs, workouts) = open()
        programs.skipNextDay()
        assertEquals(1, programs.activeProgram.first()!!.nextIndex)
        programs.skipNextDay()
        assertEquals(0, programs.activeProgram.first()!!.nextIndex)
        assertTrue(workouts.history.first().isEmpty())
        db.close()
    }

    @Test
    fun replaceBeforeFirstSetSwapsInPlaceAndIsLogged() = runBlocking {
        val (db, programs, workouts) = open()
        val day = programs.activeProgram.first()!!.days[0]
        val sessionId = workouts.startFromProgramDay(day.day.id)
        val slots = workouts.observeDetail(sessionId).first()!!.slots
        val target = slots.last()
        val catalog = CatalogRepository(db.catalogDao(), db.appMetaDao(), AssetReader(context))
        val substitute = catalog.substitutes(target.exercise.id).first()

        assertTrue(workouts.replaceExercise(sessionId, target.slot.id, substitute.id, 8, 12, 24.0, forever = false))
        val after = workouts.observeDetail(sessionId).first()!!.slots
        assertEquals(slots.size, after.size)
        assertEquals(substitute.id, after.last().exercise.id)
        assertEquals(24.0, after.last().slot.targetWeightKg!!, 1e-9)
        // "Today" leaves the program untouched.
        assertEquals(target.exercise.id, programs.activeProgram.first()!!.days[0].exercises.last().exerciseId)
        assertEquals(listOf(BusyEventEntity.REPLACE_TODAY), workouts.busyEvents(sessionId).map { it.action })
        db.close()
    }

    @Test
    fun replaceAfterSetsSplitsSlotAndKeepsLoggedSets() = runBlocking {
        val (db, programs, workouts) = open()
        val day = programs.activeProgram.first()!!.days[0]
        val sessionId = workouts.startFromProgramDay(day.day.id)
        val first = workouts.observeDetail(sessionId).first()!!.slots.first()
        workouts.logSet(sessionId, first.slot.id, SetKind.WORK, 80.0, 6, null, SupersetRestMode.AFTER_ROUND)
        val catalog = CatalogRepository(db.catalogDao(), db.appMetaDao(), AssetReader(context))
        val substitute = catalog.substitutes(first.exercise.id).first()

        assertTrue(workouts.replaceExercise(sessionId, first.slot.id, substitute.id, 6, 10, null, forever = true))
        val detail = workouts.observeDetail(sessionId).first()!!
        val old = detail.slots[0]
        val new = detail.slots[1]
        assertEquals(first.exercise.id, old.exercise.id)
        assertEquals(1, old.slot.sets)
        assertEquals(listOf(80.0), old.sets.map { it.weightKg })
        assertEquals(substitute.id, new.exercise.id)
        assertEquals(first.slot.sets - 1, new.slot.sets)
        assertEquals(day.exercises.size + 1, detail.slots.size)
        assertEquals(detail.slots.indices.toList(), detail.slots.map { it.slot.position })
        assertEquals(1, detail.session.currentPosition)
        assertEquals(day.exercises.sumOf { it.sets }, detail.plannedWorkSets)
        // "Forever" rewrites the program day for the next time.
        assertEquals(substitute.id, programs.activeProgram.first()!!.days[0].exercises.first().exerciseId)
        assertEquals(listOf(BusyEventEntity.REPLACE_PROGRAM), workouts.busyEvents(sessionId).map { it.action })
        db.close()
    }

    @Test
    fun laterMovesSlotToEndAndSurvivesReopen() = runBlocking {
        val (db1, programs, workouts1) = open()
        val day = programs.activeProgram.first()!!.days[0]
        val sessionId = workouts1.startFromProgramDay(day.day.id)
        val before = workouts1.observeDetail(sessionId).first()!!.slots
        val target = before.first { it.slot.supersetGroup == null }
        workouts1.moveToEnd(sessionId, target.slot.id)
        db1.close()

        val (db2, _, workouts2) = open()
        val after = workouts2.observeDetail(sessionId).first()!!
        assertEquals(target.slot.id, after.slots.last().slot.id)
        assertEquals(before.map { it.slot.id }.filter { it != target.slot.id }, after.slots.dropLast(1).map { it.slot.id })
        assertEquals(after.slots.indices.toList(), after.slots.map { it.slot.position })
        assertEquals(before.indexOf(target), after.session.currentPosition)
        assertEquals(listOf(BusyEventEntity.LATER), workouts2.busyEvents(sessionId).map { it.action })
        db2.close()
    }

    @Test
    fun fillerIsAppendedAndOpened() = runBlocking {
        val (db, programs, workouts) = open()
        val day = programs.activeProgram.first()!!.days[0]
        val sessionId = workouts.startFromProgramDay(day.day.id)
        val first = workouts.observeDetail(sessionId).first()!!.slots.first()
        val catalog = CatalogRepository(db.catalogDao(), db.appMetaDao(), AssetReader(context))
        val plank = catalog.exercises(listOf("plank")).getValue("plank")

        workouts.addFiller(sessionId, first.slot.id, plank)
        val detail = workouts.observeDetail(sessionId).first()!!
        assertEquals("plank", detail.slots.last().exercise.id)
        assertEquals(detail.slots.lastIndex, detail.session.currentPosition)
        assertEquals(BusyEventEntity.WAIT, workouts.busyEvents(sessionId).single().action)
        db.close()
    }

    private companion object {
        const val DB = "persistence-test.db"
    }
}
