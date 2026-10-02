package app.podkhod.ui.program

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podkhod.R
import app.podkhod.data.plan.PlanRepository
import app.podkhod.domain.plan.Fmt
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.ui.components.Hint
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramScreen(onOpenProfile: () -> Unit, onOpenWhy: () -> Unit, viewModel: ProgramViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var replacing by remember { mutableStateOf(false) }
    val program = state.program

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_program")) {
        ScreenHeader(stringResource(R.string.program_title))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (program == null) {
                Text(stringResource(R.string.program_empty), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(program.program.name, style = MaterialTheme.typography.titleLarge)
                if (state.generated) {
                    Hint(stringResource(R.string.program_source_generated, program.program.mesocycleIndex + 1))
                    WeekStrip(program.program)
                } else {
                    SectionCard {
                        Text(stringResource(R.string.program_template_hint), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onOpenProfile, modifier = Modifier.testTag("program_fill_profile")) { Text(stringResource(R.string.program_fill_profile)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenWhy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("program_why")) {
                        Text(stringResource(R.string.program_why))
                    }
                    OutlinedButton(onClick = onOpenProfile, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("program_profile")) {
                        Text(stringResource(R.string.program_edit_profile))
                    }
                }
                if (state.generated && program.program.weekIndex >= program.program.weeks) {
                    SectionCard {
                        Hint(stringResource(R.string.program_new_mesocycle_hint))
                        Button(onClick = viewModel::newMesocycle, modifier = Modifier.testTag("program_new_mesocycle")) {
                            Text(stringResource(R.string.program_new_mesocycle))
                        }
                    }
                }
                program.days.forEachIndexed { index, day ->
                    SectionCard(Modifier.testTag("program_day_$index")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(day.day.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (index == program.nextIndex) {
                                Text(stringResource(R.string.program_next), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        state.explanation?.minutes?.getOrNull(index)?.let { Hint(stringResource(R.string.program_minutes, it)) }
                        day.exercises.forEach { p ->
                            PlannedRow(
                                p,
                                state.exercises[p.exerciseId],
                                modifier = Modifier.clickable { selectedId = p.id; replacing = false }.testTag("planned_${p.exerciseId}"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }
    }

    val selected = program?.days?.flatMap { it.exercises }?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        val substitutes by viewModel.substitutes.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { selectedId = null }, modifier = Modifier.testTag("slot_sheet")) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val ex = state.exercises[selected.exerciseId]
                Text(ex?.nameRu ?: selected.exerciseId, style = MaterialTheme.typography.titleLarge)
                Text(schemeText(selected.sets, selected.repMin, selected.repMax, selected.rirMin, selected.rirMax, selected.targetWeightKg, ex))
                if (!replacing) {
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.setLocked(selected, !selected.locked) }.heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.slot_lock), style = MaterialTheme.typography.bodyLarge)
                            Hint(stringResource(R.string.slot_lock_hint))
                        }
                        Switch(checked = selected.locked, onCheckedChange = { viewModel.setLocked(selected, it) }, modifier = Modifier.testTag("slot_lock"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.changeSets(selected, -1) }, enabled = selected.sets > 1, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("slot_minus")) {
                            Text(stringResource(R.string.slot_minus_set))
                        }
                        OutlinedButton(
                            onClick = { viewModel.changeSets(selected, 1) },
                            enabled = selected.sets < PlanRepository.MAX_MANUAL_SETS,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("slot_plus"),
                        ) { Text(stringResource(R.string.slot_plus_set)) }
                    }
                    Button(
                        onClick = { replacing = true; viewModel.loadSubstitutes(selected) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("slot_replace"),
                    ) { Text(stringResource(R.string.slot_replace)) }
                    val reasons = PlanRepository.decodeReasons(selected.explanationJson)
                    if (reasons.isNotEmpty()) {
                        Text(stringResource(R.string.slot_reasons), style = MaterialTheme.typography.titleSmall)
                        reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                } else {
                    Text(stringResource(R.string.slot_replace_title), style = MaterialTheme.typography.titleSmall)
                    if (substitutes.isEmpty()) Hint(stringResource(R.string.busy_none))
                    substitutes.forEach { s ->
                        Text(
                            s.nameRu,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.replace(selected, s.id)
                                    selectedId = null
                                }
                                .heightIn(min = 48.dp)
                                .padding(vertical = 12.dp)
                                .testTag("sub_${s.id}"),
                        )
                        HorizontalDivider()
                    }
                    TextButton(onClick = { replacing = false }) { Text(stringResource(R.string.back)) }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
}

@Composable
fun WhyScreen(onBack: () -> Unit, viewModel: ProgramViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_why")) {
        ScreenHeader(stringResource(R.string.why_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val e = state.explanation
            val program = state.program
            if (e == null || program == null) {
                Text(stringResource(R.string.why_template), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            SectionCard(Modifier.testTag("why_volume")) {
                Text(stringResource(R.string.why_volume), style = MaterialTheme.typography.titleMedium)
                e.volume.forEach { v ->
                    val group = MuscleGroup.fromCode(v.group)?.let(Fmt::group) ?: v.group
                    val ok = v.withinTarget
                    Text(
                        (if (ok) "✓ " else "! ") + stringResource(
                            R.string.why_volume_row,
                            group + if (v.priority) " ★" else "",
                            Fmt.num(v.sets),
                            Fmt.range(v.targetLow, v.max),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (e.shortfalls.isNotEmpty()) {
                SectionCard {
                    Text(stringResource(R.string.why_shortfalls), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    e.shortfalls.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            SectionCard {
                Text(stringResource(R.string.why_notes), style = MaterialTheme.typography.titleMedium)
                e.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            program.days.forEach { day ->
                SectionCard {
                    Text(day.day.title, style = MaterialTheme.typography.titleMedium)
                    day.exercises.forEach { p ->
                        Text(state.exercises[p.exerciseId]?.nameRu ?: p.exerciseId, style = MaterialTheme.typography.bodyLarge)
                        PlanRepository.decodeReasons(p.explanationJson).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }
    }
}
