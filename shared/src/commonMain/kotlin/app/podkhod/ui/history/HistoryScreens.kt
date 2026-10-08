package app.podkhod.ui.history

import app.podkhod.ui.format.RuPlural
import androidx.compose.foundation.clickable
import app.podkhod.ui.theme.BrandShapes
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.podkhod.resources.*
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.HistoryRow
import app.podkhod.data.db.SetLogEntity
import app.podkhod.data.workout.SlotDetail
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.ui.workout.EditSetDialog
import app.podkhod.ui.workout.ProgressCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.model.SetKind
import app.podkhod.ui.components.BrandScene
import app.podkhod.ui.components.PawTrail
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
            workouts.deleteFinished(sessionId)
            onDone()
        }
    }

    fun updateSet(set: SetLogEntity) {
        viewModelScope.launch { workouts.updateFinishedSet(set) }
    }

    fun deleteSet(set: SetLogEntity) {
        viewModelScope.launch { workouts.deleteFinishedSet(set) }
    }

    fun addSet(set: SetLogEntity) {
        viewModelScope.launch {
            workouts.addFinishedSet(set.sessionExerciseId, SetKind.fromCode(set.kind), set.weightKg, set.reps, set.durationSec)
        }
    }

    fun saveFeedback(feeling: Int?, note: String) {
        viewModelScope.launch { workouts.setFeedback(sessionId, feeling, note) }
    }
}

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onOpenProgress: () -> Unit, viewModel: HistoryViewModel = koinViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val busiest by viewModel.busiest.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("screen_history")) {
        ScreenHeader(stringResource(Res.string.history_title))
        val list = rows ?: return@Column
        if (list.isEmpty()) {
            Box {
                PawTrail(Modifier.matchParentSize())
                BrandScene(Modifier.padding(horizontal = 32.dp, vertical = 16.dp))
            }
            Text(
                stringResource(Res.string.history_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "progress") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProgress)
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("history_progress"),
                ) {
                    Text(stringResource(Res.string.progress_open) + " →", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(Res.string.progress_open_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            if (busiest.isNotEmpty()) {
                item(key = "busiest") {
                    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("history_busiest")) {
                        Text(stringResource(Res.string.history_busiest), style = MaterialTheme.typography.titleMedium)
                        busiest.forEach { (name, count) ->
                            Text(
                                stringResource(Res.string.history_busiest_row, name, count),
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
                            Res.string.history_row,
                            duration,
                            ruPlural(RuPlural.SETS, row.workSets),
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
    onOpenProgress: (String) -> Unit,
    viewModel: HistoryDetailViewModel = koinViewModel(key = "history_$sessionId") { parametersOf(sessionId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<SetLogEntity, SlotDetail>?>(null) }
    var adding by remember { mutableStateOf<Pair<SetLogEntity, SlotDetail>?>(null) }
    var feeling by rememberSaveable { mutableStateOf<Int?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_history_detail")) {
        ScreenHeader(stringResource(Res.string.history_title), onBack = onBack)
        val s = state ?: return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionTotals(s.detail, s.result)
            OutlinedButton(
                shape = BrandShapes.Button,
                onClick = {
                    if (editMode) {
                        viewModel.saveFeedback(feeling, note)
                    } else {
                        feeling = s.detail.session.feeling
                        note = s.detail.session.note.orEmpty()
                    }
                    editMode = !editMode
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("history_edit"),
            ) { Text(stringResource(if (editMode) Res.string.history_edit_done else Res.string.history_edit)) }
            if (editMode) {
                Text(stringResource(Res.string.history_edit_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionCard {
                    Text(stringResource(Res.string.history_feedback_edit), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3).forEach { v ->
                            FilterChip(
                                selected = feeling == v,
                                onClick = { feeling = if (feeling == v) null else v },
                                label = { Text(stringResource(Format.feeling(v))) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(500) },
                        label = { Text(stringResource(Res.string.summary_note)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            } else {
                s.detail.session.feeling?.let {
                    Text(stringResource(Res.string.history_feeling, stringResource(Format.feeling(it))), style = MaterialTheme.typography.bodyLarge)
                }
                s.detail.session.note?.let { Text(stringResource(Res.string.history_note, it), style = MaterialTheme.typography.bodyLarge) }
                ProgressCard(s.detail)
                RecordsCard(s.detail, s.result)
            }
            s.detail.slots.filter { editMode || it.sets.isNotEmpty() }.forEach { slot ->
                SectionCard(Modifier.testTag("history_slot_${slot.slot.id}")) {
                    Text(
                        slot.exercise.nameRu,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable(enabled = !editMode) { onOpenProgress(slot.exercise.id) },
                    )
                    var n = 0
                    slot.sets.forEach { set ->
                        val kind = SetKind.fromCode(set.kind)
                        if (kind.countsAsWork) n++
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (editMode) Modifier.clickable { editing = set to slot }.heightIn(min = 48.dp) else Modifier)
                                .testTag("history_set_${set.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (kind.countsAsWork) "$n" else "Р", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                            Text(Format.setResult(set.weightKg, set.reps, set.durationSec), modifier = Modifier.weight(1f))
                            if (kind != SetKind.WORK) {
                                Text(stringResource(Format.setKind(kind)), color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                    if (editMode) {
                        TextButton(
                            onClick = {
                                val last = slot.sets.lastOrNull { SetKind.fromCode(it.kind).countsAsWork } ?: slot.sets.lastOrNull()
                                adding = SetLogEntity(
                                    sessionExerciseId = slot.slot.id,
                                    position = 0,
                                    kind = SetKind.WORK.code,
                                    weightKg = last?.weightKg ?: slot.slot.targetWeightKg,
                                    reps = last?.reps ?: slot.slot.repMax,
                                    durationSec = last?.durationSec,
                                    rir = null,
                                    completedAt = 0,
                                ) to slot
                            },
                            modifier = Modifier.testTag("history_add_${slot.slot.id}"),
                        ) { Text(stringResource(Res.string.history_add_set)) }
                    } else {
                        TextButton(onClick = { onOpenProgress(slot.exercise.id) }) { Text(stringResource(Res.string.progress_open_exercise) + " →") }
                    }
                }
            }
            OutlinedButton(shape = BrandShapes.Button, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(Res.string.history_delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    editing?.let { (set, slot) ->
        EditSetDialog(
            set = set,
            index = slot.sets.indexOfFirst { it.id == set.id } + 1,
            measure = Measure.fromCode(slot.exercise.measure),
            load = LoadType.fromCode(slot.exercise.load),
            onDismiss = { editing = null },
            onSave = {
                viewModel.updateSet(it)
                editing = null
            },
            onDelete = {
                viewModel.deleteSet(set)
                editing = null
            },
        )
    }
    adding?.let { (set, slot) ->
        EditSetDialog(
            set = set,
            index = slot.sets.size + 1,
            measure = Measure.fromCode(slot.exercise.measure),
            load = LoadType.fromCode(slot.exercise.load),
            onDismiss = { adding = null },
            onSave = {
                viewModel.addSet(it)
                adding = null
            },
            onDelete = null,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.history_delete_title)) },
            text = { Text(stringResource(Res.string.history_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
