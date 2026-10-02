package app.podkhod.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.podkhod.R
import kotlinx.serialization.Serializable

@Serializable data object TodayRoute
@Serializable data object ProgramRoute
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute
@Serializable data object AboutRoute
@Serializable data object LicensesRoute
@Serializable data class WorkoutRoute(val sessionId: Long)
@Serializable data class SummaryRoute(val sessionId: Long)
@Serializable data class HistoryDetailRoute(val sessionId: Long)
@Serializable data object OnboardingRoute
@Serializable data object ProfileRoute
@Serializable data object WhyRoute
@Serializable data object PlateCalcRoute
@Serializable data object ProgressRoute
@Serializable data class ExerciseProgressRoute(val exerciseId: String)

enum class TopLevelDestination(
    val route: Any,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    Today(TodayRoute, R.string.nav_today, R.drawable.ic_nav_today),
    Program(ProgramRoute, R.string.nav_program, R.drawable.ic_nav_program),
    History(HistoryRoute, R.string.nav_history, R.drawable.ic_nav_history),
    Settings(SettingsRoute, R.string.nav_settings, R.drawable.ic_nav_settings),
}
