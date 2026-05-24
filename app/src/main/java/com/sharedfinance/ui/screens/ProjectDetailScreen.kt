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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.model.ProjectStatus
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.utils.asCurrency
import com.sharedfinance.viewmodel.ProjectDetailViewModel
import com.sharedfinance.viewmodel.ProjectsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectsViewModel: ProjectsViewModel,
    detailViewModel: ProjectDetailViewModel,
    onOpenExpenses: () -> Unit,
    onOpenParticipants: () -> Unit
) {
    val projectsState by projectsViewModel.state.collectAsState()
    val detailState by detailViewModel.state.collectAsState()
    val project = detailState.project
    var statusExpanded by remember { mutableStateOf(false) }
    var editDialogVisible by remember(project?.id, projectsState.editingProjectId) {
        mutableStateOf(project != null && projectsState.editingProjectId == project.id)
    }

    if (project == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.project_not_found), style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    if (editDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                editDialogVisible = false
                projectsViewModel.cancelEdit()
            },
            title = { Text(stringResource(R.string.project_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = projectsState.editingTitle,
                        onValueChange = projectsViewModel::updateEditingTitle,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.project_title_placeholder)) }
                    )
                    OutlinedTextField(
                        value = projectsState.editingDetails,
                        onValueChange = projectsViewModel::updateEditingDetails,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.project_details_placeholder)) },
                        minLines = 2,
                        maxLines = 6,
                        singleLine = false
                    )
                    ExposedDropdownMenuBox(
                        expanded = statusExpanded,
                        onExpandedChange = { statusExpanded = !statusExpanded }
                    ) {
                        OutlinedTextField(
                            value = if (projectsState.editingStatus == ProjectStatus.ACTIVE) {
                                stringResource(R.string.project_status_active)
                            } else {
                                stringResource(R.string.project_status_archived)
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.project_status_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = statusExpanded,
                            onDismissRequest = { statusExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_status_active)) },
                                onClick = {
                                    projectsViewModel.updateEditingStatus(ProjectStatus.ACTIVE)
                                    statusExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_status_archived)) },
                                onClick = {
                                    projectsViewModel.updateEditingStatus(ProjectStatus.ARCHIVED)
                                    statusExpanded = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        projectsViewModel.saveEdit()
                        editDialogVisible = false
                    },
                    enabled = projectsState.editingTitle.trim().isNotEmpty()
                ) {
                    Text(stringResource(R.string.project_save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    editDialogVisible = false
                    projectsViewModel.cancelEdit()
                }) {
                    Text(stringResource(R.string.project_cancel_button))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PremiumHeaderCard(
                title = project.title,
                subtitle = if (project.status == ProjectStatus.ACTIVE) {
                    stringResource(R.string.project_status_active)
                } else {
                    stringResource(R.string.project_status_archived)
                },
                actions = {
                    IconButton(onClick = {
                        projectsViewModel.beginEdit(project)
                        editDialogVisible = true
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                    }
                }
            )
        }

        item {
            Text(stringResource(R.string.project_expenses_section), style = MaterialTheme.typography.labelLarge)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenExpenses, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.project_open_expenses))
                }
            }
        }

        item {
            Text(stringResource(R.string.project_info_section), style = MaterialTheme.typography.labelLarge)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.title, style = MaterialTheme.typography.titleMedium)
                    Text(project.details, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.project_status_label))
                        Text(
                            if (project.status == ProjectStatus.ACTIVE) {
                                stringResource(R.string.project_status_active)
                            } else {
                                stringResource(R.string.project_status_archived)
                            }
                        )
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.project_participants_section), style = MaterialTheme.typography.labelLarge)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenParticipants, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.project_manage_participants))
                }
            }
        }

        item {
            Text(stringResource(R.string.project_balance_section), style = MaterialTheme.typography.labelLarge)
        }

        if (detailState.participants.isEmpty()) {
            item {
                Text(stringResource(R.string.no_participants), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(detailState.participants) { participant ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(participant.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.contribution_line, participant.contributionAmount.asCurrency()))
                        Text(
                            stringResource(
                                R.string.project_balance,
                                (detailState.participantBalances[participant.id] ?: 0.0).asCurrency()
                            )
                        )
                    }
                }
            }
        }
    }
}
