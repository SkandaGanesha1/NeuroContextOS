package com.cortexn.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cortexn.app.ui.*

/**
 * Main navigation graph for Cortex-N showcase app
 * 
 * Routes:
 * - Home: Dashboard with telemetry overlay
 * - Focus: Focus mode with audio generation
 * - Fitness: Activity tracking with SNN gesture recognition
 * - Tourist: Vision-based navigation assistant
 */

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home : Screen("home", "Home", androidx.compose.material.icons.Icons.Default.Home)
    object Focus : Screen("focus", "Focus", androidx.compose.material.icons.Icons.Default.MusicNote)
    object Fitness : Screen("fitness", "Fitness", androidx.compose.material.icons.Icons.Default.FitnessCenter)
    object Tourist : Screen("tourist", "Tourist", androidx.compose.material.icons.Icons.Default.Explore)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    
    val screens = listOf(
        Screen.Home,
        Screen.Focus,
        Screen.Fitness,
        Screen.Tourist
    )
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Focus.route) { FocusScreen() }
            composable(Screen.Fitness.route) { FitnessScreen() }
            composable(Screen.Tourist.route) { TouristScreen() }
        }
    }
}
