package io.github.szzgx.smartskip.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.szzgx.smartskip.ui.screens.DashboardScreen
import io.github.szzgx.smartskip.ui.screens.EventsScreen
import io.github.szzgx.smartskip.ui.screens.InspectorScreen
import io.github.szzgx.smartskip.ui.screens.RulesScreen
import io.github.szzgx.smartskip.ui.screens.SettingsScreen

/**
 * The app's destinations.
 *
 * Five flat routes rather than nested graphs: the app has no deep hierarchy, and a flat list
 * keeps the bottom bar's selected-state logic trivial.
 */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "状态", Icons.Filled.Home),

    /**
     * Auto-mirrored so the icon flips correctly in right-to-left locales, which the plain
     * `Icons.Filled.List` no longer does — it is deprecated for exactly that reason.
     */
    Events("events", "记录", Icons.AutoMirrored.Filled.List),

    Rules("rules", "规则", Icons.Filled.Checklist),
    Inspector("inspector", "检查器", Icons.Filled.Search),
    Settings("settings", "设置", Icons.Filled.Settings),
}

/**
 * The app shell.
 *
 * Navigation uses `popUpTo` the start destination with `saveState`, which is the behaviour a
 * bottom bar is expected to have: switching tabs preserves each tab's scroll position and does
 * not grow the back stack.
 */
@Composable
fun SmartSkipApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == destination.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { insets ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(insets),
        ) {
            composable(Destination.Dashboard.route) { DashboardScreen(navController) }
            composable(Destination.Events.route) { EventsScreen() }
            composable(Destination.Rules.route) { RulesScreen() }
            composable(Destination.Inspector.route) { InspectorScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
