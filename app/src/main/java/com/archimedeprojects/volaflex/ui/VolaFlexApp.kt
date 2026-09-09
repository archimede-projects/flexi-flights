package com.archimedeprojects.volaflex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.archimedeprojects.volaflex.data.ApiKeyStore
import com.archimedeprojects.volaflex.data.DiagnosticRepository
import com.archimedeprojects.volaflex.data.FlightSearchRepository
import com.archimedeprojects.volaflex.data.WeekendSearchRepository
import com.archimedeprojects.volaflex.data.local.VolaFlexDatabase
import com.archimedeprojects.volaflex.data.network.SerpApiNetwork

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val WEEKEND = "weekend"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun VolaFlexApp(
    versionName: String,
    apiKeyStore: ApiKeyStore
) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val database = remember(appContext) {
        VolaFlexDatabase.getInstance(appContext)
    }
    val diagnosticRepository = remember(database) {
        DiagnosticRepository(database.diagnosticEventDao())
    }
    val flightSearchRepository = remember(database, diagnosticRepository) {
        FlightSearchRepository(
            service = SerpApiNetwork.service,
            cacheDao = database.flightSearchCacheDao(),
            diagnostics = diagnosticRepository
        )
    }
    val weekendSearchRepository = remember(database, diagnosticRepository) {
        WeekendSearchRepository(
            service = SerpApiNetwork.service,
            cacheDao = database.weekendSearchCacheDao(),
            diagnostics = diagnosticRepository
        )
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
                        onOpenWeekend = {
                            navController.navigate(Routes.WEEKEND)
                        },
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

                composable(Routes.WEEKEND) {
                    WeekendSearchScreen(
                        apiKeyStore = apiKeyStore,
                        repository = weekendSearchRepository,
                        onOpenFixedDates = {
                            navController.popBackStack(
                                route = Routes.SEARCH,
                                inclusive = false
                            )
                        },
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
                        onOpenDiagnostics = {
                            navController.navigate(Routes.DIAGNOSTICS)
                        },
                        onBackHome = {
                            navController.popBackStack(
                                route = Routes.HOME,
                                inclusive = false
                            )
                        }
                    )
                }

                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(
                        repository = diagnosticRepository,
                        onBackSettings = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}
