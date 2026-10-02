package app.podkhod.data.profile

import app.podkhod.data.db.BaselineLiftEntity
import app.podkhod.data.db.DumbbellEntity
import app.podkhod.data.db.KettlebellEntity
import app.podkhod.data.db.PlateEntity
import app.podkhod.data.db.ProfileEntity
import app.podkhod.domain.plan.Baseline
import app.podkhod.domain.plan.BaselineLift
import app.podkhod.domain.plan.Goal
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.plan.Style
import app.podkhod.domain.weights.Implement
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.PlatePair

/** Rows of the profile tables, saved and loaded together. */
data class ProfileRows(
    val profile: ProfileEntity,
    val plates: List<PlateEntity>,
    val kettlebells: List<KettlebellEntity>,
    val dumbbells: List<DumbbellEntity>,
    val baselines: List<BaselineLiftEntity>,
)

object ProfileMapper {

    fun toRows(p: Profile, createdAt: Long, now: Long, plateColors: Map<Double, Long?> = emptyMap()): ProfileRows = ProfileRows(
        profile = ProfileEntity(
            goal = p.goal.code,
            priorities = p.priorities.take(MAX_PRIORITIES).joinToString(SEP) { it.code },
            daysPerWeek = p.daysPerWeek,
            sessionMinutes = p.sessionMinutes,
            preferredDays = p.preferredDays.sorted().joinToString(SEP),
            longEffortDays = p.longEffortDays.sorted().joinToString(SEP),
            cardioDays = p.cardioDays.sorted().joinToString(SEP),
            style = p.style.code,
            mesocycleWeeks = p.mesocycleWeeks,
            barKg = p.inventory.barKg,
            equipment = p.inventory.equipment.sorted().joinToString(SEP),
            restrictionTags = p.restrictionTags.sorted().joinToString(SEP),
            restrictionNote = p.restrictionNote,
            bodyMassKg = p.bodyMassKg,
            excludedExercises = p.excludedExercises.sorted().joinToString(SEP),
            units = UNITS_KG,
            createdAt = createdAt,
            updatedAt = now,
        ),
        plates = p.inventory.plates.filter { it.pairs > 0 }.map { PlateEntity(it.weightKg, it.pairs, plateColors[it.weightKg]) },
        kettlebells = p.inventory.kettlebells.filter { it.count > 0 }.map { KettlebellEntity(it.weightKg, it.count) },
        dumbbells = p.inventory.dumbbells.filter { it.count > 0 }.map { DumbbellEntity(it.weightKg, it.count) },
        baselines = p.baselines.map { (lift, b) -> BaselineLiftEntity(lift.code, b.weightKg, b.reps, b.e1rmOverrideKg, b.skipped) },
    )

    fun toProfile(rows: ProfileRows): Profile {
        val e = rows.profile
        return Profile(
            goal = Goal.fromCode(e.goal),
            priorities = codes(e.priorities).mapNotNull(MuscleGroup::fromCode).take(MAX_PRIORITIES),
            daysPerWeek = e.daysPerWeek,
            sessionMinutes = e.sessionMinutes,
            preferredDays = days(e.preferredDays),
            longEffortDays = days(e.longEffortDays),
            cardioDays = days(e.cardioDays),
            style = Style.fromCode(e.style),
            mesocycleWeeks = e.mesocycleWeeks,
            baselines = rows.baselines.mapNotNull { b ->
                BaselineLift.fromCode(b.movement)?.let { it to Baseline(b.weightKg, b.reps, b.e1rmOverrideKg, b.skipped) }
            }.toMap(),
            inventory = Inventory(
                barKg = e.barKg,
                plates = rows.plates.sortedByDescending { it.weightKg }.map { PlatePair(it.weightKg, it.pairs) },
                kettlebells = rows.kettlebells.sortedBy { it.weightKg }.map { Implement(it.weightKg, it.count) },
                dumbbells = rows.dumbbells.sortedBy { it.weightKg }.map { Implement(it.weightKg, it.pairs) },
                equipment = codes(e.equipment).toSet(),
            ),
            restrictionTags = codes(e.restrictionTags).toSet(),
            restrictionNote = e.restrictionNote,
            bodyMassKg = e.bodyMassKg,
            excludedExercises = codes(e.excludedExercises).toSet(),
        )
    }

    private fun codes(s: String): List<String> = s.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }

    private fun days(s: String): Set<Int> = codes(s).mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet()

    private const val SEP = ","
    private const val MAX_PRIORITIES = 2
    const val UNITS_KG = "kg"
}
