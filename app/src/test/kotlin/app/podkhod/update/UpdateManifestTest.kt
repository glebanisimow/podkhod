package app.podkhod.update

import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val manifest = UpdateManifest(
        versionCode = 420_001, versionName = "0.6.0", applicationId = "app.podkhod", minSdk = 29,
        apkUrl = "https://github.com/glebanisimow/podkhod/releases/download/android/podkhod-420001-123-1.apk",
        sha256 = "a".repeat(64), sizeBytes = 10_000_000,
    )

    @Test fun comparesBuildNumbersEvenWhenVersionNameIsUnchanged() {
        assertTrue(manifest.isNewerThan(420_000))
        assertFalse(manifest.isNewerThan(420_001))
        assertFalse(manifest.isNewerThan(420_002))
    }

    @Test fun rejectsUntrustedAndMutableDownloadAddresses() {
        val invalid = listOf(
            manifest.apkUrl.replace("https:", "http:"),
            manifest.apkUrl.replace("github.com/", "github.com.evil.test/"),
            manifest.apkUrl.replace("glebanisimow", "someone-else"),
            manifest.apkUrl.replace("podkhod-420001-123-1.apk", "podkhod-latest.apk"),
            manifest.apkUrl + "?redirect=https://evil.test",
            manifest.apkUrl.replace("github.com/", "github.com:443/"),
            manifest.apkUrl.replace("/android/", "/android/../android/"),
        )
        invalid.forEach { url -> assertThrows(IllegalArgumentException::class.java) { manifest.copy(apkUrl = url).validate() } }
    }

    @Test fun rejectsWrongPackageCorruptHashAndUnboundedDownload() {
        listOf(
            manifest.copy(applicationId = "app.podkhod.debug"),
            manifest.copy(sha256 = "bad"),
            manifest.copy(sizeBytes = 0),
            manifest.copy(sizeBytes = UpdateManifest.MAX_APK_BYTES + 1),
            manifest.copy(versionCode = -1),
        ).forEach { value -> assertThrows(IllegalArgumentException::class.java) { value.validate() } }
    }

    @Test fun acceptsPublishedManifestWithFutureFields() {
        val parsed = UpdateManifest.parse(
            """{"versionCode":420001,"versionName":"0.6.0","applicationId":"app.podkhod","minSdk":29,
                "apkUrl":"${manifest.apkUrl}","sha256":"${manifest.sha256}","sizeBytes":10000000,"future":"ignored"}""",
        )
        assertEquals(manifest, parsed)
    }
}
