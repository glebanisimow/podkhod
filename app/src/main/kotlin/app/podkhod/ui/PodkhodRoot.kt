package app.podkhod.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.podkhod.ui.history.HistoryDetailScreen
import app.podkhod.ui.history.HistoryScreen
import app.podkhod.ui.navigation.AboutRoute
import app.podkhod.ui.navigation.HistoryDetailRoute
import app.podkhod.ui.navigation.HistoryRoute
import app.podkhod.ui.navigation.LicensesRoute
import app.podkhod.ui.navigation.ProgramRoute
import app.podkhod.ui.navigation.SettingsRoute
import app.podkhod.ui.navigation.SummaryRoute
import app.podkhod.ui.navigation.TodayRoute
import app.podkhod.ui.navigation.TopLevelDestination
import app.podkhod.ui.navigation.WorkoutRoute
import app.podkhod.ui.screens.AboutScreen
import app.podkhod.ui.screens.LicensesScreen
import app.podkhod.ui.navigation.OnboardingRoute
import app.podkhod.ui.navigation.PlateCalcRoute
import app.podkhod.ui.navigation.ProfileRoute
import app.podkhod.ui.navigation.WhyRoute
import app.podkhod.ui.profile.ProfileScreen
import app.podkhod.ui.program.ProgramScreen
import app.podkhod.ui.program.WhyScreen
import app.podkhod.ui.settings.SettingsScreen
import app.podkhod.ui.today.TodayScreen
import app.podkhod.ui.weights.PlateCalculatorScreen
import app.podkhod.data.profile.ProfileRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import app.podkhod.ui.workout.SummaryScreen
import app.podkhod.ui.workout.WorkoutScreen

@Composable
fun PodkhodRoot() {
    // The start screen is decided once: onboarding when there is no profile yet, Today otherwise.
    val profiles = koinInject<ProfileRepository>()
    var start by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        start = if (profiles.hasProfile.first()) TodayRoute else OnboardingRoute
    }
    val startRoute = start
    if (startRoute == null) Box(Modifier.fillMaxSize()) else PodkhodNav(startRoute)
}

@Composable
private fun PodkhodNav(startRoute: Any) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TopLevelDestination.entries.any { top ->
        currentDestination?.hierarchy?.any { it.hasRoute(top.route::class) } == true
    }

    Scaffold(
        // Exposes test tags to UI Automator so on-device checks can target controls without matching Russian text.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { top ->
                        val selected = currentDestination?.hierarchy?.any { it.hasRoute(top.route::class) } == true
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav_${top.name}"),
                            selected = selected,
                            onClick = {
                                navController.navigate(top.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(top.icon), contentDescription = null) },
                            label = { Text(stringResource(top.label)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<OnboardingRoute> {
                ProfileScreen(
                    firstRun = true,
                    onDone = {
                        navController.navigate(TodayRoute) {
                            popUpTo<OnboardingRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<ProfileRoute> {
                ProfileScreen(firstRun = false, onDone = { navController.popBackStack() }, onBack = { navController.popBackStack() })
            }
            composable<TodayRoute> {
                TodayScreen(
                    onOpenWorkout = { id -> navController.navigate(WorkoutRoute(id)) { launchSingleTop = true } },
                    onOpenProfile = { navController.navigate(ProfileRoute) },
                )
            }
            composable<ProgramRoute> {
                ProgramScreen(onOpenProfile = { navController.navigate(ProfileRoute) }, onOpenWhy = { navController.navigate(WhyRoute) })
            }
            composable<WhyRoute> { WhyScreen(onBack = { navController.popBackStack() }) }
            composable<HistoryRoute> {
                HistoryScreen(onOpen = { id -> navController.navigate(HistoryDetailRoute(id)) })
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenAbout = { navController.navigate(AboutRoute) },
                    onOpenProfile = { navController.navigate(ProfileRoute) },
                    onOpenPlateCalc = { navController.navigate(PlateCalcRoute) },
                )
            }
            composable<PlateCalcRoute> { PlateCalculatorScreen(onBack = { navController.popBackStack() }) }
            composable<AboutRoute> {
                AboutScreen(onBack = { navController.popBackStack() }, onOpenLicenses = { navController.navigate(LicensesRoute) })
            }
            composable<LicensesRoute> { LicensesScreen(onBack = { navController.popBackStack() }) }
            composable<WorkoutRoute> { entry ->
                val route = entry.toRoute<WorkoutRoute>()
                WorkoutScreen(
                    sessionId = route.sessionId,
                    onBack = { navController.popBackStack() },
                    onFinished = {
                        navController.navigate(SummaryRoute(route.sessionId)) {
                            popUpTo<TodayRoute>()
                        }
                    },
                    onDiscarded = { navController.popBackStack<TodayRoute>(inclusive = false) },
                )
            }
            composable<SummaryRoute> { entry ->
                SummaryScreen(
                    sessionId = entry.toRoute<SummaryRoute>().sessionId,
                    onDone = { navController.popBackStack<TodayRoute>(inclusive = false) },
                )
            }
            composable<HistoryDetailRoute> { entry ->
                HistoryDetailScreen(
                    sessionId = entry.toRoute<HistoryDetailRoute>().sessionId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
