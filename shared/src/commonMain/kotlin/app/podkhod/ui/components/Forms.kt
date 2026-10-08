package app.podkhod.ui.components

import androidx.compose.foundation.layout.Arrangement
import app.podkhod.ui.theme.BrandShapes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.podkhod.domain.plan.Fmt
import app.podkhod.ui.format.Format

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier)
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One-of chips, e.g. 2/3/4 days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SingleChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, tag: String = "") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_$o"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> MultiChoiceChips(options: List<T>, selected: Set<T>, label: (T) -> String, onToggle: (T) -> Unit, enabled: (T) -> Boolean = { true }) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o in selected,
                onClick = { onToggle(o) },
                enabled = o in selected || enabled(o),
                label = { Text(label(o)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
fun WeekdayChips(selected: Set<Int>, onToggle: (Int) -> Unit) {
    MultiChoiceChips((1..7).toList(), selected, { Fmt.weekday(it) }, onToggle)
}

/** A large tappable card for one-of choices with a description. */
@Composable
fun ChoiceCard(title: String, hint: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Decimal input with a comma or a dot. Keeps the typed text (e.g. "62,") while the value stays the last
 * parsed number; an empty field means null.
 */
@Composable
fun DecimalField(
    value: Double?,
    onValueChange: (Double?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    integer: Boolean = false,
    enabled: Boolean = true,
    max: Double = Double.MAX_VALUE,
) {
    var text by remember { mutableStateOf(value?.let { if (integer) it.toInt().toString() else Format.weight(it) }.orEmpty()) }
    fun parse(t: String): Double? = (if (integer) t.trim().toIntOrNull()?.toDouble() else Format.parseWeight(t))?.takeIf { it <= max }
    // Invalid text stays visible and red; the model keeps no value instead of a stale one.
    val invalid = text.isNotBlank() && parse(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            onValueChange(if (t.isBlank()) null else parse(t))
        },
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier.widthIn(min = 96.dp),
    )
}

/** "label  [−] n [+]" row for counts. */
@Composable
fun CountRow(label: String, count: Int, onChange: (Int) -> Unit, min: Int = 0, max: Int = 20, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(shape = BrandShapes.Button, onClick = { onChange((count - 1).coerceAtLeast(min)) }, enabled = count > min) { Text("−") }
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 8.dp),
        )
        OutlinedButton(shape = BrandShapes.Button, onClick = { onChange((count + 1).coerceAtMost(max)) }, enabled = count < max) { Text("+") }
    }
}
