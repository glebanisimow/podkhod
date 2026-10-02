package app.podkhod.data.media

import app.podkhod.data.catalog.CatalogFile
import app.podkhod.data.catalog.CatalogJsonFormat
import app.podkhod.data.catalog.CatalogValidator
import app.podkhod.data.catalog.TechniqueFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Stage 3 acceptance on the bundled assets: coverage, size budget, licenses and technique texts. */
class MediaAssetsTest {

    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
    private val catalog = CatalogJsonFormat.decodeFromString(CatalogFile.serializer(), File(assets, "catalog.json").readText())
    private val manifest = CatalogJsonFormat.decodeFromString(MediaManifest.serializer(), File(assets, "media_manifest.json").readText())
    private val technique = CatalogJsonFormat.decodeFromString(TechniqueFile.serializer(), File(assets, "technique.json").readText())
    private val ids = catalog.exercises.map { it.id }.toSet()

    @Test
    fun atLeastNinetyPercentOfExercisesHaveMedia() {
        val covered = ids.count { it in manifest.items }
        assertTrue("covered $covered of ${ids.size}", covered * 10 >= ids.size * 9)
    }

    @Test
    fun manifestReferencesOnlyCatalogExercisesAndKnownSources() {
        assertEquals(emptySet<String>(), manifest.items.keys - ids)
        val sources = manifest.sources.associateBy { it.id }
        manifest.items.forEach { (id, item) ->
            val source = sources[item.source]
            assertTrue("$id: unknown source ${item.source}", source != null)
            assertEquals("$id: license differs from source", source!!.license, item.license)
            assertTrue("$id: bad match ${item.match}", item.match == "exact" || item.match == "similar")
        }
        manifest.sources.forEach {
            assertFalse("${it.id}: empty license", it.license.isBlank())
            assertTrue("${it.id}: license url", it.licenseUrl.startsWith("https://"))
        }
    }

    @Test
    fun mediaFilesExistMatchHashAndFitBudget() {
        val files = manifest.items.values.map { it.file to it.sha256 }.distinct()
        var total = 0L
        files.forEach { (path, sha) ->
            val f = File(assets, path)
            assertTrue("$path missing", f.exists())
            val bytes = f.readBytes()
            total += bytes.size
            assertTrue("$path is ${bytes.size / 1024} KB", bytes.size <= 150 * 1024)
            assertTrue("$path is not WebP", MediaRepository.hasImageSignature(bytes, "webp"))
            assertEquals("$path hash", sha, sha256(bytes))
            val (w, h) = webpCanvas(bytes)
            assertTrue("$path is ${w}x$h", maxOf(w, h) <= 360)
        }
        assertTrue("media total ${total / 1024 / 1024} MB", total < 40L * 1024 * 1024)
        val orphans = File(assets, "media").listFiles().orEmpty().map { "media/${it.name}" } - files.map { it.first }.toSet()
        assertEquals("files not in manifest", emptyList<String>(), orphans)
    }

    @Test
    fun everyExerciseHasTechnique() {
        assertEquals(emptyList<String>(), CatalogValidator.validateTechnique(technique, ids))
    }

    @Test
    fun signatureCheckRejectsWrongFiles() {
        assertTrue(MediaRepository.hasImageSignature("GIF89a....".toByteArray(), "gif"))
        assertFalse(MediaRepository.hasImageSignature("GIF89a....".toByteArray(), "webp"))
        assertFalse(MediaRepository.hasImageSignature("\u0089PNG\r\n\u001a\n....".toByteArray(), "gif"))
        assertFalse(MediaRepository.hasImageSignature(ByteArray(3), "webp"))
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Animated WebP always starts with a VP8X chunk holding the 24-bit canvas size minus one. */
    private fun webpCanvas(b: ByteArray): Pair<Int, Int> {
        assertEquals("VP8X", String(b, 12, 4, Charsets.US_ASCII))
        fun u24(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16)
        return (u24(24) + 1) to (u24(27) + 1)
    }
}
