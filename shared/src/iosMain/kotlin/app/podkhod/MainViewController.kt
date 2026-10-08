package app.podkhod

import androidx.compose.ui.window.ComposeUIViewController
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.podkhod.data.db.AppDatabase
import app.podkhod.di.appModule
import app.podkhod.di.bootstrap
import app.podkhod.platform.AppDirs
import app.podkhod.platform.AppInfo
import app.podkhod.platform.Files
import app.podkhod.platform.HoldSignals
import app.podkhod.platform.WorkoutTracker
import app.podkhod.ui.PodkhodRoot
import app.podkhod.ui.theme.PodkhodTheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import okio.Path.Companion.toPath
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Entry point for the Swift app: `MainViewControllerKt.MainViewController()`. */
fun MainViewController(): UIViewController {
    startApp()
    return ComposeUIViewController {
        PodkhodTheme {
            PodkhodRoot()
        }
    }
}

private var started = false

private fun startApp() {
    if (started) return
    started = true
    val files = applicationSupportDir()
    startKoin { modules(iosModule(files), appModule) }
    appScope.launch(Dispatchers.IO) { KoinPlatform.getKoin().bootstrap() }
}

private fun iosModule(files: Path) = module {
    single<AppDatabase> {
        Room.databaseBuilder<AppDatabase>(name = Path(files, AppDatabase.NAME).toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
    }
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.createWithPath { Path(files, "datastore", "settings.preferences_pb").toString().toPath() }
    }
    single { AppDirs(files) }
    single {
        val info = NSBundle.mainBundle.infoDictionary
        AppInfo(
            versionName = info?.get("CFBundleShortVersionString") as? String ?: "?",
            build = info?.get("CFBundleVersion") as? String ?: "?",
        )
    }
    single<HoldSignals> { IosHoldSignals() }
    single<WorkoutTracker> { IosWorkoutTracker(get(), get(), appScope) }
}

@OptIn(ExperimentalForeignApi::class)
private fun applicationSupportDir(): Path {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val dir = Path(requireNotNull(url?.path) { "no Application Support directory" })
    Files.mkdirs(dir)
    return dir
}
