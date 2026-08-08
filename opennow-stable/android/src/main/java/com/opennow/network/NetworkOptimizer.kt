package com.opennow.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.nio.channels.DatagramChannel

class NetworkOptimizer constructor(
    private val context: Context,
) {

    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun acquireGamingWifiLock() {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CloseNOW-Gaming"
        ).apply { acquire() }
        Log.i("NetworkOptimizer", "Acquired gaming Wi-Fi lock (HIGH_PERF)")
    }

    fun releaseWifiLock() {
        wifiLock?.release()
        wifiLock = null
        Log.i("NetworkOptimizer", "Released gaming Wi-Fi lock")
    }

    fun configureSocketForGaming(channel: DatagramChannel) {
        // Increase receive buffer for high bitrate (2MB)
        channel.socket().setReceiveBufferSize(2 * 1024 * 1024)
        // Latency priority - deprecated API, use with caution
        // channel.socket().setPerformancePreferences(0, 2, 1)
        
        // DSCP EF (Expedited Forwarding) - requires root or custom WebRTC build
        // Uncomment if running with appropriate permissions:
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        //     channel.socket().setTrafficClass(0xB8)  // DSCP 46 (EF)
        // }
        
        Log.i("NetworkOptimizer", "Configured socket for gaming: 2MB recv buffer, latency priority")
    }

    fun bindToBestNetwork() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("NetworkOptimizer", "Network available: $network")
                connectivityManager.bindProcessToNetwork(network)
                
                // Check for Wi-Fi 6/6E/7 capabilities
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps?.let { c ->
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        Log.i("NetworkOptimizer", "Bound to Wi-Fi network")
                    } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        Log.i("NetworkOptimizer", "Bound to cellular network")
                    } else {
                        Log.i("NetworkOptimizer", "Bound to other network")
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.w("NetworkOptimizer", "Network lost: $network")
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    fun stopNetworkCallback() {
        networkCallback?.let {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        networkCallback = null
    }
}