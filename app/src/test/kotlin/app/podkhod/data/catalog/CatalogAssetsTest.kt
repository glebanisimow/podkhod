package app.podkhod.data.catalog

import app.podkhod.domain.busy.EstimateSource
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.busy.Occupancy
import app.podkhod.domain.busy.ReferenceSet
import app.podkhod.domain.busy.SessionSlotInfo
import app.podkhod.domain.busy.SubstituteRequest
import app.podkhod.domain.busy.Substitution
import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Validates the bundled assets exactly as the app parses them, so a broken catalog fails the build, not the user. */
class CatalogAssetsTest {

    private val catalog = CatalogJsonFormat.decodeFromString(CatalogFile.serializer(), asset("catalog.json"))
    private val templates = CatalogJsonFormat.decodeFromString(TemplatesFile.serializer(), asset("templates.json"))

    @Test
    fun catalogIsConsistent() {
        assertEquals(emptyList<String>(), CatalogValidator.validate(catalog))
    }

    @Test
    fun catalogHasAtLeastSixtyExercises() {
        assertTrue("only ${catalog.exercises.size} exercises", catalog.exercises.size >= 60)
    }

    @Test
    fun everyNameIsRussian() {
        val cyrillic = Regex("[А-Яа-яЁё]")
        val bad = catalog.exercises.filterNot { cyrillic.containsMatchIn(it.ru) }.map { it.id }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun compoundExercisesHaveAtLeastThreeSubstitutes() {
        val bad = catalog.exercises
            .filter { ExerciseKind.fromCode(it.kind) == ExerciseKind.COMPOUND && it.subs.distinct().size < 3 }
            .map { it.id }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun templatesReferenceCatalogAndAreConsistent() {
        assertEquals(emptyList<String>(), CatalogValidator.validateTemplates(templates, catalog.exercises.map { it.id }.toSet()))
    }

    @Test
    fun defaultTemplateIsFullBodyAbWithAtLeastSevenExercisesPerDay() {
        val t = templates.templates.single { it.id == "fullbody_ab" }
        assertEquals(2, t.days.size)
        t.days.forEach { assertTrue("${it.title}: ${it.exercises.size}", it.exercises.size >= 7) }
    }

    @Test
    fun validatorCatchesBrokenEntries() {
        val first = catalog.exercises.first()
        val broken = CatalogFile(
            catalogVersion = 1,
            exercises = listOf(
                first.copy(subs = listOf("missing_exercise"), primary = listOf("wings"), strava = "wings"),
                first,
            ),
        )
        val problems = CatalogValidator.validate(broken)
        assertTrue(problems.any { it.startsWith("duplicate id") })
        assertTrue(problems.any { it.contains("missing_exercise") })
        assertTrue(problems.any { it.contains("bad muscle wings") })
        assertTrue(problems.any { it.contains("unknown export category wings") })
    }

    private val substitution = CatalogJsonFormat.decodeFromString(SubstitutionFile.serializer(), asset("substitution.json"))
    private val infos: Map<String, ExerciseInfo> = catalog.exercises.withIndex().associate { (i, e) -> e.id to e.toInfo(i) }

    @Test
    fun substitutionConfigIsConsistent() {
        assertEquals(emptyList<String>(), CatalogValidator.validateSubstitution(substitution, catalog))
    }

    @Test
    fun busyCompoundExercisesGetThreeSubstitutesInsideTemplateDays() {
        val config = substitution.toConfig()
        val bad = mutableListOf<String>()
        templates.templates.forEach { t ->
            t.days.forEach { d ->
                d.exercises.forEach { x ->
                    val original = infos.getValue(x.exercise)
                    val others = d.exercises.filter { it !== x }.map { SessionSlotInfo(it.exercise, it.sets) }
                    val request = SubstituteRequest(original, x.reps[0], x.reps[1], x.sets, ReferenceSet(60.0, 8), others)
                    val options = Substitution.options(request, infos, config)
                    val compound = catalog.exercises.first { it.id == x.exercise }.kind == ExerciseKind.COMPOUND.code
                    if (options.size < (if (compound) 3 else 1)) bad += "${t.id}/${d.title}/${x.exercise}: ${options.map { it.exerciseId }}"
                }
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun everyCompoundExerciseGetsThreeSubstitutesThatAvoidTheBusyStation() {
        val config = substitution.toConfig()
        val bad = catalog.exercises.filter { it.kind == ExerciseKind.COMPOUND.code }.mapNotNull { e ->
            val request = SubstituteRequest(infos.getValue(e.id), e.reps[0], e.reps[1], 3, null, emptyList())
            val options = Substitution.options(request, infos, config)
            val free = options.count { !it.sharesBusyStation }
            if (options.size < 3 || free == 0) "${e.id}: ${options.map { it.exerciseId to it.sharesBusyStation }}" else null
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun ratioConversionGivesPlausibleWeightsForTheReferenceLifts() {
        val config = substitution.toConfig()
        // 100 kg x 5 back squat: a goblet squat suggestion must be a real kettlebell, well below the barbell.
        val squat = infos.getValue("barbell_back_squat")
        val goblet = infos.getValue("kettlebell_goblet_squat")
        val est = Substitution.estimate(squat, goblet, SubstituteRequest(squat, 4, 8, 3, ReferenceSet(100.0, 5), emptyList()), config)
        assertEquals(EstimateSource.RATIO, est.source)
        val w = est.weightKg!!
        assertTrue("goblet $w", w in 16.0..32.0 && w % goblet.stepKg == 0.0)
    }

    private fun asset(name: String): String {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }.readText(Charsets.UTF_8)
    }
}
