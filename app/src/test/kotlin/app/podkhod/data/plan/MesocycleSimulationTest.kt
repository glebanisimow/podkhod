package app.podkhod.data.plan

import app.podkhod.data.catalog.CatalogFile
import app.podkhod.data.catalog.CatalogJsonFormat
import app.podkhod.data.catalog.SubstitutionFile
import app.podkhod.domain.OneRepMax
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.plan.Baseline
import app.podkhod.domain.plan.BaselineLift
import app.podkhod.domain.plan.Change
import app.podkhod.domain.plan.DeloadAdvisor
import app.podkhod.domain.plan.GeneratorConfig
import app.podkhod.domain.plan.LoggedSet
import app.podkhod.domain.plan.PlannedSlot
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.plan.ProgramGenerator
import app.podkhod.domain.plan.Progression
import app.podkhod.domain.plan.ProgressionState
import app.podkhod.domain.plan.SessionContext
import app.podkhod.domain.plan.SessionPlanner
import app.podkhod.domain.plan.SlotRole
import app.podkhod.domain.plan.StrengthData
import app.podkhod.domain.weights.Loads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

/**
 * Six program weeks of a 3-day program with simulated results: an athlete whose true 1RM grows should see
 * the weights go up, a planned deload in week 6 with lighter weights and no progression; an athlete who
 * gets weaker should see the weight cut after two misses and an unplanned deload offered.
 */
class MesocycleSimulationTest {

    private val catalog: List<ExerciseInfo> =
        CatalogJsonFormat.decodeFromString(CatalogFile.serializer(), asset("catalog.json")).exercises.mapIndexed { i, e -> e.toInfo(i) }
    private val infos = catalog.associateBy { it.id }
    private val ratios = CatalogJsonFormat.decodeFromString(SubstitutionFile.serializer(), asset("substitution.json")).toConfig().ratios
    private val config = GeneratorConfig.parse(asset("generator_config.json"))
    private val profile = Profile(
        daysPerWeek = 3, sessionMinutes = 45, bodyMassKg = 80.0,
        baselines = mapOf(
            BaselineLift.SQUAT to Baseline(100.0, 5), BaselineLift.BENCH to Baseline(80.0, 5),
            BaselineLift.DEADLIFT to Baseline(130.0, 3), BaselineLift.OHP to Baseline(50.0, 5), BaselineLift.ROW to Baseline(70.0, 8),
        ),
    )

    private data class Week(val sessions: Int, val ups: Int, val downs: Int, val deload: Boolean)

    private class Sim(
        val weightsByWeek: MutableMap<String, MutableList<Double>> = mutableMapOf(),
        val changes: MutableList<Pair<Int, Change>> = mutableListOf(),
        var firstDeloadOffer: Int? = null,
        val deloadWeekSets: MutableList<Pair<Int, Int>> = mutableListOf(),
    )

    /** [growthPerSession] multiplies the athlete's true 1RM after every session. */
    private fun simulate(growthPerSession: Double): Sim {
        val program = ProgramGenerator(config).generate(ProgramGenerator.Input(profile, catalog, StrengthData(ratios = ratios)))
        val days = program.days.map { it.slots.toMutableList() }
        val weeks = program.weeks
        val planner = SessionPlanner(config)
        val progression = Progression(config)
        val advisor = DeloadAdvisor(config)
        val states = mutableMapOf<String, ProgressionState>()
        val trueMax = mutableMapOf<String, Double>()
        val sim = Sim()

        // The athlete is a bit stronger than the conservative start, so week 1 is not a failure.
        days.flatten().forEach { s -> s.targetWeightKg?.let { w -> trueMax.putIfAbsent(s.exerciseId, OneRepMax.epley(w, s.repMax + 3)) } }

        var session = 0
        for (week in 0 until weeks) {
            days.forEachIndexed { d, day ->
                val plan = planner.plan(day, infos, profile.inventory, SessionContext(week, weeks))
                plan.slots.forEachIndexed { i, s ->
                    val info = infos.getValue(s.exerciseId)
                    val scheme = Loads.scheme(info, profile.inventory)
                    val w = s.targetWeightKg
                    if (w != null && info.load == LoadType.EXTERNAL) {
                        assertEquals("unbuildable $w for ${info.id}", w, Loads.roundDown(scheme, w))
                        if (s.role == SlotRole.COMPOUND) sim.weightsByWeek.getOrPut(info.id) { mutableListOf() } += w
                    }
                    if (plan.deload) {
                        sim.deloadWeekSets += day[i].sets to s.sets
                        val before = day[i].targetWeightKg
                        if (before != null && w != null && info.load == LoadType.EXTERNAL) {
                            assertEquals("${info.id} deload weight", Loads.roundDown(scheme, before * config.deload.weightFactor), w)
                        }
                    }
                    val logged = perform(info, s, trueMax[info.id])
                    val r = progression.update(info, scheme, day[i], logged, states[info.id] ?: ProgressionState(w), plan.deload)
                    states[info.id] = r.state
                    sim.changes += week to r.change
                    // The repository writes the new working weight into every planned row of this exercise.
                    r.state.workingWeightKg?.let { nw -> days.forEach { dd -> dd.replaceAll { if (it.exerciseId == info.id) it.copy(targetWeightKg = nw) else it } } }
                }
                trueMax.replaceAll { _, v -> v * growthPerSession }
                session++
                val compounds = days.flatten().filter { it.role == SlotRole.COMPOUND }.map { it.exerciseId }.toSet()
                val reason = advisor.advise(states.filterKeys { it in compounds }.mapValues { it.value.e1rmHistory }, emptyList())
                if (reason != null && sim.firstDeloadOffer == null) sim.firstDeloadOffer = session
            }
        }
        return sim
    }

    /** Reps the athlete gets at the target reserve, one fewer from the third set on. */
    private fun perform(info: ExerciseInfo, s: PlannedSlot, trueMax: Double?): List<LoggedSet> {
        val w = s.targetWeightKg ?: 0.0
        return (0 until s.sets).map { i ->
            if (info.measure == Measure.SECONDS || trueMax == null || w <= 0.0) {
                LoggedSet(w, if (info.measure == Measure.SECONDS) null else s.repMax, durationSec = s.repMax, rir = s.rirMin)
            } else {
                val maxReps = floor(30.0 * (trueMax / w - 1.0)).toInt()
                val reps = (maxReps - s.rirMin - (if (i >= 2) 1 else 0)).coerceIn(1, s.repMax)
                LoggedSet(w, reps, rir = (maxReps - reps).coerceAtLeast(0))
            }
        }
    }

    @Test
    fun growingAthleteProgressesAndDeloadsInWeekSix() {
        val sim = simulate(growthPerSession = 1.01)
        val squat = sim.weightsByWeek.getValue("barbell_back_squat")
        assertTrue("squat did not go up: $squat", squat.dropLast(1).last() > squat.first())
        println("compound weights by session: ${sim.weightsByWeek}")
        assertTrue(sim.changes.any { it.second == Change.UP })
        assertTrue("progression in deload week", sim.changes.filter { it.first == 5 }.all { it.second == Change.NONE })
        assertTrue(sim.deloadWeekSets.isNotEmpty())
        sim.deloadWeekSets.forEach { (planned, done) -> assertTrue("$planned -> $done", done < planned || planned == 1) }
        assertNull("deload offered to a growing athlete at session ${sim.firstDeloadOffer}", sim.firstDeloadOffer)
    }

    @Test
    fun weakeningAthleteGetsTheWeightCutAndADeloadOffer() {
        val sim = simulate(growthPerSession = 0.96)
        assertTrue("no −10 % after misses", sim.changes.any { it.second == Change.DOWN })
        assertNotNull("unplanned deload never offered", sim.firstDeloadOffer)
        assertTrue("offered too late: ${sim.firstDeloadOffer}", sim.firstDeloadOffer!! <= 12)
    }

    private fun asset(name: String): String {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }.readText(Charsets.UTF_8)
    }
}
