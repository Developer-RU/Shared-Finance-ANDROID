package com.sharedfinance.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sharedfinance.R
import com.sharedfinance.model.Project
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.ui.utils.asShortDateTime
import com.sharedfinance.viewmodel.SyncStatus
import com.sharedfinance.viewmodel.SyncViewModel
import kotlin.math.roundToInt

@Composable
fun SyncScreen(viewModel: SyncViewModel, projects: List<Project>, remotePayloadProvider: () -> SyncPayload) {
    val context = LocalContext.current
    var pendingConnectAddress by remember { mutableStateOf<String?>(null) }
    var showProjectChooser by remember { mutableStateOf(false) }
    var selectedProjectIds by remember(projects) { mutableStateOf(projects.map { it.id }.toSet()) }

    LaunchedEffect(projects) {
        if (selectedProjectIds.isEmpty() && projects.isNotEmpty()) {
            selectedProjectIds = projects.map { it.id }.toSet()
        }
    }

    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            val allGranted = result.values.all { it }
            if (allGranted && hasScanPermissions(context)) {
                viewModel.startScan()
            }
        }
    )
    val requestConnectPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            val allGranted = result.values.all { it }
            val targetAddress = pendingConnectAddress
            if (allGranted && targetAddress != null && hasConnectPermissions(context)) {
                viewModel.connectDevice(targetAddress)
            }
            pendingConnectAddress = null
        }
    )

    val state by viewModel.state.collectAsState()
    val decidedCount = state.conflicts.count { state.decisions.containsKey(it.id) }
    val totalConflicts = state.conflicts.size
    val canRunSync = state.connectedDevice != null && !state.isSyncInProgress

    val statusText = when (state.syncStatus) {
        SyncStatus.IDLE -> stringResource(R.string.sync_state_idle)
        SyncStatus.SCANNING -> stringResource(R.string.sync_state_scanning)
        SyncStatus.SCAN_STOPPED -> stringResource(R.string.sync_state_scan_stopped)
        SyncStatus.CONNECTED -> stringResource(R.string.sync_state_connected)
        SyncStatus.CONFLICTS_DETECTED -> stringResource(R.string.sync_state_conflicts_detected, totalConflicts)
        SyncStatus.PREVIEW_READY -> stringResource(R.string.sync_state_preview_ready)
        SyncStatus.SYNC_COMPLETED -> stringResource(R.string.sync_state_completed)
        SyncStatus.SYNC_COMPLETED_WITH_DECISIONS -> stringResource(R.string.sync_state_completed_with_decisions)
        SyncStatus.MISSING_DECISIONS -> stringResource(R.string.sync_state_missing_decisions)
        SyncStatus.TRANSFER_FAILED -> stringResource(R.string.sync_state_transfer_failed)
    }
    val statusMessageText = when (state.statusMessage) {
        "sync_state_scanning" -> stringResource(R.string.sync_state_scanning)
        "sync_state_scan_stopped" -> stringResource(R.string.sync_state_scan_stopped)
        "sync_state_connected" -> stringResource(R.string.sync_state_connected)
        "sync_state_completed" -> stringResource(R.string.sync_state_completed)
        "sync_state_completed_with_decisions" -> stringResource(R.string.sync_state_completed_with_decisions)
        "sync_state_missing_decisions" -> stringResource(R.string.sync_state_missing_decisions)
        "sync_state_transfer_failed" -> stringResource(R.string.sync_state_transfer_failed)
        "sync_state_conflicts_detected" -> stringResource(R.string.sync_state_conflicts_detected, totalConflicts)
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PremiumHeaderCard(
            title = stringResource(R.string.sync_title),
            subtitle = statusText
        )

        PremiumSectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            if (hasScanPermissions(context)) {
                                viewModel.startScan()
                            } else {
                                requestPermissionsLauncher.launch(requiredScanPermissions())
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.scan)
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedIconButton(onClick = viewModel::stopScan) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.stop)
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalIconButton(
                        onClick = viewModel::connectDemoDevice,
                        enabled = state.devices.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = stringResource(R.string.connect)
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FilledIconButton(
                        onClick = { showProjectChooser = true },
                        enabled = canRunSync && projects.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = stringResource(R.string.sync_now)
                        )
                    }
                }
            }
        }

        if (showProjectChooser) {
            AlertDialog(
                onDismissRequest = { showProjectChooser = false },
                title = { Text(stringResource(R.string.sync_choose_projects_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.sync_choose_projects_message))
                        if (projects.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { selectedProjectIds = projects.map { it.id }.toSet() }) {
                                    Text(stringResource(R.string.sync_select_all_projects))
                                }
                                TextButton(onClick = { selectedProjectIds = emptySet() }) {
                                    Text(stringResource(R.string.sync_clear_projects))
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(projects) { project ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = project.id in selectedProjectIds,
                                            onCheckedChange = { checked ->
                                                selectedProjectIds = if (checked) {
                                                    selectedProjectIds + project.id
                                                } else {
                                                    selectedProjectIds - project.id
                                                }
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(project.title, style = MaterialTheme.typography.titleMedium)
                                            if (project.details.isNotBlank()) {
                                                Text(project.details, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(stringResource(R.string.sync_choose_projects_empty))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showProjectChooser = false
                            viewModel.previewConflicts(remotePayloadProvider(), selectedProjectIds)
                        },
                        enabled = canRunSync && selectedProjectIds.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.sync_choose_projects_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showProjectChooser = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (state.progress > 0f && state.progress < 1f) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "${(state.progress * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (state.devices.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
            ) {
                items(state.devices) { device ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleDeviceConnection(device) },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.heightIn(min = 26.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    label = {
                                        Text(stringResource(R.string.sync_rssi_label, device.signalStrength))
                                    }
                                )
                            }

                            Text(
                                text = device.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                FilledTonalIconButton(
                                    modifier = Modifier.size(32.dp),
                                    onClick = {
                                        if (hasConnectPermissions(context)) {
                                            viewModel.toggleDeviceConnection(device)
                                        } else {
                                            pendingConnectAddress = device.address
                                            requestConnectPermissionsLauncher.launch(requiredConnectPermissions())
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Link,
                                        contentDescription = stringResource(R.string.connect)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            PremiumSectionCard {
                Text(stringResource(R.string.no_records))
            }
        }

        if (state.conflicts.isNotEmpty()) {
            Text(stringResource(R.string.history_conflict_resolutions_section), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalIconButton(onClick = { viewModel.chooseAll(false) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallReceived,
                            contentDescription = stringResource(R.string.keep_local_all)
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalIconButton(onClick = { viewModel.chooseAll(true) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallMade,
                            contentDescription = stringResource(R.string.accept_remote_all)
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalIconButton(onClick = viewModel::autoSelectByVersionRule) {
                        Icon(
                            imageVector = Icons.Filled.AutoFixHigh,
                            contentDescription = stringResource(R.string.sync_auto_select)
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.sync_decision_progress, decidedCount, totalConflicts),
                style = MaterialTheme.typography.bodySmall
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.conflicts) { conflict ->
                    val decision = state.decisions[conflict.id]
                    val containerColor = when (decision) {
                        true -> MaterialTheme.colorScheme.tertiaryContainer
                        false -> MaterialTheme.colorScheme.secondaryContainer
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    }

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = containerColor)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${conflict.entityName}: ${conflict.entityId}", style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.local_line, conflict.localValue))
                            Text(stringResource(R.string.remote_line, conflict.remoteValue))
                            Text(stringResource(R.string.sync_local_version_line, conflict.localRecordVersion))
                            Text(stringResource(R.string.sync_remote_version_line, conflict.remoteRecordVersion))
                            Text(stringResource(R.string.sync_local_updated_line, conflict.localUpdatedAt.asShortDateTime()))
                            Text(stringResource(R.string.sync_remote_updated_line, conflict.remoteUpdatedAt.asShortDateTime()))
                            Text(
                                when (decision) {
                                    true -> stringResource(R.string.sync_decision_remote)
                                    false -> stringResource(R.string.sync_decision_local)
                                    null -> stringResource(R.string.sync_decision_pending)
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    FilledTonalIconButton(onClick = { viewModel.chooseDecision(conflict.id, false) }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.CallReceived,
                                            contentDescription = stringResource(R.string.keep_local)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    FilledIconButton(onClick = { viewModel.chooseDecision(conflict.id, true) }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.CallMade,
                                            contentDescription = stringResource(R.string.accept_remote)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hasScanPermissions(context: Context): Boolean {
    return requiredScanPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun hasConnectPermissions(context: Context): Boolean {
    return requiredConnectPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requiredScanPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun requiredConnectPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }
}
