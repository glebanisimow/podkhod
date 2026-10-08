package app.podkhod.di

import app.podkhod.data.backup.BackupRepository
import app.podkhod.data.catalog.AssetReader
import app.podkhod.data.stats.ProgressRepository
import app.podkhod.ui.progress.ExerciseProgressViewModel
import app.podkhod.ui.progress.ProgressViewModel
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
import app.podkhod.platform.AppInfo
import app.podkhod.platform.Log
import app.podkhod.ui.history.HistoryDetailViewModel
import app.podkhod.ui.history.HistoryViewModel
import app.podkhod.ui.settings.SettingsViewModel
import app.podkhod.ui.profile.ProfileViewModel
import app.podkhod.ui.program.ProgramViewModel
import app.podkhod.ui.today.TodayViewModel
import app.podkhod.ui.weights.PlateCalculatorViewModel
import app.podkhod.ui.workout.SummaryViewModel
import app.podkhod.ui.workout.WorkoutViewModel
import org.koin.core.Koin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Everything above the platform. The platform module provides [AppDatabase], the settings
 * `DataStore<Preferences>`, [app.podkhod.platform.AppDirs], [AppInfo], [app.podkhod.platform.HoldSignals]
 * and [app.podkhod.platform.WorkoutTracker].
 */
val appModule = module {
    single { get<AppDatabase>().appMetaDao() }
    single { get<AppDatabase>().catalogDao() }
    single { AppMetaRepository(get()) }
    single { SettingsRepository(get()) }
    single { AssetReader() }
    single { CatalogRepository(get(), get(), get()) }
    single { MediaRepository(get(), get()) }
    single { ProgramRepository(get(), get()) }
    single { ProfileRepository(get()) }
    single { GeneratorConfigStore(get(), get()) }
    single { PlanRepository(get(), get(), get(), get(), get()) }
    single { WorkoutRepository(get(), get(), get(), get()) }
    single { ProgressRepository(get(), get(), get()) }
    single { BackupRepository(get(), get(), get(), get(), get(), get<AppInfo>().versionName) }

    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { TodayViewModel(get(), get(), get(), get(), get()) }
    viewModel { ProgramViewModel(get(), get(), get(), get(), get()) }
    viewModel { (firstRun: Boolean) -> ProfileViewModel(get(), get(), firstRun) }
    viewModel { PlateCalculatorViewModel(get()) }
    viewModel { (sessionId: Long) -> WorkoutViewModel(sessionId, get(), get(), get(), get(), get(), get()) }
    viewModel { (sessionId: Long) -> SummaryViewModel(sessionId, get()) }
    viewModel { HistoryViewModel(get(), get()) }
    viewModel { (sessionId: Long) -> HistoryDetailViewModel(sessionId, get()) }
    viewModel { ProgressViewModel(get(), get()) }
    viewModel { (exerciseId: String) -> ExerciseProgressViewModel(exerciseId, get(), get()) }
}

/** First-launch work and catalog updates; runs off the main thread at every process start. */
suspend fun Koin.bootstrap() {
    runCatching { get<MediaRepository>().load() }.onFailure { Log.e("Podkhod", "media manifest failed", it) }
    runCatching {
        get<AppMetaRepository>().recordFirstLaunchIfMissing()
        get<CatalogRepository>().seedIfNeeded()
        get<ProgramRepository>().ensureDefaultProgram()
        get<PlanRepository>().applyMinSets()
    }.onFailure { Log.e("Podkhod", "bootstrap failed", it) }
}
