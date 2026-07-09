package com.bikenav.navlistenertest

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Fragments listen to BleNavClient states directly now, but this receiver
            // is good to keep BluetoothAdapter state sync
        }
    }

    // Launches the system's "Allow app to turn on Bluetooth?" dialog.
    private val enableBluetoothLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            evaluateStateAndRoute()
        }

    // Set right before we ask for BLUETOOTH_CONNECT specifically so we can
    // chain straight into the enable-Bluetooth prompt once it's granted.
    private var pendingEnableBluetoothAfterPermission = false

    private val blePermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = results.values.all { it }
            if (allGranted && pendingEnableBluetoothAfterPermission) {
                pendingEnableBluetoothAfterPermission = false
                requestEnableBluetooth()
            } else {
                pendingEnableBluetoothAfterPermission = false
                evaluateStateAndRoute()
            }
        }

    /** Used to detect the moment notification access gets granted, so we can chain into the accessibility prompt. */
    var previousNotifGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Apply persisted settings
        BleNavClient.autoReconnectEnabled = Prefs.autoReconnect(this)
        BleNavClient.setPairedAddress(Prefs.pairedDeviceAddress(this))

        if (savedInstanceState == null) {
            // First time logic
            val isFirstLaunch = true // We can use prefs for this, but splash on every launch is fine
            if (isFirstLaunch) {
                showFragment(SplashFragment(), addToBackStack = false)
            } else {
                evaluateStateAndRoute()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(bluetoothStateReceiver)
    }

    private fun showFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
        if (addToBackStack) {
            tx.addToBackStack(null)
        }
        tx.commit()
    }

    fun advanceFromSplash() {
        evaluateStateAndRoute()
    }

    fun advanceFromPermissions() {
        evaluateStateAndRoute()
    }

    fun advanceFromBluetooth() {
        evaluateStateAndRoute()
    }

    fun evaluateStateAndRoute() {
        if (!isNotificationAccessGranted() || !isAccessibilityGranted()) {
            showFragment(PermissionsFragment(), addToBackStack = false)
        } else if (Prefs.pairedDeviceAddress(this) == null) {
            showFragment(BluetoothFragment(), addToBackStack = false)
        } else {
            showFragment(DashboardFragment(), addToBackStack = false)
            // Try to auto-connect if possible
            if (BleNavClient.state == BleState.DISCONNECTED) {
                BleNavClient.connectToSaved(this)
            }
        }
    }

    fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }

    fun isAccessibilityGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.contains(packageName) == true
    }

    fun isBluetoothEnabled(): Boolean {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return adapter?.isEnabled == true
    }

    fun requestEnableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Need BLUETOOTH_CONNECT before we're even allowed to ask for this.
            // Request it, and once granted, come straight back here — no second tap needed.
            pendingEnableBluetoothAfterPermission = true
            blePermissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
            return
        }
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1001
                )
            }
        }
        // On Android 12+, BLUETOOTH_SCAN is declared "neverForLocation", so
        // granting it does NOT also grant ACCESS_FINE_LOCATION the way it
        // used to pre-S. GPS speed (GpsSpeedProvider) needs it explicitly,
        // decoupled from BLE — request it here too if still missing.
        requestLocationPermissionIfNeeded()
    }

    fun requestLocationPermissionIfNeeded() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1002
            )
        }
    }

    fun showDeviceScanDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_device_scan, null)
        val listView = view.findViewById<ListView>(R.id.deviceListView)
        val scanningLabel = view.findViewById<TextView>(R.id.scanningLabel)
        val scanningProgress = view.findViewById<View>(R.id.scanProgress)

        val foundDevices = mutableListOf<ScannedDevice>()
        val labels = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Nearby devices")
            .setView(view)
            .setNegativeButton("Cancel") { d, _ ->
                BleNavClient.stopDeviceScan(this)
                d.dismiss()
            }
            .setOnDismissListener { BleNavClient.stopDeviceScan(this) }
            .show()

        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = foundDevices[position]
            BleNavClient.stopDeviceScan(this)
            Prefs.setPairedDevice(this, picked.address, picked.name)
            BleNavClient.setPairedAddress(picked.address)
            BleNavClient.connect(this, picked.device)
            dialog.dismiss()
        }

        BleNavClient.startDeviceScan(
            context = this,
            onDeviceFound = { found ->
                runOnUiThread {
                    if (foundDevices.none { it.address == found.address }) {
                        foundDevices.add(found)
                        val recommended = if (found.name == BleUuids.DEVICE_NAME) " (recommended)" else ""
                        labels.add("${found.name}$recommended")
                        adapter.notifyDataSetChanged()
                    }
                }
            },
            onScanFinished = {
                runOnUiThread {
                    scanningProgress.visibility = View.GONE
                    scanningLabel.text = if (foundDevices.isEmpty())
                        "No devices found. Make sure your bike computer is powered on and nearby."
                    else
                        "Tap a device to connect"
                }
            }
        )
    }

    fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val showLogsSwitch = view.findViewById<SwitchMaterial>(R.id.showLogsSwitch)
        showLogsSwitch.isChecked = Prefs.showLogs(this)
        showLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setShowLogs(this, isChecked)
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? DashboardFragment)
                ?.refreshLogsVisibility()
        }

        // IconLearner persists icon-hash -> turn/angle mappings across app
        // restarts (that's the whole point - learn once, not once per
        // ride). But that also means a mapping learned under OLD
        // classification logic silently survives an app UPDATE and keeps
        // overriding the new logic for any icon it already learned - e.g.
        // straight/right-side roundabout icons taught the wrong angle
        // before the RoundaboutGeometry fix keep reading wrong afterward
        // until explicitly cleared, since NavDataState always prefers a
        // learned icon match over a fresh text-based read.
        val clearButton = view.findViewById<android.widget.Button>(R.id.clearLearnedIconsButton)
        clearButton.setOnClickListener {
            // init() is idempotent (no-ops if already initialized) - called
            // here so Clear reliably wipes the PERSISTED prefs even if this
            // is a fresh app launch and the notification listener service
            // (the usual caller of init()) hasn't connected yet.
            IconLearner.init(applicationContext)
            IconLearner.clear()
            android.widget.Toast.makeText(
                this,
                "Learned turn icons cleared - they'll relearn as you ride",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("Done") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
