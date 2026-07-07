package com.bikenav.navlistenertest

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PermissionsFragment : Fragment() {

    private lateinit var notifStatusView: TextView
    private lateinit var accessStatusView: TextView
    private lateinit var notifEnableButton: MaterialButton
    private lateinit var accessEnableButton: MaterialButton
    private lateinit var continueButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        notifStatusView = view.findViewById(R.id.notifStatusView)
        accessStatusView = view.findViewById(R.id.accessStatusView)
        notifEnableButton = view.findViewById(R.id.notifEnableButton)
        accessEnableButton = view.findViewById(R.id.accessEnableButton)
        continueButton = view.findViewById(R.id.continueButton)
        
        val notifHelpButton = view.findViewById<ImageButton>(R.id.notifHelpButton)
        val accessHelpButton = view.findViewById<ImageButton>(R.id.accessHelpButton)

        notifEnableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        accessEnableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        notifHelpButton.setOnClickListener {
            showDisclosure(
                title = "Before you enable this",
                message = "This lets the app read notification text on your phone, but it only " +
                    "ever looks at Google Maps directions — nothing else is read or sent " +
                    "anywhere. You'll see an Android warning about \"all notifications\" " +
                    "because that's how this permission works for every app, not because " +
                    "this app uses more than Maps."
            )
        }

        accessHelpButton.setOnClickListener {
            showDisclosure(
                title = "Now enable the second permission",
                message = "This is the most powerful permission on Android — apps with it can " +
                    "in principle see what's on your screen. This app only uses it to keep " +
                    "relaying directions while the screen is off; it doesn't read your " +
                    "screen or other apps. Only grant this to apps you trust and built or " +
                    "reviewed yourself."
            )
        }

        continueButton.setOnClickListener {
            (requireActivity() as MainActivity).advanceFromPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        
        val notifGranted = (requireActivity() as MainActivity).isNotificationAccessGranted()
        val accessGranted = (requireActivity() as MainActivity).isAccessibilityGranted()

        updateCardState(notifStatusView, notifEnableButton, notifGranted, getString(R.string.permission_notif_desc))
        updateCardState(accessStatusView, accessEnableButton, accessGranted, getString(R.string.permission_access_desc))

        val allGranted = notifGranted && accessGranted
        continueButton.isEnabled = allGranted
        continueButton.alpha = if (allGranted) 1.0f else 0.5f
    }

    private fun updateCardState(statusView: TextView, button: MaterialButton, granted: Boolean, defaultDesc: String) {
        if (granted) {
            statusView.text = getString(R.string.permission_granted)
            button.visibility = View.GONE
        } else {
            statusView.text = defaultDesc
            button.visibility = View.VISIBLE
        }
    }

    private fun showDisclosure(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
