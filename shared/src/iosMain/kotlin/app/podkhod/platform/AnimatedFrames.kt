package app.podkhod.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntSize
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy
import kotlin.math.roundToInt

/**
 * Animated WebP/GIF for iOS, where Coil only shows the first frame. Until the frames are decoded (and when
 * the file is not animated or fails to decode) Coil's still [fallback] is shown. Frames are decoded on the
 * first run only, so lists of thumbnails that never play keep just the still; a 12-frame 360×240 clip
 * takes about 4 MB while it is on screen.
 */
@Composable
internal fun rememberFramesPainter(model: String, fallback: Painter, playable: Boolean, run: Boolean): Painter {
    var wanted by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, playable, run) { if (playable && run) wanted = true }
    val frames by produceState<Frames?>(null, model, playable && wanted) {
        value = if (playable && wanted) withContext(Dispatchers.Default) { decodeFrames(model) } else null
    }
    val clip = frames ?: return fallback
    val index = remember(clip) { mutableIntStateOf(0) }
    LaunchedEffect(clip, run) {
        while (run) {
            delay(clip.durations[index.intValue])
            index.intValue = (index.intValue + 1) % clip.images.size
        }
    }
    return remember(clip) { FramesPainter(clip.images, index) }
}

private class Frames(val images: List<ImageBitmap>, val durations: List<Long>)

/** Draws the current frame; only the draw phase reads [index], so a new frame does not recompose the page. */
private class FramesPainter(private val images: List<ImageBitmap>, private val index: State<Int>) : Painter() {
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize = Size(images[0].width.toFloat(), images[0].height.toFloat())

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        drawImage(
            images[index.value],
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun decodeFrames(model: String): Frames? = try {
    readBytes(model)?.let { bytes ->
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        try {
            if (codec.frameCount < 2) return null
            val durations = codec.framesInfo.map { it.duration.toLong().takeIf { ms -> ms > 0 } ?: DEFAULT_FRAME_MS }
            val images = List(codec.frameCount) { frame ->
                val bitmap = Bitmap()
                bitmap.allocPixels(codec.imageInfo)
                codec.readPixels(bitmap, frame)
                bitmap.setImmutable()
                bitmap.asComposeImageBitmap()
            }
            Frames(images, durations)
        } finally {
            codec.close()
        }
    }
} catch (e: Exception) {
    Log.w(TAG, "cannot decode animation $model", e)
    null
}

/** [model] is a file:// URI: bundled media from compose resources or a file the user imported. */
@OptIn(ExperimentalForeignApi::class)
private fun readBytes(model: String): ByteArray? {
    val url = NSURL.URLWithString(model) ?: return null
    val data = NSData.dataWithContentsOfURL(url) ?: return null
    val size = data.length.toInt()
    if (size == 0) return null
    return ByteArray(size).apply { usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
}

private const val TAG = "AnimatedFrames"

/** Browsers treat a zero frame delay as 100 ms; so do we. */
private const val DEFAULT_FRAME_MS = 100L
