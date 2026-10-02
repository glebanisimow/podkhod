package app.podkhod.data.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.catalog.CatalogJsonFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class MediaManifest(
    val manifestVersion: Int,
    val sources: List<MediaSource>,
    val items: Map<String, MediaItem>,
)

@Serializable
data class MediaSource(
    val id: String,
    val title: String,
    val url: String,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val commit: String? = null,
)

@Serializable
data class MediaItem(
    val file: String,
    val source: String,
    val sourceId: String,
    /** "exact" or "similar": similar items show a close movement and are labelled as such. */
    val match: String,
    val license: String,
    val author: String,
    val sha256: String? = null,
)

/** What to show for one exercise. [model] is a URI string Coil can load. */
data class ExerciseMedia(val model: String, val similar: Boolean, val userProvided: Boolean)

data class ImportResult(val imported: Int, val skipped: List<String>)

class MediaRepository(context: Context, private val assets: AssetReader) {

    private val userDir = File(context.filesDir, USER_DIR)
    private val resolver = context.contentResolver

    val manifest: MediaManifest by lazy {
        CatalogJsonFormat.decodeFromString(MediaManifest.serializer(), assets.read(MANIFEST_ASSET))
    }

    private val _userFiles = MutableStateFlow(scanUserDir())

    /** Exercise id -> user-provided file; overrides bundled media. */
    val userFiles: StateFlow<Map<String, File>> = _userFiles.asStateFlow()

    fun media(exerciseId: String, userFiles: Map<String, File> = _userFiles.value): ExerciseMedia? {
        userFiles[exerciseId]?.let { return ExerciseMedia(Uri.fromFile(it).toString(), similar = false, userProvided = true) }
        val item = manifest.items[exerciseId] ?: return null
        return ExerciseMedia(ASSET_PREFIX + item.file, similar = item.match == MATCH_SIMILAR, userProvided = false)
    }

    /**
     * Copies `<exercise id>.gif|.webp` files from a folder picked with ACTION_OPEN_DOCUMENT_TREE.
     * Files with unknown ids, other extensions, wrong signatures or over [MAX_USER_FILE_BYTES] are skipped.
     */
    suspend fun importFolder(treeUri: Uri, knownIds: Set<String>): ImportResult = withContext(Dispatchers.IO) {
        userDir.mkdirs()
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        var imported = 0
        val skipped = mutableListOf<String>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1) ?: continue
                val size = if (c.isNull(2)) -1L else c.getLong(2)
                val ext = name.substringAfterLast('.', "").lowercase()
                val id = name.substringBeforeLast('.').lowercase()
                if (ext !in USER_EXTENSIONS || id !in knownIds || size > MAX_USER_FILE_BYTES) {
                    skipped += name
                    continue
                }
                val bytes = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                    ?.use { it.readBytes() }
                if (bytes == null || bytes.size > MAX_USER_FILE_BYTES || !hasImageSignature(bytes, ext)) {
                    skipped += name
                    continue
                }
                USER_EXTENSIONS.forEach { File(userDir, "$id.$it").delete() }
                File(userDir, "$id.$ext").writeBytes(bytes)
                imported++
            }
        }
        _userFiles.value = scanUserDir()
        ImportResult(imported, skipped)
    }

    suspend fun clearUserMedia() = withContext(Dispatchers.IO) {
        userDir.listFiles()?.forEach { it.delete() }
        _userFiles.value = emptyMap()
    }

    private fun scanUserDir(): Map<String, File> =
        userDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in USER_EXTENSIONS }
            ?.associateBy { it.nameWithoutExtension }
            .orEmpty()

    companion object {
        const val MANIFEST_ASSET = "media_manifest.json"
        const val ASSET_PREFIX = "file:///android_asset/"
        const val MATCH_SIMILAR = "similar"
        const val USER_DIR = "media_user"
        const val MAX_USER_FILE_BYTES = 5L * 1024 * 1024
        val USER_EXTENSIONS = setOf("gif", "webp")

        fun hasImageSignature(bytes: ByteArray, ext: String): Boolean = when (ext) {
            "gif" -> bytes.size >= 6 && String(bytes, 0, 4, Charsets.US_ASCII) == "GIF8"
            "webp" -> bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            else -> false
        }
    }
}
