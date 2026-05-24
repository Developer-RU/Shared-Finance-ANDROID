package com.sharedfinance.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothAdapter.LeScanCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.annotation.RequiresApi

data class BleDevice(
    val name: String,
    val signalStrength: Int,
    val address: String
)

class BleManager(
    context: Context
) {
    private val logTag = "SharedFinanceBLE"
    private val scanDurationMs = 10_000L
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _isScanning = MutableStateFlow(false)
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    private val _debugStatus = MutableStateFlow("ble_idle")
    private val _transferProgress = MutableStateFlow(0f)

    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()
    val debugStatus: StateFlow<String> = _debugStatus.asStateFlow()
    val transferProgress: StateFlow<Float> = _transferProgress.asStateFlow()

    private val devicesByAddress = ConcurrentHashMap<String, BluetoothDevice>()
    private val scanResults = ConcurrentHashMap<String, BleDevice>()
    private var localAdapterAddressNormalized: String = ""
    private var localAdapterNameNormalized: String = ""

    private var centralGatt: BluetoothGatt? = null
    private var transferCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var connectionInProgress = false
    private var pendingConnectAddress: String? = null
    private var lastConnectRequestElapsedMs: Long = 0L
    private var negotiatedCentralMtu = DEFAULT_ATT_MTU
    @Volatile private var isNotifySubscriptionActive = false
    private var notifySubscriptionDeferred: CompletableDeferred<Boolean>? = null
    private val ioMutex = Mutex()

    private val pendingAckWaiters = ConcurrentHashMap<UInt, CompletableDeferred<Boolean>>()
    private var inboundExpectedChunks: UInt? = null
    private var inboundReceivedChunks: UInt = 0u
    private var inboundBuffer = ByteArray(0)
    private var inboundPayloadDeferred: CompletableDeferred<ByteArray>? = null

    private var gattServer: BluetoothGattServer? = null
    private var connectedCentral: BluetoothDevice? = null
    private var isCentralNotificationsEnabled = false
    private var serverTransferCharacteristic: BluetoothGattCharacteristic? = null
    private var serverNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var serverInboundExpectedChunks: UInt? = null
    private var serverInboundReceivedChunks: UInt = 0u
    private var serverInboundBuffer = ByteArray(0)
    private val notificationQueueLock = Any()
    private val pendingNotificationPackets = ArrayDeque<ByteArray>()
    private var isNotificationInFlight = false
    private var notificationSendFailureCount = 0
    private var peripheralServerStarted = false
    private var peripheralAdvertisingActive = false
    private var negotiatedPeripheralMtu = DEFAULT_ATT_MTU

    private var responsePayloadProvider: (ByteArray) -> ByteArray = { inbound -> inbound }
    private var lastReportedDeviceCount = 0
    private var legacyScanStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var unfilteredFallbackRunnable: Runnable? = null
    private var legacyFallbackRunnable: Runnable? = null
    private var autoStopScanRunnable: Runnable? = null

    private val serviceUuid = UUID.fromString("0000A1F0-0000-1000-8000-00805F9B34FB")
    private val transferCharacteristicUuid = UUID.fromString("0000A1F1-0000-1000-8000-00805F9B34FB")
    private val notifyCharacteristicUuid = UUID.fromString("0000A1F2-0000-1000-8000-00805F9B34FB")
    private val cccDescriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    fun setResponsePayloadProvider(provider: (ByteArray) -> ByteArray) {
        responsePayloadProvider = provider
        ensurePeripheralServerStarted()
    }

    fun canInitiateCentralTransfer(): Boolean {
        return centralGatt != null && transferCharacteristic != null
    }

    private fun updateDebugStatus(status: String) {
        _debugStatus.value = status
        Log.d(logTag, status)
    }

    private fun updateTransferProgress(progress: Float) {
        _transferProgress.value = progress.coerceIn(0f, 1f)
    }

    private fun consumeDiscoveredDevice(
        device: BluetoothDevice,
        rssi: Int,
        advertisedName: String?,
        serviceUuids: List<ParcelUuid>?
    ) {
        val remoteAddress = runCatching { device.address }
            .getOrNull()
            ?.trim()
            ?.uppercase()
        val localAddress = localAdapterAddressNormalized.takeIf { it.isNotBlank() }
        if (remoteAddress != null && localAddress != null && remoteAddress == localAddress) {
            return
        }

        val normalizedRemoteName = (advertisedName ?: runCatching { device.name }.getOrNull() ?: "")
            .trim()
            .lowercase()
        val normalizedLocalName = localAdapterNameNormalized
        if (normalizedRemoteName.isNotBlank() && normalizedLocalName.isNotBlank() && normalizedRemoteName == normalizedLocalName) {
            return
        }

        val deviceKey = runCatching { device.address }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "device-${device.hashCode()}"
        val deviceName = advertisedName
            ?: runCatching { device.name }.getOrNull()
            ?: "Unknown Device"
        val mapped = BleDevice(
            name = deviceName,
            signalStrength = rssi,
            address = deviceKey
        )

        devicesByAddress[mapped.address] = device
        scanResults[mapped.address] = mapped
        _discoveredDevices.value = scanResults.values
            .sortedByDescending { it.signalStrength }
        if (scanResults.size != lastReportedDeviceCount) {
            lastReportedDeviceCount = scanResults.size
            updateDebugStatus("scan_found_${scanResults.size}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        updateDebugStatus("scan_requested")
        val adapter = bluetoothAdapter ?: return
        localAdapterAddressNormalized = runCatching { adapter.address }
            .getOrNull()
            ?.trim()
            ?.uppercase()
            .orEmpty()
        localAdapterNameNormalized = runCatching { adapter.name }
            .getOrNull()
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (!adapter.isEnabled) {
            updateDebugStatus("scan_failed_adapter_disabled")
            return
        }
        if (!isLocationEnabledForBleScan()) {
            _isScanning.value = false
            updateDebugStatus("scan_failed_location_disabled")
            return
        }

        try {
            runCatching { ensurePeripheralServerStarted() }
                .onFailure {
                    // Scanning must still work even if peripheral role cannot be started.
                    updateDebugStatus("server_start_failed_scan_continues")
                }
            scanResults.clear()
            devicesByAddress.clear()
            lastReportedDeviceCount = 0
            _discoveredDevices.value = emptyList()
            cancelUnfilteredFallback()
            cancelLegacyFallback()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .setLegacy(true)
                .build()
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                _isScanning.value = false
                updateDebugStatus("scan_failed_no_scanner")
                return
            }
            // Always stop previous scans before a fresh start to avoid scanner state races.
            scanner.stopScan(scanCallback)
            if (legacyScanStarted) {
                runCatching { adapter.stopLeScan(legacyLeScanCallback) }
                legacyScanStarted = false
            }
            _isScanning.value = true
            // Some OEM BLE stacks and iOS advertising variants omit service UUID/name in scan records.
            // Start unfiltered immediately to maximize cross-platform discovery reliability.
            scanner.startScan(null, settings, scanCallback)
            updateDebugStatus("scan_started_unfiltered")
            scheduleLegacyFallbackIfNeeded(adapter)
            scheduleAutoStopScan()
        } catch (_: SecurityException) {
            _isScanning.value = false
            updateDebugStatus("scan_failed_security_exception")
        } catch (_: IllegalStateException) {
            _isScanning.value = false
            updateDebugStatus("scan_failed_illegal_state")
        } catch (_: IllegalArgumentException) {
            _isScanning.value = false
            updateDebugStatus("scan_failed_illegal_argument")
        } catch (_: Throwable) {
            _isScanning.value = false
            updateDebugStatus("scan_failed_unexpected")
        }
    }

    private fun isLocationEnabledForBleScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Do not gate BLE scan on location toggle for Android 12+.
            return true
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { locationManager.isLocationEnabled }.getOrDefault(false)
        } else {
            runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        cancelAutoStopScan()
        cancelUnfilteredFallback()
        cancelLegacyFallback()
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Ignore permission errors; UI state still transitions to stopped.
        } catch (_: IllegalStateException) {
            // Some vendor BLE stacks throw IllegalStateException when adapter is transitioning state.
            updateDebugStatus("scan_stop_failed_illegal_state")
        } catch (_: Throwable) {
            // Guard against vendor-specific runtime failures in scanner shutdown.
            updateDebugStatus("scan_stop_failed_unexpected")
        }
        if (legacyScanStarted) {
            runCatching { bluetoothAdapter?.stopLeScan(legacyLeScanCallback) }
            legacyScanStarted = false
        }
        _isScanning.value = false
        updateDebugStatus("scan_stopped")
    }

    private fun scheduleAutoStopScan() {
        cancelAutoStopScan()
        val runnable = Runnable {
            if (_isScanning.value) {
                stopScan()
            }
        }
        autoStopScanRunnable = runnable
        mainHandler.postDelayed(runnable, scanDurationMs)
    }

    private fun cancelAutoStopScan() {
        autoStopScanRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopScanRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun stopPeripheralAdvertisingForScan() {
        if (!peripheralAdvertisingActive) return
        runCatching {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            peripheralAdvertisingActive = false
            updateDebugStatus("server_advertising_paused_for_scan")
        }.onFailure {
            updateDebugStatus("server_advertising_pause_failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun resumePeripheralAdvertisingAfterScan() {
        if (_isScanning.value) return
        if (peripheralAdvertisingActive) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        if (!peripheralServerStarted) return

        runCatching {
            startPeripheralAdvertising(adapter)
            updateDebugStatus("server_advertising_resumed")
        }.onFailure {
            updateDebugStatus("server_advertising_resume_failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPeripheralAdvertising(adapter: BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(android.os.ParcelUuid(serviceUuid))
                .build(),
            advertiseCallback
        )
        peripheralAdvertisingActive = true
    }

    private fun cancelUnfilteredFallback() {
        unfilteredFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        unfilteredFallbackRunnable = null
    }

    private fun cancelLegacyFallback() {
        legacyFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        legacyFallbackRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun scheduleUnfilteredFallbackIfNeeded(
        adapter: BluetoothAdapter,
        settings: ScanSettings
    ) {
        val runnable = Runnable {
            if (!_isScanning.value) return@Runnable
            if (scanResults.isNotEmpty()) return@Runnable

            val scanner = adapter.bluetoothLeScanner ?: return@Runnable
            runCatching { scanner.stopScan(scanCallback) }
            runCatching { scanner.startScan(null, settings, scanCallback) }
            updateDebugStatus("scan_started_unfiltered")
        }
        unfilteredFallbackRunnable = runnable
        mainHandler.postDelayed(runnable, 1_200)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleLegacyFallbackIfNeeded(adapter: BluetoothAdapter) {
        val runnable = Runnable {
            if (!_isScanning.value) return@Runnable
            if (scanResults.isNotEmpty()) return@Runnable
            if (legacyScanStarted) return@Runnable

            legacyScanStarted = runCatching {
                adapter.startLeScan(legacyLeScanCallback)
            }.getOrElse { false }
            if (legacyScanStarted) {
                updateDebugStatus("scan_started_with_legacy_fallback")
            } else {
                updateDebugStatus("scan_legacy_fallback_failed")
            }
        }
        legacyFallbackRunnable = runnable
        mainHandler.postDelayed(runnable, 2_000)
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        val btDevice = devicesByAddress[deviceAddress]
            ?: scanResults[deviceAddress]?.let { devicesByAddress[it.address] }
            ?: run {
                updateDebugStatus("connect_failed_device_not_found")
                return
            }

        val targetAddress = runCatching { btDevice.address }
            .getOrNull()
            ?.trim()
            ?.uppercase()
            .orEmpty()
        val localAddress = localAdapterAddressNormalized.takeIf { it.isNotBlank() }
        if (localAddress != null && targetAddress.isNotBlank() && targetAddress == localAddress) {
            updateDebugStatus("connect_ignored_self_address")
            return
        }
        val targetNameNormalized = runCatching { btDevice.name }
            .getOrNull()
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (targetAddress.isBlank() && targetNameNormalized.isNotBlank() && localAdapterNameNormalized.isNotBlank() && targetNameNormalized == localAdapterNameNormalized) {
            updateDebugStatus("connect_ignored_self_name")
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        if (connectionInProgress && pendingConnectAddress == targetAddress) {
            updateDebugStatus("connect_ignored_in_progress")
            return
        }
        if (pendingConnectAddress == targetAddress && nowElapsed - lastConnectRequestElapsedMs < CONNECT_DEBOUNCE_MS) {
            updateDebugStatus("connect_ignored_debounced")
            return
        }

        val currentlyConnectedAddress = runCatching { centralGatt?.device?.address }
            .getOrNull()
            ?.trim()
            ?.uppercase()
        if (_connectedDeviceName.value != null && currentlyConnectedAddress != null && currentlyConnectedAddress == targetAddress) {
            updateDebugStatus("connect_already_connected")
            return
        }

        connectionInProgress = true
        pendingConnectAddress = targetAddress
        lastConnectRequestElapsedMs = nowElapsed

        stopScan()
        try {
            centralGatt?.close()
            centralGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                btDevice.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                btDevice.connectGatt(appContext, false, gattCallback)
            }
            updateDebugStatus("connect_requested")
        } catch (_: SecurityException) {
            connectionInProgress = false
            pendingConnectAddress = null
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
            updateDebugStatus("connect_failed_security_exception")
        }
    }

    fun disconnect() {
        transferCharacteristic = null
        notifyCharacteristic = null
        connectionInProgress = false
        pendingConnectAddress = null
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        updateTransferProgress(0f)
        centralGatt?.close()
        centralGatt = null
    }

    suspend fun transfer(payload: ByteArray): ByteArray? = ioMutex.withLock {
        val gatt = centralGatt ?: return null
        val tx = transferCharacteristic ?: return null
        updateTransferProgress(0.01f)
        updateDebugStatus("transfer_started_bytes_${payload.size}")

        // Await CCC descriptor write before sending — iOS only notifies subscribed centrals.
        if (!isNotifySubscriptionActive) {
            val subDeferred = notifySubscriptionDeferred
            if (subDeferred != null) {
                val ready = runCatching {
                    kotlinx.coroutines.withTimeout(3_000) { subDeferred.await() }
                }.getOrElse { false }
                if (!ready) {
                    updateDebugStatus("transfer_failed_notify_not_ready")
                    updateTransferProgress(0f)
                    return null
                }
            } else {
                updateDebugStatus("transfer_failed_no_subscription_deferred")
                updateTransferProgress(0f)
                return null
            }
        }
        updateTransferProgress(0.05f)

        inboundPayloadDeferred = CompletableDeferred()
        resetInboundStateForCentral()

        val chunks = chunk(payload, mtuPayloadChunkSize(negotiatedCentralMtu))
        updateDebugStatus("transfer_chunks_${chunks.size}")
        val totalOutboundPackets = chunks.size + 2
        var ackedOutboundPackets = 0

        fun markOutboundAckProgress() {
            ackedOutboundPackets += 1
            val fraction = ackedOutboundPackets.toFloat() / totalOutboundPackets.toFloat()
            updateTransferProgress(0.05f + (0.80f * fraction))
        }

        if (!writeAndAwaitAck(gatt, tx, PacketType.Start, index = chunks.size.toUInt(), payload = ByteArray(0))) {
            updateDebugStatus("transfer_ack_failed_start")
            updateTransferProgress(0f)
            return null
        }
        markOutboundAckProgress()
        for ((index, chunk) in chunks.withIndex()) {
            if (!writeAndAwaitAck(gatt, tx, PacketType.Chunk, index = index.toUInt(), payload = chunk)) {
                updateDebugStatus("transfer_ack_failed_chunk_$index")
                updateTransferProgress(0f)
                return null
            }
            markOutboundAckProgress()
        }
        if (!writeAndAwaitAck(gatt, tx, PacketType.End, index = 0u, payload = ByteArray(0))) {
            updateDebugStatus("transfer_ack_failed_end")
            updateTransferProgress(0f)
            return null
        }
        markOutboundAckProgress()

        val deferred = inboundPayloadDeferred ?: return null
        updateTransferProgress(0.90f)
        updateDebugStatus("transfer_waiting_inbound")
        runCatching {
            kotlinx.coroutines.withTimeout(15_000) { deferred.await() }
        }.onSuccess { inbound ->
            updateTransferProgress(1f)
            updateDebugStatus("transfer_completed_inbound_bytes_${inbound.size}")
        }.getOrElse {
            updateDebugStatus("transfer_failed_inbound_timeout")
            updateTransferProgress(0f)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensurePeripheralServerStarted() {
        if (peripheralServerStarted) return
        startPeripheralServer()
    }

    @SuppressLint("MissingPermission")
    private fun startPeripheralServer() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            updateDebugStatus("server_skipped_adapter_disabled")
            return
        }

        try {
            gattServer = bluetoothManager.openGattServer(appContext, gattServerCallback)
            if (gattServer == null) {
                updateDebugStatus("server_failed_open_gatt_server_null")
                return
            }
            val tx = BluetoothGattCharacteristic(
                transferCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val notify = BluetoothGattCharacteristic(
                notifyCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            notify.addDescriptor(
                BluetoothGattDescriptor(
                    cccDescriptorUuid,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )

            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(tx)
            service.addCharacteristic(notify)
            gattServer?.addService(service)

            serverTransferCharacteristic = tx
            serverNotifyCharacteristic = notify

            startPeripheralAdvertising(adapter)
            peripheralServerStarted = true
            updateDebugStatus("server_started")
        } catch (_: IllegalArgumentException) {
            // Some vendor ROMs throw IllegalArgumentException when BLE stack is not fully ready.
            updateDebugStatus("server_failed_illegal_argument")
        } catch (_: SecurityException) {
            // BLE permissions are managed by the host app runtime flow.
            updateDebugStatus("server_failed_security_exception")
        }
    }

    private suspend fun writeAndAwaitAck(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        type: PacketType,
        index: UInt,
        payload: ByteArray
    ): Boolean {
        val encoded = encodePacket(type, index, payload)
        val maxAttempts = BLE_ACK_MAX_ATTEMPTS
        for (attempt in 1..maxAttempts) {
            val waiter = CompletableDeferred<Boolean>()
            pendingAckWaiters[index] = waiter

            @Suppress("DEPRECATION")
            characteristic.value = encoded
            val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ returns status Int where 0 means SUCCESS.
                gatt.writeCharacteristic(characteristic, encoded, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (!writeInitiated) {
                pendingAckWaiters.remove(index)
                if (attempt < maxAttempts) {
                    updateDebugStatus("transfer_ack_retry_write_${type.name.lowercase()}_${index}_$attempt")
                    kotlinx.coroutines.delay(75L * attempt)
                    continue
                }
                return false
            }

            val acknowledged = runCatching {
                kotlinx.coroutines.withTimeout(BLE_ACK_TIMEOUT_MS) {
                    waiter.await()
                }
            }.getOrElse {
                false
            }
            pendingAckWaiters.remove(index)

            if (acknowledged) {
                return true
            }

            if (attempt < maxAttempts) {
                updateDebugStatus("transfer_ack_retry_timeout_${type.name.lowercase()}_${index}_$attempt")
                kotlinx.coroutines.delay(75L * attempt)
            }
        }

        return false
    }

    private fun resetInboundStateForCentral() {
        inboundExpectedChunks = null
        inboundReceivedChunks = 0u
        inboundBuffer = ByteArray(0)
    }

    @SuppressLint("MissingPermission")
    private fun notifyCentral(packet: ByteArray) {
        val central = connectedCentral ?: return
        if (!isCentralNotificationsEnabled) return
        val notify = serverNotifyCharacteristic ?: return

        var shouldStart = false
        synchronized(notificationQueueLock) {
            pendingNotificationPackets.addLast(packet)
            if (!isNotificationInFlight) {
                isNotificationInFlight = true
                shouldStart = true
            }
        }

        if (shouldStart) {
            sendNextQueuedNotification(central = central, notify = notify)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextQueuedNotification(central: BluetoothDevice, notify: BluetoothGattCharacteristic) {
        val packet = synchronized(notificationQueueLock) {
            pendingNotificationPackets.firstOrNull()
        } ?: run {
            synchronized(notificationQueueLock) {
                isNotificationInFlight = false
                notificationSendFailureCount = 0
            }
            return
        }

        notify.value = packet
        val notified = gattServer?.notifyCharacteristicChanged(central, notify, false) == true
        if (!notified) {
            val shouldRetry = synchronized(notificationQueueLock) {
                notificationSendFailureCount += 1
                if (notificationSendFailureCount > 5 && pendingNotificationPackets.isNotEmpty()) {
                    pendingNotificationPackets.removeFirst()
                    notificationSendFailureCount = 0
                    updateDebugStatus("server_notify_dropped_after_retries")
                }
                val hasNext = pendingNotificationPackets.isNotEmpty()
                isNotificationInFlight = hasNext
                hasNext
            }
            updateDebugStatus("server_notify_send_failed")
            if (shouldRetry) {
                sendNextQueuedNotification(central = central, notify = notify)
            }
        }
    }

    private fun clearNotificationQueue() {
        synchronized(notificationQueueLock) {
            pendingNotificationPackets.clear()
            isNotificationInFlight = false
            notificationSendFailureCount = 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendServerAck(index: UInt) {
        notifyCentral(encodePacket(PacketType.Ack, index, ByteArray(0)))
    }

    @SuppressLint("MissingPermission")
    private fun sendServerResponse(payload: ByteArray) {
        val chunks = chunk(payload, mtuPayloadChunkSize(negotiatedPeripheralMtu))
        notifyCentral(encodePacket(PacketType.Start, chunks.size.toUInt(), ByteArray(0)))
        for ((index, chunkData) in chunks.withIndex()) {
            notifyCentral(encodePacket(PacketType.Chunk, index.toUInt(), chunkData))
        }
        notifyCentral(encodePacket(PacketType.End, 0u, ByteArray(0)))
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val scanCallback = object : ScanCallback() {
        private fun onAnyResult(result: ScanResult) {
            try {
                val device = result.device ?: return
                consumeDiscoveredDevice(
                    device = device,
                    rssi = result.rssi,
                    advertisedName = result.scanRecord?.deviceName,
                    serviceUuids = result.scanRecord?.serviceUuids
                )
            } catch (_: SecurityException) {
                // Runtime BLE permissions may still be pending; ignore this callback safely.
                updateDebugStatus("scan_result_security_exception")
            } catch (_: Throwable) {
                // Guard against vendor BLE stack runtime crashes in callback thread.
                updateDebugStatus("scan_result_unexpected_exception")
            }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onAnyResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (results.isEmpty()) {
                updateDebugStatus("scan_batch_empty")
                return
            }
            results.forEach { result ->
                onAnyResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "already_started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration_failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature_unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "internal_error"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out_of_hw_resources"
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "too_frequently"
                else -> "unknown_$errorCode"
            }
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                _isScanning.value = true
                updateDebugStatus("scan_already_started_keep_running")
            } else {
                _isScanning.value = false
                updateDebugStatus("scan_failed_$reason")
            }
        }
    }

    private val legacyLeScanCallback = LeScanCallback { device, rssi, _ ->
        if (!_isScanning.value) return@LeScanCallback
        try {
            consumeDiscoveredDevice(device = device, rssi = rssi, advertisedName = null, serviceUuids = null)
        } catch (_: SecurityException) {
            updateDebugStatus("legacy_scan_result_security_exception")
        } catch (_: Throwable) {
            updateDebugStatus("legacy_scan_result_unexpected_exception")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectionInProgress = false
                pendingConnectAddress = runCatching { gatt.device.address }
                    .getOrNull()
                    ?.trim()
                    ?.uppercase()
                _connectedDeviceName.value = scanResults[gatt.device.address]?.name
                    ?: gatt.device.name
                    ?: "SharedFinance Peer"
                _connectedDeviceAddress.value = runCatching { gatt.device.address }
                    .getOrNull()
                    ?.trim()
                    ?.uppercase()
                updateDebugStatus("connect_success")
                runCatching { gatt.requestMtu(PREFERRED_ATT_MTU) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionInProgress = false
                pendingConnectAddress = null
                negotiatedCentralMtu = DEFAULT_ATT_MTU
                transferCharacteristic = null
                notifyCharacteristic = null
                isNotifySubscriptionActive = false
                notifySubscriptionDeferred?.cancel()
                notifySubscriptionDeferred = null
                _connectedDeviceName.value = null
                _connectedDeviceAddress.value = null
                updateDebugStatus("connect_disconnected_status_$status")
            } else {
                updateDebugStatus("connect_state_status_${status}_state_$newState")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUuid) ?: return
            transferCharacteristic = service.getCharacteristic(transferCharacteristicUuid)
            notifyCharacteristic = service.getCharacteristic(notifyCharacteristicUuid)
            notifyCharacteristic?.let { characteristic ->
                gatt.setCharacteristicNotification(characteristic, true)
                characteristic.getDescriptor(cccDescriptorUuid)?.let { descriptor ->
                    isNotifySubscriptionActive = false
                    notifySubscriptionDeferred = CompletableDeferred()
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) {
                negotiatedCentralMtu = mtu
                updateDebugStatus("connect_mtu_$mtu")
            } else {
                updateDebugStatus("connect_mtu_failed_status_$status")
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != notifyCharacteristicUuid) return
            val packet = decodePacket(value) ?: return
            handleIncomingPacket(gatt, packet)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid != notifyCharacteristicUuid) return
            val packet = decodePacket(characteristic.value) ?: return

            handleIncomingPacket(gatt, packet)
        }

        private fun handleIncomingPacket(gatt: BluetoothGatt, packet: Packet) {
            when (packet.type) {
                PacketType.Ack -> {
                    pendingAckWaiters.remove(packet.index)?.complete(true)
                }
                PacketType.Start -> {
                    inboundExpectedChunks = packet.index
                    inboundReceivedChunks = 0u
                    inboundBuffer = ByteArray(0)
                    runCatching {
                        writeAndFireAckForInbound(gatt, packet.index)
                    }
                }
                PacketType.Chunk -> {
                    inboundBuffer += packet.payload
                    inboundReceivedChunks += 1u
                    runCatching {
                        writeAndFireAckForInbound(gatt, packet.index)
                    }
                }
                PacketType.End -> {
                    val expected = inboundExpectedChunks
                    if (expected == null || expected == inboundReceivedChunks) {
                        inboundPayloadDeferred?.complete(inboundBuffer)
                    }
                    runCatching {
                        writeAndFireAckForInbound(gatt, packet.index)
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == cccDescriptorUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isNotifySubscriptionActive = true
                    notifySubscriptionDeferred?.complete(true)
                    updateDebugStatus("notify_subscription_active")
                } else {
                    notifySubscriptionDeferred?.complete(false)
                    updateDebugStatus("notify_subscription_failed_status_$status")
                }
            }
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        private fun writeAndFireAckForInbound(gatt: BluetoothGatt, index: UInt) {
            val tx = transferCharacteristic ?: return
            val packet = encodePacket(PacketType.Ack, index, ByteArray(0))
            tx.value = packet
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(tx, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(tx)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedCentral = device
                isCentralNotificationsEnabled = false
                clearNotificationQueue()
                _connectedDeviceName.value = device.name ?: "Unknown Device"
                _connectedDeviceAddress.value = runCatching { device.address }
                    .getOrNull()
                    ?.trim()
                    ?.uppercase()
                negotiatedPeripheralMtu = DEFAULT_ATT_MTU
                updateDebugStatus("server_connected")
                stopScan()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedCentral = null
                isCentralNotificationsEnabled = false
                clearNotificationQueue()
                serverInboundExpectedChunks = null
                serverInboundReceivedChunks = 0u
                serverInboundBuffer = ByteArray(0)
                negotiatedPeripheralMtu = DEFAULT_ATT_MTU
                _connectedDeviceName.value = null
                _connectedDeviceAddress.value = null
                updateDebugStatus("server_disconnected_status_$status")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (mtu > 0) {
                negotiatedPeripheralMtu = mtu
                updateDebugStatus("server_mtu_$mtu")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val hasNext = synchronized(notificationQueueLock) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (pendingNotificationPackets.isNotEmpty()) {
                        pendingNotificationPackets.removeFirst()
                    }
                    notificationSendFailureCount = 0
                } else {
                    notificationSendFailureCount += 1
                    updateDebugStatus("server_notify_sent_status_$status")
                    if (notificationSendFailureCount > 5 && pendingNotificationPackets.isNotEmpty()) {
                        pendingNotificationPackets.removeFirst()
                        notificationSendFailureCount = 0
                        updateDebugStatus("server_notify_dropped_after_retries")
                    }
                }
                val notEmpty = pendingNotificationPackets.isNotEmpty()
                isNotificationInFlight = notEmpty
                notEmpty
            }

            if (hasNext) {
                val central = connectedCentral ?: return
                val notify = serverNotifyCharacteristic ?: return
                sendNextQueuedNotification(central = central, notify = notify)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == cccDescriptorUuid) {
                val enableNotify = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val enableIndicate = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)

                isCentralNotificationsEnabled = enableNotify || enableIndicate
                if (disable) {
                    isCentralNotificationsEnabled = false
                    clearNotificationQueue()
                }
                connectedCentral = if (isCentralNotificationsEnabled) device else connectedCentral
                descriptor.value = value
                updateDebugStatus(
                    if (isCentralNotificationsEnabled) "server_notify_enabled" else "server_notify_disabled"
                )
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                return
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == cccDescriptorUuid) {
                val value = if (isCentralNotificationsEnabled) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                return
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != transferCharacteristicUuid) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            val packet = decodePacket(value)
            if (packet == null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            when (packet.type) {
                PacketType.Start -> {
                    serverInboundExpectedChunks = packet.index
                    serverInboundReceivedChunks = 0u
                    serverInboundBuffer = ByteArray(0)
                    sendServerAck(packet.index)
                }
                PacketType.Chunk -> {
                    serverInboundBuffer += packet.payload
                    serverInboundReceivedChunks += 1u
                    sendServerAck(packet.index)
                }
                PacketType.End -> {
                    val expected = serverInboundExpectedChunks
                    sendServerAck(packet.index)
                    if (expected == null || expected == serverInboundReceivedChunks) {
                        val response = responsePayloadProvider(serverInboundBuffer)
                        sendServerResponse(response)
                    }
                }
                PacketType.Ack -> {
                    // Peripheral side currently sends without retry strategy; ACK is optional.
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private enum class PacketType(val code: Byte) {
        Start(1),
        Chunk(2),
        End(3),
        Ack(4);

        companion object {
            fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
        }
    }

    private data class Packet(
        val type: PacketType,
        val index: UInt,
        val payload: ByteArray
    )

    private fun encodePacket(type: PacketType, index: UInt, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.code)
        buffer.putInt(index.toInt())
        buffer.put(payload)
        return buffer.array()
    }

    private fun decodePacket(bytes: ByteArray): Packet? {
        if (bytes.size < 5) return null
        val type = PacketType.fromCode(bytes[0]) ?: return null
        val index = ByteBuffer.wrap(bytes, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toUInt()
        val payload = if (bytes.size > 5) bytes.copyOfRange(5, bytes.size) else ByteArray(0)
        return Packet(type = type, index = index, payload = payload)
    }

    private fun chunk(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        if (chunkSize <= 0) return listOf(bytes)
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            val end = minOf(start + chunkSize, bytes.size)
            chunks += bytes.copyOfRange(start, end)
            start = end
        }
        return chunks
    }

    private fun mtuPayloadChunkSize(mtu: Int): Int {
        val safeMtu = if (mtu > 0) mtu else DEFAULT_ATT_MTU
        val maxCharacteristicBytes = safeMtu - ATT_HEADER_BYTES
        val maxPayload = maxCharacteristicBytes - PROTOCOL_HEADER_BYTES
        return maxPayload.coerceIn(MIN_PAYLOAD_CHUNK_BYTES, MAX_PAYLOAD_CHUNK_BYTES)
    }

    companion object {
        private const val DEFAULT_ATT_MTU = 23
        private const val PREFERRED_ATT_MTU = 247
        private const val ATT_HEADER_BYTES = 3
        private const val PROTOCOL_HEADER_BYTES = 5
        private const val MIN_PAYLOAD_CHUNK_BYTES = 15
        private const val MAX_PAYLOAD_CHUNK_BYTES = 180
        private const val CONNECT_DEBOUNCE_MS = 1_200L
        private const val BLE_ACK_TIMEOUT_MS = 4_000L
        private const val BLE_ACK_MAX_ATTEMPTS = 5
    }
}
