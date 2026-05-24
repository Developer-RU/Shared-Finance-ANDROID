package com.sharedfinance.ui

import android.content.Context
import android.content.res.Configuration
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sharedfinance.ble.BleManager
import com.sharedfinance.data.repository.RoomSharedFinanceRepository
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.sync.SyncEngine
import com.sharedfinance.sync.SyncService
import com.sharedfinance.R
import com.sharedfinance.ui.navigation.AppDestination
import com.sharedfinance.ui.screens.BalanceScreen
import com.sharedfinance.ui.screens.ExpensesScreen
import com.sharedfinance.ui.screens.HistoryScreen
import com.sharedfinance.ui.screens.ParticipantsScreen
import com.sharedfinance.ui.screens.ProjectDetailScreen
import com.sharedfinance.ui.screens.ProjectsScreen
import com.sharedfinance.ui.screens.SettingsScreen
import com.sharedfinance.ui.screens.SyncScreen
import com.sharedfinance.ui.theme.SharedFinanceTheme
import com.sharedfinance.viewmodel.BalanceViewModel
import com.sharedfinance.viewmodel.ExpensesViewModel
import com.sharedfinance.viewmodel.HistoryViewModel
import com.sharedfinance.viewmodel.ParticipantsViewModel
import com.sharedfinance.viewmodel.ProjectDetailViewModel
import com.sharedfinance.viewmodel.ProjectsViewModel
import com.sharedfinance.viewmodel.AppThemeOption
import com.sharedfinance.viewmodel.SettingsViewModel
import com.sharedfinance.viewmodel.SyncViewModel
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking

private data class TabItem(
    val destination: AppDestination,
    val icon: ImageVector
)

@Composable
fun SharedFinanceAndroidApp() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val repository = remember(context) { RoomSharedFinanceRepository(context) }
    val bleManager = remember(context) { BleManager(context) }
    val syncService = remember { SyncService(repository, SyncEngine()) }

    remember(bleManager, repository) {
        bleManager.setResponsePayloadProvider { inboundBytes ->
            runCatching {
                val gson = GsonBuilder()
                    .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                    .create()
                runBlocking {
                    if (inboundBytes.isNotEmpty()) {
                        val inboundJson = inboundBytes.toString(Charsets.UTF_8)
                        val remotePayload = runCatching {
                            gson.fromJson(inboundJson, SyncPayload::class.java)
                        }.getOrNull()

                        if (remotePayload != null) {
                            repository.importPayload(remotePayload)
                            Log.d(
                                "SharedFinanceSync",
                                "import_payload_from_ble projects=${remotePayload.projects.size} participants=${remotePayload.participants.size} expenses=${remotePayload.expenses.size} history=${remotePayload.history.size} syncLogs=${remotePayload.syncLogs.size}"
                            )
                        } else {
                            Log.d(
                                "SharedFinanceSync",
                                "import_payload_from_ble_parse_failed bytes=${inboundBytes.size}"
                            )
                        }
                    }

                    val payload = repository.buildSyncPayload()
                    Log.d(
                        "SharedFinanceSync",
                        "export_payload projects=${payload.projects.size} participants=${payload.participants.size} expenses=${payload.expenses.size} history=${payload.history.size} syncLogs=${payload.syncLogs.size}"
                    )
                    gson.toJson(payload).toByteArray(Charsets.UTF_8)
                }
            }.getOrDefault(ByteArray(0))
        }
        Unit
    }

    val projectsViewModel = remember { ProjectsViewModel(repository) }
    val projectsState by projectsViewModel.state.collectAsState()
    val balanceViewModel = remember { BalanceViewModel(repository) }
    val historyViewModel = remember { HistoryViewModel(repository) }
    val syncViewModel = remember { SyncViewModel(bleManager, syncService) }
    val settingsViewModel = remember { SettingsViewModel(repository) }
    val settingsState by settingsViewModel.state.collectAsState()

    val currentActivityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val activityResultRegistryOwner = remember(context) {
        context.findActivity()
    }
    val localizedContext = remember(settingsState.languageCode, context) {
        val locale = Locale.forLanguageTag(settingsState.languageCode)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        context.createConfigurationContext(configuration)
    }

    val darkModeEnabled = when (settingsState.selectedTheme) {
        AppThemeOption.SYSTEM -> isSystemInDarkTheme()
        AppThemeOption.LIGHT -> false
        AppThemeOption.DARK -> true
    }

    val tabs = remember {
        listOf(
            TabItem(AppDestination.PROJECTS, Icons.AutoMirrored.Outlined.List),
            TabItem(AppDestination.BALANCE, Icons.Outlined.AccountBalanceWallet),
            TabItem(AppDestination.HISTORY, Icons.Outlined.History),
            TabItem(AppDestination.SYNC, Icons.Outlined.Sync),
            TabItem(AppDestination.SETTINGS, Icons.Outlined.Settings)
        )
    }

    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalContext provides localizedContext,
        LocalActivityResultRegistryOwner provides requireNotNull(
            currentActivityResultRegistryOwner ?: activityResultRegistryOwner
        )
    ) {
        SharedFinanceTheme(darkTheme = darkModeEnabled) {
            val shellAccentAlpha = if (darkModeEnabled) 0.35f else 0.55f
            val shellBackground = Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shellAccentAlpha),
                    MaterialTheme.colorScheme.background
                )
            )

            Surface(color = MaterialTheme.colorScheme.background) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(shellBackground)
                ) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        bottomBar = {
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentRoute = navBackStackEntry?.destination?.route
                            Surface(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(30.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                tonalElevation = 12.dp,
                                shadowElevation = 10.dp
                            ) {
                                NavigationBar(
                                    containerColor = Color.Transparent,
                                    tonalElevation = 0.dp
                                ) {
                                    tabs.forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentRoute == tab.destination.route,
                                            onClick = {
                                                navController.navigate(tab.destination.route) {
                                                    popUpTo(navController.graph.startDestinationId) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = tab.icon,
                                                    contentDescription = tabTitle(tab.destination)
                                                )
                                            },
                                            label = { Text(tabTitle(tab.destination)) }
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = AppDestination.PROJECTS.route,
                            modifier = Modifier.padding(innerPadding)
                        ) {
                        composable(AppDestination.PROJECTS.route) {
                            ProjectsScreen(projectsViewModel) { projectId ->
                                navController.navigate("project/$projectId")
                            }
                        }
                        composable(
                            route = "project/{projectId}",
                            arguments = listOf(navArgument("projectId") { defaultValue = "" })
                        ) { backStackEntry ->
                            val projectIdRaw = backStackEntry.arguments?.getString("projectId")
                            val projectId = projectIdRaw?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                            if (projectId != null) {
                                val detailViewModel = remember(projectId) { ProjectDetailViewModel(repository, projectId) }
                                ProjectDetailScreen(
                                    projectsViewModel = projectsViewModel,
                                    detailViewModel = detailViewModel,
                                    onOpenExpenses = { navController.navigate("project/$projectId/expenses") },
                                    onOpenParticipants = { navController.navigate("project/$projectId/participants") }
                                )
                            }
                        }
                        composable(
                            route = "project/{projectId}/participants",
                            arguments = listOf(navArgument("projectId") { defaultValue = "" })
                        ) { backStackEntry ->
                            val projectIdRaw = backStackEntry.arguments?.getString("projectId")
                            val projectId = projectIdRaw?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                            if (projectId != null) {
                                val viewModel = remember(projectId) { ParticipantsViewModel(repository, projectId) }
                                ParticipantsScreen(viewModel)
                            }
                        }
                        composable(
                            route = "project/{projectId}/expenses",
                            arguments = listOf(navArgument("projectId") { defaultValue = "" })
                        ) { backStackEntry ->
                            val projectIdRaw = backStackEntry.arguments?.getString("projectId")
                            val projectId = projectIdRaw?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                            if (projectId != null) {
                                val viewModel = remember(projectId) { ExpensesViewModel(repository, projectId) }
                                ExpensesScreen(viewModel)
                            }
                        }
                        composable(AppDestination.BALANCE.route) {
                            BalanceScreen(balanceViewModel)
                        }
                        composable(AppDestination.HISTORY.route) {
                            HistoryScreen(historyViewModel)
                        }
                        composable(AppDestination.SYNC.route) {
                            SyncScreen(syncViewModel, projectsState.projects) {
                                runBlocking { repository.buildSyncPayload() }
                            }
                        }
                        composable(AppDestination.SETTINGS.route) {
                            SettingsScreen(settingsViewModel)
                        }
                    }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? {
    return when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Composable
private fun tabTitle(destination: AppDestination): String {
    return when (destination) {
        AppDestination.PROJECTS -> stringResource(R.string.tab_projects)
        AppDestination.BALANCE -> stringResource(R.string.tab_balance)
        AppDestination.HISTORY -> stringResource(R.string.tab_history)
        AppDestination.SYNC -> stringResource(R.string.tab_sync)
        AppDestination.SETTINGS -> stringResource(R.string.tab_settings)
    }
}
