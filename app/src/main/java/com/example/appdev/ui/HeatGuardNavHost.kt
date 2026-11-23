package com.example.appdev.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.appdev.HeatGuardApp
import com.example.appdev.R
import com.example.appdev.ui.analytics.AnalyticsRoute
import com.example.appdev.ui.assistant.AssistantRoute
import com.example.appdev.ui.assistant.ScreenContext
import com.example.appdev.ui.coach.CoachRoute
import com.example.appdev.ui.components.LocalAskAssistant
import com.example.appdev.ui.components.LocalOpenAssistant
import com.example.appdev.ui.dashboard.DashboardRoute
import com.example.appdev.ui.demo.DemoRoute
import com.example.appdev.ui.history.HistoryRoute
import com.example.appdev.ui.history.IncidentDetailRoute
import com.example.appdev.ui.history.IncidentDetailViewModel
import com.example.appdev.ui.insights.InsightsRoute
import com.example.appdev.ui.settings.SettingsRoute
import com.example.appdev.ui.theme.HeatColors

/**
 * Top-level sections. All six sections are present, so the bar never shows a tab without working functionality.
 */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    DASHBOARD("dashboard", R.string.nav_dashboard, R.drawable.ic_dashboard),
    ANALYTICS("analytics", R.string.nav_live, R.drawable.ic_chart),
    COACH("coach", R.string.nav_coach, R.drawable.ic_coach),
    HISTORY("history", R.string.nav_history, R.drawable.ic_history),
    INSIGHTS("insights", R.string.nav_insights, R.drawable.ic_insights),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_tune),
}

private const val INCIDENT_ROUTE = "incident"
private const val DEMO_ROUTE = "demo"

@Composable
fun HeatGuardNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val container = (LocalContext.current.applicationContext as HeatGuardApp).container
    val demoEnabled by container.demoController.enabled.collectAsStateWithLifecycle()

    val route = currentDestination?.route
    val onAssistant = route == ScreenContext.ASSISTANT_ROUTE
    // The screen the user was on when they opened the assistant, so answers and suggestions fit it.
    var assistantOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    val openAssistant: () -> Unit = {
        if (route != ScreenContext.ASSISTANT_ROUTE) {
            assistantOrigin = route
            navController.navigate(ScreenContext.ASSISTANT_ROUTE) { launchSingleTop = true }
        }
    }

    // A ready-made question waiting to be sent when the assistant opens (e.g. "explain this incident").
    var pendingPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    val askAssistant: (String) -> Unit = { prompt ->
        pendingPrompt = prompt
        openAssistant()
    }

    // Every screen header shows an assistant icon that calls this.
    CompositionLocalProvider(LocalOpenAssistant provides openAssistant, LocalAskAssistant provides askAssistant) {
        Scaffold(
            // A gentle vertical gradient behind every screen instead of a flat fill.
            modifier = Modifier.background(Brush.verticalGradient(listOf(HeatColors.BackgroundTop, HeatColors.Background))),
            containerColor = Color.Transparent,
            bottomBar = {
                // The chat is full screen so the keyboard and input bar have the whole height.
                if (!onAssistant) {
                    CompactNavigationBar(
                        destinations = TopLevelDestination.entries,
                        isSelected = { destination ->
                            // The incident detail screen belongs to History; demo controls belong to Settings.
                            currentDestination?.hierarchy?.any {
                                it.route == destination.route ||
                                    (destination == TopLevelDestination.HISTORY && it.route?.startsWith(INCIDENT_ROUTE) == true) ||
                                    (destination == TopLevelDestination.SETTINGS && it.route == DEMO_ROUTE)
                            } == true
                        },
                        onSelect = { navController.navigateToTopLevel(it) },
                    )
                }
            },
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
                if (demoEnabled) {
                    DemoBanner(
                        onOpenControls = { navController.navigate(DEMO_ROUTE) { launchSingleTop = true } },
                        onExit = { container.demoMode.setEnabled(false) },
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = TopLevelDestination.DASHBOARD.route,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    composable(TopLevelDestination.DASHBOARD.route) {
                        DashboardRoute(onOpenSettings = { navController.navigateToTopLevel(TopLevelDestination.SETTINGS) })
                    }
                    composable(TopLevelDestination.ANALYTICS.route) { AnalyticsRoute() }
                    composable(TopLevelDestination.INSIGHTS.route) { InsightsRoute() }
                    composable(TopLevelDestination.COACH.route) { CoachRoute() }
                    composable(TopLevelDestination.HISTORY.route) {
                        HistoryRoute(onOpenIncident = { id -> navController.navigate("$INCIDENT_ROUTE/$id") })
                    }
                    composable(
                        route = "$INCIDENT_ROUTE/{${IncidentDetailViewModel.ARG_ID}}",
                        arguments = listOf(navArgument(IncidentDetailViewModel.ARG_ID) { type = NavType.LongType }),
                    ) {
                        IncidentDetailRoute(onBack = { navController.popBackStack() })
                    }
                    composable(TopLevelDestination.SETTINGS.route) {
                        SettingsRoute(
                            onOpenDemo = { navController.navigate(DEMO_ROUTE) { launchSingleTop = true } },
                            onOpenAssistant = openAssistant,
                        )
                    }
                    composable(DEMO_ROUTE) { DemoRoute(onBack = { navController.popBackStack() }) }
                    composable(ScreenContext.ASSISTANT_ROUTE) {
                        AssistantRoute(
                            originRoute = assistantOrigin,
                            onBack = { navController.popBackStack() },
                            pendingPrompt = pendingPrompt,
                            onPromptConsumed = { pendingPrompt = null },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A slimmer replacement for Material's navigation bar (which is a fixed 80 dp tall). Same six
 * destinations and colours, but a 56 dp bar with a smaller icon and a tight icon-to-label gap, so it
 * leaves more of the screen to the content. Selection is exposed to accessibility as a tab.
 */
@Composable
private fun CompactNavigationBar(
    destinations: List<TopLevelDestination>,
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    Surface(color = HeatColors.Surface, tonalElevation = 0.dp) {
        Column(Modifier.windowInsetsPadding(NavigationBarDefaults.windowInsets)) {
            HorizontalDivider(color = HeatColors.OutlineSubtle)
            Row(
                Modifier.fillMaxWidth().height(BAR_HEIGHT).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    val selected = isSelected(destination)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                            .padding(top = 5.dp, bottom = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .height(24.dp)
                                .width(44.dp)
                                .background(if (selected) HeatColors.SurfaceHighest else Color.Transparent, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(destination.icon),
                                contentDescription = null,
                                tint = if (selected) HeatColors.Accent else HeatColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            stringResource(destination.label),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected) HeatColors.TextPrimary else HeatColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

private val BAR_HEIGHT = 56.dp

/** Always visible while Demo Mode is on, so simulated data can never be mistaken for real data. */
@Composable
private fun DemoBanner(onOpenControls: () -> Unit, onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeatColors.Accent)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.demo_banner),
            style = MaterialTheme.typography.labelLarge,
            color = HeatColors.OnAccent,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenControls) {
            Text(stringResource(R.string.demo_banner_controls), color = HeatColors.OnAccent)
        }
        TextButton(onClick = onExit) {
            Text(stringResource(R.string.demo_banner_exit), color = HeatColors.OnAccent)
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
