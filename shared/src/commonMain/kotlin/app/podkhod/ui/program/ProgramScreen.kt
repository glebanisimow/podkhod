package app.podkhod.ui.program

import androidx.compose.foundation.clickable
import app.podkhod.ui.theme.BrandShapes
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podkhod.resources.*
import app.podkhod.data.catalog.MuscleSet
import app.podkhod.data.plan.PlanRepository
import app.podkhod.domain.plan.Fmt
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.ui.components.Hint
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import app.podkhod.ui.media.TechniqueSheet
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramScreen(onOpenProfile: () -> Unit, onOpenWhy: () -> Unit, viewModel: ProgramViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var replacing by remember { mutableStateOf(false) }
    var techniqueFor by remember { mutableStateOf<String?>(null) }
    val program = state.program

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_program")) {
        ScreenHeader(stringResource(Res.string.program_title))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (program == null) {
                Text(stringResource(Res.string.program_empty), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(program.program.name, style = MaterialTheme.typography.titleLarge)
                if (state.generated) {
                    Hint(stringResource(Res.string.program_source_generated, program.program.mesocycleIndex + 1))
                    WeekStrip(program.program)
                    state.explanation?.takeIf { it.plannerVersion > 0 }?.let {
                        Hint(stringResource(Res.string.program_adaptive, it.historyCount))
                    }
                } else {
                    SectionCard {
                        Text(stringResource(Res.string.program_template_hint), style = MaterialTheme.typography.bodyLarge)
                        Button(shape = BrandShapes.Button, onClick = onOpenProfile, modifier = Modifier.testTag("program_fill_profile")) { Text(stringResource(Res.string.program_fill_profile)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(shape = BrandShapes.Button, onClick = onOpenWhy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("program_why")) {
                        Text(stringResource(Res.string.program_why))
                    }
                    OutlinedButton(shape = BrandShapes.Button, onClick = onOpenProfile, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("program_profile")) {
                        Text(stringResource(Res.string.program_edit_profile))
                    }
                }
                if (state.generated && program.program.weekIndex >= program.program.weeks) {
                    SectionCard {
                        Hint(stringResource(Res.string.program_new_mesocycle_hint))
                        Button(shape = BrandShapes.Button, onClick = viewModel::newMesocycle, modifier = Modifier.testTag("program_new_mesocycle")) {
                            Text(stringResource(Res.string.program_new_mesocycle))
                        }
                    }
                }
                program.days.forEachIndexed { index, day ->
                    SectionCard(Modifier.testTag("program_day_$index")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(day.day.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (index == program.nextIndex) {
                                Text(stringResource(Res.string.program_next), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        state.explanation?.minutes?.getOrNull(index)?.let { Hint(stringResource(Res.string.program_minutes, it)) }
                        Hint(stringResource(Res.string.program_day_work, day.exercises.size, day.exercises.sumOf { it.sets }))
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
                            Text(stringResource(Res.string.slot_lock), style = MaterialTheme.typography.bodyLarge)
                            Hint(stringResource(Res.string.slot_lock_hint))
                        }
                        Switch(checked = selected.locked, onCheckedChange = { viewModel.setLocked(selected, it) }, modifier = Modifier.testTag("slot_lock"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shape = BrandShapes.Button, onClick = { viewModel.changeSets(selected, -1) }, enabled = selected.sets > 1, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("slot_minus")) {
                            Text(stringResource(Res.string.slot_minus_set))
                        }
                        OutlinedButton(
                            shape = BrandShapes.Button,
                            onClick = { viewModel.changeSets(selected, 1) },
                            enabled = selected.sets < PlanRepository.MAX_MANUAL_SETS,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("slot_plus"),
                        ) { Text(stringResource(Res.string.slot_plus_set)) }
                    }
                    OutlinedButton(
                        shape = BrandShapes.Button,
                        onClick = { techniqueFor = selected.exerciseId; selectedId = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("slot_technique"),
                    ) { Text(stringResource(Res.string.technique)) }
                    Button(
                        shape = BrandShapes.Button,
                        onClick = { replacing = true; viewModel.loadSubstitutes(selected) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("slot_replace"),
                    ) { Text(stringResource(Res.string.slot_replace)) }
                    val reasons = PlanRepository.decodeReasons(selected.explanationJson)
                    if (reasons.isNotEmpty()) {
                        Text(stringResource(Res.string.slot_reasons), style = MaterialTheme.typography.titleSmall)
                        reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                } else {
                    Text(stringResource(Res.string.slot_replace_title), style = MaterialTheme.typography.titleSmall)
                    if (substitutes.isEmpty()) Hint(stringResource(Res.string.busy_none))
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
                    TextButton(onClick = { replacing = false }) { Text(stringResource(Res.string.back)) }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }

    techniqueFor?.let { state.exercises[it] }?.let { exercise ->
        val muscles by viewModel.muscles.collectAsStateWithLifecycle()
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        TechniqueSheet(
            exercise = exercise,
            muscles = muscles[exercise.id] ?: MuscleSet.EMPTY,
            animate = settings.animations,
            onDismiss = { techniqueFor = null },
        )
    }
}

@Composable
fun WhyScreen(onBack: () -> Unit, viewModel: ProgramViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_why")) {
        ScreenHeader(stringResource(Res.string.why_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val e = state.explanation
            val program = state.program
            if (e == null || program == null) {
                Text(stringResource(Res.string.why_template), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            if (e.adaptationNotes.isNotEmpty()) {
                SectionCard(Modifier.testTag("why_adaptation")) {
                    Text(stringResource(Res.string.why_adaptation), style = MaterialTheme.typography.titleMedium)
                    e.adaptationNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (e.forecast.isNotEmpty() && (e.forecastWeek ?: 0) < program.program.weeks) {
                SectionCard(Modifier.testTag("why_forecast")) {
                    Text(stringResource(Res.string.why_forecast, (e.forecastWeek ?: 0) + 1), style = MaterialTheme.typography.titleMedium)
                    Hint(stringResource(Res.string.why_forecast_hint))
                    e.forecast.forEach { v ->
                        val group = MuscleGroup.fromCode(v.group)?.let(Fmt::group) ?: v.group
                        Text(
                            (if (v.withinTarget) "✓ " else "! ") + stringResource(Res.string.why_volume_row, group, Fmt.num(v.sets), Fmt.range(v.targetLow, v.max)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (v.withinTarget) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            SectionCard(Modifier.testTag("why_volume")) {
                Text(stringResource(Res.string.why_volume), style = MaterialTheme.typography.titleMedium)
                e.volume.forEach { v ->
                    val group = MuscleGroup.fromCode(v.group)?.let(Fmt::group) ?: v.group
                    val ok = v.withinTarget
                    Text(
                        (if (ok) "✓ " else "! ") + stringResource(
                            Res.string.why_volume_row,
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
                    Text(stringResource(Res.string.why_shortfalls), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    e.shortfalls.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            SectionCard {
                Text(stringResource(Res.string.why_notes), style = MaterialTheme.typography.titleMedium)
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
