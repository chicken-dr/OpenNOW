package com.closenow.render

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.closenow.session.SessionManager

class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var surfaceReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var sessionManager: SessionManager? = null

    init {
        // Critical: SurfaceView configuration for HWC overlay
        holder.addCallback(this)
        setZOrderMediaOverlay(true)  // Dedicated SurfaceFlinger layer
        holder.setFormat(PixelFormat.RGBA_8888)  // Opaque format
        
        // Touch handling directly on SurfaceView (no View hierarchy)
        setOnTouchListener { _, event ->
            sessionManager?.getInputProcessor()?.onTouchEvent(event)
            true
        }
        
        // Focus for gamepad/keyboard
        isFocusable = true
        isFocusableInTouchMode = true
        
        // Keep screen on during streaming
        setKeepScreenOn(true)
    }

    fun setSessionManager(manager: SessionManager) {
        this.sessionManager = manager
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        val surface = holder.surface
        Log.i("GameSurfaceView", "Surface created: $surface")
        sessionManager?.onSurfaceReady(surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        Log.i("GameSurfaceView", "Surface changed: ${width}x$height")
        sessionManager?.onSurfaceSizeChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        Log.i("GameSurfaceView", "Surface destroyed")
        sessionManager?.onSurfaceDestroyed()
    }

    fun isSurfaceReady(): Boolean = surfaceReady

    fun getSurface(): Surface = holder.surface

    fun getSurfaceSize(): Pair<Int, Int> = surfaceWidth to surfaceHeight
}