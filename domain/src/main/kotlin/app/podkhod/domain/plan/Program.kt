package app.podkhod.domain.plan

data class PlannedSlot(
    val exerciseId: String,
    val role: SlotRole,
    val sets: Int,
    val repMin: Int,
    val repMax: Int,
    val rirMin: Int,
    val rirMax: Int,
    val restSec: Int,
    /** Null when there is no basis for a number yet: the user finds it on warm-up sets. */
    val targetWeightKg: Double?,
    val supersetGroup: String? = null,
    val locked: Boolean = false,
    val marker: Boolean = false,
    /** Shown under the scheme, e.g. "до потери скорости". */
    val note: String? = null,
    /** "Почему так?" lines for this slot. */
    val reasons: List<String> = emptyList(),
)

data class PlannedDay(val title: String, val slots: List<PlannedSlot>, val estimatedMinutes: Int)

data class VolumeLine(
    val group: MuscleGroup,
    val sets: Double,
    /** Config range; [max] already includes the priority bonus. */
    val min: Int,
    val max: Int,
    val targetLow: Int,
    val targetHigh: Int,
    val priority: Boolean,
) {
    val withinTarget: Boolean get() = sets + 1e-9 >= targetLow && sets - 1e-9 <= max
}

data class GeneratedProgram(
    val name: String,
    val days: List<PlannedDay>,
    val weeks: Int,
    val mesocycleIndex: Int,
    val volume: List<VolumeLine>,
    /** Program-level "Почему так?" lines. */
    val notes: List<String>,
    /** Groups the generator could not bring into range, with the reason. */
    val shortfalls: List<String>,
)

/** A slot the user fixed with «не менять»: it survives regeneration as is. */
data class LockedSlot(val dayIndex: Int, val slot: PlannedSlot)
