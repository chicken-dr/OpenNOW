package com.closenow.session

import android.content.Context
import android.os.PowerManager

class ResourceManager {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    
    fun acquireWakeLock(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CloseNOW::WakeLock")
        wakeLock?.acquire()
    }
    
    fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }
    
    fun acquireWifiLock(context: Context) {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CloseNOW::WifiLock")
        wifiLock?.acquire()
    }
    
    fun releaseWifiLock() {
        wifiLock?.release()
        wifiLock = null
    }
    
    fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true
    fun isWifiLockHeld(): Boolean = wifiLock?.isHeld == true
    
    fun shutdown() {
        releaseWakeLock()
        releaseWifiLock()
    }
}