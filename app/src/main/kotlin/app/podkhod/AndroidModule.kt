package app.podkhod

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.podkhod.data.db.AppDatabase
import app.podkhod.platform.AppDirs
import app.podkhod.platform.AppInfo
import app.podkhod.platform.HoldSignals
import app.podkhod.platform.WorkoutTracker
import app.podkhod.service.AndroidHoldSignals
import app.podkhod.service.WorkoutService
import kotlinx.io.files.Path
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** What the shared module needs from Android; see app.podkhod.di.appModule. */
val androidModule = module {
    // Framework SQLite through Room's Android builder, the same file and driver as before the shared module.
    single<AppDatabase> {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
    }
    // File name is referenced by res/xml backup rules: datastore/settings.preferences_pb
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("settings") }
    }
    single { AppDirs(Path(androidContext().filesDir.path)) }
    single { AppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()) }
    single<HoldSignals> { AndroidHoldSignals(androidContext()) }
    single<WorkoutTracker> { WorkoutTracker { WorkoutService.start(androidContext()) } }
}
