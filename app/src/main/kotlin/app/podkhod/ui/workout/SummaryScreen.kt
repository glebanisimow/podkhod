package app.podkhod.ui.workout

import androidx.compose.foundation.layout.Arrangement
import app.podkhod.ui.theme.BrandShapes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.podkhod.R
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.workout.SessionDetail
import app.podkhod.data.workout.SessionResult
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.workout.RecordKind
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import app.podkhod.ui.format.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class SummaryState(val detail: SessionDetail, val result: SessionResult)

class SummaryViewModel(private val sessionId: Long, private val workouts: WorkoutRepository) : ViewModel() {
    val state: StateFlow<SummaryState?> = workouts.observeDetail(sessionId)
        .map { d -> d?.let { SummaryState(it, workouts.result(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(feeling: Int?, note: String, onDone: () -> Unit) {
        viewModelScope.launch {
            workouts.setFeedback(sessionId, feeling, note)
            onDone()
        }
    }
}

@Composable
fun SummaryScreen(sessionId: Long, onDone: () -> Unit, viewModel: SummaryViewModel = koinViewModel(key = "summary_$sessionId") { parametersOf(sessionId) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var feeling by rememberSaveable { mutableStateOf<Int?>(null) }
    var note by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_summary")) {
        ScreenHeader(stringResource(R.string.summary_title))
        val s = state ?: return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionTotals(s.detail, s.result)
            ProgressCard(s.detail)
            RecordsCard(s.detail, s.result)

            SectionCard {
                Text(stringResource(R.string.summary_feeling), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3).forEach { v ->
                        FilterChip(
                            selected = feeling == v,
                            onClick = { feeling = if (feeling == v) null else v },
                            label = { Text(stringResource(Format.feeling(v))) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("feeling_$v"),
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text(stringResource(R.string.summary_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            Button(
                shape = BrandShapes.Button,
                onClick = { viewModel.save(feeling, note, onDone) },
                modifier = Modifier.fillMaxWidth().height(64.dp).testTag("summary_done"),
            ) { Text(stringResource(R.string.summary_done), style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun SessionTotals(detail: SessionDetail, result: SessionResult) {
    SectionCard(Modifier.testTag("summary_totals")) {
        Text(detail.session.title + " · " + Format.date(detail.session.startedAt), style = MaterialTheme.typography.titleMedium)
        Metric(stringResource(R.string.summary_duration), Format.duration(result.summary.durationSec))
        Metric(stringResource(R.string.summary_sets), result.summary.workSets.toString())
        Metric(stringResource(R.string.summary_volume), Format.volume(result.summary.volumeKg) + " " + stringResource(R.string.unit_kg))
        result.previousVolumeKg?.let { prev ->
            Text(
                stringResource(R.string.summary_vs_previous, Format.signedVolume(result.summary.volumeKg - prev)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What changes next time: weight up, hold or down, per exercise. */
@Composable
fun ProgressCard(detail: SessionDetail) {
    val lines = PlanRepository.decodeProgress(detail.session.progressJson)
    if (lines.isEmpty()) return
    SectionCard(Modifier.testTag("summary_progress")) {
        Text(stringResource(R.string.summary_progress), style = MaterialTheme.typography.titleMedium)
        lines.forEach { l ->
            val mark = when (l.change) {
                "up" -> "↑"
                "down" -> "↓"
                else -> "="
            }
            Text("$mark ${l.name}: ${l.message}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun RecordsCard(detail: SessionDetail, result: SessionResult) {
    val names = detail.slots.associate { it.exercise.id to it.exercise.nameRu }
    SectionCard(Modifier.testTag("summary_records")) {
        Text(stringResource(R.string.summary_records), style = MaterialTheme.typography.titleMedium)
        if (result.records.isEmpty()) {
            Text(
                stringResource(if (result.previousVolumeKg == null) R.string.summary_first_time else R.string.summary_no_records),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.records.forEach { r ->
            val name = names[r.exerciseId] ?: r.exerciseId
            val prev = r.previous
            val text = when (r.kind) {
                RecordKind.WEIGHT -> stringResource(R.string.record_weight, name, Format.weight(r.value), prev?.let(Format::weight) ?: "—")
                RecordKind.ESTIMATED_1RM -> stringResource(R.string.record_1rm, name, Format.weight(r.value), prev?.let(Format::weight) ?: "—")
                RecordKind.BODYWEIGHT_REPS -> stringResource(R.string.record_reps, name, r.value.toInt().toString(), prev?.toInt()?.toString() ?: "—")
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
