package app.podkhod.domain.workout

/**
 * Training days run as a loop (A → B → C → A …), independent of calendar dates: a missed day does not
 * disappear, it simply stays next. Only finishing a workout of that day or an explicit skip advances.
 */
object ProgramRotation {
    fun next(currentIndex: Int, dayCount: Int): Int {
        require(dayCount > 0) { "program has no days" }
        return (currentIndex.coerceAtLeast(0) + 1) % dayCount
    }

    fun normalize(index: Int, dayCount: Int): Int {
        require(dayCount > 0) { "program has no days" }
        return ((index % dayCount) + dayCount) % dayCount
    }

    /** Pointer after finishing the workout of [finishedDayIndex]; finishing an off-rotation day re-syncs to it. */
    fun afterFinish(finishedDayIndex: Int, dayCount: Int): Int = next(normalize(finishedDayIndex, dayCount), dayCount)
}
