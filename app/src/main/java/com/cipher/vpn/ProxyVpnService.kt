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
        private const val TAG = "ProxyVpnService"
        private const val CHANNEL_ID = "CipherVpnChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CONNECT -> {
                connectVpn()
                START_STICKY
            }
            ACTION_DISCONNECT -> {
                disconnectVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun connectVpn() {
        if (vpnInterface != null) {
            Log.d(TAG, "Already connected")
            return
        }

        // Must show notification FIRST before doing VPN work
        showForegroundNotification()

        try {
            val builder = Builder()

            // Minimal VPN interface — just an address, no routes
            // This avoids blackholing traffic since we have no packet forwarder
            builder.addAddress("10.0.0.2", 32)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")

            // Set the HTTP proxy so apps route HTTP traffic through our VPS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val proxyInfo = ProxyInfo.buildDirectProxy(PROXY_HOST, PROXY_PORT)
                builder.setHttpProxy(proxyInfo)
                Log.d(TAG, "HTTP Proxy set to $PROXY_HOST:$PROXY_PORT")
            }

            builder.setSession("CipherVPN")
            builder.setMtu(1500)

            // Allow the proxy server to bypass the VPN
            // This prevents a routing loop where proxy traffic gets captured by VPN
            builder.addRoute("10.0.0.0", 8)

            val iface = builder.establish()
            if (iface == null) {
                Log.e(TAG, "establish() returned null — VPN permission not granted?")
                stopSelf()
                return
            }

            vpnInterface = iface
            Log.d(TAG, "VPN established successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            disconnectVpn()
        }
    }

    private fun disconnectVpn() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "VPN disconnected")
    }

    private fun showForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CipherVPN Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when CipherVPN is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CipherVPN is Active")
            .setContentText("Your traffic is being routed securely.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        disconnectVpn()
        super.onDestroy()
    }
}
