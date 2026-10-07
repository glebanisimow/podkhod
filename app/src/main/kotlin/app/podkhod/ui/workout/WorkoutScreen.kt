package app.podkhod.ui.workout

import androidx.activity.compose.BackHandler
import app.podkhod.ui.theme.BrandShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podkhod.R
import app.podkhod.data.catalog.MuscleSet
import app.podkhod.data.catalog.Technique
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.db.SetLogEntity
import app.podkhod.data.db.WorkoutSessionEntity
import app.podkhod.data.workout.SessionDetail
import app.podkhod.data.workout.SlotDetail
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.OneRepMax
import app.podkhod.domain.workout.HoldTimer
import androidx.compose.material3.LinearProgressIndicator
import app.podkhod.domain.weights.WarmupStep
import app.podkhod.service.WorkoutService
import app.podkhod.ui.program.schemeText
import app.podkhod.ui.components.ClawMarks
import app.podkhod.ui.weights.PlateSolutionView
import app.podkhod.ui.weights.perSideText
import app.podkhod.ui.format.Format
import app.podkhod.ui.format.ruPlural
import app.podkhod.ui.media.ExerciseAnimation
import app.podkhod.ui.media.MuscleMap
import app.podkhod.ui.media.TechniqueSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkoutScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onDiscarded: () -> Unit,
    viewModel: WorkoutViewModel = koinViewModel(key = "workout_$sessionId") { parametersOf(sessionId) },
) {
    val load by viewModel.load.collectAsStateWithLifecycle()
    when (val l = load) {
        WorkoutLoad.Loading -> Box(stringResource(R.string.workout_loading))
        WorkoutLoad.Missing -> LaunchedEffect(Unit) { onBack() }
        is WorkoutLoad.Ready -> {
            if (l.detail.session.status == WorkoutSessionEntity.FINISHED) {
                LaunchedEffect(Unit) { onFinished() }
            } else {
                WorkoutContent(l.detail, viewModel, onBack, onFinished, onDiscarded)
            }
        }
    }
}

@Composable
private fun Box(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WorkoutContent(
    detail: SessionDetail,
    vm: WorkoutViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onDiscarded: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val last by vm.lastResults.collectAsStateWithLifecycle()
    val warmup by vm.warmup.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val hold by vm.hold.collectAsStateWithLifecycle()
    val muscles by vm.muscles.collectAsStateWithLifecycle()
    val aids by vm.aids.collectAsStateWithLifecycle()
    var techniqueFor by remember { mutableStateOf<ExerciseEntity?>(null) }
    val session = detail.session
    val slots = detail.slots
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.id) { WorkoutService.start(context) }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }

    val restEndsAt = session.restEndsAt
    LaunchedEffect(restEndsAt) {
        if (restEndsAt != null) {
            delay((restEndsAt - System.currentTimeMillis()).coerceAtLeast(0))
            delay(REST_OVER_BANNER_MS)
            vm.clearExpiredRest()
        }
    }

    if (slots.isEmpty()) {
        Box(stringResource(R.string.program_empty))
        return
    }

    val pagerState = rememberPagerState(initialPage = session.currentPosition.coerceIn(0, slots.lastIndex)) { slots.size }
    LaunchedEffect(session.currentPosition) {
        val target = session.currentPosition.coerceIn(0, slots.lastIndex)
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { vm.select(it) }
    }

    var finishDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<SetLogEntity, SlotDetail>?>(null) }
    BackHandler(onBack = onBack)

    val page = pagerState.currentPage.coerceIn(0, slots.lastIndex)
    val slot = slots[page]
    val input = vm.inputFor(slot, inputs, last)

    Column(Modifier.fillMaxSize().testTag("screen_workout")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val remaining = detail.remainingWorkSets
                Text(
                    Format.clock((now - session.startedAt) / 1000) + " · " +
                        if (remaining > 0) ruPlural(R.plurals.sets_remaining, remaining)
                        else stringResource(R.string.workout_all_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("workout_progress"),
                )
            }
            if (!settings.oneHandMode) {
                TextButton(onClick = { finishDialog = true }, modifier = Modifier.testTag("workout_finish")) {
                    Text(stringResource(R.string.workout_finish))
                }
            }
        }

        // A running timed set pins the screen to its exercise.
        SlotStrip(slots, page) { index -> if (hold == null) scope.launch { pagerState.animateScrollToPage(index) } }
        session.planNote?.let { note ->
            var expanded by remember { mutableStateOf(false) }
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .testTag("workout_plan_note"),
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
            userScrollEnabled = hold == null,
        ) { index ->
            ExercisePage(
                slot = slots[index],
                index = index,
                active = index == pagerState.currentPage && techniqueFor == null && busy == null,
                animate = settings.animations,
                muscles = muscles[slots[index].exercise.id] ?: MuscleSet.EMPTY,
                last = last[slots[index].exercise.id].orEmpty(),
                aids = aids[slots[index].slot.id],
                onWarmupStep = { step -> vm.useWarmupStep(slots[index], vm.inputFor(slots[index], inputs, last), step) },
                onEditSet = { set -> editing = set to slots[index] },
                onRir = vm::setRir,
                onTechnique = { techniqueFor = slots[index].exercise },
            )
        }

        val nextSlot = detail.nextIndexAfter(page)?.let { slots[it] }
        BottomPanel(
            slot = slot,
            nextSlot = nextSlot,
            onNext = { nextSlot?.let { n -> scope.launch { pagerState.animateScrollToPage(slots.indexOf(n)) } } },
            input = input,
            aids = aids[slot.slot.id],
            warmup = warmup,
            restEndsAt = restEndsAt,
            hold = hold,
            now = now,
            oneHand = settings.oneHandMode,
            vm = vm,
            onFinish = { finishDialog = true },
        )
    }

    if (finishDialog) {
        val remaining = detail.remainingWorkSets
        AlertDialog(
            onDismissRequest = { finishDialog = false },
            title = { Text(stringResource(R.string.workout_finish_title)) },
            text = {
                Text(
                    if (remaining > 0) ruPlural(R.plurals.sets_not_done, remaining)
                    else stringResource(R.string.workout_finish_done),
                )
            },
            // Three actions never fit one row on a phone: stacked full width, the main one first.
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        shape = BrandShapes.Button,
                        onClick = {
                            finishDialog = false
                            vm.finish(onFinished)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("finish_confirm"),
                    ) { Text(stringResource(R.string.workout_finish), style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(
                        shape = BrandShapes.Button,
                        onClick = { finishDialog = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("finish_cancel"),
                    ) { Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium) }
                    TextButton(
                        onClick = {
                            finishDialog = false
                            discardDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("finish_discard"),
                    ) { Text(stringResource(R.string.workout_finish_discard), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }

    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text(stringResource(R.string.workout_discard_title)) },
            text = { Text(stringResource(R.string.workout_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    discardDialog = false
                    vm.discard(onDiscarded)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discardDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    editing?.let { (set, s) ->
        EditSetDialog(
            set = set,
            index = s.sets.indexOfFirst { it.id == set.id } + 1,
            measure = Measure.fromCode(s.exercise.measure),
            load = LoadType.fromCode(s.exercise.load),
            onDismiss = { editing = null },
            onSave = {
                vm.updateSet(it)
                editing = null
            },
            onDelete = {
                vm.deleteSet(set)
                editing = null
            },
        )
    }

    busy?.let { sheet ->
        BusyActionsSheet(
            sheet = sheet,
            animate = settings.animations,
            onDismiss = vm::closeBusy,
            onLater = vm::busyLater,
            onReplace = vm::busyReplace,
            onWaitWith = vm::busyWaitWith,
            onAddFiller = vm::busyAddFiller,
        )
    }

    techniqueFor?.let { ex ->
        TechniqueSheet(
            exercise = ex,
            muscles = muscles[ex.id] ?: MuscleSet.EMPTY,
            animate = settings.animations,
            onDismiss = { techniqueFor = null },
        )
    }
}

@Composable
private fun SlotStrip(slots: List<SlotDetail>, selected: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { listState.animateScrollToItem((selected - 2).coerceAtLeast(0)) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        itemsIndexed(slots, key = { _, s -> s.slot.id }) { index, s ->
            val isSelected = index == selected
            val bg = when {
                isSelected -> MaterialTheme.colorScheme.primary
                s.isDone -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            val fg = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                s.isDone -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
            Surface(
                color = bg,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(index) }
                    .testTag("slot_chip_$index"),
            ) {
                Text(
                    text = (s.slot.supersetGroup ?: "") + (index + 1) + if (s.isDone) " ✓" else "",
                    color = fg,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ExercisePage(
    slot: SlotDetail,
    index: Int,
    active: Boolean,
    animate: Boolean,
    muscles: MuscleSet,
    last: List<SetLogEntity>,
    aids: SlotAids?,
    onWarmupStep: (WarmupStep) -> Unit,
    onEditSet: (SetLogEntity) -> Unit,
    onRir: (SetLogEntity, Int) -> Unit,
    onTechnique: () -> Unit,
) {
    val ex = slot.exercise
    val measure = Measure.fromCode(ex.measure)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slot.slot.supersetGroup?.let {
            Text(stringResource(R.string.superset_label, it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(ex.nameRu, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("exercise_title_$index"))
        Row(Modifier.fillMaxWidth().height(MEDIA_HEIGHT), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExerciseAnimation(
                exerciseId = ex.id,
                animate = animate,
                active = active,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                fallbackCues = remember(ex.techniqueJson) { Technique.parse(ex.techniqueJson)?.cues.orEmpty() },
                onClick = onTechnique,
            )
            MuscleMap(muscles, Modifier.width(124.dp).fillMaxHeight())
        }
        val s = slot.slot
        Text(
            schemeText(s.sets, s.repMin, s.repMax, s.rirMin, s.rirMax, s.targetWeightKg, ex) + " · " + stringResource(R.string.plan_rest, Format.rest(s.restSec)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("workout_scheme_$index"),
        )
        ClawMarks(
            planned = s.sets,
            done = slot.workSetCount,
            description = stringResource(R.string.workout_sets_of, slot.workSetCount, s.sets),
            modifier = Modifier.testTag("workout_claws_$index"),
        )
        aids?.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("workout_hint_$index")) }
        s.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
        val warmupSteps = aids?.warmup.orEmpty()
        if (warmupSteps.isNotEmpty() && slot.workSetCount == 0) {
            Text(stringResource(R.string.warmup_title), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("warmup_ladder_$index")) {
                warmupSteps.forEachIndexed { i, step ->
                    AssistChip(
                        onClick = { onWarmupStep(step) },
                        label = { Text(stringResource(R.string.warmup_chip, Format.weight(step.weightKg), Format.range(step.repMin, step.repMax))) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("warmup_step_${index}_$i"),
                    )
                }
            }
        }
        val best = slot.sets.filter { SetKind.fromCode(it.kind).countsAsWork && (it.weightKg ?: 0.0) > 0.0 && (it.reps ?: 0) > 0 }
            .takeIf { ex.load == LoadType.EXTERNAL.code }
            ?.maxByOrNull { OneRepMax.estimate(it.weightKg!!, it.reps!!).average }
        if (best != null) {
            val e = OneRepMax.estimate(best.weightKg!!, best.reps!!)
            Text(
                stringResource(R.string.e1rm_line, Format.weight(e.average)) + if (e.isReliable) "" else " " + stringResource(R.string.e1rm_unreliable),
                style = MaterialTheme.typography.bodyMedium,
                color = if (e.isReliable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("workout_e1rm_$index"),
            )
        }
        val lastWork = last.filter { SetKind.fromCode(it.kind).countsAsWork }
        Text(
            if (lastWork.isEmpty()) stringResource(R.string.workout_last_time_none)
            else stringResource(R.string.workout_last_time, lastWork.joinToString(", ") { Format.setResult(it.weightKg, it.reps, it.durationSec) }),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (slot.sets.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        var workIndex = 0
        slot.sets.forEachIndexed { i, set ->
            val kind = SetKind.fromCode(set.kind)
            if (kind.countsAsWork) workIndex++
            SetRow(
                label = if (kind.countsAsWork) "$workIndex" else "Р",
                set = set,
                kind = kind,
                modifier = Modifier.testTag("set_row_$i"),
                onClick = { onEditSet(set) },
            )
        }
        val lastSet = slot.sets.lastOrNull()
        if (lastSet != null && SetKind.fromCode(lastSet.kind).countsAsWork && measure == Measure.REPS) {
            RirChips(lastSet, onRir)
        }
        if (slot.isDone) {
            Text(stringResource(R.string.workout_exercise_done), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(shape = BrandShapes.Button, onClick = onTechnique, modifier = Modifier.heightIn(min = 48.dp).testTag("workout_technique_$index")) {
            Text(stringResource(R.string.technique))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SetRow(label: String, set: SetLogEntity, kind: SetKind, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(32.dp))
        Text(
            Format.setResult(set.weightKg, set.reps, set.durationSec),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (set.rir != null) {
            Text("${stringResource(R.string.workout_rir).lowercase()} ${if (set.rir >= 3) "3+" else set.rir}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        if (kind != SetKind.WORK) {
            Text(stringResource(Format.setKind(kind)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun RirChips(set: SetLogEntity, onRir: (SetLogEntity, Int) -> Unit) {
    Column {
        Text(stringResource(R.string.workout_rir_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1, 2, 3).forEach { v ->
                FilterChip(
                    selected = set.rir == v || (v == 3 && (set.rir ?: -1) >= 3),
                    onClick = { onRir(set, v) },
                    label = { Text(if (v == 3) "3+" else "$v", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("rir_$v"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomPanel(
    slot: SlotDetail,
    nextSlot: SlotDetail?,
    onNext: () -> Unit,
    input: SetInput,
    aids: SlotAids?,
    warmup: Boolean,
    restEndsAt: Long?,
    hold: HoldTimer?,
    now: Long,
    oneHand: Boolean,
    vm: WorkoutViewModel,
    onFinish: () -> Unit,
) {
    val measure = Measure.fromCode(slot.exercise.measure)
    val load = LoadType.fromCode(slot.exercise.load)
    var editField by remember { mutableStateOf<Field?>(null) }
    var platesOpen by remember { mutableStateOf(false) }
    val table = aids?.table
    val solution = remember(table, input.weightKg) { table?.takeIf { input.weightKg > 0 }?.solve(input.weightKg) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hold != null) {
                HoldPanel(hold, now, oneHand, vm)
                return@Column
            }
            if (restEndsAt != null) RestBar(restEndsAt, now, vm)
            // Timed sets (plank, hang) run on the built-in countdown; the set logs itself when it ends.
            val timed = measure == Measure.SECONDS
            val logAction = { if (timed) vm.startHold(slot, input) else vm.logSet(slot, input) }
            val clock = Format.clock(input.seconds.toLong())

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (load != LoadType.NONE) {
                    Stepper(
                        label = stringResource(if (load == LoadType.BODYWEIGHT_PLUS) R.string.workout_added_weight else R.string.workout_weight),
                        value = Format.weight(input.weightKg),
                        tag = "weight",
                        modifier = Modifier.weight(1f),
                        onMinus = { vm.stepWeight(slot, input, -1) },
                        onPlus = { vm.stepWeight(slot, input, +1) },
                        onEdit = { editField = Field.WEIGHT },
                    )
                }
                if (measure == Measure.REPS) {
                    Stepper(
                        label = stringResource(R.string.workout_reps),
                        value = input.reps.toString(),
                        tag = "reps",
                        modifier = Modifier.weight(1f),
                        onMinus = { vm.stepReps(slot, input, -1) },
                        onPlus = { vm.stepReps(slot, input, +1) },
                        onEdit = { editField = Field.REPS },
                    )
                } else {
                    Stepper(
                        label = stringResource(R.string.workout_seconds),
                        value = input.seconds.toString(),
                        tag = "seconds",
                        modifier = Modifier.weight(1f),
                        onMinus = { vm.stepSeconds(slot, input, -1) },
                        onPlus = { vm.stepSeconds(slot, input, +1) },
                        onEdit = { editField = Field.SECONDS },
                    )
                }
            }

            if (solution != null) {
                val exact = solution.exact
                Text(
                    when {
                        exact == null -> stringResource(R.string.plates_not_exact) + " · " + stringResource(R.string.plates_title).lowercase()
                        exact.perSide.isEmpty() -> stringResource(R.string.plates_bar_only)
                        else -> stringResource(R.string.plates_per_side, perSideText(exact))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (exact == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { platesOpen = true }
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 4.dp, vertical = 10.dp)
                        .testTag("workout_plates"),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = warmup,
                    onClick = { vm.setWarmup(!warmup) },
                    label = { Text(stringResource(R.string.workout_warmup_toggle)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("warmup_toggle"),
                )
                // On a finished exercise the question is whether the next machine is free.
                val busyTarget = if (slot.isDone) nextSlot else slot
                OutlinedButton(
                    shape = BrandShapes.Button,
                    onClick = { busyTarget?.let(vm::openBusy) },
                    enabled = busyTarget != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("workout_busy"),
                ) { Text(stringResource(if (slot.isDone) R.string.busy_next else R.string.busy)) }
                Spacer(Modifier.weight(1f))
                if (oneHand) {
                    TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp).testTag("workout_finish")) {
                        Text(stringResource(R.string.workout_finish))
                    }
                }
            }

            val bigButton = Modifier.fillMaxWidth().height(if (oneHand) 84.dp else 72.dp)
            if (slot.isDone) {
                // The plan for this exercise is done: moving on is the lifter's call, an extra set stays one tap away.
                OutlinedButton(
                    shape = BrandShapes.Button,
                    onClick = logAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("workout_log_set"),
                ) {
                    Text(
                        when {
                            timed -> stringResource(R.string.hold_start_extra, clock)
                            warmup -> stringResource(R.string.workout_log_warmup)
                            else -> stringResource(R.string.workout_log_extra)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Button(
                    shape = BrandShapes.Button,
                    onClick = if (nextSlot != null) onNext else onFinish,
                    modifier = bigButton.testTag("workout_next"),
                ) {
                    Text(
                        if (nextSlot != null) stringResource(R.string.workout_next, nextSlot.exercise.nameRu)
                        else stringResource(R.string.workout_finish),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Button(
                    shape = BrandShapes.Button,
                    onClick = logAction,
                    modifier = bigButton.testTag("workout_log_set"),
                ) {
                    Text(
                        when {
                            timed -> stringResource(R.string.hold_start, clock)
                            warmup -> stringResource(R.string.workout_log_warmup)
                            else -> stringResource(R.string.workout_log_set)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (timed) {
                    TextButton(
                        onClick = { vm.logSet(slot, input) },
                        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 44.dp).testTag("hold_manual"),
                    ) { Text(stringResource(R.string.hold_manual)) }
                }
            }
        }
    }

    if (platesOpen && solution != null && aids != null) {
        ModalBottomSheet(onDismissRequest = { platesOpen = false }, modifier = Modifier.testTag("plates_sheet")) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.plates_title), style = MaterialTheme.typography.titleLarge)
                PlateSolutionView(solution, aids.barKg)
                val alternatives = listOfNotNull(solution.lower, solution.upper)
                if (solution.exact == null && alternatives.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        alternatives.forEach { alt ->
                            OutlinedButton(
                                shape = BrandShapes.Button,
                                onClick = {
                                    vm.updateInput(slot.slot.id, input.copy(weightKg = alt.totalKg))
                                    platesOpen = false
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(Format.weight(alt.totalKg) + " " + stringResource(R.string.unit_kg)) }
                        }
                    }
                }
            }
        }
    }

    editField?.let { field ->
        NumberInputDialog(
            title = stringResource(
                when (field) {
                    Field.WEIGHT -> R.string.input_title_weight
                    Field.REPS -> R.string.input_title_reps
                    Field.SECONDS -> R.string.input_title_seconds
                },
            ),
            initial = when (field) {
                Field.WEIGHT -> Format.weight(input.weightKg)
                Field.REPS -> input.reps.toString()
                Field.SECONDS -> input.seconds.toString()
            },
            decimal = field == Field.WEIGHT,
            onDismiss = { editField = null },
            onConfirm = { text ->
                when (field) {
                    Field.WEIGHT -> Format.parseWeight(text)?.let { vm.updateInput(slot.slot.id, input.copy(weightKg = it)) }
                    Field.REPS -> text.toIntOrNull()?.takeIf { it in 0..200 }?.let { vm.updateInput(slot.slot.id, input.copy(reps = it)) }
                    Field.SECONDS -> text.toIntOrNull()?.takeIf { it in 0..3600 }?.let { vm.updateInput(slot.slot.id, input.copy(seconds = it)) }
                }
                editField = null
            },
        )
    }
}

private enum class Field { WEIGHT, REPS, SECONDS }

/** The countdown of a timed set, in place of the inputs: big digits readable from the floor. */
@Composable
private fun HoldPanel(hold: HoldTimer, now: Long, oneHand: Boolean, vm: WorkoutViewModel) {
    val phase = hold.phase(now)
    Column(Modifier.fillMaxWidth().testTag("hold_panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val prep = phase is HoldTimer.Phase.Prep
        val left = when (phase) {
            is HoldTimer.Phase.Prep -> phase.leftSec
            is HoldTimer.Phase.Hold -> phase.leftSec
            HoldTimer.Phase.Done -> 0
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (prep) R.string.hold_get_ready else R.string.hold_holding), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.hold_target, Format.clock(hold.targetSec.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (prep) left.toString() else Format.clock(left.toLong()),
                fontSize = 72.sp,
                fontWeight = FontWeight.Black,
                color = if (prep) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("hold_left"),
            )
        }
        LinearProgressIndicator(
            progress = { (phase as? HoldTimer.Phase.Hold)?.progress ?: if (phase == HoldTimer.Phase.Done) 1f else 0f },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            drawStopIndicator = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                shape = BrandShapes.Button,
                onClick = vm::cancelHold,
                modifier = Modifier.weight(1f).height(if (oneHand) 84.dp else 72.dp).testTag("hold_cancel"),
            ) { Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium, maxLines = 1) }
            Button(
                shape = BrandShapes.Button,
                onClick = vm::stopHold,
                modifier = Modifier.weight(2f).height(if (oneHand) 84.dp else 72.dp).testTag("hold_stop"),
            ) {
                Text(
                    stringResource(if (prep) R.string.hold_stop_prep else R.string.hold_stop),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RestBar(restEndsAt: Long, now: Long, vm: WorkoutViewModel) {
    val leftSec = ((restEndsAt - now + 999) / 1000).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().testTag("rest_bar"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (leftSec > 0) stringResource(R.string.workout_rest) else stringResource(R.string.workout_rest_over),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (leftSec > 0) {
                Text(
                    Format.clock(leftSec),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("rest_left"),
                )
            }
        }
        if (leftSec > 0) {
            val compact = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(shape = BrandShapes.Button, onClick = { vm.adjustRest(-15) }, contentPadding = compact, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("rest_minus")) {
                    Text(stringResource(R.string.workout_rest_minus), maxLines = 1)
                }
                FilledTonalButton(shape = BrandShapes.Button, onClick = { vm.adjustRest(15) }, contentPadding = compact, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("rest_plus")) {
                    Text(stringResource(R.string.workout_rest_plus), maxLines = 1)
                }
                FilledTonalButton(shape = BrandShapes.Button, onClick = vm::skipRest, contentPadding = compact, modifier = Modifier.weight(1.5f).heightIn(min = 52.dp).testTag("rest_skip")) {
                    Text(stringResource(R.string.workout_rest_skip), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    tag: String,
    modifier: Modifier,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", stringResource(R.string.workout_minus), onMinus, Modifier.testTag("${tag}_minus"))
            Text(
                value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onEdit)
                    .padding(vertical = 8.dp)
                    .testTag("${tag}_value"),
            )
            StepButton("+", stringResource(R.string.workout_plus), onPlus, Modifier.testTag("${tag}_plus"))
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(
        shape = BrandShapes.Button,
        onClick = onClick,
        modifier = modifier.size(52.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) { Text(symbol, fontSize = 26.sp) }
}

@Composable
private fun NumberInputDialog(title: String, initial: String, decimal: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { v ->
                    val filtered = v.text.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }.take(7)
                    value = if (filtered == v.text) v else TextFieldValue(filtered, TextRange(filtered.length))
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text) }),
                modifier = Modifier.focusRequester(focus).testTag("number_input"),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text) }, modifier = Modifier.testTag("number_ok")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Edits a logged set; with [onDelete] null it creates a new one (the title says so). */
@Composable
internal fun EditSetDialog(
    set: SetLogEntity,
    index: Int,
    measure: Measure,
    load: LoadType,
    onDismiss: () -> Unit,
    onSave: (SetLogEntity) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var weight by remember { mutableStateOf(set.weightKg?.let(Format::weight) ?: "") }
    var amount by remember { mutableStateOf(((if (measure == Measure.SECONDS) set.durationSec else set.reps) ?: 0).toString()) }
    var kind by remember { mutableStateOf(SetKind.fromCode(set.kind)) }
    AlertDialog(
        modifier = Modifier.testTag("edit_set_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) stringResource(R.string.set_add_title) else stringResource(R.string.set_edit_title, index)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (load != LoadType.NONE) {
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { v -> weight = v.filter { it.isDigit() || it == ',' || it == '.' }.take(7) },
                        label = { Text(stringResource(if (load == LoadType.BODYWEIGHT_PLUS) R.string.workout_added_weight else R.string.workout_weight)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { v -> amount = v.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(if (measure == Measure.SECONDS) R.string.workout_seconds else R.string.workout_reps)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SetKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(stringResource(Format.setKind(k))) })
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("set_delete")) {
                        Text(stringResource(R.string.set_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("set_save"), onClick = {
                val n = amount.toIntOrNull()
                onSave(
                    set.copy(
                        weightKg = if (load == LoadType.NONE) null else Format.parseWeight(weight) ?: set.weightKg,
                        reps = if (measure == Measure.REPS) n ?: set.reps else null,
                        durationSec = if (measure == Measure.SECONDS) n ?: set.durationSec else null,
                        kind = kind.code,
                    ),
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val REST_OVER_BANNER_MS = 4_000L
private val MEDIA_HEIGHT = 168.dp
