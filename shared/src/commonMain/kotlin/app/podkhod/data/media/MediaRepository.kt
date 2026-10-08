package app.podkhod.data.media

import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.catalog.CatalogJsonFormat
import app.podkhod.platform.AppDirs
import app.podkhod.platform.Files
import app.podkhod.platform.PickedFolder
import app.podkhod.platform.io
import app.podkhod.resources.Res
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.ExperimentalResourceApi

@Serializable
data class MediaManifest(
    val manifestVersion: Int,
    val sources: List<MediaSource>,
    val items: Map<String, MediaItem>,
) {
    companion object {
        val EMPTY = MediaManifest(0, emptyList(), emptyMap())
    }
}

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

class MediaRepository(dirs: AppDirs, private val assets: AssetReader) {

    private val userDir = Path(dirs.files, USER_DIR)

    private val _manifest = MutableStateFlow(MediaManifest.EMPTY)

    /** Empty until [load] has read the bundled manifest at startup. */
    val manifest: StateFlow<MediaManifest> = _manifest.asStateFlow()

    private val _userFiles = MutableStateFlow<Map<String, Path>>(emptyMap())

    /** Exercise id -> user-provided file; overrides bundled media. */
    val userFiles: StateFlow<Map<String, Path>> = _userFiles.asStateFlow()

    suspend fun load() {
        _manifest.value = CatalogJsonFormat.decodeFromString(MediaManifest.serializer(), assets.read(MANIFEST_ASSET))
        _userFiles.value = io { scanUserDir() }
    }

    @OptIn(ExperimentalResourceApi::class)
    fun media(
        exerciseId: String,
        userFiles: Map<String, Path> = _userFiles.value,
        manifest: MediaManifest = _manifest.value,
    ): ExerciseMedia? {
        userFiles[exerciseId]?.let { return ExerciseMedia("file://$it", similar = false, userProvided = true) }
        val item = manifest.items[exerciseId] ?: return null
        return ExerciseMedia(Res.getUri("files/" + item.file), similar = item.match == MATCH_SIMILAR, userProvided = false)
    }

    /**
     * Copies `<exercise id>.gif|.webp` files from a folder the user picked.
     * Files with unknown ids, other extensions, wrong signatures or over [MAX_USER_FILE_BYTES] are skipped.
     */
    suspend fun importFolder(folder: PickedFolder, knownIds: Set<String>): ImportResult {
        io { Files.mkdirs(userDir) }
        var imported = 0
        val skipped = mutableListOf<String>()
        folder.files().forEach { entry ->
            val name = entry.name
            val ext = name.substringAfterLast('.', "").lowercase()
            val id = name.substringBeforeLast('.').lowercase()
            if (ext !in USER_EXTENSIONS || id !in knownIds || entry.size > MAX_USER_FILE_BYTES) {
                skipped += name
                return@forEach
            }
            val bytes = entry.readBytes()
            if (bytes == null || bytes.size > MAX_USER_FILE_BYTES || !hasImageSignature(bytes, ext)) {
                skipped += name
                return@forEach
            }
            io {
                USER_EXTENSIONS.forEach { Files.delete(Path(userDir, "$id.$it")) }
                Files.writeBytes(Path(userDir, "$id.$ext"), bytes)
            }
            imported++
        }
        _userFiles.value = io { scanUserDir() }
        return ImportResult(imported, skipped)
    }

    suspend fun clearUserMedia() {
        io { Files.list(userDir).forEach { Files.delete(it) } }
        _userFiles.value = emptyMap()
    }

    private fun scanUserDir(): Map<String, Path> =
        Files.list(userDir)
            .filter { it.name.substringAfterLast('.', "").lowercase() in USER_EXTENSIONS }
            .associateBy { it.name.substringBeforeLast('.') }

    companion object {
        const val MANIFEST_ASSET = "media_manifest.json"
        const val MATCH_SIMILAR = "similar"
        const val USER_DIR = "media_user"
        const val MAX_USER_FILE_BYTES = 5L * 1024 * 1024
        val USER_EXTENSIONS = setOf("gif", "webp")

        fun hasImageSignature(bytes: ByteArray, ext: String): Boolean = when (ext) {
            "gif" -> bytes.size >= 6 && bytes.ascii(0, 4) == "GIF8"
            "webp" -> bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP"
            else -> false
        }

        private fun ByteArray.ascii(from: Int, length: Int): String = decodeToString(from, from + length)
    }
}
