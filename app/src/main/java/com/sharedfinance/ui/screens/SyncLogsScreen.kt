package com.sharedfinance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.model.SyncResultType
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asShortDateTime
import com.sharedfinance.viewmodel.SyncLogResultFilter
import com.sharedfinance.viewmodel.SyncLogsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogsScreen(viewModel: SyncLogsViewModel) {
    val state by viewModel.state.collectAsState()
    var resultExpanded by remember { mutableStateOf(false) }

    val selectedResultLabel = when (state.selectedResultFilter) {
        SyncLogResultFilter.ALL -> stringResource(R.string.history_result_all)
        SyncLogResultFilter.SUCCESS -> stringResource(R.string.history_result_success)
        SyncLogResultFilter.CONFLICT -> stringResource(R.string.history_result_conflict)
        SyncLogResultFilter.FAILED -> stringResource(R.string.history_result_failed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PremiumHeaderCard(
            title = stringResource(R.string.history_sync_logs_section),
            subtitle = stringResource(R.string.sync_result_filter)
        )

        PremiumSectionCard {
            OutlinedTextField(
                value = state.searchText,
                onValueChange = viewModel::updateSearchText,
                label = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = resultExpanded,
                onExpandedChange = { resultExpanded = !resultExpanded }
            ) {
                OutlinedTextField(
                    value = selectedResultLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.sync_result_filter)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resultExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                DropdownMenu(
                    expanded = resultExpanded,
                    onDismissRequest = { resultExpanded = false }
                ) {
                    SyncLogResultFilter.values().forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (option) {
                                        SyncLogResultFilter.ALL -> stringResource(R.string.history_result_all)
                                        SyncLogResultFilter.SUCCESS -> stringResource(R.string.history_result_success)
                                        SyncLogResultFilter.CONFLICT -> stringResource(R.string.history_result_conflict)
                                        SyncLogResultFilter.FAILED -> stringResource(R.string.history_result_failed)
                                    }
                                )
                            },
                            onClick = {
                                viewModel.updateResultFilter(option)
                                resultExpanded = false
                            }
                        )
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
    }
}
