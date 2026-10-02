package app.podkhod.data.plan

import android.util.Log
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.catalog.CatalogValidator
import app.podkhod.domain.plan.GeneratorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generator parameters: the bundled asset, or a user-imported file in filesDir when it parses and passes
 * validation. A broken override never breaks the app: it is ignored and the asset is used.
 */
class GeneratorConfigStore(
    private val filesDir: File,
    private val catalog: CatalogRepository,
) {
    private val overrideFile get() = File(filesDir, FILE_NAME)

    @Volatile
    private var cached: GeneratorConfig? = null

    val isCustom: Boolean get() = overrideFile.exists()

    suspend fun config(): GeneratorConfig = cached ?: withContext(Dispatchers.IO) {
        val custom = overrideFile.takeIf { it.exists() }?.let { f ->
            runCatching { GeneratorConfig.parse(f.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "custom generator config ignored: ${it.message}") }
                .getOrNull()
                ?.takeIf { problems(it).isEmpty() }
        }
        (custom ?: GeneratorConfig.parse(catalog.generatorConfigAsset)).also { cached = it }
    }

    /** Current parameters as pretty JSON, for export. */
    suspend fun export(): String = GeneratorConfig.json.encodeToString(GeneratorConfig.serializer(), config())

    /** Returns problems in Russian; on an empty list the file is saved and used from now on. */
    suspend fun import(text: String): List<String> = withContext(Dispatchers.IO) {
        val parsed = runCatching { GeneratorConfig.parse(text) }.getOrElse {
            return@withContext listOf("Файл не читается как параметры генератора: ${it.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
        val problems = problems(parsed)
        if (problems.isEmpty()) {
            overrideFile.writeText(text, Charsets.UTF_8)
            cached = parsed
        }
        problems
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        overrideFile.delete()
        cached = null
    }

    private suspend fun problems(config: GeneratorConfig): List<String> =
        CatalogValidator.validateGeneratorConfig(config, catalog.exerciseInfos().keys)

    private companion object {
        const val FILE_NAME = "generator_config.json"
        const val TAG = "GeneratorConfig"
    }
}
