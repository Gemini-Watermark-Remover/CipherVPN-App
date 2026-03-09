package com.cipher.vpn

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var settingsLoader: ProgressBar
    private lateinit var settingsContent: LinearLayout
    private lateinit var togglesContainer: LinearLayout
    private lateinit var protocolSpinner: Spinner

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val apiUrl = "http://76.13.59.198:8900"
    
    // Toggles we want to display
    private val toggleOptions = listOf(
        "kill_switch",
        "threat_protection_lite",
        "auto_connect",
        "notify",
        "firewall",
        "meshnet",
        "lan_discovery",
        "virtual_location",
        "post-quantum_vpn"
    )

    private var initialProtocol = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        backButton = findViewById(R.id.backButton)
        settingsLoader = findViewById(R.id.settingsLoader)
        settingsContent = findViewById(R.id.settingsContent)
        togglesContainer = findViewById(R.id.togglesContainer)
        protocolSpinner = findViewById(R.id.protocolSpinner)

        backButton.setOnClickListener { finish() }

        setupProtocolSpinner()
        loadSettings()
    }

    private fun setupProtocolSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("NordLynx", "OpenVPN")
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        protocolSpinner.adapter = adapter
    }

    private fun loadSettings() {
        settingsLoader.visibility = View.VISIBLE
        settingsContent.visibility = View.GONE
        
        val url = "$apiUrl/settings"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Failed to load settings", Toast.LENGTH_SHORT).show()
                    settingsLoader.visibility = View.GONE
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                try {
                    val obj = JSONObject(respBody)
                    if (obj.optString("status") == "success") {
                        val settingsMap = obj.optJSONObject("settings")
                        runOnUiThread {
                            buildUI(settingsMap)
                            settingsLoader.visibility = View.GONE
                            settingsContent.visibility = View.VISIBLE
                        }
                    } else {
                        runOnUiThread { hideLoaderWithError() }
                    }
                } catch (e: Exception) {
                    runOnUiThread { hideLoaderWithError() }
                }
            }
        })
    }
    
    private fun hideLoaderWithError() {
        settingsLoader.visibility = View.GONE
        Toast.makeText(this, "Error parsing settings", Toast.LENGTH_SHORT).show()
    }

    private fun buildUI(settingsMap: JSONObject?) {
        togglesContainer.removeAllViews()
        
        // Protocol
        initialProtocol = settingsMap?.optString("technology", "nordlynx")?.lowercase() ?: "nordlynx"
        val position = if (initialProtocol.contains("openvpn")) 1 else 0
        protocolSpinner.setSelection(position)
        
        protocolSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = if (pos == 1) "openvpn" else "nordlynx"
                if (initialProtocol != selected) { // Prevent loop on initial load
                    updateSetting("technology", selected)
                    initialProtocol = selected
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Build toggles
        for (option in toggleOptions) {
            val isChecked = settingsMap?.optBoolean(option, false) ?: false
            val row = createToggleRow(option, isChecked)
            togglesContainer.addView(row)
        }
    }

    private fun createToggleRow(optionKey: String, isChecked: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 32, 0, 32)
            gravity = Gravity.CENTER_VERTICAL
        }

        val labelText = optionKey.split("_")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val textView = TextView(this).apply {
            text = labelText
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switch = Switch(this).apply {
            this.isChecked = isChecked
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            trackTintList = ColorStateList.valueOf(Color.parseColor("#94A3B8"))
            
            setOnCheckedChangeListener { _, checked ->
                updateSetting(optionKey, if (checked) "on" else "off")
            }
        }

        row.addView(textView)
        row.addView(switch)
        return row
    }

    private fun updateSetting(feature: String, value: String) {
        val url = "$apiUrl/set?feature=$feature&value=$value"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Failed to update $feature", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                try {
                    val obj = JSONObject(respBody)
                    if (obj.optString("status") != "success") {
                        val msg = obj.optString("message", "Error")
                        runOnUiThread {
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                            loadSettings() // Refresh to revert UI
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "Error saving setting", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }
}
