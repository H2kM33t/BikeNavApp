package com.bikenav.navlistenertest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class BluetoothFragment : Fragment() {

    private lateinit var deviceListView: ListView
    private lateinit var scanningProgressContainer: View
    private lateinit var scanningLabel: TextView
    private lateinit var scanButton: MaterialButton
    
    private val foundDevices = mutableListOf<ScannedDevice>()
    private val labels = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private var isScanning = false

    private val bleStateListener: (BleState) -> Unit = { state ->
        if (state == BleState.CONNECTED) {
            activity?.runOnUiThread {
                if (isAdded) {
                    (requireActivity() as MainActivity).advanceFromBluetooth()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_bluetooth, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        deviceListView = view.findViewById(R.id.deviceListView)
        scanningProgressContainer = view.findViewById(R.id.scanningProgressContainer)
        scanningLabel = view.findViewById(R.id.scanningLabel)
        scanButton = view.findViewById(R.id.scanButton)
        
        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        deviceListView.adapter = adapter
        
        scanningProgressContainer.visibility = View.GONE
        
        scanButton.setOnClickListener {
            if (!isScanning) {
                startScan()
            }
        }
        
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val picked = foundDevices[position]
            BleNavClient.stopDeviceScan(requireContext())
            isScanning = false
            Prefs.setPairedDevice(requireContext(), picked.address, picked.name)
            BleNavClient.setPairedAddress(picked.address)
            BleNavClient.connect(requireContext(), picked.device)
        }
        
        BleNavClient.addStateListener(bleStateListener)

        // Request permissions if needed, then we can start scan automatically if desired
        (requireActivity() as MainActivity).requestBlePermissionsIfNeeded()
        
        // Auto start scan on view created (startScan() itself now checks
        // whether Bluetooth is actually on before touching the scanner)
        startScan()
    }

    private fun startScan() {
        val mainActivity = requireActivity() as MainActivity
        if (!mainActivity.hasBlePermissions()) return

        if (!mainActivity.isBluetoothEnabled()) {
            // Scanning while Bluetooth is off is what causes the crash — ask
            // the user to turn it on first instead of touching the scanner.
            scanningProgressContainer.visibility = View.VISIBLE
            scanningProgressContainer.findViewById<View>(R.id.scanProgress).visibility = View.GONE
            scanningLabel.text = "Please turn on Bluetooth to scan for your bike display."
            mainActivity.requestEnableBluetooth()
            return
        }

        foundDevices.clear()
        labels.clear()
        adapter.notifyDataSetChanged()
        
        isScanning = true
        scanningProgressContainer.visibility = View.VISIBLE
        scanningLabel.text = getString(R.string.bluetooth_scanning)
        scanButton.isEnabled = false
        
        BleNavClient.startDeviceScan(
            context = requireContext(),
            onDeviceFound = { found ->
                activity?.runOnUiThread {
                    if (foundDevices.none { it.address == found.address }) {
                        foundDevices.add(found)
                        val recommended = if (found.name == BleUuids.DEVICE_NAME) " (recommended)" else ""
                        labels.add("${found.name}$recommended")
                        adapter.notifyDataSetChanged()
                    }
                }
            },
            onScanFinished = {
                activity?.runOnUiThread {
                    isScanning = false
                    scanningProgressContainer.visibility = View.GONE
                    scanButton.isEnabled = true
                    if (foundDevices.isEmpty()) {
                        scanningLabel.text = getString(R.string.bluetooth_no_devices)
                        scanningProgressContainer.visibility = View.VISIBLE
                        scanningProgressContainer.findViewById<View>(R.id.scanProgress).visibility = View.GONE
                    }
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        BleNavClient.stopDeviceScan(requireContext())
        BleNavClient.removeStateListener(bleStateListener)
    }
}
