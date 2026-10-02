package app.podkhod.domain.busy

import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubstitutionTest {

    private fun ex(
        id: String,
        pattern: MovementPattern = MovementPattern.SQUAT,
        load: LoadType = LoadType.EXTERNAL,
        measure: Measure = Measure.REPS,
        occupancy: Occupancy = Occupancy.LOW,
        equipment: Set<String> = setOf("kettlebell"),
        primary: Set<Muscle> = setOf(Muscle.QUADS, Muscle.GLUTES),
        step: Double = 4.0,
        reps: IntRange = 6..12,
        subs: List<String> = emptyList(),
    ) = ExerciseInfo(id, pattern, load, measure, reps.first, reps.last, step, occupancy, equipment, primary, emptySet(), subs)

    private val backSquat = ex(
        "back_squat", occupancy = Occupancy.HIGH, equipment = setOf("barbell", "rack"), step = 2.5, reps = 4..8,
        subs = listOf("front_squat", "goblet", "split_squat", "leg_press", "db_squat"),
    )
    private val catalog = listOf(
        backSquat,
        ex("front_squat", occupancy = Occupancy.HIGH, equipment = setOf("barbell", "rack"), step = 2.5, reps = 4..8, subs = listOf("goblet")),
        ex("goblet", subs = listOf("bodyweight_squat")),
        ex("split_squat", pattern = MovementPattern.LUNGE, occupancy = Occupancy.MEDIUM, equipment = setOf("kettlebell", "bench")),
        ex("leg_press", occupancy = Occupancy.MEDIUM, equipment = setOf("leg_press"), step = 10.0, reps = 8..15),
        ex("bodyweight_squat", load = LoadType.BODYWEIGHT_PLUS, equipment = emptySet(), reps = 15..30),
        ex("bench", pattern = MovementPattern.H_PUSH, equipment = setOf("barbell", "bench"), primary = setOf(Muscle.CHEST)),
        ex("db_squat", equipment = setOf("dumbbells")),
    ).associateBy { it.id }

    private val config = SubstitutionConfig(
        stations = setOf("rack", "bench", "leg_press"),
        unavailableEquipment = setOf("dumbbells"),
        sessionMuscleCap = 8,
        safetyFactor = 0.9,
        ratios = mapOf(
            "back_squat" to StrengthRatio("legs", 1.0),
            "front_squat" to StrengthRatio("legs", 0.8),
            "goblet" to StrengthRatio("legs", 0.3),
            "split_squat" to StrengthRatio("legs", 0.2),
            "leg_press" to StrengthRatio("legs", 1.8),
        ),
        waitExtras = emptyList(),
    )

    private fun request(
        reference: ReferenceSet? = ReferenceSet(100.0, 5),
        others: List<SessionSlotInfo> = emptyList(),
        own: Map<String, Double> = emptyMap(),
    ) = SubstituteRequest(backSquat, 4, 8, 3, reference, others, own)

    @Test
    fun busyRackPushesRackExercisesBackAndKeepsSameMovementFirst() {
        val ids = Substitution.options(request(), catalog, config).map { it.exerciseId }
        // Front squat needs the same busy rack; goblet is the same movement on free equipment.
        assertEquals(listOf("goblet", "leg_press", "bodyweight_squat"), ids)
    }

    @Test
    fun skipsMissingEquipmentAndExercisesAlreadyInSession() {
        val ids = Substitution.options(request(others = listOf(SessionSlotInfo("goblet", 3))), catalog, config, limit = 10)
            .map { it.exerciseId }
        assertFalse("goblet" in ids)
        assertFalse("db_squat" in ids)
        assertFalse("back_squat" in ids)
    }

    @Test
    fun convertsWeightThroughOneRepMaxAndRatio() {
        val option = Substitution.options(request(), catalog, config).first { it.exerciseId == "leg_press" }
        // 100x5 -> e1RM ~115.4 (avg Epley/Brzycki); leg press 1.8 -> 207.7; at 8 reps /1.2667 = 164; x0.9 = 147.6; floor to 10.
        assertEquals(EstimateSource.RATIO, option.estimate.source)
        assertEquals(140.0, option.estimate.weightKg!!, 1e-9)
        assertEquals(4, option.estimate.repMin)
        assertEquals(8, option.estimate.repMax)
    }

    @Test
    fun ownHistoryWinsOverConversion() {
        val option = Substitution.options(request(own = mapOf("goblet" to 28.0)), catalog, config).first { it.exerciseId == "goblet" }
        assertEquals(EstimateSource.OWN_HISTORY, option.estimate.source)
        assertEquals(28.0, option.estimate.weightKg!!, 1e-9)
    }

    @Test
    fun withoutReferenceTheWeightIsLeftToTheWarmUp() {
        val option = Substitution.options(request(reference = null), catalog, config).first { it.exerciseId == "goblet" }
        assertEquals(EstimateSource.UNKNOWN, option.estimate.source)
        assertNull(option.estimate.weightKg)
    }

    @Test
    fun bodyweightAlternativeStartsWithoutExtraLoadAndOwnRange() {
        val option = Substitution.options(request(), catalog, config).first { it.exerciseId == "bodyweight_squat" }
        assertEquals(EstimateSource.BODYWEIGHT, option.estimate.source)
        assertEquals(0.0, option.estimate.weightKg!!, 1e-9)
    }

    @Test
    fun differentMeasureUsesCandidateRange() {
        val plank = ex("plank", pattern = MovementPattern.CORE, load = LoadType.NONE, measure = Measure.SECONDS, equipment = emptySet(),
            primary = setOf(Muscle.ABS), reps = 30..60)
        val crunch = ex("crunch", pattern = MovementPattern.CORE, primary = setOf(Muscle.ABS), subs = listOf("plank"))
        val c = mapOf("plank" to plank, "crunch" to crunch)
        val est = Substitution.options(SubstituteRequest(crunch, 10, 15, 3, null, emptyList()), c, config).single().estimate
        assertEquals(EstimateSource.NO_LOAD, est.source)
        assertEquals(30, est.repMin)
        assertEquals(60, est.repMax)
    }

    @Test
    fun overloadingAnotherMuscleRanksLower() {
        val hamstrings = ex("rdl", pattern = MovementPattern.SQUAT, primary = setOf(Muscle.HAMSTRINGS), equipment = setOf("kettlebell"))
        val original = ex("squat_k", subs = listOf("rdl", "goblet"), primary = setOf(Muscle.QUADS), equipment = setOf("barbell", "rack"))
        val c = catalog + ("rdl" to hamstrings) + ("squat_k" to original) + ("hinge" to ex("hinge", primary = setOf(Muscle.HAMSTRINGS)))
        val req = SubstituteRequest(original, 6, 12, 3, null, listOf(SessionSlotInfo("hinge", 6)))
        val options = Substitution.options(req, c, config)
        assertEquals("goblet", options.first().exerciseId)
        assertTrue(options.first { it.exerciseId == "rdl" }.overloadsMuscle)
    }

    @Test
    fun busyKettlebellPrefersEquipmentFreeAlternative() {
        val original = ex("kb_squat", subs = listOf("goblet", "bodyweight_squat"))
        val options = Substitution.options(SubstituteRequest(original, 6, 12, 3, null, emptyList()), catalog + ("kb_squat" to original), config)
        assertEquals("bodyweight_squat", options.first().exerciseId)
        assertTrue(options.first { it.exerciseId == "goblet" }.sharesBusyStation)
    }

    @Test
    fun floorToStepNeverRoundsUp() {
        assertEquals(60.0, Substitution.floorToStep(62.4, 2.5), 1e-9)
        assertEquals(62.5, Substitution.floorToStep(62.5, 2.5), 1e-9)
        assertEquals(16.0, Substitution.floorToStep(19.9, 4.0), 1e-9)
        assertEquals(7.3, Substitution.floorToStep(7.3, 0.0), 1e-9)
    }
}
