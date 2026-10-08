package app.podkhod.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> enabled = granted }
    return NotificationAccess(
        enabled = enabled,
        request = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !enabled) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

@Composable
actual fun systemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

@Composable
actual fun exerciseImageRequest(model: String, playable: Boolean): ImageRequest {
    val context = LocalContext.current
    return remember(model, playable) {
        ImageRequest.Builder(context).data(model).apply {
            if (!playable) {
                decoderFactory(BitmapFactoryDecoder.Factory())
                memoryCacheKeyExtra(STILL_KEY, "1")
            }
        }.build()
    }
}

@Composable
actual fun AnimationControl(state: AsyncImagePainter.State, run: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(state, run) {
        val drawable = (state as? AsyncImagePainter.State.Success)?.result?.image?.asDrawable(context.resources)
        (drawable as? Animatable)?.let { if (run) it.start() else it.stop() }
    }
}

@Composable
actual fun rememberCreateDocument(mimeType: String, onPicked: (DocumentWriter?) -> Unit): (suggestedName: String) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        onPicked(uri?.let { UriWriter(context, it) })
    }
    return { name -> launcher.launch(name) }
}

@Composable
actual fun rememberOpenDocument(onPicked: (OpenedDocument?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPicked(uri?.let { UriDocument(context, it) })
    }
    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
}

@Composable
actual fun rememberPickFolder(onPicked: (PickedFolder?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onPicked(uri?.let { TreeFolder(context, it) })
    }
    return { launcher.launch(null) }
}

private const val STILL_KEY = "still"

private class UriWriter(private val context: Context, private val uri: Uri) : DocumentWriter {
    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { uri.toString() }
            .use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

private class UriDocument(private val context: Context, private val uri: Uri) : OpenedDocument {
    override val name: String get() = uri.lastPathSegment.orEmpty()

    override suspend fun readText(): String? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }
}

/** A folder picked with ACTION_OPEN_DOCUMENT_TREE. */
private class TreeFolder(private val context: Context, private val treeUri: Uri) : PickedFolder {
    override val name: String get() = treeUri.lastPathSegment.orEmpty()

    override suspend fun files(): List<FolderEntry> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        buildList {
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val size = if (c.isNull(2)) -1L else c.getLong(2)
                    add(TreeEntry(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), name, size))
                }
            }
        }
    }
}

private class TreeEntry(private val context: Context, private val uri: Uri, override val name: String, override val size: Long) : FolderEntry {
    override suspend fun readBytes(): ByteArray? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
}

actual fun androidx.compose.ui.Modifier.exposeTestTags(): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.semantics { testTagsAsResourceId = true })
