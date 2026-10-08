package app.podkhod.platform

import androidx.compose.runtime.Composable
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

/** Keeps the display on while this is in composition and [enabled]. */
@Composable
expect fun KeepScreenOn(enabled: Boolean)

/** Whether the rest alert can reach the user; [request] asks for permission where the system needs one. */
class NotificationAccess(val enabled: Boolean, val request: () -> Unit, val openSettings: () -> Unit)

@Composable
expect fun rememberNotificationAccess(): NotificationAccess

/** False when the user turned animations off system-wide; exercise animations then stay still. */
@Composable
expect fun systemAnimationsEnabled(): Boolean

/** Exercise media request; with [playable] false the file is decoded as a still frame. */
@Composable
expect fun exerciseImageRequest(model: String, playable: Boolean): ImageRequest

/** Starts or stops the loaded animated image. */
@Composable
expect fun AnimationControl(state: AsyncImagePainter.State, run: Boolean)

/** A text file the user is saving through the system dialog. */
fun interface DocumentWriter {
    suspend fun write(text: String)
}

/** A file the user opened through the system dialog. */
interface OpenedDocument {
    val name: String
    suspend fun readText(): String?
}

/** A folder the user picked; used to import their own exercise animations. */
interface PickedFolder {
    val name: String
    suspend fun files(): List<FolderEntry>
}

interface FolderEntry {
    val name: String

    /** -1 when the size is unknown. */
    val size: Long
    suspend fun readBytes(): ByteArray?
}

/** "Save as" dialog; the returned launcher takes the suggested file name. Cancel calls [onPicked] with null. */
@Composable
expect fun rememberCreateDocument(mimeType: String, onPicked: (DocumentWriter?) -> Unit): (suggestedName: String) -> Unit

/** "Open" dialog for JSON or plain text. */
@Composable
expect fun rememberOpenDocument(onPicked: (OpenedDocument?) -> Unit): () -> Unit

@Composable
expect fun rememberPickFolder(onPicked: (PickedFolder?) -> Unit): () -> Unit

/** Lets UI Automator find controls by test tag on Android; nothing elsewhere. */
expect fun androidx.compose.ui.Modifier.exposeTestTags(): androidx.compose.ui.Modifier
