package com.sharedfinance.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.viewmodel.AppThemeOption
import com.sharedfinance.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themeExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            viewModel.markExportError()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bytes = viewModel.exportBackupData()
            if (bytes == null) {
                viewModel.markExportError()
                return@launch
            }
            val written = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(bytes)
                } ?: error("Output stream unavailable")
                true
            }.getOrElse { false }

            if (written) {
                viewModel.markExportDone()
            } else {
                viewModel.markExportError()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            viewModel.markImportError()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            }.getOrElse { ByteArray(0) }
            val imported = viewModel.importBackupFromJson(bytes)
            if (!imported) {
                viewModel.markImportError()
            }
        }
    }

    val selectedThemeLabel = when (state.selectedTheme) {
        AppThemeOption.SYSTEM -> stringResource(R.string.theme_system)
        AppThemeOption.LIGHT -> stringResource(R.string.theme_light)
        AppThemeOption.DARK -> stringResource(R.string.theme_dark)
    }
    val selectedLanguageLabel = when (state.languageCode) {
        "ru" -> stringResource(R.string.language_ru)
        "en" -> stringResource(R.string.language_en)
        else -> stringResource(R.string.language_en)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PremiumHeaderCard(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_backup_section)
        )

        SectionTitle(stringResource(R.string.settings_language_section))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SettingsPickerRow(
                    title = stringResource(R.string.settings_language_section),
                    value = selectedLanguageLabel,
                    expanded = languageExpanded,
                    onClick = { languageExpanded = !languageExpanded },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.language_ru)) },
                        onClick = {
                            viewModel.setLanguage("ru")
                            languageExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.language_en)) },
                        onClick = {
                            viewModel.setLanguage("en")
                            languageExpanded = false
                        }
                    )
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_theme_section))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SettingsPickerRow(
                    title = stringResource(R.string.settings_theme_section),
                    value = selectedThemeLabel,
                    expanded = themeExpanded,
                    onClick = { themeExpanded = !themeExpanded },
                    modifier = Modifier.fillMaxWidth()
                )

                DropdownMenu(
                    expanded = themeExpanded,
                    onDismissRequest = { themeExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.theme_system)) },
                        onClick = {
                            viewModel.setTheme(AppThemeOption.SYSTEM)
                            themeExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.theme_light)) },
                        onClick = {
                            viewModel.setTheme(AppThemeOption.LIGHT)
                            themeExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.theme_dark)) },
                        onClick = {
                            viewModel.setTheme(AppThemeOption.DARK)
                            themeExpanded = false
                        }
                    )
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_security_section))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_face_id), modifier = Modifier.weight(1f))
                Switch(
                    checked = state.faceIdEnabled,
                    onCheckedChange = viewModel::setFaceIdEnabled
                )
            }
        }

        SectionTitle(stringResource(R.string.settings_backup_section))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { exportLauncher.launch("shared_finance_backup.json") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_export_json), modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                TextButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_import_json), modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (state.backupStatusKey.isNotBlank()) {
            Text(
                text = stringResource(
                    when (state.backupStatusKey) {
                        "settings_export_done" -> R.string.settings_export_done
                        "settings_import_done" -> R.string.settings_import_done
                        "settings_export_error" -> R.string.settings_export_error
                        "settings_import_error" -> R.string.settings_import_error
                        else -> R.string.settings_import_error
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPickerRow(
    title: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
    }
}
