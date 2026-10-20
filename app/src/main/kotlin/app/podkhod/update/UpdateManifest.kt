package app.podkhod.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest

/** Published only after the APK has been uploaded, with a different APK name for every build. */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val applicationId: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    fun validate(): UpdateManifest = apply {
        require(versionCode > 0 && versionName.isNotBlank() && versionName.length <= 80)
        require(applicationId == "app.podkhod" && minSdk >= 29)
        require(sizeBytes in 1..MAX_APK_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val uri = URI(apkUrl)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1)
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
        require(uri.rawPath.matches(Regex("/glebanisimow/podkhod/releases/download/android/podkhod-[0-9]+-[0-9]+-[0-9]+\\.apk")))
    }

    fun isNewerThan(installedCode: Long): Boolean = versionCode > installedCode

    companion object {
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val URL = "https://github.com/glebanisimow/podkhod/releases/download/android/update.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): UpdateManifest = json.decodeFromString<UpdateManifest>(text).validate()
    }
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
