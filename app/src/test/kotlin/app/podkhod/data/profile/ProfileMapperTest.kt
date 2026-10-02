package app.podkhod.data.profile

import app.podkhod.domain.plan.Baseline
import app.podkhod.domain.plan.BaselineLift
import app.podkhod.domain.plan.Goal
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.plan.Style
import app.podkhod.domain.weights.Implement
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.PlatePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMapperTest {

    @Test
    fun profileSurvivesARoundTrip() {
        val p = Profile(
            goal = Goal.STRENGTH,
            priorities = listOf(MuscleGroup.BACK, MuscleGroup.GLUTES),
            daysPerWeek = 4,
            sessionMinutes = 60,
            preferredDays = setOf(1, 3, 5, 6),
            longEffortDays = setOf(7),
            cardioDays = setOf(2),
            style = Style.CLASSIC,
            mesocycleWeeks = 5,
            baselines = mapOf(
                BaselineLift.SQUAT to Baseline(100.0, 5),
                BaselineLift.PULLUP to Baseline(10.0, 6),
                BaselineLift.DEADLIFT to Baseline(skipped = true),
                BaselineLift.BENCH to Baseline(e1rmOverrideKg = 95.0),
            ),
            inventory = Inventory(
                barKg = 15.0,
                plates = listOf(PlatePair(20.0, 2), PlatePair(10.0, 1), PlatePair(1.25, 2)),
                kettlebells = listOf(Implement(16.0, 2), Implement(24.0, 1)),
                dumbbells = listOf(Implement(10.0, 1)),
                equipment = Inventory.DEFAULT_EQUIPMENT + Inventory.DUMBBELLS,
            ),
            restrictionTags = setOf("knees", "grip"),
            restrictionNote = "левое колено",
            bodyMassKg = 82.5,
            excludedExercises = setOf("barbell_good_morning"),
        )
        val rows = ProfileMapper.toRows(p, createdAt = 1, now = 2)
        assertEquals(p, ProfileMapper.toProfile(rows))
        assertEquals(ProfileMapper.UNITS_KG, rows.profile.units)
    }

    @Test
    fun emptyCountsAreNotStoredAndPrioritiesAreCappedAtTwo() {
        val p = Profile(
            priorities = listOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.DELTS),
            inventory = Inventory(plates = listOf(PlatePair(20.0, 2), PlatePair(5.0, 0)), kettlebells = listOf(Implement(16.0, 0))),
        )
        val rows = ProfileMapper.toRows(p, 1, 1)
        assertEquals(listOf(20.0), rows.plates.map { it.weightKg })
        assertTrue(rows.kettlebells.isEmpty())
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.BACK), ProfileMapper.toProfile(rows).priorities)
    }
}
