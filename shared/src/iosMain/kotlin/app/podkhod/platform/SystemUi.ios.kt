package app.podkhod.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

@Composable
actual fun rememberNotificationAccess(): NotificationAccess {
    var enabled by remember { mutableStateOf(true) }
    var undecided by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            dispatch_async(dispatch_get_main_queue()) {
                enabled = status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional
                undecided = status == UNAuthorizationStatusNotDetermined
            }
        }
    }
    return NotificationAccess(
        enabled = enabled,
        request = {
            // iOS asks only once; after a refusal the switch lives in Settings.
            if (undecided) {
                UNUserNotificationCenter.currentNotificationCenter()
                    .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
                        dispatch_async(dispatch_get_main_queue()) {
                            enabled = granted
                            undecided = false
                        }
                    }
            }
        },
        openSettings = {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
            }
        },
    )
}

@Composable
actual fun systemAnimationsEnabled(): Boolean = !UIAccessibilityIsReduceMotionEnabled()

// Coil decodes WebP/GIF on iOS through Skia as a still first frame; playing them needs a frame decoder.
@Composable
actual fun exerciseImageRequest(model: String, playable: Boolean): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(model) { ImageRequest.Builder(context).data(model).build() }
}

@Composable
actual fun AnimationControl(state: AsyncImagePainter.State, run: Boolean) = Unit

actual fun Modifier.exposeTestTags(): Modifier = this

@Composable
actual fun rememberCreateDocument(mimeType: String, onPicked: (DocumentWriter?) -> Unit): (suggestedName: String) -> Unit {
    val delegate = remember { PickerDelegate() }
    return { name ->
        // iOS exports an existing file: write the text to a temporary file first, then let the user move it.
        val writer = PendingExport(Path(NSTemporaryDirectory(), name))
        writer.onWritten = { url ->
            delegate.onPicked = { _ -> }
            present(UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true), delegate)
        }
        onPicked(writer)
    }
}

@Composable
actual fun rememberOpenDocument(onPicked: (OpenedDocument?) -> Unit): () -> Unit {
    val delegate = remember { PickerDelegate() }
    return {
        delegate.onPicked = { url -> onPicked(url?.let { UrlDocument(it) }) }
        present(UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON, UTTypePlainText), asCopy = true), delegate)
    }
}

@Composable
actual fun rememberPickFolder(onPicked: (PickedFolder?) -> Unit): () -> Unit {
    val delegate = remember { PickerDelegate() }
    return {
        delegate.onPicked = { url -> onPicked(url?.let { UrlFolder(it) }) }
        present(UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder)), delegate)
    }
}

private fun present(picker: UIDocumentPickerViewController, delegate: PickerDelegate) {
    picker.delegate = delegate
    topController()?.presentViewController(picker, animated = true, completion = null)
}

private fun topController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

private class PickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
    var onPicked: (NSURL?) -> Unit = {}

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(null)
    }
}

/** Text goes to a temporary file; the export dialog then copies it where the user chooses. */
private class PendingExport(private val path: Path) : DocumentWriter {
    var onWritten: (NSURL) -> Unit = {}

    override suspend fun write(text: String) {
        withContext(Dispatchers.IO) { Files.writeText(path, text) }
        val url = NSURL.fileURLWithPath(path.toString())
        withContext(Dispatchers.Main) { onWritten(url) }
    }
}

private class UrlDocument(private val url: NSURL) : OpenedDocument {
    override val name: String get() = url.lastPathComponent.orEmpty()

    override suspend fun readText(): String? = withContext(Dispatchers.IO) {
        scoped(url) { url.path?.let { runCatching { Files.readText(Path(it)) }.getOrNull() } }
    }
}

private class UrlFolder(private val url: NSURL) : PickedFolder {
    override val name: String get() = url.lastPathComponent.orEmpty()

    override suspend fun files(): List<FolderEntry> = withContext(Dispatchers.IO) {
        scoped(url) {
            val dir = url.path?.let { Path(it) } ?: return@scoped emptyList()
            Files.list(dir).map { p ->
                // Read now: access to the folder ends when this scope closes.
                val bytes = runCatching { SystemFileSystem.source(p).buffered().use { it.readByteArray() } }.getOrNull()
                BytesEntry(p.name, bytes?.size?.toLong() ?: -1L, bytes)
            }
        }
    }
}

private class BytesEntry(override val name: String, override val size: Long, private val bytes: ByteArray?) : FolderEntry {
    override suspend fun readBytes(): ByteArray? = bytes
}

/** Files outside the sandbox are readable only between start/stopAccessingSecurityScopedResource. */
private inline fun <T> scoped(url: NSURL, block: () -> T): T {
    val granted = url.startAccessingSecurityScopedResource()
    try {
        return block()
    } finally {
        if (granted) url.stopAccessingSecurityScopedResource()
    }
}
