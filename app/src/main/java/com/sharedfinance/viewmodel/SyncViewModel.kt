package com.sharedfinance.viewmodel

import com.sharedfinance.ble.BleDevice
import com.sharedfinance.ble.BleManager
import com.sharedfinance.model.Expense
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import com.sharedfinance.model.SyncConflict
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.sync.SyncService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SyncStatus {
    IDLE,
    SCANNING,
    SCAN_STOPPED,
    CONNECTED,
    CONFLICTS_DETECTED,
    PREVIEW_READY,
    SYNC_COMPLETED,
    SYNC_COMPLETED_WITH_DECISIONS,
    MISSING_DECISIONS,
    TRANSFER_FAILED
}

data class SyncUiState(
    val isScanning: Boolean = false,
    val connectedDevice: String? = null,
    val connectedDeviceAddress: String? = null,
    val devices: List<BleDevice> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
    val decisions: Map<UUID, Boolean> = emptyMap(),
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val hasPendingPayload: Boolean = false,
    val progress: Float = 0f,
    val isSyncInProgress: Boolean = false,
    val statusMessage: String = "",
    val bleDebugStatus: String = ""
)

class SyncViewModel(
    private val bleManager: BleManager,
    private val syncService: SyncService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()
    private var pendingPayload: SyncPayload? = null
    private var pendingLocalPayload: SyncPayload? = null
    @Volatile private var previewInProgress = false

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
                _state.update { it.copy(bleDebugStatus = bleDebugStatus) }
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
                    if (connectedName != null) {
                        it.copy(
                            connectedDevice = connectedName,
                            syncStatus = SyncStatus.CONNECTED,
                            statusMessage = "sync_state_connected"
                        )
                    } else {
                        it.copy(connectedDevice = null)
                    }
                }
            }
        }
        scope.launch {
            bleManager.connectedDeviceAddress.collectLatest { connectedAddress ->
                _state.update { it.copy(connectedDeviceAddress = connectedAddress) }
            }
        }
    }

    fun startScan() {
        bleManager.startScan()
        _state.update {
            it.copy(
                syncStatus = SyncStatus.SCANNING,
                statusMessage = "sync_state_scanning"
            )
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
                statusMessage = "sync_state_scan_stopped"
            )
        }
    }

    fun connectDemoDevice() {
        val firstAddress = _state.value.devices.firstOrNull()?.address ?: return
        connectDevice(firstAddress)
    }

    fun toggleDeviceConnection(device: BleDevice) {
        val connectedAddress = _state.value.connectedDeviceAddress
            ?.trim()
            ?.uppercase()
        val targetAddress = device.address.trim().uppercase()
        if (connectedAddress != null && connectedAddress == targetAddress) {
            bleManager.disconnect()
        } else {
            connectDevice(device.address)
        }
    }

    fun chooseDecision(conflictId: UUID, acceptRemote: Boolean) {
        _state.update { state ->
            state.copy(decisions = state.decisions + (conflictId to acceptRemote))
        }
    }

    fun chooseAll(acceptRemote: Boolean) {
        val updated = _state.value.conflicts.associate { it.id to acceptRemote }
        _state.update { it.copy(decisions = updated) }
    }

    fun autoSelectByVersionRule() {
        val autoDecisions = _state.value.conflicts.associate { conflict ->
            val acceptRemote = conflict.remoteRecordVersion >= conflict.localRecordVersion
            conflict.id to acceptRemote
        }
        _state.update {
            it.copy(
                decisions = autoDecisions,
                syncStatus = if (_state.value.conflicts.isEmpty()) SyncStatus.IDLE else SyncStatus.PREVIEW_READY
            )
        }
    }

    fun previewConflicts(remotePayload: SyncPayload, selectedProjectIds: Set<UUID>) {
        if (previewInProgress) return
        scope.launch {
            previewConflictsInternal(remotePayload, selectedProjectIds)
        }
    }

    fun applyDecisions() {
        val localPayload = pendingLocalPayload ?: return
        val payload = pendingPayload ?: return
        val uiState = _state.value
        val decisions = uiState.decisions
        val conflicts = uiState.conflicts
        val hasAllDecisions = conflicts.all { decisions.containsKey(it.id) }
        if (conflicts.isNotEmpty() && !hasAllDecisions) {
            _state.update {
                it.copy(
                    syncStatus = SyncStatus.MISSING_DECISIONS,
                    statusMessage = "sync_state_missing_decisions"
                )
            }
            return
        }
        scope.launch {
            _state.update { it.copy(progress = 0.9f) }
            syncService.apply(localPayload, payload, decisions)
            pendingPayload = null
            pendingLocalPayload = null
            _state.update {
                it.copy(
                    syncStatus = SyncStatus.SYNC_COMPLETED_WITH_DECISIONS,
                    hasPendingPayload = false,
                    conflicts = emptyList(),
                    decisions = emptyMap(),
                    progress = 1f,
                    statusMessage = "sync_state_completed_with_decisions"
                )
            }
        }
    }

    private suspend fun previewConflictsInternal(remotePayload: SyncPayload, selectedProjectIds: Set<UUID>): List<SyncConflict> {
        if (previewInProgress) return emptyList()
        previewInProgress = true
        _state.update { it.copy(progress = 0.01f, isSyncInProgress = true) }
        try {
            val localPayload = filterPayloadByProjects(syncService.buildLocalPayload(), selectedProjectIds)
            val shouldUseBleTransfer = bleManager.canInitiateCentralTransfer()
            val payloadFromBle = if (shouldUseBleTransfer) {
                syncService.exchangePayloadOverBle(bleManager, localPayload)
            } else {
                null
            }
            if (shouldUseBleTransfer && payloadFromBle == null) {
                pendingPayload = null
                pendingLocalPayload = null
                _state.update {
                    it.copy(
                        progress = 0f,
                        isSyncInProgress = false,
                        hasPendingPayload = false,
                        syncStatus = SyncStatus.TRANSFER_FAILED,
                        statusMessage = "sync_state_transfer_failed"
                    )
                }
                return emptyList()
            }

            val effectiveRemotePayload = filterPayloadByProjects(payloadFromBle ?: remotePayload, selectedProjectIds)
            val conflicts = syncService.previewConflicts(localPayload, effectiveRemotePayload)
            pendingPayload = effectiveRemotePayload
            pendingLocalPayload = localPayload
            _state.update {
                it.copy(
                    conflicts = conflicts,
                    decisions = emptyMap(),
                    hasPendingPayload = true,
                    isSyncInProgress = false,
                    progress = if (conflicts.isEmpty()) 1f else 0.75f,
                    syncStatus = if (conflicts.isEmpty()) SyncStatus.SYNC_COMPLETED else SyncStatus.CONFLICTS_DETECTED,
                    statusMessage = if (conflicts.isEmpty()) "sync_state_completed" else "sync_state_conflicts_detected"
                )
            }
            if (conflicts.isEmpty()) {
                syncService.apply(localPayload, effectiveRemotePayload, emptyMap())
                pendingPayload = null
                pendingLocalPayload = null
                _state.update {
                    it.copy(
                        syncStatus = SyncStatus.SYNC_COMPLETED,
                        hasPendingPayload = false,
                        isSyncInProgress = false,
                        progress = 1f,
                        statusMessage = "sync_state_completed"
                    )
                }
            } else {
                // Always apply non-conflicting updates so lists refresh after each sync.
                // For unresolved conflicts we keep local values by default.
                syncService.apply(localPayload, effectiveRemotePayload, emptyMap())
                autoSelectByVersionRule()
                _state.update {
                    it.copy(
                        hasPendingPayload = false,
                        syncStatus = SyncStatus.SYNC_COMPLETED_WITH_DECISIONS,
                        statusMessage = "sync_state_completed_with_decisions"
                    )
                }
            }
            return conflicts
        } finally {
            previewInProgress = false
            _state.update { current ->
                if (current.isSyncInProgress) current.copy(isSyncInProgress = false) else current
            }
        }
    }

    private fun filterPayloadByProjects(payload: SyncPayload, selectedProjectIds: Set<UUID>): SyncPayload {
        if (selectedProjectIds.isEmpty()) {
            return payload.copy(
                projects = emptyList(),
                participants = emptyList(),
                expenses = emptyList()
            )
        }

        val selectedProjects = payload.projects.filter { it.id in selectedProjectIds }
        val selectedProjectIdSet = selectedProjects.mapTo(mutableSetOf()) { it.id }
        val selectedExpenseIds = selectedProjects.flatMapTo(mutableSetOf()) { it.expenseIds }
        val selectedExpenses = payload.expenses.filter { expense ->
            expense.id in selectedExpenseIds || expense.projectId in selectedProjectIdSet
        }
        val selectedParticipantIds = buildSet {
            selectedProjects.forEach { addAll(it.participantIds) }
            selectedExpenses.forEach { add(it.participantId) }
        }
        val selectedParticipants = payload.participants.filter { it.id in selectedParticipantIds }

        return payload.copy(
            projects = selectedProjects,
            participants = selectedParticipants,
            expenses = selectedExpenses
        )
    }
}
