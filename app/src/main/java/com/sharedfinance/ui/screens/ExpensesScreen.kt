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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asCurrency
import com.sharedfinance.ui.utils.asShortDateTime
import com.sharedfinance.viewmodel.ExpenseSortOption
import com.sharedfinance.viewmodel.ExpensesViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(viewModel: ExpensesViewModel) {
    val state by viewModel.state.collectAsState()
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var draftParticipantMenuExpanded by remember { mutableStateOf(false) }
    var filterParticipantMenuExpanded by remember { mutableStateOf(false) }
    var expenseToEdit by remember { mutableStateOf<UUID?>(null) }
    var editTitle by rememberSaveable { mutableStateOf("") }
    var editAmount by rememberSaveable { mutableStateOf("") }
    var editComment by rememberSaveable { mutableStateOf("") }

    val amountValue = state.draftAmount.replace(",", ".").toDoubleOrNull()
    val canAddExpense = state.draftTitle.trim().isNotEmpty() &&
        (amountValue != null && amountValue > 0.0) &&
        state.selectedDraftParticipantId != null &&
        state.participants.isNotEmpty()

    val selectedDraftParticipantName = state.participants
        .firstOrNull { it.id == state.selectedDraftParticipantId }
        ?.name
        ?: state.participants.firstOrNull()?.name
        ?: stringResource(R.string.none)

    val selectedFilterParticipantName = state.participants
        .firstOrNull { it.id == state.selectedFilterParticipantId }
        ?.name
        ?: stringResource(R.string.filter_all)

    val selectedSortLabel = when (state.selectedSort) {
        ExpenseSortOption.NEWEST -> stringResource(R.string.sort_newest)
        ExpenseSortOption.OLDEST -> stringResource(R.string.sort_oldest)
        ExpenseSortOption.AMOUNT_DESC -> stringResource(R.string.sort_amount_desc)
        ExpenseSortOption.AMOUNT_ASC -> stringResource(R.string.sort_amount_asc)
    }

    if (!state.validationMessage.isNullOrBlank()) {
        val validationText = when (state.validationMessage) {
            "expense_validation_amount_positive" -> stringResource(R.string.expense_validation_amount_positive)
            "expense_validation_participant_not_in_project" -> stringResource(R.string.expense_validation_participant_not_in_project)
            "expense_validation_not_found" -> stringResource(R.string.expense_validation_not_found)
            else -> state.validationMessage
        }
        PremiumSectionCard {
            Text(validationText, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::clearValidationMessage) {
                Text(stringResource(R.string.ok))
            }
        }
    }

    if (expenseToEdit != null) {
        AlertDialog(
            onDismissRequest = {
                expenseToEdit = null
                editTitle = ""
                editAmount = ""
                editComment = ""
            },
            title = { Text(stringResource(R.string.edit_expense)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text(stringResource(R.string.expense_title)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAmount,
                        onValueChange = { editAmount = it },
                        label = { Text(stringResource(R.string.expense_amount)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editComment,
                        onValueChange = { editComment = it },
                        label = { Text(stringResource(R.string.expense_comment)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetId = expenseToEdit
                    val parsedAmount = editAmount.replace(",", ".").toDoubleOrNull()
                    if (targetId != null && parsedAmount != null) {
                        viewModel.updateExpense(
                            expenseId = targetId,
                            title = editTitle.trim(),
                            amount = parsedAmount,
                            comment = editComment.trim()
                        )
                    }
                    expenseToEdit = null
                    editTitle = ""
                    editAmount = ""
                    editComment = ""
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    expenseToEdit = null
                    editTitle = ""
                    editAmount = ""
                    editComment = ""
                }) {
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
            title = stringResource(R.string.expenses_title),
            subtitle = stringResource(R.string.sort_filter_section),
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

        PremiumSectionCard {
            OutlinedTextField(
                value = state.draftTitle,
                onValueChange = viewModel::updateDraftTitle,
                label = { Text(stringResource(R.string.expense_title)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.draftAmount,
                onValueChange = viewModel::updateDraftAmount,
                label = { Text(stringResource(R.string.expense_amount)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.draftComment,
                onValueChange = viewModel::updateDraftComment,
                label = { Text(stringResource(R.string.expense_comment)) },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.participants.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = draftParticipantMenuExpanded,
                    onExpandedChange = { draftParticipantMenuExpanded = !draftParticipantMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedDraftParticipantName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.expense_participant)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = draftParticipantMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = draftParticipantMenuExpanded,
                        onDismissRequest = { draftParticipantMenuExpanded = false }
                    ) {
                        state.participants.forEach { participant ->
                            DropdownMenuItem(
                                text = { Text(participant.name) },
                                onClick = {
                                    viewModel.selectDraftParticipant(participant.id)
                                    draftParticipantMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Button(onClick = viewModel::addExpense, enabled = canAddExpense) {
                Text(stringResource(R.string.add_expense))
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
                        ExpenseSortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            ExpenseSortOption.NEWEST -> stringResource(R.string.sort_newest)
                                            ExpenseSortOption.OLDEST -> stringResource(R.string.sort_oldest)
                                            ExpenseSortOption.AMOUNT_DESC -> stringResource(R.string.sort_amount_desc)
                                            ExpenseSortOption.AMOUNT_ASC -> stringResource(R.string.sort_amount_asc)
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
                    expanded = filterParticipantMenuExpanded,
                    onExpandedChange = {
                        if (state.participants.isNotEmpty()) {
                            filterParticipantMenuExpanded = !filterParticipantMenuExpanded
                        }
                    }
                ) {
                    OutlinedTextField(
                        value = selectedFilterParticipantName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.filter_by_participant)) },
                        enabled = state.participants.isNotEmpty(),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterParticipantMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = filterParticipantMenuExpanded,
                        onDismissRequest = { filterParticipantMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filter_all)) },
                            onClick = {
                                viewModel.selectFilterParticipant(null)
                                filterParticipantMenuExpanded = false
                            }
                        )
                        state.participants.forEach { participant ->
                            DropdownMenuItem(
                                text = { Text(participant.name) },
                                onClick = {
                                    viewModel.selectFilterParticipant(participant.id)
                                    filterParticipantMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (state.filteredExpenses.isEmpty()) {
            PremiumSectionCard {
                Text(
                    text = if (state.searchText.isBlank()) {
                        stringResource(R.string.no_expenses)
                    } else {
                        stringResource(R.string.no_search_results)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        PremiumSectionCard {
            Text(
                stringResource(R.string.expense_list_section),
                style = MaterialTheme.typography.labelLarge
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.filteredExpenses) { expense ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(expense.title, style = MaterialTheme.typography.titleMedium)
                            Text(expense.amount.asCurrency())
                            if (expense.comment.isNotBlank()) {
                                Text(expense.comment, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(expense.date.asShortDateTime(), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                expenseToEdit = expense.id
                                editTitle = expense.title
                                editAmount = expense.amount.toString()
                                editComment = expense.comment
                            }) {
                                Text(stringResource(R.string.edit))
                            }
                            Button(onClick = { viewModel.deleteExpense(expense.id) }) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}
