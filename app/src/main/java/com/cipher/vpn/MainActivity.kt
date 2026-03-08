package com.cipher.vpn

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var powerButton: FloatingActionButton
    private lateinit var statusText: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var serverSpinner: Spinner

    private var isConnected = false
    private val httpClient = OkHttpClient()

    private val apiUrl = "http://76.13.59.198:5000"

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startProxyVpn()
        } else {
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        powerButton = findViewById(R.id.powerButton)
        statusText = findViewById(R.id.statusText)
        ipAddressText = findViewById(R.id.ipAddressText)
        serverSpinner = findViewById(R.id.serverSpinner)

        setupSpinner()
        checkServerStatus()

        powerButton.setOnClickListener {
            toggleConnection()
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
                if (isConnected) {
                    val location = serverSpinner.selectedItem.toString()
                    changeNordVpnLocation(location)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun toggleConnection() {
        if (!isConnected) {
            connectVpn()
        } else {
            disconnectVpn()
        }
    }

    private fun connectVpn() {
        Log.d("MainActivity", "Attempting connection")
        // Start Android VPN machinery
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startProxyVpn()
        }
    }

    private fun startProxyVpn() {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_CONNECT
        }
        startService(intent)
        
        // Also tell VPS to connect to chosen country if needed
        val location = serverSpinner.selectedItem.toString()
        changeNordVpnLocation(location)
        
        updateUI(true)
    }

    private fun disconnectVpn() {
        Log.d("MainActivity", "Disconnecting")
        
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_DISCONNECT
        }
        startService(intent)

        // Tell VPS to drop NordVPN proxy routing and revert to direct Squid
        sendApiRequest("/disconnect", "POST", "") {}

        updateUI(false)
    }

    private fun updateUI(connected: Boolean) {
        isConnected = connected
        runOnUiThread {
            if (connected) {
                powerButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                statusText.text = getString(R.string.status_connected)
                statusText.setTextColor(Color.parseColor("#10B981"))
                fetchIpAddress()
            } else {
                powerButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
                statusText.text = getString(R.string.status_disconnected)
                statusText.setTextColor(Color.WHITE)
                ipAddressText.text = "IP: --"
            }
        }
    }

    private fun changeNordVpnLocation(locationName: String) {
        val countryCode = when (locationName) {
            "United States" -> "us"
            "United Kingdom" -> "uk"
            "Germany" -> "de"
            "Japan" -> "jp"
            else -> "fr" // Default to France
        }

        if (countryCode == "fr" && locationName == "France (VPS Direct)") {
             sendApiRequest("/disconnect", "POST", "") {
                 fetchIpAddress()
             }
             return
        }

        val json = """{"country":"$countryCode"}"""
        sendApiRequest("/connect", "POST", json) {
            fetchIpAddress()
        }
    }

    private fun checkServerStatus() {
        sendApiRequest("/status", "GET", "") { json ->
            try {
                val obj = JSONObject(json)
                val status = obj.optString("vpn_status")
                val isPtp = obj.optBoolean("point_to_point_active")
                runOnUiThread {
                    if (isPtp) {
                        Log.d("MainActivity", "NordVPN proxy is up: \$status")
                    } else {
                        Log.d("MainActivity", "NordVPN proxy is down (VPS Direct Mode)")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "JSON Parse error on status", e)
            }
        }
    }

    private fun fetchIpAddress() {
        // Simple call to check what IP we are presenting via the VPN proxy
        // This request will be routed through the VPN if it's running
        val request = Request.Builder()
            .url("https://api.ipify.org?format=json")
            .build()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { ipAddressText.text = "IP: Error" }
            }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let {
                    try {
                        val ip = JSONObject(it).getString("ip")
                        runOnUiThread { ipAddressText.text = "IP: \$ip" }
                    } catch (e: Exception) {
                        runOnUiThread { ipAddressText.text = "IP: Unknown" }
                    }
                }
            }
        })
    }

    private fun sendApiRequest(endpoint: String, method: String, jsonBody: String, onResponse: (String) -> Unit) {
        val url = "$apiUrl$endpoint"
        val builder = Request.Builder().url(url)
        
        if (method == "POST") {
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            builder.post(body)
        }

        val request = builder.build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API", "Request failed to \$endpoint", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "VPS API Error $endpoint", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                Log.d("API", "Response from \$endpoint: \$respBody")
                onResponse(respBody)
            }
        })
    }
}
