package app.podkhod.ui.weights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.podkhod.resources.*
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.PlateLoad
import app.podkhod.domain.weights.PlateSolution
import app.podkhod.domain.weights.PlateTable
import app.podkhod.ui.components.DecimalField
import app.podkhod.ui.components.Hint
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import app.podkhod.ui.format.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel

/** Competition-like colours so the picture matches what the user sees on the rack. */
fun plateColor(kg: Double): Color = when {
    kg >= 25.0 -> Color(0xFFD32F2F)
    kg >= 20.0 -> Color(0xFF1976D2)
    kg >= 15.0 -> Color(0xFFFBC02D)
    kg >= 10.0 -> Color(0xFF388E3C)
    kg >= 5.0 -> Color(0xFFECEFF1)
    kg >= 2.5 -> Color(0xFFE57373)
    else -> Color(0xFFB0BEC5)
}

fun perSideText(load: PlateLoad): String = load.perSide.joinToString(" + ") { Format.weight(it) }

/** One side of the bar: plates from the collar outwards, heights by weight. */
@Composable
fun PlateGraphic(load: PlateLoad, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant
    val description = perSideText(load)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics { contentDescription = description },
    ) {
        val midY = size.height / 2
        drawRoundRect(bar, topLeft = Offset(0f, midY - 4.dp.toPx()), size = Size(size.width, 8.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()))
        drawRect(bar, topLeft = Offset(8.dp.toPx(), midY - 14.dp.toPx()), size = Size(8.dp.toPx(), 28.dp.toPx()))
        var x = 20.dp.toPx()
        val maxH = size.height
        load.perSide.forEach { kg ->
            val h = (maxH * (0.35f + 0.65f * (kg / 25.0).coerceAtMost(1.0).toFloat()))
            val w = (if (kg >= 10) 14.dp else 9.dp).toPx()
            drawRoundRect(plateColor(kg), topLeft = Offset(x, midY - h / 2), size = Size(w, h), cornerRadius = CornerRadius(2.dp.toPx()))
            x += w + 2.dp.toPx()
        }
    }
}

/** The exact load, or the two nearest buildable weights around the target. */
@Composable
fun PlateSolutionView(solution: PlateSolution, barKg: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("plate_solution")) {
        val exact = solution.exact
        if (exact != null) {
            LoadLine(exact, barKg, null)
        } else {
            Text(stringResource(Res.string.plates_not_exact), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            solution.lower?.let { LoadLine(it, barKg, Res.string.plates_lower) }
            solution.upper?.let { LoadLine(it, barKg, Res.string.plates_upper) }
        }
    }
}

@Composable
private fun LoadLine(load: PlateLoad, barKg: Double, title: StringResource?) {
    if (title != null) Text(stringResource(title, Format.weight(load.totalKg)), style = MaterialTheme.typography.titleSmall)
    Text(
        if (load.perSide.isEmpty()) stringResource(Res.string.plates_bar_only)
        else stringResource(Res.string.plates_exact, Format.weight(load.totalKg), Format.weight(barKg), perSideText(load)),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (load.perSide.isNotEmpty()) PlateGraphic(load)
}

class PlateCalculatorViewModel(profiles: ProfileRepository) : ViewModel() {
    val inventory: StateFlow<Inventory> = profiles.profile.map { it?.inventory ?: Inventory() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Inventory())
}

@Composable
fun PlateCalculatorScreen(onBack: () -> Unit, viewModel: PlateCalculatorViewModel = koinViewModel()) {
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val table = remember(inventory) { PlateTable.barbell(inventory.barKg, inventory.plates) }
    var target by remember { mutableStateOf<Double?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen_plate_calc")) {
        ScreenHeader(stringResource(Res.string.plate_calc_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Hint(stringResource(Res.string.settings_plate_calc_hint) + " · " + stringResource(Res.string.inv_bar) + " " + Format.weight(inventory.barKg))
            DecimalField(target, { target = it }, stringResource(Res.string.plate_calc_target), Modifier.fillMaxWidth().testTag("plate_calc_target"))
            target?.let { kg ->
                SectionCard { PlateSolutionView(table.solve(kg), inventory.barKg) }
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }
    }
}
