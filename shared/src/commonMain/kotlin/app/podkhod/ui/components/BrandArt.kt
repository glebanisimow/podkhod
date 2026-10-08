package app.podkhod.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun glyphPath(data: String): Path =
    PathParser().parsePathString(data).toPath().apply { fillType = PathFillType.EvenOdd }

/** Draws a 100×100 glyph path into a [size] box whose top-left corner is at [topLeft]. */
private fun DrawScope.glyph(path: Path, topLeft: Offset, size: Float, color: Color, degrees: Float = 0f) {
    translate(topLeft.x, topLeft.y) {
        rotate(degrees, pivot = Offset(size / 2, size / 2)) {
            scale(size / GLYPH_BOX, pivot = Offset.Zero) { drawPath(path, color) }
        }
    }
}

/**
 * Sets of one exercise as claw scratches: one stroke per set, amber when done, dim while ahead. Sets over
 * the plan add strokes, so the row also shows an extra set at a glance.
 */
@Composable
fun ClawMarks(planned: Int, done: Int, modifier: Modifier = Modifier, height: Dp = 44.dp, description: String? = null) {
    val claw = remember { glyphPath(BrandGlyphs.claws[CLAW_INDEX]) }
    val count = maxOf(planned, done).coerceAtLeast(1)
    val doneColor = MaterialTheme.colorScheme.primary
    val aheadColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Canvas(modifier.then(semantics).size(width = height * CLAW_STEP * count, height = height)) {
        val box = size.height
        repeat(count) { i ->
            // The glyph box is square and the claw tall and thin, so neighbours overlap their boxes.
            glyph(claw, Offset(i * box * CLAW_STEP - box * (1 - CLAW_STEP) / 2, 0f), box, if (i < done) doneColor else aheadColor)
        }
    }
}

/** The "зубы" zig-zag as a thin accent line under screen headers. */
@Composable
fun ZigzagLine(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val tooth = 10.dp.toPx()
        val stroke = 2.dp.toPx()
        val top = stroke
        val bottom = size.height - stroke
        val path = Path().apply {
            moveTo(0f, bottom)
            var x = 0f
            var up = true
            while (x < size.width) {
                x += tooth
                lineTo(x, if (up) top else bottom)
                up = !up
            }
        }
        drawPath(path, color, style = Stroke(width = stroke, join = StrokeJoin.Miter))
    }
}

/** Bear tracks walking across the area, faint: a backdrop for empty screens. */
@Composable
fun PawTrail(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)) {
    val paw = remember { glyphPath(BrandGlyphs.PAW) }
    Canvas(modifier) {
        val step = 64.dp.toPx()
        val pawSize = 30.dp.toPx()
        var row = 0
        var y = 0f
        while (y < size.height) {
            // Left and right prints alternate along each diagonal track, like a walking bear.
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < size.width) {
                glyph(paw, Offset(x, y), pawSize, color, degrees = if (row % 2 == 0) -18f else 18f)
                x += step
            }
            y += step * 0.6f
            row++
        }
    }
}

/** Claw scratches tiled faintly, behind the summary badge. */
@Composable
fun ClawPattern(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
    val claws = remember { BrandGlyphs.claws.map(::glyphPath) }
    Canvas(modifier) {
        val clawSize = 56.dp.toPx()
        val markWidth = clawSize * (CLAW_STEP * (claws.size - 1) + 1)
        val stepX = markWidth + 24.dp.toPx()
        val stepY = clawSize * 0.9f
        var row = 0
        var y = -clawSize / 3
        while (y < size.height) {
            // One scratch mark is the four strokes side by side; rows are staggered by half a mark.
            var x = if (row % 2 == 0) -markWidth / 3 else stepX / 2 - markWidth / 3
            while (x < size.width) {
                claws.forEachIndexed { i, claw -> glyph(claw, Offset(x + i * clawSize * CLAW_STEP, y), clawSize, color) }
                x += stepX
            }
            y += stepY
            row++
        }
    }
}

/** The bear paw as a small mark, for personal records. */
@Composable
fun PawMark(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val paw = remember { glyphPath(BrandGlyphs.PAW) }
    Canvas(modifier.size(size)) { glyph(paw, Offset.Zero, this.size.minDimension, color) }
}

private const val GLYPH_BOX = 100f
// The longest of the four strokes reads best as a single tally mark.
private const val CLAW_INDEX = 2
// Horizontal step between tally marks, as a share of their height.
private const val CLAW_STEP = 0.5f
