package com.sharedfinance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asCurrency
import com.sharedfinance.viewmodel.BalanceFilterOption
import com.sharedfinance.viewmodel.BalanceSortOption
import com.sharedfinance.viewmodel.BalanceViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceScreen(viewModel: BalanceViewModel) {
    val state by viewModel.state.collectAsState()
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var participantToDelete by remember { mutableStateOf<UUID?>(null) }
    val selectedSortLabel = when (state.selectedSort) {
        BalanceSortOption.BALANCE_DESC -> stringResource(R.string.sort_balance_desc)
        BalanceSortOption.BALANCE_ASC -> stringResource(R.string.sort_balance_asc)
        BalanceSortOption.NAME_ASC -> stringResource(R.string.sort_name)
    }
    val selectedFilterLabel = when (state.selectedFilter) {
        BalanceFilterOption.ALL -> stringResource(R.string.filter_all)
        BalanceFilterOption.POSITIVE -> stringResource(R.string.filter_positive)
        BalanceFilterOption.NEGATIVE -> stringResource(R.string.filter_negative)
        BalanceFilterOption.ZERO -> stringResource(R.string.filter_zero)
    }

    if (participantToDelete != null) {
        val hasExpenses = participantToDelete?.let(viewModel::participantHasExpenses) == true
        AlertDialog(
            onDismissRequest = { participantToDelete = null },
            title = { Text(stringResource(R.string.delete_participant_title)) },
            text = {
                Text(
                    if (hasExpenses) {
                        stringResource(R.string.delete_participant_with_expenses_message)
                    } else {
                        stringResource(R.string.delete_participant_message)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    participantToDelete?.let(viewModel::deleteParticipant)
                    participantToDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { participantToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PremiumHeaderCard(
            title = stringResource(R.string.balance_title),
            subtitle = stringResource(R.string.net_balance),
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
        })

        PremiumSectionCard {
            OutlinedTextField(
                value = state.searchText,
                onValueChange = viewModel::updateSearchText,
                label = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (filtersVisible) {
            PremiumSectionCard {
                Text(
                    stringResource(R.string.history_filters_section),
                    style = MaterialTheme.typography.labelLarge
                )

                ExposedDropdownMenuBox(
                    expanded = sortExpanded,
                    onExpandedChange = { sortExpanded = !sortExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedSortLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.sort_by)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        BalanceSortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            BalanceSortOption.BALANCE_DESC -> stringResource(R.string.sort_balance_desc)
                                            BalanceSortOption.BALANCE_ASC -> stringResource(R.string.sort_balance_asc)
                                            BalanceSortOption.NAME_ASC -> stringResource(R.string.sort_name)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.updateSort(option)
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = filterExpanded,
                    onExpandedChange = { filterExpanded = !filterExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedFilterLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.filter_all)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = filterExpanded,
                        onDismissRequest = { filterExpanded = false }
                    ) {
                        BalanceFilterOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            BalanceFilterOption.ALL -> stringResource(R.string.filter_all)
                                            BalanceFilterOption.POSITIVE -> stringResource(R.string.filter_positive)
                                            BalanceFilterOption.NEGATIVE -> stringResource(R.string.filter_negative)
                                            BalanceFilterOption.ZERO -> stringResource(R.string.filter_zero)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.updateFilter(option)
                                    filterExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        PremiumSectionCard {
            Text(
                stringResource(R.string.balance_title),
                style = MaterialTheme.typography.labelLarge
            )
        }

        if (state.filteredParticipants.isEmpty()) {
            PremiumSectionCard {
                Text(
                    text = if (state.searchText.isBlank()) {
                        stringResource(R.string.no_participants)
                    } else {
                        stringResource(R.string.no_search_results)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.filteredParticipants) { participant ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(participant.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.contribution_line, participant.contributionAmount.asCurrency()))
                            Text(stringResource(R.string.total_expense, participant.expenseAmount.asCurrency()))
                            Text(stringResource(R.string.project_balance, participant.balanceAmount.asCurrency()))
                        }
                        Button(onClick = { participantToDelete = participant.id }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}
