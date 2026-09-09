package com.archimedeprojects.volaflex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.archimedeprojects.volaflex.data.ApiKeyStore
import com.archimedeprojects.volaflex.data.FlightSearchRepository
import com.archimedeprojects.volaflex.data.network.SerpApiNetwork

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
}

@Composable
fun VolaFlexApp(
    versionName: String,
    apiKeyStore: ApiKeyStore
) {
    val navController = rememberNavController()
    val flightSearchRepository = remember {
        FlightSearchRepository(SerpApiNetwork.service)
    }

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
                        onOpenSearch = {
                            navController.navigate(Routes.SEARCH)
                        },
                        onOpenSettings = {
                            navController.navigate(Routes.SETTINGS)
                        }
                    )
                }

                composable(Routes.SEARCH) {
                    SearchScreen(
                        apiKeyStore = apiKeyStore,
                        repository = flightSearchRepository,
                        onOpenSettings = {
                            navController.navigate(Routes.SETTINGS)
                        },
                        onBackHome = {
                            navController.popBackStack(
                                route = Routes.HOME,
                                inclusive = false
                            )
                        }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        apiKeyStore = apiKeyStore,
                        onBackHome = {
                            navController.popBackStack(
                                route = Routes.HOME,
                                inclusive = false
                            )
                        }
                    )
                }
            }
        }
    }
}
