package com.cipher.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

class ProxyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_CONNECT = "com.cipher.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.cipher.vpn.DISCONNECT"
        const val PROXY_HOST = "76.13.59.198"
        const val PROXY_PORT = 8899
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connectVpn()
            ACTION_DISCONNECT -> disconnectVpn()
        }
        return START_NOT_STICKY
    }

    private fun connectVpn() {
        if (vpnInterface != null) return

        startForegroundNotification()

        try {
            val builder = Builder()

            // Define the VPN interface parameters
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0) // Route all traffic through the VPN
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")

            // Set the HTTP Proxy (Requires API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val proxyInfo = ProxyInfo.buildDirectProxy(PROXY_HOST, PROXY_PORT)
                builder.setHttpProxy(proxyInfo)
                Log.d("ProxyVpnService", "HTTP Proxy set to $PROXY_HOST:$PROXY_PORT")
            } else {
                Log.e("ProxyVpnService", "setHttpProxy requires API >= 29")
            }

            builder.setSession("CipherVPN")
            // Note: setConfigureIntent is optional, skipping it entirely

            vpnInterface = builder.establish()
            Log.d("ProxyVpnService", "VPN Established")
        } catch (e: Exception) {
            Log.e("ProxyVpnService", "Error starting VPN", e)
            disconnectVpn()
        }
    }

    private fun disconnectVpn() {
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d("ProxyVpnService", "VPN Disconnected")
    }

    private fun startForegroundNotification() {
        val channelId = "CipherVpnChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CipherVPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("CipherVPN is Active")
            .setContentText("Your traffic is being routed securely.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        disconnectVpn()
        super.onDestroy()
    }
}
