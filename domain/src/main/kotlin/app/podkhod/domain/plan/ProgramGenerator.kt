package app.podkhod.domain.plan

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.LoadScheme
import app.podkhod.domain.weights.Loads
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Deterministic, offline program generator: the same profile, catalog, config and history always give the
 * same program. Every decision leaves a line in the slot's or the program's explanation.
 */
class ProgramGenerator(private val config: GeneratorConfig) {

    data class Input(
        val profile: Profile,
        /** Catalog in file order. */
        val catalog: List<ExerciseInfo>,
        /** History and progression; onboarding lifts and body mass are taken from [profile]. */
        val strength: StrengthData = StrengthData(),
        val busyCounts: Map<String, Int> = emptyMap(),
        val locked: List<LockedSlot> = emptyList(),
        val mesocycleIndex: Int = 0,
        val accessoryWarmupSets: Int = 1,
    )

    private class Pick(
        val info: ExerciseInfo,
        val role: SlotRole,
        var sets: Int,
        val repMin: Int,
        val repMax: Int,
        val rirMin: Int,
        val rirMax: Int,
        val restSec: Int,
        val note: String?,
        val locked: Boolean,
        val marker: Boolean,
        var templateIndex: Int,
        val reasons: MutableList<String> = mutableListOf(),
        var weightKg: Double? = null,
        var superset: String? = null,
        var partner: Pick? = null,
        var antagonist: Boolean = false,
    ) {
        val baseSets = sets
    }

    private inner class Run(val input: Input) {
        val profile = input.profile
        val inventory: Inventory = profile.inventory
        val available = inventory.available
        val bannedTags: Set<String> = profile.restrictionTags.flatMap { config.restrictionTags[it] ?: listOf(it) }.toSet()
        val skippedRefs: Set<String> = profile.baselines.filterValues { it.skipped }.keys
            .mapNotNull { config.baselineExercises[it.code] }.toSet()
        val excludedIds = profile.excludedExercises + skippedRefs
        val days = profile.daysPerWeek.coerceIn(2, 4)
        val intensive = profile.style == Style.INTENSIVE
        val perSession = config.perSession(profile.style, profile.sessionMinutes)
        val budgetMin = profile.sessionMinutes * (1.0 + config.timing.tolerance)
        val schemes = HashMap<String, LoadScheme>()

        fun scheme(info: ExerciseInfo): LoadScheme = schemes.getOrPut(info.id) { Loads.scheme(info, inventory) }

        /** Why [info] cannot be used, or null if it can. */
        fun exclusion(info: ExerciseInfo): String? {
            val missing = info.equipment - available
            if (missing.isNotEmpty()) return "нет оборудования: ${missing.sorted().joinToString { Fmt.equipment(it) }}"
            val tag = info.tags.firstOrNull { it in bannedTags }
            if (tag != null) return "ограничение: ${Fmt.tag(tag)}"
            if (info.id in excludedIds) return "исключено тобой"
            val s = scheme(info)
            if (s is LoadScheme.Implements && s.weightsKg.isEmpty()) {
                return if (info.implements > 1) "нужно ${info.implements} гири одного веса" else "нет подходящего веса"
            }
            return null
        }

        val eligible: List<ExerciseInfo> = input.catalog.filter { exclusion(it) == null }

        val targets: Map<MuscleGroup, Target> = MuscleGroup.entries.associateWith { g ->
            val r = config.rangeFor(g)
            val bonus = if (g in profile.priorities) config.priorityBonus[days.toString()] ?: 0 else 0
            val high = if (intensive && days <= config.intensiveLowDaysMax) ceil((r.first + r.last) / 2.0).toInt() else r.last
            Target(r.first + bonus, high + bonus, r.last + bonus, g in profile.priorities)
        }
    }

    private data class Target(val low: Int, val high: Int, val hardMax: Int, val priority: Boolean)

    fun generate(input: Input): GeneratedProgram {
        val run = Run(input)
        val templates = config.splits.getValue(run.days.toString())
        val notes = mutableListOf<String>()
        val shortfalls = mutableListOf<String>()
        val dayPicks = List(run.days) { mutableListOf<Pick>() }
        val usedAll = mutableSetOf<String>()

        input.locked.filter { it.dayIndex in 0 until run.days }.forEach { l ->
            val info = input.catalog.firstOrNull { it.id == l.slot.exerciseId } ?: return@forEach
            dayPicks[l.dayIndex] += lockedPick(info, l.slot)
            usedAll += info.id
        }

        templates.forEachIndexed { d, template ->
            val picks = dayPicks[d]
            val consumed = BooleanArray(template.slots.size)
            picks.forEach { pk ->
                val i = template.slots.indices.firstOrNull { !consumed[it] && matches(template.slots[it], pk.info, 1) }
                if (i != null) {
                    consumed[i] = true
                    pk.templateIndex = i
                }
            }
            val firstCompound = template.slots.indexOfFirst { it.role == SlotRole.COMPOUND.code }
            template.slots.forEachIndexed { i, slot ->
                if (consumed[i] || picks.size >= run.perSession) return@forEachIndexed
                val role = SlotRole.fromCode(slot.role) ?: return@forEachIndexed
                val marker = i == firstCompound && d < config.mesocycle.markers
                val usedToday = picks.map { it.info.id }.toSet()
                when (val s = select(run, slot, role, usedToday, usedAll, marker)) {
                    is Selection.Chosen -> {
                        val effective = if (role == SlotRole.ISOLATION && s.info.kind != ExerciseKind.ISOLATION) SlotRole.ACCESSORY else role
                        picks += prescribe(run, s.info, effective, marker, i, s.reasons)
                        usedAll += s.info.id
                    }
                    is Selection.None -> notes += "${template.title}: слот «${describe(slot, role)}» пропущен — ${s.reason}."
                }
            }
        }

        dayPicks.forEach { assignSupersets(run, it) }
        // First pass only makes room for balancing; its cuts are not worth reporting.
        dayPicks.indices.forEach { d -> fitTime(run, dayPicks, d, templates[d].title, mutableListOf(), protectTargets = false) }
        balance(run, dayPicks, templates, notes)
        dayPicks.forEach { assignSupersets(run, it) }
        dayPicks.indices.forEach { d -> fitTime(run, dayPicks, d, templates[d].title, notes, protectTargets = true) }

        val weights = StartingWeights(
            config,
            input.strength.copy(baselines = input.profile.baselines, bodyMassKg = input.profile.bodyMassKg),
            input.catalog.associate { it.id to it.name },
        )
        dayPicks.flatten().filter { !it.locked }.forEach { pk ->
            val w = weights.pick(pk.info, run.scheme(pk.info), pk.repMin, pk.rirMax)
            pk.weightKg = w.weightKg
            w.reason?.let { pk.reasons += it }
        }
        dayPicks.flatten().forEach { pk ->
            if (pk.sets != pk.baseSets && !pk.locked) {
                pk.reasons += "Подходы ${pk.baseSets} → ${pk.sets}: подгонка недельного объёма к цели."
            }
            pk.partner?.let { other ->
                pk.reasons += "Суперсет ${pk.superset} с «${other.info.name}»: " +
                    (if (pk.antagonist) "антагонисты" else "неконкурирующие группы") + " — один отдых на круг."
            }
        }

        val volume = volumeOf(run, dayPicks.flatten())
        val lines = MuscleGroup.entries.map { g ->
            val t = run.targets.getValue(g)
            val r = config.rangeFor(g)
            VolumeLine(g, volume.getValue(g), r.first, t.hardMax, t.low, t.high, t.priority)
        }
        lines.forEach { l ->
            if (l.sets + 1e-9 < l.targetLow) shortfalls += "${Fmt.group(l.group)}: ${Fmt.num(l.sets)} из ${l.targetLow} подходов в неделю — " +
                "не хватило подходящих упражнений или времени."
            if (l.sets - 1e-9 > run.targets.getValue(l.group).hardMax) shortfalls += "${Fmt.group(l.group)}: ${Fmt.num(l.sets)} подходов — выше " +
                "${run.targets.getValue(l.group).hardMax}: её нагружают базовые упражнения, а убрать или заменить вспомогательные " +
                "без потерь для других групп и без пустых дней нельзя."
        }

        val days = dayPicks.mapIndexed { d, picks ->
            PlannedDay(templates[d].title, ordered(picks).map { it.toSlot() }, minutes(run, picks).roundToInt())
        }
        return GeneratedProgram(
            name = splitName(run.days),
            days = days,
            weeks = input.profile.mesocycleWeeks.coerceIn(config.mesocycle.minWeeks, config.mesocycle.maxWeeks),
            mesocycleIndex = input.mesocycleIndex,
            volume = lines,
            notes = programNotes(run, input) + notes,
            shortfalls = shortfalls,
        )
    }

    // ---- selection ----

    private sealed interface Selection {
        data class Chosen(val info: ExerciseInfo, val reasons: List<String>) : Selection
        data class None(val reason: String) : Selection
    }

    private fun slotMuscles(slot: SlotTemplate): Set<Muscle> = slot.muscles.map(Muscle::fromCode).toSet()

    private fun slotPatterns(slot: SlotTemplate): Set<MovementPattern> = slot.patterns.map(MovementPattern::fromCode).toSet()

    /** Level 0: strict; 1: kinds relaxed; 2: pattern dropped, only the target muscles must match. */
    private fun matches(slot: SlotTemplate, info: ExerciseInfo, level: Int): Boolean {
        val role = SlotRole.fromCode(slot.role) ?: return false
        val kinds = when {
            level == 0 && role == SlotRole.CORE -> setOf(ExerciseKind.ISOLATION)
            level == 0 -> role.kinds
            else -> role.relaxedKinds
        }
        if (info.kind !in kinds) return false
        val patterns = slotPatterns(slot)
        val muscles = slotMuscles(slot)
        if (level < 2) {
            if (patterns.isNotEmpty() && info.pattern !in patterns) return false
            if (muscles.isNotEmpty() && info.primary.none { it in muscles }) return false
            if (role == SlotRole.CORE && info.pattern != MovementPattern.CORE && info.pattern != MovementPattern.CARRY) return false
        } else {
            val target = muscles.ifEmpty { patterns.flatMap(::impliedMuscles).toSet() }
            if (target.isNotEmpty() && info.primary.none { it in target }) return false
        }
        return true
    }

    private fun impliedMuscles(p: MovementPattern): Set<Muscle> = when (p) {
        MovementPattern.SQUAT -> setOf(Muscle.QUADS)
        MovementPattern.HINGE -> setOf(Muscle.HAMSTRINGS, Muscle.GLUTES)
        MovementPattern.LUNGE -> setOf(Muscle.QUADS, Muscle.GLUTES)
        MovementPattern.H_PUSH -> setOf(Muscle.CHEST)
        MovementPattern.V_PUSH -> setOf(Muscle.DELTS_FRONT, Muscle.DELTS_SIDE)
        MovementPattern.H_PULL, MovementPattern.V_PULL -> setOf(Muscle.LATS, Muscle.UPPER_BACK)
        MovementPattern.CORE, MovementPattern.CARRY -> setOf(Muscle.ABS)
        MovementPattern.ISOLATION -> emptySet()
    }

    private fun select(
        run: Run,
        slot: SlotTemplate,
        role: SlotRole,
        usedToday: Set<String>,
        usedOther: Set<String>,
        marker: Boolean,
    ): Selection {
        for (level in 0..2) {
            val candidates = run.eligible.filter { it.id !in usedToday && matches(slot, it, level) }
            if (candidates.isEmpty()) continue
            val muscles = slot.muscles.map(Muscle::fromCode)
            fun key(e: ExerciseInfo): List<Int> = listOf(
                if (e.id in usedOther) 1 else 0,
                if (muscles.isEmpty() || muscles.first() in e.primary) 0 else 1,
                config.classOf(e.equipment),
                if (role == SlotRole.COMPOUND) 0 else e.fatigue,
                if ((run.input.busyCounts[e.id] ?: 0) >= config.busyThreshold) 1 else 0,
                e.occupancy.ordinal,
                e.order,
            )
            val ranked = candidates.sortedWith { a, b -> compareKeys(key(a), key(b)) }
            val best = ranked.first()
            val equivalents = ranked.filter { key(it).take(3) == key(best).take(3) }
            val rotate = !marker && run.input.mesocycleIndex > 0 && equivalents.size > 1
            val chosen = if (rotate) equivalents[run.input.mesocycleIndex % equivalents.size] else best

            val reasons = mutableListOf<String>()
            reasons += "Слот: ${describe(slot, role)}."
            reasons += "Выбрано: ${classLabel(config.classOf(chosen.equipment))}" +
                (if (chosen.id in usedOther) "; повтор с другим днём — других вариантов нет" else "") + "."
            if (level > 0) reasons += "Точного совпадения нет — условие слота ослаблено."
            if (marker) {
                reasons += "Маркерное упражнение: не меняется между мезоциклами, по нему видно прогресс."
            } else if (rotate) {
                reasons += "Мезоцикл ${run.input.mesocycleIndex + 1}: вариант ${equivalents.indexOf(chosen) + 1} из ${equivalents.size} (ротация)."
            }
            if ((run.input.busyCounts[chosen.id] ?: 0) < config.busyThreshold &&
                equivalents.any { (run.input.busyCounts[it.id] ?: 0) >= config.busyThreshold }
            ) {
                reasons += "Часто занятые снаряды поставлены ниже."
            }
            val rejected = run.input.catalog
                .filter { it.id != chosen.id && matches(slot, it, 0) }
                .mapNotNull { e -> run.exclusion(e)?.let { "${e.name} ($it)" } }
                .take(MAX_REJECTED)
            if (rejected.isNotEmpty()) reasons += "Не подошли: ${rejected.joinToString()}."
            return Selection.Chosen(chosen, reasons)
        }
        val rejected = run.input.catalog.filter { matches(slot, it, 2) }.mapNotNull { run.exclusion(it) }.distinct().take(2)
        return Selection.None(if (rejected.isEmpty()) "в каталоге нет упражнения" else rejected.joinToString())
    }

    private fun compareKeys(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    private fun prescribe(run: Run, info: ExerciseInfo, role: SlotRole, marker: Boolean, templateIndex: Int, reasons: List<String>): Pick {
        val rx = config.prescriptionFor(run.profile.style, role)
        val adj = config.goals[run.profile.goal.code]
        var reps = rx.reps
        var sets = rx.sets
        when (role) {
            SlotRole.COMPOUND -> {
                adj?.compoundReps?.let { reps = it }
                adj?.compoundSets?.let { sets = it }
            }
            SlotRole.ACCESSORY -> adj?.accessorySets?.let { sets = it }
            SlotRole.ISOLATION, SlotRole.CORE -> adj?.isolationSets?.let { sets = it }
        }
        sets = sets.coerceAtLeast(config.sets.min)
        val (repMin, repMax) = if (info.measure == Measure.SECONDS) {
            info.repMin to info.repMax
        } else {
            val lo = maxOf(reps.min, info.repMin)
            val hi = minOf(reps.max, info.repMax)
            if (hi - lo >= 1) lo to hi else info.repMin to info.repMax
        }
        val barbell = Inventory.BARBELL in info.equipment
        val ballistic = "ballistic" in info.tags
        val rir = when {
            ballistic -> config.ballistic.rir
            barbell && (role == SlotRole.COMPOUND || role == SlotRole.ACCESSORY) -> rx.rir
            role == SlotRole.COMPOUND || role == SlotRole.ACCESSORY -> config.rirNonBarbell
            else -> rx.rir
        }
        val unit = if (info.measure == Measure.SECONDS) "с" else "повт."
        val all = reasons.toMutableList()
        all += "Схема $sets × ${Fmt.range(repMin, repMax)} $unit, запас ${Fmt.range(rir.min, rir.max)}, отдых ${rx.restSec} с: " +
            "${Fmt.role(role)}, стиль «${styleLabel(run.profile.style)}»" +
            (if (ballistic) ", баллистика — до потери скорости" else if (barbell) ", штанга" else "") + "."
        return Pick(
            info = info, role = role, sets = sets, repMin = repMin, repMax = repMax, rirMin = rir.min, rirMax = rir.max,
            restSec = rx.restSec, note = if (ballistic) config.ballistic.note else null, locked = false, marker = marker,
            templateIndex = templateIndex, reasons = all,
        )
    }

    private fun lockedPick(info: ExerciseInfo, s: PlannedSlot) = Pick(
        info = info, role = s.role, sets = s.sets, repMin = s.repMin, repMax = s.repMax, rirMin = s.rirMin, rirMax = s.rirMax,
        restSec = s.restSec, note = s.note, locked = true, marker = s.marker, templateIndex = LOCKED_INDEX,
        reasons = mutableListOf("Зафиксировано тобой («не менять»): генератор сохранил упражнение, подходы и вес."),
    ).also { it.weightKg = s.targetWeightKg }

    // ---- volume ----

    private fun credit(info: ExerciseInfo, g: MuscleGroup): Double = config.credit(info, g)

    /** Removing [sets] of [p] keeps every group it trains at or above its low target. */
    private fun keepsLows(run: Run, p: Pick, sets: Int, volume: Map<MuscleGroup, Double>): Boolean =
        MuscleGroup.entries.all { o ->
            val c = credit(p.info, o)
            c == 0.0 || volume.getValue(o) - sets * c + 1e-9 >= run.targets.getValue(o).low
        }

    private fun volumeOf(run: Run, picks: List<Pick>): Map<MuscleGroup, Double> =
        MuscleGroup.entries.associateWith { g -> picks.sumOf { it.sets * credit(it.info, g) } }

    private fun maxSets(p: Pick): Int = if (p.role == SlotRole.COMPOUND) config.sets.compoundMax else config.sets.max

    /**
     * Rounds of: cut what is past the hard maximum (frees time), fill groups below their minimum, trim what
     * is above the soft target. A group that could not be filled is retried next round, after cuts.
     */
    private fun balance(run: Run, dayPicks: List<MutableList<Pick>>, templates: List<DayTemplate>, notes: MutableList<String>) {
        repeat(BALANCE_ROUNDS) {
            var changed = trim(run, dayPicks, templates, notes, soft = false)
            changed = fill(run, dayPicks, templates, notes) || changed
            changed = trim(run, dayPicks, templates, notes, soft = true) || changed
            if (!changed) return
        }
    }

    private fun fill(run: Run, dayPicks: List<MutableList<Pick>>, templates: List<DayTemplate>, notes: MutableList<String>): Boolean {
        val unmet = mutableSetOf<MuscleGroup>()
        var changed = false
        repeat(MAX_BALANCE_STEPS) {
            val volume = volumeOf(run, dayPicks.flatten())
            val deficit = MuscleGroup.entries
                .filter { it !in unmet && volume.getValue(it) + 1e-9 < run.targets.getValue(it).low }
                .sortedByDescending { run.targets.getValue(it).low - volume.getValue(it) }
                .firstOrNull() ?: return changed
            if (addSet(run, dayPicks, deficit, volume) || addExercise(run, dayPicks, templates, deficit, volume, notes)) {
                changed = true
            } else {
                unmet += deficit
            }
        }
        return changed
    }

    private fun trim(run: Run, dayPicks: List<MutableList<Pick>>, templates: List<DayTemplate>, notes: MutableList<String>, soft: Boolean): Boolean {
        var changed = false
        repeat(MAX_BALANCE_STEPS) {
            val volume = volumeOf(run, dayPicks.flatten())
            val hard = MuscleGroup.entries
                .filter { volume.getValue(it) - 1e-9 > run.targets.getValue(it).hardMax }
                .sortedByDescending { volume.getValue(it) - run.targets.getValue(it).hardMax }
            val over = if (soft) {
                MuscleGroup.entries
                    .filter { volume.getValue(it) - 1e-9 > run.targets.getValue(it).high }
                    .sortedByDescending { volume.getValue(it) - run.targets.getValue(it).high }
            } else {
                hard
            }
            if (over.isEmpty()) return changed
            val done = over.any { removeSet(run, dayPicks, it, volume) } ||
                // Sets are at their floor: swap or drop a light exercise, but only past the hard maximum.
                hard.any { swapExercise(run, dayPicks, templates, it, volume, notes) || removeExercise(run, dayPicks, templates, it, volume, notes) }
            if (!done) return changed
            changed = true
        }
        return changed
    }

    /** Replaces a light exercise that loads [g] with one that trains its other muscles but not [g]. */
    private fun swapExercise(
        run: Run,
        dayPicks: List<MutableList<Pick>>,
        templates: List<DayTemplate>,
        g: MuscleGroup,
        volume: Map<MuscleGroup, Double>,
        notes: MutableList<String>,
    ): Boolean {
        val used = dayPicks.flatten().map { it.info.id }.toSet()
        val options = dayPicks.flatMapIndexed { d, picks -> picks.map { d to it } }
            .filter { (_, p) -> !p.locked && !p.marker && p.role != SlotRole.COMPOUND && credit(p.info, g) == 1.0 }
            .sortedWith(compareBy<Pair<Int, Pick>>({ -it.second.role.rank }, { -it.second.templateIndex }, { -it.first }))
        for ((d, p) in options) {
            val others = groupsOf(p.info.primary) - g
            if (others.isEmpty()) continue
            val after = volume.mapValues { (o, v) -> v - p.sets * credit(p.info, o) }
            val candidate = run.eligible
                .filter { it.id !in used && credit(it, g) == 0.0 && groupsOf(it.primary).any { o -> o in others } }
                .filter { it.pattern == p.info.pattern || (it.kind == ExerciseKind.ISOLATION && p.info.kind != ExerciseKind.COMPOUND) }
                .filter { it.kind != ExerciseKind.COMPOUND || p.role == SlotRole.ACCESSORY }
                .filter { fitsCaps(run, it, after, p.sets) }
                .minWithOrNull(compareBy<ExerciseInfo>({ config.classOf(it.equipment) }, { it.fatigue }, { it.order }))
                ?: continue
            if (!MuscleGroup.entries.all { o -> after.getValue(o) + p.sets * credit(candidate, o) + 1e-9 >= run.targets.getValue(o).low || volume.getValue(o) < run.targets.getValue(o).low }) continue
            val role = if (candidate.kind == ExerciseKind.ISOLATION) SlotRole.ISOLATION else SlotRole.ACCESSORY
            val replacement = prescribe(
                run, candidate, role, false, p.templateIndex,
                listOf("Замена «${p.info.name}»: ${Fmt.group(g)} выше ${run.targets.getValue(g).hardMax} подходов в неделю, а это упражнение " +
                    "нагружает ${others.joinToString { Fmt.group(it) }} без них."),
            )
            replacement.sets = p.sets.coerceAtLeast(config.sets.min)
            val picks = dayPicks[d]
            picks[picks.indexOf(p)] = replacement
            notes += "${templates[d].title}: «${p.info.name}» → «${candidate.name}» — ${Fmt.group(g)} выше нормы."
            return true
        }
        return false
    }

    private fun removeExercise(
        run: Run,
        dayPicks: List<MutableList<Pick>>,
        templates: List<DayTemplate>,
        g: MuscleGroup,
        volume: Map<MuscleGroup, Double>,
        notes: MutableList<String>,
    ): Boolean {
        val options = dayPicks.flatMapIndexed { d, picks -> picks.map { d to it } }
            .filter { (_, p) -> !p.locked && !p.marker && p.role != SlotRole.COMPOUND && credit(p.info, g) == 1.0 }
            .filter { (d, _) -> dayPicks[d].size > run.perSession - MAX_DROPPED_PER_DAY }
            .filter { (_, p) -> keepsLows(run, p, p.sets, volume) }
            .sortedWith(
                compareBy<Pair<Int, Pick>>(
                    { -it.second.role.rank },
                    { pair -> MuscleGroup.entries.count { credit(pair.second.info, it) > 0 } },
                    { -it.second.templateIndex },
                    { -it.first },
                ),
            )
        val (d, pick) = options.firstOrNull() ?: return false
        dayPicks[d].remove(pick)
        notes += "${templates[d].title}: убрано «${pick.info.name}» — ${Fmt.group(g)} выше ${run.targets.getValue(g).hardMax} подходов в неделю."
        return true
    }

    /**
     * Only the exercise's primary groups are capped: secondary spill-over (front delts in a bench press) is
     * trimmed afterwards from isolation work, instead of blocking the main group from reaching its minimum.
     */
    private fun fitsCaps(run: Run, info: ExerciseInfo, volume: Map<MuscleGroup, Double>, addSets: Int): Boolean =
        MuscleGroup.entries.all { g ->
            credit(info, g) < 1.0 || volume.getValue(g) + addSets <= run.targets.getValue(g).hardMax + 1e-9
        }

    private fun addSet(run: Run, dayPicks: List<MutableList<Pick>>, g: MuscleGroup, volume: Map<MuscleGroup, Double>): Boolean {
        val options = dayPicks.flatMapIndexed { d, picks -> picks.map { d to it } }
            .filter { (_, p) -> !p.locked && credit(p.info, g) == 1.0 && p.sets < maxSets(p) && fitsCaps(run, p.info, volume, 1) }
            .filter { (d, p) -> withExtraSet(p) { minutes(run, dayPicks[d]) } <= run.budgetMin }
            .sortedWith(
                compareBy<Pair<Int, Pick>>({ it.second.sets - it.second.baseSets }, { -it.second.role.rank }, { minutes(run, dayPicks[it.first]) }, { it.first }),
            )
        val (_, pick) = options.firstOrNull() ?: return false
        pick.sets++
        return true
    }

    private inline fun <T> withExtraSet(p: Pick, block: () -> T): T {
        p.sets++
        try {
            return block()
        } finally {
            p.sets--
        }
    }

    private fun addExercise(
        run: Run,
        dayPicks: List<MutableList<Pick>>,
        templates: List<DayTemplate>,
        g: MuscleGroup,
        volume: Map<MuscleGroup, Double>,
        notes: MutableList<String>,
    ): Boolean {
        val dayOrder = dayPicks.indices.sortedBy { minutes(run, dayPicks[it]) }
        for (d in dayOrder) {
            val picks = dayPicks[d]
            if (picks.size >= run.perSession + EXTRA_EXERCISES) continue
            val usedToday = picks.map { it.info.id }.toSet()
            val usedAll = dayPicks.flatten().map { it.info.id }.toSet()
            val role = SlotRole.ISOLATION
            val slot = SlotTemplate(role.code, muscles = g.muscles.map { it.code })
            val s = select(run, slot, role, usedToday, usedAll, marker = false) as? Selection.Chosen ?: continue
            if (credit(s.info, g) < 1.0 || !fitsCaps(run, s.info, volume, config.sets.min)) continue
            val actualRole = when {
                s.info.pattern == MovementPattern.CORE -> SlotRole.CORE
                s.info.kind == ExerciseKind.ISOLATION -> SlotRole.ISOLATION
                else -> SlotRole.ACCESSORY
            }
            val pick = prescribe(run, s.info, actualRole, false, ADDED_INDEX, s.reasons + "Добавлено: не хватало объёма — ${Fmt.group(g)}.")
            pick.sets = config.sets.min
            picks += pick
            if (minutes(run, picks) > run.budgetMin) {
                picks.removeAt(picks.lastIndex)
                continue
            }
            notes += "${templates[d].title}: добавлено «${s.info.name}» — не хватало объёма (${Fmt.group(g)})."
            return true
        }
        return false
    }

    private fun removeSet(run: Run, dayPicks: List<MutableList<Pick>>, g: MuscleGroup, volume: Map<MuscleGroup, Double>): Boolean {
        val options = dayPicks.flatten()
            .filter { p -> !p.locked && credit(p.info, g) == 1.0 && p.sets > config.sets.min }
            // Main lifts keep their prescribed sets: trimming them would hurt strength work for a side effect.
            .filter { p -> p.role != SlotRole.COMPOUND || p.sets > p.baseSets }
            .filter { p -> keepsLows(run, p, 1, volume) }
            .sortedWith(compareBy<Pick>({ -it.role.rank }, { -it.sets }, { it.templateIndex }))
        val pick = options.firstOrNull() ?: return false
        pick.sets--
        return true
    }

    // ---- time ----

    private fun workSec(p: Pick): Int = if (p.info.measure == Measure.SECONDS) p.repMax else config.timing.workSec

    private fun minutes(run: Run, picks: List<Pick>): Double {
        val t = config.timing
        var sec = 0.0
        val seen = HashSet<Pick>()
        picks.forEach { p ->
            if (p in seen) return@forEach
            seen += p
            val q = p.partner
            if (q != null && q in picks) {
                seen += q
                val rounds = minOf(p.sets, q.sets)
                val rest = maxOf(p.restSec, q.restSec)
                sec += rounds * (workSec(p) + workSec(q) + rest)
                val big = if (p.sets >= q.sets) p else q
                sec += (big.sets - rounds) * (workSec(big) + big.restSec)
            } else {
                sec += p.sets * (workSec(p) + p.restSec)
            }
        }
        picks.forEach { p ->
            sec += t.transitionSec
            val s = run.scheme(p.info)
            sec += 60.0 * when {
                s is LoadScheme.Barbell && p.role == SlotRole.COMPOUND -> t.barbellWarmupMin
                s is LoadScheme.NoLoad || s is LoadScheme.Added -> 0.0
                else -> t.lightWarmupSetMin * run.input.accessoryWarmupSets
            }
        }
        return sec / 60.0
    }

    /** Cuts sets, lightest roles first, until day [d] fits; with [protectTargets] no group drops below its low target. */
    private fun fitTime(run: Run, dayPicks: List<MutableList<Pick>>, d: Int, title: String, notes: MutableList<String>, protectTargets: Boolean) {
        val picks = dayPicks[d]
        var cut = 0
        val dropped = mutableListOf<String>()
        while (minutes(run, picks) > run.budgetMin) {
            val volume = volumeOf(run, dayPicks.flatten())
            fun allowed(p: Pick, sets: Int) = !protectTargets || keepsLows(run, p, sets, volume)
            val light = picks
                .filter { !it.locked && it.role != SlotRole.COMPOUND && it.sets > config.sets.min && allowed(it, 1) }
                .maxWithOrNull(compareBy<Pick>({ it.role.rank }, { it.templateIndex }))
            if (light != null) {
                light.sets--
                cut++
                continue
            }
            val drop = picks
                .filter { !it.locked && !it.marker && it.role != SlotRole.COMPOUND && allowed(it, it.sets) }
                .maxWithOrNull(compareBy<Pick>({ it.role.rank }, { it.templateIndex }))
            if (drop != null) {
                picks.remove(drop)
                dropped += drop.info.name
                continue
            }
            val heavy = picks
                .filter { !it.locked && it.role == SlotRole.COMPOUND && it.sets > config.sets.min && allowed(it, 1) }
                .maxWithOrNull(compareBy<Pick>({ it.sets }, { it.templateIndex }))
                ?: break
            heavy.sets--
            cut++
        }
        val left = minutes(run, picks)
        if (left > run.budgetMin) {
            notes += "$title: по оценке ${left.roundToInt()} мин при выбранных ${run.profile.sessionMinutes} — подходы уже на минимуме."
        }
        if (cut > 0) notes += "$title: снято подходов: $cut, чтобы уложиться в ${run.profile.sessionMinutes} мин."
        if (dropped.isNotEmpty()) notes += "$title: не поместилось по времени — ${dropped.joinToString()}."
    }

    // ---- supersets ----

    private fun ordered(picks: List<Pick>): List<Pick> {
        val base = picks.sortedWith(compareBy<Pick>({ it.role.rank }, { it.templateIndex }))
        val out = mutableListOf<Pick>()
        base.forEach { p ->
            if (p in out) return@forEach
            out += p
            p.partner?.let { if (it !in out) out += it }
        }
        return out
    }

    private fun groupsOf(muscles: Set<Muscle>): Set<MuscleGroup> = muscles.mapNotNull(MuscleGroup::of).toSet()

    private fun compatible(a: Pick, b: Pick): Boolean {
        if (groupsOf(a.info.primary).intersect(groupsOf(b.info.primary)).isNotEmpty()) return false
        val stations = config.stations.toSet()
        if (a.info.equipment.intersect(b.info.equipment).any { it in stations }) return false
        val heavyLegs = { p: Pick -> p.info.fatigue >= 2 && groupsOf(p.info.primary).any { it.isLegs } }
        return !(heavyLegs(a) && heavyLegs(b))
    }

    private fun antagonists(a: Pick, b: Pick): Boolean {
        val push = setOf(MovementPattern.H_PUSH, MovementPattern.V_PUSH)
        val pull = setOf(MovementPattern.H_PULL, MovementPattern.V_PULL)
        if ((a.info.pattern in push && b.info.pattern in pull) || (a.info.pattern in pull && b.info.pattern in push)) return true
        val ga = groupsOf(a.info.primary)
        val gb = groupsOf(b.info.primary)
        return ANTAGONIST_GROUPS.any { (x, y) -> (x in ga && y in gb) || (y in ga && x in gb) }
    }

    private fun assignSupersets(run: Run, picks: MutableList<Pick>) {
        picks.forEach {
            it.superset = null
            it.partner = null
            it.antagonist = false
        }
        if (!run.intensive) return
        val pool = picks.sortedWith(compareBy<Pick>({ it.role.rank }, { it.templateIndex })).filter { it.role != SlotRole.COMPOUND }
        var label = 'A'
        pool.forEachIndexed { i, a ->
            if (a.partner != null) return@forEachIndexed
            val rest = pool.drop(i + 1).filter { it.partner == null && compatible(a, it) }
            val b = rest.firstOrNull { antagonists(a, it) } ?: rest.firstOrNull() ?: return@forEachIndexed
            a.partner = b
            b.partner = a
            a.antagonist = antagonists(a, b)
            b.antagonist = a.antagonist
            a.superset = label.toString()
            b.superset = label.toString()
            label++
        }
    }

    // ---- output ----

    private fun Pick.toSlot() = PlannedSlot(
        exerciseId = info.id, role = role, sets = sets, repMin = repMin, repMax = repMax, rirMin = rirMin, rirMax = rirMax,
        restSec = restSec, targetWeightKg = weightKg, supersetGroup = superset, locked = locked, marker = marker, note = note,
        reasons = reasons.toList(),
    )

    private fun describe(slot: SlotTemplate, role: SlotRole): String {
        val what = when {
            slot.muscles.isNotEmpty() -> slot.muscles.map { Fmt.muscle(Muscle.fromCode(it)) }.joinToString("/")
            slot.patterns.isNotEmpty() -> slot.patterns.map { Fmt.pattern(MovementPattern.fromCode(it)) }.joinToString("/")
            else -> "любое"
        }
        return "${Fmt.role(role)} — $what"
    }

    private fun classLabel(c: Int): String = when (c) {
        0 -> "штанга — первая в приоритете оборудования"
        1 -> "гири или гантели — штанги для этого слота нет или она исключена"
        2 -> "свой вес — свободные веса не подошли"
        else -> "тренажёр — свободных вариантов не осталось"
    }

    private fun styleLabel(s: Style) = if (s == Style.INTENSIVE) "Интенсивный" else "Классическая сила"

    private fun splitName(days: Int) = when (days) {
        2 -> "Полное тело A/B"
        3 -> "Полное тело A/B/C"
        else -> "Верх/низ ×2"
    }

    private fun programNotes(run: Run, input: Input): List<String> = buildList {
        val p = run.profile
        add("Сплит: ${run.days} дня → ${splitName(run.days).replaceFirstChar { it.lowercase() }}.")
        add("Стиль «${styleLabel(p.style)}», цель «${goalLabel(p.goal)}»: около ${run.perSession} упражнений на ${p.sessionMinutes} мин" +
            (if (run.intensive) ", вспомогательные — суперсетами." else ", длинный отдых в базовых."))
        val weeks = p.mesocycleWeeks.coerceIn(config.mesocycle.minWeeks, config.mesocycle.maxWeeks)
        add("Мезоцикл ${input.mesocycleIndex + 1}: $weeks нед. — ${weeks - 1} нагрузочных и разгрузочная " +
            "(объём ×${Fmt.num(config.deload.volumeFactor)}, вес ×${Fmt.num(config.deload.weightFactor)}).")
        add("Неделя программы — полный круг из ${run.days} тренировок: число тренировок в календарной неделе у тебя меняется.")
        if (run.intensive && run.days <= config.intensiveLowDaysMax) add("Интенсивный стиль при ${run.days} днях: цель — нижняя и средняя часть диапазонов объёма.")
        if (p.priorities.isNotEmpty()) {
            add("Приоритет: ${p.priorities.joinToString { Fmt.group(it) }} — +${config.priorityBonus[run.days.toString()] ?: 0} подходов в неделю.")
        }
        if (run.bannedTags.isNotEmpty()) add("Ограничения исключают: ${run.bannedTags.sorted().joinToString { Fmt.tag(it) }}.")
        val excluded = input.catalog.count { run.exclusion(it) != null }
        add("Доступно упражнений: ${run.eligible.size} из ${input.catalog.size}; исключено по инвентарю и ограничениям: $excluded.")
        if (p.longEffortDays.isNotEmpty()) {
            add("Длинные нагрузки: ${p.longEffortDays.sorted().joinToString { Fmt.weekday(it) }} — накануне ноги облегчаются " +
                "(−${config.endurance.legSetCut} подход, запас +${config.endurance.legRirBump}, тяжёлые базовые ×${Fmt.num(config.endurance.heavyLegWeightFactor)}).")
        }
        if (input.locked.isNotEmpty()) add("Зафиксировано тобой: ${input.locked.size} — оставлены как есть.")
    }

    private fun goalLabel(g: Goal) = when (g) {
        Goal.STRENGTH -> "сила"
        Goal.HYPERTROPHY -> "масса"
        Goal.BALANCE -> "баланс"
    }

    private companion object {
        const val MAX_REJECTED = 3
        const val MAX_BALANCE_STEPS = 200
        const val BALANCE_ROUNDS = 4
        const val EXTRA_EXERCISES = 1
        const val MAX_DROPPED_PER_DAY = 2
        const val LOCKED_INDEX = -1
        const val ADDED_INDEX = 100
        val ANTAGONIST_GROUPS = listOf(
            MuscleGroup.BICEPS to MuscleGroup.TRICEPS,
            MuscleGroup.QUADS to MuscleGroup.HAMSTRINGS,
            MuscleGroup.CHEST to MuscleGroup.BACK,
        )
    }
}
