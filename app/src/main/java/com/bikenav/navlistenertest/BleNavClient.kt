package com.bikenav.navlistenertest

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/** A device found during a "scan and pick" sweep, shown to the user in a list. */
data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

object BleNavClient {

    private const val TAG = "BleNavClient"
    private const val SCAN_TIMEOUT_MS = 10_000L
    private const val WRITE_DEBOUNCE_MS = 400L
    private const val RECONNECT_INITIAL_DELAY_MS = 3_000L
    private const val RECONNECT_MAX_DELAY_MS = 30_000L

    // Negotiated MTU is 247 (see requestMtu below), leaving 244 usable ATT
    // bytes (247 - 3 byte header). Cap well under that so we never hit a
    // truncated write on a phone that negotiates slightly less than asked.
    private const val MAX_PACKET_BYTES = 230

    // Resends whatever nav payload we last had, on a fixed interval, for as
    // long as we're connected — well under the firmware's PACKET_TIMEOUT_MS
    // (20s). This used to live only inside NavAccessibilityService's own
    // 5s timer, which meant: (a) it stopped the moment that service's event
    // stream dried up (screen off, Maps minimized/PiP), and (b) it did
    // nothing at all for instructions coming from NavNotificationListener,
    // which has no heartbeat of its own. Centralizing it here means the
    // display keeps getting refreshed regardless of which source produced
    // the data or whether the screen/app is in the foreground.
    private const val HEARTBEAT_INTERVAL_MS = 4_000L
    private var heartbeatRunning = false

    private var bluetoothGatt: BluetoothGatt? = null
    private var navCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingWrite: String? = null
    private var pendingBytes: ByteArray? = null
    private var writeScheduled = false
    private var bytesWriteScheduled = false

    /**
     * The most recent nav payload successfully queued for send, kept around so
     * we can resend it as soon as the BLE link comes back up. Without this,
     * a brief disconnect (tunnel, phone in pocket, etc.) silently drops
     * whatever turn/ETA was current, and the BikeNav display goes stale until
     * the next notification update happens to arrive - which may be a while
     * if the rider is mid-way through a long straight road.
     */
    private var lastKnownNavText: String? = null
    private var lastKnownNavBytes: ByteArray? = null

    /** Set from MainActivity based on the persisted setting. Defaults to on. */
    var autoReconnectEnabled: Boolean = true

    private var appContext: Context? = null
    private var manualDisconnect = false
    private var reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
    private var reconnectScheduled = false

    /** MAC address of the device the user picked via the scan dialog. */
    private var pairedAddress: String? = null

    private var deviceScanCallback: ScanCallback? = null
    private val seenScanAddresses = mutableSetOf<String>()

    // ---------------------------------------------------------------------
    // Bonding — required for the HID (media/call control) side to work.
    // The nav GATT characteristic writes fine without bonding, but Android's
    // HID subsystem only subscribes to the ESP32's Consumer Control input
    // report characteristic once a proper bond exists. If we go straight to
    // requestMtu()/discoverServices() without bonding first, the nav service
    // works but media/call buttons silently do nothing.
    // ---------------------------------------------------------------------
    private var bondReceiverRegistered = false
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device == null || device.address != bluetoothGatt?.device?.address) return

            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    Log.d(TAG, "Bonded with ${device.address} — continuing connection setup")
                    bluetoothGatt?.let { proceedAfterBonded(it) }
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.w(TAG, "Bonding with ${device.address} failed or was removed")
                }
            }
        }
    }

    private fun ensureBondReceiverRegistered(context: Context) {
        if (bondReceiverRegistered) return
        context.applicationContext.registerReceiver(
            bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
        bondReceiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun proceedAfterBonded(gatt: BluetoothGatt) {
        // Default ATT MTU is 23 bytes (20 usable) — way too small for our
        // packet. 185 was enough for the old text-only format (10-byte
        // header + up to 60 bytes text = 70 bytes), but roundabout packets
        // now also carry a 128-byte bitmap (10 + 128 + 60 = 198 bytes), so
        // request the common practical ceiling instead. Request before
        // discovering services; writes before this completes are truncated.
        gatt.requestMtu(247)
    }

    private val stateListeners = mutableListOf<(BleState) -> Unit>()
    var state: BleState = BleState.DISCONNECTED
        private set(value) {
            field = value
            handler.post { stateListeners.forEach { it(value) } }
        }

    fun addStateListener(l: (BleState) -> Unit) = stateListeners.add(l)
    fun removeStateListener(l: (BleState) -> Unit) = stateListeners.remove(l)

    /** Call on app start so reconnects know which device to target. */
    fun setPairedAddress(address: String?) {
        pairedAddress = address
    }

    // ---------------------------------------------------------------------
    // Device discovery — "scan and pick" flow shown in the connect dialog
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startDeviceScan(
        context: Context,
        onDeviceFound: (ScannedDevice) -> Unit,
        onScanFinished: () -> Unit
    ) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onScanFinished()
            return
        }
        seenScanAddresses.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address ?: return
                if (!seenScanAddresses.add(address)) return // already reported this one
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return // skip unnamed/unknown devices
                onDeviceFound(ScannedDevice(result.device, name, address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Device scan failed: $errorCode")
                onScanFinished()
            }
        }
        deviceScanCallback = callback
        scanner.startScan(listOf(), settings, callback)

        handler.postDelayed({
            stopDeviceScan(context)
            onScanFinished()
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopDeviceScan(context: Context) {
        val callback = deviceScanCallback ?: return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter
        adapter?.bluetoothLeScanner?.stopScan(callback)
        deviceScanCallback = null
    }

    // ---------------------------------------------------------------------
    // Connecting
    // ---------------------------------------------------------------------

    /** Connects to a device the user explicitly picked from the scan list. */
    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        if (state == BleState.CONNECTING || state == BleState.CONNECTED) return
        appContext = context.applicationContext
        manualDisconnect = false
        reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
        pairedAddress = device.address
        connectToDevice(context, device)
    }

    /**
     * Attempts to reconnect directly to the last-paired device without
     * re-scanning. Returns false (and does nothing) if there's no saved
     * device, Bluetooth is off, or a connection is already in progress —
     * callers should fall back to the scan-and-pick dialog in that case.
     */
    @SuppressLint("MissingPermission")
    fun connectToSaved(context: Context): Boolean {
        val address = pairedAddress ?: return false
        if (state != BleState.DISCONNECTED) return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter ?: return false
        if (!adapter.isEnabled) return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return false
        }
        appContext = context.applicationContext
        manualDisconnect = false
        connectToDevice(context, device)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(context: Context, device: BluetoothDevice) {
        state = BleState.CONNECTING
        ensureBondReceiverRegistered(context)

        // Close out any stale GATT client before opening a new one. Leaving a
        // previous BluetoothGatt object open (even a disconnected one) is a
        // common cause of status=133 (GATT_ERROR) on the *next* connection
        // attempt, which mainly shows up when reconnecting to a device that
        // was already paired/connected before — a first-time connection to
        // an unknown device doesn't hit this because there's no stale client.
        bluetoothGatt?.close()
        bluetoothGatt = null

        // Explicitly request the LE transport. Without this, Android can try
        // to negotiate a dual (BR/EDR + LE) transport for a device that
        // already has a system-level bond, which is the other common cause
        // of a previously-paired ESP32 silently failing to connect while an
        // unpaired one connects fine.
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Covers GATT_ERROR (133) and friends — most commonly seen when
                // reconnecting to a device that was already bonded/connected
                // before. Close the client fully rather than leaving it in a
                // half-open state (which would silently block all future
                // connect attempts) and fall through to the normal reconnect
                // backoff.
                Log.w(TAG, "GATT error status=$status newState=$newState — closing and retrying")
                gatt.close()
                bluetoothGatt = null
                navCharacteristic = null
                state = BleState.DISCONNECTED
                maybeScheduleReconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                when (gatt.device.bondState) {
                    BluetoothDevice.BOND_NONE -> {
                        // Not bonded yet — request it now, before touching
                        // services. This is the step that makes media/call
                        // control work when connecting straight from the
                        // app: it gives Android's HID subsystem a clean
                        // chance to subscribe to the input report
                        // characteristic. proceedAfterBonded() picks up
                        // once BOND_BONDED arrives via bondReceiver above.
                        Log.d(TAG, "Not bonded — requesting bond")
                        gatt.device.createBond()
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        Log.d(TAG, "Already bonded — continuing")
                        proceedAfterBonded(gatt)
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        Log.d(TAG, "Bonding already in progress, waiting")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state = BleState.DISCONNECTED
                navCharacteristic = null
                heartbeatRunning = false
                // Only close() here — never earlier. Calling close() before
                // this callback fires (e.g. right after disconnect()) is what
                // leaves a "ghost" connection behind at the Android system
                // level: the app moves on and shows Disconnected, but the
                // OS Bluetooth stack (and the quick-settings panel) hasn't
                // actually finished tearing down the link yet.
                gatt.close()
                bluetoothGatt = null
                heartbeatRunning = false
                maybeScheduleReconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU negotiated: $mtu (status=$status)")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(BleUuids.SERVICE_UUID)
            navCharacteristic = service?.getCharacteristic(BleUuids.NAV_CHAR_UUID)
            state = if (navCharacteristic != null) BleState.CONNECTED else BleState.DISCONNECTED

            // Reliability fix: as soon as the link is actually ready to accept
            // writes again, push whatever nav state we last had, rather than
            // waiting for the next Maps notification (which might not arrive
            // for a while on a long straight road).
            if (state == BleState.CONNECTED) {
                lastKnownNavText?.let { sendNavText(it) }
                lastKnownNavBytes?.let { sendNavBytes(it) }
                startHeartbeat()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: $status")
            }
        }
    }

    /**
     * Schedules a reconnect attempt with exponential backoff (capped at
     * RECONNECT_MAX_DELAY_MS), unless the disconnect was requested by the
     * user, auto-reconnect is off in settings, or there's no paired device
     * to reconnect to.
     */
    private fun maybeScheduleReconnect() {
        if (manualDisconnect || !autoReconnectEnabled) return
        val context = appContext ?: return
        if (pairedAddress == null) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        Log.d(TAG, "Scheduling reconnect in ${reconnectDelayMs}ms")
        handler.postDelayed({
            reconnectScheduled = false
            if (state == BleState.DISCONNECTED && !manualDisconnect && autoReconnectEnabled) {
                connectToSaved(context)
            }
        }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }

    /**
     * Starts (if not already running) a loop that resends lastKnownNavBytes
     * every HEARTBEAT_INTERVAL_MS while connected. Self-terminates once
     * heartbeatRunning is flipped false by a disconnect, so there's no need
     * to explicitly cancel it elsewhere.
     */
    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (!heartbeatRunning || state != BleState.CONNECTED) {
                    heartbeatRunning = false
                    return
                }
                lastKnownNavBytes?.let { sendNavBytes(it) }
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(tick, HEARTBEAT_INTERVAL_MS)
    }

    // Text-based send (used by notification listener fallback)
    @SuppressLint("MissingPermission")
    fun sendNavText(text: String) {
        lastKnownNavText = text
        pendingWrite = text
        if (writeScheduled) return
        writeScheduled = true
        handler.postDelayed({
            writeScheduled = false
            val payload = pendingWrite ?: return@postDelayed
            val gatt = bluetoothGatt ?: return@postDelayed
            val char = navCharacteristic ?: return@postDelayed
            val bytes = payload.toByteArray(Charsets.UTF_8).take(MAX_PACKET_BYTES).toByteArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char, bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }, WRITE_DEBOUNCE_MS)
    }

    // Binary packet send (used by AccessibilityService and NavNotificationListener — matches navigation.h / main.cpp format)
    @SuppressLint("MissingPermission")
    fun sendNavBytes(bytes: ByteArray) {
        lastKnownNavBytes = bytes
        pendingBytes = bytes
        if (bytesWriteScheduled) return
        bytesWriteScheduled = true
        handler.postDelayed({
            bytesWriteScheduled = false
            val payload = pendingBytes ?: return@postDelayed
            val gatt = bluetoothGatt ?: return@postDelayed
            val char = navCharacteristic ?: return@postDelayed
            val data = payload.take(MAX_PACKET_BYTES).toByteArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char, data,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }, WRITE_DEBOUNCE_MS)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnect = true
        handler.removeCallbacksAndMessages(null)

        val gatt = bluetoothGatt
        if (gatt == null) {
            // Nothing to tear down.
            navCharacteristic = null
            state = BleState.DISCONNECTED
            return
        }

        // Ask the OS to actually tear down the LE link first. close() is
        // intentionally NOT called here — closing the GATT client before the
        // system confirms the disconnect is what leaves the device showing
        // as "connected" in Android's quick settings/Bluetooth panel even
        // though the app has moved on. gattCallback's
        // onConnectionStateChange(STATE_DISCONNECTED) now owns close()
        // and will fire once the OS confirms the link is really down.
        gatt.disconnect()

        // The nav characteristic is plain GATT and gatt.disconnect() above
        // is enough for it — but the ESP32 also exposes a HOGP Consumer
        // Control characteristic for media/call buttons, which is why we
        // bond (createBond) in the first place. Once bonded, Android's
        // system Bluetooth stack keeps its own HID-host connection to the
        // device that is completely separate from this BluetoothGatt
        // object; gatt.disconnect()/close() has no effect on it. That's
        // what leaves the device shown as "Connected" in system Bluetooth
        // settings/quick panel after an app-level disconnect, and it's also
        // why it won't reappear in a fresh BLE scan (it's not advertising
        // while the OS still holds it connected).
        //
        // There's no public API to tell the OS to drop just the HID
        // connection, so the only way to fully release a bonded HOGP device
        // is to remove the bond outright. removeBond() is a hidden API —
        // reflection is the standard workaround for this.
        try {
            val device = gatt.device
            val method = device.javaClass.getMethod("removeBond")
            val removed = method.invoke(device) as? Boolean
            Log.d(TAG, "removeBond() on disconnect -> $removed")
        } catch (e: Exception) {
            Log.w(TAG, "removeBond() via reflection failed: $e")
        }

        // Update app-level UI state immediately so the button/status don't
        // feel laggy, but leave the underlying gatt reference and cleanup to
        // the callback so we don't race it.
        state = BleState.DISCONNECTED
    }

    /**
     * BikeNav devices the OS still considers bonded, even if they're not
     * currently advertising (and therefore won't show up in a fresh BLE
     * scan). Used to let the user reconnect to a lingering bonded device
     * without having to manually "Forget" it in system Bluetooth settings
     * first.
     */
    @SuppressLint("MissingPermission")
    fun getBondedCandidates(context: Context): List<ScannedDevice> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter ?: return emptyList()
        val bonded = adapter.bondedDevices ?: return emptyList()
        return bonded
            .filter { it.name != null }
            .map { ScannedDevice(it, it.name, it.address, rssi = 0) }
    }
}
