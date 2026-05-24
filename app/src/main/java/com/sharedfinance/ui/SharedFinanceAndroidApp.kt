package com.sharedfinance.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.fragment.app.FragmentActivity
import com.google.gson.GsonBuilder
import com.sharedfinance.R
import com.sharedfinance.ble.BleManager
import com.sharedfinance.data.repository.RoomSharedFinanceRepository
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.sync.SyncService
import com.sharedfinance.ui.navigation.AppDestination
import com.sharedfinance.ui.components.PatternLockView
import com.sharedfinance.ui.screens.BalanceScreen
import com.sharedfinance.ui.screens.ExpensesScreen
import com.sharedfinance.ui.screens.HistoryScreen
import com.sharedfinance.ui.screens.ParticipantsScreen
import com.sharedfinance.ui.screens.ProjectDetailScreen
import com.sharedfinance.ui.screens.ProjectsScreen
import com.sharedfinance.ui.screens.SettingsScreen
import com.sharedfinance.ui.screens.SyncLogsScreen
import com.sharedfinance.ui.screens.SyncScreen
import com.sharedfinance.ui.theme.SharedFinanceTheme
import com.sharedfinance.viewmodel.AppThemeOption
import com.sharedfinance.viewmodel.BalanceViewModel
import com.sharedfinance.viewmodel.ExpensesViewModel
import com.sharedfinance.viewmodel.HistoryViewModel
import com.sharedfinance.viewmodel.ParticipantsViewModel
import com.sharedfinance.viewmodel.ProjectDetailViewModel
import com.sharedfinance.viewmodel.ProjectsViewModel
import com.sharedfinance.viewmodel.SettingsViewModel
import com.sharedfinance.viewmodel.SyncLogsViewModel
import com.sharedfinance.viewmodel.SyncViewModel
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
    val syncService = remember { SyncService(repository) }

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

    val projectsViewModel = remember { ProjectsViewModel(context, repository) }
    val projectsState by projectsViewModel.state.collectAsState()
    val balanceViewModel = remember { BalanceViewModel(repository) }
    val historyViewModel = remember { HistoryViewModel(repository) }
    val syncLogsViewModel = remember { SyncLogsViewModel(repository) }
    val syncViewModel = remember { SyncViewModel(bleManager, syncService) }
    val settingsViewModel = remember { SettingsViewModel(context, repository) }
    val settingsState by settingsViewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAppLocked by rememberSaveable { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinDraft by rememberSaveable { mutableStateOf("") }
    var pinError by rememberSaveable { mutableStateOf(false) }
    var showPatternDialog by rememberSaveable { mutableStateOf(false) }
    var patternDraft by rememberSaveable { mutableStateOf<List<Int>>(emptyList()) }
    var patternError by rememberSaveable { mutableStateOf(false) }

    val unlockWithBiometric: () -> Unit = unlock@{
        val activity = context.findActivity()
        if (activity == null) {
            if (settingsState.pinEnabled) {
                showPinDialog = true
            } else if (settingsState.patternEnabled) {
                showPatternDialog = true
            }
            return@unlock
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isAppLocked = false
                    showPinDialog = false
                    showPatternDialog = false
                    pinDraft = ""
                    patternDraft = emptyList()
                    pinError = false
                    patternError = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (settingsState.pinEnabled) {
                        showPinDialog = true
                    } else if (settingsState.patternEnabled) {
                        showPatternDialog = true
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.security_unlock_title))
            .setSubtitle(context.getString(R.string.security_unlock_subtitle))
            .setNegativeButtonText(context.getString(R.string.cancel))
            .build()
        prompt.authenticate(promptInfo)
    }

    val requestUnlock: () -> Unit = request@{
        if (!settingsState.appLockEnabled) {
            isAppLocked = false
            showPinDialog = false
            showPatternDialog = false
            return@request
        }

        if (!settingsState.faceIdEnabled && !settingsState.pinEnabled && !settingsState.patternEnabled) {
            isAppLocked = false
            showPinDialog = false
            showPatternDialog = false
            return@request
        }

        isAppLocked = true
        if (settingsState.faceIdEnabled) {
            unlockWithBiometric()
        } else if (settingsState.pinEnabled) {
            showPinDialog = true
        } else if (settingsState.patternEnabled) {
            showPatternDialog = true
        }
    }

    DisposableEffect(
        lifecycleOwner,
        settingsState.appLockEnabled,
        settingsState.faceIdEnabled,
        settingsState.pinEnabled,
        settingsState.patternEnabled
    ) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    requestUnlock()
                }
                Lifecycle.Event.ON_STOP -> {
                    if (settingsState.appLockEnabled) {
                        isAppLocked = true
                    }
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
                                SyncScreen(syncViewModel, projectsState.projects)
                            }
                            composable(AppDestination.SYNC_LOGS.route) {
                                SyncLogsScreen(syncLogsViewModel)
                            }
                            composable(AppDestination.SETTINGS.route) {
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    onOpenSyncLogs = { navController.navigate(AppDestination.SYNC_LOGS.route) }
                                )
                            }
                        }
                    }

                    if (isAppLocked && settingsState.appLockEnabled) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.security_unlock_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                Text(
                                    text = stringResource(R.string.security_unlock_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                TextButton(
                                    onClick = { requestUnlock() },
                                    modifier = Modifier
                                        .padding(top = 16.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.security_unlock_button))
                                }
                                if (settingsState.pinEnabled) {
                                    TextButton(
                                        onClick = { showPinDialog = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.security_unlock_pin_button))
                                    }
                                }
                                if (settingsState.patternEnabled) {
                                    TextButton(
                                        onClick = { showPatternDialog = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.security_unlock_pattern_button))
                                    }
                                }
                            }
                        }
                    }

                    if (showPinDialog && settingsState.appLockEnabled && settingsState.pinEnabled) {
                        AlertDialog(
                            onDismissRequest = { showPinDialog = false },
                            title = { Text(stringResource(R.string.settings_pin_dialog_title)) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.settings_pin_dialog_message))
                                    OutlinedTextField(
                                        value = pinDraft,
                                        onValueChange = { value ->
                                            pinDraft = value.filter { it.isDigit() }.take(8)
                                            pinError = false
                                        },
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.settings_pin_input_label)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                        visualTransformation = PasswordVisualTransformation(),
                                        isError = pinError,
                                        supportingText = {
                                            if (pinError) {
                                                Text(stringResource(R.string.settings_pin_invalid))
                                            }
                                        }
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (settingsViewModel.verifyPin(pinDraft)) {
                                            showPinDialog = false
                                            isAppLocked = false
                                            pinDraft = ""
                                            pinError = false
                                        } else {
                                            pinError = true
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.security_unlock_button))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPinDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }

                    if (showPatternDialog && settingsState.appLockEnabled && settingsState.patternEnabled) {
                        AlertDialog(
                            onDismissRequest = { showPatternDialog = false },
                            title = { Text(stringResource(R.string.settings_pattern_dialog_title)) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.settings_pattern_dialog_message))
                                    PatternLockView(
                                        pattern = patternDraft,
                                        onPatternChange = {
                                            patternDraft = it
                                            patternError = false
                                        },
                                        activeColor = MaterialTheme.colorScheme.primary,
                                        inactiveColor = MaterialTheme.colorScheme.outlineVariant
                                    )
                                    if (patternError) {
                                        Text(
                                            text = stringResource(R.string.settings_pattern_invalid),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            patternDraft = emptyList()
                                            patternError = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.settings_pattern_clear))
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (settingsViewModel.verifyPattern(patternDraft)) {
                                            showPatternDialog = false
                                            isAppLocked = false
                                            patternDraft = emptyList()
                                            patternError = false
                                        } else {
                                            patternError = true
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.security_unlock_button))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPatternDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): FragmentActivity? {
    return when (this) {
        is FragmentActivity -> this
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
        AppDestination.SYNC_LOGS -> stringResource(R.string.history_sync_logs_section)
        AppDestination.SETTINGS -> stringResource(R.string.tab_settings)
    }
}
