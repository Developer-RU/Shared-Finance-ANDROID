package com.sharedfinance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharedfinance.R
import com.sharedfinance.model.ProjectStatus
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asCurrency
import com.sharedfinance.viewmodel.ProjectsViewModel
import java.util.UUID
import kotlinx.coroutines.launch

private enum class ProjectsStatusFilter {
    ALL,
    ACTIVE,
    ARCHIVED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel, onOpenProject: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var statusFilterExpanded by remember { mutableStateOf(false) }
    var statusFilter by rememberSaveable { mutableStateOf(ProjectsStatusFilter.ALL) }
    var projectToDelete by remember { mutableStateOf<UUID?>(null) }
    val projects = viewModel.filteredProjects.filter { project ->
        when (statusFilter) {
            ProjectsStatusFilter.ALL -> true
            ProjectsStatusFilter.ACTIVE -> project.status == ProjectStatus.ACTIVE
            ProjectsStatusFilter.ARCHIVED -> project.status == ProjectStatus.ARCHIVED
        }
    }
    var createDialogVisible by remember { mutableStateOf(false) }
    val canCreateProject = state.draftTitle.trim().isNotEmpty()
    val statusFilterLabel = when (statusFilter) {
        ProjectsStatusFilter.ALL -> stringResource(R.string.all)
        ProjectsStatusFilter.ACTIVE -> stringResource(R.string.status_active)
        ProjectsStatusFilter.ARCHIVED -> stringResource(R.string.status_archived)
    }

    if (projectToDelete != null) {
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text(stringResource(R.string.delete_project_title)) },
            text = { Text(stringResource(R.string.delete_project_cascade_message)) },
            confirmButton = {
                TextButton(onClick = {
                    projectToDelete?.let(viewModel::deleteProject)
                    projectToDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PremiumHeaderCard(
            title = stringResource(R.string.projects_title),
            subtitle = statusFilterLabel,
            actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                FilledIconButton(onClick = { createDialogVisible = true }) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(R.string.create))
                }
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
                    expanded = statusFilterExpanded,
                    onExpandedChange = { statusFilterExpanded = !statusFilterExpanded }
                ) {
                    OutlinedTextField(
                        value = statusFilterLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.project_status_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusFilterExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = statusFilterExpanded,
                        onDismissRequest = { statusFilterExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.all)) },
                            onClick = {
                                statusFilter = ProjectsStatusFilter.ALL
                                statusFilterExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.status_active)) },
                            onClick = {
                                statusFilter = ProjectsStatusFilter.ACTIVE
                                statusFilterExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.status_archived)) },
                            onClick = {
                                statusFilter = ProjectsStatusFilter.ARCHIVED
                                statusFilterExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (createDialogVisible) {
            AlertDialog(
                onDismissRequest = {
                    createDialogVisible = false
                    viewModel.updateDraftTitle("")
                    viewModel.updateDraftDetails("")
                },
                title = { Text(stringResource(R.string.projects_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.draftTitle,
                            onValueChange = viewModel::updateDraftTitle,
                            label = { Text(stringResource(R.string.project_title)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = state.draftDetails,
                            onValueChange = viewModel::updateDraftDetails,
                            label = { Text(stringResource(R.string.project_details)) },
                            minLines = 2,
                            maxLines = 6,
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.createProject()
                            createDialogVisible = false
                        },
                        enabled = canCreateProject
                    ) {
                        Text(stringResource(R.string.create))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            createDialogVisible = false
                            viewModel.updateDraftTitle("")
                            viewModel.updateDraftDetails("")
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        PremiumSectionCard {
            Text(
                text = stringResource(R.string.projects_title),
                style = MaterialTheme.typography.labelLarge
            )
        }

        if (projects.isEmpty()) {
            PremiumSectionCard {
                Text(
                    text = if (state.searchText.isBlank()) {
                        stringResource(R.string.no_projects)
                    } else {
                        stringResource(R.string.no_search_results)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects) { project ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { newValue ->
                        // Keep the row in the revealed end-to-start state until user action.
                        newValue != SwipeToDismissBoxValue.StartToEnd
                    },
                    positionalThreshold = { it * 0.35f }
                )
                val scope = rememberCoroutineScope()

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = true,
                    enableDismissFromEndToStart = true,
                    backgroundContent = {
                        ProjectSwipeActions(
                            onPinToggle = {
                                viewModel.togglePinned(project.id)
                                scope.launch { dismissState.reset() }
                            },
                            onArchive = {
                                viewModel.archiveProject(project.id)
                                scope.launch { dismissState.reset() }
                            },
                            onDelete = {
                                projectToDelete = project.id
                                scope.launch { dismissState.reset() }
                            },
                            isPinned = project.id in state.pinnedProjectIds,
                            canArchive = project.status == ProjectStatus.ACTIVE,
                            dismissValue = dismissState.dismissDirection
                        )
                    }
                ) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenProject(project.id.toString()) }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        project.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (project.id in state.pinnedProjectIds) {
                                        Icon(
                                            imageVector = Icons.Filled.PushPin,
                                            contentDescription = stringResource(R.string.project_pin_action),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                AssistChip(
                                    onClick = {},
                                    enabled = true,
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (project.status == ProjectStatus.ACTIVE) {
                                            androidx.compose.ui.graphics.Color(0xFF00C853)
                                        } else {
                                            androidx.compose.ui.graphics.Color(0xFFFFD600)
                                        },
                                        labelColor = androidx.compose.ui.graphics.Color(0xFF000000)
                                    ),
                                    label = {
                                        Text(
                                            if (project.status == ProjectStatus.ACTIVE) {
                                                stringResource(R.string.status_active)
                                            } else {
                                                stringResource(R.string.status_archived)
                                            }
                                        )
                                    }
                                )
                            }

                            if (project.details.isNotBlank()) {
                                Text(
                                    project.details,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Group,
                                        contentDescription = stringResource(R.string.participants),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.4.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.participants_count, project.participantIds.size),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.project_balance,
                                        (state.projectBalances[project.id] ?: 0.0).asCurrency()
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSwipeActions(
    onPinToggle: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    isPinned: Boolean,
    canArchive: Boolean,
    dismissValue: SwipeToDismissBoxValue
) {
    val isLeadingActionsVisible = dismissValue == SwipeToDismissBoxValue.StartToEnd
    val isTrailingActionsVisible = dismissValue == SwipeToDismissBoxValue.EndToStart

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .width(92.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isLeadingActionsVisible) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            FilledTonalIconButton(onClick = onPinToggle) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = if (isPinned) {
                        stringResource(R.string.project_unpin_action)
                    } else {
                        stringResource(R.string.project_pin_action)
                    }
                )
            }
        }
        Box(
            modifier = Modifier
                .width(184.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isTrailingActionsVisible) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canArchive) {
                    FilledIconButton(onClick = onArchive) {
                        Icon(
                            imageVector = Icons.Filled.Archive,
                            contentDescription = stringResource(R.string.archive)
                        )
                    }
                }
                OutlinedIconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete)
                    )
                }
            }
        }
    }
}
