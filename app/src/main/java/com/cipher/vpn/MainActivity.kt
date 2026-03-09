package com.cipher.vpn

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var powerButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var protocolInfoText: TextView
    private lateinit var serverSpinner: Spinner
    
    // Visualizer rings
    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View
    private var pulseAnimators = mutableListOf<ObjectAnimator>()

    private var currentConnectionStatus = "disconnected" // "disconnected", "connecting", "connected", "error"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val apiUrl = "http://76.13.59.198:8900"
    
    private val handler = Handler(Looper.getMainLooper())
    private var isPollingStatus = false

    companion object {
        private const val TAG = "MainActivity"
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startProxyVpn()
        } else {
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
            updateUI("disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        powerButton = findViewById(R.id.powerButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusText = findViewById(R.id.statusText)
        statusSub = findViewById(R.id.statusSub)
        ipAddressText = findViewById(R.id.ipAddressText)
        protocolInfoText = findViewById(R.id.protocolInfoText)
        serverSpinner = findViewById(R.id.serverSpinner)
        
        pulseRing1 = findViewById(R.id.pulseRing1)
        pulseRing2 = findViewById(R.id.pulseRing2)
        pulseRing3 = findViewById(R.id.pulseRing3)

        setupSpinner()
        
        // Initial Poll to check if already connected
        pollStatus(once = true)

        powerButton.setOnClickListener {
            toggleConnection()
        }
        
        settingsButton.setOnClickListener {
            // Unimplemented for now until SettingsActivity exists
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        setupPulseAnimations()
    }
    
    override fun onResume() {
        super.onResume()
        if (!isPollingStatus) {
            pollStatus()
        }
    }
    
    override fun onPause() {
        super.onPause()
        isPollingStatus = false
    }

    private fun setupPulseAnimations() {
        val rings = listOf(pulseRing1 to 0L, pulseRing2 to 800L, pulseRing3 to 1600L)
        for ((ring, delay) in rings) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 2.5f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 2.5f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f)
            
            val animator = ObjectAnimator.ofPropertyValuesHolder(ring, scaleX, scaleY, alpha).apply {
                duration = 2500
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                startDelay = delay
            }
            pulseAnimators.add(animator)
            ring.scaleX = 0f
            ring.scaleY = 0f
        }
    }
    
    private fun startPulse() {
        if (!pulseAnimators[0].isRunning) {
            pulseAnimators.forEach { it.start() }
        }
    }
    
    private fun stopPulse() {
        pulseAnimators.forEach { 
            it.cancel()
            val target = it.target as View
            target.scaleX = 0f
            target.scaleY = 0f
            target.alpha = 0f
        }
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.vpn_locations,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        serverSpinner.adapter = adapter

        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val location = serverSpinner.selectedItem.toString()
                if (location.startsWith("⭐") || location.startsWith("\uD83C") || location.startsWith("\uD83D")) {
                    return
                }
                
                if (currentConnectionStatus == "connected" || currentConnectionStatus == "connecting") {
                    changeNordVpnLocation(location)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun toggleConnection() {
        if (currentConnectionStatus == "disconnected" || currentConnectionStatus == "error") {
            connectVpn()
        } else {
            disconnectVpn()
        }
    }

    private fun connectVpn() {
        Log.d(TAG, "Attempting connection")
        updateUI("connecting")
        try {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startProxyVpn()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing VPN", e)
            Toast.makeText(this, "VPN Error: ${e.message}", Toast.LENGTH_LONG).show()
            updateUI("error")
        }
    }

    private fun startProxyVpn() {
        try {
            val serviceIntent = Intent(this, ProxyVpnService::class.java)
            serviceIntent.action = ProxyVpnService.ACTION_CONNECT
            ContextCompat.startForegroundService(this, serviceIntent)

            val location = serverSpinner.selectedItem.toString()
            if (!location.startsWith("⭐") && !location.startsWith("\uD83C") && !location.startsWith("\uD83D")) {
                changeNordVpnLocation(location)
            } else {
                changeNordVpnLocation("United_States") // fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN service", e)
            Toast.makeText(this, "Failed to start VPN: ${e.message}", Toast.LENGTH_LONG).show()
            updateUI("error")
        }
    }

    private fun disconnectVpn() {
        Log.d(TAG, "Disconnecting")
        updateUI("connecting")
        try {
            val serviceIntent = Intent(this, ProxyVpnService::class.java)
            serviceIntent.action = ProxyVpnService.ACTION_DISCONNECT
            startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN service", e)
        }

        sendApiRequest("/connect?country=France", "GET") {
            pollStatus(once = true)
        }
    }

    private fun changeNordVpnLocation(locationName: String) {
        val cleanName = locationName.replace(" ", "_").replace("_(VPS_Direct)", "")
        val queryParam = if (locationName.contains("France")) "France" else cleanName

        sendApiRequest("/connect?country=$queryParam", "GET") {
            if (!isPollingStatus) pollStatus()
        }
    }

    private fun pollStatus(once: Boolean = false) {
        if (!once && isPollingStatus) return
        if (!once) isPollingStatus = true
        
        sendApiRequest("/status", "GET") { json ->
            try {
                val obj = JSONObject(json)
                val status = obj.optString("status", "")
                
                runOnUiThread {
                    if (status == "connecting") {
                        updateUI("connecting")
                        ipAddressText.text = "Negotiating..."
                    } else if (status == "error") {
                        updateUI("error")
                        val errCode = obj.optString("error", "Failed")
                        Toast.makeText(this@MainActivity, "NordVPN Error: $errCode", Toast.LENGTH_LONG).show()
                    } else if (status == "success") {
                        val vpnOn = obj.optBoolean("vpn_connected", false)
                        val ip = obj.optString("ip", "Unknown")
                        val details = obj.optString("details", "")
                        
                        // Parse protocol from status
                        if (details.contains("NordLynx", ignoreCase = true)) {
                            protocolInfoText.text = "NordLynx"
                        } else if (details.contains("OpenVPN", ignoreCase = true)) {
                            protocolInfoText.text = "OpenVPN"
                        }
                        
                        if (vpnOn) {
                            updateUI("connected")
                        } else {
                            updateUI("disconnected")
                        }
                        ipAddressText.text = if (ip.length > 5) ip else "Direct"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Status parse failed", e)
            }
            
            if (!once && isPollingStatus) {
                handler.postDelayed({ pollStatus() }, 2000)
            }
        }
    }

    private fun updateUI(status: String) {
        currentConnectionStatus = status
        
        val colorPrimary = Color.parseColor("#1E293B")
        val colorAccentText = Color.parseColor("#10B981")
        val colorConnectingText = Color.parseColor("#FFAB40")
        val colorErrorText = Color.parseColor("#EF4444")
        val colorMuted = Color.parseColor("#94A3B8")
        
        when (status) {
            "connected" -> {
                powerButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                powerButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
                
                pulseRing1.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2610B981"))
                pulseRing2.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2610B981"))
                pulseRing3.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2610B981"))
                startPulse()
                
                statusText.text = "Secured"
                statusText.setTextColor(colorAccentText)
                
                val serverName = serverSpinner.selectedItem?.toString() ?: "Unknown"
                statusSub.text = serverName
            }
            "connecting" -> {
                powerButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFAB40"))
                powerButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
                
                pulseRing1.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#40FFAB40"))
                pulseRing2.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#40FFAB40"))
                pulseRing3.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#40FFAB40"))
                startPulse()

                statusText.text = "Connecting..."
                statusText.setTextColor(colorConnectingText)
                statusSub.text = "Establishing secure link"
            }
            "error" -> {
                powerButton.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                powerButton.imageTintList = ColorStateList.valueOf(colorMuted)
                stopPulse()
                
                statusText.text = "Failed"
                statusText.setTextColor(colorErrorText)
                statusSub.text = "Connection failed — try again"
                ipAddressText.text = "Unavailable"
                
                handler.postDelayed({
                    if (currentConnectionStatus == "error") {
                        updateUI("disconnected")
                    }
                }, 4000)
            }
            else -> { // disconnected
                powerButton.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                powerButton.imageTintList = ColorStateList.valueOf(colorMuted)
                stopPulse()
                
                statusText.text = "Disconnected"
                statusText.setTextColor(colorMuted)
                statusSub.text = "Ready to secure"
            }
        }
    }

    private fun sendApiRequest(endpoint: String, method: String, onResponse: (String) -> Unit) {
        val url = apiUrl + endpoint
        val requestBuilder = Request.Builder().url(url)

        if (method == "GET") {
            requestBuilder.get()
        }

        val request = requestBuilder.build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API request failed: $endpoint", e)
                if (endpoint.contains("/status")) {
                    // Only error out on status checks if needed, but for now ignore transient network drops while polling
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                Log.d(TAG, "API response from $endpoint: $respBody")
                onResponse(respBody)
            }
        })
    }
}
