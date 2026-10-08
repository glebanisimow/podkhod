package app.podkhod.data.plan

import app.podkhod.data.catalog.CatalogFile
import app.podkhod.data.catalog.CatalogJsonFormat
import app.podkhod.data.catalog.CatalogValidator
import app.podkhod.data.catalog.SubstitutionFile
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.plan.Baseline
import app.podkhod.domain.plan.BaselineLift
import app.podkhod.domain.plan.GeneratedProgram
import app.podkhod.domain.plan.GeneratorConfig
import app.podkhod.domain.plan.Goal
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.plan.ProgramGenerator
import app.podkhod.domain.plan.StrengthData
import app.podkhod.domain.plan.Style
import app.podkhod.domain.weights.Implement
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.LoadScheme
import app.podkhod.domain.weights.Loads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 2 acceptance on the bundled catalog and config: three profiles with 2, 3 and 4 days and different
 * restrictions and inventories cover every group within its weekly target, with nothing excluded or missing.
 */
class GeneratorAcceptanceTest {

    private val catalogFile = CatalogJsonFormat.decodeFromString(CatalogFile.serializer(), asset("catalog.json"))
    private val catalog: List<ExerciseInfo> = catalogFile.exercises.mapIndexed { i, e -> e.toInfo(i) }
    private val byId = catalog.associateBy { it.id }
    private val ratios = CatalogJsonFormat.decodeFromString(SubstitutionFile.serializer(), asset("substitution.json")).toConfig().ratios
    private val config = GeneratorConfig.parse(asset("generator_config.json"))
    private val generator = ProgramGenerator(config)

    private val baselines = mapOf(
        BaselineLift.SQUAT to Baseline(100.0, 5),
        BaselineLift.BENCH to Baseline(80.0, 5),
        BaselineLift.DEADLIFT to Baseline(130.0, 3),
        BaselineLift.OHP to Baseline(50.0, 5),
        BaselineLift.ROW to Baseline(70.0, 8),
        BaselineLift.PULLUP to Baseline(0.0, 8),
        BaselineLift.KB_SWING to Baseline(24.0, 15),
    )

    /** 2 days × 60 min, lower back spared, kettlebells and machines. */
    private val twoDays = Profile(
        daysPerWeek = 2, sessionMinutes = 60, restrictionTags = setOf("lower_back"), baselines = baselines, bodyMassKg = 82.0,
    )

    /** 3 days × 45 min (defaults), shoulders spared, back is a priority. */
    private val threeDays = Profile(
        daysPerWeek = 3, sessionMinutes = 45, restrictionTags = setOf("shoulders"), priorities = listOf(MuscleGroup.BACK),
        baselines = baselines, bodyMassKg = 82.0,
    )

    /** 4 days × 45 min, strength goal, grip and ballistics spared, dumbbells and bands added, few kettlebells. */
    private val fourDays = Profile(
        daysPerWeek = 4, sessionMinutes = 45, goal = Goal.STRENGTH, restrictionTags = setOf("grip", "no_ballistic"),
        inventory = Inventory(
            kettlebells = listOf(Implement(16.0, 2), Implement(24.0, 2), Implement(32.0, 1)),
            dumbbells = listOf(Implement(6.0, 1), Implement(8.0, 1), Implement(10.0, 1), Implement(14.0, 1)),
            equipment = Inventory.DEFAULT_EQUIPMENT + setOf("dumbbells", "bands"),
        ),
        baselines = baselines, bodyMassKg = 82.0,
    )

    /** Home gym without leg machines: every leg exercise also loads the glutes. */
    private val homeGym = fourDays.copy(
        restrictionTags = setOf("grip"),
        inventory = fourDays.inventory.copy(
            dumbbells = emptyList(),
            equipment = setOf("barbell", "rack", "bench", "adjustable_bench", "kettlebell", "pullup_bar", "dip_bars", "bands"),
        ),
    )

    private fun generate(p: Profile, mesocycle: Int = 0) = generator.generate(
        ProgramGenerator.Input(profile = p, catalog = catalog, strength = StrengthData(ratios = ratios), mesocycleIndex = mesocycle),
    )

    @Test
    fun minSetsFromSettingsLiftsEveryExercise() {
        for (p in listOf(twoDays, threeDays, fourDays)) {
            val program = ProgramGenerator(config.withMinSets(3)).generate(
                ProgramGenerator.Input(profile = p, catalog = catalog, strength = StrengthData(ratios = ratios)),
            )
            val sets = program.days.flatMap { d -> d.slots.map { it.sets } }
            assertTrue("${p.daysPerWeek} дня: $sets", sets.all { it >= 3 })
        }
    }

    @Test
    fun configIsValid() {
        assertEquals(emptyList<String>(), CatalogValidator.validateGeneratorConfig(config, byId.keys))
    }

    @Test
    fun twoDayProgramMeetsTargets() = check(twoDays, "2 дня")

    @Test
    fun threeDayProgramMeetsTargets() = check(threeDays, "3 дня")

    @Test
    fun fourDayProgramMeetsTargets() = check(fourDays, "4 дня")

    @Test
    fun classicStyleFitsTheSessionToo() = check(threeDays.copy(style = Style.CLASSIC, sessionMinutes = 60), "классика")

    /**
     * Without leg machines glutes cannot stay under 12 weekly sets on a 4-day upper/lower split. The program
     * must still be valid, keep its days, and say so instead of silently passing.
     */
    @Test
    fun infeasibleTargetIsReportedNotHidden() {
        val program = generate(homeGym)
        println(dump("домашний зал", program))
        checkStructure(homeGym, program)
        val glutes = program.volume.first { it.group == MuscleGroup.GLUTES }
        if (!glutes.withinTarget) assertTrue(program.shortfalls.joinToString(), program.shortfalls.any { it.startsWith("ягодицы") })
        program.volume.filter { !it.withinTarget }.forEach { line ->
            assertTrue("${line.group} out of range but not reported", program.shortfalls.any { it.startsWith(app.podkhod.domain.plan.Fmt.group(line.group)) })
        }
        val perSession = config.perSession(homeGym.style, homeGym.sessionMinutes)
        program.days.forEach { assertTrue("${it.title}: only ${it.slots.size}", it.slots.size >= perSession - 2) }
    }

    @Test
    fun generationIsDeterministic() {
        assertEquals(generate(threeDays), generate(threeDays))
    }

    @Test
    fun nextMesocycleRotatesVariationsButKeepsMarkers() {
        val first = generate(fourDays, 0)
        val second = generate(fourDays, 1)
        val markers1 = first.days.flatMap { d -> d.slots.filter { it.marker }.map { it.exerciseId } }
        val markers2 = second.days.flatMap { d -> d.slots.filter { it.marker }.map { it.exerciseId } }
        assertEquals(config.mesocycle.markers, markers1.size)
        assertEquals(markers1, markers2)
        val all1 = first.days.flatMap { d -> d.slots.map { it.exerciseId } }.toSet()
        val all2 = second.days.flatMap { d -> d.slots.map { it.exerciseId } }.toSet()
        assertTrue("no variation rotated", (all2 - all1).isNotEmpty())
    }

    private fun check(profile: Profile, label: String) {
        val program = generate(profile)
        println(dump(label, program))
        checkStructure(profile, program)
        program.volume.forEach { line ->
            assertTrue("${line.group}: ${line.sets} not in ${line.targetLow}..${line.max}", line.withinTarget)
        }
        assertEquals(emptyList<String>(), program.shortfalls)
    }

    private fun checkStructure(profile: Profile, program: GeneratedProgram) {
        val available = profile.inventory.available
        val banned = profile.restrictionTags.flatMap { config.restrictionTags.getValue(it) }.toSet()
        assertEquals(profile.daysPerWeek, program.days.size)
        program.days.forEach { day ->
            assertTrue("${day.title}: empty", day.slots.isNotEmpty())
            assertTrue("${day.title}: ${day.estimatedMinutes} min > ${profile.sessionMinutes}", day.estimatedMinutes <= profile.sessionMinutes * (1 + config.timing.tolerance))
            assertEquals("${day.title}: duplicate exercise", day.slots.size, day.slots.map { it.exerciseId }.toSet().size)
            day.slots.forEach { s ->
                val e = byId.getValue(s.exerciseId)
                assertTrue("${e.id} needs ${e.equipment - available}", available.containsAll(e.equipment))
                assertTrue("${e.id} has banned ${e.tags intersect banned}", e.tags.none { it in banned })
                assertTrue("${e.id}: no reasons", s.reasons.isNotEmpty())
                assertTrue("${e.id}: sets ${s.sets}", s.sets in 1..config.sets.compoundMax)
                assertTrue("${e.id}: reps ${s.repMin}-${s.repMax}", s.repMin in 1..s.repMax)
                val w = s.targetWeightKg
                if (w != null) {
                    val scheme = Loads.scheme(e, profile.inventory)
                    assertEquals("${e.id}: $w is not buildable", w, Loads.roundDown(scheme, w))
                    if (scheme is LoadScheme.Barbell) assertTrue(w >= profile.inventory.barKg)
                }
            }
            day.slots.mapNotNull { it.supersetGroup }.groupBy { it }.forEach { (g, m) -> assertEquals("${day.title}: superset $g", 2, m.size) }
        }
        MuscleGroup.entries.forEach { g ->
            assertTrue("$g not trained", program.volume.first { it.group == g }.sets >= 1.0)
        }
    }

    private fun dump(label: String, p: GeneratedProgram): String = buildString {
        appendLine("=== $label: ${p.name}")
        p.notes.forEach { appendLine("  * $it") }
        p.days.forEach { d ->
            appendLine("  -- ${d.title} (~${d.estimatedMinutes} мин)")
            d.slots.forEach { s ->
                appendLine("     ${s.supersetGroup ?: " "} ${byId.getValue(s.exerciseId).name}: ${s.sets}×${s.repMin}-${s.repMax} RIR ${s.rirMin}-${s.rirMax} " +
                    "rest ${s.restSec} w=${s.targetWeightKg}${if (s.marker) " [M]" else ""}")
            }
        }
        p.volume.forEach { appendLine("  ${it.group}: ${it.sets} (${it.targetLow}..${it.targetHigh}/${it.max})") }
        p.shortfalls.forEach { appendLine("  ! $it") }
    }

    private fun asset(name: String): String {
        val candidates = listOf(File("../shared/src/commonMain/composeResources/files/$name"), File("shared/src/commonMain/composeResources/files/$name"))
        return candidates.first { it.exists() }.readText(Charsets.UTF_8)
    }
}
