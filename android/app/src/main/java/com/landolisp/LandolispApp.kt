package com.landolisp

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.landolisp.ui.LessonListScreen
import com.landolisp.ui.LessonScreen
import com.landolisp.ui.ReplScreen
import com.landolisp.ui.theme.LandolispTheme

/**
 * Top-level Compose entry.
 *
 * Routes:
 *  - `lessons`        Lesson index (bottom-nav default)
 *  - `lesson/{id}`    Single lesson detail
 *  - `repl`           Free-form sandbox REPL (bottom-nav "Sandbox")
 */
@Composable
fun LandolispApp() {
    LandolispTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route

        Scaffold(
            bottomBar = {
                // Hide bottom nav on the lesson detail screen so the user can focus.
                if (currentRoute?.startsWith("lesson/") != true) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == Routes.LESSONS,
                            onClick = {
                                navController.navigate(Routes.LESSONS) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_lessons)) },
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.REPL,
                            onClick = {
                                navController.navigate(Routes.REPL) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_sandbox)) },
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.LESSONS,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.LESSONS) {
                    LessonListScreen(
                        onLessonClick = { id ->
                            navController.navigate("lesson/$id")
                        },
                    )
                }
                composable(Routes.LESSON_PATTERN) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    LessonScreen(
                        lessonId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.REPL) {
                    ReplScreen()
                }
            }
        }
    }
}

object Routes {
    const val LESSONS = "lessons"
    const val LESSON_PATTERN = "lesson/{id}"
    const val REPL = "repl"
}
