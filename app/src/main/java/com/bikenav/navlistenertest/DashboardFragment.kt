package com.bikenav.navlistenertest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.color.MaterialColors
import android.content.Context

class DashboardFragment : Fragment() {

    private lateinit var statusDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var scanOtherButton: MaterialButton
    private lateinit var autoReconnectSwitch: SwitchMaterial
    private lateinit var logView: TextView
    private lateinit var scrollView: NestedScrollView
    private lateinit var logsSection: View

    private val logListener: (String) -> Unit = { line ->
        activity?.runOnUiThread {
            if (isAdded) {
                logView.append(line + "\n\n")
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private val bleStateListener: (BleState) -> Unit = { _ ->
        activity?.runOnUiThread {
            if (isAdded) {
                refreshBleStatus()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusDot = view.findViewById(R.id.statusDot)
        statusText = view.findViewById(R.id.statusText)
        actionButton = view.findViewById(R.id.actionButton)
        scanOtherButton = view.findViewById(R.id.scanOtherButton)
        autoReconnectSwitch = view.findViewById(R.id.autoReconnectSwitch)
        logView = view.findViewById(R.id.logView)
        scrollView = view.findViewById(R.id.scrollView)
        logsSection = view.findViewById(R.id.logsSection)

        val settingsButton = view.findViewById<ImageButton>(R.id.settingsButton)
        val clearLogButton = view.findViewById<MaterialButton>(R.id.clearLogButton)

        settingsButton.setOnClickListener {
            (requireActivity() as MainActivity).showSettingsDialog()
        }

        clearLogButton.setOnClickListener {
            logView.text = ""
        }

        autoReconnectSwitch.isChecked = Prefs.autoReconnect(requireContext())
        autoReconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoReconnect(requireContext(), isChecked)
            BleNavClient.autoReconnectEnabled = isChecked
        }

        actionButton.setOnClickListener {
            val state = BleNavClient.state
            if (state == BleState.CONNECTED) {
                BleNavClient.disconnect()
            } else {
                (requireActivity() as MainActivity).requestBlePermissionsIfNeeded()
                BleNavClient.connectToSaved(requireContext())
            }
        }

        scanOtherButton.setOnClickListener {
            (requireActivity() as MainActivity).requestBlePermissionsIfNeeded()
            (requireActivity() as MainActivity).showDeviceScanDialog()
        }

        NavLog.addListener(logListener)
        BleNavClient.addStateListener(bleStateListener)
        refreshBleStatus()
        refreshLogsVisibility()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        NavLog.removeListener(logListener)
        BleNavClient.removeStateListener(bleStateListener)
    }

    /** Shows/hides the LOGS card based on the "Show debug logs" setting. */
    fun refreshLogsVisibility() {
        if (!isAdded) return
        val show = Prefs.showLogs(requireContext())
        logsSection.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun attrColor(attrResId: Int): Int =
        MaterialColors.getColor(requireContext(), attrResId, javaClass.simpleName)

    private fun refreshBleStatus() {
        val state = BleNavClient.state
        val connected = state == BleState.CONNECTED
        val pairedName = Prefs.pairedDeviceName(requireContext())

        val (text, dotColor) = when {
            connected && pairedName != null -> getString(R.string.status_connected_to, pairedName) to resources.getColor(R.color.success, null)
            connected -> getString(R.string.status_connected) to resources.getColor(R.color.success, null)
            state == BleState.CONNECTING -> getString(R.string.status_connecting) to attrColor(com.google.android.material.R.attr.colorPrimary)
            state == BleState.SCANNING -> getString(R.string.bluetooth_scanning) to attrColor(com.google.android.material.R.attr.colorPrimary)
            pairedName != null -> getString(R.string.status_paired, pairedName) to attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            else -> getString(R.string.status_disconnected) to attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        
        statusText.text = text
        statusDot.setColorFilter(dotColor)
        
        actionButton.text = if (connected) "Disconnect" else "Reconnect"
    }
}
