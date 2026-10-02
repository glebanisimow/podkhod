package app.podkhod.ui.media

import android.graphics.drawable.Animatable
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import app.podkhod.R
import app.podkhod.data.catalog.MuscleSet
import app.podkhod.data.catalog.Technique
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.media.MediaRepository
import app.podkhod.domain.model.Muscle
import app.podkhod.ui.format.Format
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import org.koin.compose.koinInject
import kotlin.math.min

/**
 * Looping movement animation. It only runs while [active] (the visible page), [animate] (user setting)
 * and the screen is started; otherwise the current frame stays on screen.
 */
@Composable
fun ExerciseAnimation(
    exerciseId: String,
    animate: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Technique cues shown instead of the animation when an exercise has no media. */
    fallbackCues: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
) {
    val repo = koinInject<MediaRepository>()
    val userFiles by repo.userFiles.collectAsStateWithLifecycle()
    val media = remember(exerciseId, userFiles) { repo.media(exerciseId, userFiles) }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("exercise_media"),
        contentAlignment = Alignment.Center,
    ) {
        if (media == null && !compact && fallbackCues.isNotEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(12.dp).testTag("exercise_media_none"),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                fallbackCues.take(FALLBACK_CUES).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
            return@Box
        }
        if (media == null) {
            Text(
                stringResource(if (compact) R.string.media_none_short else R.string.media_none),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(if (compact) 4.dp else 16.dp).testTag("exercise_media_none"),
            )
            return@Box
        }
        val context = LocalContext.current
        val systemAnimations = remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
        val playable = animate && systemAnimations
        // With animations off the file is decoded as a still frame, so no animated drawable exists at all.
        val request = remember(media.model, playable) {
            ImageRequest.Builder(context).data(media.model).apply {
                if (!playable) {
                    decoderFactory(BitmapFactoryDecoder.Factory())
                    memoryCacheKeyExtra(STILL_KEY, "1")
                }
            }.build()
        }
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val run = playable && active && lifecycle.isAtLeast(Lifecycle.State.STARTED)
        LaunchedEffect(state, run) {
            val drawable = (state as? AsyncImagePainter.State.Success)?.result?.image?.asDrawable(context.resources)
            (drawable as? Animatable)?.let { if (run) it.start() else it.stop() }
        }
        // Own layer: each animation frame re-records only the image, not the whole page.
        Image(painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer())
        if (state is AsyncImagePainter.State.Error) {
            Text(stringResource(R.string.media_error), style = MaterialTheme.typography.bodyMedium)
        }
        val badge = when {
            media.userProvided -> R.string.media_user
            media.similar -> R.string.media_similar
            else -> null
        }
        if (badge != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            ) {
                Text(
                    if (compact) "≈" else stringResource(badge),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 0.dp else 3.dp),
                )
            }
        }
    }
}

/** Simple front/back body diagram: primary muscles saturated, secondary pale. */
@Composable
fun MuscleMap(muscles: MuscleSet, modifier: Modifier = Modifier, showLabels: Boolean = true) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = primaryColor.copy(alpha = 0.38f)
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outlineVariant
    val description = muscleNames(muscles.primary)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer()
                .semantics { contentDescription = description }
                .testTag("muscle_map"),
        ) {
            fun colorOf(m: Muscle?): Color = when (m) {
                null -> outline
                in muscles.primary -> primaryColor
                in muscles.secondary -> secondaryColor
                else -> baseColor
            }
            val scale = min(size.width / FIGURES_W, size.height / FIGURE_H)
            val dx = (size.width - FIGURES_W * scale) / 2
            val dy = (size.height - FIGURE_H * scale) / 2
            withTransform({
                translate(dx, dy)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawFigure(FRONT_REGIONS, 0f, ::colorOf)
                drawFigure(BACK_REGIONS, FIGURE_W + FIGURE_GAP, ::colorOf)
            }
        }
        if (showLabels) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.muscles_front), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.muscles_back), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun DrawScope.drawFigure(regions: List<Pair<Muscle?, Rect>>, x0: Float, colorOf: (Muscle?) -> Color) {
    drawCircle(colorOf(null), radius = 11f, center = Offset(x0 + 50f, 15f))
    regions.forEach { (m, r) ->
        drawRoundRect(
            color = colorOf(m),
            topLeft = Offset(x0 + r.left, r.top),
            size = Size(r.width, r.height),
            cornerRadius = CornerRadius(5f, 5f),
        )
    }
}

private const val STILL_KEY = "still"
private const val FALLBACK_CUES = 3
private const val FIGURE_W = 100f
private const val FIGURE_H = 216f
private const val FIGURE_GAP = 12f
private const val FIGURES_W = FIGURE_W * 2 + FIGURE_GAP

private fun pair(m: Muscle?, l: Float, t: Float, r: Float, b: Float): List<Pair<Muscle?, Rect>> =
    listOf(m to Rect(l, t, r, b), m to Rect(FIGURE_W - r, t, FIGURE_W - l, b))

private val COMMON_NEUTRAL: List<Pair<Muscle?, Rect>> =
    listOf<Pair<Muscle?, Rect>>(null to Rect(45f, 25f, 55f, 35f)) +
        pair(null, 13f, 112f, 24f, 124f) +
        pair(null, 35f, 159f, 48.5f, 166f) +
        pair(null, 34f, 205f, 48.5f, 214f)

private val FRONT_REGIONS: List<Pair<Muscle?, Rect>> = COMMON_NEUTRAL +
    listOf<Pair<Muscle?, Rect>>(null to Rect(36f, 100f, 64f, 113f)) +
    pair(null, 33f, 58f, 37f, 100f) +
    pair(Muscle.DELTS_FRONT, 20f, 36f, 33f, 52f) +
    pair(Muscle.DELTS_SIDE, 14f, 40f, 20f, 56f) +
    pair(Muscle.CHEST, 33f, 37f, 49.5f, 58f) +
    listOf<Pair<Muscle?, Rect>>(Muscle.ABS to Rect(37f, 60f, 63f, 99f)) +
    pair(Muscle.BICEPS, 16f, 54f, 27f, 80f) +
    pair(Muscle.FOREARMS, 14f, 82f, 25f, 110f) +
    pair(Muscle.QUADS, 35f, 114f, 49.5f, 158f) +
    pair(Muscle.CALVES, 37f, 168f, 47.5f, 203f)

private val BACK_REGIONS: List<Pair<Muscle?, Rect>> = COMMON_NEUTRAL +
    pair(null, 31f, 86f, 38f, 102f) +
    pair(Muscle.DELTS_REAR, 20f, 36f, 33f, 52f) +
    pair(Muscle.DELTS_SIDE, 14f, 40f, 20f, 56f) +
    listOf<Pair<Muscle?, Rect>>(Muscle.UPPER_BACK to Rect(35f, 34f, 65f, 57f)) +
    pair(Muscle.LATS, 31f, 59f, 49.5f, 85f) +
    listOf<Pair<Muscle?, Rect>>(Muscle.LOWER_BACK to Rect(39f, 87f, 61f, 102f)) +
    pair(Muscle.TRICEPS, 16f, 54f, 27f, 80f) +
    pair(Muscle.FOREARMS, 14f, 82f, 25f, 110f) +
    pair(Muscle.GLUTES, 35f, 104f, 49.5f, 125f) +
    pair(Muscle.HAMSTRINGS, 35f, 127f, 49.5f, 158f) +
    pair(Muscle.CALVES, 36f, 168f, 48.5f, 203f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechniqueSheet(exercise: ExerciseEntity, muscles: MuscleSet, animate: Boolean, onDismiss: () -> Unit) {
    val repo = koinInject<MediaRepository>()
    val userFiles by repo.userFiles.collectAsStateWithLifecycle()
    val technique = remember(exercise.techniqueJson) { Technique.parse(exercise.techniqueJson) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("technique_sheet")) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(exercise.nameRu, style = MaterialTheme.typography.headlineSmall)
            ExerciseAnimation(exercise.id, animate = animate, active = true, modifier = Modifier.fillMaxWidth().height(240.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MuscleMap(muscles, Modifier.height(170.dp).weight(1f))
                Column(Modifier.weight(1.2f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (muscles.primary.isNotEmpty()) {
                        Text(
                            stringResource(R.string.muscles_primary, muscleNames(muscles.primary)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (muscles.secondary.isNotEmpty()) {
                        Text(
                            stringResource(R.string.muscles_secondary, muscleNames(muscles.secondary)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (technique == null) {
                Text(stringResource(R.string.technique_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                Bullets(stringResource(R.string.technique_cues), technique.cues)
                Bullets(stringResource(R.string.technique_mistakes), technique.mistakes)
                Bullets(stringResource(R.string.technique_safety), technique.safety)
                if (!exercise.reviewed) {
                    Text(stringResource(R.string.technique_unreviewed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            val item = repo.manifest.items[exercise.id]
            val credit = when {
                userFiles.containsKey(exercise.id) -> stringResource(R.string.media_credit_user)
                item != null -> {
                    val source = repo.manifest.sources.firstOrNull { it.id == item.source }
                    stringResource(R.string.media_credit, source?.title ?: item.source, item.license, item.sourceId) +
                        if (item.match == MediaRepository.MATCH_SIMILAR) " " + stringResource(R.string.media_credit_similar) else ""
                }
                else -> null
            }
            credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun muscleNames(set: Set<Muscle>): String =
    set.sortedBy { it.ordinal }.map { stringResource(Format.muscle(it)) }.joinToString(", ")

@Composable
private fun Bullets(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
    }
}
