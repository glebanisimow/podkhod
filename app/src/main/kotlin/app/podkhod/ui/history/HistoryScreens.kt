package app.podkhod.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.podkhod.R
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.HistoryRow
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.model.SetKind
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import app.podkhod.ui.format.Format
import app.podkhod.ui.format.ruPlural
import app.podkhod.ui.workout.RecordsCard
import app.podkhod.ui.workout.SessionTotals
import app.podkhod.ui.workout.SummaryState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class HistoryViewModel(workouts: WorkoutRepository, catalog: CatalogRepository) : ViewModel() {
    val rows: StateFlow<List<HistoryRow>?> =
        workouts.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Exercises most often marked busy, with names: the gym's usual bottlenecks. */
    val busiest: StateFlow<List<Pair<String, Int>>> =
        combine(workouts.busiest, catalog.exercisesById) { rows, byId ->
            rows.map { (byId[it.exerciseId]?.nameRu ?: it.exerciseId) to it.count }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class HistoryDetailViewModel(private val sessionId: Long, private val workouts: WorkoutRepository) : ViewModel() {
    val state: StateFlow<SummaryState?> = workouts.observeDetail(sessionId)
        .map { d -> d?.let { SummaryState(it, workouts.result(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            workouts.discard(sessionId)
            onDone()
        }
    }
}

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, viewModel: HistoryViewModel = koinViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val busiest by viewModel.busiest.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("screen_history")) {
        ScreenHeader(stringResource(R.string.history_title))
        val list = rows ?: return@Column
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (busiest.isNotEmpty()) {
                item(key = "busiest") {
                    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("history_busiest")) {
                        Text(stringResource(R.string.history_busiest), style = MaterialTheme.typography.titleMedium)
                        busiest.forEach { (name, count) ->
                            Text(
                                stringResource(R.string.history_busiest_row, name, count),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(list, key = { it.id }) { row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(row.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .testTag("history_row_${row.id}"),
                ) {
                    Text(Format.date(row.startedAt) + " · " + row.title, style = MaterialTheme.typography.titleMedium)
                    val duration = row.finishedAt?.let { Format.duration((it - row.startedAt) / 1000) } ?: "—"
                    Text(
                        stringResource(
                            R.string.history_row,
                            duration,
                            ruPlural(R.plurals.sets_count, row.workSets),
                            Format.volume(row.volumeKg),
                        ),
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
fun HistoryDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    viewModel: HistoryDetailViewModel = koinViewModel(key = "history_$sessionId") { parametersOf(sessionId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_history_detail")) {
        ScreenHeader(stringResource(R.string.history_title), onBack = onBack)
        val s = state ?: return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionTotals(s.detail, s.result)
            s.detail.session.feeling?.let {
                Text(stringResource(R.string.history_feeling, stringResource(Format.feeling(it))), style = MaterialTheme.typography.bodyLarge)
            }
            s.detail.session.note?.let { Text(stringResource(R.string.history_note, it), style = MaterialTheme.typography.bodyLarge) }
            RecordsCard(s.detail, s.result)
            s.detail.slots.filter { it.sets.isNotEmpty() }.forEach { slot ->
                SectionCard {
                    Text(slot.exercise.nameRu, style = MaterialTheme.typography.titleMedium)
                    var n = 0
                    slot.sets.forEach { set ->
                        val kind = SetKind.fromCode(set.kind)
                        if (kind.countsAsWork) n++
                        Row {
                            Text(if (kind.countsAsWork) "$n" else "Р", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                            Text(Format.setResult(set.weightKg, set.reps, set.durationSec), modifier = Modifier.weight(1f))
                            if (kind != SetKind.WORK) {
                                Text(stringResource(Format.setKind(kind)), color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.history_delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.workout_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
