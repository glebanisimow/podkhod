package app.podkhod.ui.workout

import androidx.compose.foundation.clickable
import app.podkhod.ui.theme.BrandShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import app.podkhod.resources.*
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.workout.SlotDetail
import app.podkhod.domain.busy.EstimateSource
import app.podkhod.domain.model.Measure
import app.podkhod.ui.format.Format
import app.podkhod.ui.media.ExerciseAnimation

/**
 * "Busy" actions for the current exercise. A substitute is one tap away from the sheet, so a busy
 * station costs two taps in total; "forever" is an opt-in switch above the options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusyActionsSheet(
    sheet: BusySheet,
    animate: Boolean,
    onDismiss: () -> Unit,
    onLater: () -> Unit,
    onReplace: (BusyOption, Boolean) -> Unit,
    onWaitWith: (SlotDetail) -> Unit,
    onAddFiller: (ExerciseEntity) -> Unit,
) {
    var forever by remember { mutableStateOf(false) }
    var showWait by remember { mutableStateOf(false) }
    val remaining = (sheet.slot.slot.sets - sheet.slot.workSetCount).coerceAtLeast(0)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("busy_sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Res.string.busy_title, sheet.slot.exercise.nameRu), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape = BrandShapes.Button, onClick = onLater, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("busy_later")) {
                    Text(stringResource(Res.string.busy_later))
                }
                OutlinedButton(
                    shape = BrandShapes.Button,
                    onClick = { showWait = !showWait },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("busy_wait"),
                ) { Text(stringResource(Res.string.busy_wait)) }
            }
            if (showWait) WaitSection(sheet, onWaitWith, onAddFiller)

            Text(stringResource(Res.string.busy_replace_today), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            if (sheet.slot.sets.isNotEmpty() && remaining > 0) {
                Text(
                    stringResource(Res.string.busy_partial, remaining),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sheet.canReplaceForever) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { forever = !forever }
                        .heightIn(min = 52.dp)
                        .testTag("busy_forever"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.busy_forever), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = forever, onCheckedChange = { forever = it })
                }
            }
            if (sheet.options.isEmpty()) {
                Text(stringResource(Res.string.busy_none), style = MaterialTheme.typography.bodyLarge)
            }
            sheet.options.forEachIndexed { index, option ->
                OptionCard(option, remaining, animate, Modifier.testTag("busy_option_$index")) { onReplace(option, forever) }
            }
        }
    }
}

@Composable
private fun OptionCard(option: BusyOption, sets: Int, animate: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val estimate = option.option.estimate
    val unit = if (Measure.fromCode(option.exercise.measure) == Measure.SECONDS) " с" else ""
    val plan = buildString {
        append(stringResource(Res.string.plan_line, sets.coerceAtLeast(1), Format.range(estimate.repMin, estimate.repMax) + unit))
        estimate.weightKg?.takeIf { it > 0.0 }?.let { append(" · ").append(Format.weight(it)).append(" кг") }
    }
    val basis = when (estimate.source) {
        EstimateSource.OWN_HISTORY -> Res.string.busy_est_own
        EstimateSource.RATIO -> Res.string.busy_est_ratio
        EstimateSource.BODYWEIGHT -> Res.string.busy_est_bodyweight
        EstimateSource.NO_LOAD -> null
        EstimateSource.UNKNOWN -> Res.string.busy_est_unknown
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ExerciseAnimation(
                exerciseId = option.exercise.id,
                animate = animate,
                active = true,
                modifier = Modifier.width(104.dp).height(72.dp),
                compact = true,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.exercise.nameRu, style = MaterialTheme.typography.titleMedium)
                Text(plan, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                basis?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (estimate.source == EstimateSource.UNKNOWN) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (option.option.sharesBusyStation) {
                    Text(stringResource(Res.string.busy_same_station), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (option.option.overloadsMuscle) {
                    Text(stringResource(Res.string.busy_overload), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun WaitSection(sheet: BusySheet, onWaitWith: (SlotDetail) -> Unit, onAddFiller: (ExerciseEntity) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("busy_wait_section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sheet.waitSlot == null && sheet.extras.isEmpty()) {
            Text(stringResource(Res.string.busy_wait_none), style = MaterialTheme.typography.bodyLarge)
        }
        sheet.waitSlot?.let { s ->
            Text(stringResource(Res.string.busy_wait_from_plan), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WaitRow(stringResource(Res.string.busy_wait_go, s.exercise.nameRu), Modifier.testTag("busy_wait_slot")) { onWaitWith(s) }
        }
        if (sheet.extras.isNotEmpty()) {
            Text(stringResource(Res.string.busy_wait_extra), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sheet.extras.forEachIndexed { i, ex ->
                WaitRow(stringResource(Res.string.busy_wait_add, ex.nameRu), Modifier.testTag("busy_extra_$i")) { onAddFiller(ex) }
            }
        }
    }
}

@Composable
private fun WaitRow(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}
