package app.podkhod.domain.busy

data class QueueSlot(val id: Long, val exerciseId: String, val supersetGroup: String?, val done: Boolean)

/** New slot order (ids, first to last) and the slot to show next. */
data class Reorder(val order: List<Long>, val nextId: Long)

/** [slotId] is a remaining slot that does not need the busy equipment; [extras] are light fillers. */
data class WaitPlan(val slotId: Long?, val extras: List<String>)

object BusyQueue {

    /**
     * "Do it later": the slot (with its superset partners) goes to the end of the queue, everything else
     * keeps its order. The next slot shown is the first unfinished one that took the moved slot's place.
     */
    fun moveToEnd(queue: List<QueueSlot>, slotId: Long): Reorder {
        val target = queue.firstOrNull { it.id == slotId } ?: return Reorder(queue.map { it.id }, slotId)
        val moved = queue.filter { it.id == slotId || (target.supersetGroup != null && it.supersetGroup == target.supersetGroup) }
        val movedIds = moved.map { it.id }.toSet()
        val rest = queue.filter { it.id !in movedIds }
        val order = rest + moved
        val startIndex = queue.indexOfFirst { it.id in movedIds }
        val next = rest.drop(startIndex).firstOrNull { !it.done }
            ?: rest.firstOrNull { !it.done }
            ?: moved.first()
        return Reorder(order.map { it.id }, next.id)
    }

    /**
     * "What to do while waiting": the nearest unfinished slot after the current one that needs none of the
     * busy equipment, plus up to [extrasLimit] light fillers that are not already in the session.
     */
    fun whileWaiting(
        queue: List<QueueSlot>,
        currentId: Long,
        catalog: Map<String, ExerciseInfo>,
        config: SubstitutionConfig,
        extrasLimit: Int = 3,
    ): WaitPlan {
        val index = queue.indexOfFirst { it.id == currentId }
        val current = queue.getOrNull(index) ?: return WaitPlan(null, emptyList())
        val busy = catalog[current.exerciseId]?.let { Substitution.busyEquipment(it, config) }.orEmpty()
        fun free(exerciseId: String): Boolean {
            val info = catalog[exerciseId] ?: return false
            return info.equipment.none { it in busy } && info.equipment.none { it in config.unavailableEquipment }
        }
        val after = queue.drop(index + 1) + queue.take(index)
        val slot = after.firstOrNull { s ->
            !s.done && (current.supersetGroup == null || s.supersetGroup != current.supersetGroup) && free(s.exerciseId)
        }
        val inSession = queue.map { it.exerciseId }.toSet()
        val extras = config.waitExtras.filter { it !in inSession && free(it) }.take(extrasLimit)
        return WaitPlan(slot?.id, extras)
    }
}
