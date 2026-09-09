package com.archimedeprojects.volaflex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.archimedeprojects.volaflex.data.ApiKeyStore

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

@Composable
fun VolaFlexApp(
    versionName: String,
    apiKeyStore: ApiKeyStore
) {
    val navController = rememberNavController()

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        versionName = versionName,
                        onOpenSettings = {
                            navController.navigate(Routes.SETTINGS)
                        }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        apiKeyStore = apiKeyStore,
                        onBackHome = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}
