package app.podkhod.data.catalog

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.busy.Occupancy
import app.podkhod.domain.busy.StrengthRatio
import app.podkhod.domain.busy.SubstitutionConfig
import app.podkhod.domain.plan.GeneratorConfig
import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogFile(val catalogVersion: Int, val exercises: List<CatalogExercise>)

@Serializable
data class CatalogExercise(
    val id: String,
    val ru: String,
    val en: String,
    val pattern: String,
    val kind: String,
    val equipment: List<String>,
    val primary: List<String>,
    val secondary: List<String> = emptyList(),
    val measure: String,
    val load: String,
    val reps: List<Int>,
    val rest: Int,
    val step: Double,
    val occupancy: String,
    val difficulty: Int,
    val fatigue: Int,
    val tags: List<String> = emptyList(),
    val subs: List<String> = emptyList(),
    /** Kettlebells or dumbbells held at once when they must be the same weight. */
    val implements: Int = 1,
) {
    fun toInfo(order: Int) = ExerciseInfo(
        id = id,
        pattern = MovementPattern.fromCode(pattern),
        load = LoadType.fromCode(load),
        measure = Measure.fromCode(measure),
        repMin = reps[0],
        repMax = reps[1],
        stepKg = step,
        occupancy = Occupancy.fromCode(occupancy),
        equipment = equipment.toSet(),
        primary = primary.mapNotNull { m -> runCatching { Muscle.fromCode(m) }.getOrNull() }.toSet(),
        secondary = secondary.mapNotNull { m -> runCatching { Muscle.fromCode(m) }.getOrNull() }.toSet(),
        substitutes = subs,
        kind = ExerciseKind.fromCode(kind),
        tags = tags.toSet(),
        difficulty = difficulty,
        fatigue = fatigue,
        restSec = rest,
        implements = implements,
        order = order,
        name = ru,
    )
}

@Serializable
data class TechniqueFile(val techniqueVersion: Int, val items: Map<String, Technique>)

/** Stored as JSON in exercise.technique_json; texts are written for the app and are unreviewed until the user checks them. */
@Serializable
data class Technique(
    val cues: List<String>,
    val mistakes: List<String> = emptyList(),
    val safety: List<String> = emptyList(),
) {
    companion object {
        fun parse(json: String?): Technique? =
            json?.let { runCatching { CatalogJsonFormat.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

@Serializable
data class TemplatesFile(val templatesVersion: Int, val templates: List<ProgramTemplate>)

@Serializable
data class ProgramTemplate(val id: String, val name: String, val days: List<TemplateDay>)

@Serializable
data class TemplateDay(val title: String, val exercises: List<TemplateExercise>)

@Serializable
data class TemplateExercise(
    val exercise: String,
    val superset: String? = null,
    val sets: Int,
    val reps: List<Int>,
    val rir: List<Int>? = null,
    val rest: Int,
    val note: String? = null,
)

@Serializable
data class SubstitutionFile(
    val substitutionVersion: Int,
    val stations: List<String>,
    val unavailableEquipment: List<String> = emptyList(),
    val sessionMuscleCap: Int,
    val safetyFactor: Double,
    val waitExtras: List<String> = emptyList(),
    val families: Map<String, String>,
    val ratios: Map<String, RatioEntry>,
) {
    fun toConfig() = SubstitutionConfig(
        stations = stations.toSet(),
        unavailableEquipment = unavailableEquipment.toSet(),
        sessionMuscleCap = sessionMuscleCap,
        safetyFactor = safetyFactor,
        ratios = ratios.mapValues { (_, r) -> StrengthRatio(r.family, r.ratio) },
        waitExtras = waitExtras,
    )
}

@Serializable
data class RatioEntry(val family: String, val ratio: Double)

val CatalogJsonFormat = Json { ignoreUnknownKeys = true }

object CatalogValidator {
    val EQUIPMENT = setOf(
        "barbell", "rack", "bench", "adjustable_bench", "kettlebell", "pullup_bar", "dip_bars", "rings",
        "lat_pulldown", "cable_row", "chest_press_machine", "leg_press", "leg_extension", "leg_curl",
        "cable_station", "dumbbells", "belt_chain", "bands", "hyperextension",
    )
    val TAGS = setOf(
        "axial_load", "deep_squat", "knee_stress", "lower_back_stress", "grip_intensive", "shoulder_stress",
        "overhead", "ballistic", "spinal_flexion", "behind_neck",
    )
    val OCCUPANCY = setOf("low", "medium", "high")

    /** Returns human-readable problems; empty means the catalog is consistent. */
    fun validate(catalog: CatalogFile): List<String> {
        val problems = mutableListOf<String>()
        val ids = catalog.exercises.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "duplicate id $it" }
        val idSet = ids.toSet()
        catalog.exercises.forEach { e ->
            fun check(ok: Boolean, msg: String) { if (!ok) problems += "${e.id}: $msg" }
            check(e.id.matches(Regex("[a-z0-9_]+")), "id must be a lowercase slug")
            check(e.ru.isNotBlank(), "empty Russian name")
            check(runCatching { MovementPattern.fromCode(e.pattern) }.isSuccess, "bad pattern ${e.pattern}")
            check(runCatching { ExerciseKind.fromCode(e.kind) }.isSuccess, "bad kind ${e.kind}")
            check(runCatching { Measure.fromCode(e.measure) }.isSuccess, "bad measure ${e.measure}")
            check(runCatching { LoadType.fromCode(e.load) }.isSuccess, "bad load ${e.load}")
            check(e.primary.isNotEmpty(), "no primary muscles")
            (e.primary + e.secondary).forEach { m ->
                check(runCatching { Muscle.fromCode(m) }.isSuccess, "bad muscle $m")
            }
            check(e.primary.intersect(e.secondary.toSet()).isEmpty(), "muscle is both primary and secondary")
            e.equipment.forEach { check(it in EQUIPMENT, "unknown equipment $it") }
            e.tags.forEach { check(it in TAGS, "unknown tag $it") }
            check(e.occupancy in OCCUPANCY, "bad occupancy ${e.occupancy}")
            check(e.reps.size == 2 && e.reps[0] in 1..e.reps[1], "reps must be [min, max]")
            check(e.rest in 15..600, "rest out of range")
            check(e.step >= 0.0, "negative step")
            check(e.difficulty in 1..3 && e.fatigue in 1..3, "difficulty/fatigue must be 1..3")
            check(e.implements in 1..2, "implements must be 1 or 2")
            check(e.implements == 1 || "kettlebell" in e.equipment || "dumbbells" in e.equipment, "implements without kettlebells or dumbbells")
            e.subs.forEach { s ->
                check(s in idSet, "substitute $s does not exist")
                check(s != e.id, "substitutes itself")
            }
        }
        return problems
    }

    fun validateTechnique(technique: TechniqueFile, catalogIds: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        (catalogIds - technique.items.keys).forEach { problems += "$it: no technique" }
        (technique.items.keys - catalogIds).forEach { problems += "$it: technique for unknown exercise" }
        technique.items.forEach { (id, t) ->
            if (t.cues.size !in 3..5) problems += "$id: needs 3-5 cues, has ${t.cues.size}"
            if (t.mistakes.size !in 2..3) problems += "$id: needs 2-3 mistakes, has ${t.mistakes.size}"
            (t.cues + t.mistakes + t.safety).forEach { if (it.isBlank()) problems += "$id: blank line" }
        }
        return problems
    }

    fun validateSubstitution(file: SubstitutionFile, catalog: CatalogFile): List<String> {
        val problems = mutableListOf<String>()
        val byId = catalog.exercises.associateBy { it.id }
        (file.stations + file.unavailableEquipment).forEach { if (it !in EQUIPMENT) problems += "unknown equipment $it" }
        if (file.sessionMuscleCap !in 4..30) problems += "sessionMuscleCap out of range"
        if (file.safetyFactor !in 0.5..1.0) problems += "safetyFactor must be within 0.5..1.0"
        file.waitExtras.forEach { if (it !in byId) problems += "wait extra $it does not exist" }
        file.families.forEach { (family, reference) ->
            val r = file.ratios[reference]
            if (r == null || r.family != family || r.ratio != 1.0) problems += "family $family: reference $reference must have ratio 1.0"
        }
        file.ratios.forEach { (id, r) ->
            val e = byId[id]
            when {
                e == null -> problems += "ratio for unknown exercise $id"
                e.load != LoadType.EXTERNAL.code -> problems += "$id: ratios only apply to external load"
            }
            if (r.family !in file.families) problems += "$id: unknown family ${r.family}"
            if (r.ratio <= 0.0 || r.ratio > 10.0) problems += "$id: ratio out of range"
        }
        return problems
    }

    /** Config problems in Russian: they are shown to the user when an edited config is imported. */
    fun validateGeneratorConfig(config: GeneratorConfig, catalogIds: Set<String>): List<String> {
        val problems = config.validate().toMutableList()
        config.baselineExercises.forEach { (lift, id) -> if (id !in catalogIds) problems += "baselineExercises.$lift: нет упражнения $id" }
        config.restrictionTags.forEach { (key, tags) ->
            tags.forEach { if (it !in TAGS) problems += "restrictionTags.$key: неизвестный тег $it" }
        }
        (config.equipmentClass.keys + config.stations).forEach { if (it !in EQUIPMENT) problems += "неизвестное оборудование $it" }
        return problems
    }

    fun validateTemplates(templates: TemplatesFile, catalogIds: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        templates.templates.forEach { t ->
            if (t.days.isEmpty()) problems += "${t.id}: no days"
            t.days.forEach { d ->
                d.exercises.forEach { x ->
                    val where = "${t.id}/${d.title}/${x.exercise}"
                    if (x.exercise !in catalogIds) problems += "$where: unknown exercise"
                    if (x.sets !in 1..10) problems += "$where: sets out of range"
                    if (x.reps.size != 2 || x.reps[0] !in 1..x.reps[1]) problems += "$where: reps must be [min, max]"
                    if (x.rir != null && (x.rir.size != 2 || x.rir[0] !in 0..x.rir[1])) problems += "$where: rir must be [min, max]"
                }
                d.exercises.mapNotNull { it.superset }.groupBy { it }.forEach { (g, members) ->
                    if (members.size < 2) problems += "${t.id}/${d.title}: superset $g has one member"
                }
            }
        }
        return problems
    }
}
