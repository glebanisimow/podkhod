package app.podkhod.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.podkhod.R
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.stats.MesocycleVolume
import app.podkhod.data.stats.ProgressRepository
import app.podkhod.domain.plan.Fmt
import app.podkhod.domain.stats.ExerciseProgress
import app.podkhod.domain.stats.ExerciseSummary
import app.podkhod.domain.stats.ProgressMetric
import app.podkhod.domain.stats.VolumeStatus
import app.podkhod.ui.components.BrandScene
import app.podkhod.ui.components.PawTrail
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import app.podkhod.ui.format.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class ProgressListState(
    val exercises: List<Pair<ExerciseSummary, ExerciseEntity?>>,
    val mesocycle: MesocycleVolume?,
)

class ProgressViewModel(progress: ProgressRepository, catalog: CatalogRepository) : ViewModel() {
    val state: StateFlow<ProgressListState?> =
        combine(progress.exercises, progress.mesocycle, catalog.exercisesById) { list, meso, byId ->
            ProgressListState(list.map { it to byId[it.exerciseId] }, meso)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

data class ExerciseProgressState(val progress: ExerciseProgress?, val exercise: ExerciseEntity?)

class ExerciseProgressViewModel(exerciseId: String, progress: ProgressRepository, catalog: CatalogRepository) : ViewModel() {
    val state: StateFlow<ExerciseProgressState?> =
        combine(progress.exercise(exerciseId), catalog.exercisesById) { p, byId -> ExerciseProgressState(p, byId[exerciseId]) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ProgressScreen(onBack: () -> Unit, onOpenExercise: (String) -> Unit, viewModel: ProgressViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("screen_progress")) {
        ScreenHeader(stringResource(R.string.progress_title), onBack = onBack)
        val s = state ?: return@Column
        if (s.exercises.isEmpty() && s.mesocycle == null) {
            Box {
                PawTrail(Modifier.matchParentSize())
                BrandScene(Modifier.padding(horizontal = 32.dp, vertical = 16.dp))
            }
            Text(stringResource(R.string.progress_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            s.mesocycle?.let { meso ->
                item(key = "volume") { VolumeCard(meso, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (s.exercises.isNotEmpty()) {
                item(key = "exercises_title") {
                    Text(
                        stringResource(R.string.progress_exercises),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(s.exercises, key = { it.first.exerciseId }) { (summary, exercise) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenExercise(summary.exerciseId) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("progress_ex_${summary.exerciseId}"),
                ) {
                    Text(exercise?.nameRu ?: summary.exerciseId, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.progress_exercise_row, sessionsText(summary.sessions), Format.date(summary.lastAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun sessionsText(n: Int): String = app.podkhod.ui.format.ruPlural(R.plurals.sessions_count, n)

/** Weekly volume of the mesocycle; arrows page through the weeks, the current one is shown first. */
@Composable
private fun VolumeCard(meso: MesocycleVolume, modifier: Modifier = Modifier) {
    val current = meso.weekIndex.coerceIn(0, meso.totalWeeks - 1)
    // A week that has not started yet says nothing: open the latest week with sessions instead.
    val initial = if ((meso.weeks.firstOrNull { it.weekIndex == current }?.sessions ?: 0) > 0) {
        current
    } else {
        meso.weeks.filter { it.sessions > 0 && it.weekIndex <= current }.maxOfOrNull { it.weekIndex } ?: current
    }
    var week by rememberSaveable(meso.totalWeeks) { mutableStateOf(initial) }
    val data = meso.weeks.firstOrNull { it.weekIndex == week }
    SectionCard(modifier.testTag("progress_volume")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val deload = week == meso.totalWeeks - 1
                Text(
                    stringResource(R.string.progress_volume_title, week + 1, meso.totalWeeks) +
                        if (deload) " · " + stringResource(R.string.progress_volume_deload) else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.progress_volume_sessions, data?.sessions ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { week-- }, enabled = week > 0, modifier = Modifier.testTag("volume_prev")) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.progress_week_prev))
            }
            IconButton(onClick = { week++ }, enabled = week < current, modifier = Modifier.testTag("volume_next")) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.progress_week_next), modifier = Modifier.rotate(180f))
            }
        }
        Text(stringResource(R.string.progress_volume_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (data == null || data.sessions == 0) {
            Text(stringResource(R.string.progress_volume_none), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        data.groups.filter { it.targetLow != null || it.sets > 0 }.forEach { g ->
            val status = g.status
            // Status is never color-only: the mark in front says it too.
            val mark = when (status) {
                VolumeStatus.BELOW -> "↓ "
                VolumeStatus.ABOVE -> "↑ "
                VolumeStatus.WITHIN -> "✓ "
                VolumeStatus.NO_TARGET -> "· "
            }
            val target = if (g.targetLow != null && g.targetHigh != null) Fmt.range(g.targetLow!!, g.targetHigh!!) else "—"
            Text(
                mark + stringResource(R.string.progress_volume_row, Fmt.group(g.group), Fmt.num(g.sets), target),
                style = MaterialTheme.typography.bodyMedium,
                color = if (status == VolumeStatus.BELOW && week < current) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("volume_${g.group.code}"),
            )
        }
    }
}

@Composable
fun ExerciseProgressScreen(
    exerciseId: String,
    onBack: () -> Unit,
    viewModel: ExerciseProgressViewModel = koinViewModel(key = "progress_$exerciseId") { parametersOf(exerciseId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_exercise_progress")) {
        val s = state
        ScreenHeader(s?.exercise?.nameRu ?: stringResource(R.string.progress_title), onBack = onBack)
        val p = s?.progress ?: return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard(Modifier.testTag("progress_chart_card")) {
                Text(stringResource(metricTitle(p.metric)), style = MaterialTheme.typography.titleMedium)
                val values = p.points.mapNotNull { pt -> pt.value(p.metric)?.let { pt.startedAt to it } }
                if (values.size < 2) {
                    Text(stringResource(R.string.progress_chart_one), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LineChart(values, p.metric)
                    p.change?.let { c ->
                        val sign = if (c >= 0) "+" else "−"
                        Text(
                            stringResource(R.string.progress_change, sign + metricValue(abs(c), p.metric)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SectionCard {
                p.bestE1rmKg?.let { Metric(stringResource(R.string.progress_best_e1rm), e1rmText(it)) }
                p.bestWeightKg?.let { Metric(stringResource(R.string.progress_best_weight), Format.weight(it) + " кг") }
                p.bestReps?.let { Metric(stringResource(R.string.progress_best_reps), it.toString()) }
                p.bestSeconds?.let { Metric(stringResource(R.string.progress_best_seconds), "$it с") }
                Metric(stringResource(R.string.progress_sessions), p.points.size.toString())
            }
            // The table is the accessible view of the chart and shows what the chart leaves out.
            SectionCard(Modifier.testTag("progress_table")) {
                Text(stringResource(R.string.progress_table), style = MaterialTheme.typography.titleMedium)
                p.points.asReversed().forEach { pt ->
                    val parts = buildList {
                        pt.value(p.metric)?.let { add(metricValue(it, p.metric)) }
                        pt.topWeightKg?.let { add("макс. " + Format.weight(it) + " кг") }
                        add(app.podkhod.ui.format.ruPlural(R.plurals.sets_count, pt.workSets))
                    }
                    Text(
                        stringResource(R.string.progress_table_row, Format.date(pt.startedAt), parts.joinToString(" · ")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun metricTitle(m: ProgressMetric): Int = when (m) {
    ProgressMetric.E1RM -> R.string.progress_metric_e1rm
    ProgressMetric.REPS -> R.string.progress_metric_reps
    ProgressMetric.SECONDS -> R.string.progress_metric_seconds
}

/** An estimate to 0,5 kg: two decimals would suggest a precision the formula does not have. */
private fun e1rmText(v: Double): String = Format.weight(Math.round(v * 2) / 2.0) + " кг"

private fun metricValue(v: Double, m: ProgressMetric): String = when (m) {
    ProgressMetric.E1RM -> e1rmText(v)
    ProgressMetric.REPS -> Fmt.num(v) + " повт."
    ProgressMetric.SECONDS -> Fmt.num(v) + " с"
}

/**
 * Single-series line over sessions (x is the session order, not calendar time, so a two-week break does
 * not squash the rest). Thin 2dp line, 8dp markers, recessive grid with three value labels; a tap selects
 * the nearest session and shows its value and date.
 */
@Composable
private fun LineChart(values: List<Pair<Long, Double>>, metric: ProgressMetric) {
    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall
    val valueStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    var selected by remember(values) { mutableStateOf(values.lastIndex) }

    val minV = values.minOf { it.second }
    val maxV = values.maxOf { it.second }
    val (lo, hi) = niceBounds(minV, maxV)
    val description = stringResource(
        R.string.progress_chart_desc,
        stringResource(metricTitle(metric)),
        metricValue(values.first().second, metric),
        metricValue(values.last().second, metric),
    )

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .testTag("progress_chart")
            .pointerInput(values) {
                detectTapGestures { offset ->
                    val left = AXIS_WIDTH.toPx()
                    val w = size.width - left - END_PAD.toPx()
                    val step = if (values.size > 1) w / (values.size - 1) else 0f
                    selected = (((offset.x - left) / step) + 0.5f).toInt().coerceIn(0, values.lastIndex)
                }
            },
    ) {
        val left = AXIS_WIDTH.toPx()
        val top = TOP_PAD.toPx()
        val bottom = size.height - BOTTOM_PAD.toPx()
        val w = size.width - left - END_PAD.toPx()
        val step = w / (values.size - 1)
        fun x(i: Int) = left + i * step
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        // Grid: three recessive lines with value labels on the left.
        listOf(lo, (lo + hi) / 2, hi).forEach { g ->
            val gy = y(g)
            drawLine(grid, Offset(left, gy), Offset(size.width - END_PAD.toPx(), gy), strokeWidth = 1.dp.toPx())
            drawLabel(measurer, Fmt.num(g), Offset(0f, gy), labelStyle.copy(color = ink), centerY = true)
        }
        // Date labels at the ends only: every session's date would collide.
        drawLabel(measurer, Format.shortDate(values.first().first), Offset(left, bottom + 4.dp.toPx()), labelStyle.copy(color = ink))
        drawLabel(measurer, Format.shortDate(values.last().first), Offset(x(values.lastIndex), bottom + 4.dp.toPx()), labelStyle.copy(color = ink), alignEnd = true)

        val path = Path().apply {
            values.forEachIndexed { i, (_, v) -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Selection crosshair under the markers.
        val sx = x(selected)
        drawLine(
            ink, Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        values.forEachIndexed { i, (_, v) ->
            val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
            // A surface ring keeps markers readable where the line passes through them.
            drawCircle(surface, radius = r + 2.dp.toPx(), center = Offset(x(i), y(v)))
            drawCircle(line, radius = r, center = Offset(x(i), y(v)))
        }
        val (at, v) = values[selected]
        drawLabel(
            measurer,
            metricValue(v, metric) + " · " + Format.shortDate(at),
            Offset(sx, 0f),
            valueStyle.copy(color = strong),
            alignEnd = selected > values.lastIndex / 2,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    style: androidx.compose.ui.text.TextStyle,
    centerY: Boolean = false,
    alignEnd: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val x = (if (alignEnd) at.x - layout.size.width else at.x).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    val y = if (centerY) at.y - layout.size.height / 2f else at.y
    drawText(layout, topLeft = Offset(x, y))
}

/** Axis bounds on round numbers with some headroom, never a flat zero-height range. */
internal fun niceBounds(min: Double, max: Double): Pair<Double, Double> {
    val span = (max - min).takeIf { it > 1e-9 } ?: maxOf(abs(max) * 0.1, 1.0)
    val unit = when {
        span <= 5 -> 1.0
        span <= 20 -> 2.5
        span <= 50 -> 5.0
        span <= 200 -> 10.0
        else -> 50.0
    }
    val lo = floor((min - span * 0.1) / unit) * unit
    val hi = ceil((max + span * 0.1) / unit) * unit
    return maxOf(lo, 0.0) to hi
}

private val AXIS_WIDTH = 40.dp
private val END_PAD = 8.dp
private val TOP_PAD = 28.dp
private val BOTTOM_PAD = 22.dp
