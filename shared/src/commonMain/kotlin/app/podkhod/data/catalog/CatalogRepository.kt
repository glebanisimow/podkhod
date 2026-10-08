package app.podkhod.data.catalog

import kotlin.concurrent.Volatile
import kotlinx.coroutines.IO
import app.podkhod.platform.Log
import app.podkhod.data.db.AppMetaDao
import app.podkhod.data.db.AppMetaEntity
import app.podkhod.data.db.CatalogDao
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.db.ExerciseEquipmentEntity
import app.podkhod.data.db.ExerciseMuscleEntity
import app.podkhod.data.db.ExerciseSubstituteEntity
import app.podkhod.data.db.ExerciseTagEntity
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.busy.SubstitutionConfig
import app.podkhod.domain.model.Muscle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import app.podkhod.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

data class MuscleSet(val primary: Set<Muscle>, val secondary: Set<Muscle>) {
    companion object {
        val EMPTY = MuscleSet(emptySet(), emptySet())
    }
}

/** Bundled JSON files from composeResources/files. */
class AssetReader {
    @OptIn(ExperimentalResourceApi::class)
    suspend fun read(name: String): String = Res.readBytes("files/$name").decodeToString()
}

class CatalogRepository(
    private val dao: CatalogDao,
    private val meta: AppMetaDao,
    private val assets: AssetReader,
) {

    val exercisesById: Flow<Map<String, ExerciseEntity>> = dao.observeExercises().map { list -> list.associateBy { it.id } }

    val musclesByExercise: Flow<Map<String, MuscleSet>> = dao.observeMuscles().map { rows ->
        rows.groupBy { it.exerciseId }.mapValues { (_, list) ->
            MuscleSet(
                primary = list.filter { it.role == ROLE_PRIMARY }.mapNotNull { muscleOrNull(it.muscle) }.toSet(),
                secondary = list.filter { it.role == ROLE_SECONDARY }.mapNotNull { muscleOrNull(it.muscle) }.toSet(),
            )
        }
    }

    private fun muscleOrNull(code: String) = runCatching { Muscle.fromCode(code) }.getOrNull()

    /** Loads assets/catalog.json into Room when the bundled version is newer than the stored one. */
    suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        val file = CatalogJsonFormat.decodeFromString(CatalogFile.serializer(), assets.read(CATALOG_ASSET))
        val stored = meta.get(KEY_CATALOG_VERSION)?.toIntOrNull() ?: 0
        if (stored >= file.catalogVersion && dao.count() > 0) return@withContext

        val technique = CatalogJsonFormat.decodeFromString(TechniqueFile.serializer(), assets.read(TECHNIQUE_ASSET))
        val problems = CatalogValidator.validate(file) +
            CatalogValidator.validateTechnique(technique, file.exercises.map { it.id }.toSet())
        if (problems.isNotEmpty()) {
            // Ship-blocking: unit tests validate the same assets, so this only fires on a broken build.
            Log.e(TAG, "catalog has ${problems.size} problems, first: ${problems.first()}")
        }
        dao.replaceCatalog(
            exercises = file.exercises.map { e ->
                e.toEntity(file.catalogVersion, technique.items[e.id]?.let { CatalogJsonFormat.encodeToString(Technique.serializer(), it) })
            },
            equipment = file.exercises.flatMap { e -> e.equipment.distinct().map { ExerciseEquipmentEntity(e.id, it) } },
            muscles = file.exercises.flatMap { e ->
                e.primary.distinct().map { ExerciseMuscleEntity(e.id, it, ROLE_PRIMARY) } +
                    e.secondary.distinct().map { ExerciseMuscleEntity(e.id, it, ROLE_SECONDARY) }
            },
            tags = file.exercises.flatMap { e -> e.tags.distinct().map { ExerciseTagEntity(e.id, it) } },
            substitutes = file.exercises.flatMap { e ->
                e.subs.distinct().mapIndexed { i, s -> ExerciseSubstituteEntity(e.id, s, i) }
            },
        )
        meta.put(AppMetaEntity(KEY_CATALOG_VERSION, file.catalogVersion.toString()))
        infoCache = null
    }

    suspend fun exercises(ids: Collection<String>): Map<String, ExerciseEntity> =
        dao.exercises(ids.distinct()).associateBy { it.id }

    suspend fun muscles(ids: Collection<String>): List<ExerciseMuscleEntity> = dao.muscles(ids.distinct())

    suspend fun substitutes(exerciseId: String): List<ExerciseEntity> = dao.substitutes(exerciseId)

    suspend fun ids(): Set<String> = dao.ids().toSet()

    @Volatile
    private var substitutionCache: SubstitutionConfig? = null

    suspend fun substitutionConfig(): SubstitutionConfig = substitutionCache
        ?: CatalogJsonFormat.decodeFromString(SubstitutionFile.serializer(), assets.read(SUBSTITUTION_ASSET)).toConfig()
            .also { substitutionCache = it }

    @Volatile
    private var infoCache: Map<String, ExerciseInfo>? = null

    /**
     * Catalog view for the busy mode and the generator, in file order. Built from the bundled asset (the
     * Room copy is seeded from the same file), so it carries fields Room does not store.
     */
    suspend fun exerciseInfos(): Map<String, ExerciseInfo> = infoCache ?: withContext(Dispatchers.IO) {
        val file = CatalogJsonFormat.decodeFromString(CatalogFile.serializer(), assets.read(CATALOG_ASSET))
        val map = LinkedHashMap<String, ExerciseInfo>()
        file.exercises.forEachIndexed { i, e -> map[e.id] = e.toInfo(i) }
        map.also { infoCache = it }
    }

    suspend fun generatorConfigAsset(): String = assets.read(GENERATOR_CONFIG_ASSET)

    private fun CatalogExercise.toEntity(version: Int, techniqueJson: String?) = ExerciseEntity(
        id = id,
        nameRu = ru,
        nameEn = en,
        pattern = pattern,
        kind = kind,
        measure = measure,
        load = load,
        repMin = reps[0],
        repMax = reps[1],
        restSec = rest,
        stepKg = step,
        occupancyRisk = occupancy,
        difficulty = difficulty,
        fatigue = fatigue,
        techniqueJson = techniqueJson,
        mediaPath = null,
        stravaCategory = strava,
        reviewed = false,
        catalogVersion = version,
    )

    companion object {
        const val CATALOG_ASSET = "catalog.json"
        const val TECHNIQUE_ASSET = "technique.json"
        const val SUBSTITUTION_ASSET = "substitution.json"
        const val GENERATOR_CONFIG_ASSET = "generator_config.json"
        const val KEY_CATALOG_VERSION = "catalog_version"
        const val ROLE_PRIMARY = "primary"
        const val ROLE_SECONDARY = "secondary"
        private const val TAG = "Catalog"
    }
}
