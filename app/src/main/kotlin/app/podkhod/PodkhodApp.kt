package app.podkhod

import android.app.Application
import android.util.Log
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppMetaRepository
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.program.ProgramRepository
import app.podkhod.di.appModule
import app.podkhod.service.WorkoutService
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PodkhodApp : Application(), SingletonImageLoader.Factory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val appMeta: AppMetaRepository by inject()
    private val catalog: CatalogRepository by inject()
    private val programs: ProgramRepository by inject()
    private val plans: PlanRepository by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PodkhodApp)
            modules(appModule)
        }
        WorkoutService.createChannels(this)
        appScope.launch {
            runCatching {
                appMeta.recordFirstLaunchIfMissing()
                catalog.seedIfNeeded()
                programs.ensureDefaultProgram()
                plans.applyMinSets()
            }.onFailure { Log.e("PodkhodApp", "bootstrap failed", it) }
        }
    }

    // Media is bundled or imported into app storage, so there is no disk cache; the memory cache is kept
    // small for 3 GB phones. Animated WebP/GIF decode through ImageDecoder (API 28+).
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(AnimatedImageDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.1).build() }
            .diskCache(null)
            .build()
}
