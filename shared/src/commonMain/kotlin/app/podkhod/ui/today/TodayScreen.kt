package app.podkhod.ui.today

import app.podkhod.ui.format.RuPlural
import app.podkhod.ui.theme.BrandShapes
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import app.podkhod.platform.rememberNotificationAccess
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podkhod.resources.*
import app.podkhod.data.catalog.MuscleSet
import app.podkhod.domain.plan.Checkin
import app.podkhod.ui.components.FieldLabel
import app.podkhod.ui.components.Hint
import app.podkhod.ui.components.BrandScene
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import app.podkhod.ui.components.SingleChoiceChips
import app.podkhod.ui.format.Format
import app.podkhod.ui.format.ruPlural
import app.podkhod.ui.media.TechniqueSheet
import app.podkhod.ui.program.PlannedRow
import app.podkhod.ui.program.WeekStrip
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TodayScreen(onOpenWorkout: (Long) -> Unit, onOpenProfile: () -> Unit, viewModel: TodayViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notifications = rememberNotificationAccess()
    var pendingSkip by remember { mutableStateOf(false) }
    var checkinFor by remember { mutableStateOf<Long?>(null) }
    var techniqueFor by remember { mutableStateOf<String?>(null) }

    val active = state.active
    var resumeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(active?.session?.id) {
        if (active != null && !ResumeOffer.shown) {
            ResumeOffer.shown = true
            resumeDialog = true
        } else if (active == null && !state.loading) {
            ResumeOffer.shown = true
        }
    }

    val program = state.program
    val next = program?.nextDay
    Column(Modifier.fillMaxSize().testTag("screen_today")) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            ScreenHeader(stringResource(Res.string.today_title))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (active != null) {
                    SectionCard(Modifier.testTag("today_active")) {
                        Text(stringResource(Res.string.today_active_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(Res.string.today_active_body, active.session.title, Format.moment(active.session.startedAt)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                if (program != null && state.generated) {
                    WeekStrip(program.program)
                    val offer = program.program.deloadOffer
                    if (offer != null && active == null) {
                        SectionCard(Modifier.testTag("today_deload_offer")) {
                            Text(stringResource(Res.string.today_deload_offer_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(Res.string.today_deload_offer_body, offer), style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(shape = BrandShapes.Button, onClick = viewModel::acceptDeload, modifier = Modifier.weight(1f).testTag("deload_accept")) {
                                    Text(stringResource(Res.string.today_deload_accept))
                                }
                                OutlinedButton(shape = BrandShapes.Button, onClick = viewModel::declineDeload, modifier = Modifier.weight(1f).testTag("deload_decline")) {
                                    Text(stringResource(Res.string.today_deload_decline))
                                }
                            }
                        }
                    }
                    if (program.program.deloadSessionsLeft > 0) {
                        Hint(stringResource(Res.string.today_deload_active, program.program.deloadSessionsLeft))
                    }
                    if (state.mesocycleDone && active == null) {
                        SectionCard(Modifier.testTag("today_mesocycle_done")) {
                            Text(stringResource(Res.string.today_mesocycle_done_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(Res.string.today_mesocycle_done_body), style = MaterialTheme.typography.bodyLarge)
                            Button(shape = BrandShapes.Button, onClick = viewModel::newMesocycle, modifier = Modifier.testTag("today_new_mesocycle")) {
                                Text(stringResource(Res.string.program_new_mesocycle))
                            }
                        }
                    }
                } else if (program != null) {
                    SectionCard(Modifier.testTag("today_template_hint")) {
                        Text(stringResource(Res.string.program_template_hint), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = onOpenProfile) { Text(stringResource(Res.string.program_fill_profile)) }
                    }
                }

                when {
                    program == null -> SectionCard { Text(stringResource(Res.string.today_loading), style = MaterialTheme.typography.bodyLarge) }
                    next == null -> SectionCard { Text(stringResource(Res.string.program_empty), style = MaterialTheme.typography.bodyLarge) }
                    active == null -> SectionCard(Modifier.testTag("today_next")) {
                        Text(stringResource(Res.string.today_next_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(next.day.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(
                                Res.string.today_day_of,
                                program.nextIndex + 1,
                                program.days.size,
                                ruPlural(RuPlural.EXERCISES, next.exercises.size),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        next.exercises.forEach { p ->
                            PlannedRow(
                                p,
                                state.exercises[p.exerciseId],
                                compact = true,
                                onClick = { techniqueFor = p.exerciseId },
                                modifier = Modifier.testTag("today_exercise_${p.exerciseId}"),
                            )
                        }
                    }
                    // A workout is running: the plan lives on the workout screen, the scene fills the pause.
                    else -> BrandScene(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), height = 220.dp)
                }

                if (!notifications.enabled) {
                    Text(
                        stringResource(Res.string.today_notifications_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.heightIn(min = 16.dp))
            }
        }

        // Primary actions stay pinned to the bottom: reachable with one thumb and never scrolled away.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                active != null -> Button(
                    shape = BrandShapes.Button,
                    onClick = { onOpenWorkout(active.session.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("today_continue"),
                ) { Text(stringResource(Res.string.today_continue), style = MaterialTheme.typography.titleLarge) }
                program != null && next != null -> {
                    Button(
                        shape = BrandShapes.Button,
                        onClick = {
                            notifications.request()
                            checkinFor = next.day.id
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("today_start"),
                    ) { Text(stringResource(Res.string.today_start), style = MaterialTheme.typography.titleLarge) }
                    if (program.days.size > 1) {
                        OutlinedButton(
                            shape = BrandShapes.Button,
                            onClick = { pendingSkip = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("today_skip"),
                        ) { Text(stringResource(Res.string.today_skip)) }
                    }
                }
            }
        }
    }

    checkinFor?.let { dayId ->
        CheckinDialog(
            onStart = { checkin ->
                checkinFor = null
                viewModel.start(dayId, checkin, onOpenWorkout)
            },
            onDismiss = { checkinFor = null },
        )
    }

    if (resumeDialog && active != null) {
        AlertDialog(
            modifier = Modifier.testTag("resume_dialog"),
            onDismissRequest = { resumeDialog = false },
            title = { Text(stringResource(Res.string.today_resume_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.today_resume_body,
                        active.session.title,
                        Format.moment(active.session.startedAt),
                        active.slots.sumOf { it.sets.size },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resumeDialog = false
                        onOpenWorkout(active.session.id)
                    },
                    modifier = Modifier.testTag("resume_continue"),
                ) { Text(stringResource(Res.string.today_resume_continue)) }
            },
            dismissButton = { TextButton(onClick = { resumeDialog = false }) { Text(stringResource(Res.string.today_resume_later)) } },
        )
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

    if (pendingSkip && program != null && program.days.size > 1) {
        val current = program.days[program.nextIndex].day.title
        val following = program.days[(program.nextIndex + 1) % program.days.size].day.title
        AlertDialog(
            onDismissRequest = { pendingSkip = false },
            title = { Text(stringResource(Res.string.today_skip_title, current)) },
            text = { Text(stringResource(Res.string.today_skip_body, following)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSkip = false
                        viewModel.skip()
                    },
                    modifier = Modifier.testTag("today_skip_confirm"),
                ) { Text(stringResource(Res.string.today_skip_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingSkip = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

private val CHECKIN_KEYS = listOf("sleep", "legs", "ready")

/** Three questions with "хорошо" preselected: one tap to start when everything is fine. */
@Composable
private fun CheckinDialog(onStart: (Checkin?) -> Unit, onDismiss: () -> Unit) {
    var sleep by remember { mutableIntStateOf(0) }
    var legs by remember { mutableIntStateOf(0) }
    var ready by remember { mutableIntStateOf(0) }
    var long by remember { mutableStateOf(false) }
    val labels = listOf(stringResource(Res.string.checkin_good), stringResource(Res.string.checkin_mid), stringResource(Res.string.checkin_bad))
    AlertDialog(
        modifier = Modifier.testTag("checkin_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.checkin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple(Res.string.checkin_sleep, sleep) { v: Int -> sleep = v },
                    Triple(Res.string.checkin_legs, legs) { v: Int -> legs = v },
                    Triple(Res.string.checkin_ready, ready) { v: Int -> ready = v },
                ).forEachIndexed { i, (title, value, set) ->
                    FieldLabel(stringResource(title))
                    SingleChoiceChips(listOf(0, 1, 2), value, { labels[it] }, set, tag = "checkin_${CHECKIN_KEYS[i]}")
                }
                Row(Modifier.fillMaxWidth().clickable { long = !long }, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.checkin_long), modifier = Modifier.weight(1f))
                    Switch(checked = long, onCheckedChange = { long = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(Checkin(sleep, legs, ready, long)) }, modifier = Modifier.testTag("checkin_start")) {
                Text(stringResource(Res.string.checkin_start))
            }
        },
        dismissButton = {
            TextButton(onClick = { onStart(null) }, modifier = Modifier.testTag("checkin_skip")) { Text(stringResource(Res.string.checkin_skip)) }
        },
    )
}
