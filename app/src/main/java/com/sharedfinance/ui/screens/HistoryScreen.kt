package com.sharedfinance.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.model.HistoryOperationType
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asShortDate
import com.sharedfinance.ui.utils.asShortDateTime
import com.sharedfinance.viewmodel.HistoryDateFilter
import com.sharedfinance.viewmodel.HistoryOperationFilter
import com.sharedfinance.viewmodel.HistoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsState()

    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var operationExpanded by remember { mutableStateOf(false) }
    var dateExpanded by remember { mutableStateOf(false) }

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
        }
    }
}
