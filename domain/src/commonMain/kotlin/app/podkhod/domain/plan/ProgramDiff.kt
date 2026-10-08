package app.podkhod.domain.plan

/** What a regeneration changes, in lines for the confirmation dialog. */
object ProgramDiff {

    data class Day(val title: String, val slots: List<PlannedSlot>)

    fun diff(old: List<Day>, new: List<Day>, names: Map<String, String>): List<String> = buildList {
        fun name(id: String) = names[id] ?: id
        if (old.size != new.size) add("Тренировок в круге: ${old.size} → ${new.size}.")
        new.forEachIndexed { i, day ->
            val before = old.getOrNull(i)
            if (before == null) {
                add("${day.title}: новый день — ${day.slots.joinToString { name(it.exerciseId) }}.")
                return@forEachIndexed
            }
            val title = if (before.title == day.title) day.title else "${before.title} → ${day.title}"
            val oldById = before.slots.associateBy { it.exerciseId }
            val newById = day.slots.associateBy { it.exerciseId }
            val removed = before.slots.filter { it.exerciseId !in newById }.map { name(it.exerciseId) }
            val added = day.slots.filter { it.exerciseId !in oldById }.map { name(it.exerciseId) }
            if (removed.isNotEmpty()) add("$title: убрано — ${removed.joinToString()}.")
            if (added.isNotEmpty()) add("$title: добавлено — ${added.joinToString()}.")
            day.slots.forEach { s ->
                val o = oldById[s.exerciseId] ?: return@forEach
                val parts = mutableListOf<String>()
                if (o.sets != s.sets || o.repMin != s.repMin || o.repMax != s.repMax) {
                    parts += "${o.sets}×${Fmt.range(o.repMin, o.repMax)} → ${s.sets}×${Fmt.range(s.repMin, s.repMax)}"
                }
                if (o.targetWeightKg != s.targetWeightKg) parts += "${Fmt.kg(o.targetWeightKg)} → ${Fmt.kg(s.targetWeightKg)}"
                if (o.rirMin != s.rirMin || o.rirMax != s.rirMax) parts += "запас ${Fmt.range(o.rirMin, o.rirMax)} → ${Fmt.range(s.rirMin, s.rirMax)}"
                if (parts.isNotEmpty()) add("$title: ${name(s.exerciseId)} — ${parts.joinToString(", ")}.")
            }
        }
        old.drop(new.size).forEach { add("${it.title}: день убран.") }
    }
}
