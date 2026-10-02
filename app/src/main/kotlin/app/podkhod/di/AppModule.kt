package app.podkhod.di

import androidx.room.Room
import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.db.AppMetaRepository
import app.podkhod.data.media.MediaRepository
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.data.program.ProgramRepository
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.ui.history.HistoryDetailViewModel
import app.podkhod.ui.history.HistoryViewModel
import app.podkhod.ui.settings.SettingsViewModel
import app.podkhod.ui.profile.ProfileViewModel
import app.podkhod.ui.program.ProgramViewModel
import app.podkhod.ui.today.TodayViewModel
import app.podkhod.ui.weights.PlateCalculatorViewModel
import app.podkhod.ui.workout.SummaryViewModel
import app.podkhod.ui.workout.WorkoutViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
    }
    single { get<AppDatabase>().appMetaDao() }
    single { get<AppDatabase>().catalogDao() }
    single { AppMetaRepository(get()) }
    single { SettingsRepository(androidContext()) }
    single { AssetReader(androidContext()) }
    single { CatalogRepository(get(), get(), get()) }
    single { MediaRepository(androidContext(), get()) }
    single { ProgramRepository(get(), get()) }
    single { ProfileRepository(get()) }
    single { GeneratorConfigStore(androidContext().filesDir, get()) }
    single { PlanRepository(get(), get(), get(), get(), get()) }
    single { WorkoutRepository(get(), get(), get(), get()) }

    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    viewModel { TodayViewModel(get(), get(), get(), get()) }
    viewModel { ProgramViewModel(get(), get(), get(), get()) }
    viewModel { (firstRun: Boolean) -> ProfileViewModel(get(), get(), firstRun) }
    viewModel { PlateCalculatorViewModel(get()) }
    viewModel { (sessionId: Long) -> WorkoutViewModel(sessionId, get(), get(), get(), get(), get()) }
    viewModel { (sessionId: Long) -> SummaryViewModel(sessionId, get()) }
    viewModel { HistoryViewModel(get(), get()) }
    viewModel { (sessionId: Long) -> HistoryDetailViewModel(sessionId, get()) }
}
