package com.kartik.aistudyassistant.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.kartik.aistudyassistant.BuildConfig
import com.google.android.material.button.MaterialButton
import com.kartik.aistudyassistant.R

class OfflineActivity : AppCompatActivity() {

    companion object {
        private const val LOG_TAG = "OfflineRouter"
    }

    private lateinit var btnRefresh: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_offline)

        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "OfflineActivity opened")
        }

        btnRefresh = findViewById(R.id.btnRefreshConnection)
        setupListeners()
        blockBackWhileOffline()
    }

    private fun setupListeners() {
        btnRefresh.setOnClickListener {
            if (isInternetAvailable()) {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "Refresh succeeded; closing OfflineActivity")
                }
                finish()
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "Refresh failed; internet still unavailable")
                }
                Toast.makeText(
                    this,
                    getString(R.string.offline_still_disconnected_message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun blockBackWhileOffline() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Toast.makeText(
                        this@OfflineActivity,
                        getString(R.string.offline_connect_first_message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        val hasInternet = capabilities.hasCapability(NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NET_CAPABILITY_VALIDATED)
        return hasInternet && isValidated
    }
}




