package app.podkhod.ui.navigation

import app.podkhod.resources.*
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

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
    val label: StringResource,
    val icon: DrawableResource,
) {
    Today(TodayRoute, Res.string.nav_today, Res.drawable.ic_nav_today),
    Program(ProgramRoute, Res.string.nav_program, Res.drawable.ic_nav_program),
    History(HistoryRoute, Res.string.nav_history, Res.drawable.ic_nav_history),
    Settings(SettingsRoute, Res.string.nav_settings, Res.drawable.ic_nav_settings),
}
