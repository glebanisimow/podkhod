package app.podkhod.domain.plan

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.workout.SupersetRestMode

/** Actual work, aggregated across split/replacement slots. Warm-ups must not enter [workSets]. */
data class CompletedWork(
    val exerciseId: String,
    val plannedSets: Int,
    val workSets: Int,
    val belowRepTarget: Boolean = false,
)

data class CompletedWorkout(
    val id: Long,
    val startedAt: Long,
    /** Null for sessions outside the current mesocycle. */
    val weekIndex: Int?,
    val dayIndex: Int?,
    val work: List<CompletedWork>,
    val hard: Boolean = false,
    val deload: Boolean = false,
    val recoveryAdjusted: Boolean = false,
)

data class AdaptivePlan(
    val days: List<PlannedDay>,
    /** Actual work this rotation + the remaining planned days; never presented as completed work. */
    val forecast: List<VolumeLine>,
    val notes: List<String>,
    val historyCount: Int,
)

/**
 * Replays from an immutable weekly baseline, not yesterday's adjusted plan. Changes cannot accumulate on
 * repeated launches. Numeric limits are conservative product heuristics, not clinical thresholds.
 */
class AdaptivePlanner(private val config: GeneratorConfig) {
    fun adapt(
        baseline: List<PlannedDay>,
        profile: Profile,
        catalog: Map<String, ExerciseInfo>,
        history: List<CompletedWorkout>,
        weekIndex: Int,
        nextDayIndex: Int,
        deload: Boolean = false,
        accessoryWarmupSets: Int = 1,
        restMode: SupersetRestMode = SupersetRestMode.AFTER_ROUND,
        asOfMillis: Long = history.maxOfOrNull { it.startedAt } ?: 0L,
    ): AdaptivePlan {
        val workload = Workload(config, profile, catalog, accessoryWarmupSets, restMode)
        val notes = mutableListOf<String>()
        val days = baseline.map { it.slots.toMutableList() }
        val current = history.filter { it.weekIndex == weekIndex }
        val completedDays = current.mapNotNull { it.dayIndex }.toSet()
        val remaining = days.indices.filter { it >= nextDayIndex && it !in completedDays }
        val actual = workload.volume(current.flatMap { it.work }.groupBy { it.exerciseId }.mapValues { (_, rows) -> rows.sumOf { it.workSets } })
        val recent = history.filter { it.startedAt >= asOfMillis - RECOVERY_WINDOW_MS }
            .sortedWith(compareBy<CompletedWorkout> { it.startedAt }.thenBy { it.id })
        val baselineVolume = workload.volume(baseline.flatMap { it.slots })
        val limits = workload.lines(baselineVolume).associateBy { it.group }
        fun forecast() = workload.volume(remaining.flatMap { days[it] }).mapValues { (group, sets) -> sets + actual.getValue(group) }
        fun editable(slot: PlannedSlot) = !slot.locked && slot.reasons.none { it.startsWith("Подходы изменены тобой:") }
        fun credit(slot: PlannedSlot, group: MuscleGroup) = catalog[slot.exerciseId]?.let { config.credit(it, group) } ?: 0.0
        fun change(day: Int, index: Int, delta: Int, reason: String) {
            val slot = days[day][index]
            days[day][index] = slot.copy(sets = slot.sets + delta, reasons = slot.reasons + reason)
            notes += "${baseline[day].title}: ${catalog[slot.exerciseId]?.name ?: slot.exerciseId}, ${slot.sets} → ${slot.sets + delta} подходов. $reason"
        }

        // Two comparable non-deload exposures are required; one difficult workout is not a trend.
        val struggling = catalog.keys.filter { id ->
            val exposures = recent.filter { !it.deload && it.work.any { w -> w.exerciseId == id && w.workSets > 0 } }.takeLast(2)
            exposures.size == 2 && exposures.all { session ->
                val work = session.work.filter { it.exerciseId == id }
                session.hard || work.any { it.belowRepTarget } ||
                    (!session.recoveryAdjusted && work.sumOf { it.workSets } < work.sumOf { it.plannedSets } * 0.8)
            }
        }.toSet()

        if (!deload) {
            remaining.forEach { d ->
                // At most two cuts for recovery per day; the session check-in can still ease it further.
                days[d].indices.filter { editable(days[d][it]) && days[d][it].exerciseId in struggling && days[d][it].sets > 1 }
                    .take(MAX_RECOVERY_CUTS_PER_DAY).forEach { index ->
                        change(d, index, -1, "Две последние тренировки этого упражнения дались тяжело: временно −1 подход для восстановления.")
                    }
            }
            // Actual extra work can reduce remaining volume, but never below the normal minimum,
            // never by more than one set per slot and never by increasing another group's deficit.
            remaining.forEach { d ->
                days[d].indices.sortedByDescending { days[d][it].role.rank }.forEach { index ->
                    val slot = days[d][index]
                    if (!editable(slot) || slot.sets <= config.sets.min || slot.sets != baseline[d].slots[index].sets) return@forEach
                    val before = forecast()
                    val over = MuscleGroup.entries.any { credit(slot, it) > 0 && before.getValue(it) > limits.getValue(it).max + 1e-9 }
                    val preserves = MuscleGroup.entries.all { group ->
                        before.getValue(group) - credit(slot, group) + 1e-9 >= minOf(before.getValue(group), limits.getValue(group).targetLow.toDouble())
                    }
                    if (over && preserves) change(d, index, -1, "В этом круге уже выполнен дополнительный объём на эти мышцы.")
                }
            }
        }

        val latest = recent.lastOrNull()
        val recoveryInRound = current.any { it.deload || it.hard || it.recoveryAdjusted }
        val canAdd = !deload && !recoveryInRound && latest?.hard != true && current.any { s -> s.work.any { it.workSets > 0 } }
        if (canAdd) {
            val added = IntArray(days.size)
            val used = mutableSetOf<Pair<Int, Int>>()
            repeat(days.size * MAX_ADDITIONS_PER_DAY) {
                val before = forecast()
                val candidates = remaining.flatMap { d -> days[d].indices.map { d to it } }.filter { (d, i) ->
                    val slot = days[d][i]
                    editable(slot) && slot.exerciseId !in struggling && (d to i) !in used &&
                        added[d] < MAX_ADDITIONS_PER_DAY && slot.sets == baseline[d].slots[i].sets &&
                        slot.sets < if (slot.role == SlotRole.COMPOUND) config.sets.compoundMax else config.sets.max
                }.mapNotNull { (d, i) ->
                    val slot = days[d][i]
                    val gain = MuscleGroup.entries.sumOf { group ->
                        // Do not "repair" an infeasible baseline, or carry missed volume into a new week.
                        val target = minOf(baselineVolume.getValue(group), limits.getValue(group).targetLow.toDouble())
                        minOf((target - before.getValue(group)).coerceAtLeast(0.0), credit(slot, group))
                    }
                    val respectsCaps = MuscleGroup.entries.all { group ->
                        val c = credit(slot, group)
                        c == 0.0 || before.getValue(group) + c <= limits.getValue(group).max + 1e-9
                    }
                    val trial = days[d].toMutableList().also { it[i] = slot.copy(sets = slot.sets + 1) }
                    if (gain <= 0 || !respectsCaps || workload.minutes(trial) > workload.budgetMinutes) null else Triple(d, i, gain)
                }.sortedWith(compareByDescending<Triple<Int, Int, Double>> { it.third }
                    .thenBy { workload.effort(days[it.first]) }.thenBy { it.first }.thenBy { it.second })
                val candidate = candidates.firstOrNull() ?: return@repeat
                val (d, i) = candidate
                used += d to i
                added[d]++
                change(d, i, 1, "По выполненным подходам этим мышцам не хватает объёма; +1 подход помещается в лимит времени.")
            }
        }
        if (current.isNotEmpty()) notes.add(0, "Учтено завершённых тренировок в текущем круге: ${current.size}. Разминка не входит в рабочий объём; замены учтены по фактическому упражнению.")
        if (deload || recoveryInRound) notes += "После облегчённой или тяжело давшейся тренировки пропущенные подходы не догоняем."
        if (current.isEmpty()) notes += "Новый круг начинается с базового объёма, без долга за прошлые недели."
        if (struggling.isNotEmpty()) notes += "Коррекция восстановления опирается на две последние тренировки упражнения; одна неудачная тренировка не меняет основу программы."
        notes += "За один день можно добавить не более $MAX_ADDITIONS_PER_DAY подходов, не более одного на упражнение. Закреплённые и вручную изменённые подходы сохраняются."
        return AdaptivePlan(
            days = baseline.mapIndexed { index, day -> workload.day(day.title, days[index]) },
            forecast = workload.lines(forecast()),
            notes = notes,
            historyCount = history.size,
        )
    }

    companion object {
        const val VERSION = 1
        const val MAX_ADDITIONS_PER_DAY = 2
        const val MAX_RECOVERY_CUTS_PER_DAY = 2
        private const val RECOVERY_WINDOW_MS = 28L * 24 * 60 * 60 * 1000
    }
}
