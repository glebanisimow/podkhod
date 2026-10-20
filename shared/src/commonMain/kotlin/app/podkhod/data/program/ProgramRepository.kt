package app.podkhod.data.program

import app.podkhod.platform.nowMillis
import app.podkhod.data.db.withTransaction
import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.catalog.CatalogJsonFormat
import app.podkhod.data.catalog.TemplatesFile
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.db.PlannedExerciseEntity
import app.podkhod.data.db.ProgramDayEntity
import app.podkhod.data.db.ProgramDayWithExercises
import app.podkhod.data.db.ProgramEntity
import app.podkhod.domain.workout.ProgramRotation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class ActiveProgram(
    val program: ProgramEntity,
    val days: List<ProgramDayWithExercises>,
) {
    val nextIndex: Int get() = if (days.isEmpty()) 0 else ProgramRotation.normalize(program.nextDayIndex, days.size)
    val nextDay: ProgramDayWithExercises? get() = days.getOrNull(nextIndex)
}

class ProgramRepository(
    private val db: AppDatabase,
    private val assets: AssetReader,
    private val clock: () -> Long = ::nowMillis,
) {
    private val dao = db.programDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeProgram: Flow<ActiveProgram?> = dao.observeActive().flatMapLatest { program ->
        if (program == null) {
            flowOf(null)
        } else {
            dao.observeDays(program.id).map { days ->
                ActiveProgram(program, days.map { d -> d.copy(exercises = d.exercises.sortedBy { it.position }) })
            }
        }
    }

    /** Stage 1 has no generator yet: the first launch gets the bundled full-body A/B template. */
    suspend fun ensureDefaultProgram() {
        if (dao.active() != null) return
        val file = CatalogJsonFormat.decodeFromString(TemplatesFile.serializer(), assets.read(TEMPLATES_ASSET))
        val template = file.templates.first { it.id == DEFAULT_TEMPLATE_ID }
        db.withTransaction {
            if (dao.active() != null) return@withTransaction
            val programId = dao.insertProgram(
                ProgramEntity(
                    name = template.name,
                    source = "template",
                    templateId = template.id,
                    status = "active",
                    createdAt = clock(),
                    nextDayIndex = 0,
                ),
            )
            template.days.forEachIndexed { dayIndex, day ->
                val dayId = dao.insertDay(ProgramDayEntity(programId = programId, seqIndex = dayIndex, title = day.title))
                dao.insertPlanned(
                    day.exercises.mapIndexed { pos, x ->
                        PlannedExerciseEntity(
                            programDayId = dayId,
                            position = pos,
                            exerciseId = x.exercise,
                            supersetGroup = x.superset,
                            sets = x.sets,
                            repMin = x.reps[0],
                            repMax = x.reps[1],
                            rirMin = x.rir?.getOrNull(0),
                            rirMax = x.rir?.getOrNull(1),
                            restSec = x.rest,
                            targetWeightKg = null,
                            note = x.note,
                            locked = false,
                        )
                    },
                )
            }
        }
    }

    suspend fun skipNextDay() = db.withTransaction {
        val program = dao.active() ?: return@withTransaction
        val count = dao.days(program.id).size
        if (count == 0) return@withTransaction
        val index = ProgramRotation.normalize(program.nextDayIndex, count)
        dao.setNextDay(program.id, ProgramRotation.next(index, count))
        if (program.source == "generated" && index == count - 1 && program.weekIndex < program.weeks) {
            dao.setWeek(program.id, program.weekIndex + 1)
        }
    }

    suspend fun advanceAfterFinish(programId: Long, finishedDayIndex: Int) {
        val count = dao.days(programId).size
        if (count == 0) return
        dao.setNextDay(programId, ProgramRotation.afterFinish(finishedDayIndex, count))
    }

    companion object {
        const val TEMPLATES_ASSET = "templates.json"
        const val DEFAULT_TEMPLATE_ID = "fullbody_ab"
    }
}
