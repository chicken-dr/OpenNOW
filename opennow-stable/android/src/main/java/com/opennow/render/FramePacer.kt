package com.opennow.render

import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import com.opennow.diagnostics.TelemetryCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

class FramePacer constructor(
    private val surfaceView: GameSurfaceView,
    private val telemetry: TelemetryCollector,
) {

    private var frameCallback: Choreographer.FrameCallback? = null
    private var running = false

    fun startChoreographerPacing() {
        if (running) return
        running = true
        
        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                
                // Record Choreographer frame timing
                telemetry.recordChoreographerFrame(frameTimeNanos)
                
                // Request next frame
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(frameCallback!!)
        Log.i("FramePacer", "Choreographer pacing started")
    }

    fun stop() {
        running = false
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        Log.i("FramePacer", "Frame pacing stopped")
    }
}