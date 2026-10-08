package app.podkhod

import android.app.Application
import app.podkhod.di.appModule
import app.podkhod.di.bootstrap
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
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform

class PodkhodApp : Application(), SingletonImageLoader.Factory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PodkhodApp)
            modules(androidModule, appModule)
        }
        WorkoutService.createChannels(this)
        appScope.launch { KoinPlatform.getKoin().bootstrap() }
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
