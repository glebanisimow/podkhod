package app.podkhod.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

enum class UpdateProblem {
    NETWORK, NOT_PUBLISHED, RATE_LIMIT, INVALID_APK, INCOMPATIBLE, STORAGE,
    ACTIVE_WORKOUT, INSTALL_PERMISSION, INSTALL_FAILED,
}

class UpdateException(val problem: UpdateProblem) : IOException(problem.name)

/** No GitHub credentials are needed or stored: the release is public. */
interface UpdateSource {
    suspend fun check(): UpdateManifest
    suspend fun download(manifest: UpdateManifest, onProgress: (Int) -> Unit): File
    suspend fun validateForInstall(apk: File, manifest: UpdateManifest)
}

class UpdateRepository(private val context: Context) : UpdateSource {
    override suspend fun check(): UpdateManifest = withContext(Dispatchers.IO) {
        request(UpdateManifest.URL) { connection ->
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 64 * 1024) throw UpdateException(UpdateProblem.INVALID_APK)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val manifest = try {
                UpdateManifest.parse(bytes.toString(Charsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                throw UpdateException(UpdateProblem.INVALID_APK)
            }
            if (manifest.minSdk > Build.VERSION.SDK_INT) throw UpdateException(UpdateProblem.INCOMPATIBLE)
            manifest
        }
    }

    override suspend fun download(manifest: UpdateManifest, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        manifest.validate()
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // This directory contains only updater-owned files. Never touch user backups or application data.
        dir.listFiles()?.forEach { it.delete() }
        if (dir.usableSpace < manifest.sizeBytes + 10 * 1024 * 1024) throw UpdateException(UpdateProblem.STORAGE)
        val partial = File(dir, "update.part")
        val apk = File(dir, "podkhod-${manifest.versionCode}.apk")
        try {
            val coroutine = currentCoroutineContext()
            request(manifest.apkUrl) { connection ->
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastProgress = -1
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > manifest.sizeBytes) throw UpdateException(UpdateProblem.INVALID_APK)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val progress = (total * 100 / manifest.sizeBytes).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != manifest.sizeBytes || hash != manifest.sha256) throw UpdateException(UpdateProblem.INVALID_APK)
            }
            validatePackage(partial, manifest)
            if (!partial.renameTo(apk)) throw UpdateException(UpdateProblem.STORAGE)
            apk
        } finally {
            partial.delete()
        }
    }

    override suspend fun validateForInstall(apk: File, manifest: UpdateManifest) = withContext(Dispatchers.IO) {
        if (!apk.isFile || apk.length() != manifest.sizeBytes) throw UpdateException(UpdateProblem.INVALID_APK)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != manifest.sha256) {
            throw UpdateException(UpdateProblem.INVALID_APK)
        }
        validatePackage(apk, manifest)
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(apk: File, manifest: UpdateManifest) {
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateException(UpdateProblem.INVALID_APK)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val expectedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().sha256() }?.toSet()
        val actualSigners = candidate.signingInfo?.apkContentsSigners?.map { it.toByteArray().sha256() }?.toSet()
        if (candidate.packageName != context.packageName || candidate.longVersionCode != manifest.versionCode ||
            candidate.longVersionCode <= installed.longVersionCode || candidate.versionName != manifest.versionName ||
            expectedSigners.isNullOrEmpty() || actualSigners != expectedSigners
        ) throw UpdateException(UpdateProblem.INVALID_APK)
    }

    private fun <T> request(address: String, block: (HttpURLConnection) -> T): T {
        var url = URL(address)
        // GitHub redirects release assets to its CDN. Accept HTTPS redirects only; bound the chain.
        repeat(6) {
            val connection = url.openConnection() as? HttpsURLConnection
                ?: throw UpdateException(UpdateProblem.INVALID_APK)
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Podkhod-Android-Updater")
                connection.setRequestProperty("Cache-Control", "no-cache")
                when (connection.responseCode) {
                    200 -> return block(connection)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw UpdateException(UpdateProblem.NETWORK)
                        url = URL(url, location)
                        if (url.protocol != "https") throw UpdateException(UpdateProblem.INVALID_APK)
                    }
                    404 -> throw UpdateException(UpdateProblem.NOT_PUBLISHED)
                    403, 429 -> throw UpdateException(UpdateProblem.RATE_LIMIT)
                    else -> throw UpdateException(UpdateProblem.NETWORK)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateException(UpdateProblem.NETWORK)
    }
}
