package app.podkhod.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/** The app's private storage: Context.filesDir on Android, Application Support on iOS. */
class AppDirs(val files: Path)

/** Blocking file helpers over kotlinx-io; callers switch to [Dispatchers.IO]. */
object Files {
    fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

    fun readText(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    fun readBytes(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    fun writeText(path: Path, text: String) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    fun writeBytes(path: Path, bytes: ByteArray) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    }

    fun delete(path: Path) = SystemFileSystem.delete(path, mustExist = false)

    fun mkdirs(path: Path) = SystemFileSystem.createDirectories(path)

    /** Regular files directly inside [dir]; empty when it does not exist. */
    fun list(dir: Path): List<Path> =
        if (!SystemFileSystem.exists(dir)) emptyList()
        else SystemFileSystem.list(dir).filter { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }
}

suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
