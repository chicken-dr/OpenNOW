package com.closenow

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.closenow.R
import com.closenow.decode.DecoderSelector
import com.closenow.decode.MediaTekWorkaround
import com.closenow.device.DeviceOptimizer
import com.closenow.di.AppContainerImpl
import com.closenow.di.AppContainerProvider
import com.closenow.diagnostics.DumpsysCollector
import com.closenow.render.GameSurfaceView
import com.closenow.session.SessionConfig
import com.closenow.session.SessionManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var appContainer: AppContainerImpl
    private lateinit var sessionManager: SessionManager
    private lateinit var deviceOptimizer: DeviceOptimizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize dependency injection container
        appContainer = AppContainerProvider.initialize(this)
        
        // Get dependencies
        sessionManager = appContainer.sessionManager()
        deviceOptimizer = appContainer.deviceOptimizer()
        
        // Set the game surface view in container
        val gameSurfaceView = findViewById<android.view.View>(android.R.id.content) as GameSurfaceView
        appContainer.setGameSurfaceView(gameSurfaceView)
        
        // Set input processor on session manager
        sessionManager.setInputProcessor(appContainer.inputProcessor())

        // Apply device-specific optimizations at startup
        applyDeviceOptimizations()

        // Collect baseline dumpsys data
        collectBaselineMetrics()

        // Initialize session manager
        sessionManager.initialize()
    }

    private fun applyDeviceOptimizations() {
        // Apply MediaTek Android 15 HEVC workaround
        MediaTekWorkaround.applyIfNeeded(appContainer.decoderSelector())
        
        // Apply device-specific optimizations
        deviceOptimizer.applyOptimizations()
        
        Log.i("MainActivity", "Device optimizations applied: ${android.os.Build.HARDWARE}")
    }

    private fun collectBaselineMetrics() {
        // Collect initial device info and codec capabilities
        lifecycleScope.launch {
            val dumpsysCollector = DumpsysCollector(this@MainActivity)
            val snapshot = dumpsysCollector.collectSessionStart()
            Log.i("MainActivity", "Baseline metrics collected")
        }
    }

    override fun onStart() {
        super.onStart()
        // App becoming visible
        sessionManager.onAppForeground()
    }

    override fun onResume() {
        super.onResume()
        // App actively in foreground
        sessionManager.onAppActive()
    }

    override fun onPause() {
        super.onPause()
        // App going to background
        sessionManager.onAppBackground()
    }

    override fun onStop() {
        super.onStop()
        // App no longer visible
        sessionManager.onAppBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.shutdown()
    }

    // Gamepad/Keyboard input (Activity level - no View hierarchy)
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            sessionManager.getInputProcessor().onGamepadEvent(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        sessionManager.getInputProcessor().onKeyEvent(event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        sessionManager.getInputProcessor().onKeyEvent(event)
        return true
    }
}