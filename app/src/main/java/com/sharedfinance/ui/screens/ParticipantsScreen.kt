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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asCurrency
import com.sharedfinance.viewmodel.ParticipantBalanceFilter
import com.sharedfinance.viewmodel.ParticipantSortOption
import com.sharedfinance.viewmodel.ParticipantsViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantsScreen(viewModel: ParticipantsViewModel) {
    val state by viewModel.state.collectAsState()
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var balanceExpanded by remember { mutableStateOf(false) }
    var participantToRename by remember { mutableStateOf<UUID?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var participantToDelete by remember { mutableStateOf<UUID?>(null) }
    val canAddParticipant = state.draftName.trim().isNotEmpty() && state.draftContribution.trim().isNotEmpty()
    val selectedSortLabel = when (state.selectedSort) {
        ParticipantSortOption.NAME_ASC -> stringResource(R.string.sort_name)
        ParticipantSortOption.CONTRIBUTION_DESC -> stringResource(R.string.sort_contribution_desc)
        ParticipantSortOption.CONTRIBUTION_ASC -> stringResource(R.string.sort_contribution_asc)
        ParticipantSortOption.BALANCE_DESC -> stringResource(R.string.sort_balance_desc)
        ParticipantSortOption.BALANCE_ASC -> stringResource(R.string.sort_balance_asc)
    }
    val selectedBalanceFilterLabel = when (state.selectedBalanceFilter) {
        ParticipantBalanceFilter.ALL -> stringResource(R.string.filter_all)
        ParticipantBalanceFilter.POSITIVE -> stringResource(R.string.filter_positive)
        ParticipantBalanceFilter.NEGATIVE -> stringResource(R.string.filter_negative)
        ParticipantBalanceFilter.ZERO -> stringResource(R.string.filter_zero)
    }

    if (participantToRename != null) {
        AlertDialog(
            onDismissRequest = {
                participantToRename = null
                renameText = ""
            },
            title = { Text(stringResource(R.string.rename_participant)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.participant_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetId = participantToRename
                        if (targetId != null && renameText.isNotBlank()) {
                            viewModel.updateParticipantName(targetId, renameText.trim())
                        }
                        participantToRename = null
                        renameText = ""
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        participantToRename = null
                        renameText = ""
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
            title = stringResource(R.string.participants_title),
            subtitle = stringResource(R.string.participant_list_section),
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
                    stringResource(R.string.sort_filter_section),
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
                        ParticipantSortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            ParticipantSortOption.NAME_ASC -> stringResource(R.string.sort_name)
                                            ParticipantSortOption.CONTRIBUTION_DESC -> stringResource(R.string.sort_contribution_desc)
                                            ParticipantSortOption.CONTRIBUTION_ASC -> stringResource(R.string.sort_contribution_asc)
                                            ParticipantSortOption.BALANCE_DESC -> stringResource(R.string.sort_balance_desc)
                                            ParticipantSortOption.BALANCE_ASC -> stringResource(R.string.sort_balance_asc)
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
                    expanded = balanceExpanded,
                    onExpandedChange = { balanceExpanded = !balanceExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedBalanceFilterLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.history_filters_section)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = balanceExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = balanceExpanded,
                        onDismissRequest = { balanceExpanded = false }
                    ) {
                        ParticipantBalanceFilter.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            ParticipantBalanceFilter.ALL -> stringResource(R.string.filter_all)
                                            ParticipantBalanceFilter.POSITIVE -> stringResource(R.string.filter_positive)
                                            ParticipantBalanceFilter.NEGATIVE -> stringResource(R.string.filter_negative)
                                            ParticipantBalanceFilter.ZERO -> stringResource(R.string.filter_zero)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.updateBalanceFilter(option)
                                    balanceExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (state.searchText.isEmpty()) {
            PremiumSectionCard {
                Text(
                    stringResource(R.string.add_participant_section),
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedTextField(
                    value = state.draftName,
                    onValueChange = viewModel::updateDraftName,
                    label = { Text(stringResource(R.string.participant_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.draftContribution,
                    onValueChange = viewModel::updateDraftContribution,
                    label = { Text(stringResource(R.string.participant_contribution)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = viewModel::addParticipant, enabled = canAddParticipant) {
                    Text(stringResource(R.string.add_participant))
                }
            }
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

        Text(
            stringResource(R.string.participant_list_section),
            style = MaterialTheme.typography.labelLarge
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.filteredParticipants) { participant ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(participant.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.contribution_line, participant.contributionAmount.asCurrency()))
                            Text(
                                stringResource(
                                    R.string.project_balance,
                                    (state.balancesByParticipantId[participant.id] ?: 0.0).asCurrency()
                                )
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                participantToRename = participant.id
                                renameText = participant.name
                            }) {
                                Text(stringResource(R.string.rename))
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
}
