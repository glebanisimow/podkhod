package app.podkhod.domain.weights

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.LoadType
import kotlin.math.floor
import kotlin.math.roundToLong

/** [count] kettlebells of [weightKg], or [count] pairs of dumbbells. */
data class Implement(val weightKg: Double, val count: Int)

data class Inventory(
    val barKg: Double = 20.0,
    val plates: List<PlatePair> = DEFAULT_PLATES,
    val kettlebells: List<Implement> = DEFAULT_KETTLEBELLS,
    val dumbbells: List<Implement> = emptyList(),
    /** Gym checklist. "dumbbells", "belt_chain", "bands" and "hyperextension" are opt-in. */
    val equipment: Set<String> = DEFAULT_EQUIPMENT,
) {
    /** Equipment the generator may use: a ticked kettlebell box with no kettlebells listed is not usable. */
    val available: Set<String>
        get() = equipment.toMutableSet().apply {
            if (kettlebells.none { it.count > 0 }) remove(KETTLEBELL)
            if (dumbbells.none { it.count > 0 }) remove(DUMBBELLS)
        }

    companion object {
        const val BARBELL = "barbell"
        const val KETTLEBELL = "kettlebell"
        const val DUMBBELLS = "dumbbells"
        const val BELT = "belt_chain"

        val DEFAULT_EQUIPMENT = setOf(
            "barbell", "rack", "bench", "adjustable_bench", "kettlebell", "pullup_bar", "dip_bars", "rings",
            "lat_pulldown", "cable_row", "chest_press_machine", "leg_press", "leg_extension", "leg_curl", "cable_station",
        )
        val OPTIONAL_EQUIPMENT = setOf("dumbbells", "belt_chain", "bands", "hyperextension")

        val DEFAULT_PLATES = listOf(
            PlatePair(25.0, 2), PlatePair(20.0, 4), PlatePair(15.0, 2), PlatePair(10.0, 4),
            PlatePair(5.0, 4), PlatePair(2.5, 4), PlatePair(1.25, 2),
        )
        val DEFAULT_KETTLEBELLS = listOf(8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0).map { Implement(it, 2) }
    }
}

/** How the weight of an exercise is built from the user's inventory. */
sealed interface LoadScheme {
    /** Bar plus symmetric plates. */
    data class Barbell(val table: PlateTable) : LoadScheme

    /** Fixed kettlebells or dumbbells, weight per implement, ascending. */
    data class Implements(val weightsKg: List<Double>) : LoadScheme

    /** Machine or cable stack with an even step. */
    data class Stack(val stepKg: Double) : LoadScheme

    /** Body weight plus optional plates; [optionsKg] always starts at 0. */
    data class Added(val optionsKg: List<Double>) : LoadScheme

    /** Bands, planks: no weight at all. */
    data object NoLoad : LoadScheme
}

object Loads {
    /** Pull-up bars, dip bars and rings carry extra weight only on a dip belt. */
    private val HANGING = setOf("pullup_bar", "dip_bars", "rings")

    fun scheme(info: ExerciseInfo, inventory: Inventory): LoadScheme = when {
        info.load == LoadType.NONE -> LoadScheme.NoLoad
        info.load == LoadType.BODYWEIGHT_PLUS -> {
            val canAdd = inventory.plates.isNotEmpty() &&
                (info.equipment.none { it in HANGING } || Inventory.BELT in inventory.available)
            LoadScheme.Added(if (canAdd) PlateTable.singles(inventory.plates).totals else listOf(0.0))
        }
        Inventory.BARBELL in info.equipment -> LoadScheme.Barbell(PlateTable.barbell(inventory.barKg, inventory.plates))
        Inventory.KETTLEBELL in info.equipment -> LoadScheme.Implements(
            inventory.kettlebells.filter { it.count >= info.implements }.map { it.weightKg }.distinct().sorted(),
        )
        Inventory.DUMBBELLS in info.equipment -> LoadScheme.Implements(
            inventory.dumbbells.filter { it.count >= 1 }.map { it.weightKg }.distinct().sorted(),
        )
        // Machines and cable stacks: catalog step is the stack increment.
        else -> LoadScheme.Stack(info.stepKg.takeIf { it > 0 } ?: DEFAULT_STACK_STEP)
    }

    /**
     * The heaviest buildable weight not above [kg]; below the lightest option, the lightest option
     * (an empty bar is the floor for barbell lifts). Null when nothing can be built or there is no load.
     */
    fun roundDown(scheme: LoadScheme, kg: Double): Double? = when (scheme) {
        is LoadScheme.Barbell -> scheme.table.totals.lastOrNull { it <= kg + EPS } ?: scheme.table.totals.firstOrNull()
        is LoadScheme.Implements -> scheme.weightsKg.lastOrNull { it <= kg + EPS } ?: scheme.weightsKg.firstOrNull()
        is LoadScheme.Stack -> round2(maxOf(scheme.stepKg, floor((kg + EPS) / scheme.stepKg) * scheme.stepKg))
        is LoadScheme.Added -> scheme.optionsKg.lastOrNull { it <= kg + EPS } ?: 0.0
        LoadScheme.NoLoad -> null
    }

    /** The nearest buildable weight to [kg], preferring the lighter one on a tie. */
    fun nearest(scheme: LoadScheme, kg: Double): Double? {
        val options = when (scheme) {
            is LoadScheme.Barbell -> scheme.table.totals
            is LoadScheme.Implements -> scheme.weightsKg
            is LoadScheme.Added -> scheme.optionsKg
            is LoadScheme.Stack -> return round2(maxOf(scheme.stepKg, Math.round(kg / scheme.stepKg) * scheme.stepKg))
            LoadScheme.NoLoad -> return null
        }
        return options.minWithOrNull(compareBy<Double>({ kotlin.math.abs(it - kg) }, { it }))
    }

    /** The lightest buildable weight at least [minStepKg] above [kg], or null if the set has nothing heavier. */
    fun nextUp(scheme: LoadScheme, kg: Double, minStepKg: Double): Double? {
        val floorKg = kg + minStepKg - EPS
        return when (scheme) {
            is LoadScheme.Barbell -> scheme.table.totals.firstOrNull { it >= floorKg }
            is LoadScheme.Implements -> scheme.weightsKg.firstOrNull { it > kg + EPS }
            is LoadScheme.Stack -> {
                val step = scheme.stepKg
                round2(kotlin.math.ceil(floorKg / step) * step).takeIf { it > kg + EPS } ?: round2(kg + step)
            }
            is LoadScheme.Added -> scheme.optionsKg.firstOrNull { it >= floorKg }
            LoadScheme.NoLoad -> null
        }
    }

    /** Smallest jump the inventory allows near [kg]; for a barbell it is twice the lightest plate. */
    fun minIncrement(scheme: LoadScheme, kg: Double): Double? = when (scheme) {
        is LoadScheme.Barbell -> scheme.table.totals.firstOrNull { it > kg + EPS }?.let { round2(it - kg) }
        is LoadScheme.Implements -> scheme.weightsKg.firstOrNull { it > kg + EPS }?.let { round2(it - kg) }
        is LoadScheme.Stack -> scheme.stepKg
        is LoadScheme.Added -> scheme.optionsKg.firstOrNull { it > kg + EPS }?.let { round2(it - kg) }
        LoadScheme.NoLoad -> null
    }

    private const val EPS = 1e-6
    private const val DEFAULT_STACK_STEP = 2.5
    private fun round2(kg: Double): Double = (kg * 100.0).roundToLong() / 100.0
}
