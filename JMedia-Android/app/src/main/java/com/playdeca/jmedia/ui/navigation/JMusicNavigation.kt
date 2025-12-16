package com.playdeca.jmedia.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.playdeca.jmedia.ui.screens.HomeScreen
import com.playdeca.jmedia.ui.screens.LibraryScreen
import com.playdeca.jmedia.ui.screens.PlayerScreen
import com.playdeca.jmedia.ui.screens.QueueScreen
import com.playdeca.jmedia.ui.screens.SettingsScreen

@Composable
fun JMusicNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(navController = navController)
        }
        composable("library") {
            LibraryScreen(navController = navController)
        }
        composable("player") {
            PlayerScreen(navController = navController)
        }
        composable("queue") {
            QueueScreen(navController = navController)
        }
        composable("settings") {
            SettingsScreen(navController = navController)
        }
    }
}