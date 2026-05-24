package com.sharedfinance.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.model.HistoryOperationType
import com.sharedfinance.model.SyncResultType
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asShortDate
import com.sharedfinance.ui.utils.asShortDateTime
import com.sharedfinance.viewmodel.ConflictDecisionFilter
import com.sharedfinance.viewmodel.HistoryDateFilter
import com.sharedfinance.viewmodel.HistoryOperationFilter
import com.sharedfinance.viewmodel.HistorySyncResultFilter
import com.sharedfinance.viewmodel.HistoryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var operationExpanded by remember { mutableStateOf(false) }
    var dateExpanded by remember { mutableStateOf(false) }
    var syncResultExpanded by remember { mutableStateOf(false) }
    var decisionExpanded by remember { mutableStateOf(false) }
    var exportStatusKey by rememberSaveable { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            exportStatusKey = "history_export_error"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bytes = viewModel.exportFilteredConflictLogsData()
            if (bytes == null) {
                exportStatusKey = "history_export_error"
                return@launch
            }
            val written = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(bytes)
                } ?: error("Output stream unavailable")
                true
            }.getOrElse { false }
            exportStatusKey = if (written) "history_export_done" else "history_export_error"
        }
    }

    val selectedOperationLabel = when (state.selectedOperation) {
        HistoryOperationFilter.ALL -> stringResource(R.string.all)
        HistoryOperationFilter.CREATE -> stringResource(R.string.created)
        HistoryOperationFilter.UPDATE -> stringResource(R.string.updated)
        HistoryOperationFilter.DELETE -> stringResource(R.string.deleted)
        HistoryOperationFilter.SYNC -> stringResource(R.string.synced)
    }

    val selectedDateLabel = when (state.selectedDateFilter) {
        HistoryDateFilter.ALL -> stringResource(R.string.filter_all_dates)
        HistoryDateFilter.TODAY -> stringResource(R.string.filter_today)
        HistoryDateFilter.SEVEN_DAYS -> stringResource(R.string.filter_7_days)
        HistoryDateFilter.THIRTY_DAYS -> stringResource(R.string.filter_30_days)
    }

    val selectedSyncResultLabel = when (state.selectedSyncResultFilter) {
        HistorySyncResultFilter.ALL -> stringResource(R.string.history_result_all)
        HistorySyncResultFilter.SUCCESS -> stringResource(R.string.history_result_success)
        HistorySyncResultFilter.CONFLICT -> stringResource(R.string.history_result_conflict)
        HistorySyncResultFilter.FAILED -> stringResource(R.string.history_result_failed)
    }

    val selectedDecisionLabel = when (state.selectedDecisionFilter) {
        ConflictDecisionFilter.ALL -> stringResource(R.string.history_filter_all_decisions)
        ConflictDecisionFilter.ACCEPT_REMOTE -> stringResource(R.string.history_filter_accept_remote)
        ConflictDecisionFilter.KEEP_LOCAL -> stringResource(R.string.history_filter_keep_local)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PremiumHeaderCard(
            title = stringResource(R.string.history_title),
            subtitle = stringResource(R.string.history_filters_section),
            actions = {
                FilledTonalIconButton(onClick = { filtersVisible = !filtersVisible }) {
                    Icon(
                        imageVector = if (filtersVisible) Icons.Filled.FilterList else Icons.Outlined.FilterList,
                        contentDescription = if (filtersVisible) {
                            stringResource(R.string.hide_filters)
                        } else {
                            stringResource(R.string.show_filters)
                        }
                    )
                }
            }
        )

        if (filtersVisible) {
            PremiumSectionCard {
                Text(stringResource(R.string.history_filters_section), style = MaterialTheme.typography.labelLarge)

                ExposedDropdownMenuBox(
                    expanded = operationExpanded,
                    onExpandedChange = { operationExpanded = !operationExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedOperationLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.operation_filter)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = operationExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = operationExpanded,
                        onDismissRequest = { operationExpanded = false }
                    ) {
                        HistoryOperationFilter.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            HistoryOperationFilter.ALL -> stringResource(R.string.all)
                                            HistoryOperationFilter.CREATE -> stringResource(R.string.created)
                                            HistoryOperationFilter.UPDATE -> stringResource(R.string.updated)
                                            HistoryOperationFilter.DELETE -> stringResource(R.string.deleted)
                                            HistoryOperationFilter.SYNC -> stringResource(R.string.synced)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.updateOperationFilter(option)
                                    operationExpanded = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = dateExpanded,
                    onExpandedChange = { dateExpanded = !dateExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedDateLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.date_filter)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dateExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = dateExpanded,
                        onDismissRequest = { dateExpanded = false }
                    ) {
                        HistoryDateFilter.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            HistoryDateFilter.ALL -> stringResource(R.string.filter_all_dates)
                                            HistoryDateFilter.TODAY -> stringResource(R.string.filter_today)
                                            HistoryDateFilter.SEVEN_DAYS -> stringResource(R.string.filter_7_days)
                                            HistoryDateFilter.THIRTY_DAYS -> stringResource(R.string.filter_30_days)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.updateDateFilter(option)
                                    dateExpanded = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = syncResultExpanded,
                    onExpandedChange = { syncResultExpanded = !syncResultExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedSyncResultLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.sync_result_filter)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = syncResultExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = syncResultExpanded,
                        onDismissRequest = { syncResultExpanded = false }
                    ) {
                        HistorySyncResultFilter.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            HistorySyncResultFilter.ALL -> stringResource(R.string.history_result_all)
                                            HistorySyncResultFilter.SUCCESS -> stringResource(R.string.history_result_success)
                                            HistorySyncResultFilter.CONFLICT -> stringResource(R.string.history_result_conflict)
                                            HistorySyncResultFilter.FAILED -> stringResource(R.string.history_result_failed)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.updateSyncResultFilter(option)
                                    syncResultExpanded = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = decisionExpanded,
                    onExpandedChange = { decisionExpanded = !decisionExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedDecisionLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.decision_filter)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = decisionExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = decisionExpanded,
                        onDismissRequest = { decisionExpanded = false }
                    ) {
                        ConflictDecisionFilter.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            ConflictDecisionFilter.ALL -> stringResource(R.string.history_filter_all_decisions)
                                            ConflictDecisionFilter.ACCEPT_REMOTE -> stringResource(R.string.history_filter_accept_remote)
                                            ConflictDecisionFilter.KEEP_LOCAL -> stringResource(R.string.history_filter_keep_local)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.updateDecisionFilter(option)
                                    decisionExpanded = false
                                }
                            )
                        }
                    }
                }

                TextButton(onClick = { exportLauncher.launch("shared_finance_conflicts.json") }) {
                    Text(stringResource(R.string.history_export_conflicts_button))
                }

                if (exportStatusKey.isNotBlank()) {
                    Text(
                        text = if (exportStatusKey == "history_export_done") {
                            stringResource(R.string.history_export_done)
                        } else {
                            stringResource(R.string.history_export_error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exportStatusKey == "history_export_done") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }

        PremiumSectionCard {
            OutlinedTextField(
                value = state.searchText,
                onValueChange = viewModel::updateSearchText,
                label = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.history_changes_section), style = MaterialTheme.typography.labelLarge)
            }

            if (state.groupedEntries.isEmpty()) {
                item { Text(stringResource(R.string.no_records)) }
            } else {
                state.groupedEntries.forEach { group ->
                    item {
                        Text(group.date.asShortDate(), style = MaterialTheme.typography.titleMedium)
                    }
                    items(group.items) { entry ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(entry.description, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.history_by_line, entry.actorName, entry.date.asShortDateTime()))
                                val operationLabel = when (entry.operationType) {
                                    HistoryOperationType.CREATE -> stringResource(R.string.created)
                                    HistoryOperationType.UPDATE -> stringResource(R.string.updated)
                                    HistoryOperationType.DELETE -> stringResource(R.string.deleted)
                                    HistoryOperationType.SYNC -> stringResource(R.string.synced)
                                }
                                Text(stringResource(R.string.history_operation_line, operationLabel))
                            }
                        }
                    }
                }
            }

            item {
                Text(stringResource(R.string.history_sync_logs_section), style = MaterialTheme.typography.labelLarge)
            }

            if (state.filteredSyncLogs.isEmpty()) {
                item { Text(stringResource(R.string.no_records)) }
            } else {
                items(state.filteredSyncLogs) { syncLog ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(syncLog.deviceName, style = MaterialTheme.typography.titleMedium)
                            val resultText = when (syncLog.result) {
                                SyncResultType.SUCCESS -> stringResource(R.string.history_result_success)
                                SyncResultType.CONFLICT -> stringResource(R.string.history_result_conflict)
                                SyncResultType.FAILED -> stringResource(R.string.history_result_failed)
                            }
                            Text(stringResource(R.string.history_sync_log_line, resultText, syncLog.changedRecordsCount))
                            Text(syncLog.date.asShortDateTime(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.history_conflict_resolutions_section),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (state.filteredConflictResolutionLogs.isEmpty()) {
                item { Text(stringResource(R.string.no_records)) }
            } else {
                items(state.filteredConflictResolutionLogs) { conflict ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                stringResource(
                                    R.string.history_conflict_entity_line,
                                    conflict.entityName,
                                    conflict.entityId.toString().take(8)
                                ),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(stringResource(R.string.local_line, conflict.localValue), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.remote_line, conflict.remoteValue), style = MaterialTheme.typography.bodySmall)
                            Text(conflict.date.asShortDateTime(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
