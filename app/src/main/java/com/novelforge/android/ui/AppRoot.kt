package com.novelforge.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("dashboard", "Studio", Icons.Filled.AutoStories),
    Tab("new", "Neues Buch", Icons.Filled.Add),
    Tab("settings", "Einstellungen", Icons.Filled.Settings),
)

@Composable
fun AppRoot(vm: AppViewModel) {
    val onboarded by vm.onboarded.collectAsState()
    val config by vm.config.collectAsState()
    // First-Run-Assistent, bis er abgeschlossen ist ODER bereits eine nutzbare KI-Config vorliegt.
    if (!onboarded && !config.usable) {
        OnboardingScreen(vm)
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val onSelect: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo("dashboard") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Querformat: seitliche NavigationRail (mehr vertikaler Platz fürs Schreiben).
    // Hochformat: klassische BottomBar (Daumen-erreichbar).
    Scaffold(
        bottomBar = {
            if (!landscape) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { onSelect(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = current == tab.route,
                            onClick = { onSelect(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) { AppNavHost(nav, vm) }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) { AppNavHost(nav, vm) }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, vm: AppViewModel) {
    NavHost(navController = nav, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(vm, onOpen = { id -> nav.navigate("project/$id") })
        }
        composable("new") {
            NewBookScreen(vm, onCreated = { id ->
                nav.navigate("project/$id") { popUpTo("dashboard") }
            })
        }
        composable("settings") { SettingsScreen(vm) }
        composable(
            "project/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            ProjectScreen(
                vm,
                projectId = entry.arguments?.getString("id").orEmpty(),
                onOpenProject = { id -> nav.navigate("project/$id") },
                onBack = { if (!nav.popBackStack()) nav.navigate("dashboard") },
                onKdpUpload = { id -> nav.navigate("kdp/$id") }
            )
        }
        composable(
            "kdp/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val project = vm.projects.collectAsState().value.firstOrNull { it.id == id }
            if (project != null) {
                KdpUploadScreen(project, onBack = { if (!nav.popBackStack()) nav.navigate("dashboard") })
            } else {
                nav.popBackStack()
            }
        }
    }
}
