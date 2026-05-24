package com.sharedfinance.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sharedfinance.R
import com.sharedfinance.model.Project
import com.sharedfinance.ui.components.PremiumHeaderCard
import com.sharedfinance.ui.components.PremiumSectionCard
import com.sharedfinance.viewmodel.SyncStatus
import com.sharedfinance.viewmodel.SyncViewModel
import kotlin.math.roundToInt

private val DeviceConnectButtonSize: Dp = 44.dp
private val DeviceConnectIconSize: Dp = 24.dp
private const val ScanPermissionsRequestCode = 1001
private const val ConnectPermissionsRequestCode = 1002

@Composable
fun SyncScreen(viewModel: SyncViewModel, projects: List<Project>) {
    val context = LocalContext.current
    var showProjectChooser by remember { mutableStateOf(false) }
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val activity = (activityResultRegistryOwner as? Activity) ?: remember(context) { context.findActivity() }

    LaunchedEffect(projects) {
        viewModel.refreshProjectSelection(projects)
    }

    val state by viewModel.state.collectAsState()
    val canRunSync = state.connectedDevice != null && !state.isSyncInProgress
    val missingPermissionLabels = buildList {
        if (!hasCoreScanPermission(context)) {
            add(stringResource(R.string.sync_permission_nearby_devices))
        }
        if (!hasLocationPermission(context)) {
            add(stringResource(R.string.sync_permission_location))
        }
    }

    val statusText = when (state.syncStatus) {
        SyncStatus.IDLE -> stringResource(R.string.sync_state_idle)
        SyncStatus.SCANNING -> stringResource(R.string.sync_state_scanning)
        SyncStatus.SCAN_STOPPED -> stringResource(R.string.sync_state_scan_stopped)
        SyncStatus.CONNECTING -> stringResource(R.string.sync_state_connecting)
        SyncStatus.CONNECTED -> stringResource(R.string.sync_state_connected)
        SyncStatus.TRANSFERRING -> stringResource(R.string.sync_state_transferring)
        SyncStatus.WAITING_RESPONSE -> stringResource(R.string.sync_state_waiting_response)
        SyncStatus.SYNC_COMPLETED -> stringResource(R.string.sync_state_completed)
        SyncStatus.TRANSFER_FAILED -> stringResource(R.string.sync_state_transfer_failed)
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
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(
                        onClick = {
                            val missingPermissions = missingScanPermissions(context)
                            if (missingPermissions.isEmpty()) {
                                viewModel.startScan()
                            } else {
                                if (activity != null) {
                                    ActivityCompat.requestPermissions(
                                        activity,
                                        missingPermissions.toTypedArray(),
                                        ScanPermissionsRequestCode
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.scan))
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    OutlinedIconButton(onClick = viewModel::stopScan) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop))
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(
                        onClick = viewModel::connectDemoDevice,
                        enabled = state.devices.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = stringResource(R.string.connect))
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FilledIconButton(
                        onClick = { showProjectChooser = true },
                        enabled = canRunSync && projects.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.sync_now))
                    }
                }
            }
        }

        if (missingPermissionLabels.isNotEmpty()) {
            PremiumSectionCard {
                Text(
                    text = stringResource(R.string.sync_permissions_hint_title),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = stringResource(
                        R.string.sync_permissions_hint_message,
                        missingPermissionLabels.joinToString(separator = ", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showProjectChooser) {
            AlertDialog(
                onDismissRequest = { showProjectChooser = false },
                title = { Text(stringResource(R.string.sync_choose_projects_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.sync_choose_projects_message))
                        if (state.availableProjects.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = viewModel::selectAllProjects) {
                                    Text(stringResource(R.string.sync_select_all_projects))
                                }
                                TextButton(onClick = viewModel::clearSelectedProjects) {
                                    Text(stringResource(R.string.sync_clear_projects))
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.availableProjects) { project ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = project.id in state.selectedProjectIds,
                                            onCheckedChange = { viewModel.toggleProjectSelection(project.id) }
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
                            viewModel.syncNow()
                        },
                        enabled = canRunSync && state.selectedProjectIds.isNotEmpty()
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = "${(state.progress * 100f).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                }
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    text = stringResource(R.string.sync_rssi_label, device.signalStrength),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            FilledTonalIconButton(
                                modifier = Modifier.size(DeviceConnectButtonSize),
                                onClick = {
                                    if (hasConnectPermissions(context)) {
                                        viewModel.toggleDeviceConnection(device)
                                    } else {
                                        if (activity != null) {
                                            ActivityCompat.requestPermissions(
                                                activity,
                                                requiredConnectPermissions(),
                                                ConnectPermissionsRequestCode
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = stringResource(R.string.connect),
                                    modifier = Modifier.size(DeviceConnectIconSize)
                                )
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
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun hasScanPermissions(context: Context): Boolean {
    return hasAllRequestedScanPermissions(context)
}

private fun missingScanPermissions(context: Context): List<String> {
    return requiredScanPermissions().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
}

private fun hasCoreScanPermission(context: Context): Boolean {
    return requiredCoreScanPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requiredCoreScanPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun hasAllRequestedScanPermissions(context: Context): Boolean {
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
