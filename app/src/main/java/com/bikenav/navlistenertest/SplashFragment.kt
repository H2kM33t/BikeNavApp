package com.bikenav.navlistenertest

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class SplashFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Enforce the SplashTheme for this fragment's context
        val contextThemeWrapper = android.view.ContextThemeWrapper(requireContext(), R.style.Theme_BikeNav_Splash)
        val themedInflater = inflater.cloneInContext(contextThemeWrapper)
        return themedInflater.inflate(R.layout.fragment_splash, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded) {
                (requireActivity() as MainActivity).advanceFromSplash()
            }
        }, 2000) // 2 second splash as per plan
    }
}
