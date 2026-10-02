package app.podkhod.ui.profile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.podkhod.domain.OneRepMax
import app.podkhod.domain.plan.Baseline
import app.podkhod.domain.plan.BaselineLift
import app.podkhod.domain.plan.Fmt
import app.podkhod.domain.plan.Goal
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.plan.Style
import app.podkhod.domain.weights.Implement
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.PlatePair
import app.podkhod.ui.components.ChoiceCard
import app.podkhod.ui.components.CountRow
import app.podkhod.ui.components.DecimalField
import app.podkhod.ui.components.FieldLabel
import app.podkhod.ui.components.Hint
import app.podkhod.ui.components.MultiChoiceChips
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import app.podkhod.ui.components.SingleChoiceChips
import app.podkhod.ui.components.WeekdayChips
import app.podkhod.ui.format.Format
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** First-run onboarding ([firstRun]) or the profile editor reachable at any time. */
@Composable
fun ProfileScreen(
    firstRun: Boolean,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: ProfileViewModel = koinViewModel(key = "profile_$firstRun") { parametersOf(firstRun) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val last = state.step == ProfileStep.entries.last()
    Column(Modifier.fillMaxSize().testTag(if (firstRun) "screen_onboarding" else "screen_profile")) {
        ScreenHeader(stringResource(if (firstRun) R.string.onb_title else R.string.profile_title), onBack = onBack)
        if (firstRun) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.onb_step_of, state.step.ordinal + 1, ProfileStep.entries.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.skipAll(onDone) }, enabled = !state.busy, modifier = Modifier.testTag("onboarding_skip_all")) {
                    Text(stringResource(R.string.onb_skip_all))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileStep.entries.forEach { s ->
                    FilterChip(
                        selected = s == state.step,
                        onClick = { viewModel.goTo(s) },
                        label = { Text(stringResource(s.title)) },
                        modifier = Modifier.testTag("profile_step_${s.name.lowercase()}"),
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (!state.loading) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(stringResource(state.step.title), style = MaterialTheme.typography.headlineSmall)
                    if (firstRun && state.step == ProfileStep.GOAL) Hint(stringResource(R.string.onb_skip_all_hint))
                    StepContent(state, viewModel::edit)
                    Spacer(Modifier.heightIn(min = 16.dp))
                }
            }
            if (state.busy) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.onb_working), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!firstRun) {
                Button(
                    onClick = { viewModel.finish(onDone) },
                    enabled = !state.busy && !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("profile_save"),
                ) { Text(stringResource(R.string.profile_save)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::previous,
                    enabled = state.step.ordinal > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("onboarding_back"),
                    contentPadding = NavButtonPadding,
                ) { Text(stringResource(R.string.back)) }
                if (firstRun && !last) {
                    OutlinedButton(
                        onClick = viewModel::next,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("onboarding_skip"),
                        contentPadding = NavButtonPadding,
                    ) { Text(stringResource(R.string.onb_skip)) }
                }
                when {
                    !last -> Button(
                        onClick = viewModel::next,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("onboarding_next"),
                        contentPadding = NavButtonPadding,
                    ) { Text(stringResource(R.string.onb_next)) }
                    firstRun -> Button(
                        onClick = { viewModel.finish(onDone) },
                        enabled = !state.busy && !state.loading,
                        modifier = Modifier.weight(2f).heightIn(min = 52.dp).testTag("onboarding_create"),
                    ) { Text(stringResource(R.string.onb_create)) }
                }
            }
        }
    }

    state.preview?.let { preview ->
        AlertDialog(
            modifier = Modifier.testTag("profile_diff"),
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(stringResource(R.string.diff_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (preview.changes.isEmpty()) Text(stringResource(R.string.diff_none))
                    preview.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    preview.program.shortfalls.forEach { Text("! $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirm(onDone) }, modifier = Modifier.testTag("profile_diff_apply")) {
                    Text(stringResource(R.string.diff_apply))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private val NavButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

private val ProfileStep.title: Int
    get() = when (this) {
        ProfileStep.GOAL -> R.string.onb_goal_title
        ProfileStep.SCHEDULE -> R.string.onb_schedule_title
        ProfileStep.STYLE -> R.string.onb_style_title
        ProfileStep.BASELINES -> R.string.onb_baselines_title
        ProfileStep.INVENTORY -> R.string.onb_inventory_title
        ProfileStep.RESTRICTIONS -> R.string.onb_restrictions_title
        ProfileStep.BODY -> R.string.onb_body_title
    }

@Composable
private fun StepContent(state: ProfileUiState, edit: ((Profile) -> Profile) -> Unit) {
    val p = state.draft
    when (state.step) {
        ProfileStep.GOAL -> {
            listOf(
                Triple(Goal.STRENGTH, R.string.goal_strength, R.string.goal_strength_hint),
                Triple(Goal.HYPERTROPHY, R.string.goal_hypertrophy, R.string.goal_hypertrophy_hint),
                Triple(Goal.BALANCE, R.string.goal_balance, R.string.goal_balance_hint),
            ).forEach { (goal, title, hint) ->
                ChoiceCard(stringResource(title), stringResource(hint), p.goal == goal, { edit { it.copy(goal = goal) } }, Modifier.testTag("goal_${goal.code}"))
            }
            FieldLabel(stringResource(R.string.onb_priorities))
            MultiChoiceChips(
                options = MuscleGroup.entries,
                selected = p.priorities.toSet(),
                label = { Fmt.group(it) },
                onToggle = { g -> edit { it.copy(priorities = if (g in it.priorities) it.priorities - g else (it.priorities + g).take(2)) } },
                enabled = { p.priorities.size < 2 },
            )
        }

        ProfileStep.SCHEDULE -> {
            FieldLabel(stringResource(R.string.onb_days))
            SingleChoiceChips(listOf(2, 3, 4), p.daysPerWeek, { it.toString() }, { d -> edit { it.copy(daysPerWeek = d) } }, tag = "days")
            Hint(stringResource(R.string.onb_days_hint))
            FieldLabel(stringResource(R.string.onb_minutes))
            SingleChoiceChips(listOf(40, 45, 60, 75), p.sessionMinutes, { it.toString() }, { m -> edit { it.copy(sessionMinutes = m) } }, tag = "minutes")
            FieldLabel(stringResource(R.string.onb_preferred_days))
            WeekdayChips(p.preferredDays) { d -> edit { it.copy(preferredDays = it.preferredDays.toggle(d)) } }
            FieldLabel(stringResource(R.string.onb_long_days))
            WeekdayChips(p.longEffortDays) { d -> edit { it.copy(longEffortDays = it.longEffortDays.toggle(d)) } }
            Hint(stringResource(R.string.onb_long_days_hint))
            FieldLabel(stringResource(R.string.onb_cardio_days))
            WeekdayChips(p.cardioDays) { d -> edit { it.copy(cardioDays = it.cardioDays.toggle(d)) } }
            CountRow(stringResource(R.string.onb_mesocycle, p.mesocycleWeeks), p.mesocycleWeeks, { w -> edit { it.copy(mesocycleWeeks = w) } }, min = 4, max = 8)
        }

        ProfileStep.STYLE -> {
            ChoiceCard(stringResource(R.string.style_intensive), stringResource(R.string.style_intensive_hint), p.style == Style.INTENSIVE, {
                edit { it.copy(style = Style.INTENSIVE) }
            }, Modifier.testTag("style_intensive"))
            ChoiceCard(stringResource(R.string.style_classic), stringResource(R.string.style_classic_hint), p.style == Style.CLASSIC, {
                edit { it.copy(style = Style.CLASSIC) }
            }, Modifier.testTag("style_classic"))
        }

        ProfileStep.BASELINES -> {
            Hint(stringResource(R.string.onb_baselines_hint))
            BaselineLift.entries.forEach { lift ->
                BaselineCard(
                    title = state.liftNames[lift.code] ?: lift.code,
                    lift = lift,
                    baseline = p.baselines[lift] ?: Baseline(),
                    bodyMassKg = p.bodyMassKg,
                    onChange = { b -> edit { it.copy(baselines = it.baselines + (lift to b)) } },
                )
            }
        }

        ProfileStep.INVENTORY -> InventoryStep(p.inventory) { inv -> edit { it.copy(inventory = inv) } }

        ProfileStep.RESTRICTIONS -> {
            Hint(stringResource(R.string.onb_restrictions_hint))
            val labels = restrictionLabels()
            MultiChoiceChips(state.restrictionKeys, p.restrictionTags, { labels[it] ?: it }, { k -> edit { it.copy(restrictionTags = it.restrictionTags.toggle(k)) } })
            var note by remember { mutableStateOf(p.restrictionNote) }
            OutlinedTextField(
                value = note,
                onValueChange = { t -> note = t; edit { it.copy(restrictionNote = t) } },
                label = { Text(stringResource(R.string.restriction_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ProfileStep.BODY -> {
            DecimalField(p.bodyMassKg, { v -> edit { it.copy(bodyMassKg = v?.takeIf { kg -> kg > 0 }) } }, stringResource(R.string.body_mass), Modifier.testTag("body_mass"))
            Hint(stringResource(R.string.body_mass_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.units_kg)) })
                FilterChip(selected = false, onClick = {}, enabled = false, label = { Text(stringResource(R.string.units_lb_later)) })
            }
        }
    }
}

@Composable
private fun restrictionLabels(): Map<String, String> = mapOf(
    "knees" to stringResource(R.string.restr_knees),
    "lower_back" to stringResource(R.string.restr_lower_back),
    "shoulders" to stringResource(R.string.restr_shoulders),
    "grip" to stringResource(R.string.restr_grip),
    "no_overhead" to stringResource(R.string.restr_no_overhead),
    "no_ballistic" to stringResource(R.string.restr_no_ballistic),
    "spine_flexion" to stringResource(R.string.restr_spine_flexion),
)

@Composable
private fun BaselineCard(title: String, lift: BaselineLift, baseline: Baseline, bodyMassKg: Double?, onChange: (Baseline) -> Unit) {
    SectionCard(Modifier.testTag("baseline_${lift.code}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(selected = baseline.skipped, onClick = { onChange(baseline.copy(skipped = !baseline.skipped)) }, label = { Text(stringResource(R.string.baseline_skip)) })
        }
        if (!baseline.skipped) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(
                    baseline.weightKg,
                    { onChange(baseline.copy(weightKg = it)) },
                    stringResource(if (lift.bodyweight) R.string.baseline_added else R.string.baseline_weight),
                    Modifier.weight(1f),
                )
                DecimalField(baseline.reps?.toDouble(), { onChange(baseline.copy(reps = it?.toInt()?.takeIf { r -> r > 0 })) }, stringResource(R.string.baseline_reps), Modifier.weight(1f), integer = true, max = OneRepMax.MAX_REPS.toDouble())
            }
            DecimalField(baseline.e1rmOverrideKg, { onChange(baseline.copy(e1rmOverrideKg = it?.takeIf { kg -> kg > 0 })) }, stringResource(R.string.baseline_override), Modifier.fillMaxWidth())
            val w = baseline.weightKg
            val r = baseline.reps
            if (baseline.e1rmOverrideKg == null && w != null && r != null && r > 0) {
                val total = if (lift.bodyweight) (bodyMassKg ?: 0.0) + w else w
                if (total > 0) {
                    val e = OneRepMax.estimate(total, r)
                    Hint(stringResource(R.string.baseline_e1rm, Format.weight(e.average), Format.weight(e.epley), Format.weight(e.brzycki)))
                    if (!e.isReliable) Text(stringResource(R.string.baseline_e1rm_unreliable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val STANDARD_PLATES = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5)
private val STANDARD_KETTLEBELLS = listOf(8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0)
private val STANDARD_DUMBBELLS = (1..12).map { it * 2.5 }

@Composable
private fun InventoryStep(inv: Inventory, onChange: (Inventory) -> Unit) {
    DecimalField(inv.barKg, { v -> v?.takeIf { it > 0 }?.let { onChange(inv.copy(barKg = it)) } }, stringResource(R.string.inv_bar), Modifier.testTag("inv_bar"))

    FieldLabel(stringResource(R.string.inv_plates))
    WeightCounts(
        weights = (STANDARD_PLATES + inv.plates.map { it.weightKg }).distinct().sortedDescending(),
        count = { w -> inv.plates.firstOrNull { it.weightKg == w }?.pairs ?: 0 },
        onCount = { w, n -> onChange(inv.copy(plates = (inv.plates.filter { it.weightKg != w } + PlatePair(w, n)).sortedByDescending { it.weightKg })) },
        tag = "plate",
    )

    FieldLabel(stringResource(R.string.inv_kettlebells))
    WeightCounts(
        weights = (STANDARD_KETTLEBELLS + inv.kettlebells.map { it.weightKg }).distinct().sorted(),
        count = { w -> inv.kettlebells.firstOrNull { it.weightKg == w }?.count ?: 0 },
        onCount = { w, n -> onChange(inv.copy(kettlebells = (inv.kettlebells.filter { it.weightKg != w } + Implement(w, n)).sortedBy { it.weightKg })) },
        tag = "kb",
    )

    val hasDumbbells = Inventory.DUMBBELLS in inv.equipment
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = hasDumbbells,
            onClick = { onChange(inv.copy(equipment = inv.equipment.toggle(Inventory.DUMBBELLS))) },
            label = { Text(stringResource(R.string.inv_dumbbells_toggle)) },
            modifier = Modifier.testTag("inv_dumbbells"),
        )
    }
    if (hasDumbbells) {
        FieldLabel(stringResource(R.string.inv_dumbbells))
        WeightCounts(
            weights = (STANDARD_DUMBBELLS + inv.dumbbells.map { it.weightKg }).distinct().sorted(),
            count = { w -> inv.dumbbells.firstOrNull { it.weightKg == w }?.count ?: 0 },
            onCount = { w, n -> onChange(inv.copy(dumbbells = (inv.dumbbells.filter { it.weightKg != w } + Implement(w, n)).sortedBy { it.weightKg })) },
            tag = "db",
        )
    }

    FieldLabel(stringResource(R.string.inv_equipment))
    MultiChoiceChips(
        options = Fmt.EQUIPMENT.keys.filter { it != Inventory.DUMBBELLS },
        selected = inv.equipment,
        label = { Fmt.equipment(it) },
        onToggle = { code -> onChange(inv.copy(equipment = inv.equipment.toggle(code))) },
    )
}

/** Count rows for standard weights plus a field to add an unusual one. */
@Composable
private fun WeightCounts(weights: List<Double>, count: (Double) -> Int, onCount: (Double, Int) -> Unit, tag: String) {
    Column {
        weights.forEach { w ->
            CountRow("${Format.weight(w)} кг", count(w), { onCount(w, it) }, modifier = Modifier.testTag("${tag}_${Format.weight(w)}"))
        }
        var custom by remember { mutableStateOf<Double?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DecimalField(custom, { custom = it }, stringResource(R.string.inv_add_weight), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { custom?.takeIf { it > 0 }?.let { onCount(it, maxOf(count(it), 1)) } }, enabled = (custom ?: 0.0) > 0) {
                Text(stringResource(R.string.inv_add))
            }
        }
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
