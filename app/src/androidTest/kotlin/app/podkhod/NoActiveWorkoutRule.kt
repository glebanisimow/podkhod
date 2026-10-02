package app.podkhod

import app.podkhod.data.workout.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.koin.core.context.GlobalContext

/**
 * Discards an unfinished workout left by an earlier run or by manual use of the debug build, so every
 * scenario starts from the first exercise of a fresh session. Must run before the activity is launched.
 */
class NoActiveWorkoutRule : ExternalResource() {
    override fun before() {
        val workouts = GlobalContext.get().get<WorkoutRepository>()
        runBlocking { workouts.activeSessionNow()?.let { workouts.discard(it.id) } }
    }
}
