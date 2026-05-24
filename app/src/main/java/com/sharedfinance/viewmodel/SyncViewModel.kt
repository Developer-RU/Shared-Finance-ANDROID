package com.sharedfinance.viewmodel

import com.sharedfinance.ble.BleDevice
import com.sharedfinance.ble.BleManager
import com.sharedfinance.model.Project
import com.sharedfinance.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class SyncStatus {
    IDLE,
    SCANNING,
    SCAN_STOPPED,
    CONNECTING,
    CONNECTED,
    TRANSFERRING,
    WAITING_RESPONSE,
    SYNC_COMPLETED,
    TRANSFER_FAILED
}

data class SyncUiState(
    val isScanning: Boolean = false,
    val connectedDevice: String? = null,
    val connectedDeviceAddress: String? = null,
    val devices: List<BleDevice> = emptyList(),
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val progress: Float = 0f,
    val isSyncInProgress: Boolean = false,
    val statusMessage: String = "",
    val bleDebugStatus: String = "",
    val availableProjects: List<Project> = emptyList(),
    val selectedProjectIds: Set<UUID> = emptySet()
)

class SyncViewModel(
    private val bleManager: BleManager,
    private val syncService: SyncService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    init {
        scope.launch {
            bleManager.isScanning.collectLatest { isScanning ->
                _state.update { it.copy(isScanning = isScanning) }
            }
        }
        scope.launch {
            bleManager.discoveredDevices.collectLatest { devices ->
                _state.update { it.copy(devices = devices) }
            }
        }
        scope.launch {
            bleManager.debugStatus.collectLatest { bleDebugStatus ->
                _state.update { current ->
                    if (!current.isSyncInProgress) {
                        return@update current.copy(bleDebugStatus = bleDebugStatus)
                    }

                    when {
                        bleDebugStatus.startsWith("transfer_waiting_inbound") -> {
                            current.copy(
                                bleDebugStatus = bleDebugStatus,
                                syncStatus = SyncStatus.WAITING_RESPONSE,
                                statusMessage = "sync_state_waiting_response"
                            )
                        }
                        bleDebugStatus.startsWith("transfer_") -> {
                            current.copy(
                                bleDebugStatus = bleDebugStatus,
                                syncStatus = SyncStatus.TRANSFERRING,
                                statusMessage = "sync_state_transferring"
                            )
                        }
                        else -> current.copy(bleDebugStatus = bleDebugStatus)
                    }
                }
            }
        }
        scope.launch {
            bleManager.transferProgress.collectLatest { transferProgress ->
                _state.update { current ->
                    val shouldUpdate = current.isSyncInProgress || transferProgress == 0f || transferProgress == 1f
                    if (shouldUpdate) {
                        current.copy(progress = transferProgress)
                    } else {
                        current
                    }
                }
            }
        }
        scope.launch {
            bleManager.connectedDeviceName.collectLatest { connectedName ->
                _state.update {
                    it.copy(connectedDevice = connectedName)
                }
            }
        }
        scope.launch {
            bleManager.connectedDeviceAddress.collectLatest { connectedAddress ->
                _state.update { current ->
                    if (connectedAddress != null) {
                        current.copy(
                            connectedDeviceAddress = connectedAddress,
                            syncStatus = SyncStatus.CONNECTED,
                            statusMessage = "sync_state_connected"
                        )
                    } else {
                        val fallbackStatus = if (current.isScanning) SyncStatus.SCANNING else SyncStatus.SCAN_STOPPED
                        val fallbackMessage = if (current.isScanning) "sync_state_scanning" else "sync_state_scan_stopped"
                        current.copy(
                            connectedDevice = null,
                            connectedDeviceAddress = null,
                            syncStatus = if (current.isSyncInProgress) current.syncStatus else fallbackStatus,
                            statusMessage = if (current.isSyncInProgress) current.statusMessage else fallbackMessage
                        )
                    }
                }
            }
        }
    }

    fun startScan() {
        runCatching {
            bleManager.startScan()
        }.onSuccess {
            _state.update {
                it.copy(
                    syncStatus = SyncStatus.SCANNING,
                    statusMessage = "sync_state_scanning"
                )
            }
        }.onFailure {
            _state.update {
                it.copy(
                    syncStatus = SyncStatus.SCAN_STOPPED,
                    statusMessage = "sync_state_scan_stopped"
                )
            }
        }
    }

    fun stopScan() {
        bleManager.stopScan()
        _state.update {
            it.copy(
                syncStatus = SyncStatus.SCAN_STOPPED,
                statusMessage = "sync_state_scan_stopped"
            )
        }
    }

    fun connectDevice(deviceAddress: String) {
        bleManager.connect(deviceAddress)
        _state.update {
            it.copy(
                isScanning = false,
                syncStatus = SyncStatus.CONNECTING,
                statusMessage = "sync_state_connecting"
            )
        }
    }

    fun connectDemoDevice() {
        val firstAddress = _state.value.devices.firstOrNull()?.address ?: return
        connectDevice(firstAddress)
    }

    fun toggleDeviceConnection(device: BleDevice) {
        val connectedAddress = _state.value.connectedDeviceAddress?.trim()?.uppercase()
        val targetAddress = device.address.trim().uppercase()
        if (connectedAddress != null && connectedAddress == targetAddress) {
            bleManager.disconnect()
        } else {
            connectDevice(device.address)
        }
    }

    fun refreshProjectSelection(projects: List<Project>) {
        _state.update {
            val selected = if (it.selectedProjectIds.isEmpty()) projects.map { p -> p.id }.toSet() else it.selectedProjectIds
            it.copy(availableProjects = projects, selectedProjectIds = selected)
        }
    }

    fun toggleProjectSelection(projectId: UUID) {
        _state.update {
            val selected = if (projectId in it.selectedProjectIds) {
                it.selectedProjectIds - projectId
            } else {
                it.selectedProjectIds + projectId
            }
            it.copy(selectedProjectIds = selected)
        }
    }

    fun selectAllProjects() {
        _state.update { it.copy(selectedProjectIds = it.availableProjects.map { p -> p.id }.toSet()) }
    }

    fun clearSelectedProjects() {
        _state.update { it.copy(selectedProjectIds = emptySet()) }
    }

    fun syncNow() {
        scope.launch {
            _state.update {
                it.copy(
                    isSyncInProgress = true,
                    progress = 0.01f,
                    syncStatus = SyncStatus.TRANSFERRING,
                    statusMessage = "sync_state_transferring"
                )
            }
            val status = syncService.syncNow(bleManager, _state.value.selectedProjectIds)
            if (status == "sync_state_completed") {
                _state.update {
                    it.copy(
                        syncStatus = SyncStatus.SYNC_COMPLETED,
                        isSyncInProgress = false,
                        progress = 1f,
                        statusMessage = "sync_state_completed"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        syncStatus = SyncStatus.TRANSFER_FAILED,
                        isSyncInProgress = false,
                        progress = 0f,
                        statusMessage = "sync_state_transfer_failed"
                    )
                }
            }
        }
    }
}
