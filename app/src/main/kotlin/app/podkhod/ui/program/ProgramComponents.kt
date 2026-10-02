package app.podkhod.ui.program

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podkhod.R
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.db.PlannedExerciseEntity
import app.podkhod.data.db.ProgramEntity
import app.podkhod.domain.model.LoadType
import app.podkhod.ui.format.Format

/** "3 × 6–8 @ 80 кг, запас 1–2" in the user's terms; bodyweight lifts show the added weight. */
@Composable
fun schemeText(
    sets: Int,
    repMin: Int,
    repMax: Int,
    rirMin: Int?,
    rirMax: Int?,
    weightKg: Double?,
    exercise: ExerciseEntity?,
    withRir: Boolean = true,
): String {
    val seconds = exercise?.measure == "seconds"
    val reps = Format.range(repMin, repMax) + if (seconds) " с" else ""
    val load = exercise?.load?.let { runCatching { LoadType.fromCode(it) }.getOrNull() }
    val base = when {
        load == LoadType.NONE -> stringResource(R.string.scheme_no_weight, sets, reps)
        load == LoadType.BODYWEIGHT_PLUS -> if (weightKg != null && weightKg > 0) {
            stringResource(R.string.scheme_added, sets, reps, Format.weight(weightKg))
        } else {
            stringResource(R.string.scheme_no_weight, sets, reps)
        }
        weightKg != null && weightKg > 0 -> stringResource(R.string.scheme_weight, sets, reps, Format.weight(weightKg))
        else -> stringResource(R.string.scheme_no_weight, sets, reps) + ", " + stringResource(R.string.scheme_weight_unknown)
    }
    return if (withRir && rirMin != null && rirMax != null) base + ", " + stringResource(R.string.scheme_rir, Format.range(rirMin, rirMax)) else base
}

@Composable
fun PlannedRow(p: PlannedExerciseEntity, exercise: ExerciseEntity?, compact: Boolean = false, modifier: Modifier = Modifier) {
    val scheme = schemeText(p.sets, p.repMin, p.repMax, p.rirMin, p.rirMax, p.targetWeightKg, exercise, withRir = !compact)
    val details = buildList {
        add(scheme)
        if (!compact) add(stringResource(R.string.plan_rest, Format.rest(p.restSec)))
    }.joinToString(" · ")
    Row(modifier.fillMaxWidth().heightIn(min = if (compact) 0.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = p.supersetGroup ?: "",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                exercise?.nameRu ?: p.exerciseId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact) {
                val flags = listOfNotNull(
                    stringResource(R.string.slot_locked).takeIf { p.locked },
                    stringResource(R.string.slot_marker).takeIf { p.marker },
                    p.note,
                )
                if (flags.isNotEmpty()) Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/** Week boxes of the mesocycle: done, current, and the deload week marked. */
@Composable
fun WeekStrip(program: ProgramEntity, modifier: Modifier = Modifier) {
    val weeks = program.weeks
    val current = program.weekIndex
    Column(modifier.testTag("week_strip"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                current >= weeks -> stringResource(R.string.program_week_done)
                current == weeks - 1 -> stringResource(R.string.program_week_deload, current + 1, weeks)
                else -> stringResource(R.string.program_week, current + 1, weeks)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0 until weeks).forEach { w ->
                val color = when {
                    w < current -> MaterialTheme.colorScheme.primary
                    w == current -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 28.dp)
                        .background(color, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (w == weeks - 1) stringResource(R.string.program_deload_short) else "${w + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (w <= current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
